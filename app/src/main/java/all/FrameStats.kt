package io.github.aixtin.nyral

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Window
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内渲染帧统计(2026-11-17)
 *
 * 场景: HUAWEI SNE-AL00(Android 10 / SDK 29) 无 root 无 ADB, 无法执行
 * `dumpsys gfxinfo ... framestats`, 只能由 App 自己采每一帧的耗时拆解, 再走
 * filesDir/crash + crash-export-upload 链路送出来, 用于判定
 * `HardwareRenderer.nSetStopped` 那 5s 绘制卡顿是布局重排(LAYOUT/ANIMATION 高)
 * 还是合成等待(SYNC_DELAY/SWAP_BUFFERS 高)。
 *
 * 约束: 只读采集, 不改设置、不依赖 root/Shizuku/Debug 权限; 缓冲定长环形,
 *       常驻内存上限 900 帧(约 15s@60Hz), 不做任何磁盘写除非显式导出。
 */
object FrameStats {

    private const val CAP = 900
    private const val STAGE = 8

    // FrameMetrics 计时项索引(其公开常量取值固定, 直接用数字避免不同 SDK 常量名差异):
    // 0 total / 1 input / 2 animation / 3 layout+measure / 4 draw / 5 sync(delay) / 6 command issue / 7 swap buffers
    private val ORDER = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private val NAMES = arrayOf("total", "input", "anim", "layout", "draw", "sync", "cmd", "swap")

    private class Sample(val tag: String, val v: LongArray)

    private val buf = ArrayDeque<Sample>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var win: Window? = null
    private var winTag: String = ""

    private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        try {
            val v = LongArray(STAGE)
            for (i in 0 until STAGE) v[i] = m.getMetric(ORDER[i])
            val s = Sample(winTag, v)
            synchronized(buf) {
                if (buf.size >= CAP) buf.removeFirst()
                buf.addLast(s)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 挂到窗口: 聊天页传 "main", 诊断面板传 "diag"。同一窗口重复调用自动跳过。 */
    fun attach(w: Window, tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (win === w) return
        detach()
        try {
            val t = HandlerThread("frame-stats").apply { start() }
            thread = t
            handler = Handler(t.looper)
            win = w
            winTag = tag
            w.addOnFrameMetricsAvailableListener(listener, handler)
        } catch (ignored: Throwable) {
            detach()
        }
    }

    fun detach() {
        try { win?.removeOnFrameMetricsAvailableListener(listener) } catch (ignored: Throwable) { }
        try { handler?.looper?.quitSafely() } catch (ignored: Throwable) { }
        thread?.quitSafely()
        win = null
        handler = null
        thread = null
        winTag = ""
    }

    fun count(tag: String? = null): Int = synchronized(buf) {
        if (tag == null) buf.size else buf.count { it.tag == tag }
    }

    private fun ms(ns: Long): String = String.format(Locale.US, "%.2f", ns / 1e6)

    /** 生成可直接塞进崩溃/ANR 文件或独立报告的纯文本统计段。 */
    fun snapshot(tag: String? = null): String {
        val list: List<Sample> = synchronized(buf) {
            if (tag == null) buf.toList() else buf.filter { it.tag == tag }
        }
        val head = "frame stats (" + (tag ?: "all") + "): "
        if (list.isEmpty()) return head + "无样本(窗口未挂载或帧回调未触发)\n"
        val totals = list.map { it.v[0] }.sorted()
        fun percentile(p: Double): Long {
            val idx = Math.round((totals.size - 1) * p).toInt().coerceIn(0, totals.size - 1)
            return totals[idx]
        }
        val sb = StringBuilder()
        sb.append(head).append("samples=").append(list.size).append('\n')
        sb.append("  total ms: mean=").append(ms(list.sumOf { it.v[0] } / list.size))
            .append(" p50=").append(ms(percentile(0.50)))
            .append(" p90=").append(ms(percentile(0.90)))
            .append(" p99=").append(ms(percentile(0.99)))
            .append(" max=").append(ms(totals.last())).append('\n')
        val j17 = list.count { it.v[0] > 16_666_667L }
        val j33 = list.count { it.v[0] > 33_333_333L }
        val j100 = list.count { it.v[0] > 100_000_000L }
        fun ratio(n: Int): String = String.format(Locale.US, "%.1f%%", n * 100.0 / list.size)
        sb.append("  jank: >16.7ms=").append(j17).append('(').append(ratio(j17))
            .append(") >33ms=").append(j33).append('(').append(ratio(j33))
            .append(") >100ms=").append(j100).append('(').append(ratio(j100)).append(")\n")
        sb.append("  stage mean ms:")
        for (i in 1 until STAGE) {
            sb.append(' ').append(NAMES[i]).append('=').append(ms(list.sumOf { it.v[i] } / list.size))
        }
        sb.append('\n')
        sb.append("  slowest 5 frames:\n")
        list.sortedByDescending { it.v[0] }.take(5).forEach { s ->
            sb.append("    ").append(NAMES[0]).append('=').append(ms(s.v[0]))
            for (i in 1 until STAGE) sb.append(' ').append(NAMES[i]).append('=').append(ms(s.v[i]))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 写独立帧报告到 filesDir/crash/frame_*.txt, 由 crash-export-upload 上传/导出。 */
    @Suppress("DEPRECATION")
    fun writeReport(c: Context, tag: String? = null): File? {
        return try {
            val vn = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
            val vc = try {
                if (Build.VERSION.SDK_INT >= 28) c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()
                else c.packageManager.getPackageInfo(c.packageName, 0).versionCode
            } catch (e: Exception) { 0 }
            val sb = StringBuilder()
            sb.append("type: FRAME\n")
            sb.append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            sb.append("app: ").append(vn).append(" (vc ").append(vc).append(")\n")
            sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            sb.append("android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
            sb.append("scope: ").append(tag ?: "all").append('\n')
            sb.append(snapshot(tag))
            val dir = File(c.filesDir, "crash")
            dir.mkdirs()
            val f = File(dir, "frame_" + System.currentTimeMillis() + ".txt")
            f.writeText(sb.toString())
            f
        } catch (ignored: Throwable) {
            null
        }
    }
}
