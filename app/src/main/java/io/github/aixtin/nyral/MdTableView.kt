package io.github.aixtin.nyral

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ===== 方案 C 独立表格视图 (2026-10-02) =====
 * 所有线由布局引擎出线(View/Drawable 承担), 彻底退役 Canvas onDraw 手绘:
 *  - 竖线: 每格右侧 1px View(末列不画, 右侧由外框收口)。竖线锚定在 cell 布局内,
 *          与单元格边界天然对齐, 根治 onDraw 中 cell.right 未加 row.left 偏移
 *          导致整组竖线左移、末列视觉偏宽的 bug;
 *  - 行线: 相邻两行之间 1px View(最后一行不画, 底边由外框收口);
 *  - 表头底: 表头行 GradientDrawable 实心圆角(上边两角), 不再单独画线;
 *  - 外框: 整表 GradientDrawable stroke 圆角。
 *  - 列宽: Paint.measureText 实测文本宽 + 左右各 12dp 留白(DeepSeek 策略),
 *          保底 8dp、单列上限 240dp; 总宽超可用宽先等比压缩(每列保底 48dp),
 *          仍超则保留内部横滑。
 *  - 左右留白对称: 列宽由内容+固定留白决定, 表格总宽=Σ列宽, 无需手算 padding 修正。
 */
internal class MdTableView(context: Context) : HorizontalScrollView(context) {

    private val tableLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var cornerRadius = 0f
    private var borderColor = 0
    private var headerBg = 0
    private var rowLineColor = 0
    private var bodyTextColor = 0
    private var densityScale = 1f
    private var tableWidthPx = 0   // 内容区总宽(Σ列宽), 行线/表头底宽度基准

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(tableLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Float): Float = v * densityScale

    /**
     * 绑定表格数据并渲染。
     * @param data       结构化表格(header + rows + colMaxChars)
     * @param availW     气泡可用宽度(px), 超宽走内部横滑
     * @param density    屏幕密度
     * @param border     边框颜色(Argb)
     * @param headerBgC  表头底色(Argb)
     * @param textColor  正文文字色(Argb)
     * @param headerText 表头文字色(Argb, 默认白)
     */
    fun bindTable(
        data: MdTableData,
        availW: Int,
        density: Float,
        scaledDensity: Float,
        border: Int,
        headerBgC: Int,
        textColor: Int,
        headerText: Int = Color.WHITE
    ) {
        densityScale = density
        borderColor = border
        headerBg = headerBgC
        bodyTextColor = textColor
        cornerRadius = dp(6f)
        rowLineColor = blendColor(border, 0xFFFFFF, 0.30f)

        tableLayout.removeAllViews()
        val cellPadH = dp(12f).toInt()
        val cellPadV = dp(6f).toInt()
        val outerPad = dp(8f).toInt()
        // 列数: 取 整表最大列宽数 / 表头列数 / 数据行最大列数 的最大值,
        // 防 colMaxChars 预扫描漏列时表头/内容被裁(缺列一律补空 cell)
        val cols = maxOf(
            data.colMaxChars.size,
            data.header.size,
            data.rows.maxOfOrNull { it.size } ?: 0
        ).coerceAtLeast(1)
        // 列宽: 按该列所有 cell 文本的真实测量宽度取最大(DeepSeek 同款策略),
        // 不再用字符数×估宽, 避免文字与边框脱节; 表头 bold / 正文 regular 分开测;
        // 文本封顶 16 字符(与 MAX_TABLE_CELL_CHARS 一致, 超长换行不撑爆列宽)。
        val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * scaledDensity }
        val colWidths = IntArray(cols)
        val rawWs = FloatArray(cols)
        val minW = dp(8f)
        val maxColW = dp(240f).toInt()
        for (i in 0 until cols) {
            var maxTextW = 0f
            if (i < data.header.size) {
                measurePaint.typeface = Typeface.DEFAULT_BOLD
                maxTextW = maxOf(maxTextW, measurePaint.measureText(data.header[i].text.take(16)))
            }
            for (row in data.rows) {
                if (i < row.size) {
                    measurePaint.typeface = Typeface.DEFAULT
                    maxTextW = maxOf(maxTextW, measurePaint.measureText(row[i].text.take(16)))
                }
            }
            rawWs[i] = maxTextW * 1.05f  // 5% 安全系数: 防 measureText 与 TextView 实际渲染差异导致文字被挤换行
            // 保底宽度: 仅防空列/极端短列塌缩到 0; 单列上限 240dp 防超长列撑爆整表
            val w = maxOf(rawWs[i], minW).toInt() + cellPadH * 2
            colWidths[i] = w.coerceAtMost(maxColW)
        }
        var naturalW = colWidths.sum()
        // 总宽压缩: 超过表格内可用宽(气泡可用宽 - 外padding) -> 先按比例压缩,
        // 但每列保底 >= 该列文字实测宽 + 左右留白, 否则文字会被挤成逐字竖排;
        // 若按文字需求保底后总宽仍超可用宽(列太多/文字太长), 放弃压缩, 保留内部横滑
        val availInner = (availW - outerPad * 2).coerceAtLeast(1)
        if (naturalW > availInner) {
            val scale = availInner.toFloat() / naturalW
            val needW = IntArray(cols)
            var needSum = 0
            for (i in 0 until cols) {
                // 文字最小需求: 实测宽 + 左右留白(封顶单列上限, 超长文本允许折行, 但绝不逐字竖排)
                needW[i] = (rawWs[i].toInt() + cellPadH * 2).coerceAtMost(maxColW)
                needSum += needW[i]
            }
            if (needSum <= availInner) {
                for (i in 0 until cols) {
                    colWidths[i] = maxOf((colWidths[i] * scale).toInt(), needW[i])
                }
                naturalW = colWidths.sum()
            }
            // needSum > availInner: 文字需求都放不下 -> 保持自然宽, 由 HorizontalScrollView 内部横滑
        }
        tableWidthPx = naturalW
        android.util.Log.d("TableFix", "density=$density scaled=$scaledDensity padH=$cellPadH padV=$cellPadV outer=$outerPad availW=$availW availInner=$availInner naturalW=$naturalW cols=$cols")
        android.util.Log.d("TableFix", "rawWs=${rawWs.joinToString(",")} colWidths=${colWidths.joinToString(",")} header=${data.header.joinToString("/"){it.text}}")
        android.util.Log.d("TableFix", "rows=${data.rows.joinToString("|"){r -> r.joinToString("/"){it.text}}}")
        tableLayout.setPadding(outerPad, outerPad, outerPad, outerPad)

