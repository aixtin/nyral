package io.github.aixtin.nyral

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * 定时岗位重排总入口(2026-11-10): 三层触发源共用, 幂等。
 * L1 广播(TimerBootReceiver) / L2 冷启动(NyralApp.onCreate) / L3 persisted Job(TimerBootJobService)
 *
 * 为什么不能只依赖 BOOT_COMPLETED: ColorOS BootPressureHolder 会把第三方包的开机广播压进延迟队列,
 * 一加重启后实测 pidof 为空、dumpsys alarm 归零, AI 通道 bind 拉起进程也不重排 —— 唯一入口失效,
 * 一次性 setAlarmClock 已随重启消失且无人续期, 任务永久静默死亡(UI 仍显"启用中")。
 * 竞品交叉验证: MacroDroid 用 INT_MAX priority + 多判据去重, Tasker 用 999 priority + 自定义 WILLYUM/AWAKEY
 * 通道, 两家都没有对抗厂商拦截的黑科技, 靠的都是"多个重排触发源 + 广播后先立进程"。
 */
object TimerRecovery {

    private const val TAG = "TimerBoot"
    private const val PREFS = "timer_boot_state"
    private const val KEY_BOOT_COUNT = "last_boot_count"
    private const val KEY_BOOT_ELAPSED = "last_boot_elapsed"
    private const val KEY_LAST_REASON = "last_reason"

    /** 第二批(2026-11-14): 错过补跑的基线与节流状态 */
    private const val CATCHUP_PREFS = "timer_catchup_state"
    private const val KEY_BASELINED = "baselined"
    private const val CATCHUP_THROTTLE_MS = 24 * 60 * 60 * 1000L

