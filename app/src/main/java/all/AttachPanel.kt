package io.github.aixtin.nyral

import android.view.View

/**
 * 附件相关辅助(2026-11-21): 底部附件抽屉整体下线, 改为 PopupWindow 弹窗(拍照/相册/文件),
 * 本文件只保留与聊天视口/附件预览条相关的两个辅助函数。
 */

/** 聊天区视口尺寸变化(键盘压缩/恢复)后末条贴底:
 * 末条可见按真实像素 gap 拉底(长文/变高消息也精确); 末条不可见(用户正翻历史)保持阅读位置不动 */
internal fun MainActivity.alignChatToViewport() {
    // 合并节流: 掉帧时 post 队列里堆多个补偿会依次执行, 表现为消息"下去又上来";
    // 只保留最新一次(下一帧按最新视口重算)。
    if (chatAlignPending) return
    chatAlignPending = true
    chatRec.post {
        chatAlignPending = false
        if (chatRec.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return@post
        val lm = chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
        val lastPos = (chatRec.adapter?.itemCount ?: 0) - 1
        val lv = lm.findViewByPosition(lastPos) ?: return@post
        val gap = lv.bottom - (chatRec.height - chatRec.paddingBottom)
        if (gap != 0) chatRec.scrollBy(0, gap)
    }
}

/** 判断触摸点是否落在附件预览条可视区域内(点击预览条/×按钮只删附件, 不应收键盘) */
internal fun MainActivity.isTouchInsideAttachPreview(rawX: Float, rawY: Float): Boolean {
    val v = attachPreviewWrap
    if (v.visibility != View.VISIBLE) return false
    val loc = IntArray(2)
    v.getLocationOnScreen(loc)
    return rawX >= loc[0] && rawX <= loc[0] + v.width &&
            rawY >= loc[1] && rawY <= loc[1] + v.height
}
