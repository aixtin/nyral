package io.github.aixtin.nyral

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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

    /** 期望触发时刻锚点(2026-11-10): PendingIntent 记录在 force-stop 后仍会残留, 单靠 FLAG_NO_CREATE 会误判"闹钟齐全" */
    private const val ANCHOR_PREFS = "timer_alarm_anchor"

    /** 锚点宽限: 到点被消费后若执行链没及时续期, 超过该时长即视为丢失, 允许自愈重排 */
    private const val ANCHOR_GRACE_MS = 10 * 60 * 1000L

    private fun anchorKey(taskId: Long) = "t:$taskId"

    private fun writeAnchor(context: Context, taskId: Long, at: Long) {
        context.getSharedPreferences(ANCHOR_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(anchorKey(taskId), at).apply()
    }

    private fun clearAnchor(context: Context, taskId: Long) {
        context.getSharedPreferences(ANCHOR_PREFS, Context.MODE_PRIVATE).edit()
            .remove(anchorKey(taskId)).apply()
    }

    private fun readAnchor(context: Context, taskId: Long): Long =
        context.getSharedPreferences(ANCHOR_PREFS, Context.MODE_PRIVATE)
            .getLong(anchorKey(taskId), 0L)

    /** 解析 "每天 HH:mm" -> Calendar 目标时刻; 失败返回 null */
    fun parseCron(cronDesc: String): Calendar? {
        val m = Regex("每天\\s*(\\d{1,2}):(\\d{2})").find(cronDesc.trim()) ?: run { Log.e("TimerScheduler", "表达式无法解析: $cronDesc"); return null }
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

    /** 早投递栅栏(2026-11-14, 第二批): 系统允许的提前量, 早于此即判为"提前投递", 不算本 slot 的合法出发 */
    const val EARLY_SLACK_MS = 5_000L

    /** 下次触发时刻(毫秒): 今天的 HH:mm 未过则今天, 已过则明天; 表达式无法解析返回 0 */
    fun nextRunAt(cronDesc: String): Long = parseCron(cronDesc)?.timeInMillis ?: 0L

    /** 解析 "每天 HH:mm" -> (时, 分); 失败返回 null */
    fun hhmmOf(cronDesc: String): Pair<Int, Int>? {
        val m = Regex("每天\\s*(\\d{1,2}):(\\d{2})").find(cronDesc.trim()) ?: return null
        val hh = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2].toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh to mm
    }

    /** now 所在自然日的目标时刻 */
    private fun targetOfDay(now: Long, hh: Int, mm: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hh)
            set(Calendar.MINUTE, mm)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * 最近一个"已经到期"的 slot(2026-11-14, 第二批): 以固定的每日时刻为锚反推, 不再从 now 实时推算下一次。
     * 这是问题 2「一天两次触发」的根因修法 —— 07:52 被提前投递时归属的是"昨天 08:00"这个 slot,
     * 08:00 才是"今天 08:00", 两者互不冒充; 同一个 slot 被投递两次则第二次必然被占位挡住。
     */
    fun latestDueSlot(cronDesc: String, now: Long): Long {
        val hm = hhmmOf(cronDesc) ?: return 0L
        val today = targetOfDay(now, hm.first, hm.second)
        if (now + EARLY_SLACK_MS >= today) return today
        return Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
    }

    /** 提前投递判定(2026-11-14, 第二批): now 比今天目标时刻早出 EARLY_SLACK_MS 以上 */
    fun isEarlyDelivery(cronDesc: String, now: Long): Boolean {
        val hm = hhmmOf(cronDesc) ?: return false
        return now + EARLY_SLACK_MS < targetOfDay(now, hm.first, hm.second)
    }

    /** slot 可读格式化, 仅供日志/审计 */
    fun fmtSlot(ts: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))

    /** 注册/刷新任务闹钟; 成功即写入期望触发时刻锚点(2026-11-10) */
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
            writeAnchor(context, taskId, cal.timeInMillis)
        } catch (e: Exception) {
            // 兜底降级(2026-11-10 改): 有精确闹钟权限走 setExactAndAllowWhileIdle, 没有才退 inexact 窗口,
            // 抄 MacroDroid Lnq;.d(J,Z,PendingIntent) 的现场探测思路, 不对权限做任何假定
            try {
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * 60 * 1000L, pi)
                }
                writeAnchor(context, taskId, cal.timeInMillis)
            } catch (e2: Exception) {
                Log.e("TimerScheduler", "schedule 失败: ${e2.message}")
                clearAnchor(context, taskId)
            }
        }
    }

    /** 取消任务闹钟 */
    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, taskId))
        clearAnchor(context, taskId)
    }

    /**
     * 闹钟是否仍在册(2026-11-10): 用 FLAG_NO_CREATE 探测 PendingIntent 是否还存在。
     * 语义刻意做窄: 返回 false => 一定没排(重启/进程被杀/被系统清理后 PendingIntent 记录消失);
     * 返回 true 只代表"排过", 不代表闹钟没被消费 —— 但足以区分"重启后全丢"这个主场景,
     * 且避免周期任务每次 tick 都无条件重置闹钟。
     */
    fun hasAlarm(context: Context, taskId: Long): Boolean {
        val intent = Intent(context, TimerWakeReceiver::class.java)
            .setAction(ACTION_WAKE)
            .putExtra("task_id", taskId)
        val exists = try {
            PendingIntent.getBroadcast(
                context,
                (taskId and 0x7FFFFFFF).toInt(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) != null
        } catch (e: Exception) {
            false
        }
        if (!exists) return false
        // 2026-11-10 修复: force-stop / 厂商清理只移除 AlarmManager 里的闹钟, PendingIntentRecord 仍残留,
        // 只看 FLAG_NO_CREATE 会误判"齐全"导致永不重排(红米实测 force-stop 后 dumpsys alarm=0 却报齐全)。
        // 故叠加期望触发时刻锚点: 锚点缺失(旧版遗留)或已过期超宽限 => 判定丢失, 交给上层重排。
        val anchor = readAnchor(context, taskId)
        if (anchor <= 0L) return false
        return System.currentTimeMillis() <= anchor + ANCHOR_GRACE_MS
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

    /** 触发即续期(2026-11-14 fix): 以锚点为基准逐日前移, 保证本轮中断后下一轮仍被安排 */
    fun rescheduleNext(context: Context, taskId: Long, cronDesc: String = "") {
        val anchor = readAnchor(context, taskId)
        if (anchor <= 0L) {
            // 第二批(2026-11-14): 锚点缺失(被清/旧版遗留)时退化为按表达式重排,
            // 否则闸门拦截后既不执行也不再排, 任务会永久失调度
            if (cronDesc.isNotEmpty()) {
                Log.w("TimerScheduler", "续期: task=$taskId 锚点缺失, 按表达式重排")
                schedule(context, taskId, cronDesc)
            } else {
                Log.w("TimerScheduler", "续期跳过 task=$taskId: 锚点缺失且无表达式")
            }
            return
        }
        var ts = anchor
        while (ts <= System.currentTimeMillis()) ts += 86_400_000L
        writeAnchor(context, taskId, ts)
        scheduleOnce(context, taskId, ts)
    }

    /** 单次注册闹钟(不写锚点), 供触发即续期使用 */
    private fun scheduleOnce(context: Context, taskId: Long, atMs: Long) {
        val pi = pendingIntent(context, taskId)
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val showPi = PendingIntent.getActivity(
                    context, SHOW_REQUEST_CODE,
                    Intent(context, TimerTaskActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, showPi), pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
            Log.i("TimerScheduler", "续期到 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))}")
        } catch (e: Exception) {
            Log.e("TimerScheduler", "续期失败 task=$taskId: ${e.message}")
        }
    }
}
