@file:Suppress("InvalidPackageDeclaration") // all/ 目录沿用 io.github.aixtin.nyral 包名，与同目录既有文件一致

package io.github.aixtin.nyral

import org.json.JSONObject

/**
 * 流式正文唯一出口(2026-11-19, 根治批次 A)。
 *
 * 设计动机见 docs/Nyral_工具标签泄漏根治_改造清单.md：
 * 旧链路里泄漏拦截散落在 streamOnce 的十余处 `cb.onDelta`、四个 tail 收口分支、
 * 三套互不感知的缓冲(xmlBuf/xmlLeakBuf/dsmlBuf)与三套判据不一致的净化规则里，
 * 每遇到一种新的泄漏形态就得再补一处补丁 —— 规则有限、变体无限, 必然漏。
 *
 * 本类把"正文该不该上屏"收敛为一件事: **字符级状态机 + 滞后窗口**。
 *  - 所有 Content/Thinking/ToolCall/LeakDropped 只经 emit 出口, 别无第二条路
 *  - `<tool_` 这类半截前缀必须扣住 LAG_WINDOW 字符等后续 chunk 确认, 再决定放行还是吞掉
 *  - 代码围栏内同形文本原样保留(不误伤示例代码)
 *  - 断链/EOF 残余走 flushAbort/flushEof 统一裁决, 不再有"直接上屏"的后门
 *
 * 纯 Kotlin, 不依赖 android.*, 可被 JVM 单测与 fuzz 直接驱动(参照 StreamPipeline.kt 的做法)。
 */
sealed interface Delta {
    /** 可上屏正文 */
    data class Content(val text: String) : Delta

    /** 思考区增量(仅转发, 不做标签判定) */
    data class Thinking(val text: String) : Delta

    /** 从泄漏块里解析出的工具调用(只走结构化通道, 永不进正文) */
    data class ToolCall(val name: String, val args: String) : Delta

    /**
     * 泄漏块被吞掉(未上屏)。
     * @param retryable 整块看完了但解析不出调用 —— 上层若本轮无任何正文, 应重试而非给空气泡
     */
    data class LeakDropped(val reason: String, val raw: String, val retryable: Boolean) : Delta

    /** 流被截断导致块不完整, 无法判定: 建议整轮重试 */
    data class NeedRetry(val reason: String) : Delta
}

/**
 * 正交净化器。
 *
 * 用法:
 * ```
 * val s = StreamSanitizer(useTools = true) { d -> when (d) { ... } }
 * s.feed(chunk)      // 反复喂
 * s.flushEof()       // 正常收尾
 * s.flushAbort()     // 断链收口
 * ```
 * 三个方法都幂等, 收尾后可继续 feed(续跑场景)。
 */
