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
import android.os.SystemClock
import android.os.IBinder
import androidx.annotation.RequiresApi
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
        // 第五批(2026-11-14): 失败告警独立渠道。完成通知是 IMPORTANCE_LOW(静默不打扰),
        // 但失败必须能被看见, 所以单独开 DEFAULT 渠道, 用户可单独关掉它而不影响完成通知。
        private const val CHANNEL_ALERT_ID = "task_alert"
        private const val NOTIF_FAIL_ID = 1006

        /**
         * fix(2026-11-13): 定岗任务前台服务看门狗上限(30 分钟)。
         * 定时岗位要跑完整 AI 链路(联网+工具), 正常 1~3 分钟; 但 LocalEngine.chat 经 chatLock 单飞,
         * 若主对话长时间不释放锁、或网络请求挂死, 后台线程会一直阻塞, TaskService 前台状态不退出,
         * 直到 dataSync 6 小时配额到期被系统杀进程(2026-11-13 08:00:10 线上崩溃即此路径,
         * 与 08:00 早报闹钟同一秒, 任务被打断)。故超过上限主动收摊, 只丢这一轮, 不连累进程。
         */
        private const val TIMER_TASK_WATCHDOG_MS = 30 * 60 * 1000L

        /** 胶囊重试动作(通知 Action 携带, MainActivity.onNewIntent 处理) */
        const val ACTION_RETRY = "io.github.aixtin.nyral.action.RETRY_TASK"

        /** 定时岗位唤醒动作(2026-10-11): TimerWakeReceiver 拉起后静默执行定时任务 */
        const val ACTION_TIMER_TASK = "io.github.aixtin.nyral.action.TIMER_TASK"
        const val EXTRA_TASK_ID = "task_id"
        /** 第二批(2026-11-14): 本次投递是否为"错过补跑", 决定提示语与通知标题 */
        const val EXTRA_CATCH_UP = "catch_up"

        /**
         * 第三批(2026-11-14): 服务生命周期收敛为单实例状态机。
         * 旧实现用 fgStarted + pendingStop + pendingStopAt 三个散字段表达同一件事,
         * 曾因"补停标记与真实闹钟唤醒竞争"导致定时任务静默死亡(vc121), 状态无法静态推理。
         * IDLE=未启动/已销毁, STARTING=已请求启动未前台化, FOREGROUND=已前台化, STOPPING=前台化前被请求停止
         */
        enum class State { IDLE, STARTING, FOREGROUND, STOPPING }
        /** 当前生命周期状态 */
        @Volatile private var state = State.IDLE
        /** STOPPING 登记时刻, 超过 5s 视为失效, 避免跨对话残留吞掉真实闹钟唤醒 */
        @Volatile private var stoppingAt = 0L
        /** 兼容旧判据: 是否已完成前台化 */
        private val fgStarted: Boolean get() = state == State.FOREGROUND
        /** fix(2026-11-13): 定岗看门狗投递用主线程 Handler */
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * fix(2026-11-14): 灵动岛上岛修复。
         * 服务存活实例: updateStage 必须经由服务自身 startForeground 更新通知。
         * 旧实现用 nm.notify 更新前台服务通知, ColorOS 会把通知按普通通知刷新, promoted 提升态丢失;
         * 定时任务只在启动时发布一次通知, 所以后台任务能上岛, 而用户聊天在 thinking/tool 阶段更新后就不上岛。
         */
        @Volatile private var instance: TaskService? = null
        /** 最近一次发布的阶段文案: 切后台重新申请提升时复用 */
        @Volatile private var lastStage: String? = null

        /** 第三批(2026-11-14): 标记本次启动, 清掉可能残留的 STOPPING */
        private fun markStarting() {
            stoppingAt = 0L
            state = State.STARTING
        }

        /** 启动前台服务(Android 8+ 需 startForegroundService, 兼容旧版走 startService) */
        fun start(context: Context) {
            markStarting()
            val intent = Intent(context, TaskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * fix(2026-11-09): 定时岗位唤醒专用启动。
         * TimerWakeReceiver 原先直接 startForegroundService, 绕过了 start() 里的 pendingStop=false,
         * 导致上一轮对话在服务未前台化时登记的补停标记残留, 下一次真实闹钟被 onStartCommand 直接自停吞掉
         * (任务不执行、last_run 不变、且因不进 onDone 而不续期, 任务从此静默死亡)。
         */
        fun startTimerTask(context: Context, taskId: Long, catchUp: Boolean = false) {
            markStarting()
            val intent = Intent(context, TaskService::class.java)
                .setAction(ACTION_TIMER_TASK)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_CATCH_UP, catchUp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止前台服务 */
        fun stop(context: Context) {
            // 诊断插桩(2026-11-15): 记下谁在什么时候叫停, 定位"认领了 slot 但服务提前消失"
            try {
                val frames = Thread.currentThread().stackTrace.drop(2).take(6)
                    .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
                TimerTrace.write(context, "stop() state=" + state + " 来源: " + frames)
            } catch (_: Exception) {
            }
            // fix(2026-11-08): start() 后立刻失败(如未配置 Key 的即时 onError)会紧跟 stop(),
            // 此时服务实例可能还没创建: 直接 stopService 等于取消这次启动, 系统仍判定
            // “startForegroundService 后未 startForeground”, 约 10s 后主线程抛
            // ForegroundServiceDidNotStartInTimeException 闪退(线上 vc121 崩溃主因)。
            // 未前台化前只登记待停, 由 onStartCommand 补完前台化后立即自停。
            if (state != State.FOREGROUND) {
                state = State.STOPPING
                stoppingAt = SystemClock.elapsedRealtime()
            } else {
                context.stopService(Intent(context, TaskService::class.java))
            }
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
        // fix(2026-11-08): 前台化必须最先做, 其余 init 全部后移。
        // 原实现先跑 ApiConfig/MemoryApiConfig/ModeConfig.init(SharedPreferences + Keystore 解密,
        // 冷启动首读是 Binder 重活)再 startForeground, 叠加主线程其他重活即超系统 FGS 时限,
        // 抛 ForegroundServiceDidNotStartInTimeException 闪退(线上 6 次, 跨 4 品牌)。
        createChannel()
        startForegroundCompat()
        fgStarted = true
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        ModeConfig.init(this)
        ServiceNotifGroup.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等: onCreate 已启动前台, 重复调用 startForeground 无副作用
        startForegroundCompat()
        // 第三批(2026-11-14): 走到这里说明已前台化, STARTING -> FOREGROUND;
        // 若 onCreate 前就被 stop() 请求过则保持 STOPPING, 交给末尾补停分支收摊
        if (state == State.STARTING) state = State.FOREGROUND
        // fix(2026-11-09): 定时唤醒必须优先于 pendingStop 补停处理。
        // 旧实现先消费 pendingStop 再判断 ACTION_TIMER_TASK, 只要用户做过一次普通对话
        // (对话结束 stop() 时服务已销毁 → fgStarted=false → 登记 pendingStop),
        // 下一次真实闹钟 100% 被自停吞掉: 任务不执行、不续期, UI 仍显示"启用中"。
        // fix(2026-11-14): 定时唤醒必须优先于 pendingStop 补停处理。
        if (intent?.action == ACTION_TIMER_TASK) {
            // 只清 STOPPING 残留(上一轮对话 stop() 晚到), 不得把已前台化的 FOREGROUND 打回 STARTING:
            // 一旦停在 STARTING, 执行结束时 onDone 里的 stop() 会误判"未前台化"而只登记 STOPPING,
            // 前台服务永不退出(真机 2026-11-14 17:56 实测复现: 任务已完成后 TaskService 仍 isForeground=true)
            stoppingAt = 0L
            state = State.FOREGROUND
            ServiceNotifGroup.publishSummary(this)
            val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
            val catchUp = intent.getBooleanExtra(EXTRA_CATCH_UP, false)
            if (taskId > 0) {
                // 第二批(2026-11-14): 投递闸门 —— 早投递栅栏 + slot 原子占位, 挡住"一天两次触发"
                val admitted = try {
                    TimerDispatch.admit(this, taskId, catchUp)
                } catch (e: Exception) {
                    android.util.Log.e("TimerTask", "投递闸门异常, 按放行处理: " + e.message)
                    true
                }
                if (!admitted) {
                    android.util.Log.i("TimerTask", "投递被闸门拦截 task=" + taskId + " catchUp=" + catchUp)
                    val desc = try {
                        TimerTaskDb(this).get(taskId)?.cronDesc.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    TimerScheduler.rescheduleNext(this, taskId, desc)
                    TaskService.stop(this)
                    return START_NOT_STICKY
                }
                // fix(2026-11-14): 触发即续期 —— 本轮中断也不会永久失调度
                TimerScheduler.rescheduleNext(this, taskId)
                runTimerTask(taskId, catchUp)
            } else TaskService.stop(this)
            return START_NOT_STICKY
        }
        // fix(2026-11-08): stop() 在实例创建前到达时只登记 pendingStop; 这里补完前台化后立即自停,
        // 保证“started 且已 startForeground”, 不留 FGS 超时判定
        // fix(2026-11-09): 补停只对 5s 内的登记有效, 过期残留一律忽略, 不再静默吞启动
        if (state == State.STOPPING && SystemClock.elapsedRealtime() - stoppingAt < 5000L) {
            val waited = SystemClock.elapsedRealtime() - stoppingAt
            state = State.IDLE
            android.util.Log.w("TaskService", "pendingStop 补停(启动即停, 距登记 ${waited}ms)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        state = State.FOREGROUND
        ServiceNotifGroup.publishSummary(this)
        return START_NOT_STICKY
    }

    /**
     * 定时岗位静默执行(2026-10-11):
     * 构造带任务目标的历史 -> LocalEngine.chat 后台跑完整链路; 回调只落库+审计, 不碰 UI。
     * AI 忙时 LocalEngine 内部互斥返回 false, 本次跳过留待下轮; 完成后自动停前台服务。
     */
    private fun runTimerTask(taskId: Long, catchUp: Boolean = false) {
        TimerTrace.write(this, "runTimerTask 进入 task=" + taskId + " catchUp=" + catchUp)
        val db = TimerTaskDb(this)
        val runStartTs = System.currentTimeMillis()
        val task = db.get(taskId) ?: run { TaskService.stop(this); return }
        if (!task.enabled) { TaskService.stop(this); return }
        // fix(2026-11-15): 台账占位前移到"真正开跑之前"。
        // 原先只有 onDone/onError 才写 timer_runs, 执行途中被系统杀掉(Doze 回收/厂商清理/FGS 配额到期)
        // 就彻底无痕: slot 已被认领 + 无台账 + last_run 不变, 事后完全看不出"跑过但没跑完",
        // 补跑判据还把已认领当跑过 —— 这一次就永久丢了(2026-11-14 08:00 早报即此形态)。
        val runSlotTs = task.lastSlotTs
        val runId = db.beginRun(taskId, runSlotTs, runStartTs, catchUp)
        TimerTrace.write(this, "beginRun runId=" + runId + " slot=" + runSlotTs + " sessionId=" + task.sessionId)
        // 专属会话绑定(2026-10-13): 创建时已建会话; 旧任务(测试用)首次触发时补绑, 模式用任务固化值(旧任务默认 Agent 模式)
        var sessionId = task.sessionId
        if (sessionId <= 0L) {
            sessionId = MemoryDb(this).saveSession(task.name, emptyList(), task.mode, MemoryDb.KIND_TIMER)
            db.bindSession(taskId, sessionId)
        }
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        // 能力预授权(2026-10-13): 解析任务白名单(逗号分隔 key), 静默执行命中即放行危险工具
        val allowTools = task.allowTools.split(',').map { SecurityConfig.normalizeAllowKey(it) }.filter { it.isNotEmpty() }.toSet()
        val history = "[当前时间]\n" + now + "\n\n" +
            (if (catchUp) "[投递类型]\n补跑 —— 该任务上一次定时触发因设备关机/进程被杀等原因被错过, 本次是对那一次的补执行, 请照常执行并在摘要开头标注[补跑]\n\n" else "") +
            "[定时岗位任务]\n" +
            "任务名: " + task.name + "\n" +
            "目标: " + task.goal + "\n" +
            "请作为 Nyral 后台静默执行该定时任务, 完成后输出简洁结果摘要, 不需要请示确认。"
        android.util.Log.i("TimerTask", "定时岗位执行开始: ${task.name}" + (if (catchUp) " (补跑)" else ""))
        // fix(2026-11-13): 起看门狗, 超时主动收摊, 避免前台服务被定岗线程拖住直到配额到期杀进程
        val watchdog = Runnable {
            android.util.Log.w("TimerTask", "定时岗位执行超时(30min), 强制停止前台服务: ${task.name}")
            // fix(2026-11-15): 超时是新的一条"跑过但没跑完"路径, 也必须留终态, 否则台账停在 ok=-1 无从判断
            try {
                db.setLastRun(taskId, System.currentTimeMillis(), "失败: 超时(30min)未返回")
                db.finishRun(runId, taskId, runSlotTs, runStartTs, false, catchUp, "失败: 超时(30min)未返回")
            } catch (_: Exception) {
            }
            try {
                SecurityConfig.audit(this, "timer_task_timeout",
                    "${task.name} 超过 30 分钟未返回, 已强制停止前台服务", "timer_task", "timer_task")
            } catch (_: Exception) {
            }
            TaskService.stop(this)
        }
        mainHandler.postDelayed(watchdog, TIMER_TASK_WATCHDOG_MS)
        // 2026-10-11 fix: LocalEngine 网络请求必须在后台线程执行(主线程会抛 NetworkOnMainThreadException)
        val cb = object : LocalEngine.Callback {
            override fun onThinkingStart() {}
            override fun onThinkingDelta(text: String) {}
            override fun onThinkingEnd() {}
            override fun onTool(name: String, arg: String) {}
            override fun onDelta(text: String) {}
            override fun onDone(reply: String) {
                mainHandler.removeCallbacks(watchdog)
                TimerTrace.write(this@TaskService, "onDone task=" + taskId + " len=" + reply.length)
                db.setLastRun(taskId, System.currentTimeMillis(), reply.take(500))
                // 第四批(2026-11-14): 台账留痕, slot 直接取任务行上已认领的值, 不重算
                // fix(2026-11-15): 改成收尾 beginRun 落的占位行, 避免同一轮出现"占位行 + 终态行"两条
                db.finishRun(runId, taskId, runSlotTs, runStartTs, true, catchUp, reply.take(300))
                writeToSession(sessionId, task, history, reply)
                SecurityConfig.audit(this@TaskService, "timer_task_run",
                    "${task.name} -> ${reply.take(120)}", "alarm", "timer_task")
                android.util.Log.i("TimerTask", "定时岗位执行完成: ${task.name}")
                notifyTaskDone(this@TaskService, task, reply, catchUp)
                // fix(2026-11-15): 收尾时顺手巡检——别让其它任务因"认领后没跑完"永久失调度
                TimerRecovery.sweepCatchUpAsync(this@TaskService, "定时任务收尾")
                TaskService.stop(this@TaskService)
            }
            override fun onError(msg: String) {
                mainHandler.removeCallbacks(watchdog)
                TimerTrace.write(this@TaskService, "onError task=" + taskId + ": " + msg)
                db.setLastRun(taskId, System.currentTimeMillis(), "失败: $msg")
                db.finishRun(runId, taskId, runSlotTs, runStartTs, false, catchUp, "失败: $msg")
                writeToSession(sessionId, task, history, "执行失败: $msg")
                SecurityConfig.audit(this@TaskService, "timer_task_run",
                    "${task.name} -> 失败: $msg", "alarm", "timer_task")
                android.util.Log.w("TimerTask", "定时岗位执行失败: ${task.name}: $msg")
                // 第五批(2026-11-14): 失败原先只写进 timer_runs 台账, 用户在通知栏一无所知,
                // 表现就是"这个任务好像从来没跑过"。失败必须有可见通道。
                notifyTaskFailed(this@TaskService, task, msg, catchUp)
                TaskService.stop(this@TaskService)
            }
        }
        Thread {
            // C1 修复(2026-10-20): 取消状态重置已移入 LocalEngine.chat 门卫(持锁后统一处理), 避免等待期间误清主对话取消状态; 定时任务与主对话经 chatLock 单飞互斥
            TimerTrace.write(this@TaskService, "chat 线程开始 task=" + taskId)
            try {
                LocalEngine.chat(this@TaskService, history, cb, silentMode = true, allowTools = allowTools, taskId = taskId) // 定时岗位静默: 未授权危险工具自动拒绝不挂起, 白名单内放行; X-Nyral-Task-ID 标识任务来源
                TimerTrace.write(this@TaskService, "chat 返回(未抛异常) task=" + taskId)
            } catch (e: Throwable) {
                // fix(2026-11-15): 原先 chat 抛出的异常会直接打死这个线程, 既不落台账也不回调,
                // 服务留在前台等 30 分钟看门狗 —— 外部看就是"认领了但没跑完且长时间无声"。这里兜住。
                TimerTrace.write(this@TaskService, "chat 抛异常 task=" + taskId + ": " + e)
                try { cb.onError("执行异常: " + (e.message ?: e.javaClass.simpleName)) } catch (_: Exception) {
                }
            }
        }.start()
    }

    /** 定时岗位完成通知(2026-10-13): 静默执行完成后弹低重要性通知, 点击进主界面 */
    private fun notifyTaskDone(ctx: Context, task: TimerTaskDb.Task, reply: String, catchUp: Boolean = false) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(ctx, task.id.toInt(),
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle((if (catchUp) "补跑完成: " else "定时任务完成: ") + task.name)
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
     * 定时岗位失败告警(2026-11-14 第五批)。
     *
     * 失败原因九成是环境问题(未配 API Key、自启动/精确闹钟被关、后台被限流), 所以点击直接进
     * 「定时岗位体检」而不是主界面 —— 让用户一眼看到哪一项没就绪, 而不是只看到一句失败原文。
     * 固定 NOTIF_FAIL_ID: 连续失败只覆盖同一条通知, 不刷屏。
     */
    private fun notifyTaskFailed(ctx: Context, task: TimerTaskDb.Task, msg: String, catchUp: Boolean = false) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // requestCode 与完成通知错开(task.id vs 5000+task.id), 否则 PendingIntent 会复用同一个
            val pi = PendingIntent.getActivity(ctx, 5000 + task.id.toInt(),
                Intent(ctx, TimerHealthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ALERT_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle((if (catchUp) "补跑失败: " else "定时任务失败: ") + task.name)
                .setContentText(msg.take(80) + "  (点击检查定时岗位体检)")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(msg.take(300) + "\n\n点击进入定时岗位体检, 检查精确闹钟、自启动、电池优化等前置条件。"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            nm.notify(NOTIF_FAIL_ID, notif)
        } catch (e: Exception) {
            android.util.Log.w("TimerTask", "失败告警通知发送失败: " + e.message)
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
        fgStarted = false
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

    /**
     * fix(2026-11-13): Android 15(API35) 起 dataSync 型前台服务有 6 小时运行配额(单次/24h 累计),
     * 到期由 system_server 回调本方法; 若不在回调内主动停服, 系统直接抛
     * RemoteServiceException$ForegroundServiceDidNotStopInTimeException 杀掉整个进程。
     * 2026-11-13 08:00:10 线上崩溃即此路径(Process-Runtime 15493976ms, 与 08:00 早报闹钟同一秒),
     * 该轮早报任务被连带打断且未留存档。
     * 此处兜底: 记录审计 + 移除前台通知 + 自停, 保进程活着, 剩余工作交给看门狗与下次闹钟。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        android.util.Log.w("TaskService", "onTimeout: dataSync 前台配额到期, 主动停止服务 startId=$startId")
        try {
            SecurityConfig.audit(this, "fgs_timeout", "dataSync 前台配额到期, 服务主动停止(startId=$startId)", "system", "service")
        } catch (_: Exception) {
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * 定时任务链路落盘追踪(2026-11-15, 临时诊断)。
 * 起因: 一加/ColorOS 对 release 包的 I 级日志做了过滤, `adb logcat --pid` 看不到 TaskService 的任何
 * Log 输出, 导致"认领了 slot 却没产物、没台账"的断点无法定位。这里改写到 App 私有目录,
 * 用 `su -c cat files/timer_trace.log` 就能读到, 不受 ROM 日志策略影响。
 * 定位结束后可移除(或保留, 体积上限 128KB 自动轮转)。
 */
object TimerTrace {
    fun write(ctx: Context, msg: String) {
        try {
            val f = java.io.File(ctx.filesDir, "timer_trace.log")
            if (f.exists() && f.length() > 128 * 1024) f.delete()
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText(ts + " " + msg + "\n")
        } catch (_: Exception) {
        }
    }
}
