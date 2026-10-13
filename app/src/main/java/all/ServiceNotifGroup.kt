package io.github.aixtin.nyral

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * 常驻服务通知组(2026-10-11):
 * 将「定时岗位运行 / 调试服务 / 任务执行」三个前台服务通知折叠到一个父通知下。
 * 父通知为 group summary(id=1004, channel=nyral_service_summary),
 * 三个子通知(1001/1002/1003)各自挂 GROUP_KEY, 系统自动折叠到父通知下方。
 *
 * 计数方案: 各子服务 onCreate 调 register(), onDestroy 调 unregister()。
 * 不用 NotificationManager.getActiveNotifications() —— 实测 ColorOS(Android16)
 * 上 targetSdk>=33 时该方法恒返回空数组, 无法作为统计依据。
 */
object ServiceNotifGroup {
    const val GROUP_KEY = "nyral_services"
    const val SUMMARY_CHANNEL_ID = "nyral_service_summary"
    const val SUMMARY_NOTIF_ID = 1004

    private val activeCount = AtomicInteger(0)

    /** 子服务启动时注册(服务实例级: onCreate 调用一次) */
    fun register(context: Context) {
        activeCount.incrementAndGet()
        publishSummary(context)
    }

    /** 子服务销毁时注销(onDestroy 调用) */
    fun unregister(context: Context) {
        activeCount.decrementAndGet()
        publishSummary(context)
    }

    /** 刷新父通知: 活跃子服务数>0 发布 summary, =0 撤销 summary */
    fun publishSummary(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val count = activeCount.get()
        if (count > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    SUMMARY_CHANNEL_ID, "Nyral 服务汇总",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, SUMMARY_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Nyral 服务运行中")
                .setContentText("$count 项服务运行中")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
            nm.notify(SUMMARY_NOTIF_ID, builder.build())
        } else {
            nm.cancel(SUMMARY_NOTIF_ID)
        }
    }
}
