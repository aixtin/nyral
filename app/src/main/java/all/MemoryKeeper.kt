package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
    private const val MAX_INDEX_TEXT = 6000        // 单条索引生成的输入上限
    private const val REFRESH_PER_ARCHIVE = 8      // 每次归档后精修的索引条数(单条一次调用, 控成本)
    private const val REFRESH_PER_RUN = 20         // 手动维护/删除后每次精修条数
    private const val REFRESH_BOOTSTRAP = 200      // 升级首启: 只精修最近 N 条, 更早的留本地占位标题
    private const val INDEX_API_GAP_MS = 300L      // 相邻两次索引生成的间隔, 让出 CPU/限流
    private const val BATCH_SIZE = 10              // 每次向量化+落库的条数上限(2026-10-20 发热修复)
    private const val BATCH_GAP_MS = 800L          // 批次间隔, 让出 CPU 防止持续 200% 占用

    private lateinit var appContext: Context
    private lateinit var db: MemoryDb
    private lateinit var embedder: MemoryEmbedder

    // 独立单线程: 与主对话线程池互不干扰; 低优先级避免与前台交互抢 CPU(2026-10-20 发热修复)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "memory-keeper").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val archiving = AtomicBoolean(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        LogStore.init(appContext)
        MemoryApiConfig.init(appContext)   // 保证归档线程一定能读到独立辅助配置
        db = MemoryDb(appContext)
        ModelUpdater.init(appContext)
        embedder = MemoryEmbedder(appContext)
        // 恢复残留队列: 若上次会话已结束则归档, 否则等新消息
        maybeArchive()
        // v14: 索引页引导 —— 先把缺口行补上本地占位标题(零模型成本), 再后台精修最近的若干条
        executor.execute {
            try {
                val gap = db.backfillTitlesLocal()
                if (gap > 0) {
                    LogStore.i(LogStore.MEM, "索引页补齐: 待精修 $gap 条(本轮上限 $REFRESH_BOOTSTRAP)")
                    refineIndexes(REFRESH_BOOTSTRAP)
                }
            } catch (e: Exception) {
                LogStore.w(LogStore.MEM, "索引页初始化失败: ${e.message}")
            }
        }
    }

    /** 主对话每轮消息实时入队(落盘, 不阻塞) */
    fun push(role: String, content: String, sessionId: Long? = null, msgNo: Int? = null) {
        if (!::db.isInitialized) return
        db.addPending(role, content, sessionId, msgNo)
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
                archive()
            } catch (e: Exception) {
                android.util.Log.e("MemoryKeeper", "归档异常", e)
                false
            }
            archiving.set(false)
            // 仅成功后继续处理新到消息; 失败不自动重试, 等下次 push/init 再触发, 防空转死循环
            if (ok) maybeArchive()
        }
    }

    private fun archive(): Boolean {
        return try {
            // 语义模型未就绪: 触发下载, 本次归档暂缓(pending 保留, 下载完成后下次触发再归档)
            // 2026-10-20 发热修复: 未就绪返回 false, 不再视为"成功"导致 maybeArchive 立即递归空转
            if (!embedder.isReady()) {
                LogStore.i(LogStore.MEM, "语义模型未就绪, 触发下载, 归档暂缓")
                ModelUpdater.ensureDownloaded()
                return false
            }
            val batch = db.pendingAll()
            if (batch.isEmpty()) return true
            // 每条消息带时间戳(如 user[09-02 14:30]: ...), 让原文记忆与主题索引都具备时间观念
            val text = batch.joinToString("\n") { p ->
                val t = MemoryDb.fmtTs(p.ts)
                if (t.isEmpty()) "${p.role}: ${p.content}" else "${p.role}[$t]: ${p.content}"
            }
            LogStore.i(LogStore.MEM, "开始归档: ${batch.size} 条 / ${text.length} 字符")

            // 1) 原文分块 + 向量化: 每条消息独立成块(不跨消息混合), 超长消息内部按行切块
            //    每块携带来源指针(session_id + msg_no), 删除时按来源联动清同源块
            // 2026-10-20 发热修复: 分批处理, 批间 sleep 让出 CPU, 防止大量 pending 一口气全量向量化导致持续 200% 占用
            var processed = 0
            while (processed < batch.size) {
                val sub = batch.subList(processed, minOf(processed + BATCH_SIZE, batch.size))
                val chunks = ArrayList<MemoryDb.PendingChunk>()
                for (p in sub) {
                    val line = if (MemoryDb.fmtTs(p.ts).isEmpty()) "${p.role}: ${p.content}" else "${p.role}[${MemoryDb.fmtTs(p.ts)}]: ${p.content}"
                    for (seg in chunkText(line, CHUNK_CHARS)) {
                        if (seg.isBlank()) continue
                        chunks.add(MemoryDb.PendingChunk(seg, embedder.embed(seg), p.sessionId, p.msgNo))
                    }
                }
                // 2) 原子"入库 + 清队列": 同一事务, 索引生成即使失败也不会导致同一批 pending 重复入库
                db.consumePending(sub.map { it.id }, chunks, MemoryTools.sessionTitleProvider?.invoke())
                processed += sub.size
                if (processed < batch.size) {
                    LogStore.i(LogStore.MEM, "批次 ${processed}/${batch.size} 已入库, 让出 CPU ${BATCH_GAP_MS}ms")
                    Thread.sleep(BATCH_GAP_MS)
                }
            }
            LogStore.i(LogStore.MEM, "原文已入库")

            // 3) 索引层(v14): 新入库行先补本地占位标题(零成本, 只补缺口), 再把占位行逐条精修成
            //    「标题|状态|触发词|锚点」写进 mem_index。一条记忆一行索引, 不再整段覆盖重建。
            //    索引失败不影响原文入库, 也不影响队列状态
            try {
                val pending = db.backfillTitlesLocal()
                LogStore.i(LogStore.MEM, "索引占位已补齐, 待精修 $pending 条, 配置: ${MemoryApiConfig.statusText()}")
                val done = refineIndexes(REFRESH_PER_ARCHIVE)
                LogStore.i(LogStore.MEM, "本次精修索引 $done 条")
            } catch (e: Exception) {
                LogStore.w(LogStore.MEM, "索引生成失败(不影响原文): ${e.message}")
                android.util.Log.w("MemoryKeeper", "索引生成失败(不影响原文): ${e.message}")
            }
            true
        } catch (e: Exception) {
            // 归档失败(embed/DB异常): 队列保留, 下次触发自动重试
            LogStore.e(LogStore.MEM, "归档失败: ${e.message}")
            android.util.Log.e("MemoryKeeper", "归档失败", e)
            false
        }
    }

    /** 按行拼块, 尽量在消息边界切分 */
    private fun chunkText(text: String, size: Int): List<String> {
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.lines()) {
            // B2 修复(2026-10-20): 单行超过 size 时按 size 硬切(码点安全), 保证向量与块内容对应
            var rest = line
            while (rest.length > size) {
                if (sb.isNotEmpty()) {
                    chunks.add(sb.toString().trim())
                    sb.clear()
                }
                var cut = size
                if (Character.isLowSurrogate(rest[cut - 1])) {
                    cut = if (cut >= 2 && Character.isHighSurrogate(rest[cut - 2])) cut - 2 else cut - 1
                }
                chunks.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut)
            }
            if (sb.isNotEmpty() && sb.length + rest.length > size) {
                chunks.add(sb.toString().trim())
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(rest)
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        return chunks
    }

    /**
     * 删除记忆后的一致性维护(v14 起): mem_index 由 AFTER DELETE 触发器级联清理,
     * 不再需要「按现存原文重写整段摘要」这种会把已删内容重新搀回去的做法;
     * 这里只补齐缺口占位并继续精修。保留函数签名, 供记忆列表页删除后调用。
     */
    fun rebuildSummary() {
        executor.execute {
            try {
                val pending = db.backfillTitlesLocal()
                val done = refineIndexes(REFRESH_PER_RUN)
                LogStore.i(LogStore.MEM, "索引维护完成: 待精修 $pending 条, 本次精修 $done 条")
            } catch (e: Exception) {
                LogStore.w(LogStore.MEM, "索引维护失败: ${e.message}")
                android.util.Log.w("MemoryKeeper", "索引维护失败: ${e.message}")
            }
        }
    }

    /**
     * 逐条精修占位索引: 每次取 limit 条, 一条记忆一次调用, 生成「标题|状态|触发词|锚点」。
     * 失败分两种:
     *  - API 调用异常(未配置/网络/额度): 不写任何标记, 该行留在队列里, 配置恢复后下次启动自动补上;
     *    连续异常 3 次即中断本轮, 防止配置错误时空转烧额度。
     *  - 内容为空/解析不出标题(脏数据): 把 anchor 写成空串标记"已尝试", 不再反复重试。
     */
    fun refineIndexes(limit: Int = REFRESH_PER_RUN): Int {
        if (!::db.isInitialized) return 0
        var done = 0
        var fails = 0
        val rows = try {
            db.pendingIndexRows(limit)
        } catch (e: Exception) {
            LogStore.w(LogStore.MEM, "取待精修行失败: ${e.message}")
            return 0
        }
        for (row in rows) {
            val out = try {
                callIndexOne(row.content)
            } catch (e: Exception) {
                LogStore.w(LogStore.MEM, "索引精修失败 memId=${row.id}: ${e.message}")
                fails++
                if (fails >= 3) {
                    LogStore.w(LogStore.MEM, "索引精修连续失败 $fails 次, 本轮中断(配置恢复后下次启动继续)")
                    break
                }
                if (rows.size > 1) {
                    try { Thread.sleep(INDEX_API_GAP_MS) } catch (_: InterruptedException) {}
                }
                continue
            }
            fails = 0
            if (out != null) {
                try {
                    db.upsertMemIndex(row.id, out.title, out.status, row.ts, out.anchor, out.triggers)
                    done++
                } catch (e: Exception) {
                    LogStore.w(LogStore.MEM, "索引写库失败 memId=${row.id}: ${e.message}")
                }
            } else {
                // 解析不出有效标题: 标记已尝试(anchor 写空串, 非 NULL), 不再进队列; 标题保留本地占位
                try {
                    db.upsertMemIndex(row.id, null, 0, row.ts, "", "")
                } catch (e: Exception) {
                    LogStore.w(LogStore.MEM, "索引占位标记失败 memId=${row.id}: ${e.message}")
                }
            }
            if (rows.size > 1) {
                try { Thread.sleep(INDEX_API_GAP_MS) } catch (_: InterruptedException) {}
            }
        }
        return done
    }

    /** 索引解析结果: 一条记忆对应一行 */
    private data class IdxOut(val title: String, val status: Int, val triggers: String, val anchor: String)

    /**
     * 单条记忆 -> 一行索引: 「标题|状态|触发词|锚点」。
     * 不与旧索引拼接、不整段覆盖, 从根上消除"索引条数忽 3 忽 5"和"删了记忆还在摘要里"的问题。
     */
    private fun callIndexOne(content: String): IdxOut? {
        if (content.isBlank()) return null
        val text = content.take(MAX_INDEX_TEXT)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            "你是对话索引器(记忆管家)。把下面这一条对话记忆提炼为一行索引, 四段用竖线 | 分隔, " +
                "格式: 标题|状态|触发词|锚点。\n" +
                "标题: 不超过15字, 概括这条记忆讲的是什么, 不要带书名号、引号、序号。\n" +
                "状态: 只能填 未分类/进行中/待办/已完成/已废弃 之一。\n" +
                "触发词: 3~5 个, 用顿号分隔, 优先保留专有名词、项目名、文件名、命令名。\n" +
                "锚点: 不超过30字, 概括关键结论或决定。\n" +
                "只输出这一行, 不要解释, 不要换行, 不要加任何前缀。"))
        messages.put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject()
        body.put("model", MemoryApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.1)

        val obj = ApiClient.postJson(
            MemoryApiConfig.chatUrl(),
            body.toString(),
            headers = ApiClient.bearer(MemoryApiConfig.apiKey()),
            connectMs = 20_000,
            readMs = 60_000,
        ).getOrElse { throw RuntimeException("API 失败: ${it.message}") }
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
        return parseIdx(content)
    }

    /** 解析「标题|状态|触发词|锚点」; 只取第一行有效内容, 容错小模型的多余前缀 */
    private fun parseIdx(raw: String): IdxOut? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val clean = line.replace(Regex("^[\\s\\-*\u2022\u00b7\\d.\u3001)\uff09]+"), "").trim()
        if (clean.isEmpty()) return null
        val parts = clean.split('|').map { it.trim() }
        val title = (parts.getOrNull(0) ?: return null)
            .trim('\u300c', '\u300d', '"', '\u201c', '\u201d', '[', ']', '#', '\u300a', '\u300b')
            .trim()
            .take(20)
        if (title.isEmpty()) return null
        val statusText = parts.getOrNull(1) ?: ""
        val status = when {
            statusText.contains("进行") -> 1
            statusText.contains("待办") || statusText.contains("待做") || statusText.contains("计划") -> 2
            statusText.contains("完成") || statusText.contains("已做") -> 3
            statusText.contains("废弃") || statusText.contains("作废") || statusText.contains("放弃") -> 4
            else -> 0
        }
        val triggers = (parts.getOrNull(2) ?: "").take(120)
        val anchor = (parts.getOrNull(3) ?: "").take(80)
        return IdxOut(title, status, triggers, anchor)
    }
}