class StreamSanitizer(
    private val useTools: Boolean = true,
    private val emit: (Delta) -> Unit
) {
    /**
     * 批次 C(2026-11-19): 本轮实际下发的工具名集合(构造后设置)。
     * 旧实现靠 LocalEngine.isXmlLeakRow 的「白名单制」(不在正文标签里就吞) 兜未知壳,
     * 收敛到净化器后必须补上等价能力: 命中本轮工具名的标签(<web_search>{..}</web_search>)同样按壳吞。
     * 这里只认工具名, 不认"任意未知标签", 避免把正文里的自定义 HTML 吞掉。
     */
    var extraShell: Set<String> = emptySet()
        set(value) {
            field = value
            extraShellSet = value.mapTo(HashSet()) { it.lowercase() }
        }

    private var extraShellSet: Set<String> = emptySet()


    companion object {
        /** 滞后窗口: 未判定的 '<' 前缀最多扣这么久, 超过即当正文放行 */
        const val LAG_WINDOW = 64

        /** 单个泄漏块上限, 超过强制结算(防模型吐垃圾把内存撑爆) */
        const val MAX_BLOCK = 8192

        /** 已知工具壳标签(出现即必须是"块", 不允许上屏) */
        private val SHELL_TAGS = setOf(
            "tool_call", "tool_calls", "tool_name", "tool_use", "tool_result", "tool_detail",
            "function_call", "function_calls", "function", "invoke", "antml", "memory_search",
            "arg_key", "arg_value", "parameter", "parameters", "dsml", "tool"
        )

        /** 通用壳标签: 只是容器, 名字里没有工具信息 */
        private val GENERIC_SHELL = setOf(
            "tool_call", "tool_calls", "tool_name", "tool_use", "tool_result", "tool_detail",
            "function_call", "function_calls", "function", "invoke", "antml",
            "arg_key", "arg_value", "parameter", "parameters", "dsml", "tool"
        )

        /** 正文里合法的 HTML 标签(与 LocalEngine.xmlBodyTags 同口径) */
        private val BODY_TAGS = setOf(
            "br", "b", "i", "u", "s", "em", "strong", "code", "pre", "a", "img",
            "p", "ul", "ol", "li", "hr", "blockquote", "span", "div", "del", "sup", "sub",
            "small", "font", "details", "summary", "table", "thead", "tbody", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6"
        )

        /**
         * 闭合壳标签: 命中即认为一个泄漏块自然结束。
         * 只收**容器型**标签 —— tool_name/arg_key/arg_value 是块内子标签, 拿它们当结束信号
         * 会把 `<tool_call><tool_name>x</tool_name>` 提前截断, 参数那半截被当成新块丢掉。
         */
        private val CLOSE_SHELL_RE = Regex(
            "</\\s*(?:tool_call|tool_calls|tool_use|tool_result|tool_detail|function_call|function_calls|invoke|memory_search|tool)\\s*>",
            RegexOption.IGNORE_CASE
        )

        /** 块内所有开标签是否都有闭标签(用于判定"块完整") */
        private val OPEN_TAG_RE = Regex("<([a-zA-Z][a-zA-Z0-9_:\\-]*)(?:\\s[^>]*)?>")
        private val SELF_CLOSE_TAG_RE = Regex("<([a-zA-Z][a-zA-Z0-9_:\\-]*)(?:\\s[^>]*)?/>")

        /** <tool_name>x</tool_name> */
        private val TOOL_NAME_TAG_RE = Regex(
            "<\\s*tool_name\\s*>(.*?)<\\s*/\\s*tool_name\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        /** <invoke name="x"> / <antml:invoke name="x"> */
        private val INVOKE_NAME_RE = Regex(
            "<\\s*(?:antml:)?invoke\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        )

        /** 方括号壳的闭合形态 */
        private val BRACKET_CLOSE = listOf(
            "[/tool_call]", "[/tool_calls]", "[/invoke]", "[/tool_use]", "[/function_call]", "[/antml]"
        )

        /** 判定硬上限: 超过它就按正文放行, 防止孤立开括号卡住整条流 */
        private const val LAG_HARD = 96

        private val ARG_KEY_RE = Regex("<\\s*arg_key\\s*>(.*?)<\\s*/\\s*arg_key\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ARG_VALUE_RE = Regex("<\\s*arg_value\\s*>(.*?)<\\s*/\\s*arg_value\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        /** DSML <｜｜DSML｜｜ parameter name="x" ...>v</...> */
        private val DSML_PARAM_RE = Regex(
            "<[^<>]*?parameter\\b[^>]*?\\bname\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)<\\s*/[^<>]*?parameter\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        /** 文本协议前缀(思考/答案/工具), 供上层在 feed 前剥离; 这里只提供判定 */
        fun isProtocolPrefix(line: String): Boolean {
            val t = line.trimStart()
            return t.startsWith("思考:") || t.startsWith("思考：") ||
                t.startsWith("答案:") || t.startsWith("答案：") ||
                t.startsWith("TOOL:") || t.startsWith("TOOL：")
        }

        /** 规则同源的纯文本净化: UI 兜底 / 落库 / 差值补写共用同一份实现 */
        fun scrubText(s: String): String {
            if (s.isEmpty() || !s.contains('<')) return s
            val out = ArrayList<Delta>(8)
            val sc = StreamSanitizer(true) { out.add(it) }
            sc.feed(s)
            sc.flushEof()
            val sb = StringBuilder(s.length)
            for (d in out) {
                if (d is Delta.Content) sb.append(d.text)
            }
            // 不回退到原文: 整段都是泄漏块时正确结果就是空串, 回退等于把脏数据原样放回屏幕
            return sb.toString()
        }
    }

    // ================= 状态 =================
    /** 待处理输入 */
    private val buf = StringBuilder()
    /** 本轮已确定可上屏的累积(减少 emit 次数) */
    private val out = StringBuilder()
    /** 当前泄漏块内容 */
    private val blockBuf = StringBuilder()
    /** 是否处于 ``` 围栏内 */
    private var inFence = false
    /** 是否正在吞一个泄漏块 */
    private var blockActive = false
    /** 吞块后紧跟的一个换行要一并吃掉(避免留下空行) */
    private var suppressOneNewline = false
    /** 收尾是否已执行过(防止重复收口) */
    private var closed = false

    // ================= 公开入口 =================

    fun feed(chunk: CharSequence) {
        if (chunk.isEmpty()) return
        closed = false
        buf.append(chunk)
        pump(final = false)
    }

    /** 正常收尾: 滞后窗口剩余内容统一裁决; 半截块 → NeedRetry */
    fun flushEof() {
        if (closed) return
        pump(final = true)
        if (blockActive) finishBlock(forced = true)
        flushOut()
        closed = true
    }

    /**
     * 断链收口: 残余只判不吐。
     * 与 flushEof 的差别: 断链是网络问题不是模型格式问题, 半截块给 LeakDropped 而非 NeedRetry
     * (重试由上层续跑逻辑决定, 不在这里再叠一层)。
     */
    fun flushAbort() {
        if (closed) return
        pump(final = true)
        if (blockActive) finishBlock(forced = true, abort = true)
        flushOut()
        closed = true
    }

    // ================= 主循环 =================

    private fun pump(final: Boolean) {
        while (true) {
            if (suppressOneNewline && buf.isNotEmpty()) {
                if (buf[0] == '\n') buf.deleteCharAt(0)
                suppressOneNewline = false
            }
            if (blockActive) {
                pumpBlock(final)
                if (blockActive) return
                continue
            }
            if (inFence) {
                val close = buf.indexOf("```")
                if (close >= 0) {
                    out.append(buf, 0, close + 3)
                    buf.delete(0, close + 3)
                    inFence = false
                    continue
                }
                if (final) {
                    out.append(buf); buf.setLength(0)
                    return
                }
                val keep = trailingBackticks()
                if (buf.length > keep) {
                    out.append(buf, 0, buf.length - keep)
                    buf.delete(0, buf.length - keep)
                }
                return
            }

            // 批次 C: 方括号壳([tool_call])不带 '<', 只找 '<' 会把它整段当正文放行,
            // 必须把 '[' 也作为候选切点, 由 classify 决定吞还是放行。
            val ltRaw = buf.indexOf("<")
            val brRaw = buf.indexOf("[")
            val lt = when {
                ltRaw < 0 -> brRaw
                brRaw < 0 -> ltRaw
                else -> minOf(ltRaw, brRaw)
            }
            val fence = buf.indexOf("```")
            if (fence >= 0 && (lt < 0 || fence < lt)) {
                val atLineStart = fence == 0 || buf[fence - 1] == '\n'
                out.append(buf, 0, fence + 3)
                buf.delete(0, fence + 3)
                if (atLineStart) inFence = true
                continue
            }
            if (lt < 0) {
                if (final) {
                    out.append(buf); buf.setLength(0)
                    return
                }
                val keep = trailingBackticks()
                if (buf.length > keep) {
                    out.append(buf, 0, buf.length - keep)
                    buf.delete(0, buf.length - keep)
                }
                return
            }
            // '<' 之前的都是干净正文
            if (lt > 0) {
                out.append(buf, 0, lt)
                buf.delete(0, lt)
            }
            when (classify(final)) {
                Cls.NEED_MORE -> {
                    // 硬滞后上限: 判定不出又囤太久(正文里塞了奇怪的开括号), 按正文放行,
                    // 否则整条流会被一个孤立的 '[' 卡住
                    if (buf.length > LAG_HARD) { out.append(buf[0]); buf.deleteCharAt(0); continue }
                    return
                }
                Cls.PLAIN -> { out.append(buf[0]); buf.deleteCharAt(0) }
                Cls.SHELL -> startBlock()
            }
        }
    }

    private fun pumpBlock(final: Boolean) {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            blockBuf.append(c)
            i++
            if (c == '>' || c == '}' || c == '\n') {
                if (blockLooksComplete(blockBuf)) {
                    buf.delete(0, i)
                    finishBlock(forced = false)
                    return
                }
            }
            if (blockBuf.length >= MAX_BLOCK) {
                buf.delete(0, i)
                finishBlock(forced = true)
                return
            }
        }
        buf.setLength(0)
        if (final) finishBlock(forced = true)
    }

    // ================= 判定 =================

    private enum class Cls { NEED_MORE, PLAIN, SHELL }

    /**
     * 判定 buf 开头的 '<' 是什么。
     * 只有"确定是已知工具壳"才吞; 判定不出的一律按正文放行 —— 流的场景里误吞正文的代价
     * 远高于漏一个标签, 而漏掉的已知壳会在滞后窗口耗尽前被后面的 chunk 补上证据。
     */
    private fun classify(final: Boolean): Cls {
        if (buf.length < 2) return if (final) Cls.PLAIN else Cls.NEED_MORE
        // DSML 家族: 开闭都要认(<｜｜DSML｜｜… 与 </｜｜DSML｜｜…), 且闭合标签的第 2 字符是 '/',
        // 必须先于普通标签分支判定, 否则 </｜｜DSML｜｜invoke> 会被当成未知标签放行上屏。
        val dsmlArmed = buf[0] == '<' && (
            buf[1] == '｜' || buf[1] == '|' ||
                (buf[1] == '/' && buf.length >= 3 && (buf[2] == '｜' || buf[2] == '|'))
            )
        if (dsmlArmed) {
            val head = buf.substring(0, minOf(buf.length, 16)).replace('｜', '|')
            if (head.startsWith("<||DSML") || head.startsWith("</||DSML")) return Cls.SHELL
            if (head.length < 9 && !final) return Cls.NEED_MORE
            return Cls.PLAIN
        }
        val c1 = buf[1]
        // 方括号壳([tool_call] ... [/tool_call]): deepseek 系偶发用 Markdown 风格包裹工具调用,
        // 旧的尖括号状态机对它完全无感(实测 round#7 直接上屏)。这里只认"方括号内就是壳名",
        // 不做"未知方括号一律吞", 避免误吞正文里的 [1] 这类引用标记。
        if (buf[0] == '[') {
            val close = buf.indexOf(']')
            if (close < 0) {
                // 真机实测: 工具调用解析成功后引擎会提前收流, 残余的 "[/tool_call]" 只到了 "[/",
                // 收尾时 final=true 把它当正文吐了两个字符上屏。只要已积累的内容是已知壳名的
                // 前缀, 就必须按壳吞掉, 不能因为它没写完就当正文。
                val partial = buf.substring(1).trim().lowercase().removePrefix("/")
                if (isShellPrefix(partial)) return Cls.SHELL
                return if (final || buf.length > LAG_HARD) Cls.PLAIN else Cls.NEED_MORE
            }
            val nm = buf.substring(1, close).trim().lowercase().removePrefix("/")
            return if (isShell(nm)) Cls.SHELL else Cls.PLAIN
        }
        if (c1 == '/') {
            val name = readTagName(2)
                ?: return if (!final) Cls.NEED_MORE
                else if (isShellPrefix(buf.substring(2).lowercase())) Cls.SHELL else Cls.PLAIN
            return if (isShell(name)) Cls.SHELL else Cls.PLAIN
        }
        if (!c1.isLetter()) return Cls.PLAIN
        val name = readTagName(1)
            ?: return if (!final) Cls.NEED_MORE
            else if (isShellPrefix(buf.substring(1).lowercase())) Cls.SHELL else Cls.PLAIN
        return if (isShell(name)) Cls.SHELL else Cls.PLAIN
    }

    /** 输入耗尽时, 已积累的部分是否某个已知壳名的前缀(含空串=只有一个起始括号) */
    private fun isShellPrefix(p: String): Boolean {
        if (p.isEmpty()) return true
        if (SHELL_TAGS.any { it.startsWith(p) }) return true
        return extraShellSet.isNotEmpty() && extraShellSet.any { it.startsWith(p) }
    }

    /** 是否按泄漏壳处理: 已知壳标签 ∪ 本轮工具名 */
    private fun isShell(name: String): Boolean =
        name in SHELL_TAGS || (extraShellSet.isNotEmpty() && name in extraShellSet)

    /**
     * 读标签名; 名字还没结束(没有空格 / '/' / '>')且长度未超过 24 时返回 null 表示"证据不足"。
     * 24 是标签名的实际上限, 超过还没结束说明根本不是标签, 交给调用方按正文处理。
     */
    private fun readTagName(from: Int): String? {
        val sb = StringBuilder()
        var i = from
        while (i < buf.length) {
            val c = buf[i]
            if (c == ' ' || c == '\t' || c == '/' || c == '>' || c == '\n' || c == '\r') {
                return if (sb.isEmpty()) "" else sb.toString().lowercase()
            }
            if (c == '<') return if (sb.isEmpty()) "" else null
            sb.append(c)
            if (sb.length > 24) return null
            i++
        }
        // 输入耗尽: 名字可能还没收完, 交回调用方决定(未 final 则等更多 chunk)
        return null
    }

    /** 泄漏块是否已"自然完整"(可以结算) */
    private fun blockLooksComplete(s: CharSequence): Boolean {
        val str = s.toString()
        if (CLOSE_SHELL_RE.containsMatchIn(str)) return true
        if (str.contains('[')) {
            for (t in BRACKET_CLOSE) if (str.contains(t, ignoreCase = true)) return true
        }
        if (str.contains("DSML", ignoreCase = true)) {
            // DSML: 优先认最外层 tool_calls 闭合, 没有再退到 invoke 闭合(部分模型不给外层)
            if (Regex("<\\s*/[^<>]{0,24}tool_calls\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(str)) return true
            if (Regex("<\\s*/[^<>]{0,24}invoke\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(str)) return true
            return false
        }
        // 无闭合壳: 退化为"所有开标签都成对 + 出现换行/JSON 平衡"
        val opens = OPEN_TAG_RE.findAll(str).map { it.groupValues[1].lowercase() }
            .filter { it !in BODY_TAGS }
            .toList()
        if (opens.isNotEmpty()) {
            val selfClosed = SELF_CLOSE_TAG_RE.findAll(str).map { it.groupValues[1].lowercase() }.toSet()
            val allClosed = opens.all { tag ->
                tag in selfClosed || Regex("<\\s*/\\s*${Regex.escape(tag)}\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(str)
            }
            if (allClosed && (str.contains('\n') || str.contains('}') || str.contains('>'))) return true
        }
        // JSON 平衡兜底: 第一个 '{' 已闭合, 且它就是这个块的全部内容(后面只剩空白/换行)。
        // 后面紧跟 </tool_call> 时**不算**完整, 否则闭合标签会被漏到正文, 等于把老 bug 从
        // "漏标签"改造成"漏闭合标签", 仍然是泄漏。
        val json = extractBalancedJson(str)
        if (json != null) {
            val after = str.substring(str.indexOf(json) + json.length)
            // 紧随其后的若还是闭合壳([/tool_call] / </tool_call>), 说明块没结束, 不能结算,
            // 否则闭合标签会被漏到正文
            val at = after.trimStart()
            val trailingClose = at.startsWith("[/") || at.startsWith("</")
            if (!trailingClose && (after.isBlank() || after.startsWith("\n"))) return true
        }
        return false
    }

    private fun startBlock() {
        blockActive = true
        blockBuf.setLength(0)
        flushOut()
    }

    /**
     * 结算当前块。
     * @param forced 是否因 EOF/断链/超长而强制结算(此时块可能不完整)
     * @param abort  断链收口(不产生 NeedRetry)
     */
    private fun finishBlock(forced: Boolean, abort: Boolean = false) {
        val raw = blockBuf.toString()
        blockActive = false
        blockBuf.setLength(0)
        suppressOneNewline = true
        // 批次 C(2026-11-19): 只有"自然闭合"的块才允许产出 ToolCall。
        // 之前 XML 缓冲分流时, 半截块靠 xmlTagsClosed 判定不完整才被丢弃; 收敛到净化器后
        // 若仍对 forced(EOF/断链/超长)块直接 parseBlock, 半截 <invoke name="sh_run"><parameter ...>id;uname -a
        // 会被解析成 sh_run|{} 真去执行(参数全丢), 实测弹出工具确认框 —— 这是比泄漏更严重的问题。
        val complete = blockLooksComplete(raw)
        val parsed = if (complete) parseBlock(raw) else null
        when {
            parsed != null -> emit(Delta.ToolCall(parsed.first, parsed.second))
            // 只吞到一个壳前缀(如收流后被掐断的 "[/"): 既没形成标签也没形成 JSON, 属残渣, 不必重试
            !raw.contains('>') && !raw.contains(']') && !raw.contains('{') -> Unit
            abort -> emit(Delta.LeakDropped("abort-residue", raw.take(256), false))
            forced -> emit(Delta.NeedRetry("leak-block-truncated"))
            else -> emit(Delta.LeakDropped("unparsable", raw.take(256), true))
        }
        flushOut()
    }

    private fun flushOut() {
        if (out.isEmpty()) return
        val text = out.toString()
        out.setLength(0)
        emit(Delta.Content(text))
    }

    private fun trailingBackticks(): Int {
        var n = 0
        var i = buf.length - 1
        while (i >= 0 && buf[i] == '`' && n < 3) { n++; i-- }
        return n
    }

    // ================= 泄漏块解析(离线职责, 不参与上屏判定) =================

    /** 返回 (工具名, JSON 参数) 或 null; 解析不出名字视为失败 */
    private fun parseBlock(raw: String): Pair<String, String>? {
        var name = ""
        var args: String? = null
        val s = raw.trim()

        TOOL_NAME_TAG_RE.find(s)?.let { name = it.groupValues[1].trim() }
        if (name.isEmpty()) {
            INVOKE_NAME_RE.find(s)?.let { name = it.groupValues[1].trim() }
        }

        val json = extractBalancedJson(s)
        if (json != null) {
            runCatching {
                val o = JSONObject(json)
                if (name.isEmpty()) {
                    name = o.optString("name", "").ifBlank { o.optString("tool", "") }
                }
                args = when {
                    o.has("arguments") -> o.get("arguments").toString()
                    o.has("args") -> o.get("args").toString()
                    o.has("name") && o.length() > 1 -> {
                        val copy = JSONObject(json)
                        copy.remove("name")
                        copy.toString()
                    }
                    else -> json
                }
            }
        }

        if (args == null) {
            val keys = ARG_KEY_RE.findAll(s).map { it.groupValues[1].trim() }.toList()
            val vals = ARG_VALUE_RE.findAll(s).map { it.groupValues[1].trim() }.toList()
            if (keys.isNotEmpty()) {
                val o = JSONObject()
                for (i in 0 until minOf(keys.size, vals.size)) {
                    if (keys[i].isEmpty()) continue
                    o.put(keys[i], coerce(vals[i]))
                }
                args = o.toString()
            }
        }

        if (args == null && s.contains("parameter", ignoreCase = true)) {
            val pairs = DSML_PARAM_RE.findAll(s).toList()
            if (pairs.isNotEmpty()) {
                val o = JSONObject()
                for (m in pairs) o.put(m.groupValues[1].trim(), coerce(m.groupValues[2].trim()))
                args = o.toString()
                if (name.isEmpty()) {
                    Regex("invoke\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .find(s)?.let { name = it.groupValues[1].trim() }
                }
            }
        }

        // 最后兜底: 标签名本身可能就是工具名(<memory_search>{...}</memory_search> 这类)
        if (name.isBlank()) {
            OPEN_TAG_RE.findAll(s)
                .map { it.groupValues[1].lowercase() }
                .firstOrNull { it !in GENERIC_SHELL && it !in BODY_TAGS }
                ?.let { name = it }
        }

        if (name.isBlank()) return null
        return name.trim() to (args ?: "{}")
    }

    /** 标量按 JSON 语义归一, 对象/数组保持字符串 */
    private fun coerce(v: String): Any {
        // 批次 C(2026-11-19): 裸文本直接原样返回。
        // 之前无脑丢给 JSONTokener: 它读到空格就停, 把 "echo c_ok" 截成 "echo",
        // 导致完整块的参数被吃掉后半截, 工具真按截断参数执行(实测 mock 注入复现)。
        val t = v.trim()
        if (t.isEmpty()) return ""
        val c = t[0]
        val looksJson = c == '{' || c == '[' || c == '"' || c == '-' || c.isDigit() ||
            t == "true" || t == "false" || t == "null"
        if (!looksJson) return v
        return runCatching {
            when (val parsed = org.json.JSONTokener(t).nextValue()) {
            is JSONObject, is org.json.JSONArray, is Int, is Long, is Double, is Boolean -> parsed
                is String -> if (parsed == "null") JSONObject.NULL else parsed
                else -> v
            }
        }.getOrElse { v }
    }

    /** 取第一个平衡的 {...}(跳过字符串内的大括号) */
    private fun extractBalancedJson(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