    /**
     * 判定是否需要重排, 需要则全量 cancel+schedule。
     * 触发条件(命中任一): ① 本次开机 boot_count 与上次记录不同(或 uptime 倒退); ② 任一启用任务的闹钟 PendingIntent 已不存在。
     * @return true 表示本次确实执行了重排
     */
    @Synchronized
    fun ensure(context: Context, reason: String, force: Boolean = false): Boolean {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bootCount = readBootCount(ctx)
        val elapsed = SystemClock.elapsedRealtime()
        val lastBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        val lastElapsed = prefs.getLong(KEY_BOOT_ELAPSED, 0L)

        // 判据一: boot_count 变化 => 新开机; 取不到 boot_count(旧 ROM/被限制)时退回 uptime 倒退判断
        val rebooted = when {
            bootCount >= 0 && lastBootCount >= 0 -> bootCount != lastBootCount
            else -> lastElapsed <= 0L || elapsed < lastElapsed
        }

        val tasks = try {
            TimerTaskDb(ctx).all()
        } catch (e: Exception) {
            Log.e(TAG, "$reason: 读取任务失败 ${e.message}")
            return false
        }
        val enabled = tasks.filter { it.enabled }

        // 判据二: 闹钟 PendingIntent 不存在 => 一定没排(重启/被清/被杀后无所谓, 反正丢了)
        val missing = enabled.filter {
            try { !TimerScheduler.hasAlarm(ctx, it.id) } catch (e: Exception) { true }
        }

        TimerTrace.write(ctx, "ensure 进入 reason=" + reason + " force=" + force + " rebooted=" + rebooted + " enabled=" + enabled.size + " missing=" + missing.size)
        // force=true: 冷启动通道(L2)无条件重排。force-stop / 厂商清理后 AlarmManager 里的闹钟已被移除,
        // 但 PendingIntentRecord 与锚点都还在, 任何"是否齐全"的探测都会误判(红米实测),
        // 所以这一步不探测, 直接幂等 cancel+schedule —— 与 MacroDroid 开机无条件重排同思路。
        if (!force && !rebooted && missing.isEmpty()) {
            Log.i(TAG, "$reason: 无需重排(启用 ${enabled.size} 个, 闹钟齐全)")
            TimerTrace.write(ctx, "ensure 早退: 闹钟齐全且非冷启动, 不补跑")
            return false
        }

        // 先全量取消旧闹钟(含已禁用任务), 再排启用任务, 防禁用后 *walarm* 残留到点仍触发
        for (t in tasks) {
            try { TimerScheduler.cancel(ctx, t.id) } catch (e: Exception) { }
        }
        var n = 0
        for (t in enabled) {
            try {
                TimerScheduler.schedule(ctx, t.id, t.cronDesc)
                n++
            } catch (e: Exception) {
                Log.e(TAG, "$reason: 任务 ${t.id} 重排失败 ${e.message}")
            }
        }
        // 第二批(2026-11-14): 重排只能排"下一次", 被关机/被杀期间错过的那一次必须显式补跑
        catchUpMissed(ctx, enabled, reason)
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, bootCount)
            .putLong(KEY_BOOT_ELAPSED, elapsed)
            .putString(KEY_LAST_REASON, reason)
            .apply()
        Log.i(TAG, "$reason: 重排 $n 个启用任务(新开机=$rebooted, 闹钟缺失=${missing.size})")
        if (n > 0) {
            try { TimerKeepAliveService.start(ctx) } catch (e: Exception) {
                Log.e(TAG, "$reason: 保活服务拉起失败 ${e.message}")
            }
        }
        // 重排成功即顺手补注册兜底 job: 一加实测 job 在 bind 失败时会被 JobScheduler 直接丢弃(不重试),
        // 有进程活着时补一次册, 保证下次重启仍有兜底通道
        TimerBootJobService.ensureScheduled(ctx)
        return true
    }

    /**
     * 补跑巡检入口(fix 2026-11-15): 供定时任务收尾时顺手调用。
     * 补跑原先只在 ensure()(开机/冷启动/闹钟缺失)时触发, 若用户整天不开 App 也不重启,
     * 僵尸认领会一直躺到下次开机才被发现。每轮定时任务结束扫一次, 频率由每任务 24h 节流兜住。
     */
    fun sweepCatchUpAsync(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        Thread {
            try {
                val tasks = TimerTaskDb(app).all().filter { it.enabled }
                catchUpMissed(app, tasks, reason)
            } catch (e: Exception) {
                Log.e(TAG, "$reason: 补跑巡检异常 ${e.message}")
            }
        }.start()
    }

    /**
     * 错过补跑(2026-11-14, 第二批): 判据 = 最近一个到期 slot 既没被认领(last_slot_ts) 也没跑过(last_run_ts)。
     * 原型 Past Rearm.catchUp(): missed = enabled && due 已过 && lastRunAt < due && 台账未认领。
     * 两个护栏:
     * ① 基线初始化 —— 升级前没有 last_slot_ts 字段, 首次运行把当前 slot 视为已处理, 避免全量补跑风暴;
     * ② 节流 —— 同一任务 24h 内最多补 1 次, 避免重排风暴(冷启动/保活反复拉 ensure)放大成刷屏。
     * 补跑走 TaskService.startTimerTask(catchUp=true), 与正常投递共用同一套 slot 闸门。
     */
    private fun catchUpMissed(ctx: Context, tasks: List<TimerTaskDb.Task>, reason: String) {
        TimerTrace.write(ctx, "catchUpMissed 进入 reason=" + reason + " tasks=" + tasks.size)
        if (tasks.isEmpty()) return
        val prefs = ctx.getSharedPreferences(CATCHUP_PREFS, Context.MODE_PRIVATE)
        val db = try {
            TimerTaskDb(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "$reason: 补跑读取任务库失败 ${e.message}")
            return
        }
        val now = System.currentTimeMillis()

        if (!prefs.getBoolean(KEY_BASELINED, false)) {
            var filled = 0
            for (t in tasks) {
                val due = TimerScheduler.latestDueSlot(t.cronDesc, now)
                if (due > 0L && t.lastSlotTs < due && db.tryClaimSlot(t.id, due)) filled++
            }
            TimerTrace.write(ctx, "补跑基线初始化, 回填 " + filled + " 个 slot, 本次不补跑")
            prefs.edit().putBoolean(KEY_BASELINED, true).apply()
            Log.i(TAG, "$reason: 补跑基线初始化(旧数据无 slot 记录, 回填 " + filled + " 个, 本次不补跑)")
            return
        }

        for (t in tasks) {
            val due = TimerScheduler.latestDueSlot(t.cronDesc, now)
            if (due <= 0L) continue
            // fix(2026-11-15): "已认领" 不再直接算跑过。
            // 原先 t.lastSlotTs >= due 就 continue, 而认领发生在执行之前 —— 认领后进程被杀,
            // 这一 slot 既没产物也没台账, 却因为"已认领"永远不补跑, 等于永久丢一次。
            // 现在要求: 认领了还要有终态台账(ok=0/1)才算跑过; 只有 ok=-1 占位或被认领后根本没起来,
            // 且超过宽限期, 判为僵尸认领, 允许补跑(交 TimerDispatch 重新接管 slot)。
            var zombie = false
            if (t.lastSlotTs >= due) {
                // 跑完的硬证据: onDone/onError 都会刷新 last_run_ts(旧版本还没有台账, 只能靠它兜底),
                // 而认领后被杀则 last_run 一定还停在旧值 —— 所以"已认领 + last_run 已越过 due" = 真跑过
                if (t.lastRunTs >= due) continue
                if (db.hasFinishedRun(t.id, due)) continue
                if (now - due < TimerDispatch.ZOMBIE_GRACE_MS) continue
                zombie = true
            } else {
                // 老字段兜底: 上次执行时间已覆盖该 slot
                if (t.lastRunTs >= due) continue
            }
            TimerTrace.write(ctx, "任务 " + t.id + " slot=" + TimerScheduler.fmtSlot(due) + " lastSlot=" + TimerScheduler.fmtSlot(t.lastSlotTs) + " lastRun=" + TimerScheduler.fmtSlot(t.lastRunTs) + " zombie=" + zombie)
            if (now - prefs.getLong("t:" + t.id, 0L) < CATCHUP_THROTTLE_MS) {
                TimerTrace.write(ctx, "任务 " + t.id + " 24h 节流中, 跳过")
                continue
            }
            prefs.edit().putLong("t:" + t.id, now).apply()
            Log.i(TAG, "$reason: 任务 " + t.id + "(" + t.name + ") " +
                (if (zombie) "slot=" + TimerScheduler.fmtSlot(due) + " 已认领但无终态台账(僵尸认领)" else "错过 slot=" + TimerScheduler.fmtSlot(due)) +
                ", 触发补跑")
            try {
                TimerTrace.write(ctx, "补跑拉起 task=" + t.id)
                TaskService.startTimerTask(ctx, t.id, catchUp = true)
            } catch (e: Exception) {
                TimerTrace.write(ctx, "补跑拉起失败 task=" + t.id + ": " + e)
                Log.e(TAG, "$reason: 任务 " + t.id + " 补跑拉起失败 ${e.message}")
            }
        }
    }

    /** 异步版本: 供 onCreate / 广播 onReceive 使用, 避免主线程碰 SQLite 与 AlarmManager binder */
    fun ensureAsync(context: Context, reason: String, force: Boolean = false) {
        val app = context.applicationContext
        Thread {
            try {
                ensure(app, reason, force)
            } catch (e: Exception) {
                TimerTrace.write(app, "ensure 异常 reason=" + reason + ": " + e)
                Log.e(TAG, "$reason: 重排异常 ${e.message}")
            }
        }.start()
    }

    private fun readBootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }
}
