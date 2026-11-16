package io.github.aixtin.nyral

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/** 记忆预取指示点状态(2026-11-15)
 *  HIDDEN/ MISS: 不显示; SEARCHING: 呼吸中(检索进行中); HIT: 常亮(已检索到相关记忆) */
internal enum class MemDotState { HIDDEN, SEARCHING, HIT, MISS }

/** 渲染指示点。底栏不可用(AI 输出中/语音模式/发送按钮隐藏)时一律隐藏, 避免虚线元素残留。
 *  待接 API, 不是死代码(2026-11-16): 目前只有 MemoryPrefetch 接入后才会调用它,
 *  applyMemDotFromInput() 那个"按输入字数亮灯"的假信号驱动已在本次修复中删除,
 *  因为它在没做任何检索的情况下就显示"已检索到记忆"。请勿顺手清理本函数。 */
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
