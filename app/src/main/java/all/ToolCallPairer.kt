package io.github.aixtin.nyral

/**
 * 工具调用截断配对(2026-10-05 为 400 insufficient tool messages 修复而抽):
 * 单会话工具调用有 MAX_TOOL_CALLS 上限, 主循环按序执行调用, 达上限即停。
 * 回填 assistant tool_calls 时必须只用"实际执行的子集", 与逐条 role=tool 结果消息数量一一对应,
 * 否则服务端拒 "insufficient tool messages following tool_calls"(LocalEngine 曾因回填全集触发)。
 */
object ToolCallPairer {

    /**
     * 计算在 maxToolCalls 额度内本轮实际可执行的调用子集(保持原始顺序)。
     * @param allCalls 模型本轮请求的全部调用(可能并行多 call)
     * @param alreadyExecuted 本会话已执行的调用数(会话级累计)
     * @param maxToolCalls 单会话工具调用上限(MAX_TOOL_CALLS)
     * @return 实际应执行的子集; 空列表表示无剩余额度
     */
    fun allowedSubset(
        allCalls: List<Pair<String, String>>,
        alreadyExecuted: Int,
        maxToolCalls: Int
    ): List<Pair<String, String>> {
        if (maxToolCalls <= 0) return emptyList()
        val room = (maxToolCalls - alreadyExecuted).coerceAtLeast(0)
        return if (allCalls.size <= room) allCalls else allCalls.subList(0, room)
    }
}
