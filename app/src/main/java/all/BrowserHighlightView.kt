package io.github.aixtin.nyral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** 高亮圈层: 全屏透明覆盖, 在目标矩形外画半透明遮罩 + 圆角描边高亮, 跟随滚动 */
internal class BrowserHighlightView(context: Context) : View(context) {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#40000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#FF7043")
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        color = Color.parseColor("#FFAB40")
    }
    private var target: RectF? = null
    private var scrollX = 0
    private var scrollY = 0
    private var docH = 0
    private var viewH = 0
    private var scale = 1f
    // 高亮时刻的滚动锚点: 视口坐标随高亮后的滚动增量平移, 实现滚动跟随
    private var anchorSx = 0
    private var anchorSy = 0
    private var anchored = false

    fun setTarget(r: RectF) { target = r; anchored = false; setWillNotDraw(false); invalidate() }
    fun updateScroll(sx: Int, sy: Int, docHeight: Int, viewHeight: Int, s: Float) {
        if (target != null && !anchored) { anchorSx = sx; anchorSy = sy; anchored = true }
        scrollX = sx; scrollY = sy; docH = docHeight; viewH = viewHeight; scale = s; invalidate()
    }
    fun clearTarget() { target = null; setWillNotDraw(true); invalidate() }

    override fun onDraw(c: Canvas) {
        val t = target ?: return
        // 视口坐标(高亮时刻) + 高亮后的滚动增量, 实现滚动跟随
        val s = scale
        val top = t.top * s - (scrollY - anchorSy)
        val bottom = t.bottom * s - (scrollY - anchorSy)
        val left = t.left * s - (scrollX - anchorSx)
        val right = t.right * s - (scrollX - anchorSx)
        // 超出视口的元素裁剪掉
        if (bottom < 0 || top > viewH) return
        val r = RectF(left, top, right, bottom)
        // 目标外遮罩(四块)
        c.drawRect(0f, 0f, width.toFloat(), r.top.coerceAtLeast(0f), maskPaint)
        c.drawRect(0f, r.bottom.coerceAtMost(height.toFloat()), width.toFloat(), height.toFloat(), maskPaint)
        c.drawRect(0f, r.top.coerceAtLeast(0f), r.left.coerceAtLeast(0f), r.bottom.coerceAtMost(height.toFloat()), maskPaint)
        c.drawRect(r.right.coerceAtMost(width.toFloat()), r.top.coerceAtLeast(0f), width.toFloat(), r.bottom.coerceAtMost(height.toFloat()), maskPaint)
        // 圆角高亮框 + 四角加粗
        val rr = RectF(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f)
        c.drawRoundRect(rr, 12f, 12f, borderPaint)
        val L = 26f
        c.drawLine(rr.left, rr.top + L, rr.left, rr.top, cornerPaint)
        c.drawLine(rr.left, rr.top, rr.left + L, rr.top, cornerPaint)
        c.drawLine(rr.right - L, rr.top, rr.right, rr.top, cornerPaint)
        c.drawLine(rr.right, rr.top, rr.right, rr.top + L, cornerPaint)
        c.drawLine(rr.right, rr.bottom - L, rr.right, rr.bottom, cornerPaint)
        c.drawLine(rr.right, rr.bottom, rr.right - L, rr.bottom, cornerPaint)
        c.drawLine(rr.left + L, rr.bottom, rr.left, rr.bottom, cornerPaint)
        c.drawLine(rr.left, rr.bottom, rr.left, rr.bottom - L, cornerPaint)
    }
}
