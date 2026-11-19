package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamSanitizer 泄漏形态回归(2026-11-19, 批次 A)。
 *
 * 覆盖清单里全部 12 种已知/实测形态, 外加围栏保护、HTML 白名单、EOF 与断链两个收口分支。
 * 断言口径只有两条:
 *  1) 上屏正文里绝不允许出现工具壳标签
 *  2) 该识别成工具调用的必须变成 ToolCall, 不能变正文, 也不能凭空消失得没有交代
 */
class StreamSanitizerTest {

    private class Rec {
        val content = StringBuilder()
        val tools = ArrayList<Pair<String, String>>()
        val dropped = ArrayList<Delta.LeakDropped>()
        val retry = ArrayList<String>()

        fun on(d: Delta) {
            when (d) {
                is Delta.Content -> content.append(d.text)
                is Delta.ToolCall -> tools.add(d.name to d.args)
                is Delta.LeakDropped -> dropped.add(d)
                is Delta.NeedRetry -> retry.add(d.reason)
                is Delta.Thinking -> Unit
            }
        }

        val text: String get() = content.toString()
    }

    private val SHELL_RE = Regex(
        "</?\\s*(?:tool_call|tool_calls|tool_name|tool_use|tool_result|tool_detail|function_call|function_calls|invoke|memory_search|arg_key|arg_value)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun run(vararg chunks: String, abort: Boolean = false): Rec {
        val r = Rec()
        val s = StreamSanitizer(useTools = true, emit = r::on)
        for (c in chunks) s.feed(c)
        if (abort) s.flushAbort() else s.flushEof()
        return r
    }

    /** 逐字符喂, 模拟最恶劣的分片 */
    private fun runByChar(text: String, abort: Boolean = false): Rec {
        val r = Rec()
        val s = StreamSanitizer(useTools = true, emit = r::on)
        for (c in text) s.feed(c.toString())
        if (abort) s.flushAbort() else s.flushEof()
        return r
    }

    private fun assertClean(text: String) {
        assertFalse("正文出现工具壳标签: <<<$text>>>", SHELL_RE.containsMatchIn(text))
    }

    // ---------------------------------------------------------------- 1. 整块单行
    private val p1 = """<tool_call>{"name":"ssh_run","arguments":{"cmd":"ls -l"}}</tool_call>"""

    @Test
    fun shape1_wholeBlockSingleLine() {
        val r = run(p1)
        assertClean(r.text)
        assertEquals("", r.text.trim())
        assertEquals(1, r.tools.size)
        assertEquals("ssh_run", r.tools[0].first)
        assertTrue(r.tools[0].second.contains("ls -l"))
    }

    @Test
    fun shape1_wholeBlockByChar() {
        val r = runByChar(p1)
        assertClean(r.text)
        assertEquals(1, r.tools.size)
        assertEquals("ssh_run", r.tools[0].first)
    }

    // ---------------------------------------------------------------- 2. tool_name + arg_key/arg_value
    @Test
    fun shape2_toolNameWithArgPairs() {
        val r = run(
            "<tool_call><tool_name>ssh_run</tool_name>",
            "<arg_key>cmd</arg_key><arg_value>ls -l</arg_value></tool_call>"
        )
        assertClean(r.text)
        assertEquals(1, r.tools.size)
        assertEquals("ssh_run", r.tools[0].first)
        assertTrue(r.tools[0].second.contains("cmd"))
        assertTrue(r.tools[0].second.contains("ls -l"))
    }

    // ---------------------------------------------------------------- 3. tool_detail JSON
    @Test
    fun shape3_toolDetailJson() {
        val r = run("""<tool_detail>{"name":"ssh_run","arguments":{"cmd":"whoami"}}</tool_detail>""")
        assertClean(r.text)
        assertEquals(1, r.tools.size)
        assertEquals("ssh_run", r.tools[0].first)
        assertTrue(r.tools[0].second.contains("whoami"))
    }

    // ---------------------------------------------------------------- 4. 标签名即工具名
    @Test
    fun shape4_memorySearchTag() {
        val r = run("""<memory_search>{"query":"报销单"}</memory_search>""")
        assertClean(r.text)
        assertEquals(1, r.tools.size)
        assertEquals("memory_search", r.tools[0].first)
        assertTrue(r.tools[0].second.contains("报销单"))
    }

