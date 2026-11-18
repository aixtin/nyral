package io.github.aixtin.nyral

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务对账(2026-11-18, 可观测性 P0 对账侧)
 *
 * 只回答一个问题: 我们自己的定时链路有没有把该办的事办成。只读 timer_tasks / timer_runs,
 * 不改闹钟、不补跑、不碰 UI —— 补跑是 TimerRecovery 的职责, 对账只负责"记账并上报"。
 *
 * 判定口径(cron 目前只支持"每天 HH:mm", 故每任务只需判定"最近一个已到期槽位"):
 *   1. 槽位还没过判定宽限(GRACE_MS) -> 可能正在跑, 不判
 *   2. 台账有该槽位行:
 *        ok=-1 且超过 ZOMBIE_MS 未收尾 -> timer_stuck    (blocked: 认领后无终态, 典型执行中被杀)
 *        ok=0                          -> timer_failed   (结果里明确提到超时/中断/被杀 -> blocked, 否则 unknown)
 *        ok=1 但 result 空/极短        -> timer_result_empty (unknown: 跑完没产出)
 *        ok=1 但 result 以"失败"开头   -> timer_result_mismatch (unknown: 台账自相矛盾)
 *   3. 台账无该槽位行:
 *        lastRunTs 已覆盖该槽位      -> 台账被裁(只留最近 200 行), 不判, 避免误报
 *        lastSlotTs 已覆盖该槽位      -> timer_run_absent (unknown: 闸门认领了却没落占位行)
 *        从未认领                     -> timer_missed, why 见 whyOfMissed()
 *
 * why 四值: alarm_absent(闹钟没响/进程没被拉起) / perm_missing(精确闹钟或通知权限缺失)
 *          / blocked(跑到一半被系统打断) / unknown(定位不到具体环节)
 *
 * 触发时机: 冷启动、定时任务执行结束后。可反复扫描 —— 同一槽位同一结论靠 dedupKey 只上报一次。
 */
object TimerReconciler {
    private const val TAG = "TimerReconciler"

    /** 判定宽限: 槽位过期这么久仍无终态台账, 才认定"没办成"; 小于此值视为可能还在跑 */
    const val GRACE_MS = 15 * 60 * 1000L

    /** 认领后超过这么久仍无终态, 判定为执行中被杀 */
    private const val ZOMBIE_MS = 30 * 60 * 1000L

    private const val PREFS = "obs_recon"
    private const val KEY_SEEN_PREFIX = "seen:"

    private val slotFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 后台扫描, 不阻塞调用方(冷启动路径上尤其重要) */
    fun sweepAsync(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        Thread({
            try {
                sweep(app, reason)
            } catch (e: Exception) {
                Log.w(TAG, "对账异常(" + reason + "): " + e.message)
            }
        }, "obs-recon").start()
    }

    fun sweep(ctx: Context, reason: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val db = try {
            TimerTaskDb(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "对账读不到任务库: " + e.message)
            return
        }
        val tasks = try {
            db.all()
        } catch (e: Exception) {
            Log.w(TAG, "对账读任务列表失败: " + e.message)
            return
        }
        val now = System.currentTimeMillis()
        var judged = 0
        for (t in tasks) {
            try {
                if (!t.enabled) continue
                // 首次见到该任务只登记时间, 不判定: 避免把"装应用之前/建任务之前"的槽位当事故
                val seenKey = KEY_SEEN_PREFIX + t.id
                val seenAt = prefs.getLong(seenKey, 0L)
                if (seenAt <= 0L) {
                    prefs.edit().putLong(seenKey, now).apply()
                    Log.i(TAG, "任务 " + t.id + " 首次纳入对账, 本轮只登记")
                    continue
                }
                val due = TimerScheduler.latestDueSlot(t.cronDesc, now)
                if (due <= 0L) {
                    ObsEvent.emit(
                        ctx, "timer_cron_invalid", "unknown",
                        mapOf(
                            "task" to t.id.toString(),
                            "task_name" to U.redact(t.name).take(32),
                            "cron" to t.cronDesc,
                            "reason" to reason
                        ),
                        "timer:" + t.id + ":cron:" + t.cronDesc
                    )
                    continue
                }
                if (due < seenAt) continue                 // 槽位早于纳入对账的时刻
                if (now - due < GRACE_MS) continue         // 还在宽限内, 可能正在跑
                judged++
                judge(ctx, db, t, due, now, reason)
            } catch (e: Exception) {
                Log.w(TAG, "任务 " + t.id + " 判定失败: " + e.message)
            }
        }
        Log.i(TAG, "对账完成 reason=" + reason + " 任务=" + tasks.size + " 判定=" + judged)
        // 2026-11-18: 一轮对账最多攒出几条事件, 结束时统一发一次, 一次 POST 带上同批全部事件。
        // 这里不等 5 分钟周期, 是因为冷启动后进程随时可能被系统回收; 也不逐条发, 避免多条事件各打一次网络。
        ObsEvent.flushAsync(ctx)
    }

