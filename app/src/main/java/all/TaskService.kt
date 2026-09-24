package io.github.aixtin.nyral

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 任务执行前台服务: AI 处理任务期间拉起(带常驻通知), 防止进程切后台后被系统回收。
 * 任务开始 -> start(); 任务结束/出错/用户停止 -> stop()。
 * targetSdk 36 / Android 16: 接入 Live Updates(系统胶囊/流体云/超级岛)。
 *
 * Live Updates 关键点(参考官方规范):
 * - 必须用 NotificationCompat(setRequestPromotedOngoing), 原生 Builder 无此方法;
 * - TASK 场景用 ProgressStyle(API 36 无 MetricStyle), setProgressIndeterminate(true) 表示持续进行;
 * - 满足前置条件: setOngoing(true) / setContentTitle / 无 RemoteViews / 非 group summary / 非 colorized;
 * - setShortCriticalText(<=7字符) 控制状态栏胶囊文案;
 * - setDeleteIntent 防用户划走通知后 Live Updates 复活;
 * - 通知渠道 importance 不可为 IMPORTANCE_MIN(当前 LOW 满足);
 * - 低版本 Android(<36) 安全降级为普通 ongoing 通知, 不影响现有逻辑。
 */
class TaskService : Service() {

    companion object {
        private const val CHANNEL_ID = "task_running"
        private const val NOTIF_ID = 1001

        /** 胶囊重试动作(通知 Action 携带, MainActivity.onNewIntent 处理) */
        const val ACTION_RETRY = "io.github.aixtin.nyral.action.RETRY_TASK"

        /** 启动前台服务(Android 8+ 需 startForegroundService, 兼容旧版走 startService) */
        fun start(context: Context) {
            val intent = Intent(context, TaskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止前台服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, TaskService::class.java))
            // ColorOS/Android16 Live Updates: 通知被提升(promoted)后 stopForeground 不移除,
            // 必须显式 cancel 兜底, 否则任务已停但"任务执行中"通知残留, 用户误以为取消没生效
            try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }

        /**
         * 更新任务阶段文案(同时刷新 Live Updates 胶囊/进度)。
         * 由 MainActivity 在思考/工具/作答等阶段切换时调用。
         */
        fun updateStage(context: Context, stage: String) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val builder = buildNotification(context, stage)
                nm.notify(NOTIF_ID, builder.build())
            } catch (e: Exception) {
                android.util.Log.e("TaskService", "updateStage 失败: ${e.message}")
            }
        }

        /** 构建 Live Updates 通知; 非 API36 设备降级为普通 ongoing 通知 */
        fun buildNotification(context: Context, stage: String): NotificationCompat.Builder {
            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 防用户划走通知后 Live Updates 复活: 划走即触发 receiver 自清理
            val deleteIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TaskDismissReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.ts_running))
                .setContentText(stage)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // 胶囊交互卡: 取消(广播后台静默停)+重试(拉起主界面重发最后一条用户消息)
            val cancelPI = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, TaskControlReceiver::class.java)
                .setAction(TaskControlReceiver.ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.ts_action_cancel),
                    cancelPI
                ).build()
            )
            val retryPI = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_RETRY)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_rotate,
                    context.getString(R.string.ts_action_retry),
                    retryPI
                ).build()
            )
            if (Build.VERSION.SDK_INT >= 36) {
                // Android 16 Live Updates: 申请提升为系统胶囊(TASK 场景, 无限进度)
                builder.setRequestPromotedOngoing(true)
                builder.setShortCriticalText(stage.take(7))
                val ps = NotificationCompat.ProgressStyle()
                ps.setProgressIndeterminate(true)
                builder.setStyle(ps)
            }
            return builder
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 尽早完成前台化: startForegroundService 后系统限时(约5s)内必须 startForeground,
        // 否则抛 ForegroundServiceDidNotStartInTimeException 闪退。onCreate 先于 onStartCommand 执行,
        // 在这里立即 startForeground 能最大化满足时限(Android 15/16 + ColorOS 校验更严格)。
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等: onCreate 已启动前台, 重复调用 startForeground 无副作用
        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        try {
            val notif = buildNotification(this, getString(R.string.ts_content))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 前台服务必须带类型; targetSdk 34 下 Android 14 强制校验
                startForeground(NOTIF_ID, notif.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif.build())
            }
        } catch (e: Exception) {
            // 兜底: 个别 ROM 类型/权限异常时降级为无类型前台服务, 避免崩溃
            try {
                startForeground(NOTIF_ID, buildNotification(this, getString(R.string.ts_content)).build())
            } catch (e2: Exception) {
                android.util.Log.e("TaskService", "startForeground 失败: ${e2.message}")
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.ts_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.ts_channel_desc)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 任务结束由外部显式 stopService, 这里只需移除前台标记
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        // 兜底: 显式移除 Live Updates 残留通知(服务销毁后通知中心可能仍挂有旧通知)
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
