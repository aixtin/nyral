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

        // force=true: 冷启动通道(L2)无条件重排。force-stop / 厂商清理后 AlarmManager 里的闹钟已被移除,
        // 但 PendingIntentRecord 与锚点都还在, 任何"是否齐全"的探测都会误判(红米实测),
        // 所以这一步不探测, 直接幂等 cancel+schedule —— 与 MacroDroid 开机无条件重排同思路。
        if (!force && !rebooted && missing.isEmpty()) {
            Log.i(TAG, "$reason: 无需重排(启用 ${enabled.size} 个, 闹钟齐全)")
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

    /** 异步版本: 供 onCreate / 广播 onReceive 使用, 避免主线程碰 SQLite 与 AlarmManager binder */
    fun ensureAsync(context: Context, reason: String, force: Boolean = false) {
        val app = context.applicationContext
        Thread {
            try {
                ensure(app, reason, force)
            } catch (e: Exception) {
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
