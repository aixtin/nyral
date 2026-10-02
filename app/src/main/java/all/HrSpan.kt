package io.github.aixtin.nyral

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/**
 * 分割线 span: 替换零宽占位字符, 绘制一条占满 TextView 可用宽度的横线。
 * 宽度复用 MdSpannable.tableMaxWidth(流式渲染前由 AiBubbleHolder 注入), 与表格同机制。
 */
class HrSpan(
    private val density: Float
) : ReplacementSpan() {

    /** 线条半高(dp) */
    private val lineH = (1.5f * density).toInt()
    /** 上下留白(dp) */
    private val padV = (9f * density).toInt()

    fun getDensity(): Float = density

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            fm.ascent = -padV - lineH
            fm.descent = padV
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        val w = MdSpannable.tableMaxWidth
        return if (w > 0) w else (240f * density).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val w = MdSpannable.tableMaxWidth
        val right = if (w > 0) x + w else x + (240f * density)
        val oldColor = paint.color
        paint.color = 0xFFD6D6D6.toInt()
        canvas.drawRect(x, y - lineH / 2f, right, y + lineH / 2f, paint)
        paint.color = oldColor
    }
}
