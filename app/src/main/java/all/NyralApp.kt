package io.github.aixtin.nyral

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局崩溃兜底(2026-10-19)。
 * - 崩溃瞬间: 只做本地快写 filesDir/crash/crash_*.txt(毫秒级, 不赌系统回收前能否完成跨进程 IPC)。
 * - 下次冷启动: onCreate 早期把未导出的崩溃文件用 MediaStore 复制到公共 Download/nyral_crash/,
 *   用户不用打开本 App, 用系统文件管理器即可取到并分享。
 * - 导出成功后删除私有源文件并打标记, 避免重复复制。
 */
class NyralApp : Application() {

    /** 崩溃报告匿名上报端点(零鉴权, 只收纯文本崩溃报告) */
    private val UPLOAD_URL = "https://atin.asia/crash"

    /** ANR 卡死进行中标记: 只有 正常->卡死 边沿写一次, 恢复后重置 */
    private val anrInProgress = AtomicBoolean(false)

    /** 生命周期标记: 记录前台/后台时间, 用于发现"前台无日志闪退" */
    private val lifecyclePrefs by lazy { getSharedPreferences("lifecycle_marker", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        installCrashHandler()
        installAnrWatchdog()
        checkUnexplainedExit()
        installLifecycleMarkers()
        exportPendingCrashes()
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val sb = StringBuilder()
                sb.append("time: ").append(now()).append('\n')
                sb.append("app: ").append(versionName()).append(" (vc ").append(versionCode()).append(")\n")
                sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                sb.append("android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
                sb.append("thread: ").append(t?.name).append('\n')
                sb.append("stack:\n").append(sw.toString())
                sb.append("recent log:\n").append(recentLogs())
                writeLocalCrash(sb.toString())
                LogStore.e("CRASH", t?.name + ": " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            } catch (ignored: Exception) {
            } finally {
                prev?.uncaughtException(t, e)
            }
        }
    }

    /**
     * 异常退出检测(2026-10-20)。
     * 前台使用中进程直接死亡(OOM/native 杀/无异常闪退)不会触发 Java 异常, 也无 crash/anr 日志。
     * 用生命周期标记区分: 上次到过前台且从未经历"退到后台"过渡进程就消失,
     * 且本次冷启动没有任何 crash/anr 文件 -> 记为 UNEXPLAINED_EXIT, 走同一上传链路。
     */
    private fun checkUnexplainedExit() {
        try {
            val p = lifecyclePrefs
            val fg = p.getLong("last_foreground", 0L)
            val bg = p.getLong("last_background", 0L)
            if (fg <= 0L || fg <= bg) return
            val dir = File(filesDir, "crash")
            val hasLog = dir.listFiles()?.any { it.isFile && (it.name.startsWith("crash_") || it.name.startsWith("anr_")) } ?: false
            if (hasLog) return
            val sb = StringBuilder()
            sb.append("type: UNEXPLAINED_EXIT\n")
            sb.append("time: ").append(now()).append('\n')
            sb.append("app: ").append(versionName()).append(" (vc ").append(versionCode()).append(")\n")
            sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            sb.append("android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
            sb.append("last_foreground_ms: ").append(fg).append('\n')
            sb.append("last_background_ms: ").append(bg).append('\n')
            sb.append("note: 上次到前台后未经历后台过渡即进程消失, 且无 crash/anr 日志\n")
            sb.append("recent log:\n").append(recentLogs())
            dir.mkdirs()
            val f = File(dir, "crash_unexplained_" + System.currentTimeMillis() + ".txt")
            f.writeText(sb.toString())
            LogStore.e("NYRAL", "unexplained exit detected, wrote file")
        } catch (ignored: Exception) {
        }
    }

    private fun installLifecycleMarkers() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                lifecyclePrefs.edit().putLong("last_foreground", System.currentTimeMillis()).apply()
            }
            override fun onActivityPaused(activity: Activity) {
                lifecyclePrefs.edit().putLong("last_background", System.currentTimeMillis()).apply()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 主线程 ANR watchdog(2026-10-20)。
     * 系统 ANR 广播普通应用收不到, 采用心跳自监控: 后台线程每 1s 向主线程 post 心跳,
     * 主线程超过 3s 未响应即判定卡死, 抓主线程+全线程堆栈写 anr_*.txt(与崩溃同链路导出/上传)。
     * 只有 正常->卡死 边沿写一次, 卡死恢复后重置, 可记录多次卡死事件。
     */
    private fun installAnrWatchdog() {
        val mainLooper = Looper.getMainLooper()
        val mainHandler = Handler(mainLooper)
        val mainThread = mainLooper.thread
        val tickMs = 1000L
        val thresholdMs = 3000L
        val lastTick = AtomicLong(SystemClock.uptimeMillis())
        mainHandler.post { lastTick.set(SystemClock.uptimeMillis()) }
        Thread {
            while (true) {
                Thread.sleep(tickMs)
                val idle = SystemClock.uptimeMillis() - lastTick.get()
                if (idle > thresholdMs) {
                    // 2026-10-20 息屏冻结误报过滤: 非交互态(息屏/Doze 冻结)主线程停摆不算卡死,
                    // 直接重置计时跳过, 避免解冻后累计 idle 触发延迟误报
                    if (!isScreenInteractive()) {
                        anrInProgress.set(false)
                        mainHandler.post { lastTick.set(SystemClock.uptimeMillis()) }
                        continue
                    }
                    if (anrInProgress.compareAndSet(false, true)) {
                        writeAnrFile(mainThread, idle)
                    }
                } else {
                    anrInProgress.set(false)
                    mainHandler.post { lastTick.set(SystemClock.uptimeMillis()) }
                }
            }
        }.apply { name = "anr-watchdog"; isDaemon = true }.start()
    }

    /** 屏幕是否处于交互态(亮屏)。息屏冻结期间主线程停摆不算 ANR; 查询失败保守返回 true 维持原判定。 */
    private fun isScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (ignored: Exception) {
            true
        }
    }

    private fun writeAnrFile(mainThread: Thread, idleMs: Long) {
        try {
            val sb = StringBuilder()
            sb.append("type: ANR\n")
            sb.append("time: ").append(now()).append('\n')
            sb.append("app: ").append(versionName()).append(" (vc ").append(versionCode()).append(")\n")
            sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            sb.append("android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
            sb.append("main blocked: ").append(idleMs).append("ms\n")
            sb.append("thread: main\n")
            sb.append("stack:\n")
            mainThread.stackTrace.forEach { sb.append("\tat ").append(it).append('\n') }
            sb.append("all threads:\n")
            Thread.getAllStackTraces().forEach { (t, st) ->
                sb.append("-- ").append(t.name).append(" (daemon=").append(t.isDaemon).append(") --\n")
                st.forEach { sb.append("\tat ").append(it).append('\n') }
            }
            sb.append("recent log:\n").append(recentLogs())
            val dir = File(filesDir, "crash")
            dir.mkdirs()
            val f = File(dir, "anr_" + System.currentTimeMillis() + ".txt")
            f.writeText(sb.toString())
            LogStore.e("ANR", "main blocked " + idleMs + "ms, wrote anr file")
        } catch (ignored: Exception) {
        }
    }

    private fun writeLocalCrash(text: String) {
        val dir = File(filesDir, "crash")
        dir.mkdirs()
        val f = File(dir, "crash_" + System.currentTimeMillis() + ".txt")
        f.writeText(text)
    }

    private fun exportPendingCrashes() {
        // 单后台线程: 先上传云端(成功删源), 再对剩余源文件导出公共副本(失败保留源, 下次冷启动重试上传)
        Thread {
            try {
                val dir = File(filesDir, "crash")
                val marked = getSharedPreferences("crash_export", MODE_PRIVATE)
                var files = dir.listFiles()?.filter { it.isFile && (it.name.startsWith("crash_") || it.name.startsWith("anr_")) && it.name.endsWith(".txt") } ?: return@Thread
                if (files.isEmpty()) return@Thread
                // 1) 上传: 成功删源+标记; 失败保留源
                for (f in files) {
                    if (marked.getBoolean("uploaded_" + f.name, false)) continue
                    if (uploadCrash(f)) {
                        marked.edit().putBoolean("uploaded_" + f.name, true).apply()
                        f.delete()
                    }
                }
                // 2) 剩余源(上传失败/未尝试)导出副本到公共 Download/nyral_crash 供用户手动取, 不删源
                files = dir.listFiles()?.filter { it.isFile && (it.name.startsWith("crash_") || it.name.startsWith("anr_")) && it.name.endsWith(".txt") } ?: return@Thread
                for (f in files) {
                    val key = "done_" + f.name
                    if (marked.getBoolean(key, false)) continue
                    if (exportToDownload(f)) marked.edit().putBoolean(key, true).apply()
                }
            } catch (ignored: Exception) {
            }
        }.apply { name = "crash-export-upload"; isDaemon = true }.start()
    }

    private fun recentLogs(max: Int = 30): String {
        return try {
            LogStore.snapshot(null).takeLast(max).joinToString("\n") { it.line() }
        } catch (e: Exception) { "log unavailable" }
    }

    /**
     * 弱网上传(2026-10-20): 放宽超时 + 最多 attempts 次尝试, 指数退避(2s/4s/8s);
     * 全部失败返回 false, 由调用方保留源文件, 下次冷启动继续重试。
     */
    private fun uploadCrash(f: File, attempts: Int = 4): Boolean {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try {
                val conn = java.net.URL(UPLOAD_URL).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                val body = f.readText()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) return true
                last = RuntimeException("http " + conn.responseCode)
                Log.w("NyralApp", "upload crash http " + conn.responseCode + " (attempt " + (i + 1) + "/" + attempts + ")")
            } catch (e: Exception) {
                last = e
                Log.w("NyralApp", "upload crash failed (attempt " + (i + 1) + "/" + attempts + "): " + e.message)
            }
            if (i < attempts - 1) Thread.sleep(2000L shl i) // 2s, 4s, 8s 退避
        }
        Log.w("NyralApp", "upload crash give up after " + attempts + " attempts: " + (last?.message ?: "unknown"))
        return false
    }

    private fun exportToDownload(src: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/nyral_crash")
                }
                val uri: Uri? = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) return false
                contentResolver.openOutputStream(uri)?.use { os ->
                    src.inputStream().use { it.copyTo(os) }
                }
                true
            } else {
                // Android 9 及以下: 直接写公共 Download(需 WRITE_EXTERNAL_STORAGE, 尽力而为)
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "nyral_crash")
                if (!dir.exists()) dir.mkdirs()
                src.copyTo(File(dir, src.name), overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.w("NyralApp", "export crash failed: " + e.message)
            false
        }
    }


    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun versionCode(): Int = try {
        if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        else packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

}