    private fun judge(ctx: Context, db: TimerTaskDb, t: TimerTaskDb.Task, due: Long, now: Long, reason: String) {
        val lagS = (now - due) / 1000
        val common = HashMap<String, String>()
        common["task"] = t.id.toString()
        common["task_name"] = U.redact(t.name).take(32)
        common["cron"] = t.cronDesc
        common["slot"] = slotFmt.format(Date(due))
        common["lag_s"] = lagS.toString()
        common["last_slot"] = if (t.lastSlotTs > 0L) slotFmt.format(Date(t.lastSlotTs)) else "none"
        common["gap_h"] = if (t.lastRunTs > 0L) ((now - t.lastRunTs) / 3600_000L).toString() else "none"
        common["reason"] = reason

        val run = try {
            db.runOfSlot(t.id, due)
        } catch (e: Exception) {
            Log.w(TAG, "读台账失败 task=" + t.id + ": " + e.message)
            return
        }

        // ---- 无台账行 ----
        if (run == null) {
            if (t.lastRunTs >= due) {
                // lastRunTs 已覆盖该槽位: 台账只留最近 200 行, 被裁掉了, 不能当没跑
                Log.i(TAG, "任务 " + t.id + " slot=" + slotFmt.format(Date(due)) + " 台账已裁, lastRunTs 覆盖, 不判")
                return
            }
            if (t.lastSlotTs >= due) {
                common["lane"] = "claimed_no_row"
                emit(ctx, t, due, "timer_run_absent", "unknown", common)
                return
            }
            common["lane"] = "never_claimed"
            common["exact_alarm"] = if (canExactAlarm(ctx)) "1" else "0"
            common["notify_perm"] = if (canNotify(ctx)) "1" else "0"
            emit(ctx, t, due, "timer_missed", whyOfMissed(ctx), common)
            return
        }

        common["run_ok"] = run.okInt.toString()
        common["run_start"] = if (run.startTs > 0L) slotFmt.format(Date(run.startTs)) else "0"
        common["run_end"] = if (run.endTs > 0L) slotFmt.format(Date(run.endTs)) else "0"
        val res = U.redact(run.result ?: "")
        common["run_result"] = res.take(120)
        common["catch_up"] = if (run.catchUp) "1" else "0"

        when {
            run.okInt < 0 -> {
                val stuckMs = if (run.startTs > 0L) now - run.startTs else now - due
                if (stuckMs < ZOMBIE_MS) {
                    Log.i(TAG, "任务 " + t.id + " slot=" + slotFmt.format(Date(due)) + " 执行中, 暂不判")
                    return
                }
                common["lane"] = "no_terminal"
                common["stuck_s"] = (stuckMs / 1000).toString()
                emit(ctx, t, due, "timer_stuck", "blocked", common)
            }
            run.okInt == 0 -> {
                common["lane"] = "failed"
                emit(ctx, t, due, "timer_failed", whyOfFailed(res), common)
            }
            else -> {
                val body = res.trim()
                when {
                    body.isEmpty() || body.length < 2 -> {
                        common["lane"] = "empty_result"
                        emit(ctx, t, due, "timer_result_empty", "unknown", common)
                    }
                    body.startsWith("失败") -> {
                        common["lane"] = "mismatch"
                        emit(ctx, t, due, "timer_result_mismatch", "unknown", common)
                    }
                    else -> Log.i(TAG, "任务 " + t.id + " slot=" + slotFmt.format(Date(due)) + " 正常")
                }
            }
        }
    }

    private fun emit(
        ctx: Context,
        t: TimerTaskDb.Task,
        due: Long,
        kind: String,
        why: String,
        fields: Map<String, String>
    ) {
        ObsEvent.emit(ctx, kind, why, fields, "timer:" + t.id + ":" + due + ":" + kind)
        Log.w(TAG, "对账事件 " + kind + " why=" + why + " task=" + t.id + " slot=" + slotFmt.format(Date(due)))
    }

    /**
     * "该跑没跑"的 why 归因:
     * 精确闹钟权限被收回(Android 12+) 或通知权限关掉(Android 13+) 都会让用户"什么都没收到",
     * 对用户而言与没跑等价, 归 perm_missing; 权限都在却仍没被执行记录, 只能是闹钟没响/进程没被拉起,
     * 归 alarm_absent。
     */
    private fun whyOfMissed(ctx: Context): String {
        return if (!canExactAlarm(ctx) || !canNotify(ctx)) "perm_missing" else "alarm_absent"
    }

    private fun whyOfFailed(res: String): String {
        val keys = arrayOf("超时", "中断", "被杀", "kill", "中断执行", "任务被")
        for (k in keys) {
            if (res.contains(k, ignoreCase = true)) return "blocked"
        }
        return "unknown"
    }

    private fun canExactAlarm(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            true
        }
    }

    private fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            true
        }
    }

    /** 事件上报前的轻量脱敏: 邮箱 / user@host / URL query / 绝对路径 */
    private object U {
        private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val userhost = Regex("\\b\\w+@[\\w.-]+\\b")
        private val query = Regex("([?&][^=\\s&]+)=([^&\\s]+)")
        private val absPath = Regex("(/[A-Za-z0-9._-]+){2,}")

        fun redact(s: String): String {
            var x = s
            x = email.replace(x, "[redacted]")
            x = userhost.replace(x, "[redacted]")
            x = query.replace(x) { m -> m.groupValues[1] + "=[redacted]" }
            x = absPath.replace(x, "[path]")
            return x
        }
    }
}
