package io.github.aixtin.nyral

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 定时岗位调度器(2026-10-11, v2 setAlarmClock 版):
 * Android M+ 用 AlarmManager.setAlarmClock(系统闹钟级)注册任务, 到点唤醒 TimerWakeReceiver,
 * 由它拉起 TaskService 静默执行。
 * setAlarmClock 豁免 Doze/App Standby/battery saver 攒批, 厂商代理层(如 ColorOS
 * OplusAlarmAdjustment 后台代理)对系统闹钟白名单放行, 避免后台任务被推迟到天级。
 * 代价: 系统状态栏/设置会展示"下一个闹钟", 点击跳转定时任务列表页。
 * cron_desc 当前支持格式: "每天 HH:mm"(MVP), 如 "每天 09:00"。
 */
object TimerScheduler {

    private const val ACTION_WAKE = "io.github.aixtin.nyral.action.TIMER_WAKE"

    /** showIntent 固定 requestCode, 与 taskId 广播 requestCode 区分开 */
    private const val SHOW_REQUEST_CODE = 0x2001

    /** 解析 "每天 HH:mm" -> Calendar 目标时刻; 失败返回 null */
    fun parseCron(cronDesc: String): Calendar? {
        val m = Regex("每天\\s*(\\d{1,2}):(\\d{2})").find(cronDesc.trim()) ?: return null
        val hh = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2].toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hh)
            set(Calendar.MINUTE, mm)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 目标时刻已过 -> 顺延到明天
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    /** 注册/刷新任务闹钟 */
    fun schedule(context: Context, taskId: Long, cronDesc: String) {
        val cal = parseCron(cronDesc) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, taskId)
        try {
            am.cancel(pi)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 系统闹钟级(无需 SCHEDULE_EXACT_ALARM 权限), 厂商代理不敢动
                val showPi = PendingIntent.getActivity(
                    context,
                    SHOW_REQUEST_CODE,
                    Intent(context, TimerTaskActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(cal.timeInMillis, showPi),
                    pi
                )
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) {
            // 兜底降级, 保证链路能跑
            try {
                am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * 60 * 1000L, pi)
            } catch (e2: Exception) {
                android.util.Log.e("TimerScheduler", "schedule 失败: ${e2.message}")
            }
        }
    }

    /** 取消任务闹钟 */
    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, taskId))
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, TimerWakeReceiver::class.java)
            .setAction(ACTION_WAKE)
            .putExtra("task_id", taskId)
        return PendingIntent.getBroadcast(
            context,
            (taskId and 0x7FFFFFFF).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
