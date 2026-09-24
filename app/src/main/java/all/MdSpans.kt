package io.github.aixtin.nyral

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.json.JSONArray

/** 文本区间样式类型 */
enum class MdSpanType { BOLD, ITALIC, INLINE_CODE, STRIKETHROUGH, LINK, CODE_BLOCK, HEADING, TABLE_ROW, TABLE_BLOCK }

/** 单个样式区间: [start,end) 左闭右开, 作用于 displayText */
data class MdSpan(val start: Int, val end: Int, val type: MdSpanType, val extra: String? = null)

/**
 * 解析结果中间层 (借鉴豆包 MessageMarkdownParseResult):
 * displayText = 纯显示文本(无 MD 标记), spans = 扁平样式区间列表。
 * 天然适配 AnnotatedString / Spannable 的区间样式, 比 ASTNode 更轻。
 */
class MdSpans(val displayText: String, val spans: List<MdSpan>) {
    override fun toString(): String = "MdSpans(text=${displayText.length}ch, spans=${spans.size})"
}

/** CommonMark Node -> MdSpans 转换器 (D路线第2步: spans 中间层) */
object MdToSpans {

    /** 完整解析入口: 解析 MD 文本并转成 spans (带 GFM 扩展) */
    fun parse(md: String): MdSpans {
        val parser = org.commonmark.parser.Parser.builder()
            .extensions(listOf(
                org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create()
            ))
            .build()
        return convert(parser.parse(md))
    }

    /** 已有 Document 节点树 -> spans */
    fun convert(doc: Node): MdSpans {
        val sb = StringBuilder()
        val spans = ArrayList<MdSpan>()
        walk(doc, sb, spans, blockStart = true, listPrefix = null)
        val t = sb.toString().trimEnd('\n')
        return MdSpans(t, spans)
    }

