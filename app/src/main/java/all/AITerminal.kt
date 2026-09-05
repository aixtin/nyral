package io.github.aixtin.nyral

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 悬浮迷你终端（透明终端 MVP）。
 * - 形态：半透明黑底白字，固定左上角，最多 8 行，不挡触摸(FLAG_NOT_TOUCHABLE)；
 * - 数据源：LocalEngine 执行事件流，由 MainActivity 的 Callback 转发到 AITerminal.push()；
 * - 载体：前台服务 + TYPE_APPLICATION_OVERLAY 悬浮窗，切到其他 App 也可见实时 AI 动作；
 * - 开关：设置页「AI 悬浮终端」；悬浮窗权限在开关处引导授予。
 */
object AITerminal {

    const val LINES = 8

    /** 单行最大字符数(超出截断加省略号, 防长输出刷爆悬浮窗) */
    private const val MAX_LINE_LEN = 110

    private const val PREF = "ai_terminal"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    var serviceRunning = false
        internal set

    /** 服务未运行时暂存的环形缓冲(最大 LINES 行), 服务拉起后回放, 避免开关打开瞬间丢事件 */
    private val buffer = ArrayDeque<String>()

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 事件推送入口（工作线程可调，幂等安全）。 */
    @Synchronized
    fun push(level: String, text: String) {
        val line = formatLine(level, text) ?: return
        if (serviceRunning) {
            AITerminalService.append(line)
        } else {
            buffer.addLast(line)
            while (buffer.size > LINES) buffer.removeFirst()
        }
    }

    /** 服务 onCreate 时回放暂存内容并清空 */
    @Synchronized
    fun drain(): List<String> {
        val out = buffer.toList()
        buffer.clear()
        return out
    }

    /** 事件格式化为单行: 时间 + 类型 + 内容 */
    private fun formatLine(level: String, text: String): String? {
        val head = when (level) {
            "thinking"    -> "[思考]"
            "tool"        -> "[工具]"
            "tool_result" -> "[结果]"
            "delta"       -> "[输出]"
            "done"        -> "[完成]"
            "error"       -> "[错误]"
            "stop"        -> "[停止]"
            else          -> "[AI]"
        }
        val body = text?.replace('\n', ' ')?.trim() ?: ""
        if (body.isEmpty()) return null
        val cut = if (body.length > MAX_LINE_LEN) body.take(MAX_LINE_LEN) + "…" else body
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return "$t $head $cut"
    }
}

/**
 * 承载悬浮终端的前台服务：创建悬浮窗 + 常驻通知保活。
 * 生命周期由设置页开关控制（start/stop），与 AI 任务本身解耦。
 */
class AITerminalService : Service() {

    companion object {
        private const val CHANNEL_ID = "ai_terminal_running"
        private const val NOTIF_ID = 1002

        @Volatile
        private var wm: WindowManager? = null

        @Volatile
        private var overlayView: ViewGroup? = null

        @Volatile
        private var tv: TextView? = null

        /** 服务内行缓冲(进程级, 服务重建不丢已显示内容) */
        private val lines = ArrayDeque<String>()

        fun start(context: Context) {
            val i = Intent(context, AITerminalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AITerminalService::class.java))
        }

        /** 追加一行(线程安全, 内部切主线程更新 UI) */
        fun append(line: String) {
            val handler = mainHandler()
            handler.post {
                synchronized(lines) {
                    lines.addLast(line)
                    while (lines.size > AITerminal.LINES) lines.removeFirst()
                }
                tv?.text = synchronized(lines) { lines.joinToString("\n") }
            }
        }

        /**
         * 悬浮窗显隐门控: DA 前台(应用内)隐藏, 切到其他 APP/回桌面显示。
         * 由 MainActivity 的 Application 级生命周期回调驱动, 任何线程可调。
         */
        fun setOverlayVisible(visible: Boolean) {
            val v = overlayView ?: return
            mainHandler().post {
                try { v.visibility = if (visible) View.VISIBLE else View.GONE } catch (e: Exception) {}
            }
        }

        /**
         * 悬浮窗 UI 快照(供 DebugServer /v1/ui 读取, 非主线程可调):
         * 切主线程读真实 View 状态, 最多等 1s, 读不到则返回含 has_view=false 的对象。
         */
        fun overlayInfo(): org.json.JSONObject {
            val result = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>()
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler().post {
                val o = org.json.JSONObject()
                val v = overlayView
                o.put("has_view", v != null)
                if (v != null) {
                    o.put("visible", v.visibility == View.VISIBLE)
                    o.put("width", v.width)
                    o.put("height", v.height)
                    o.put("measured_width", v.measuredWidth)
                    o.put("measured_height", v.measuredHeight)
                }
                val t = tv
                o.put("line_count", if (t != null) t.lineCount else 0)
                result.set(o)
                latch.countDown()
            }
            try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
            return result.get() ?: org.json.JSONObject().put("error", "overlay snapshot timeout")
        }

        private fun mainHandler(): Handler = Handler(Looper.getMainLooper())
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        addOverlay()
        AITerminal.serviceRunning = true
        // 回放服务未运行期间暂存的事件
        AITerminal.drain().forEach { AITerminalService.append(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun addOverlay() {
        // 悬浮窗权限未授予时不加窗(静默跳过, 开关处已引导授权; 避免崩溃)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // 不聚焦 + 不挡触摸 + 越界布局(避开状态栏)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val wmSvc = getSystemService(WINDOW_SERVICE) as WindowManager

        val terminal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xB3000000.toInt())   // 半透明黑
                cornerRadius = 12f
            }
        }

        val label = TextView(this).apply {
            text = "DA AI 终端"
            textSize = 10f
            setTextColor(0xFF9A9A9A.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(4))
        }
        val body = TextView(this).apply {
            text = "待命中…"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
            setMaxLines(AITerminal.LINES)
        }
        terminal.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        terminal.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 竖向窄面板: 宽 ~250dp(小屏时取屏宽70%), 高自适应; 左上角贴边
        val width = minOf(dp(250), (resources.displayMetrics.widthPixels * 0.7f).toInt())
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(4)
        lp.y = dp(4)

        try {
            wmSvc.addView(terminal, lp)
            AITerminalService.wm = wmSvc
            AITerminalService.overlayView = terminal
            AITerminalService.tv = body
            // 服务于 DA 前台时启动需按当前前台状态校正一次显隐(DA 内隐藏, 切走/桌面显示)
            try { TerminalGate.refreshTerminalOverlay() } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("AITerminal", "悬浮窗添加失败: ${e.message}")
        }
    }

    private fun removeOverlay() {
        val w = wm ?: return
        val v = overlayView ?: return
        try {
            w.removeView(v)
        } catch (e: Exception) {
            // view 可能已移除
        }
        wm = null
        overlayView = null
        tv = null
    }

    override fun onDestroy() {
        super.onDestroy()
        AITerminal.serviceRunning = false
        removeOverlay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun startForegroundCompat() {
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (e2: Exception) {
                android.util.Log.e("AITerminal", "startForeground 失败: ${e2.message}")
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "AI 悬浮终端", NotificationManager.IMPORTANCE_LOW)
            ch.description = "显示 AI 实时执行状态的悬浮迷你终端"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("AI 悬浮终端")
            .setContentText("实时显示 AI 执行状态")
            .setOngoing(true)
            .setContentIntent(contentIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setChannelId(CHANNEL_ID)
        return b.build()
    }
}
