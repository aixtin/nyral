package io.github.aixtin.nyral

import android.view.View

/** 附件/输入栏粘合行为域(MainActivity 扩展): 底栏三形态布局切换 + 附件发送入口
 *  —— 自 MainActivity.kt 拆出；字段 attachBtn/attachBtn2/attachWrap/attachPreviewWrap/attachPreviewRow/pendingAttachments
 *  仍留在 MainActivity 声明，UI 创建仍在 setup 方法内（与 sendBtn/stopBtn 同槽位叠放，暂不迁移） */

internal fun MainActivity.onSend() = doSend(pendingAttachments.toList())

/** 拍照/相册入口按当前模型能力置灰(仅视觉; 点击时再判一次, 防切换模型后状态过期)
 *  拍照只产出图片 → 只看识图能力; 相册可出图片/视频 → 任一能力即可 */
internal fun MainActivity.refreshAttachRowCapability() {
    val caps = ApiConfig.modelCapabilities(ApiConfig.providerId(), ApiConfig.model())
    val hasCamera = hasUsableCamera()
    attachCameraEntry?.alpha = if (hasCamera && ApiConfig.CAP_IMAGE in caps) 1f else 0.4f
    attachAlbumEntry?.alpha = if (ApiConfig.CAP_IMAGE in caps || ApiConfig.CAP_VIDEO in caps) 1f else 0.4f
}

/** 门禁按钮随档位换色: 严格=危险红 / 自动=主题蓝 / 放行=灰 */
internal fun MainActivity.refreshGateButtons() {
    val mode = SecurityConfig.dangerMode(this)
    val fg: Int
    when (mode) {
        "strict" -> { fg = Ui.DANGER }
        "off" -> { fg = Ui.SUB }
        else -> { fg = Ui.PRIMARY }
    }
    listOf(attachBtn, attachBtn2).forEach {
        it.setColorFilter(fg)
        it.background = glass(dp(16), dp(1))
    }
    gateStatusTv?.text = SecurityConfig.modeText(mode)
}

/** 统一刷新底栏三形态布局(槽位固定/输入框左右宽距恒不动):
 * 支持语音模型: 无字→槽A=语音 槽B=门禁; 有字→槽A=门禁 槽B=发送
 * 不支持语音模型: 无论有无文字→槽A=门禁 槽B=发送 (语音槽由门禁接管, 不留空白)
 * AI输出/语音模式期间不切换 */
internal fun MainActivity.applyInputMode() {
    if (session.aiBusy || voiceMode) return
    val hasText = input.text.isNotBlank()
    if (!currentModelSupportsVoice()) {
        // 不支持语音: 恒为 [门禁(槽A)][发送(槽B)]
        micBtn.visibility = View.GONE
        attachBtn2.visibility = View.VISIBLE
        attachBtn.visibility = View.GONE
        sendBtn.visibility = View.VISIBLE
        return
    }
    // 槽A(micWrap): 有字→门禁按钮; 无字→让语音按钮显示
    attachBtn2.visibility = if (hasText) View.VISIBLE else View.GONE
    // 槽B(attachWrap): 有字→发送; 无字→门禁按钮
    attachBtn.visibility = if (hasText) View.GONE else View.VISIBLE
    sendBtn.visibility = if (hasText) View.VISIBLE else View.GONE
    // 有字时语音按钮 INVISIBLE 占位防槽塌陷(槽A由门禁按钮接管), 无字显示
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
    refreshAttachRowCapability()
}

/** 底栏交互入口: 输入框文字变化时刷新三形态布局 */
internal fun MainActivity.updateInputMode() {
    applyInputMode()
    // 输入态变化顺带对齐状态带(发送/收尾都会走到这里, 上下文数字随之更新)
    refreshStatusStrip()
    // fix(2026-11-16): 记忆召回指示点下线 —— 原来用"输入字数"假信号驱动(≥6 字常亮"已检索到记忆"),
    // 真实检索(MemoryPrefetch)还没接上, 等于给用户一个会说谎的指示器。
    // 接上真实结果后在此处调用 applyMemDot(state) 即可恢复。
    // applyMemDotFromInput()
}
