package io.github.aixtin.nyral

/**
 * SSE 单行解析纯逻辑(2026-11-06 从 LocalEngine 流式循环抽出, 行为等价):
 * 只回答三件事 —— 这行是不是 data 字段 / 载荷是什么 / 是不是结束标记。
 * 与网络读取解耦后可在 JVM 侧随机化 fuzz, 锁住流式协议容错行为
 * (注释心跳、无空格 data:、载荷空格、大小写、[DONE])。
 */
object SseLine {

    private const val PREFIX = "data:"

    /** 流式结束标记 */
    const val DONE = "[DONE]"

    /**
     * 取行内载荷: 非 data 行(空行分隔 / `:` 注释心跳 / event / id 字段)返回 null。
     * 兼容 "data:xxx" 与 "data: xxx"; 字段名大小写敏感(SSE 规范), "DATA:" 不认。
     */
    fun payload(line: String): String? {
        if (!line.startsWith(PREFIX)) return null
        return line.substring(PREFIX.length).trim()
    }

    fun isDone(payload: String): Boolean = payload == DONE
}
