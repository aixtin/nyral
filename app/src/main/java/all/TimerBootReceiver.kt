package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 定时岗位重排广播入口(2026-10-11, 2026-11-10 扩至多触发源):
 * 设备重启 / 应用更新 / 时间被改 / 时区变更 都会让 AlarmManager 与实际时刻脱节,
 * 收到广播后统一交给 TimerRecovery 幂等重排。
 *
 * 2026-11-10 扩展依据(反编译竞品):
 * - MacroDroid: BOOT priority=INT_MAX, 另有 crashguard SchedulerReceiver 监听 TIME_SET+TIMEZONE_CHANGED
 * - Tasker: BOOT priority=999, 只靠 priority 而不做 Direct Boot
 * - 两家都监听 QUICKBOOT_POWERON(部分厂商重启只发这个, 不发 BOOT_COMPLETED)
 * - Tasker 还监听 PACKAGE_ADDED/REMOVED: 重启后用户只要装/卸任意 App 就多一次重排机会(该 action 在 O+ 隐式广播豁免清单内)
 * 顺序抄 MacroDroid StartupReceiver: 广播里先做"立进程"的动作, 再进入可失败的业务逻辑。
 */
class TimerBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val supported = action == Intent.ACTION_BOOT_COMPLETED ||
            action == ACTION_QUICKBOOT ||
            action == ACTION_QUICKBOOT_HTC ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED
        if (!supported) return

        Log.i("TimerBoot", "收到广播: $action")
        // 去重前先确保兜底 job 在册(即便后续重排抛异常, 15min 内还能自愈)
        try {
            TimerBootJobService.ensureScheduled(context)
        } catch (e: Exception) {
            Log.e("TimerBoot", "ensureScheduled 失败 ${e.message}")
        }
        // 时间/时区被改: 闹钟 PendingIntent 还在, 但"每天 HH:mm"算出来的绝对时刻已经变了,
        // 判据探不到这种脱节, 必须无条件重排(2026-11-10 补测发现)。
        val timeShifted = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        // 其余场景交给 TimerRecovery 内部判据(boot_count / 闹钟是否缺失 / 锚点过期), 重复投递不会重复执行
        TimerRecovery.ensureAsync(context, "bcast:$action", force = timeShifted)
    }

    private companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_QUICKBOOT_HTC = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
