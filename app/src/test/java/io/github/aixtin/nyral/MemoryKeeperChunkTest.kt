package io.github.aixtin.nyral

import org.junit.Test
import java.lang.reflect.Method

/**
 * MemoryKeeper 归档分块逻辑 JVM 测试。
 * 目标: 原文分块(每块 CHUNK_CHARS=800 字符)是记忆无损入库的前提,
 * 切块错误会导致向量化块缺失/越界, 属高风险纯逻辑区。
 * chunkText 为 object 私有方法, 通过反射调用真实实现, 不做拷贝复刻。
 */
class MemoryKeeperChunkTest {

    private val chunkMethod: Method by lazy {
        MemoryKeeper::class.java.getDeclaredMethod("chunkText", String::class.java, Int::class.java)
            .apply { isAccessible = true }
    }

    private fun chunk(text: String, size: Int): List<String> {
        @Suppress("UNCHECKED_CAST")
        return chunkMethod.invoke(MemoryKeeper, text, size) as List<String>
    }

    @Test
    fun chunkShortTextSingleBlock() {
        val lines = listOf("user: 你好", "assistant: 我在", "user[09-02 14:30]: 记住表格方案")
        val r = chunk(lines.joinToString("\n"), 800)
        check(r.size == 1) { "短文本应合并为 1 块, 实际 ${r.size}" }
        check(r[0].contains("你好") && r[0].contains("记住表格方案")) { "块内应包含全部行" }
    }

    @Test
    fun chunkSplitsAtLineBoundaryWhenPossible() {
        // 每行 500 字符, size=800: 第一行塞满后第二行放不下, 应在行边界切, 不拆行
        val a = "user: " + "x".repeat(500)
        val b = "assistant: " + "y".repeat(500)
        val r = chunk("$a\n$b", 800)
        check(r.size == 2) { "应在行边界切成 2 块, 实际 ${r.size}: $r" }
        check(r[0].startsWith("user:") && !r[0].contains("assistant")) { "第1块不应混入第2行: ${r[0].take(20)}" }
        check(r[1].startsWith("assistant:")) { "第2块应以第2行开头: ${r[1].take(20)}" }
    }

    @Test
    fun chunkOversizeSingleLineBecomesOwnBlock() {
        // 单行超长: 不拆行(保持消息完整性), 超长行单独成块
        val long = "user: " + "z".repeat(1200)
        val r = chunk(long, 800)
        check(r.size == 1) { "单行超长应整行成块(不截断), 实际 ${r.size}" }
        check(r[0].length == long.length) { "块应保留整行完整长度, 实际 ${r[0].length} vs ${long.length}" }
    }

    @Test
    fun chunkEmptyInputYieldsEmpty() {
        val r = chunk("", 800)
        check(r.isEmpty()) { "空输入应返回空列表, 实际 ${r}" }
    }

    @Test
    fun chunkBlankLinesFiltered() {
        val r = chunk("\n\n\n", 800)
        check(r.isEmpty()) { "全空白输入应过滤为空, 实际 ${r}" }
    }

    @Test
    fun chunkMultipleSmallLinesPackedUntilLimit() {
        val lines = (1..100).map { "line$it: " + "d".repeat(20) }
        val text = lines.joinToString("\n")
        val size = 300
        val r = chunk(text, size)
        check(r.size > 1) { "100 行应被切分为多块, 实际 ${r.size}" }
        // 除最后一块外, 每块都尽量贴近但不超 size(受行边界限制)
        for ((i, blk) in r.withIndex()) {
            check(blk.isNotBlank()) { "块 $i 不应为空白" }
            if (i < r.size - 1) {
                check(blk.length <= size) { "非最后一块长度 ${blk.length} 应 ≤ size=$size" }
            }
        }
        // 拼接后应无损还原全部行(去重空白)
        val joined = r.joinToString("\n").lines().filter { it.isNotBlank() }
        check(joined.size == 100) { "分块拼接应还原 100 行, 实际 ${joined.size}" }
    }

    @Test
    fun chunkPreservesMessageOrder() {
        val lines = (1..20).map { "msg$it: v" }
        val r = chunk(lines.joinToString("\n"), 50)
        val order = r.joinToString("\n").lines().filter { it.isNotBlank() }.map { it.substringBefore(':') }
        check(order == lines.map { it.substringBefore(':') }) { "分块拼接后消息顺序应保持, 实际 $order" }
    }
}
