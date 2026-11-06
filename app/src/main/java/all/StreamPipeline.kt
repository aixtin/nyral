package io.github.aixtin.nyral

import org.json.JSONObject

/**
 * 流式管线纯逻辑组件（2026-11-07 抽取）。
 *
 * 抽取动机：流式这条链上的三次真实事故（切后台正文消失、长工具参数期间 UI 全盲、
 * 中断时已收完整的产物被丢弃）都发生在 LocalEngine.streamOnce / 取消路径里，而这段代码
 * 原本与 HttpURLConnection、Log、android.util 缠在一起，只能靠真机偶遇验证。
 * 抽出无 Android 依赖的组件后，JVM 随机测试可以跑上万轮把这条链扫一遍，
 * 且 LocalEngine 与测试共用同一份实现（不是对照副本），测的就是线上代码。
 *
 * 三个组件：
 *  - ToolCallAccumulator：原生 tool_calls 增量累积（分片粘合/顺序/id 缺省/空参归一）
 *  - PendingProgressGate：参数生成进度上报节流（字节阈值 + 时间窗，保证单调与稀疏）
 *  - CancelSalvagePolicy：中断时哪些调用允许补偿执行（参数必须完整 + 语义幂等）
 */

/**
 * 原生 tool_calls 增量累积器。
 * 语义与 LocalEngine 原 HashMap<Int, NativeCallAcc> 实现逐条对齐：
 *  - 同 index 的分片按到达顺序拼接 arguments（跨 chunk 续传）
 *  - name 只在非空白时覆盖（防后到空片把名字抹掉）
 *  - id 只要出现就覆盖（最终为空才用 call_<name>_<index> 兜底）
 *  - 转出时按 index 升序，跳过无 name 的残片，空参数归一为 "{}"
 */
class ToolCallAccumulator {
    private class Acc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private val map = HashMap<Int, Acc>()

    fun isEmpty(): Boolean = map.isEmpty()

    fun isNotEmpty(): Boolean = map.isNotEmpty()

    fun size(): Int = map.size

    /** 参数已累积的字符数（用于进度上报与"完整性"判断） */
    fun totalArgBytes(): Int {
        var n = 0
        for (a in map.values) n += a.args.length
        return n
    }

    /** 第一个已知工具名（进度提示用；未知名返回空串） */
    fun firstNonBlankName(): String {
        for (a in map.values) if (a.name.isNotBlank()) return a.name
        return ""
    }

    /** 单条调用的中间快照（测试与断点续用；不消费状态） */
    fun argsOf(index: Int): String = map[index]?.args?.toString() ?: ""

    fun nameOf(index: Int): String = map[index]?.name ?: ""

    /** 吃一个 SSE delta 分片；字段为 null 表示该分片未携带此字段 */
    fun onDelta(index: Int, id: String?, name: String?, argChunk: String?) {
        val acc = map.getOrPut(index) { Acc() }
        if (id != null) acc.id = id
        if (name != null && name.isNotBlank()) acc.name = name
        if (!argChunk.isNullOrEmpty()) acc.args.append(argChunk)
    }

    /** 一条已完成的工具调用 */
    data class Call(val index: Int, val id: String, val name: String, val args: String)

    /** 丢弃全部累积状态（流未完整结束时用，防止半截参数被当成完整参数执行） */
    fun clear() {
        map.clear()
    }

    /** 流结束转出全部调用并清空状态（空参归一 {}，id 缺省补造，按 index 升序） */
    fun drain(): List<Call> {
        val idxs = map.keys.sorted()
        val out = ArrayList<Call>(idxs.size)
        for (idx in idxs) {
            val acc = map[idx] ?: continue
            val name = acc.name.trim()
            if (name.isEmpty()) continue
            val args = acc.args.toString().trim()
            out.add(Call(idx, acc.id.ifEmpty { "call_${name}_$idx" }, name, if (args.isEmpty()) "{}" else args))
        }
        map.clear()
        return out
    }
}

/**
 * 参数生成进度节流器：模型把长文整段塞进写操作参数时，UI 不能每秒被刷几百次，
 * 也不能一直静默。规则：首报立即；此后"字节增量达阈值"或"距上次达时间窗"才再报。
 * 不变量（由 StreamPipelineFuzzTest 保证）：上报值单调不减、不超过当前总量、间隔满足阈值。
 */
class PendingProgressGate(
    private val byteStep: Int = 512,
    private val timeStepMs: Long = 800L,
) {
    private var lastBytes: Int = 0
    private var lastAt: Long = 0L
    private var everReported: Boolean = false

    /** 是否应当上报；返回 true 时内部状态推进 */
    fun shouldReport(totalBytes: Int, nowMs: Long): Boolean {
        if (totalBytes <= 0) return false
        if (!everReported) {                                          // 首报必须立即：不依赖机器时钟绝对值
            everReported = true
            lastBytes = totalBytes
            lastAt = nowMs
            return true
        }
        if (totalBytes <= lastBytes) return false                    // 参数不会回退：无新增长不上报
        val byteHit = totalBytes - lastBytes >= byteStep
        val timeHit = nowMs - lastAt >= timeStepMs
        if (!byteHit && !timeHit) return false
        lastBytes = totalBytes
        lastAt = nowMs
        return true
    }

    /** 本轮结束复位（同一 LocalEngine 实例跨轮复用时不串味） */
    fun reset() {
        lastBytes = 0
        lastAt = 0L
        everReported = false
    }
}

/**
 * 中断补偿策略：用户按停止/断线时，只有"参数已完整且语义幂等"的调用允许先执行再终止。
 * 这是安全策略而非体验优化——放行半截参数会写出损坏文件，放宽非幂等工具会重放副作用。
 */
object CancelSalvagePolicy {
    /** 纯产物写入类：同名覆盖/版本递增，重放无外部副作用 */
    private val WHITELIST = setOf("artifact_save", "artifact_save_bin", "workdir")

    fun isWhitelisted(name: String): Boolean = name in WHITELIST

    /**
     * 可补偿判定：白名单 + 参数能完整解析为 JSON + 关键字段非空。
     * 任何被截断的 JSON 都解析失败，因此"半截参数"天然进不来。
     */
    fun isEligible(name: String, arg: String): Boolean {
        if (name !in WHITELIST) return false
        val jo = try {
            JSONObject(arg.trim())
        } catch (e: Throwable) {
            return false
        }
        return when (name) {
            "artifact_save" -> jo.optString("name").isNotBlank() && jo.optString("content").isNotBlank()
            "artifact_save_bin" -> jo.optString("name").isNotBlank() && jo.optString("data").isNotBlank()
            else -> jo.optString("action") == "write" &&
                jo.optString("name").isNotBlank() && jo.optString("content").isNotBlank()
        }
    }
}
