package io.github.aixtin.nyral

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
 * 定时岗位保活前台服务(2026-10-11):
 * 存在任意启用中的定时任务时运行, 常驻通知"定时岗位运行中"将进程钉在前台(FGS),
 * 配合「耗电无限制(准点) + 自启动/pending alarm(兜底复活)」组成保活三件套:
 * FGS 负责"进程压根不被杀/不被速冻", 触发时任务已在进程中直接执行。
 * 仅当定时任务全部停用/删除时停止; 服务被系统意外重启(START_STICKY)时自动复检自停。
 */
class TimerKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "timer_keepalive"
        // 2026-10-11: 1002 原与 DebugService 冲突互相覆盖, 改为 1003; 组内父通知 1004
        private const val NOTIF_ID = 1003

        /** 按需启停: 存在启用任务则拉起保活服务, 否则停掉 */
        fun ensure(context: Context) {
            val hasEnabled = try {
                TimerTaskDb(context).all().any { it.enabled }
            } catch (e: Exception) {
                false
            }
            if (hasEnabled) start(context) else stop(context)
        }

        /** 启动保活前台服务(Android 8+ 走 startForegroundService) */
        fun start(context: Context) {
            val i = Intent(context, TimerKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                android.util.Log.w("TimerKeepAlive", "启动保活服务失败: ${e.message}")
            }
        }

        /** 停止保活服务并移除常驻通知(含 Live Updates/promoted 残留兜底) */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TimerKeepAliveService::class.java))
            } catch (_: Exception) {
            }
            try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // onCreate 立即前台化, 满足 Android 8+ 5s 时限要求
        startForegroundCompat()
        ServiceNotifGroup.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        ServiceNotifGroup.publishSummary(this)
        // 系统重启服务(空 intent)时复检: 已无启用任务则自停
        if (intent == null) {
            val hasEnabled = try {
                TimerTaskDb(this).all().any { it.enabled }
            } catch (e: Exception) {
                false
            }
            if (!hasEnabled) {
                android.util.Log.i("TimerKeepAlive", "无启用任务, 保活服务自停")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START_STICKY: 被系统杀后自动重启, 保证常驻
        return START_STICKY
    }

    private fun startForegroundCompat() {
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif.build())
            }
        } catch (e: Exception) {
            // 兜底: 个别 ROM 类型/权限异常时降级无类型前台服务, 避免崩溃
            try {
                startForeground(NOTIF_ID, buildNotification().build())
            } catch (e2: Exception) {
                android.util.Log.e("TimerKeepAlive", "startForeground 失败: ${e2.message}")
            }
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TimerTaskActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.tk_running))
            .setContentText(getString(R.string.tk_content))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup(ServiceNotifGroup.GROUP_KEY)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tk_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.tk_channel_desc)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        ServiceNotifGroup.unregister(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
