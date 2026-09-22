package io.github.aixtin.nyral

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Test

/**
 * D路线第1步验收demo: commonmark-java + GFM 扩展, 纯文本 -> 块级结构
 */
class MdParseDemoTest {

    @Test
    fun parseBlocks() {
        val parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .build()
        val md = """
            # 标题一

            - 列表项1
            - 列表项2

            ```kotlin
            fun main() = println("hi")
            ```

            | 列A | 列B |
            |-----|-----|
            | 1 | 2 |

            **加粗** 和 ~~删除线~~
        """.trimIndent()
        val doc = parser.parse(md)
        val sb = StringBuilder()
        fun walk(node: Node, depth: Int) {
            sb.append("  ".repeat(depth)).append(node.javaClass.simpleName)
            when (node) {
                is Heading -> sb.append(" [h").append(node.level).append("]")
                is Text -> sb.append(" \"").append(node.literal).append("\"")
            }
            sb.append("\n")
            var c = node.firstChild
            while (c != null) { walk(c, depth + 1); c = c.next }
        }
        walk(doc, 0)
        println("===== 块级结构 =====")
        println(sb)
        check(sb.contains("Heading")) { "缺标题块" }
        check(sb.contains("BulletList")) { "缺列表块" }
        check(sb.contains("FencedCodeBlock")) { "缺代码块" }
        check(sb.contains("TableBlock")) { "缺表格块(GFM)" }
        check(sb.contains("StrongEmphasis")) { "缺加粗" }
        check(sb.contains("Strikethrough")) { "缺删除线(GFM)" }
        println("===== 验收通过: 块级解析 OK =====")
    }
}
