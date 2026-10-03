package io.github.aixtin.nyral

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/**
 * 危险工具硬门禁 UI 桥(2026-10-03):
 * - 由 MainActivity.onCreate 注册自身引用
 * - LocalEngine 命中危险工具时调用 requestConfirm: 在主线程弹不可自动关闭的确认框
 * - 用户点"允许执行" → SecurityConfig.grantTicket 签发一次性票据(5分钟有效, 绑定工具+参数)
 * - 用户点"拒绝" → 记审计, 不签发
 * 模型无法自行签发票据, 只能重试消费由用户亲手确认生成的票据。
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
     * 请求用户确认危险操作。返回 true 表示已弹出确认框(或已有确认框在屏); false 表示无前台界面(如后台 DebugServer 调用), 由调用方降级处理。
     */
    @JvmStatic
    fun requestConfirm(ctx: Context, name: String, arg: String): Boolean {
        val act = activity ?: return false
        if (dialog?.isShowing == true) return true // 已有确认框在屏, 不重复弹
        val preview = arg.take(300)
        act.runOnUiThread {
            if (dialog?.isShowing == true) return@runOnUiThread
            val dlg = AlertDialog.Builder(act)
                .setTitle("安全确认")
                .setMessage("工具 [$name] 属于危险操作，需要你亲手批准。\n\n参数摘要:\n$preview\n\n票据 5 分钟内有效，仅本次参数可用。")
                .setCancelable(false)
                .setPositiveButton("允许执行") { _: android.content.DialogInterface, _: Int ->
                    SecurityConfig.grantTicket(ctx, name, arg)
                    SecurityConfig.audit(ctx, name, arg, "user")
                    dialog = null
                }
                .setNegativeButton("拒绝") { _: android.content.DialogInterface, _: Int ->
                    SecurityConfig.audit(ctx, name, arg, "rejected")
                    dialog = null
                }
                .setOnCancelListener { dialog = null }
                .create()
            dialog = dlg
            dlg.show()
        }
        return true
    }
}
