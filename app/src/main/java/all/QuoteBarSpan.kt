package io.github.aixtin.nyral

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan

/**
 * 引用块竖线 span: 自实现 LeadingMarginSpan, 替代系统 QuoteSpan。
 * 系统 QuoteSpan 在部分 TextView 场景(28+/流式渲染)缩进生效但竖线不绘制,
 * 这里自绘竖线保证与缩进同时可见。
 */
class QuoteBarSpan(
    private val color: Int,
    private val stripeWidth: Int,
    private val gapWidth: Int
) : LeadingMarginSpan {

    override fun getLeadingMargin(first: Boolean): Int = stripeWidth + gapWidth

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        val style = p.style
        val oldColor = p.color
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRect(x.toFloat(), top.toFloat(), (x + dir * stripeWidth).toFloat(), bottom.toFloat(), p)
        p.style = style
        p.color = oldColor
    }
}
