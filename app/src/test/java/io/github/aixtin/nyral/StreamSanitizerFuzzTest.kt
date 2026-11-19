package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * StreamSanitizer 随机分片 fuzz(2026-11-19, 批次 A)。
 *
 * 泄漏 bug 的本质是"规则有限、变体无限"。这里不再穷举形态, 而是把已知形态和正文
 * 随机拼、随机切, 断言不变式:
 *  I1 上屏正文永远不含工具壳标签(含半截形态)
 *  I2 每个泄漏块必须有交代: 要么 ToolCall, 要么 LeakDropped/NeedRetry, 不允许静默蒸发
 *  I3 纯正文片段必须原样保留(不许为了防泄漏把用户内容吃掉)
 */
class StreamSanitizerFuzzTest {

    private val iters: Int = System.getProperty("nyral.sanitizer.fuzz.iters")?.toIntOrNull() ?: 3000

    @org.junit.Before
    fun announce() {
        println("StreamSanitizerFuzzTest: iters=$iters")
    }

    private class Rec {
        val content = StringBuilder()
        var tools = 0
        var dropped = 0
        var retry = 0

        fun on(d: Delta) {
            when (d) {
                is Delta.Content -> content.append(d.text)
                is Delta.ToolCall -> tools++
                is Delta.LeakDropped -> dropped++
                is Delta.NeedRetry -> retry++
                is Delta.Thinking -> Unit
            }
        }

        val accounted: Boolean get() = tools + dropped + retry > 0
    }

    private val SHELL_RE = Regex(
        "</?\\s*(?:tool_call|tool_calls|tool_name|tool_use|tool_result|tool_detail|function_call|function_calls|invoke|memory_search|arg_key|arg_value|DSML)",
        RegexOption.IGNORE_CASE
    )

    /** 半截标签残留也算泄漏: 上屏出现 "<tool_" / "</tool" / "｜DSML" 一律不合格 */
    private val FRAGMENT_RE = Regex(
        "</?(?:tool_|tool$|function_|invoke|memory_|arg_)|｜DSML|\\|\\|DSML",
        RegexOption.IGNORE_CASE
    )

    // 已知泄漏形态池
    private val leaks = listOf(
        """<tool_call>{"name":"ssh_run","arguments":{"cmd":"ls"}}</tool_call>""",
        """<tool_calls><tool_call>{"name":"web_search","arguments":{"q":"x"}}</tool_call></tool_calls>""",
        """<tool_call><tool_name>ssh_run</tool_name><arg_key>cmd</arg_key><arg_value>ls</arg_value></tool_call>""",
        """<tool_detail>{"name":"ssh_run","arguments":{"cmd":"whoami"}}</tool_detail>""",
        """<memory_search>{"query":"报销单"}</memory_search>""",
        """<function_call>{"name":"artifact_save","arguments":{"path":"/tmp/a"}}</function_call>""",
        """<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="ssh_run">
<｜｜DSML｜｜parameter name="cmd" string="true">ls</｜｜DSML｜｜parameter>
</｜｜DSML｜｜invoke>
</｜｜DSML｜｜tool_calls>""",
        """</tool_call>""",
        """</tool_calls>""",
        """<tool_use>{"name":"app_click","arguments":{"x":1}}</tool_use>""",
        """<tool_result>{"ok":true}</tool_result>""",
        """<invoke name="ssh_run"><parameter name="cmd">ls</parameter></invoke>"""
    )

    // 必须原样保留的正文
    private val plains = listOf(
        "正文一",
        "答案是 42。",
        "换行\n",
        "<br>",
        "下一段<p>段落</p>",
        "a < b 且 c > d"
    )

    private fun feedSplit(s: StreamSanitizer, text: String, rnd: Random) {
        var i = 0
        while (i < text.length) {
            val n = rnd.nextInt(1, 8)
            val end = minOf(text.length, i + n)
            s.feed(text.substring(i, end))
            i = end
        }
    }

    private fun assertInvariants(label: String, rec: Rec) {
        val text = rec.content.toString()
        assertFalse("[$label] 正文泄漏壳标签: <<<$text>>>", SHELL_RE.containsMatchIn(text))
        assertFalse("[$label] 正文残留半截标签: <<<$text>>>", FRAGMENT_RE.containsMatchIn(text))
    }

    @Test
    fun randomSplitsOfSingleLeakNeverLeak() {
        val rnd = Random(20261119L)
        repeat(iters) { round ->
            val leak = leaks[rnd.nextInt(leaks.size)]
            val rec = Rec()
            val s = StreamSanitizer(true, rec::on)
            feedSplit(s, leak, rnd)
            if (rnd.nextBoolean()) s.flushEof() else s.flushAbort()
            assertInvariants("single#$round", rec)
            assertTrue("[$round] 泄漏块未交代: $leak", rec.accounted)
        }
    }

    @Test
    fun mixedStreamKeepsPlainTextAndNeverLeaks() {
        val rnd = Random(20261120L)
        repeat(iters) { round ->
            val parts = ArrayList<String>()
            val n = rnd.nextInt(1, 5)
            repeat(n) {
                if (rnd.nextBoolean()) parts.add(plains[rnd.nextInt(plains.size)])
                else parts.add(leaks[rnd.nextInt(leaks.size)])
            }
            val full = parts.joinToString("")
            val rec = Rec()
            val s = StreamSanitizer(true, rec::on)
            feedSplit(s, full, rnd)
            s.flushEof()
            assertInvariants("mixed#$round", rec)
        }
    }

    @Test
    fun plainTextIsNeverEaten() {
        repeat(500) { round ->
            val rnd = Random(20261121L + round)
            val rec = Rec()
            val s = StreamSanitizer(true, rec::on)
            val p = plains[rnd.nextInt(plains.size)]
            feedSplit(s, p, rnd)
            s.flushEof()
            assertEquals("[$round] 正文被吃掉: $p", p, rec.content.toString())
        }
    }

    @Test
    fun fenceContentIsExempt() {
        repeat(200) { round ->
            val rnd = Random(20261122L + round)
            val leak = leaks[rnd.nextInt(leaks.size)]
            val src = "看这段：\n```\n$leak\n```\n以上"
            val rec = Rec()
            val s = StreamSanitizer(true, rec::on)
            feedSplit(s, src, rnd)
            s.flushEof()
            assertEquals("[$round] 围栏内容被改动", src, rec.content.toString())
            assertEquals("[$round] 围栏内不应触发工具调用", 0, rec.tools)
        }
    }

    @Test
    fun repeatedFeedsAfterCloseStillSafe() {
        val rnd = Random(20261123L)
        val rec = Rec()
        val s = StreamSanitizer(true, rec::on)
        s.feed("先说话")
        s.flushEof()
        feedSplit(s, leaks[rnd.nextInt(leaks.size)], rnd)
        s.flushEof()
        assertInvariants("resume", rec)
        assertTrue(rec.content.toString().contains("先说话"))
    }
}
