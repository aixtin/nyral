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
 *
 * 2026-10-11 扩展: ACTION_TIMER_TASK 定时岗位静默执行分支。
 * TimerWakeReceiver 到点拉起 -> runTimerTask: 复用 LocalEngine.chat 无 UI 链路(与 DebugServer /v1/chat 同源),
 * 回调只落库+审计不碰 UI, 完成后自动停前台服务。
 */
class TaskService : Service() {

    companion object {
        private const val CHANNEL_ID = "task_running"
        private const val NOTIF_ID = 1001
        private const val NOTIF_DONE_ID = 1005

        /** 胶囊重试动作(通知 Action 携带, MainActivity.onNewIntent 处理) */
        const val ACTION_RETRY = "io.github.aixtin.nyral.action.RETRY_TASK"

        /** 定时岗位唤醒动作(2026-10-11): TimerWakeReceiver 拉起后静默执行定时任务 */
        const val ACTION_TIMER_TASK = "io.github.aixtin.nyral.action.TIMER_TASK"
        const val EXTRA_TASK_ID = "task_id"

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
            ServiceNotifGroup.publishSummary(context)
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
                ServiceNotifGroup.publishSummary(context)
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
                .setGroup(ServiceNotifGroup.GROUP_KEY)
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
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        ModeConfig.init(this)
        createChannel()
        // 尽早完成前台化: startForegroundService 后系统限时(约5s)内必须 startForeground,
        // 否则抛 ForegroundServiceDidNotStartInTimeException 闪退。onCreate 先于 onStartCommand 执行,
        // 在这里立即 startForeground 能最大化满足时限(Android 15/16 + ColorOS 校验更严格)。
        startForegroundCompat()
        ServiceNotifGroup.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等: onCreate 已启动前台, 重复调用 startForeground 无副作用
        startForegroundCompat()
        ServiceNotifGroup.publishSummary(this)
        if (intent?.action == ACTION_TIMER_TASK) {
            val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
            if (taskId > 0) runTimerTask(taskId)
        }
        return START_NOT_STICKY
    }