    // ---------------------------------------------------------------- 5. 行尾粘连
    @Test
    fun shape5_tailGluedToText() {
        val r = run("查询完成，结果如下</tool_call>")
        assertClean(r.text)
        assertTrue(r.text.contains("查询完成，结果如下"))
        assertTrue(r.dropped.isNotEmpty())
    }

    // ---------------------------------------------------------------- 6. 行首粘连
    @Test
    fun shape6_headGluedToText() {
        val r = run("</tool_call>接下来是正式答案。")
        assertClean(r.text)
        assertTrue(r.text.contains("接下来是正式答案。"))
    }

    // ---------------------------------------------------------------- 7. 正文夹块
    @Test
    fun shape7_blockSandwichedByText() {
        val r = run("正文A\n", p1, "\n正文B")
        assertClean(r.text)
        assertTrue(r.text.contains("正文A"))
        assertTrue(r.text.contains("正文B"))
        assertEquals(1, r.tools.size)
    }

    // ---------------------------------------------------------------- 8. 围栏内原样保留
    @Test
    fun shape8_fenceKeepsExample() {
        val src = "示例：\n```\n$p1\n```\n完"
        val r = run(src)
        assertEquals(src, r.text)
        assertTrue(r.tools.isEmpty())
    }

    // ---------------------------------------------------------------- 9. 正文 HTML 不误伤
    @Test
    fun shape9_htmlInBodyUntouched() {
        val src = "下一行<br>换行 <a href=\"https://example.com\">链接</a><p>段落</p>"
        val r = run(src)
        assertEquals(src, r.text)
        assertTrue(r.tools.isEmpty())
    }

    // ---------------------------------------------------------------- 10. EOF 半截
    @Test
    fun shape10_eofHalfBlockNeedsRetry() {
        val r = run("答案在下面：<tool_call><tool_name>ssh_run")
        assertClean(r.text)
        assertTrue("半截块应提示重试", r.retry.isNotEmpty() || r.dropped.isNotEmpty())
    }

    // ---------------------------------------------------------------- 11. 断链残余
    @Test
    fun shape11_abortResidueNotShown() {
        val r = run("好的，我来执行", "</tool_call>", abort = true)
        assertClean(r.text)
        assertTrue(r.text.contains("好的，我来执行"))
    }

    // ---------------------------------------------------------------- 12. DSML
    @Test
    fun shape12_dsml() {
        val dsml = "<｜｜DSML｜｜tool_calls>\n" +
            "<｜｜DSML｜｜invoke name=\"ssh_run\">\n" +
            "<｜｜DSML｜｜parameter name=\"cmd\" string=\"true\">ls -l</｜｜DSML｜｜parameter>\n" +
            "</｜｜DSML｜｜invoke>\n" +
            "</｜｜DSML｜｜tool_calls>"
        val r = run(dsml)
        assertClean(r.text)
        assertEquals(1, r.tools.size)
        assertEquals("ssh_run", r.tools[0].first)
        assertTrue(r.tools[0].second.contains("ls -l"))
    }

    // ---------------------------------------------------------------- 附加: 滞后窗口
    @Test
    fun lagWindow_doesNotSwallowRealText() {
        // '<' 后面跟的不是标签, 必须尽快放行, 不能被滞后窗口永久扣住
        val r = run("判断 a < b 且 c > d，成立。")
        assertTrue(r.text.contains("a < b"))
        assertTrue(r.text.contains("成立。"))
    }

    @Test
    fun scrubText_isIdempotent() {
        val dirty = "结果如下$p1 结束"
        val once = StreamSanitizer.scrubText(dirty)
        val twice = StreamSanitizer.scrubText(once)
        assertEquals(once, twice)
        assertClean(once)
    }

    @Test
    fun pureBlockBecomesEmpty() {
        assertEquals("", StreamSanitizer.scrubText(p1))
        assertNotNull(StreamSanitizer.scrubText(p1))
    }
}
