package io.github.aixtin.nyral

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 危险工具硬门禁 UI 桥 v2(2026-10-03):
 * - 由 MainActivity.onCreate 注册自身引用
 * - LocalEngine(工作线程) 命中危险工具时调用 requestConfirm: 阻塞挂起等待用户决策
 * - 用户点"允许执行" → SecurityConfig.grantTicket 签发窗口期票据(5分钟, 绑定工具+参数) + 审计
 * - 用户点"拒绝" → 记审计, 返回 false, 调用方不执行工具
 * - 无前台界面或等待超时(2分钟) → 返回 null
 * 模型无法自行签发票据, 只能由用户亲手确认; 票据窗口期内同参数直接放行不重复弹窗。
 */
object SecurityUi {
    @Volatile
    private var activity: Activity? = null

    /** 当前在屏确认框(单例化, 避免 Agent 快速重试时叠多个弹窗) */
    @Volatile
    private var dialog: AlertDialog? = null

    /** MainActivity.onCreate 调用; 重建时重复注册无害 */
    @JvmStatic
    fun register(act: Activity) {
        activity = act
    }

    /**
     * 阻塞式确认: 由工作线程调用(非主线程), 弹窗期间当前线程挂起。
     * 返回: true=用户允许(票据已签发); false=用户拒绝; null=无前台界面/等待超时。
     * 注意: 必须在非主线程调用, 否则死锁(弹窗需要主线程渲染)。
     */
    @JvmStatic
    fun requestConfirm(ctx: Context, name: String, arg: String): Boolean? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 防御: 主线程调用时无法阻塞等待, 直接降级为无确认
            return null
        }
        val act = activity ?: return null
        if (dialog?.isShowing == true) return null // 已有确认框在屏(理论上阻塞式下不会并发), 防叠加

        val latch = CountDownLatch(1)
        val result = AtomicReference<Boolean?>(null)
        val preview = arg.take(300)
        act.runOnUiThread {
            if (dialog?.isShowing == true) {
                latch.countDown()
                return@runOnUiThread
            }
            val dlg = AlertDialog.Builder(act)
                .setTitle("安全确认")
                .setMessage("工具 [$name] 属于危险操作，需要你亲手批准。\n\n参数摘要:\n$preview\n\n确认后 5 分钟内同参数调用不再询问；拒绝则本次调用停止。")
                .setCancelable(false)
                .setPositiveButton("允许执行") { _: android.content.DialogInterface, _: Int ->
                    SecurityConfig.grantTicket(ctx, name, arg)
                    SecurityConfig.audit(ctx, name, arg, "user")
                    result.set(true)
                    dialog = null
                    latch.countDown()
                }
                .setNegativeButton("拒绝") { _: android.content.DialogInterface, _: Int ->
                    SecurityConfig.audit(ctx, name, arg, "rejected")
                    result.set(false)
                    dialog = null
                    latch.countDown()
                }
                .setOnCancelListener {
                    result.set(null)
                    dialog = null
                    latch.countDown()
                }
                .create()
            dialog = dlg
            dlg.show()
        }
        // 最多等待 2 分钟: 用户一直不决策时避免工具循环永久挂起
        if (!latch.await(2, TimeUnit.MINUTES)) {
            act.runOnUiThread {
                dialog?.dismiss()
                dialog = null
            }
            return null
        }
        return result.get()
    }
}
