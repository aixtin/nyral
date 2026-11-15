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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用更新检查与版本详情（GitHub + Gitee 双仓库）。
 *
 * 数据源策略：两个仓库的 Release 内容完全一致，因此不做手工切换，改为"竞速测源"：
 * 两个仓库的 Release 列表接口并行发起请求，谁先成功返回就用谁的日志渲染版本详情，
 * 并记录该侧实测耗时（快 = 网速好）。这样国内网络 GitHub 不通时自动落到 Gitee，
 * 海外网络则优先吃到 GitHub，用户无需关心。
 *
 * - 自动检查（启动/回前台 + 前台每 6 小时轮询，6 小时网络节流）
 * - 自动提示为 Toast 轻提示：同版本 24 小时内最多一次，新版本号首次出现立即提示
 * - 检查结果整包缓存到本地（1 小时新鲜期），更新页首屏读缓存秒开再后台刷新
 * - 下载渠道由用户手动选择：官方直链 / GitHub / Gitee（三者同一份包）
 */
object UpdateChecker {

    /** 两个仓库：api 取 Release 列表，page 为"全部发布记录"外链，dl 为 APK 直链模板。 */
    enum class Source(val label: String, val api: String, val page: String, val dl: String) {
        GITHUB(
            "GitHub",
            "https://api.github.com/repos/aixtin/nyral/releases?per_page=30",
            "https://github.com/aixtin/nyral/releases",
            "https://github.com/aixtin/nyral/releases/download/%s/nyral-%s.apk"
        ),
        GITEE(
            "Gitee",
            "https://gitee.com/api/v5/repos/aixtin/Nyral/releases?per_page=30&direction=desc&page=1",
            "https://gitee.com/aixtin/Nyral/releases",
            "https://gitee.com/aixtin/Nyral/releases/download/%s/nyral-%s.apk"
        )
    }

