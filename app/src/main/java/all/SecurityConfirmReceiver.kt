package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台通知通道确认接收器(2026-10-04):
 * 处理通知上的"允许执行/拒绝"两个 Action, 回传 SecurityUi.decide 完成决策。
 * 静态注册 exported=false, 仅本 app 的 PendingIntent 可触发, 防外部伪造。
 */
class SecurityConfirmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ALLOW = "io.github.aixtin.nyral.security.ALLOW"
        const val ACTION_REJECT = "io.github.aixtin.nyral.security.REJECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("requestId") ?: return
        when (intent.action) {
            ACTION_ALLOW -> SecurityUi.decide(context, requestId, true)
            ACTION_REJECT -> SecurityUi.decide(context, requestId, false)
        }
    }
}
