package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 B 影子对比器单测(2026-11-19)。
 *
 * 只验证影子自身的记账与判定, 不涉及 LocalEngine:
 * - 正常正文 -> CLEAN
 * - 含工具标签残留 -> DIFF/LEAK_ON_SCREEN
 * - finish 幂等, feed 在 finish 之后不再累计
 */
class StreamShadowTest {

    private fun run(chunks: List<String>, useTools: Boolean = true, aborted: Boolean = false): Pair<StreamShadow, String> {
        val logs = StringBuilder()
        val sh = StreamShadow(useTools) { m -> logs.append(m).append('\n') }
        chunks.forEach { sh.feed(it) }
        sh.finish(aborted)
        return sh to logs.toString()
    }

    @Test
    fun plainTextRoundIsClean() {
        val (_, logs) = run(listOf("你好，", "这是一段", "普通正文。"))
        assertTrue("应判 CLEAN, 实际: $logs", logs.contains("CLEAN"))
        assertTrue("不应出现 DIFF, 实际: $logs", !logs.contains("DIFF"))
    }

    @Test
    fun toolTagResidueIsFlagged() {
        val (_, logs) = run(listOf("好的，已完成。", "</tool_call>", "请查收。"))
        assertTrue("应判 DIFF, 实际: $logs", logs.contains("DIFF"))
        assertTrue("应命中泄漏判定, 实际: $logs", logs.contains("LEAK_ON_SCREEN"))
    }

    @Test
    fun dsmlBlockIsFlagged() {
        // 真实 DeepSeek DSML 用全角竖线包裹(与 StreamSanitizerTest 第 12 形态一致)
        val (_, logs) = run(
            listOf(
                "稍等。\n<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"search\">\n",
                "<｜｜DSML｜｜parameter name=\"q\" string=\"true\">天气</｜｜DSML｜｜parameter>\n",
                "</｜｜DSML｜｜invoke>\n</｜｜DSML｜｜tool_calls>\n"
            )
        )
        assertTrue("应判 DIFF, 实际: $logs", logs.contains("DIFF"))
    }

    @Test
    fun finishIsIdempotent() {
        val logs = StringBuilder()
        val sh = StreamShadow(true) { m -> logs.append(m).append('\n') }
        sh.feed("正文")
        sh.finish(false)
        val first = logs.toString()
        sh.finish(false)
        assertEquals("重复 finish 不应再打日志", first, logs.toString())
        // finish 之后再 feed 应被忽略
        sh.feed("后续")
        assertEquals(first, logs.toString())
    }

    @Test
    fun abortFlushKeepsPartialText() {
        val (_, logs) = run(listOf("写到一半", "被断链"), aborted = true)
        assertTrue("断链收口也要出结论, 实际: $logs", logs.contains("[shadow] round#"))
    }
}