    /**
     * 定时岗位静默执行(2026-10-11):
     * 构造带任务目标的历史 -> LocalEngine.chat 后台跑完整链路; 回调只落库+审计, 不碰 UI。
     * AI 忙时 LocalEngine 内部互斥返回 false, 本次跳过留待下轮; 完成后自动停前台服务。
     */
    private fun runTimerTask(taskId: Long) {
        val db = TimerTaskDb(this)
        val task = db.get(taskId) ?: run { TaskService.stop(this); return }
        if (!task.enabled) { TaskService.stop(this); return }
        // 专属会话绑定(2026-10-13): 创建时已建会话; 旧任务(测试用)首次触发时补绑, 模式用任务固化值(旧任务默认 Agent 模式)
        var sessionId = task.sessionId
        if (sessionId <= 0L) {
            sessionId = MemoryDb(this).saveSession(task.name, emptyList(), task.mode)
            db.bindSession(taskId, sessionId)
        }
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        // 能力预授权(2026-10-13): 解析任务白名单(逗号分隔 key), 静默执行命中即放行危险工具
        val allowTools = task.allowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val history = "[当前时间]\n" + now + "\n\n" +
            "[定时岗位任务]\n" +
            "任务名: " + task.name + "\n" +
            "目标: " + task.goal + "\n" +
            "请作为 Nyral 后台静默执行该定时任务, 完成后输出简洁结果摘要, 不需要请示确认。"
        android.util.Log.i("TimerTask", "定时岗位执行开始: ${task.name}")
        // 2026-10-11 fix: LocalEngine 网络请求必须在后台线程执行(主线程会抛 NetworkOnMainThreadException)
        val cb = object : LocalEngine.Callback {
            override fun onThinkingStart() {}
            override fun onThinkingDelta(text: String) {}
            override fun onThinkingEnd() {}
            override fun onTool(name: String, arg: String) {}
            override fun onDelta(text: String) {}
            override fun onDone(reply: String) {
                db.setLastRun(taskId, System.currentTimeMillis(), reply.take(500))
                writeToSession(sessionId, task, history, reply)
                SecurityConfig.audit(this@TaskService, "timer_task_run",
                    "${task.name} -> ${reply.take(120)}", "alarm", "timer_task")
                android.util.Log.i("TimerTask", "定时岗位执行完成: ${task.name}")
                // 2026-10-13 fix: setAlarmClock 单次闹钟, 完成后必须续期下一次, 否则 *walarm* 消失任务次日不再触发
                val stillEnabled = db.get(taskId)?.enabled == true
                if (stillEnabled && !task.cronDesc.isNullOrEmpty()) {
                    TimerScheduler.schedule(this@TaskService, taskId, task.cronDesc)
                    android.util.Log.i("TimerTask", "定时岗位已续期: ${task.name} -> ${task.cronDesc}")
                }
                notifyTaskDone(this@TaskService, task, reply)
                TaskService.stop(this@TaskService)
            }
            override fun onError(msg: String) {
                db.setLastRun(taskId, System.currentTimeMillis(), "失败: $msg")
                writeToSession(sessionId, task, history, "执行失败: $msg")
                SecurityConfig.audit(this@TaskService, "timer_task_run",
                    "${task.name} -> 失败: $msg", "alarm", "timer_task")
                android.util.Log.w("TimerTask", "定时岗位执行失败: ${task.name}: $msg")
                // 2026-10-13 fix: 失败同样续期下一次(任务仍启用时), 保留每日重试机会
                val stillEnabled = db.get(taskId)?.enabled == true
                if (stillEnabled && !task.cronDesc.isNullOrEmpty()) {
                    TimerScheduler.schedule(this@TaskService, taskId, task.cronDesc)
                    android.util.Log.i("TimerTask", "定时岗位失败后已续期: ${task.name} -> ${task.cronDesc}")
                }
                TaskService.stop(this@TaskService)
            }
        }
        Thread {
            LocalEngine.cancelRequested = false
            LocalEngine.chat(this@TaskService, history, cb, silentMode = true, allowTools = allowTools, taskId = taskId) // 定时岗位静默: 未授权危险工具自动拒绝不挂起, 白名单内放行; X-Nyral-Task-ID 标识任务来源
        }.start()
    }

    /** 定时岗位完成通知(2026-10-13): 静默执行完成后弹低重要性通知, 点击进主界面 */
    private fun notifyTaskDone(ctx: Context, task: TimerTaskDb.Task, reply: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(ctx, task.id.toInt(),
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("定时任务完成: " + task.name)
                .setContentText(reply.take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(reply.take(200)))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            nm.notify(NOTIF_DONE_ID, notif)
        } catch (e: Exception) {
            android.util.Log.w("TimerTask", "完成通知失败: " + e.message)
        }
    }

    /**
     * 把本次触发与结果写入绑定会话(2026-10-13): user 消息=任务触发指令, assistant 消息=执行结果。
     * 会话列表按模式隔离, 结果只出现在绑定会话所在模式。
     */
    private fun writeToSession(sessionId: Long, task: TimerTaskDb.Task, userMsg: String, reply: String) {
        if (sessionId <= 0L) return
        try {
            val mdb = MemoryDb(this)
            val msgs = mdb.loadSessionMessages(sessionId).toMutableList()
            val nowTs = System.currentTimeMillis()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val dateStr = fmt.format(java.util.Date(nowTs))
            msgs.add(MemoryDb.SessionMsg("user", userMsg, ts = nowTs, date = dateStr))
            msgs.add(MemoryDb.SessionMsg("assistant", reply, ts = nowTs, date = dateStr))
            mdb.updateSession(sessionId, mdb.sessionTitleOf(sessionId) ?: task.name, msgs, task.mode)
        } catch (e: Exception) {
            android.util.Log.w("TimerTask", "写绑定会话失败: ${e.message}")
        }
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
        ServiceNotifGroup.unregister(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
