package io.github.aixtin.nyral

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * MemoryGate: 记忆检索决策闸门(关键时候才翻记忆)。
 *
 * 架构(2026-09-02):
 *  - 轮首: 每轮用户消息发送前, 先经辅助模型(MemoryApiConfig)判断「这轮是否需要借鉴此前的对话」,
 *          YES 才本地 memory_search 并在主请求注入记忆参考——不依赖主模型自觉翻记忆。
 *  - 收尾: 主模型在工具循环后准备输出"答案:"时, 若本轮从未注入过记忆, 由 LocalEngine 用本地快检兜底,
 *          再拦截重发(见 LocalEngine.streamOnce 的 answerGate)。
 *  - 降级: 辅助判断任何异常一律静默当 NO, 绝不让判断失败阻塞主对话。
 */
object MemoryGate {

    /** 本轮对话注入的"记忆参考"区块前缀(供 LocalEngine 过滤/识别) */
    const val REF_PREFIX = "【记忆参考】"

    /**
     * 轮首: 判断 + 检索, 返回可注入主请求的记忆参考文本; 无需翻记忆(判断 NO / 检索无命中 / 异常)返回 null。
     * @param recent 近期对话尾部(建议 1200~1500 字符), 含最新用户问题的语境
     */
    fun recall(context: Context, recent: String): String? {
        return try {
            val need = judge(context, recent) ?: return null
            if (!need.first) return null
            val query = need.second.ifBlank { lastUserText(recent) }
            val hits = MemoryTools.search(context, query)
            if (hits.isBlank() || hits.startsWith("记忆中暂无") || hits.startsWith("记忆检索失败")) return null
            "$REF_PREFIX(可能相关,以对话为准):\n$hits"
        } catch (e: Exception) {
            android.util.Log.w("MemoryGate", "recall 降级为 NO: ${e.message}")
            null
        }
    }

    /**
     * 辅助模型单输出两道: 是否需要翻记忆(YES/NO) + 检索 query。
     * 返回 Pair(needMemory, query); 任何失败返回 null(调用方按 NO 处理)。
     */
    private fun judge(context: Context, recent: String): Pair<Boolean, String>? {
        try {
            MemoryApiConfig.init(context.applicationContext)
        } catch (e: Exception) {
            Log.w("agent", "MemoryGate: 辅助模型配置初始化失败，记忆判断将按未配置处理: ${e.message}")
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            "你是记忆检索决策器。判断下面这轮对话是否需要查阅该用户过去的对话记录才能较好地回答。" +
                "只需输出两行: 第一行 YES 或 NO; 若第一行为 YES, 第二行给一个适合检索记忆的关键词/短句(30字内)。不要输出任何其它内容。"))
        messages.put(JSONObject().put("role", "user").put("content", recent.take(1200)))

        val body = JSONObject()
        body.put("model", MemoryApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.1)
        body.put("max_tokens", 40)

        val obj = ApiClient.postJson(
            MemoryApiConfig.chatUrl(),
            body.toString(),
            headers = ApiClient.bearer(MemoryApiConfig.apiKey()),
            connectMs = 10_000,
            readMs = 20_000,
        ).getOrElse { throw RuntimeException("MemoryGate judge API 失败: ${it.message}") }
        val content = obj.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        val usage = obj.optJSONObject("usage")
        val usedPrompt = if (usage != null) usage.optLong("prompt_tokens", 0) else 0L
        val usedCompletion = if (usage != null) usage.optLong("completion_tokens", 0) else 0L
        try {
            TokenStore.recordAux(
                context.applicationContext,
                (if (usedPrompt > 0) usedPrompt else recent.take(1200).length / 3L).toInt().coerceAtLeast(0),
                (if (usedCompletion > 0) usedCompletion else content.length / 3L).toInt().coerceAtLeast(0)
            )
        } catch (e: Exception) {
            Log.w("agent", "MemoryGate: token 统计(辅助)记录失败: ${e.message}")
        }
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.uppercase() ?: ""
        return when {
            first.contains("NO") -> false to ""
            first.contains("YES") -> true to (lines.getOrNull(1) ?: "")
            else -> null
        }
    }

    /** 从对话文本末尾提取最近一条用户消息(供 fallback query 用) */
    fun lastUserText(recent: String): String {
        val idx = recent.lastIndexOf("user: ")
        return if (idx >= 0) recent.substring(idx + 6).substringBefore('\n').trim()
            .ifBlank { recent.takeLast(200) } else recent.takeLast(200)
    }
}
