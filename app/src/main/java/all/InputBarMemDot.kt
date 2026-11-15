package io.github.aixtin.nyral

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/** 记忆预取指示点状态(2026-11-15)
 *  HIDDEN/ MISS: 不显示; SEARCHING: 呼吸中(检索进行中); HIT: 常亮(已检索到相关记忆) */
internal enum class MemDotState { HIDDEN, SEARCHING, HIT, MISS }

/** 渲染指示点。底栏不可用(AI 输出中/语音模式/发送按钮隐藏)时一律隐藏, 避免虚线元素残留。
 *  TODO: MemoryPrefetch 就绪后由真实检索结果驱动, 本函数签名无需改动。 */
internal fun MainActivity.applyMemDot(state: MemDotState) {
    if (!memDotReady) return
    memDotState = state
    memDotAnim?.cancel()
    memDotAnim = null
    val blocked = session.aiBusy || voiceMode || sendBtn.visibility != View.VISIBLE
    if (blocked || state == MemDotState.HIDDEN || state == MemDotState.MISS) {
        memDot.visibility = View.GONE
        memDot.alpha = 0f
        return
    }
    memDot.visibility = View.VISIBLE
    when (state) {
        MemDotState.HIT -> {
            memDot.background = rounded(dp(3), Ui.PRIMARY)
            memDot.alpha = 1f
        }
        else -> {
            memDot.background = rounded(dp(3), Ui.SUB)
            memDotAnim = ValueAnimator.ofFloat(0.25f, 0.7f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { memDot.alpha = it.animatedValue as Float }
                start()
            }
        }
    }
}

/** 临时假信号(待 MemoryPrefetch 接入后整体替换): 输入字数驱动三态, 仅用于验证视觉与布局 */
internal fun MainActivity.applyMemDotFromInput() {
    val n = input.text.toString().trim().length
    applyMemDot(
        when {
            n == 0 -> MemDotState.HIDDEN
            n < 6 -> MemDotState.SEARCHING
            else -> MemDotState.HIT
        }
    )
}
