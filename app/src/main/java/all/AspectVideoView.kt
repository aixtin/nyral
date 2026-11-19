package io.github.aixtin.nyral

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/**
 * 按视频原始宽高比等比缩放的 VideoView:
 * 横屏视频在竖长容器里左右铺满、上下留黑并垂直居中;
 * 竖屏视频在横向容器里上下铺满、左右留黑并水平居中;
 * 永不拉伸变形。无视频轨(纯音频)时退回铺满父容器。
 */
class AspectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VideoView(context, attrs) {

    private var videoW = 0
    private var videoH = 0

    /** prepared 后由外部传入真实视频尺寸(含像素宽高比修正) */
    fun setAspect(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1) {
        if (width <= 0 || height <= 0) return
        var w = width.toLong()
        var h = height.toLong()
        if (sarNum > 0 && sarDen > 0) {
            w *= sarNum.toLong()
            h *= sarDen.toLong()
        }
        videoW = w.toInt()
        videoH = h.toInt()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cw = MeasureSpec.getSize(widthMeasureSpec)
        val ch = MeasureSpec.getSize(heightMeasureSpec)
        if (videoW <= 0 || videoH <= 0 || cw <= 0 || ch <= 0) {
            // 尚未拿到视频尺寸(或纯音频): 铺满
            setMeasuredDimension(cw, ch)
            return
        }
        val scale = minOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
        val w = (videoW * scale).toInt().coerceAtLeast(1)
        val h = (videoH * scale).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    /** 供外部在切换媒体源时复位 */
    fun resetAspect() {
        videoW = 0
        videoH = 0
        requestLayout()
    }
}
