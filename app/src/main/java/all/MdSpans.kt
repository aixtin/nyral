package io.github.aixtin.nyral

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
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
enum class MdSpanType { BOLD, ITALIC, INLINE_CODE, STRIKETHROUGH, LINK, CODE_BLOCK, HEADING, QUOTE, TABLE_ROW, TABLE_BLOCK, HR }

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

    /** 表格单列字符上限: 与 RoundedTableRowSpan.MAX_CELL_CHARS 对齐, 超长折行 */
    private const val MAX_TABLE_CELL_CHARS = 16

    /** 脚注引用形如 [^1], commonmark 0.13 无脚注扩展时被解析为 link reference, 需降级为纯文本 */
    private val FOOTNOTE_REF = Regex("""\^\d+$""")

    /** 复用 Parser 实例: commonmark Parser 线程安全可复用, 避免每次 parse 重复 build */
    private val parser: org.commonmark.parser.Parser by lazy {
        org.commonmark.parser.Parser.builder()
            .extensions(listOf(
                org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create()
            ))
            .build()
    }

    /** 完整解析入口: 解析 MD 文本并转成 spans (带 GFM 扩展) */
    fun parse(md: String): MdSpans = convert(parser.parse(md))

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
        listPrefix: String?,
        indentLevel: Int = 0
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
                // 段落以空行结束: 保证相邻段落/后续块级元素之间有标准空行分隔
                sb.append("\n\n")
            }
            is BulletList -> {
                var c = node.firstChild
                while (c != null) { walk(c, sb, spans, blockStart = true, listPrefix = "- ", indentLevel = indentLevel); c = c.next }
            }
            is OrderedList -> {
                var c = node.firstChild
                var idx = node.startNumber
                while (c != null) { walk(c, sb, spans, blockStart = true, listPrefix = "$idx. ", indentLevel = indentLevel); c = c.next; idx++ }
            }
            is ListItem -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                if (listPrefix != null) sb.append("  ".repeat(indentLevel)).append(listPrefix)
                // 列表项内容直下钻: 项内段落紧跟前缀(不触发块级空行逻辑), 嵌套列表缩进一级
                var c = node.firstChild
                while (c != null) {
                    if (c is Paragraph) {
                        val box = taskBoxMarker(c)
                        if (box != null) {
                            // GFM 任务列表: [x] 已完成 -> ☑, [ ] 待办 -> ☐
                            sb.append(box).append(' ')
                            walkChildrenSkip(c, sb, spans, 4)
                        } else {
                            walkChildren(c, sb, spans)
                        }
                        sb.append("\n")
                    } else {
                        walk(c, sb, spans, blockStart = false, listPrefix = listPrefix, indentLevel = indentLevel + 1)
                    }
                    c = c.next
                }
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
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                val qStart = sb.length
                walkChildren(node, sb, spans)
                val qEnd = sb.length
                if (qEnd > qStart) {
                    spans += MdSpan(qStart, qEnd, MdSpanType.QUOTE)
                }
                sb.append("\n\n")
            }
            is HtmlBlock -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                val text = stripHtmlTags(node.literal)
                if (text.isNotBlank()) {
                    sb.append(text)
                    sb.append("\n\n")
                }
            }
            is ThematicBreak -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                val hrStart = sb.length
                sb.append("\u200B") // 零宽占位, 由 HrSpan 替换为分割线
                val hrEnd = sb.length
                spans += MdSpan(hrStart, hrEnd, MdSpanType.HR)
                sb.append("\n\n")
            }
            is TableBlock -> {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n\n")
                val tableStart = sb.length
                // 预扫描: 收集整表所有行文本, 计算每列最大字符数(封顶16), 供各行统一列宽,
                // 修复"每行各自长短不一像积木"——各行 span 独立算宽导致外框不统一
                val colMaxChars = ArrayList<Int>()
                var scan = node.firstChild
                while (scan != null) {
                    if (scan is TableHead || scan is TableBody) {
                        var srow = scan.firstChild
                        while (srow != null) {
                            if (srow is TableRow) {
                                var idx = 0
                                var scell = srow.firstChild
                                while (scell != null) {
                                    if (scell is TableCell) {
                                        val t = collectCellText(scell)
                                        while (colMaxChars.size <= idx) colMaxChars.add(0)
                                        colMaxChars[idx] = maxOf(colMaxChars[idx], minOf(t.length, MAX_TABLE_CELL_CHARS))
                                        idx++
                                    }
                                    scell = scell.next
                                }
                            }
                            srow = srow.next
                        }
                    }
                    scan = scan.next
                }
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
                                // 第4项: 整表统一列宽(每列最大字符数), 供渲染端所有行共享同一表格宽
                                val colArr = JSONArray()
                                colMaxChars.forEach { colArr.put(it) }
                                arr.put(colArr)
                                spans += MdSpan(lineStart, (sb.length - 1).coerceAtLeast(lineStart), MdSpanType.TABLE_ROW, arr.toString())
                                if (!isHeader) dataRowIdx++
                            }
                            row = row.next
                        }
                    }
                    c = c.next
                }
                spans += MdSpan(tableStart, (sb.length - 1).coerceAtLeast(tableStart), MdSpanType.TABLE_BLOCK)
                sb.append("\n\n")
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
                    // 脚注引用 [^n] 无脚注扩展时被解析成 link reference: 降级为纯文本, 不渲染成链接
                    val label = StringBuilder()
                    digText(node.firstChild, label)
                    if (label.isNotEmpty() && FOOTNOTE_REF.matches(label) && !dest.contains("://")) {
                        sb.append(label)
                    } else {
                        val start = sb.length
                        walkChildren(node, sb, spans)
                        spans += MdSpan(start, sb.length, MdSpanType.LINK, node.destination)
                    }
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
                    // 占位文本不可点击跳转: 不加 LINK span
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

    /** 任务列表标记检测: 段落文本以 [x] / [X] / [ ] + 空格开头则返回对应 checkbox 字符 */
    private fun taskBoxMarker(p: Paragraph): Char? {
        val t = StringBuilder()
        var c: Node? = p.firstChild
        while (c != null) { if (c is Text) t.append(c.literal); c = c.next }
        val s = t.toString()
        return when {
            s.startsWith("[x] ") || s.startsWith("[X] ") -> '\u2611'
            s.startsWith("[ ] ") -> '\u2610'
            else -> null
        }
    }

    /** 遍历子节点但跳过前 skipChars 个字符(用于任务列表吃掉 [x] 前缀), 其余同 walkChildren */
    private fun walkChildrenSkip(node: Node, sb: StringBuilder, spans: MutableList<MdSpan>, skipChars: Int) {
        var c: Node? = node.firstChild
        var remain = skipChars
        while (c != null) {
            if (c is Text) {
                val lit = c.literal
                if (remain > 0) {
                    if (lit.length <= remain) {
                        remain -= lit.length
                    } else {
                        sb.append(lit.substring(remain))
                        remain = 0
                    }
                } else {
                    sb.append(lit)
                }
            } else {
                walk(c, sb, spans, blockStart = false, listPrefix = null)
            }
            c = c.next
        }
    }

    /** HTML 块降级: 剥离标签与注释, 保留纯文本(避免整块消失) */
    private fun stripHtmlTags(html: String): String {
        val noComments = html.replace(Regex("""(?s)<!--.*?-->"""), "")
        val noTags = noComments.replace(Regex("""<[^>]*>"""), "")
        return noTags.replace(Regex("""(?m)^[ \t]+$"""), "").trim()
    }

    /** TableCell.Alignment -> RoundedTableRowSpan.ALIGN_* */
    private fun alignmentCode(a: TableCell.Alignment?): Int = when (a) {
        TableCell.Alignment.CENTER -> 1
        TableCell.Alignment.RIGHT -> 2
        else -> 0
    }

    /** 收集 cell 内全部文本(含 Code/Emphasis/Link 等内联子节点), 供表格行 span 复用 markwon 视觉 */
    private fun collectCellText(cell: TableCell): String {
        val t = StringBuilder()
        digText(cell.firstChild, t)
        return t.toString()
    }

    /** 递归下钻取纯文本: 认 Code/软硬换行, 其余节点继续下钻(修表格含行内代码整格空白) */
    private fun digText(n: Node?, t: StringBuilder) {
        var x = n
        while (x != null) {
            when (x) {
                is Text -> t.append(x.literal)
                is Code -> t.append(x.literal)
                is SoftLineBreak, is HardLineBreak -> t.append(' ')
                else -> digText(x.firstChild, t)
            }
            x = x.next
        }
    }
}
