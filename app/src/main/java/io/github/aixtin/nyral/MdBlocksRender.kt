package io.github.aixtin.nyral

import org.json.JSONArray

/**
 * ===== 方案 B 块化渲染抽象 (2026-10-01) =====
 * 气泡内部块化: 表格从 TextView 文本流拆出为独立 TableView 子视图,
 * 渲染路径收敛一条(不再有 RoundedTableRowSpan/colMaxChars/TableScrollWrap 三处构造点)。
 *
 * MdRenderBlock = 一个气泡内的一个渲染单元:
 *  - TextBlock: 文本块(行内样式已渲染为 Spanned), 由 TextView 承载
 *  - TableBlock: 表格块(结构化数据), 由 MdTableView 独立承载
 *
 * MdToBlocks.render(md) 把 MD 文本解析成有序块列表(文本/表格交替)。
 */

/** 单元格对齐码: 与 MdToSpans.alignmentCode / TableRowSpan.Cell 对齐(0=left 1=center 2=right) */
internal data class MdCell(val text: String, val align: Int)

/** 结构化表格数据: 表头行 + 数据行 + 整表统一列宽(每列最大字符数, 与旧 colMaxChars 同语义) */
internal data class MdTableData(
    val header: List<MdCell>,
    val rows: List<List<MdCell>>,
    val colMaxChars: IntArray
)

/** 渲染块 */
internal sealed class MdRenderBlock {
    class TextBlock(val spanned: CharSequence) : MdRenderBlock()
    class TableBlock(val table: MdTableData) : MdRenderBlock()
}

/** MD -> 块列表转换器 */
internal object MdToBlocks {

    private const val MAX_TABLE_CELL_CHARS = 16

    /** 完整渲染入口: 解析 MD 并切块; 无表格时退化为单文本块(与旧路径等价) */
    fun render(md: String): List<MdRenderBlock> {
        val t0 = System.nanoTime()
        val result = renderInner(md)
        val dtUs = (System.nanoTime() - t0) / 1000
        android.util.Log.i("PerfB", "MdToBlocks.render len=${md.length} blocks=${result.size} dtUs=$dtUs")
        return result
    }

    private fun renderInner(md: String): List<MdRenderBlock> {
        if (md.isBlank()) return emptyList()
        val parsed = MdToSpans.parse(md)
        android.util.Log.i("NyralTbl", "parseOK textLen=" + parsed.displayText.length + " spans=" + parsed.spans.joinToString(",") { it.type.name } + " (M6 脱敏)") 
        val tables = parsed.spans.filter { it.type == MdSpanType.TABLE_BLOCK }.sortedBy { it.start }
        if (tables.isEmpty()) {
            val spanned = MdSpannable.toSpannable(parsed)
            if (spanned.isBlank()) return emptyList()
            return listOf(MdRenderBlock.TextBlock(spanned))
        }
        val blocks = ArrayList<MdRenderBlock>()
        var cursor = 0
        for (t in tables) {
            if (t.start > cursor) {
                textSegment(parsed, cursor, t.start)?.let { blocks.add(it) }
            }
            tableBlock(parsed, t.start, t.end)?.let { blocks.add(it) }
            cursor = t.end
        }
        if (cursor < parsed.displayText.length) {
            textSegment(parsed, cursor, parsed.displayText.length)?.let { blocks.add(it) }
        }
        return blocks
    }

    /** 切出 [s,e) 区间内的文本块: 过滤表格 span, 偏移重映射后走同一 Spannable 渲染 */
    private fun textSegment(parsed: MdSpans, s: Int, e: Int): MdRenderBlock.TextBlock? {
        if (s >= e) return null
        val sub = parsed.displayText.substring(s, e)
        if (sub.isBlank()) return null
        val clipped = ArrayList<MdSpan>(4)
        for (sp in parsed.spans) {
            if (sp.type == MdSpanType.TABLE_BLOCK || sp.type == MdSpanType.TABLE_ROW) continue
            if (sp.start >= s && sp.end <= e) {
                clipped.add(MdSpan(sp.start - s, sp.end - s, sp.type, sp.extra))
            }
        }
        val spanned = MdSpannable.toSpannable(MdSpans(sub, clipped))
        if (spanned.isBlank()) return null
        return MdRenderBlock.TextBlock(spanned)
    }

    /** 表格区间 [s,e) -> 结构化数据: 从 TABLE_ROW spans 的 extra JSON 重建 header/rows/colMax */
    private fun tableBlock(parsed: MdSpans, s: Int, e: Int): MdRenderBlock.TableBlock? {
        val rows = parsed.spans
            .filter { it.type == MdSpanType.TABLE_ROW && it.start >= s && it.end <= e }
            .sortedBy { it.start }
        if (rows.isEmpty()) return null
        var colMax = IntArray(0)
        var hasColMax = false
        val headerCells = ArrayList<MdCell>()
        val dataRows = ArrayList<List<MdCell>>()
        var first = true
        for (r in rows) {
            if (r.extra == null) continue
            try {
                val arr = JSONArray(r.extra)
                val isHeader = arr.getInt(0) == 1
                val cellsArr = arr.getJSONArray(2)
                val cells = ArrayList<MdCell>(cellsArr.length())
                for (i in 0 until cellsArr.length()) {
                    val c = cellsArr.getJSONArray(i)
                    cells.add(MdCell(c.getString(1), c.getInt(0)))
                }
                if (isHeader) {
                    headerCells.addAll(cells)
                } else {
                    dataRows.add(cells)
                }
                // 第4项: 整表统一列宽(每列最大字符数)
                if (first) {
                    val colArr = arr.optJSONArray(3)
                    if (colArr != null && colArr.length() > 0) {
                        hasColMax = true
                        val cm = IntArray(colArr.length())
                        for (i in 0 until colArr.length()) cm[i] = colArr.optInt(i, 0)
                        colMax = cm
                    }
                }
                first = false
            } catch (_: Exception) { /* 单行解析失败跳过 */ }
        }
        // 无 colMax 兜底: 按表头/数据行实测每列最大字符数
        if (!hasColMax) {
            val cm = IntArray(headerCells.size.coerceAtLeast(dataRows.maxOfOrNull { it.size } ?: 0))
            for ((j, c) in headerCells.withIndex()) if (j < cm.size) cm[j] = maxOf(cm[j], minOf(c.text.length, MAX_TABLE_CELL_CHARS))
            for (row in dataRows) for ((j, c) in row.withIndex()) if (j < cm.size) cm[j] = maxOf(cm[j], minOf(c.text.length, MAX_TABLE_CELL_CHARS))
            colMax = cm
        }
        if (headerCells.isEmpty() && dataRows.isEmpty()) return null
        return MdRenderBlock.TableBlock(MdTableData(headerCells, dataRows, colMax))
    }
}
