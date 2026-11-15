package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上游存活探测三道闸的纯逻辑用例(2026-11-15)。
 * 只验判定结果, 不碰网络与 Android 运行时, 保证 testDebugUnitTest 秒级跑完。
 * 关联缺陷: 旧实现只有 readTimeout(读不到字节才超时), 上游持续发心跳时会无限停在"思考中"。
 */
class StallProbeTest {

    private fun probe(total: Long, idle: Long, progress: Boolean): LocalEngine.ProbeVerdict =
        LocalEngine.probeVerdict(
            elapsedTotalMs = total,
            idleMs = idle,
            gotProgress = progress,
            firstContentMs = 90_000L,
            stallMs = 120_000L,
            maxStreamMs = 600_000L
        )

    @Test
    fun firstContentJustBeforeLimitKeepsWaiting() {
        // 差 1ms 时应继续等, 不能误杀正常慢模型
        assertEquals(LocalEngine.ProbeVerdict.OK, probe(89_999L, 89_999L, false))
    }

    @Test
    fun firstContentTimeoutFiresAtLimit() {
        // 边界取 >=, 避免恰好等于时漏判再等一个周期
        assertEquals(LocalEngine.ProbeVerdict.FIRST_CONTENT_TIMEOUT, probe(90_000L, 90_000L, false))
    }

    @Test
    fun firstContentPhaseIgnoresStallThreshold() {
        // 尚无任何增量时用 90s 而非 120s: 100s 静默必须已判死
        assertEquals(LocalEngine.ProbeVerdict.FIRST_CONTENT_TIMEOUT, probe(100_000L, 100_000L, false))
    }

    @Test
    fun noProgressAfterContentKeepsWaitingBeforeLimit() {
        assertEquals(LocalEngine.ProbeVerdict.OK, probe(119_999L, 119_999L, true))
    }

    @Test
    fun noProgressAfterContentFiresAtLimit() {
        // 已出过正文后只剩心跳: 120s 无有效增量即收口
        assertEquals(LocalEngine.ProbeVerdict.NO_PROGRESS_TIMEOUT, probe(120_000L, 120_000L, true))
    }

    @Test
    fun totalDurationWinsEvenWithFreshProgress() {
        // 刻意让 idle=0(刚有增量): 仍必须被单轮总时长闸拦下, 否则慢速吐字可无限拖
        assertEquals(LocalEngine.ProbeVerdict.TOTAL_DURATION_EXCEEDED, probe(600_000L, 0L, true))
    }

    @Test
    fun heartbeatsAloneNeverProduceProgress() {
        // 语义固化: 心跳/空帧不算进展 —— 调用方只在收到思考/正文/工具参数时打点,
        // 因此"只有心跳"表现为 progress 恒 false, 必须最终判死而非无限等
        var idle = 0L
        var verdict = LocalEngine.ProbeVerdict.OK
        while (verdict == LocalEngine.ProbeVerdict.OK) {
            idle += 30_000L
            verdict = probe(idle, idle, false)
        }
        assertEquals(LocalEngine.ProbeVerdict.FIRST_CONTENT_TIMEOUT, verdict)
        assertEquals(90_000L, idle)
    }
}
