package io.github.aixtin.nyral

/**
 * 错误文案两层化(2026-11-08)。
 * 旧实现把引擎异常 message 直接上屏: 400/429 的 technical 报文、含 URL/JSON 的原始错误糊在错误卡上,
 * 用户看不懂也不知道该做什么。这里只做"降维": 原始报文一律保留(折叠查看), 主文案给行动建议。
 * 纯函数, 无状态, 便于单测与回归断言。
 */
object ErrorText {

    /** 中断标记: 追加在半截正文末尾(与〔已中断〕同形, 但写明原因) */
    fun mark(reason: String) = "\n\n〔已中断：" + reason + "〕"

    /** 原始异常 -> 人话(能看懂 + 知道下一步做什么) */
    fun humanize(raw: String): String {
        val m = raw.trim()
        if (m.isEmpty()) return "回复没有完成，请重试一次"
        val l = m.lowercase()
        return when {
            l.contains("api key") || l.contains("unauthorized") || m.contains("401") ->
                "模型密钥无效或已过期，请到「设置 → 模型配置」检查 API Key"
            m.contains("403") || l.contains("forbidden") ->
                "服务端拒绝了这次请求（403），可能是密钥权限不足或额度受限"
            m.contains("404") || l.contains("not found") ->
                "接口地址或模型名不存在（404），请核对模型配置里的地址与模型名"
            m.contains("429") || l.contains("rate limit") || l.contains("quota") || l.contains("insufficient") ->
                "请求过于频繁或额度已用尽（429），稍后再试或检查账户额度"
            l.contains("context length") || l.contains("maximum context") || l.contains("too many tokens") ||
                l.contains("context_length_exceeded") ->
                "这轮内容超出模型上下文上限，建议新建会话或精简引用内容后再发"
            m.contains("超时") || l.contains("timeout") || l.contains("timed out") ->
                "服务端响应超时，网络或上游过慢，可稍后重试"
            l.contains("unable to resolve host") || l.contains("unknownhost") || l.contains("connection refused") ||
                l.contains("failed to connect") || l.contains("connect timed out") ->
                "连不上模型服务器，请检查网络或代理设置"
            l.contains("broken pipe") || l.contains("connection reset") || l.contains("unexpected end") ||
                l.contains("stream was closed") || l.contains("网络连接中断") ->
                "网络连接中断，回复没有传完"
            m.contains("502") || m.contains("503") || m.contains("504") ->
                "模型服务暂时不可用（5xx），稍后重试"
            m.contains("500") ->
                "模型服务内部报错（500），稍后重试"
            m.contains("400") ->
                "请求被服务端拒绝（400），多为参数或容量问题，详情见下方"
            m.contains("未配置") || m.contains("请先") ->
                m   // 已是面向用户的引导文案, 原样保留
            // 兜底: 短中文错误原样展示(无技术噪音), 否则给通用话术
            m.length <= 40 && !m.contains("{") && !m.contains("http") -> m
            else -> "这轮回复没能完成，稍后重试；若反复出现可展开详情"
        }
    }

    /** 是否值得折叠展示原始报文(纯通用文案时不重复展示) */
    fun hasDetail(raw: String, human: String): Boolean {
        val m = raw.trim()
        return m.isNotEmpty() && m != human && (m.length > 24 || m.contains("http") || m.contains("{") || m.contains("error"))
    }
}
