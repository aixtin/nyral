package io.github.aixtin.nyral

/**
 * 错误文案两层化(2026-11-08)。
 * 旧实现把引擎异常 message 直接上屏: 400/429 的 technical 报文、含 URL/JSON 的原始错误糊在错误卡上,
 * 用户看不懂也不知道该做什么。这里只做"降维": 原始报文一律保留(折叠查看), 主文案给行动建议。
 * 纯函数, 无状态, 便于单测与回归断言。
 */
object ErrorText {

    /** 短文案阈值: 不超过该长度的纯文本错误原样展示 */
    private const val SHORT_TEXT_LIMIT = 40

    /** 详情阈值: 超过该长度才值得折叠展示原始报文 */
    private const val DETAIL_TEXT_LIMIT = 24

    private const val EMPTY_HINT = "回复没有完成，请重试一次"
    private const val GENERIC_HINT = "这轮回复没能完成，稍后重试；若反复出现可展开详情"

    /** 关键词 -> 人话。顺序即优先级, 先命中先返回 */
    private val RULES: List<Pair<List<String>, String>> = listOf(
        listOf("api key", "unauthorized", "401") to
            "模型密钥无效或已过期，请到「设置 → 模型配置」检查 API Key",
        listOf("403", "forbidden") to
            "服务端拒绝了这次请求（403），可能是密钥权限不足或额度受限",
        listOf("404", "not found") to
            "接口地址或模型名不存在（404），请核对模型配置里的地址与模型名",
        listOf("429", "rate limit", "quota", "insufficient") to
            "请求过于频繁或额度已用尽（429），稍后再试或检查账户额度",
        listOf("context length", "maximum context", "too many tokens", "context_length_exceeded") to
            "这轮内容超出模型上下文上限，建议新建会话或精简引用内容后再发",
        listOf("超时", "timeout", "timed out") to
            "服务端响应超时，网络或上游过慢，可稍后重试",
        listOf("unable to resolve host", "unknownhost", "connection refused",
            "failed to connect", "connect timed out") to
            "连不上模型服务器，请检查网络或代理设置",
        listOf("broken pipe", "connection reset", "unexpected end",
            "stream was closed", "网络连接中断") to
            "网络连接中断，回复没有传完",
        listOf("502", "503", "504") to
            "模型服务暂时不可用（5xx），稍后重试",
        listOf("500") to
            "模型服务内部报错（500），稍后重试",
        listOf("400") to
            "请求被服务端拒绝（400），多为参数或容量问题，详情见下方",
    )

    /** 已是面向用户的引导文案, 原样保留 */
    private val PASS_THROUGH = listOf("未配置", "请先")

    /** 中断标记: 追加在半截正文末尾(与〔已中断〕同形, 但写明原因) */
    fun mark(reason: String) = "\n\n〔已中断：" + reason + "〕"

    /** 原始异常 -> 人话(能看懂 + 知道下一步做什么) */
    fun humanize(raw: String): String {
        val m = raw.trim()
        val l = m.lowercase()
        val hit = RULES.firstOrNull { (keys, _) -> keys.any { l.contains(it) || m.contains(it) } }
        return when {
            m.isEmpty() -> EMPTY_HINT
            hit != null -> hit.second
            PASS_THROUGH.any { m.contains(it) } -> m
            isPlainShort(m) -> m
            else -> GENERIC_HINT
        }
    }

    /** 是否值得折叠展示原始报文(纯通用文案时不重复展示) */
    fun hasDetail(raw: String, human: String): Boolean {
        val m = raw.trim()
        if (m.isEmpty() || m == human) return false
        return m.length > DETAIL_TEXT_LIMIT ||
            m.contains("http") || m.contains("{") || m.contains("error")
    }

    private fun isPlainShort(m: String): Boolean =
        m.length <= SHORT_TEXT_LIMIT && !m.contains("{") && !m.contains("http")
}
