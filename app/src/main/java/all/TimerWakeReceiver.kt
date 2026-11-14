package io.github.aixtin.nyral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * 定时岗位唤醒接收器(2026-10-11):
 * AlarmManager 到点触发 -> 校验任务仍启用 -> 拉起 TaskService 静默后台执行。
 * 唤醒路径带 X-Nyral-Task-ID 语义(以 extra task_id 传递), 普通对话无此标记。
 *
 * 第三批(2026-11-14): 改为 goAsync() + 单线程 IO 执行。
 * 旧实现在 onReceive 主线程同步查 SQLite, 冷启动场景下 TimerTaskDb 首次建连
 * (打开库 + WAL 恢复 + 版本迁移)可能数十毫秒到数百毫秒, 叠加锁竞争有 ANR/广播超时风险,
 * 一旦超时投递丢失, 该 slot 又已被占位, 任务当天静默不跑。
 */
class TimerWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimerWakeReceiver"
        /** 预算: 广播 onReceive 的 goAsync 允许窗口内必须 finish(), 超时系统判定接收器无响应 */
        private const val GO_ASYNC_BUDGET_MS = 8_000L
        /** 串行执行, 避免同一时刻多条闹钟并发打开数据库互相加锁 */
        private val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "timer-wake-io").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra("task_id", -1L) ?: -1L
        if (taskId <= 0) return
        // 第三批(2026-11-14): 先拿 goAsync 令牌再下 IO, 主线程立即返回
        val pending = goAsync()
        val appCtx = context.applicationContext
        val enqueuedAt = android.os.SystemClock.elapsedRealtime()
        io.execute {
            try {
                val task = try {
                    TimerTaskDb(appCtx).get(taskId)
                } catch (e: Exception) {
                    Log.e(TAG, "读取任务失败: ${e.message}")
                    null
                }
                if (task == null) {
                    Log.w(TAG, "任务 $taskId 不存在, 忽略唤醒")
                    return@execute
                }
                if (!task.enabled) {
                    Log.i(TAG, "任务 $taskId 已停用, 忽略唤醒")
                    return@execute
                }
                val cost = android.os.SystemClock.elapsedRealtime() - enqueuedAt
                if (cost > GO_ASYNC_BUDGET_MS) {
                    Log.w(TAG, "唤醒处理耗时 ${cost}ms 超出预算, 仍尝试派发")
                }
                Log.i(TAG, "定时岗位唤醒: ${task.name} (id=$taskId, 校验耗时 ${cost}ms)")

                // 拉起前台服务承载静默执行(通知仅提示"任务执行中", 不弹 UI)
                // fix(2026-11-09): 统一走 TaskService.startTimerTask, 启动前清残留补停标记,
                // 否则上一轮对话遗留的补停标记会把真实闹钟唤醒整体吞掉
                TaskService.startTimerTask(appCtx, taskId)
            } catch (e: Exception) {
                Log.e(TAG, "唤醒派发失败: ${e.message}")
            } finally {
                try {
                    pending.finish()
                } catch (_: Exception) {
                }
            }
        }
    }
}
