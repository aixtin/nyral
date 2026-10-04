package io.github.aixtin.nyral

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 危险工具硬门禁确认桥 v3(2026-10-04):
 * - 三档决策在 SecurityConfig.needsConfirm, 本对象只负责"确认通道 + 并发队列"
 * - 前台通道: 消息流气泡确认(每申请独立气泡, 队列 FIFO 排队延伸, ✔/✘ 决策后留态)
 * - 后台通道: 系统通知(允许/拒绝两个 Action, PendingIntent FLAG_IMMUTABLE, 2 分钟超时自动拒绝)
 * - 并发: 多条申请同时命中时排队, 各自独立 latch, 决策后各自放行/拒绝
 */
object SecurityUi {
    @Volatile
    private var activity: Activity? = null

    data class PendingRequest(
        val requestId: String,
        val tool: String,
        val arg: String,
        val risk: SecurityConfig.RiskLevel,
        val channel: String,            // "bubble" / "notification"
        val latch: CountDownLatch,
        val result: AtomicReference<Boolean?>,
        val deadlineMs: Long
    ) {
        @Volatile var status: String = "PENDING"  // PENDING / ALLOWED / REJECTED / TIMEOUT
    }

    private val queue = ConcurrentLinkedQueue<PendingRequest>()
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val counter = AtomicLong(0)

    /** 主界面回调: 队列增删/状态变化时通知(消息流气泡行刷新 + 聊天区背景微暗) */
    @Volatile
    var onQueueChanged: ((List<PendingRequest>) -> Unit)? = null

    private const val CHANNEL_ID = "nyral_security_confirm"
    private const val REQUEST_TIMEOUT_MS = 2 * 60 * 1000L

    @JvmStatic
    fun register(act: Activity) { activity = act }

    @JvmStatic
    fun unregister() { activity = null }

    fun snapshot(): List<PendingRequest> = queue.toList()

    /**
     * 阻塞式确认: 由工作线程调用(非主线程), 弹窗期间当前线程挂起。
     * 返回: true=用户允许(票据已签发); false=用户拒绝/超时自动拒绝; null=无可用通道。
     */
    @JvmStatic
    fun requestConfirm(ctx: Context, name: String, arg: String, risk: SecurityConfig.RiskLevel): Boolean? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val req = PendingRequest(
            requestId = "${System.currentTimeMillis()}_${counter.incrementAndGet()}",
            tool = name, arg = arg, risk = risk,
            channel = if (activity != null) "bubble" else "notification",
            latch = CountDownLatch(1),
            result = AtomicReference(null),
            deadlineMs = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        )
        pending[req.requestId] = req
        queue.add(req)
        if (req.channel == "bubble") {
            notifyQueueChanged()
        } else {
            postNotification(ctx, req)
        }
        // 等待决策: 用户点 ✔/✘ (decide 触发 countDown) 或 2 分钟超时
        val done = req.latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (req.channel == "notification") cancelNotification(ctx, req)
        if (!done) {
            req.status = "TIMEOUT"
            req.result.set(false)
            SecurityConfig.audit(ctx, req.tool, req.arg, "timeout", req.channel)
            pending.remove(req.requestId)
            queue.remove(req)
            notifyQueueChanged()
        }
        return req.result.get()
    }

    /** 用户决策入口(气泡 ✔/✘ 按钮、通知 Action 回调; 任意线程可调) */
    @JvmStatic
    fun decide(ctx: Context, requestId: String, allowed: Boolean) {
        val req = pending[requestId] ?: return
        req.status = if (allowed) "ALLOWED" else "REJECTED"
        if (allowed) SecurityConfig.grantTicket(ctx, req.tool, req.arg)
        SecurityConfig.audit(ctx, req.tool, req.arg, if (allowed) "user" else "rejected", req.channel)
        req.result.set(allowed)
        req.latch.countDown()
        pending.remove(requestId)
        queue.remove(req)
        if (req.channel == "notification") cancelNotification(ctx, req)
        notifyQueueChanged()
    }

    private fun notifyQueueChanged() {
        onQueueChanged?.invoke(snapshot())
    }

    // ===== 后台通知通道 =====
    private fun postNotification(ctx: Context, req: PendingRequest) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "安全确认", NotificationManager.IMPORTANCE_HIGH))
        }
        val allow = Intent(ctx, SecurityConfirmReceiver::class.java)
            .setAction(SecurityConfirmReceiver.ACTION_ALLOW)
            .putExtra("requestId", req.requestId)
        val reject = Intent(ctx, SecurityConfirmReceiver::class.java)
            .setAction(SecurityConfirmReceiver.ACTION_REJECT)
            .putExtra("requestId", req.requestId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val piAllow = PendingIntent.getBroadcast(ctx, req.requestId.hashCode(), allow, flags)
        val piReject = PendingIntent.getBroadcast(ctx, req.requestId.hashCode() + 1, reject, flags)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("安全确认")
            .setContentText("工具 [${req.tool}] 请求执行, 请批准或拒绝")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "工具 [${req.tool}] 请求执行危险操作。\n\n参数摘要:\n${req.arg.take(300)}\n\n2 分钟内未操作将自动拒绝。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(true)
            .addAction(0, "允许执行", piAllow)
            .addAction(0, "拒绝", piReject)
            .build()
        nm.notify(req.requestId.hashCode(), n)
    }

    private fun cancelNotification(ctx: Context, req: PendingRequest) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(req.requestId.hashCode())
    }
    /** 放行档二次确认(2026-10-04): 安全中心与欢迎卡片共用, Ui.dialog 主题弹窗替代原始 AlertDialog */
    @JvmStatic
    fun confirmDisableGate(a: Activity, onConfirm: () -> Unit, onCancel: () -> Unit = {}) {
        val (dlg, box) = Ui.dialog(a, "确认关闭门禁？", jellyOvershoot = 1.4f)
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        box.addView(Ui.dialogText(a,
            "关闭后 AI 可自由执行危险操作（sh/ssh/写文件等），不再弹确认。\n\n建议保持「自动」或「严格」模式。切换动作将写入审计日志。"))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(a, 14), 0, 0)
            addView(Ui.dialogCancelBtn(a, "取消") { dlg.dismiss(); onCancel() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(a).apply {
                text = "确认关闭"
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                setTextColor(Color.WHITE)
                background = Ui.rounded(Ui.PRIMARY, 14, a)
                setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12))
                Ui.press(this)
                setOnClickListener { dlg.dismiss(); onConfirm() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Ui.dp(a, 10)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.show()
    }
}

