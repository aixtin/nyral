package io.github.aixtin.nyral

import android.view.View

/** 附件/输入栏粘合行为域(MainActivity 扩展): 底栏三形态布局切换 + 附件发送入口
 *  —— 自 MainActivity.kt 拆出；字段 attachBtn/attachBtn2/attachWrap/attachPreviewWrap/attachPreviewRow/pendingAttachments
 *  仍留在 MainActivity 声明，UI 创建仍在 setup 方法内（与 sendBtn/stopBtn 同槽位叠放，暂不迁移） */

internal fun MainActivity.onSend() = doSend(pendingAttachments.toList())

/** 统一刷新底栏三形态布局(槽位固定/输入框左右宽距恒不动):
 * 支持语音模型: 无字→槽A=语音 槽B=附件(+); 有字→槽A=附件(+) 槽B=发送
 * 不支持语音模型: 无论有无文字→槽A=附件(+) 槽B=发送 (语音槽由附件接管, 不留空白)
 * AI输出/语音模式期间不切换 */
internal fun MainActivity.applyInputMode() {
    if (session.aiBusy || voiceMode) return
    val hasText = input.text.isNotBlank()
    if (!currentModelSupportsVoice()) {
        // 不支持语音: 恒为 [附件(槽A)][发送(槽B)]
        micBtn.visibility = View.GONE
        attachBtn2.visibility = View.VISIBLE
        attachBtn.visibility = View.GONE
        sendBtn.visibility = View.VISIBLE
        return
    }
    // 槽A(micWrap): 有字→附件按钮; 无字→让语音按钮显示
    attachBtn2.visibility = if (hasText) View.VISIBLE else View.GONE
    // 槽B(attachWrap): 有字→发送; 无字→附件按钮
    attachBtn.visibility = if (hasText) View.GONE else View.VISIBLE
    sendBtn.visibility = if (hasText) View.VISIBLE else View.GONE
    // 有字时语音按钮 INVISIBLE 占位防槽塌陷(槽A由附件按钮接管), 无字显示
    micBtn.visibility = if (hasText) View.INVISIBLE else View.VISIBLE
}

/** 刷新语音切换按钮及底栏: 仅当当前模型支持语音时展示语音; 不支持时隐藏并强制退回文字输入 */
internal fun MainActivity.refreshVoiceButton() {
    if (!currentModelSupportsVoice()) {
        if (voiceMode) {
            voiceMode = false
            input.visibility = View.VISIBLE
            speakBar.visibility = View.GONE
            micBtn.background = micIconBg(false, density = resources.displayMetrics.density)
        }
    }
    applyInputMode()
}

/** 底栏交互入口: 输入框文字变化时刷新三形态布局 */
internal fun MainActivity.updateInputMode() {
    applyInputMode()
}
