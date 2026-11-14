package io.github.aixtin.nyral

import android.content.Context
import android.util.Log

/**
 * 定时岗位投递闸门(2026-11-14, 第二批)。
 *
 * 解决问题 2「一天两次触发」: 闹钟被重复投递 / 提前投递时, 用"slot 归属 + 单行 UPDATE 原子占位"去重。
 * 原型 momoding: store.update(taskId){ 校验 + 计数 + 锚点前移 } 返回 null 即判重复投递, 重排但不执行。
 *
 * slot 不是"闹钟实际触发时刻", 而是"本应执行的那个每日时刻"(如今天 08:00):
 * 07:52 的提前投递归属"昨天 08:00", 与"今天 08:00"是两个不同 slot, 因此不会互相顶掉;
 * 而同一个 slot 被投递两次, 第二次 UPDATE 影响 0 行, 直接丢弃。
 */
object TimerDispatch {

    private const val TAG = "TimerDispatch"

    /**
     * 僵尸认领宽限(fix 2026-11-15): slot 被认领后, 台账迟迟没有终态记录就说明那一轮没跑完。
     * 之所以要等 10 分钟而不是立刻判: 正常一轮任务耗时数十秒到几分钟, 立刻判会和"正在跑的这一轮"抢跑,
     * 造成同一 slot 双执行。超过 10 分钟仍无终态, 才是真正的僵尸。
     */
    const val ZOMBIE_GRACE_MS = 10 * 60 * 1000L

    /**
     * 放行判定。
     * @return true 表示本次投递拿到执行权(已完成 slot 占位); false 表示应丢弃(早投递/重复投递/任务失效)
     */
    fun admit(context: Context, taskId: Long, catchUp: Boolean): Boolean {
        val db = TimerTaskDb(context)
        val task = try {
            db.get(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "读取任务 " + taskId + " 失败: " + e.message)
            null
        }
        if (task == null) {
            Log.w(TAG, "任务 " + taskId + " 不存在, 丢弃投递")
            return false
        }
        if (!task.enabled) {
            Log.i(TAG, "任务 " + taskId + " 已停用, 丢弃投递")
            return false
        }

        val now = System.currentTimeMillis()
        // 补跑是对过去 slot 的追认, 不受早投递栅栏约束
        if (!catchUp && TimerScheduler.isEarlyDelivery(task.cronDesc, now)) {
            Log.i(TAG, "任务 " + taskId + " 提前投递(" + task.cronDesc + " 未到 5s 栅栏), 丢弃交重排")
            return false
        }
        val slot = TimerScheduler.latestDueSlot(task.cronDesc, now)
        if (slot <= 0L) {
            Log.e(TAG, "任务 " + taskId + " 表达式无法解析: " + task.cronDesc)
            return false
        }
        if (!db.tryClaimSlot(taskId, slot)) {
            // fix(2026-11-15): "已认领" 不等于 "已跑过"。
            // 占位成功后进程若在执行途中被系统杀掉, 台账里只留一行 ok=-1 的占位(见 TimerTaskDb.beginRun),
            // slot 却被永久占住; 此时补跑仍走 last_slot_ts < slot 就会被自己上一轮的残留挡住,
            // 变成"既没跑成、也永远补不上"(2026-11-14 08:00 冕宁早报即此形态)。
            // 僵尸判据四条同时成立才接管: catchUp 补跑 + last_run 未越过 slot(真跑完一定会刷 last_run)
            // + 该 slot 无终态台账 + 距 slot 已过宽限(避开正在跑的本轮)。
            val zombie = catchUp && task.lastRunTs < slot && (now - slot > ZOMBIE_GRACE_MS) &&
                !db.hasFinishedRun(taskId, slot)
            if (zombie && db.reclaimSlot(taskId, slot)) {
                Log.w(TAG, "任务 " + taskId + " slot=" + TimerScheduler.fmtSlot(slot) + " 已认领但无终态台账, 判定僵尸认领, 补跑重新接管")
            } else {
                Log.i(TAG, "任务 " + taskId + " slot=" + TimerScheduler.fmtSlot(slot) + " 已被认领, 丢弃重复投递")
                return false
            }
        }
        Log.i(TAG, "任务 " + taskId + " 占位成功 slot=" + TimerScheduler.fmtSlot(slot) + " catchUp=" + catchUp)
        return true
    }
}
