package io.github.aixtin.droidagent

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

/**
 * 任务执行前台服务: AI 处理任务期间拉起(带常驻通知), 防止进程切后台后被系统回收。
 * 任务开始 -> start(); 任务结束/出错/用户停止 -> stop()。
 * targetSdk 34 / Android 14+: 声明 foregroundServiceType=dataSync。
 */
class TaskService : Service() {

    companion object {
        private const val CHANNEL_ID = "task_running"
        private const val NOTIF_ID = 1001

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
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 前台服务必须带类型; targetSdk 34 下 Android 14 强制校验
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "任务执行中", NotificationManager.IMPORTANCE_LOW)
            channel.description = "AI 处理任务期间的前台通知，防止进程被系统回收"
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // 点击通知回到主界面
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("任务执行中…")
            .setContentText("AI 正在后台处理任务，请勿强制停止")
            .setOngoing(true)
            .setContentIntent(contentIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID)
        }
        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 任务结束由外部显式 stopService, 这里只需移除前台标记
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
