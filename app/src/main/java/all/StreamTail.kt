package io.github.aixtin.nyral

/**
 * 流式收口的"差值补写"算法（2026-11-16 第二版）。
 *
 * 背景：停止/超时收口时，读循环里未换行的那段残余正文是由引擎在断链之后经 `onDelta` 补发的，
 * 而 `StreamUiBridge.onDelta` / `dispatch` 都带"已取消就不再渲染"的门禁，于是引擎把残余并进了
 * 落库正文（reply），UI 侧的气泡却缺这一段 —— 表现为"库里有、屏上没有"，且 rendered 快照写回后
 * 会长期缺这段。这里按"已渲染前缀"做一次差值补写。
 *
 * 为什么不是纯 startsWith（第一版的 bug）：引擎侧 `CancelWithPartial.merge` 拼的是
 * `full + "\n\n" + partial`（LocalEngine.kt:382），而 `holder.contentSnapshot()` 取到的是
 * 连续文本或单换行 —— 两侧差异是**分隔符**，纯前缀比对在多轮会话里必然失败、且是静默失效。
 * 为什么也不是"最长公共后缀"：它只能判断"尾巴是否一致"，判断不了"该从哪里切"，切点仍要靠猜。
 *
 * 现在的做法：先试直接前缀；不成立就把 `visible` 的**非空白字符序列**拿去和 `full` 逐字符对齐，
 * 只要它是 `full` 的非空白前缀，就把 `full` 里剩下的部分（含中间的分隔符）补上；对不齐返回 null。
 *
 * 安全取向：对不齐就返回 null（宁缺不错）。正文被表情掩码、`att://` 显示形态改写等情况下两侧
 * 不同源，无法安全推断缺口，此时不补 —— 那一类要靠 B3（onDone 按 reply 全量重绘）根治。
 *
 * 抽成纯函数便于在 JVM 单测里覆盖边界（见 StreamTailTest）。
 */
internal fun missingTail(full: String, visible: String): String? = when {
    full.isEmpty() -> null
    visible.isEmpty() -> full.ifBlank { null }
    visible.length >= full.length -> null
    full.startsWith(visible) -> full.substring(visible.length).ifBlank { null }
    else -> blankInsensitiveTail(full, visible)
}

/** 空白不敏感前缀对齐：忽略两侧的空白差异，只要求非空白字符序列逐字相同。 */
private fun blankInsensitiveTail(full: String, visible: String): String? {
    val target = visible.filterNot { it.isWhitespace() }
    if (target.isEmpty()) return null
    var matched = 0
    var idx = 0
    var broke = false
    while (idx < full.length && matched < target.length) {
        val c = full[idx]
        if (!c.isWhitespace()) {
            if (c != target[matched]) {
                broke = true
                break
            }
            matched++
        }
        idx++
    }
    return if (!broke && matched == target.length) full.substring(idx).ifBlank { null } else null
}
