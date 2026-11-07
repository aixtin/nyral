package io.github.aixtin.nyral

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用更新检查（GitHub + Gitee 双通道）。
 *   - 启动自动检查（同一天只查一次，静默）
 *   - 关于页手动检查（不节流，失败/无新版均有提示）
 *   - 发现新版本弹窗："是否前往更新？" -> 按钮【取消 / 前往下载】
 *   - 点击"前往下载"：并行探测 GitHub/Gitee 下载通道，选可达且延迟低的一侧，
 *     交给系统 DownloadManager 后台下载（通知栏可见进度，下载完成可直接打开安装）。
 *     国内网络 GitHub 常不通/极慢，Gitee 通道兜底，双通道探测自动选优。
 */
object UpdateChecker {

    // 双源最新版接口：GitHub 优先，失败自动回退 Gitee
    private val UPDATE_URLS = listOf(
        "https://api.github.com/repos/aixtin/nyral/releases/latest",
        "https://gitee.com/api/v5/repos/aixtin/Nyral/releases/latest"
    )
    // 双通道 APK 下载地址模板（%s 为 tag，如 v2.9.0）
    private val DOWNLOAD_URLS = listOf(
        "https://github.com/aixtin/nyral/releases/download/%s/nyral-%s.apk",
        "https://gitee.com/aixtin/Nyral/releases/download/%s/nyral-%s.apk"
    )
    // 「此版本不再提醒」持久化
    private const val PREFS = "app_prefs"
    private const val KEY_IGNORED_VERSION = "update_ignored_version"
    // 通道探测超时
    private const val PROBE_TIMEOUT_MS = 4000L

    private val main = Handler(Looper.getMainLooper())

    /** manual=true 来自关于页手动点击；false 为启动自动检查。 */
    fun check(ctx: Context, manual: Boolean) {
        Thread {
            when (val result = fetchLatest()) {
                is FetchResult.NoRelease -> {
                    if (manual) toast(ctx, "暂无可用更新")
                }
                is FetchResult.Error -> {
                    if (manual) toast(ctx, "检查失败，请稍后重试")
                }
                is FetchResult.Ok -> {
                    val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
                    if (compareVersion(result.version, current) > 0) {
                        val ignored = ignoredVersion(ctx)
                        if (result.version == ignored) {
                            // 该版本已被用户选择「此版本不再提醒」: 自动检查静默跳过, 手动检查给出提示
                            if (manual) toast(ctx, "该版本已设置不再提醒，可前往更新页查看")
                        } else {
                            main.post { showUpdateDialog(ctx, result.version) }
                        }
                    } else {
                        if (manual) toast(ctx, "已是最新版本")
                    }
                }
            }
        }.start()
    }

    private sealed class FetchResult {
        object NoRelease : FetchResult()
        object Error : FetchResult()
        data class Ok(val version: String) : FetchResult()
    }

    /** 拉取最新版信息：GitHub 优先，异常回退 Gitee；404 视为仓库暂无 Release。 */
    private fun fetchLatest(): FetchResult {
        for (url in UPDATE_URLS) {
            val r = fetchLatestFrom(url)
            if (r is FetchResult.Ok) return r
            if (r is FetchResult.NoRelease) return r
        }
        return FetchResult.Error
    }

    private fun fetchLatestFrom(url: String): FetchResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 404) return FetchResult.NoRelease
            if (code != 200) return FetchResult.Error
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name", "").trim()
            return if (tag.isEmpty()) FetchResult.Error else FetchResult.Ok(tag)
        } catch (e: Exception) {
            return FetchResult.Error
        } finally {
            conn.disconnect()
        }
    }

    /** 版本号比较：a>b 返回 1，相等 0，a<b 返回 -1。支持 v1.2 / 1.2.0 等常见 tag 形式。 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.replace(Regex("[^0-9.]"), "").split(".").filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val pb = b.replace(Regex("[^0-9.]"), "").split(".").filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 用户选择「此版本不再提醒」的版本号, 无则 null。 */
    private fun ignoredVersion(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IGNORED_VERSION, null)

    private fun ignoreVersion(c: Context, version: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_IGNORED_VERSION, version).apply()
    }

    private fun showUpdateDialog(ctx: Context, version: String) {
        if (ctx !is Activity) return
        val (d, box) = Ui.dialog(ctx, "发现新版本 ${version}")
        box.addView(Ui.dialogText(ctx, "检测到新版本，是否前往更新？"))
        box.addView(Ui.primaryBtn(ctx, "前往下载") {
            d.dismiss()
            startBestDownload(ctx, version)
        })
        val ignoreBtn = Ui.dialogCancelBtn(ctx, "此版本不再提醒") {
            ignoreVersion(ctx, version)
            d.dismiss()
            toast(ctx, "已设置 v$version 不再提醒")
        }
        box.addView(ignoreBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10) })
        val cancelBtn = Ui.dialogCancelBtn(ctx, "取消") { d.dismiss() }
        box.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10) })
        d.show()
    }

    /**
     * 双通道选优下载：并行探测 GitHub/Gitee 的 APK 直链（HEAD + 计时），
     * 选可达且延迟最低的一侧，交给系统 DownloadManager 后台下载。
     */
    private fun startBestDownload(ctx: Context, version: String) {
        val tag = version.trim()
        val candidates = DOWNLOAD_URLS.map { String.format(Locale.US, it, tag, tag) }
        val latch = CountDownLatch(candidates.size)
        val best = AtomicReference<Pair<String, Long>?>(null) // (url, latencyMs)
        candidates.forEach { url ->
            Thread {
                val lat = probe(url)
                if (lat != null) {
                    synchronized(best) {
                        val cur = best.get()
                        if (cur == null || lat < cur.second) best.set(url to lat)
                    }
                }
                latch.countDown()
            }.start()
        }
        Thread {
            latch.await(PROBE_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS)
            val chosen = best.get()
            if (chosen == null) {
                toast(ctx, "下载通道均不可达，请稍后重试")
                return@Thread
            }
            if (enqueueDownload(ctx, chosen.first, tag)) {
                toast(ctx, "开始下载 v$tag，进度见通知栏")
            } else {
                toast(ctx, "下载启动失败，请稍后重试")
            }
        }.start()
    }

    /** HEAD 探测通道可达性与延迟, 返回耗时 ms, 失败返回 null。 */
    private fun probe(url: String): Long? {
        val start = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) System.currentTimeMillis() - start else null
        } catch (e: Exception) {
            null
        }
    }

    /** 系统 DownloadManager 下载到公共 Download 目录, 通知栏可见, 完成可直接打开安装。 */
    private fun enqueueDownload(ctx: Context, url: String, tag: String): Boolean {
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("Nyral v$tag")
                .setDescription("正在下载更新包")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "nyral-$tag.apk")
                .setMimeType("application/vnd.android.package-archive")
            dm.enqueue(req) >= 0
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(c: Context, msg: String) {
        main.post { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }
}
