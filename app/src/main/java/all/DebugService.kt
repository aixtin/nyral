package io.github.aixtin.nyral

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * DebugServer 保活前台服务: 防止 App 退后台后进程被系统冻结(cached freezer),
 * 导致 DebugServer(8765) accept 线程停摆、云端 curl 连不上。
 * 仅 debug 构建且 DebugServer 启用时启动; release 构建不出现。
 */
class DebugService : Service() {

    companion object {
        private const val CHANNEL_ID = "nyral_debug_server"
        private const val NOTIF_ID = 1002

        /** 仅 debug 构建且 DebugServer 启用时启动(与 DebugServer.init 同条件) */
        fun start(context: Context) {
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            if (!DebugServer.isEnabled(context)) return
            val intent = Intent(context, DebugService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, DebugService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 尽早前台化, 满足 startForegroundService 5s 时限(Android 15/16 + ColorOS 校验严格)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "调试服务", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (e2: Exception) {
                android.util.Log.e("DebugService", "startForeground 失败: ${e2.message}")
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Nyral 调试服务")
            .setContentText("DebugServer 运行中 (8765)")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
