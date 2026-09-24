package io.github.aixtin.nyral

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan

/**
 * 代码块整块圆角背景 span(流式轻量版, 零 markwon 依赖).
 * 对齐收尾 RoundedCodeBlockSpan 的视觉: 整块浅灰底 + 四角圆角(首行上两角/末行下两角),
 * 避免流式阶段文本级底色 -> 收尾整块圆角的视觉突变。
 */
class RoundedBlockBgSpan(
    private val bgColor: Int,
    private val density: Float
) : MetricAffectingSpan(), LeadingMarginSpan {

    private val radius = 0f
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rectF = RectF()
    private val path = Path()
    private val radii = FloatArray(8)

    override fun updateMeasureState(p: TextPaint) {}
    override fun updateDrawState(ds: TextPaint) {}

    override fun getLeadingMargin(first: Boolean): Int = 0

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int,
        bottom: Int, text: CharSequence, start: Int, end: Int,
        first: Boolean, layout: Layout
    ) {
        val spanStart: Int
        val spanEnd: Int
        if (text is Spanned) {
            val sp = text as Spanned
            spanStart = sp.getSpanStart(this)
            spanEnd = sp.getSpanEnd(this)
        } else {
            spanStart = start
            spanEnd = end
        }
        val isFirstRow = start <= spanStart && end > spanStart
        val isLastRow = end >= spanEnd && start < spanEnd

        val left = x.toFloat()
        val right = (c.width - radius).coerceAtLeast(left + 1f)

        radii[0] = if (isFirstRow) radius else 0f
        radii[1] = if (isFirstRow) radius else 0f
        radii[2] = if (isFirstRow) radius else 0f
        radii[3] = if (isFirstRow) radius else 0f
        radii[4] = if (isLastRow) radius else 0f
        radii[5] = if (isLastRow) radius else 0f
        radii[6] = if (isLastRow) radius else 0f
        radii[7] = if (isLastRow) radius else 0f

        rectF.set(left, top.toFloat(), right, bottom.toFloat())
        path.reset()
        path.addRoundRect(rectF, radii, Path.Direction.CW)
        bgPaint.color = bgColor
        c.drawPath(path, bgPaint)
    }
}
