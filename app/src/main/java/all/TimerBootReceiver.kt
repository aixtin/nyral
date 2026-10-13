package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机/应用更新后重排定时岗位(2026-10-11):
 * 设备重启后 AlarmManager 全部清空, 应用更新(MY_PACKAGE_REPLACED)也可能丢闹钟,
 * 收到广播后遍历启用任务重新注册, 并尝试拉起保活前台服务;
 * FGS 启动失败(个别 ROM 限制)不影响 alarm 重排, 下次进页面 ensure 会兜底拉起。
 */
class TimerBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val tasks = try {
            TimerTaskDb(context).all()
        } catch (e: Exception) {
            android.util.Log.e("TimerBoot", "读取任务失败: ${e.message}")
            return
        }
        var enabledCount = 0
        for (t in tasks) {
            if (t.enabled) {
                TimerScheduler.schedule(context, t.id, t.cronDesc)
                enabledCount++
            }
        }
        android.util.Log.i("TimerBoot", "$action: 重排 $enabledCount 个启用任务")
        if (enabledCount > 0) {
            TimerKeepAliveService.start(context)
        }
    }
}
