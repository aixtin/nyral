package io.github.aixtin.nyral

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan

/** 气泡渲染 Span 集合: 从 MainActivity 拆出, 不依赖 Activity/this */

/** 语音气泡声波动画共享相位 */
class WaveState { var phase = 0f }

/** 气泡内嵌图片 span: 显式扩展行高至图片高度 + 底部对齐绘制, 避免标准 ImageSpan 在 wrap_content TextView 中的行高/裁剪问题 */
class BubbleImageSpan(private val d: Drawable) : ImageSpan(d, ImageSpan.ALIGN_BOTTOM) {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -d.intrinsicHeight
            fm.descent = 0
            fm.top = fm.ascent
            fm.bottom = 0
        }
        return d.intrinsicWidth
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val b = d
        canvas.save()
        canvas.translate(x, (bottom - b.bounds.bottom).toFloat())
        b.draw(canvas)
        canvas.restore()
    }
}

/** 文件卡片类型角标 span: 白色圆角底 + 品牌蓝粗体字, 垂直居中(适配深蓝气泡)
 *  @param density 屏幕像素密度, 替代 Activity.dp() */
class BadgeSpan(private val label: String, private val density: Float) : ReplacementSpan() {
    private fun dp(v: Int) = (v * density).toInt()
    private val padH = dp(7).toFloat()
    private val h = dp(20).toFloat()
    private val radius = dp(6).toFloat()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B93F6")
        textSize = dp(11).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        return (textPaint.measureText(label) + padH * 2).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val w = textPaint.measureText(label) + padH * 2
        val cy = (top + bottom) / 2f
        canvas.drawRoundRect(RectF(x, cy - h / 2f, x + w, cy + h / 2f), radius, radius, bgPaint)
        canvas.drawText(label, x + w / 2f, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }
}

/** 语音气泡内声波动画 span: 播放时 5 根白色竖条随共享相位跳动(类似微信语音), 静止时矮平
 *  @param density 屏幕像素密度, 替代 Activity.dp() */
class WaveSpan(private val state: WaveState, private val playing: Boolean, private val density: Float) : ReplacementSpan() {
    private fun dp(v: Int) = (v * density).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        return dp(26)
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val barW = dp(3).toFloat()
        val gap = dp(2).toFloat() + 0.5f
        val base = dp(6).toFloat()                              // 静止基准高度
        val amp = if (playing) dp(8).toFloat() else 0f          // 播放时跳动幅度
        val cy = (top + bottom) / 2f
        val radius = dp(1).toFloat() + 0.5f
        val phase = state.phase * Math.PI * 2
        for (i in 0 until 5) {
            val t = phase + i * 1.1
            val h = base + amp * (0.5f + 0.5f * Math.sin(t)).toFloat()
            val bx = x + i * (barW + gap)
            canvas.drawRoundRect(bx, cy - h / 2f, bx + barW, cy + h / 2f, radius, radius, p)
        }
    }
}

/** 语音气泡播放/暂停按钮: playing=false 白实心右三角(播放), playing=true 两条白竖线(暂停), 垂直居中 */
class PlayPauseIconSpan(private val size: Int, private val playing: Boolean) : ReplacementSpan() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        return size
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        canvas.save()
        val s = size.toFloat()
        val base = (top + bottom) / 2f
        canvas.translate(x, base - s / 2f)
        if (playing) {
            // 暂停: 两条圆角竖线
            val bw = s * 0.13f
            val gap = s * 0.07f
            val topY = s * 0.18f
            val botY = s * 0.82f
            val r = bw / 2f
            canvas.drawRoundRect(RectF(cx(s) - gap / 2f - bw, topY, cx(s) - gap / 2f, botY), r, r, p)
            canvas.drawRoundRect(RectF(cx(s) + gap / 2f, topY, cx(s) + gap / 2f + bw, botY), r, r, p)
        } else {
            // 播放: 实心右三角
            val path = Path()
            path.moveTo(s * 0.36f, s * 0.18f)
            path.lineTo(s * 0.80f, s * 0.50f)
            path.lineTo(s * 0.36f, s * 0.82f)
            path.close()
            canvas.drawPath(path, p)
        }
        canvas.restore()
    }
    private fun cx(s: Float) = s / 2f
}
