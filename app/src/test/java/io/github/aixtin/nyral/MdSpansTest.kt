package io.github.aixtin.nyral

import org.junit.Test

/** D路线第2步验收: MdSpans 中间层, displayText + spans 区间正确 */
class MdSpansTest {

    @Test
    fun spansBasic() {
        val md = """
            # 大标题

            **粗体** 与 *斜体* 和 `行内代码`

            链接: [点我](https://example.com/a)

            - 项一
            - 项二

            ```kotlin
            val x = 1
            ```

            | 列A | 列B |
            |-----|-----|
            | 1 | 2 |

            ~~删除线~~ 结束
        """.trimIndent()
        val r = MdToSpans.parse(md)
        println("===== displayText =====")
        println(r.displayText)
        println("===== spans =====")
        r.spans.forEach { println("${it.type} [${it.start},${it.end}) extra=${it.extra}") }

        // displayText 不得残留 MD 标记符号
        val t = r.displayText
        check(!t.contains("**")) { "残留 **" }
        check(!t.contains("```")) { "残留 ```" }
        check(!t.contains("|-----|")) { "残留表格分隔线" }
        check(!t.contains("| 列A |")) { "表格未转为文本" }
        check(!t.contains("~~")) { "残留 ~~" }
        check(!t.contains("](")) { "残留链接语法" }

        // span 类型覆盖
        val types = r.spans.map { it.type }.toSet()
        check(MdSpanType.HEADING in types) { "缺 HEADING" }
        check(MdSpanType.BOLD in types) { "缺 BOLD" }
        check(MdSpanType.ITALIC in types) { "缺 ITALIC" }
        check(MdSpanType.INLINE_CODE in types) { "缺 INLINE_CODE" }
        check(MdSpanType.LINK in types) { "缺 LINK" }
        check(MdSpanType.CODE_BLOCK in types) { "缺 CODE_BLOCK" }
        check(MdSpanType.STRIKETHROUGH in types) { "缺 STRIKETHROUGH" }

        // 链接 extra
        val link = r.spans.first { it.type == MdSpanType.LINK && it.extra != null }
        check(link.extra == "https://example.com/a") { "链接 url 错误: ${link.extra}" }

        // 区间合法性: 全部在 displayText 范围内
        r.spans.forEach {
            check(it.start >= 0 && it.end <= t.length && it.start < it.end) { "非法区间 ${it}" }
            check(it.start <= it.end) { "区间颠倒 ${it}" }
        }
        println("===== 验收通过: MdSpans 中间层 OK =====")
    }

    @Test
    fun spansPlainText() {
        val r = MdToSpans.parse("普通文本，没有任何标记")
        check(r.displayText == "普通文本，没有任何标记") { "纯文本被改动" }
        check(r.spans.isEmpty()) { "纯文本不应有 span" }
        println("===== 验收通过: 纯文本无标记 OK =====")
    }
}
