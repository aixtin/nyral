package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 流式管线万轮随机测试（2026-11-07）。
 *
 * 打的就是三次真实事故背后的机制，全部为纯 JVM、不依赖真机与 Android API：
 *  1) 长工具参数生成期间 UI 完全静默（P2-1）-> PendingProgressGate
 *  2) 用户中断时已收完整的产物被一起丢弃（P2-2）-> CancelSalvagePolicy + ToolCallAccumulator 完整性语义
 *  3) 分片 arguments 跨 chunk 粘合错乱（会写出损坏文件）-> ToolCallAccumulator
 *
 * 纪律：只断言设计上必然成立的不变量，不做输出快照；轮数经 -Dnyral.fuzz.iters 透传，
 * 默认 2 万轮，nightly 深扫 20 万轮（见 .github/workflows/nightly.yml）。
 */
class StreamPipelineFuzzTest {

    private val iters: Int = System.getProperty("nyral.fuzz.iters")?.toIntOrNull() ?: 20000

    @org.junit.Before
    fun announce() {
        // 便于在 test-results/*.xml 的 system-out 里核对本轮实际扫了多少次
        println("StreamPipelineFuzzTest: iters=$iters")
    }

    private val names = listOf(
        "artifact_save", "artifact_save_bin", "workdir",
        "memory_search", "web_search", "sh_run", "app_click",
    )

    // ------------------------------------------------------------------ 1. 分片累积

    @Test
    fun toolCalls_fragmentedArgumentsReassembleLossless() {
        val rnd = Random(20261107L)
        repeat(iters) { round ->
            val n = rnd.nextInt(1, 5)
            // 稀疏且可能乱序下发的 index
            val idxs = LinkedHashSet<Int>()
            var cur = 0
            repeat(n) {
                idxs.add(cur)
                cur += rnd.nextInt(1, 3)
            }
            val plans = idxs.map { idx ->
                Plan(
                    index = idx,
                    name = if (rnd.nextInt(10) == 0) "" else names[rnd.nextInt(names.size)],
                    payload = randomJson(rnd),
                    withId = rnd.nextBoolean(),
                )
            }

            val acc = ToolCallAccumulator()
            for (plan in plans) {
                val pieces = splitRandom(rnd, plan.payload)
                pieces.forEachIndexed { i, piece ->
                    acc.onDelta(
                        plan.index,
                        if (i == 0 && plan.withId) "call_${plan.index}_x" else null,
                        if (i == 0) plan.name else null,
                        piece,
                    )
                }
                // 迟到的空分片 / 重复 name 不得抹掉已收内容
                if (rnd.nextInt(4) == 0) acc.onDelta(plan.index, null, plan.name, "")
            }

            val drained = acc.drain()
            val expect = plans.filter { it.name.isNotBlank() }.sortedBy { it.index }

            assertEquals("round=$round 有效调用数", expect.size, drained.size)
            assertEquals("round=$round 输出按 index 升序", expect.map { it.index }, drained.map { it.index })
            drained.forEachIndexed { i, call ->
                val plan = expect[i]
                val want = if (plan.payload.trim().isEmpty()) "{}" else plan.payload.trim()
                assertEquals("round=$round call#$i 参数必须零丢失", want, call.args)
                assertEquals("round=$round call#$i 工具名", plan.name, call.name)
                assertTrue("round=$round call#$i id 不得为空", call.id.isNotBlank())
                if (plan.withId) {
                    assertEquals("round=$round 显式 id 必须原样保留", "call_${plan.index}_x", call.id)
                } else {
                    assertEquals("round=$round id 兜底格式", "call_${call.name}_${call.index}", call.id)
                }
            }
            assertTrue("drain 后必须清空", acc.isEmpty())
            assertEquals("二次 drain 必须为空", 0, acc.drain().size)
        }
    }

    // ------------------------------------------------------------------ 2. 进度节流

    @Test
    fun progressGate_monotoneBoundedSparse() {
        val rnd = Random(980551L)
        val byteStep = 512
        val timeStep = 800L
        repeat(iters) {
            val gate = PendingProgressGate(byteStep, timeStep)
            var total = 0
            var now = rnd.nextLong(1000L, 10_000_000L)
            var lastReported = -1
            var lastAt = now
            var reports = 0

            repeat(120) loop@{
                val prevTotal = total
                total += rnd.nextInt(0, 700)
                now += rnd.nextInt(0, 400).toLong()
                val hit = gate.shouldReport(total, now)
                if (total <= 0) {
                    assertFalse("0 字节不得上报", hit)
                    return@loop
                }
                // 首个非空参数必须立刻上报，否则用户侧继续静默（P2-1 的原始症状）
                if (prevTotal == 0) assertTrue("首个非空参数必须立即上报", hit)
                if (!hit) return@loop
                // 上报值就是当前已收字节数，不允许回退
                assertTrue("上报值必须单调不减", total >= lastReported)
                if (reports > 0) {
                    val db = total - lastReported
                    val dt = now - lastAt
                    assertTrue("两次上报必须满足 512B 或 800ms 之一 db=$db dt=$dt", db >= byteStep || dt >= timeStep)
                }
                lastReported = total
                lastAt = now
                reports++
            }

            if (total > 0) assertTrue("有参数就必须至少上报一次", reports >= 1)
            // 参数不再增长: 允许补报一次落后量, 之后必须彻底静默
            val frozen = total
            gate.shouldReport(frozen, now + 100_000L)
            assertFalse("参数冻结后不得反复上报", gate.shouldReport(frozen, now + 200_000L))
            assertFalse("参数冻结后再久也不得上报", gate.shouldReport(frozen, now + 900_000L))
        }
    }

