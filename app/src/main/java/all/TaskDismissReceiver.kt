package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 用户划走 Live Updates 通知时触发: 由 TaskService.setDeleteIntent 注册。
 * 作用: 防止用户主动划走胶囊后, Live Updates 被系统判定失效却仍重复弹出复活。
 * 这里仅作清理兜底: 刷新一次通知(移除 promoted 标记由系统处理), 无需额外动作。
 */
class TaskDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        android.util.Log.i("TaskService", "Live Updates 通知被用户划走, 清理胶囊态")
    }
}