    /** 下载渠道：官网直链恒定指向最新版。 */
    enum class Channel { OFFICIAL, GITHUB, GITEE }

    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val publishedAt: String,
        val htmlUrl: String
    )

    /** 一次成功拉取的结果：来源 + 实测耗时 + 版本列表（按版本号降序）。 */
    data class Snapshot(val source: Source, val latencyMs: Long, val releases: List<Release>)

    private const val OFFICIAL_URL = "https://atin.asia/dl/nyral-latest.apk"

    private const val PREFS = "app_prefs"
    private const val KEY_IGNORED_VERSION = "update_ignored_version"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"
    private const val KEY_LAST_TOAST_AT = "update_last_toast_at"
    private const val KEY_LAST_TOAST_VER = "update_last_toast_version"
    /** 整包缓存：Release 列表 JSON + 抓取时间 */
    private const val KEY_SNAP = "update_snapshot_json"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val TOAST_INTERVAL_MS = 24 * 60 * 60 * 1000L
    /** 缓存新鲜期：超过后仍先展示旧数据，同时后台刷新 */
    const val CACHE_TTL_MS = 60 * 60 * 1000L
    /** 竞速总超时：超过即判定这一次拉取失败 */
    private const val RACE_TIMEOUT_MS = 9000L
    private const val NET_TIMEOUT_MS = 7000

    private val main = Handler(Looper.getMainLooper())

    // ============================ 对外：检查是否更新 ============================

    /** manual=true 来自关于页手动点击；false 为启动/前台自动检查。 */
    fun check(ctx: Context, manual: Boolean) {
        Thread {
            val snap = race()
            if (snap == null || snap.releases.isEmpty()) {
                if (manual) toast(ctx, "检查失败，请稍后重试")
                return@Thread
            }
            saveCache(ctx, snap)
            val version = latestVersion(snap) ?: run {
                if (manual) toast(ctx, "暂无可用版本")
                return@Thread
            }
            val current = currentVersion(ctx)
            if (compareVersion(version, current) > 0) {
                if (version == ignoredVersion(ctx)) {
                    if (manual) toast(ctx, "该版本已设置不再提醒，可前往更新页查看")
                } else if (manual) {
                    main.post { showUpdateDialog(ctx, version) }
                } else {
                    toastUpdateAvailable(ctx, version)
                }
            } else {
                if (manual) toast(ctx, "已是最新版本")
            }
        }.start()
    }

    /**
     * 前台热检测入口（MainActivity.onResume 调用）：距上次检查不足 6 小时直接返回，
     * 否则记账后发起一次静默检查。冷启动时 last=0 会立刻检查一次。
     */
    fun checkIfDue(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = sp.getLong(KEY_LAST_CHECK_AT, 0L)
        // 时钟回拨(last > now)时放行, 避免被永久静默
        if (last in 1..now && now - last < CHECK_INTERVAL_MS) return
        sp.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
        check(ctx, false)
    }

    /** 前台轮询任务: 应用长时间留在前台(不切后台)时也能按 6 小时周期热检测。 */
    private var pollTask: Runnable? = null

    /** MainActivity.onResume 调用: 回前台即检一次(内部 6 小时节流), 并启动前台轮询; 重复调用无副作用。 */
    fun startForegroundPolling(ctx: Context) {
        val app = ctx.applicationContext
        checkIfDue(app)
        if (pollTask != null) return
        val task = object : Runnable {
            override fun run() {
                checkIfDue(app)
                main.postDelayed(this, CHECK_INTERVAL_MS + 60_000L)
            }
        }
        pollTask = task
        main.postDelayed(task, CHECK_INTERVAL_MS + 60_000L)
    }

    /** MainActivity.onStop 调用: 停止前台轮询, 避免退到后台空跑。 */
    fun stopForegroundPolling() {
        pollTask?.let { main.removeCallbacks(it) }
        pollTask = null
    }

    // ============================ 对外：版本详情页 ============================

    /**
     * 更新页刷新入口：并行竞速两个仓库，先成功者胜出；成功则刷新本地缓存。
     * 回调在主线程，snapshot 为 null 表示本次网络全失败（调用方保留已有缓存展示）。
     */
    fun refresh(ctx: Context, cb: (Snapshot?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val snap = race()
            if (snap != null) saveCache(app, snap)
            val cb2: (Snapshot?) -> Unit = { cb(it) }
            main.post { cb2(snap) }
        }.start()
    }

    /** 读本地缓存；返回 snapshot 与缓存写入时间（用于判断是否新鲜）。 */
    fun readCache(ctx: Context): Pair<Snapshot, Long>? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SNAP, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val src = runCatching { Source.valueOf(o.optString("source", Source.GITHUB.name)) }
                .getOrDefault(Source.GITHUB)
            val at = o.optLong("at", 0L)
            val arr = o.optJSONArray("releases") ?: JSONArray()
            val list = ArrayList<Release>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                list.add(
                    Release(
                        r.optString("tag", ""),
                        r.optString("name", ""),
                        r.optString("body", ""),
                        r.optString("publishedAt", ""),
                        r.optString("htmlUrl", "")
                    )
                )
            }
            if (list.isEmpty()) return null
            Snapshot(src, o.optLong("latency", 0L), list) to at
        } catch (e: Exception) {
            null
        }
    }

    /** 当前已安装版本名，取不到时给 "0"。 */
    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** 列表里版本号最大的 tag（列表已在解析阶段过滤 draft / pre-release 并按版本降序）。 */
    fun latestVersion(snap: Snapshot): String? = snap.releases
        .filter { it.tag.isNotEmpty() }
        .maxWithOrNull(Comparator { a, b -> compareVersion(a.tag, b.tag) })
        ?.tag

    /** 指定渠道的下载直链；历史版本不提供官方直链（官方直链恒定指向最新版）。 */
    fun downloadUrl(channel: Channel, tag: String): String = when (channel) {
        Channel.OFFICIAL -> OFFICIAL_URL
        Channel.GITHUB -> String.format(Locale.US, Source.GITHUB.dl, tag, tag)
        Channel.GITEE -> String.format(Locale.US, Source.GITEE.dl, tag, tag)
    }

    /** 交给系统 DownloadManager 后台下载（通知栏进度，完成可直接安装）。 */
    fun download(ctx: Context, channel: Channel, tag: String): Boolean {
        val url = downloadUrl(channel, tag)
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("Nyral $tag")
                .setDescription("正在下载更新包")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "nyral-$tag.apk")
                .setMimeType("application/vnd.android.package-archive")
            dm.enqueue(req) >= 0
        } catch (e: Exception) {
            false
        }
    }

    /** 打开独立更新页。 */
    fun openPage(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(ctx, UpdateActivity::class.java).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    // ============================ 数据源竞速 ============================

    /**
     * 两个仓库并行请求，先成功者胜出（这就是"测速选源"）：
     * 谁先返回结果，谁的网络就更快，用它渲染版本详情。
     * 另一侧无论快慢都只作为失败兜底，因此不必等待慢的一方。
     */
    private fun race(): Snapshot? {
        val lock = Any()
        val winner = AtomicReference<Snapshot?>(null)
        val done = AtomicInteger(0)
        val total = Source.values().size
        for (src in Source.values()) {
            Thread {
                val t0 = System.currentTimeMillis()
                val list = fetchReleases(src)
                val cost = System.currentTimeMillis() - t0
                if (!list.isNullOrEmpty()) {
                    synchronized(lock) {
                        if (winner.get() == null) winner.set(Snapshot(src, cost, list))
                    }
                }
                done.incrementAndGet()
            }.apply { isDaemon = true }.start()
        }
        val deadline = System.currentTimeMillis() + RACE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (winner.get() != null || done.get() >= total) break
            try {
                Thread.sleep(25)
            } catch (e: InterruptedException) {
                break
            }
        }
        return winner.get()
    }

    /** 拉取某个仓库的 Release 列表并按版本号降序；失败返回 null。 */
    private fun fetchReleases(src: Source): List<Release>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(src.api).openConnection() as HttpURLConnection
            conn.connectTimeout = NET_TIMEOUT_MS
            conn.readTimeout = NET_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Nyral-Android")
            if (conn.responseCode != 200) return null
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val text = raw.trimStart()
            // GitHub 返回对象数组；Gitee 同样返回数组，兼容单对象（文章/兜底接口）
            val arr = when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> JSONArray().put(JSONObject(text))
                else -> return null
            }
            val list = ArrayList<Release>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) continue
                val tag = o.optString("tag_name", "").trim()
                if (tag.isEmpty()) continue
                val at = o.optString("published_at", "").ifBlank { o.optString("created_at", "") }
                val html = o.optString("html_url", "").ifBlank { src.page }
                list.add(
                    Release(
                        tag = tag,
                        name = o.optString("name", "").trim().ifBlank { tag },
                        body = o.optString("body", ""),
                        publishedAt = at,
                        htmlUrl = html
                    )
                )
            }
            if (list.isEmpty()) return null
            // 按版本号数值降序（不依赖接口排序，Gitee 按 tag 字符串排会失真）
            list.sortWith { a, b -> compareVersion(b.tag, a.tag) }
            list
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 版本号比较：a>b 返回 1，相等 0，a<b 返回 -1。支持 v1.2 / 1.2.0 等 tag 形式。 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.replace(Regex("[^0-9.]"), "").split(".").filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val pb = b.replace(Regex("[^0-9.]"), "").split(".").filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    // ============================ 缓存 ============================

    private fun saveCache(ctx: Context, snap: Snapshot) {
        try {
            val arr = JSONArray()
            snap.releases.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("tag", r.tag)
                        .put("name", r.name)
                        .put("body", r.body)
                        .put("publishedAt", r.publishedAt)
                        .put("htmlUrl", r.htmlUrl)
                )
            }
            val o = JSONObject()
                .put("source", snap.source.name)
                .put("latency", snap.latencyMs)
                .put("at", System.currentTimeMillis())
                .put("releases", arr)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SNAP, o.toString()).apply()
        } catch (e: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    // ============================ 提示与弹窗 ============================

    /**
     * 自动检查命中新版本: Toast 轻提示, 引导用户自行前往更新页。
     * 节流: 新版本号首次出现立刻提示; 同一版本 24 小时内最多一次。
     */
    private fun toastUpdateAvailable(ctx: Context, version: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastAt = sp.getLong(KEY_LAST_TOAST_AT, 0L)
        val lastVer = sp.getString(KEY_LAST_TOAST_VER, null)
        val clockOk = lastAt in 1..now
        if (lastVer == version && clockOk && now - lastAt < TOAST_INTERVAL_MS) return
        sp.edit().putLong(KEY_LAST_TOAST_AT, now).putString(KEY_LAST_TOAST_VER, version).apply()
        val app = ctx.applicationContext
        main.post {
            Toast.makeText(app, "发现新版本 $version，可在「关于 → 检查更新」中查看更新内容", Toast.LENGTH_LONG).show()
        }
    }

    /** 用户选择「此版本不再提醒」的版本号, 无则 null。 */
    private fun ignoredVersion(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IGNORED_VERSION, null)

    private fun ignoreVersion(c: Context, version: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_IGNORED_VERSION, version).apply()
    }

    private fun showUpdateDialog(ctx: Context, version: String) {
        if (ctx !is Activity) return
        val (d, box) = Ui.dialog(ctx, "发现新版本 $version")
        box.addView(Ui.dialogText(ctx, "检测到新版本，是否查看更新内容？"))
        box.addView(Ui.primaryBtn(ctx, "查看更新内容") {
            d.dismiss()
            openPage(ctx)
        })
        val ignoreBtn = Ui.dialogCancelBtn(ctx, "此版本不再提醒") {
            ignoreVersion(ctx, version)
            d.dismiss()
            toast(ctx, "已设置 $version 不再提醒")
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

    private fun toast(c: Context, msg: String) {
        main.post { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }
}
