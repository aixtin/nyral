package io.github.aixtin.nyral

/**
 * 流式收口的"差值补写"算法（2026-11-16）。
 *
 * 背景：停止/超时收口时，读循环里未换行的那段残余正文是由引擎在断链之后经 `onDelta` 补发的，
 * 而 `StreamUiBridge.onDelta` / `dispatch` 都带"已取消就不再渲染"的门禁，于是引擎把残余并进了
 * 落库正文（reply），UI 侧的气泡却缺这一段 —— 表现为"库里有、屏上没有"，且 rendered 快照写回后
 * 会长期缺这段。这里按"已渲染前缀"做一次差值补写。
 *
 * 安全取向：前缀对不上就返回 null（宁缺不错）。正文可能被表情掩码、换行归一化处理过，
 * 一旦不匹配说明无法安全推断缺口，此时不补。
 *
 * 抽成纯函数便于在 JVM 单测里覆盖边界（见 StreamTailTest）。
 */
internal fun missingTail(full: String, visible: String): String? {
    // 单出口写法: detekt 的 ReturnCount 门禁要求 ≤2
    return when {
        full.isEmpty() -> null
        visible.length >= full.length -> null
        full.startsWith(visible) -> full.substring(visible.length)
        else -> null
    }
}