        // 表头行(补齐到整表最大列数, 缺列补空 cell, 保证竖线贯穿结构完整)
        if (data.header.isNotEmpty() || cols > 0) {
            val tr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val cell = data.header.getOrNull(i) ?: MdCell("", 0)
                val w = colWidths.getOrElse(i) { colWidths.lastOrNull() ?: dp(40f).toInt() }
                addCellRow(tr, cell, w, i, cols, cellPadH, cellPadV, true, headerText)
            }
            tableLayout.addView(tr)
        }
        // 数据行(同样补齐到整表最大列数), 行与行之间插入 1px 行线 View
        val rowCount = data.rows.size
        for (r in 0 until rowCount) {
            val tr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val cell = data.rows[r].getOrNull(i) ?: MdCell("", 0)
                val w = colWidths.getOrElse(i) { colWidths.lastOrNull() ?: dp(40f).toInt() }
                addCellRow(tr, cell, w, i, cols, cellPadH, cellPadV, false, 0)
            }
            tableLayout.addView(tr)
            if (r < rowCount - 1) {
                tableLayout.addView(hlineView())
            }
        }
        // 表头底: 表头行实心圆角(上边两角), 与外框圆角一致
        if (tableLayout.childCount > 0) {
            val headerRow = tableLayout.getChildAt(0)
            headerRow.background = GradientDrawable().apply {
                setColor(headerBg)
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius,
                    cornerRadius, cornerRadius,
                    0f, 0f,
                    0f, 0f)
            }
        }
        // 外框: 整表圆角 stroke, 底透明(表头底由表头行背景承担)
        tableLayout.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = this@MdTableView.cornerRadius
            setStroke(dp(1f).toInt(), borderColor)
        }
        tableLayout.invalidate()
    }

    /**
     * 向行容器添加一个单元格: TextView(宽度=列宽-1px) + 右侧竖线 View(1px, 末列不画)。
     * 竖线锚定在 cell 布局内, 由布局引擎对齐, 不再依赖手算坐标。
     */
    private fun addCellRow(
        tr: LinearLayout,
        cell: MdCell,
        w: Int,
        colIndex: Int,
        cols: Int,
        padH: Int,
        padV: Int,
        isHeader: Boolean,
        headerText: Int
    ) {
        val isLast = (colIndex == cols - 1)
        val tv = TextView(context).apply {
            text = cell.text
            textSize = 14f
            setTextColor(if (isHeader) headerText else bodyTextColor)
            typeface = if (isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
        }
        val lineW = dp(1f).toInt().coerceAtLeast(1)
        if (isLast) {
            tr.addView(tv, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            tr.addView(tv, LinearLayout.LayoutParams(
                (w - lineW).coerceAtLeast(0), ViewGroup.LayoutParams.WRAP_CONTENT))
            tr.addView(View(context).apply {
                setBackgroundColor(rowLineColor)
            }, LinearLayout.LayoutParams(lineW, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** 行分隔线 View: 宽度=内容区总宽(不含 outer padding), 两端不碰外框 */
    private fun hlineView(): View = View(context).apply {
        setBackgroundColor(rowLineColor)
        layoutParams = LinearLayout.LayoutParams(tableWidthPx, dp(1f).toInt().coerceAtLeast(1))
    }

    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val ta = (a ushr 24) and 0xFF; val tb = (b ushr 24) and 0xFF
        val tr = (a ushr 16) and 0xFF; val tbr = (b ushr 16) and 0xFF
        val tg = (a ushr 8) and 0xFF; val tbg = (b ushr 8) and 0xFF
        val tbb = a and 0xFF; val tbbb = b and 0xFF
        return (((ta + ((tb - ta) * t).toInt()) shl 24) or
                ((tr + ((tbr - tr) * t).toInt()) shl 16) or
                ((tg + ((tbg - tg) * t).toInt()) shl 8) or
                (tbb + ((tbbb - tbb) * t).toInt()))
    }
}
