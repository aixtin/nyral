package io.github.aixtin.nyral

import org.junit.Test

/**
 * 工具调用截断配对纯逻辑 JVM 测试(2026-10-05):
 * 锁住 MAX_TOOL_CALLS 截断子集计算, 防止 400 insufficient tool messages 回归
 * (回填数量必须等于实际执行数量)。
 */
class ToolCallPairerTest {

    private fun calls(n: Int): List<Pair<String, String>> =
        (0 until n).map { "tool_$it" to "{}" }

    @Test
    fun roomPlentyKeepsAll() {
        // 剩余额度充足: 全部调用都应执行
        val r = ToolCallPairer.allowedSubset(calls(5), alreadyExecuted = 10, maxToolCalls = 40)
        check(r.size == 5) { "额度充足时应全量执行, 实际 ${r.size}" }
        check(r == calls(5)) { "应保持原始顺序与内容" }
    }

    @Test
    fun truncatesToRemainingRoom() {
        // 剩余额度不足: 只执行前 room 个(保持顺序)
        val r = ToolCallPairer.allowedSubset(calls(5), alreadyExecuted = 38, maxToolCalls = 40)
        check(r.size == 2) { "应截断到剩余额度 2, 实际 ${r.size}" }
        check(r[0] == ("tool_0" to "{}") && r[1] == ("tool_1" to "{}")) { "应取前 2 个原始调用" }
    }

    @Test
    fun exactlyFitsRoom() {
        // 恰好占满剩余额度: 全量执行, 不截断
        val r = ToolCallPairer.allowedSubset(calls(2), alreadyExecuted = 38, maxToolCalls = 40)
        check(r.size == 2) { "恰好占满时应全量执行, 实际 ${r.size}" }
    }

    @Test
    fun zeroRoomReturnsEmpty() {
        // 已用完额度: 不执行任何调用
        val r = ToolCallPairer.allowedSubset(calls(5), alreadyExecuted = 40, maxToolCalls = 40)
        check(r.isEmpty()) { "无剩余额度时不应执行任何调用" }
    }

    @Test
    fun overBudgetReturnsEmpty() {
        // 已执行数超过上限(防御): 返回空
        val r = ToolCallPairer.allowedSubset(calls(3), alreadyExecuted = 50, maxToolCalls = 40)
        check(r.isEmpty()) { "超上限时应返回空" }
    }

    @Test
    fun nonPositiveMaxReturnsEmpty() {
        val r = ToolCallPairer.allowedSubset(calls(3), alreadyExecuted = 0, maxToolCalls = 0)
        check(r.isEmpty()) { "maxToolCalls<=0 时应返回空" }
    }

    @Test
    fun emptyInputStaysEmpty() {
        val r = ToolCallPairer.allowedSubset(emptyList(), alreadyExecuted = 0, maxToolCalls = 40)
        check(r.isEmpty()) { "空输入应返回空" }
    }
}
