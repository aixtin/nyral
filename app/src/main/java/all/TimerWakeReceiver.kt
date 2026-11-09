package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 定时岗位唤醒接收器(2026-10-11):
 * AlarmManager 到点触发 -> 校验任务仍启用 -> 拉起 TaskService 静默后台执行。
 * 唤醒路径带 X-Nyral-Task-ID 语义(以 extra task_id 传递), 普通对话无此标记。
 */
class TimerWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra("task_id", -1L) ?: -1L
        if (taskId <= 0) return
        val task = try {
            TimerTaskDb(context).get(taskId)
        } catch (e: Exception) {
            Log.e("TimerWakeReceiver", "读取任务失败: ${e.message}")
            null
        }
        if (task == null) {
            Log.w("TimerWakeReceiver", "任务 $taskId 不存在, 忽略唤醒")
            return
        }
        if (!task.enabled) {
            Log.i("TimerWakeReceiver", "任务 $taskId 已停用, 忽略唤醒")
            return
        }

        Log.i("TimerWakeReceiver", "定时岗位唤醒: ${task.name} (id=$taskId)")

        // 拉起前台服务承载静默执行(通知仅提示"任务执行中", 不弹 UI)
        // fix(2026-11-09): 统一走 TaskService.startTimerTask, 启动前清残留 pendingStop,
        // 否则上一轮对话遗留的补停标记会把真实闹钟唤醒整体吞掉
        TaskService.startTimerTask(context, taskId)
    }
}
