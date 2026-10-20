package io.github.aixtin.nyral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 知识库自动沉淀执行器(2026-10-19 阶段2): 对话流游标接入 + 辅助AI废话过滤/提炼/章节归类。
 *
 * 触发时机: AI 回复完成(onDone)后由 StreamUiBridge 调用 maybeSink(主线程), 内部转后台线程执行, 不阻塞聊天。
 * 游标语义: kb_cursors.last_msg_id 存"本会话最后沉淀到的全局消息 seq"(对齐 session_msgs.seq);
 *           幂等只前进不倒退; 沉淀失败/调用失败不推进, 下轮自动重试。
 * 窗口: 每轮取游标之后最近 5-10 条(方案定稿 4.2, 取 8); 超长会话窗口外旧消息不追溯(由 summary 承担)。
 * 与记忆系统零交集: 只写 knowledge.db, 不碰 MemoryDb / MemoryGate / MemoryKeeper。
 */
object KnowledgeSinker {
    private const val TAG = "KnowledgeSinker"
    private const val WINDOW = 8                 // 每轮窗口: 游标之后最近 5-10 条
    private const val SINK_TIMEOUT_MS = 60_000   // 辅助AI调用读超时

    private val executor = Executors.newSingleThreadExecutor()
    private val sinkLock = Any()

    /** 入口(主线程调用即可): 开关关/无会话/空消息时静默跳过 */
    fun maybeSink(context: Context, sessionId: Long?, sessionBaseSeq: Int, messages: List<MemoryDb.SessionMsg>, toolTrace: List<Pair<String, String>> = emptyList()) {
        if (!KnowledgeBaseConfig.isEnabled(context)) return
        if (sessionId == null) return
        if (messages.isEmpty()) return
        val app = context.applicationContext
        executor.execute {
            try {
                sinkOnce(app, sessionId, sessionBaseSeq, messages, toolTrace)
            } catch (t: Throwable) {
                // 兜底: 任何异常不推进游标, 下轮自动重试; 绝不打扰用户
                Log.w(TAG, "sink failed, cursor not advanced, will retry next round", t)
            }
        }
    }

