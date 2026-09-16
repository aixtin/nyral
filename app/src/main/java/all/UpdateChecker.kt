package io.github.aixtin.nyral

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用更新检查（GitHub Releases 方案）。
 *   - 启动自动检查（同一天只查一次，静默）
 *   - 关于页手动检查（不节流，失败/无新版均有提示）
 *   - 发现新版本弹窗："是否前往更新？" -> 按钮【取消 / 前往下载】
 *   - 点击"前往下载"跳转浏览器打开 GitHub Releases 页面，由用户自行下载
 */
object UpdateChecker {

    // GitHub Releases 最新版接口
    private const val UPDATE_URL = "https://api.github.com/repos/aixtin/nyral/releases/latest"
    // 跳转目标：仓库 Releases 页面
    private const val RELEASE_URL = "https://github.com/aixtin/nyral/releases/latest"

    private val main = Handler(Looper.getMainLooper())

    /** manual=true 来自关于页手动点击；false 为启动自动检查（每次启动均检查）。 */
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
                        main.post { showUpdateDialog(ctx, result.version) }
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

    /** 拉取最新版信息：404 视为仓库暂无 Release，其余异常归为 Error。 */
    private fun fetchLatest(): FetchResult {
        val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
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

    private fun showUpdateDialog(ctx: Context, version: String) {
        if (ctx !is Activity) return
        val (d, box) = Ui.dialog(ctx, "发现新版本 ${version}")
        box.addView(Ui.dialogText(ctx, "检测到新版本，是否前往更新？"))
        box.addView(Ui.primaryBtn(ctx, "前往下载") {
            d.dismiss()
            openReleasePage(ctx)
        })
        val cancelBtn = Ui.dialogCancelBtn(ctx, "取消") { d.dismiss() }
        box.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 10) })
        d.show()
    }

    private fun openReleasePage(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_URL)))
        } catch (e: Exception) {
            toast(ctx, "未找到可用的浏览器")
        }
    }

    private fun toast(c: Context, msg: String) {
        main.post { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }
}
