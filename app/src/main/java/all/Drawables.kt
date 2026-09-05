package io.github.aixtin.nyral

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** 带缺口的旋转加载环: 画 270° 圆弧, 剩 90° 缺口, 旋转时有明显转动感 */
class ArcRingDrawable(private val size: Int, stroke: Int, color: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke.toFloat()
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private var angle = 0f

    override fun draw(canvas: Canvas) {
        val half = size / 2f
        val r = half - paint.strokeWidth / 2f - 1f
        canvas.save()
        canvas.rotate(angle, half, half)
        canvas.drawArc(half - r, half - r, half + r, half + r, 0f, 270f, false, paint)
        canvas.restore()
    }

    fun setAngle(a: Int) {
        angle = a.toFloat()
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
}


/** 固定背景: 按位图宽高比固定绘制高度, 输入法弹起窗口变矮时只裁切不拉伸不平铺(类微信) */
class FixedBgDrawable(private val bmp: android.graphics.Bitmap) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas: android.graphics.Canvas) {
        // centerCrop: 按比例放大至完全覆盖窗口, 多余裁掉, 居中 -> 任意尺寸图片都不留空白(同微信)
        val vw = bounds.width().coerceAtLeast(1)
        val vh = bounds.height().coerceAtLeast(1)
        val bw = bmp.width.coerceAtLeast(1)
        val bh = bmp.height.coerceAtLeast(1)
        val scale = Math.max(vw.toFloat() / bw, vh.toFloat() / bh)
        val dw = (bw * scale).toInt()
        val dh = (bh * scale).toInt()
        val left = (vw - dw) / 2
        val top = (vh - dh) / 2
        canvas.drawBitmap(bmp, null, android.graphics.Rect(left, top, left + dw, top + dh), paint)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