    private fun sinkOnce(context: Context, sessionId: Long, sessionBaseSeq: Int, messages: List<MemoryDb.SessionMsg>, toolTrace: List<Pair<String, String>>) {
        synchronized(sinkLock) {
            val db = KnowledgeBaseDb(context)
            val cursor = db.getCursor(sessionId)
            val fromGlobal = cursor?.lastMsgId ?: 0L
            // 游标(全局seq)映射到内存窗口 index; 窗口外旧消息不追溯(MVP 行为, 超长会话由 summary 承担)
            val fromIdx = (fromGlobal - sessionBaseSeq).coerceAtLeast(0L).toInt()
            if (fromIdx >= messages.size) return     // 已沉淀到最新(或游标超前), 无事可做
            val toIdx = minOf(fromIdx + WINDOW, messages.size)
            // 立即拷贝窗口文本(防后台线程迭代时主线程继续追加消息导致 ConcurrentModification)
            val windowMsgs = messages.subList(fromIdx, toIdx)
            val transcript = windowMsgs
                .joinToString("\n") { "[${it.role}] ${it.content.take(600)}" }
            val lastSeq = (toIdx + sessionBaseSeq - 1).coerceAtLeast(sessionBaseSeq).toLong()

            // 已有书目录(全部话题), 供辅助AI归并判断
            val bookCatalog = db.listBooks().joinToString("\n") { "- ${it.title}" }

            val result = callSinkAi(transcript, bookCatalog)
            if (result == null) {
                Log.w(TAG, "sink ai call failed, cursor not advanced")
                return
            }
            if (!result.optBoolean("has_content", false)) {
                // 无内容: 游标前进, 静默跳过
                db.advanceCursor(sessionId, cursor?.bookId ?: 0L, lastSeq)
                Log.d(TAG, "no content, cursor -> $lastSeq")
                return
            }

            val bookTitle = result.optString("book").trim()
            val chapterTitle = result.optString("chapter").trim()
            val entryTitle = result.optString("entry_title").trim()
            val entryContent = result.optString("entry_content").trim()
            val chapterKeywords = result.optString("chapter_keywords").trim()
            val chapterSummary = result.optString("chapter_summary").trim()
            if (bookTitle.isEmpty() || chapterTitle.isEmpty() || entryTitle.isEmpty() || entryContent.isEmpty()) {
                Log.w(TAG, "sink ai returned incomplete json: $result")
                return    // 游标不动, 下轮重试
            }

            // 书 = 话题容器: 按标题归并已有书(同名话题归一本), 全新话题独立成书
            val bid = db.findBookByTitle(bookTitle)?.id
                ?: db.createBook(bookTitle).also { Log.i(TAG, "new book: $bookTitle") }
            // 章 = 每次沉淀的一个批次: 一律新建章(章名=切入点, 具体差异化; 同话题后续批次自然倒序堆叠)
            val chapterId = db.createChapter(bid, chapterTitle, chapterKeywords, chapterSummary)
            // 条目: 每轮新建一条(版本1起步; 更新已有条目/编辑门禁属阶段3)
            val srcIds = (fromIdx + sessionBaseSeq until toIdx + sessionBaseSeq).joinToString(",")
            // 图片池(阶段3 2026-10-19): 窗口内附件图压缩复制进知识库 kb_images/, MD 相对路径引用;
            // 知识库自包含, 不依赖原附件库(删会话/附件不影响渲染)
            val imgRefs = mutableListOf<String>()
            try {
                val imgDir = File(context.filesDir, "kb_images").apply { mkdirs() }
                windowMsgs.forEachIndexed { wi, m ->
                    Regex("att://[A-Za-z0-9_.\\-]+").findAll(m.content).forEachIndexed { j, r ->
                        sinkImage(context, r.value, imgDir, lastSeq.toInt(), wi * 8 + j)
                            ?.let { imgRefs += "![](kb_images/$it)" }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "image pool failed, continue without images", t)
            }
            // PDF 页快照(阶段3 2026-10-19): 本轮 pdf_page 工具引用过的页渲染 JPEG 进 kb_images/,
            // 用户无需在 PDF 里翻页即可看到被引用的内容; 引用页随 AI 实际调用自动捕获
            try {
                val pdfDir = File(context.filesDir, "kb_images").apply { mkdirs() }
                // 同一 PDF 同一页去重: AI 常先 text 失败再 image 重试, 只快照一次
                val seenPdfPages = mutableSetOf<String>()
                toolTrace.forEachIndexed { i, (name, arg) ->
                    if (name == "pdf_page") {
                        val key = pdfPageKey(arg)
                        if (key == null || !seenPdfPages.add(key)) return@forEachIndexed
                        sinkPdfPage(context, arg, pdfDir, lastSeq.toInt(), i)
                            ?.let { imgRefs += "![](kb_images/$it)" }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pdf page pool failed, continue without pdf pages", t)
            }
            val finalContent = if (imgRefs.isEmpty()) entryContent else entryContent + "\n\n" + imgRefs.joinToString("\n")
            db.createEntry(chapterId, entryTitle, finalContent, sessionId, srcIds)
            // 书列表按最近更新倒序: 沉淀落库后刷新书时间戳
            db.touchBook(bid)
            // 推进游标(幂等 MAX)
            db.advanceCursor(sessionId, bid, lastSeq)
            Log.i(TAG, "sinked entry=$entryTitle book=$bookTitle chapter=$chapterTitle cursor->$lastSeq")
        }
    }

    /**
     * 图片池单图: 从附件库压缩复制到 kb_images/(最长边≤1280, JPEG 80), 返回落盘文件名。
     * 非图片/读取失败返回 null(静默跳过, 不阻塞沉淀)。
     */
    private fun sinkImage(context: Context, attRef: String, dir: File, seq: Int, idx: Int): String? {
        val name = attRef.removePrefix("att://")
        val src = AttachmentStore.fileOf(context, name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = File(dir, "kb_${seq}_$idx.jpg")
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (t: Throwable) {
            bmp.recycle()
            return null
        }
        bmp.recycle()
        return out.name
    }

    /**
     * PDF 页快照: 解析 pdf_page 工具参数(name=att://xxx.pdf, page=1-based), 渲染该页 JPEG 到 kb_images/。
     * 解析失败/文件缺失/页越界返回 null(静默跳过, 不阻塞沉淀)。
     */
    /** PDF 页快照去重 key: "附件名|页码"; 参数非法返回 null */
    private fun pdfPageKey(arg: String): String? {
        val o = try { JSONObject(arg) } catch (e: Exception) { return null }
        val attRef = o.optString("name", "").removePrefix("att://")
        val page = o.optInt("page", -1)
        return if (attRef.isBlank() || page < 1) null else "$attRef|$page"
    }

    private fun sinkPdfPage(context: Context, arg: String, dir: File, seq: Int, idx: Int): String? {
        val o = try { JSONObject(arg) } catch (e: Exception) { return null }
        val attRef = o.optString("name", "")
        val page = o.optInt("page", -1)
        if (attRef.isBlank() || page < 1) return null
        val name = attRef.removePrefix("att://")
        val src = AttachmentStore.fileOf(context, name) ?: return null
        val (jpeg, total) = renderPdfPageJpeg(src, page - 1, 1600)
        if (jpeg == null || jpeg.isEmpty()) return null
        val out = File(dir, "kb_pdf_${seq}_${idx}.jpg")
        return try {
            out.writeBytes(jpeg)
            out.name
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 辅助AI一次调用: 判断有无可沉淀内容 + 提炼条目 + 章节归类, 低温度 JSON 输出。
     * 返回 null 表示调用/解析失败(游标不动, 下轮重试)。
     */
    private fun callSinkAi(transcript: String, catalog: String): JSONObject? {
        val url = ApiConfig.chatUrl()
        val key = ApiConfig.apiKey()
        if (key.isBlank()) {
            Log.w(TAG, "no api key, skip sink")
            return null
        }
        val model = ApiConfig.model()
        val providerId = ApiConfig.providerId()
        val authType = ApiConfig.authTypeOf(providerId)
        val authHeader = ApiConfig.authHeaderOf(providerId)
        val headers = when (authType) {
            "x-api-key", "custom" -> mapOf(authHeader to key)
            else -> ApiClient.bearer(key)
        }
        val sys = buildString {
            append("你是知识沉淀助手。下面是一段用户与 AI 的对话片段(每条带 role 标注)。\n")
            append("请判断其中是否有可沉淀的知识内容。只收四类:\n")
            append("1. 明确的结论/判断\n2. 概念/定义\n3. 方法/框架\n4. 纠错/认知更新\n")
            append("不收: 寒暄、闲聊、过程性讨论、重复已知内容。\n")
            append("若有内容: 提炼成一条知识条目(MD 正文, 简洁、保留关键信息), 并做归类:\n")
            append("- 书 = 话题容器(长期存在): 判断这段内容属于哪个话题。若与已有书是同一话题, 则 book 填该书书名(保持一致)且 is_new_book=false; 全新话题则起简洁新书名且 is_new_book=true。\n")
            append("- 章 = 本次沉淀的批次: 章名用本次内容的切入点, 简短具体(能与其他章区分, 如 \"withContext 实战用法\"、\"协程取消与超时\")。同一话题不同时间沉淀是不同章。\n")
            append("- 条目标题也要具体, 与章呼应, 一眼看出讲了什么。\n")
            append("已有书目录:\n")
            append(if (catalog.isBlank()) "(空, 尚无任何书)" else catalog)
            append("\n只输出 JSON, 不要 markdown 围栏, 不要多余解释:\n")
            append("{\"has_content\":true,\"book\":\"书名\",\"is_new_book\":true,\"chapter\":\"章名\",\"chapter_keywords\":\"关键词,逗号分隔\",\"chapter_summary\":\"本章一句话摘要\",\"entry_title\":\"条目标题\",\"entry_content\":\"MD正文\"}\n")
            append("无内容时只输出: {\"has_content\":false}")
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", "对话片段:\n$transcript"))
            })
            put("temperature", 0.1)
            put("stream", false)
        }.toString()
        val res = ApiClient.postJson(url, body, headers, readMs = SINK_TIMEOUT_MS).getOrElse { e ->
            Log.w(TAG, "sink ai postJson failed: ${e.message}")
            return null
        }
        val content = res.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?: ""
        if (content.isBlank()) {
            Log.w(TAG, "sink ai empty content: $res")
            return null
        }
        return parseContentJson(content)
    }

    /** 容错解析: 去 ```json 围栏, 只取首个 '{' 到末个 '}' 之间的 JSON */
    private fun parseContentJson(s: String): JSONObject? {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            t = t.removeSuffix("```").trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(t.substring(start, end + 1))
        } catch (e: Exception) {
            Log.w(TAG, "sink ai json parse failed: ${e.message}")
            null
        }
    }
}
