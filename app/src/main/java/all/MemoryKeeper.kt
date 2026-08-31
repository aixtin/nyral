package io.github.aixtin.droidagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MemoryKeeper: 会话归档器(记忆管家)。
 * 独立后台线程整理记忆, 与主 AI 对话完全隔离, 用户无感。
 *
 * 架构(2026-08-24 重写):
 *  - 短期: 当前会话在内存窗口(主AI buildHistory 最近N条)
 *  - 中期: 会话结束/攒够阈值 -> 原文分块全量入 memory 表(向量+原文, 无损)
 *  - 长期: 所有历史原文都在 memory 表, 语义检索 top-k 召回
 *  - 辅助: 每次归档生成 3~5 条主题索引合并进 summary, 主AI自动注入当导航
 *
 * 可靠性:
 *  - pending 表落盘, 进程被杀不丢消息
 *  - 原文入库优先: 即使索引生成失败, 原文已在 memory 表, 语义检索不丢
 *  - AtomicBoolean 防并发重复归档
 */
object MemoryKeeper {

    private const val ARCHIVE_SIZE = 40            // 攒够 40 条(约20轮)强制归档
    private const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L // 30 分钟无消息 = 会话结束, 归档
    private const val CHUNK_CHARS = 800            // 归档块大小(字符), 每块一条向量
    private const val MAX_INDEX_TEXT = 6000        // 索引生成输入上限

    private lateinit var appContext: Context
    private lateinit var db: MemoryDb
    private lateinit var embedder: MemoryEmbedder

    // 独立单线程: 与主对话线程池互不干扰
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "memory-keeper").apply { isDaemon = true }
    }
    private val archiving = AtomicBoolean(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        LogStore.init(appContext)
        MemoryApiConfig.init(appContext)   // 保证归档线程一定能读到独立辅助配置
        db = MemoryDb(appContext)
        embedder = MemoryEmbedder(appContext)
        // 恢复残留队列: 若上次会话已结束则归档, 否则等新消息
        maybeArchive()
    }

    /** 主对话每轮消息实时入队(落盘, 不阻塞) */
    fun push(role: String, content: String) {
        if (!::db.isInitialized) return
        db.addPending(role, content)
        // 用户明确表达"记住"意图时立即归档, 不等攒够阈值/空闲超时
        maybeArchive(force = role == "user" && content.contains("记住"))
    }

    /** 触发条件: 攒够阈值 或 会话空闲超时(force=true 则跳过门槛直接归档) -> 丢后台线程, 立即返回 */
    private fun maybeArchive(force: Boolean = false) {
        val n = db.pendingCount()
        if (n == 0) return
        if (!force && n < ARCHIVE_SIZE) {
            val last = db.pendingLastTs()
            if (last <= 0 || System.currentTimeMillis() - last < IDLE_TIMEOUT_MS) return
        }
        if (!archiving.compareAndSet(false, true)) return
        executor.execute {
            val ok = try {
                archive(); true
            } catch (e: Exception) {
                android.util.Log.e("MemoryKeeper", "归档异常", e)
                false
            }
            archiving.set(false)
            // 仅成功后继续处理新到消息; 失败不自动重试, 等下次 push/init 再触发, 防空转死循环
            if (ok) maybeArchive()
        }
    }

    private fun archive() {
        try {
            val batch = db.pendingAll()
            if (batch.isEmpty()) return
            val text = batch.joinToString("\n") { p -> "${p.role}: ${p.content}" }
            LogStore.i(LogStore.MEM, "开始归档: ${batch.size} 条 / ${text.length} 字符")

            // 1) 原文分块 + 向量化(失败则整体放弃, pending 保留, 下次重试)
            val chunks = chunkText(text, CHUNK_CHARS)
                .filter { it.isNotBlank() }
                .map { it to embedder.embed(it) }
            LogStore.i(LogStore.MEM, "原文向量化完成: ${chunks.size} 块")

            // 2) 原子"入库 + 清队列": 同一事务, 索引生成即使失败也不会导致同一批 pending 重复入库
            db.consumePending(batch.map { it.id }, chunks, MemoryTools.sessionTitleProvider?.invoke())
            LogStore.i(LogStore.MEM, "原文已入库")

            // 3) 生成主题索引(3~5条要点)合并进 summary, 供主AI自动注入当导航
            //    索引失败不影响原文入库, 也不影响队列状态
            try {
                val oldIdx = db.loadSummary()
                val combined = (oldIdx?.let { "旧索引:\n$it\n\n" } ?: "") +
                    "本次会话内容:\n${text.take(MAX_INDEX_TEXT)}"
                LogStore.i(LogStore.MEM, "生成主题索引, 配置: ${MemoryApiConfig.statusText()}")
                val newIdx = callIndex(combined)
                if (newIdx.isNotBlank()) {
                    db.saveSummary(newIdx)
                    LogStore.i(LogStore.MEM, "索引生成成功: ${newIdx.length} 字符")
                } else {
                    LogStore.w(LogStore.MEM, "索引生成为空, 跳过更新")
                }
            } catch (e: Exception) {
                LogStore.w(LogStore.MEM, "索引生成失败(不影响原文): ${e.message}")
                android.util.Log.w("MemoryKeeper", "索引生成失败(不影响原文): ${e.message}")
            }
        } catch (e: Exception) {
            // 归档失败(embed/DB异常): 队列保留, 下次触发自动重试
            LogStore.e(LogStore.MEM, "归档失败: ${e.message}")
            android.util.Log.e("MemoryKeeper", "归档失败", e)
        }
    }

    /** 按行拼块, 尽量在消息边界切分 */
    private fun chunkText(text: String, size: Int): List<String> {
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.lines()) {
            if (sb.isNotEmpty() && sb.length + line.length > size) {
                chunks.add(sb.toString().trim())
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        return chunks
    }

    private fun callIndex(text: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            "你是对话索引器(记忆管家)。把用户对话提炼为 3~5 条主题索引, 每条一行, 格式: 「主题|关键事实或决策|结果」。" +
            "只保留值得长期记住的事实、偏好、决策、待办; 省略寒暄和过程。不要前缀, 不要序号以外的格式。"))
        messages.put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject()
        body.put("model", MemoryApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.3)

        val conn = URL(MemoryApiConfig.chatUrl()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${MemoryApiConfig.apiKey()}")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        // errorStream 可能为 null(网络层异常无响应体), 判空避免 NPE 掩盖真实错误码
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = if (stream != null) BufferedReader(InputStreamReader(stream)).readText() else ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("API $code")
        val obj = JSONObject(resp)
        val content = obj.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        // 辅助 AI token 统计: 有真实 usage 用真实值, 否则本地估算(仅成功请求计入)
        val usage = obj.optJSONObject("usage")
        val usedPrompt = if (usage != null) usage.optLong("prompt_tokens", 0) else 0L
        val usedCompletion = if (usage != null) usage.optLong("completion_tokens", 0) else 0L
        val promptTokens = if (usedPrompt > 0) usedPrompt else text.length / 3L
        val completionTokens = if (usedCompletion > 0) usedCompletion else content.length / 3L
        if (::appContext.isInitialized) {
            TokenStore.recordAux(
                appContext,
                promptTokens.toInt().coerceAtLeast(0),
                completionTokens.toInt().coerceAtLeast(0)
            )
        }
        return content
    }
}
