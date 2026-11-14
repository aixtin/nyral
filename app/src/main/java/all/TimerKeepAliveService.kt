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
import android.os.SystemClock
import androidx.core.app.NotificationCompat

/**
 * 定时岗位保活前台服务(2026-10-11):
 * 存在任意启用中的定时任务时运行, 常驻通知"定时岗位运行中"将进程钉在前台(FGS),
 * 配合「耗电无限制(准点) + 自启动/pending alarm(兜底复活)」组成保活三件套:
 * FGS 负责"进程压根不被杀/不被速冻", 触发时任务已在进程中直接执行。
 * 仅当定时任务全部停用/删除时停止; 服务被系统意外重启(START_STICKY)时自动复检自停。
 *
 * 2026-11-12 修 ForegroundServiceDidNotStopInTimeException(type=dataSync):
 * Android 15(API 35)起 dataSync 类型 FGS 单次/24h 累计运行有 6 小时上限, 到期系统回调
 * onTimeout(), 应用未及时 stopSelf 即由 system_server 抛 RemoteServiceException 直接杀进程。
 * 保活服务天然需要"长期常驻", 与 dataSync 时限在设计上冲突 —— 因此改用 specialUse 类型
 * (无时长配额, manifest 声明 subtype), 并补 onTimeout 兜底自停, 避免未来政策变动再崩。
 */
class TimerKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "timer_keepalive"
        // 2026-10-11: 1002 原与 DebugService 冲突互相覆盖, 改为 1003; 组内父通知 1004
        private const val NOTIF_ID = 1003
        private const val TAG = "TimerKeepAlive"
        /** 同进程内两次拉起的最小间隔: 恢复链路(L1/L2/L3)会并发 ensure, 防重复 start(2026-11-12) */
        private const val START_THROTTLE_MS = 5_000L

        @Volatile private var lastStartAt = 0L

        /** 按需启停: 存在启用任务则拉起保活服务, 否则停掉 */
        fun ensure(context: Context) {
            val hasEnabled = try {
                TimerTaskDb(context).all().any { it.enabled }
            } catch (e: Exception) {
                false
            }
            // 第四批(2026-11-14): 常驻保活降级为可关开关。默认仍开(沿用旧行为, 不引入可靠性回归),
            // 但用户可在"定时岗位体检"页关掉 —— 对每日 1~2 次的任务它是净负担(占通知位 + 常驻内存)
            if (hasEnabled && keepAliveEnabled(context)) start(context) else stop(context)
        }

        /** 常驻保活开关(默认开, 兼容旧行为) */
        fun keepAliveEnabled(context: Context): Boolean = try {
            context.getSharedPreferences("timer_health", Context.MODE_PRIVATE)
                .getBoolean("keep_alive", true)
        } catch (e: Exception) {
            true
        }

        /** 切换开关并即时生效: 开 -> 若有启用任务则拉起; 关 -> 立刻停掉并移除常驻通知 */
        fun setKeepAlive(context: Context, on: Boolean) {
            try {
                context.getSharedPreferences("timer_health", Context.MODE_PRIVATE).edit()
                    .putBoolean("keep_alive", on).apply()
            } catch (_: Exception) {
            }
            if (on) ensure(context) else stop(context)
        }

        /** 启动保活前台服务(Android 8+ 走 startForegroundService) */
        @Synchronized
        fun start(context: Context) {
            val now = SystemClock.elapsedRealtime()
            // 节流: 已拉起(或刚拉起)时不重复下发 start, 减少 FGS 状态机抖动
            if (lastStartAt != 0L && now - lastStartAt < START_THROTTLE_MS) return
            lastStartAt = now
            val i = Intent(context, TimerKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "启动保活服务失败: ${e.message}")
            }
        }

        /** 停止保活服务并移除常驻通知(含 Live Updates/promoted 残留兜底) */
        @Synchronized
        fun stop(context: Context) {
            lastStartAt = 0L
            try {
                context.stopService(Intent(context, TimerKeepAliveService::class.java))
            } catch (_: Exception) {
            }
            try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }

        /** API 34+: 常驻保活用 specialUse(无 6h 配额); 更低版本无时限, 沿用 dataSync */
        private fun fgsType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
    }

    /** 已进入停止流程: 此后不再前台化, 防"超时被杀 → 又 startForeground"循环 */
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        stopping = false
        createChannel()
        // onCreate 立即前台化, 满足 Android 8+ 5s 时限要求
        startForegroundCompat()
        ServiceNotifGroup.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping) {
            // 停止流程中收到的 start: 直接确认并退出, 不再前台化
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!startForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ServiceNotifGroup.publishSummary(this)
        // 系统重启服务(空 intent)时复检: 已无启用任务则自停
        if (intent == null) {
            val hasEnabled = try {
                TimerTaskDb(this).all().any { it.enabled }
            } catch (e: Exception) {
                false
            }
            if (!hasEnabled) {
                android.util.Log.i(TAG, "无启用任务, 保活服务自停")
                stopping = true
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        // START_STICKY: 被系统杀后自动重启, 保证常驻
        return START_STICKY
    }

    /**
     * Android 15+ 前台服务超时回调(API 36 还有带 type 的重载, 默认实现会转调本方法)。
     * specialUse 正常不触发; 一旦触发必须立刻自停, 否则 system_server 抛
     * ForegroundServiceDidNotStopInTimeException 直接杀进程。
     */
    override fun onTimeout(startId: Int) {
        android.util.Log.w(TAG, "前台服务超时, 主动自停(startId=$startId)")
        stopping = true
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat(): Boolean {
        if (stopping) return false
        return try {
            startForeground(NOTIF_ID, buildNotification().build(), fgsType())
            true
        } catch (e: Exception) {
            // 兜底: 个别 ROM 类型/权限异常时降级无类型前台服务, 避免崩溃
            try {
                startForeground(NOTIF_ID, buildNotification().build())
                true
            } catch (e2: Exception) {
                android.util.Log.e(TAG, "startForeground 失败: ${e2.message}")
                false
            }
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "stopForeground 失败: ${e.message}")
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
        // 先撤前台状态再交还父类, 保证通知与 FGS 状态在同一帧内一致
        stopping = true
        stopForegroundCompat()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        ServiceNotifGroup.unregister(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
