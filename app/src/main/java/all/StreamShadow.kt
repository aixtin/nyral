package io.github.aixtin.nyral

/**
 * 净化器影子对比器(2026-11-19, 根治批次 B)。
 *
 * 批次 B 只做"接线 + 影子", 不改变用户可见行为:
 * - 正文仍按原文 cb.onDelta 上屏(与改造前逐字节一致, 出问题可立刻回退);
 * - 同一份正文并行喂给 [StreamSanitizer], 收集它的输出与判定, 只在收口时打一条日志。
 *
 * 这样可以在真机上用真实流量验证净化器"会不会误吞正常正文 / 能不能抓住真泄漏",
 * 而不是等批次 C 切到唯一出口后才发现回归。
 *
 * 纯 Kotlin, 不依赖 android.util.Log(日志经 [log] 回调注入), 可被 JVM 单测直接驱动。
 */
class StreamShadow(
    useTools: Boolean = true,
    private val log: (String) -> Unit = {}
) {
    /** 实际上屏的原文(与改造前的 cb.onDelta 序列逐字节一致) */
    private val raw = StringBuilder()

    /** 净化器认为该上屏的正文 */
    private val clean = StringBuilder()

    private var drops = 0
    private var needRetry = 0
    private var toolCalls = 0

    /** 有效轮(有正文 delta)/空轮(零正文)计数, 用于排除纯工具轮噪点 */
    private var activeRounds = 0
    private var emptyRounds = 0
    private val dropReasons = LinkedHashSet<String>()
    private var closed = false

    private val sanitizer = StreamSanitizer(useTools = useTools) { d: Delta ->
        when (d) {
            is Delta.Content -> clean.append(d.text)
            is Delta.Thinking -> Unit
            is Delta.ToolCall -> toolCalls++
            is Delta.LeakDropped -> {
                drops++
                if (dropReasons.size < 6) dropReasons.add(d.reason)
            }
            is Delta.NeedRetry -> {
                needRetry++
                if (dropReasons.size < 6) dropReasons.add("retry:" + d.reason)
            }
        }
    }

    /** 喂一段将要上屏的正文。仅在正文出口调用, 思考/提示语不入影子。 */
    fun feed(text: String) {
        if (closed || text.isEmpty()) return
        raw.append(text)
        sanitizer.feed(text)
    }

    /**
     * 收口结算。aborted=true 表示断链/异常收尾, 走 flushAbort。
     * 幂等: 重复调用只结算一次。
     */
    fun finish(aborted: Boolean, nativeToolCalls: Int = 0) {
        if (closed) return
        closed = true
        // 诊断(2026-11-19): 先落 ENTER, 保证 flush 内部抛异常时也能定位该轮原始长度(缺号轮排查)
        log("[shadow] round#${rounds + 1} ENTER raw=${raw.length} aborted=$aborted")
        if (aborted) sanitizer.flushAbort() else sanitizer.flushEof()
        val r = raw.toString()
        val c = clean.toString()
        totalRaw += r.length
        totalClean += c.length
        totalDrops += drops
        totalRetry += needRetry
        rounds++

        val rTrim = r.trimEnd()
        val cTrim = c.trimEnd()
        // 空正文轮(原生 function calling 只回 tool_calls 时 content 通道零 delta):
        // 净化器从未被喂过文本, CLEAN 无意义, 单独打 SKIP 且不计入分歧率分母。
        if (r.isEmpty() && drops == 0 && needRetry == 0) {
            emptyRounds++
            log("[shadow] round#$rounds SKIP raw=0 clean=0 nativeTools=$nativeToolCalls (零正文轮, 不计入分歧率)")
            return
        }
        activeRounds++
        // 影子结论: 净化器认为该上屏的内容与实际上屏内容是否一致
        if (rTrim == cTrim && drops == 0 && needRetry == 0) {
            log("[shadow] round#$rounds CLEAN raw=${r.length} clean=${c.length} toolCalls=$toolCalls nativeTools=$nativeToolCalls")
            return
        }
        val sb = StringBuilder()
        sb.append("[shadow] round#$rounds DIFF raw=").append(r.length)
            .append(" clean=").append(c.length)
            .append(" drops=").append(drops)
            .append(" retry=").append(needRetry)
            .append(" toolCalls=").append(toolCalls).append(" nativeTools=").append(nativeToolCalls)
        if (dropReasons.isNotEmpty()) sb.append(" reasons=").append(dropReasons.joinToString("|"))
        if (drops > 0 || needRetry > 0) {
            // 影子抓住了真泄漏: 说明当前上屏路径确实漏了, 批次 C 切出口后将被拦下
            sb.append(" verdict=LEAK_ON_SCREEN")
            sb.append(" sample=").append(quote(snippetAround(r, c)))
        } else if (cTrim.length < rTrim.length) {
            // 净化器吞掉了原文却不报泄漏 -> 疑似误吞, 需要看规则
            sb.append(" verdict=SUSPECT_OVER_DROP")
            sb.append(" at=").append(firstDiff(rTrim, cTrim))
            sb.append(" sample=").append(quote(snippetAt(rTrim, firstDiff(rTrim, cTrim))))
        } else {
            sb.append(" verdict=TEXT_DIFF")
            sb.append(" at=").append(firstDiff(rTrim, cTrim))
            sb.append(" sample=").append(quote(snippetAt(rTrim, firstDiff(rTrim, cTrim))))
        }
        log(sb.toString())
    }

    /** 本轮影子统计(CLEAN/DIFF 次数) */
    fun stats(): String = "round#$rounds(active=$activeRounds empty=$emptyRounds) raw=$totalRaw clean=$totalClean drops=$totalDrops retry=$totalRetry"

    private fun firstDiff(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun snippetAt(s: String, at: Int, span: Int = 48): String {
        if (s.isEmpty()) return ""
        val from = (at - 12).coerceAtLeast(0)
        val to = (from + span).coerceAtMost(s.length)
        return s.substring(from, to).replace("\n", "\\n")
    }

    /** 找原文里被净化器删掉的第一段, 作为泄漏样例 */
    private fun snippetAround(r: String, c: String, span: Int = 64): String {
        val at = firstDiff(r, c)
        return snippetAt(r, at, span)
    }

    private fun quote(s: String): String = "'" + s.replace("'", "’") + "'"

    companion object {
        /** 进程级累计, 便于一次会话结束后看总量(不改任何行为) */
        private var totalRaw = 0
        private var totalClean = 0
        private var totalDrops = 0
        private var totalRetry = 0
        private var rounds = 0

        fun totals(): String =
            "StreamShadow rounds=$rounds raw=$totalRaw clean=$totalClean drops=$totalDrops retry=$totalRetry"
    }
}
