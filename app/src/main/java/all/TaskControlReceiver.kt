package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Live Updates 胶囊交互卡动作接收器:
 * - ACTION_CANCEL: 用户点胶囊"取消" → 请求引擎中断 + 停前台服务(后台静默生效, 不唤醒界面)
 * 引擎中断后其回调链(MainActivity onError)会清理 UI 状态, 若 Activity 已销毁则通知已停, 无副作用。
 */
class TaskControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.i("TaskService", "胶囊交互: 用户点击取消")
                LocalEngine.requestCancel()
                TaskService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "io.github.aixtin.nyral.action.CANCEL_TASK"
    }
}