    /** 单轮的一条工具调用计划 */
    private data class Plan(val index: Int, val name: String, val payload: String, val withId: Boolean)

    // ------------------------------------------------------------------ 3. 补偿白名单安全

    @Test
    fun cancelSalvage_rejectsTruncatedAndUnknown() {
        val rnd = Random(77019L)
        repeat(iters) {
            val name = listOf("artifact_save", "artifact_save_bin", "workdir")[rnd.nextInt(3)]
            val full = fullArgsOf(name, rnd)
            assertTrue("完整参数必须可补偿: $name $full", CancelSalvagePolicy.isEligible(name, full))

            // 任意截断都必须拒绝（半截 JSON 不得落盘）
            val cut = rnd.nextInt(1, maxOf(2, full.length))
            val truncated = full.substring(0, full.length - cut)
            assertFalse("截断 $cut 字符后不得可补偿: $truncated", CancelSalvagePolicy.isEligible(name, truncated))

            // 非白名单工具一律不补偿
            val other = names.first { it !in setOf("artifact_save", "artifact_save_bin", "workdir") }
            assertFalse("非白名单工具不得补偿: $other", CancelSalvagePolicy.isEligible(other, full))
            assertFalse("空参数不得补偿", CancelSalvagePolicy.isEligible(name, ""))
            assertFalse("垃圾参数不得补偿", CancelSalvagePolicy.isEligible(name, "{oops"))
        }
    }

    // ------------------------------------------------------------------ 4. 取消时刻组合模拟

    @Test
    fun cancelSimulation_neverWritesPartialAndNeverLosesComplete() {
        val rnd = Random(31415926L)
        repeat(iters) { round ->
            val name = listOf("artifact_save", "artifact_save_bin")[rnd.nextInt(2)]
            val full = fullArgsOf(name, rnd)
            val pieces = splitRandom(rnd, full)
            val acc = ToolCallAccumulator()

            // 用户在收到第 k 片时按下停止（k 属于 0..pieces.size）
            val k = rnd.nextInt(0, pieces.size + 1)
            pieces.take(k).forEach { acc.onDelta(0, "call_0", name, it) }

            val argsNow = acc.argsOf(0)
            val salvage = CancelSalvagePolicy.isEligible(name, argsNow)
            // 以"字符是否全部到齐"判定，而非片数：随机切片里允许空片，空片不影响完整性
            val complete = argsNow.trim() == full.trim()

            if (k == pieces.size) {
                assertTrue("round=$round 全部片收完时参数必然完整", complete)
            }
            if (complete) {
                assertTrue("round=$round 参数已完整时必须补偿(否则用户等几十秒的产物白丢)", salvage)
                assertEquals("round=$round 补偿参数必须是完整参数", full.trim(), acc.drain().single().args)
            } else {
                assertFalse("round=$round 参数不完整($k/${pieces.size})时绝不能执行, 否则写出损坏文件", salvage)
            }
        }
    }

    // ------------------------------------------------------------------ 工具方法

    /** 随机文本：含中文、引号、反斜杠、换行、emoji 代理对，专挑转义边界 */
    private fun randomText(rnd: Random): String {
        val pool = listOf(
            "产物", "报告", "a", "Z", " ", "\n", "\t", "\"", "\\", "中文字符测试",
            "\uD83D\uDE00", "{", "}", "[", "]", ",", ":", "null", "true", "512",
        )
        val sb = StringBuilder()
        repeat(rnd.nextInt(0, 8)) { sb.append(pool[rnd.nextInt(pool.size)]) }
        return sb.toString()
    }

    /** 生成一段可能为空的 JSON 参数（随机键值、随机类型、随机嵌套转义） */
    private fun randomJson(rnd: Random): String {
        if (rnd.nextInt(14) == 0) return ""                       // 空参数：应归一为 {}
        if (rnd.nextInt(14) == 0) return "   "                    // 纯空白同理
        val keys = listOf("name", "content", "action", "data", "path", "k")
        val sb = StringBuilder("{")
        repeat(rnd.nextInt(1, 5)) { i ->
            if (i > 0) sb.append(',')
            val key = keys[rnd.nextInt(keys.size)] + i
            sb.append('"').append(key).append("\":")
            when (rnd.nextInt(5)) {
                0, 1 -> sb.append(org.json.JSONObject.quote(randomText(rnd)))
                2 -> sb.append(rnd.nextInt(0, 1_000_000))
                3 -> sb.append(rnd.nextBoolean())
                else -> sb.append("null")
            }
        }
        sb.append('}')
        return sb.toString()
    }

    /** 随机切片：片数 1..12，允许空片，拼接结果与原串完全相同 */
    private fun splitRandom(rnd: Random, s: String): List<String> {
        if (s.isEmpty()) return listOf("")
        val parts = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val len = rnd.nextInt(1, minOf(6, s.length - i) + 1)
            parts.add(s.substring(i, i + len))
            i += len
            if (rnd.nextInt(10) == 0) parts.add("")
        }
        if (parts.isEmpty()) parts.add(s)
        return parts
    }

    /** 构造一个语义合法（可补偿）的白名单参数；content/data 必须非空，否则策略本就该拒绝 */
    private fun fullArgsOf(name: String, rnd: Random): String {
        val body = randomText(rnd).ifBlank { "x" }
        return when (name) {
            "artifact_save" -> """{"name":"r${rnd.nextInt(999)}.md","content":"${escape(body)}${rnd.nextInt(999)}"}"""
            "artifact_save_bin" -> """{"name":"b${rnd.nextInt(999)}.png","data":"iVBORw0KGgo${rnd.nextInt(999)}${escape(body)}"}"""
            else -> """{"action":"write","name":"w${rnd.nextInt(999)}.txt","content":"${escape(body)}"}"""
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
}