    private fun walk(
        node: Node,
        sb: StringBuilder,
        spans: MutableList<MdSpan>,
        blockStart: Boolean,
        listPrefix: String?
    ) {
        when (node) {
            is Document -> {
                var c = node.firstChild
                while (c != null) { walk(c, sb, spans, blockStart = true, listPrefix = null); c = c.next }
            }
            is Heading -> {
                val start = sb.length
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                walkChildren(node, sb, spans)
                sb.append("\n\n")
                spans += MdSpan(start, sb.length, MdSpanType.HEADING, node.level.toString())
            }
            is Paragraph -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                walkChildren(node, sb, spans)
                sb.append("\n")
            }
            is BulletList -> {
                var c = node.firstChild
                while (c != null) { walk(c, sb, spans, blockStart = true, listPrefix = "- "); c = c.next }
            }
            is OrderedList -> {
                var c = node.firstChild
                var idx = node.startNumber
                while (c != null) { walk(c, sb, spans, blockStart = true, listPrefix = "$idx. "); c = c.next; idx++ }
            }
            is ListItem -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                if (listPrefix != null) sb.append(listPrefix)
                walkChildren(node, sb, spans)
                sb.append("\n")
            }
            is FencedCodeBlock, is IndentedCodeBlock -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                val start = sb.length
                val literal = if (node is FencedCodeBlock) node.literal else (node as IndentedCodeBlock).literal
                sb.append(literal)
                if (!sb.endsWith("\n")) sb.append("\n")
                sb.append("\n")
                spans += MdSpan(start, sb.length - 1, MdSpanType.CODE_BLOCK)
            }
            is BlockQuote -> {
                walkChildren(node, sb, spans)
            }
            is ThematicBreak -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                sb.append("---\n\n")
            }
            is TableBlock -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                val tableStart = sb.length
                var c = node.firstChild
                var dataRowIdx = 0
                while (c != null) {
                    if (c is TableHead || c is TableBody) {
                        var row = c.firstChild
                        while (row != null) {
                            if (row is TableRow) {
                                val cells = ArrayList<TableCell>()
                                var cell = row.firstChild
                                while (cell != null) {
                                    if (cell is TableCell) cells.add(cell)
                                    cell = cell.next
                                }
                                val lineStart = sb.length
                                val isHeader = c is TableHead
                                val odd = (!isHeader) && (dataRowIdx % 2 == 1)
                                cells.forEachIndexed { i, tc ->
                                    if (i > 0) sb.append(" ")
                                    walkChildren(tc, sb, spans)
                                }
                                sb.append("\n")
                                // 表格行 span: extra 编码 cells(文本+对齐) + header/odd 标志,
                                // MdSpannable 据此构造 RoundedTableRowSpan 复用 markwon 同款视觉
                                val arr = JSONArray()
                                arr.put(if (isHeader) 1 else 0)
                                arr.put(if (odd) 1 else 0)
                                val cellsArr = JSONArray()
                                cells.forEach { tc ->
                                    val cArr = JSONArray()
                                    cArr.put(alignmentCode(tc.alignment))
                                    cArr.put(collectCellText(tc))
                                    cellsArr.put(cArr)
                                }
                                arr.put(cellsArr)
                                spans += MdSpan(lineStart, (sb.length - 1).coerceAtLeast(lineStart), MdSpanType.TABLE_ROW, arr.toString())
                                if (!isHeader) dataRowIdx++
                            }
                            row = row.next
                        }
                    }
                    c = c.next
                }
                spans += MdSpan(tableStart, (sb.length - 1).coerceAtLeast(tableStart), MdSpanType.TABLE_BLOCK)
                sb.append("\n")
            }
            is StrongEmphasis -> {
                val start = sb.length
                walkChildren(node, sb, spans)
                spans += MdSpan(start, sb.length, MdSpanType.BOLD)
            }
            is Emphasis -> {
                val start = sb.length
                walkChildren(node, sb, spans)
                spans += MdSpan(start, sb.length, MdSpanType.ITALIC)
            }
            is Strikethrough -> {
                val start = sb.length
                walkChildren(node, sb, spans)
                spans += MdSpan(start, sb.length, MdSpanType.STRIKETHROUGH)
            }
            is Link -> {
                val dest = node.destination ?: ""
                if (dest.startsWith("att://")) {
                    // Nyral 附件卡: 流式阶段轻量占位, 收尾 markwon 渲染真实卡片
                    sb.append("\uD83D\uDCCE 附件")
                } else {
                    val start = sb.length
                    walkChildren(node, sb, spans)
                    spans += MdSpan(start, sb.length, MdSpanType.LINK, node.destination)
                }
            }
            is Image -> {
                val start = sb.length
                val dest = node.destination ?: ""
                if (dest.startsWith("att://")) {
                    sb.append("\uD83D\uDDBC\uFE0F 图片")
                } else {
                    val alt = node.firstChild?.let { c ->
                        val t = StringBuilder()
                        var ch: Node? = c
                        while (ch != null) { if (ch is Text) t.append(ch.literal); ch = ch.next }
                        t.toString()
                    } ?: ""
                    sb.append("[图片").append(if (alt.isNotBlank()) ":$alt" else "").append("]")
                    spans += MdSpan(start, sb.length, MdSpanType.LINK, node.destination)
                }
            }
            is Code -> {
                val start = sb.length
                sb.append(node.literal)
                spans += MdSpan(start, sb.length, MdSpanType.INLINE_CODE)
            }
            is Text -> sb.append(node.literal)
            is SoftLineBreak -> sb.append("\n")
            is HardLineBreak -> sb.append("\n")
            else -> walkChildren(node, sb, spans)
        }
    }

    private fun walkChildren(node: Node, sb: StringBuilder, spans: MutableList<MdSpan>) {
        var c = node.firstChild
        while (c != null) { walk(c, sb, spans, blockStart = false, listPrefix = null); c = c.next }
    }

    /** TableCell.Alignment -> RoundedTableRowSpan.ALIGN_* */
    private fun alignmentCode(a: TableCell.Alignment?): Int = when (a) {
        TableCell.Alignment.CENTER -> 1
        TableCell.Alignment.RIGHT -> 2
        else -> 0
    }

    /** 收集 cell 内全部 Text 字面量(不含 MD 标记), 供表格行 span 复用 markwon 视觉 */
    private fun collectCellText(cell: TableCell): String {
        val t = StringBuilder()
        var n: Node? = cell.firstChild
        while (n != null) {
            if (n is Text) t.append(n.literal)
            n = n.next
        }
        return t.toString()
    }
}
