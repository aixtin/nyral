package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * LocalEngine: 本地两步式路由 + 流式输出 + 工具调用循环 (v2.0)
 *
 * 流式分段渲染 (对标 assistant 交互):
 *  模型按约定输出 -> 思考段(打字机) -> 收缩 -> 工具调用 / 正文(流式)
 *  分段格式: 行前缀 "思考:" 思考段 / "答案:" 正文段 / "TOOL:" 工具行
 *  兼容: 模型不按格式输出时, 全部当正文流式渲染
 */
object LocalEngine {

    // 40: 适配扫描项目/批量检索类长任务(40 次足够覆盖 workdir_grep->head->read->write->upload 全链路)
    private const val MAX_TOOL_CALLS = 40

    /** 发送附件: mime 类型 + Base64 内容 + 文件名; 图片走 image_url, 音频走 input_audio, 其余走 input_file;
     *  text 为附件本地解析出的纯文本(如 PDF 提取内容), 非空时随 history 一并注入给模型;
     *  isVoice 标记该音频来自本地录音(需展示微信式语音气泡), 上传的音频文件为 false(展示为文件卡片) */
    data class Attachment(val mime: String, val base64: String, val name: String = "attachment", val text: String? = null, val isVoice: Boolean = false, val pdfSourceName: String? = null)

    /** 取消状态: requestCancel() 置 true, 引擎在流式读取/工具循环处检查并中断 */
    @Volatile
    var cancelRequested = false

    /** 当前活跃连接, 取消时 disconnect 以打断阻塞读 */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 请求停止当前 AI 输出/工具循环 */
    fun requestCancel() {
        cancelRequested = true
        activeConn?.disconnect()
    }

    private val toolRegistry = listOf(
        ToolSpec("web_search", "联网搜索(Bing), 返回结果标题+链接+摘要", "JSON: {\"q\":\"搜索关键词\",\"max_results\":5}"),
        ToolSpec("web_fetch", "抓取网页并提取正文文本; 若 site_auth.json 已配置该域名 Cookie 会自动注入, 无需重复传", "JSON: {\"url\":\"https://...\",\"max_chars\":3000}"),
        ToolSpec("site_auth", "管理站点登录凭据(存 site_auth.json, 供 web_fetch/web_download 自动注入 Cookie)", "JSON: {\"action\":\"list\"} 或 {\"action\":\"set\",\"site\":\"域名\",\"cookie\":\"完整Cookie字符串\"} 或 {\"action\":\"del\",\"site\":\"域名\"}"),
        ToolSpec("get_time", "获取当前日期时间", "无参数"),
        ToolSpec("calc", "数学计算", "表达式, 如 17*23"),
        ToolSpec("memory_search", "语义检索本地记忆", "查询内容"),
        ToolSpec("ssh_run", "通过SSH在远程主机执行命令, 格式: 连接名:命令(连接名见下方可用SSH连接); 经跳板机(标注\"经跳板\")的连接只需指定连接名, 跳板自动处理, 不要自行添加跳板参数", "如 vps:ls / 或 dev188:free -h"),
        ToolSpec("file_list", "列出远程目录文件", "JSON: {\"conn\":\"连接名\",\"path\":\"/目录\"}"),
        ToolSpec("file_read", "读取远程文件内容", "JSON: {\"conn\":\"连接名\",\"path\":\"/文件\",\"lines\":200}"),
        ToolSpec("file_info", "查看远程文件详情(类型/大小/权限/修改时间)", "JSON: {\"conn\":\"连接名\",\"path\":\"/文件\"}"),
        ToolSpec("file_write", "写入或追加远程文件内容", "JSON: {\"conn\":\"连接名\",\"path\":\"/文件\",\"content\":\"内容\",\"append\":false}"),
        ToolSpec("ssh_upload", "SFTP上传: 把手机工作目录文件传到远端", "JSON: {\"conn\":\"连接名\",\"local\":\"工作目录文件名\",\"remote\":\"/远端/绝对/路径\"}"),
        ToolSpec("ssh_download", "SFTP下载: 把远端文件拉到手机工作目录", "JSON: {\"conn\":\"连接名\",\"remote\":\"/远端/绝对/路径\",\"local\":\"可选本地文件名(默认取远端文件名)\"}"),
        ToolSpec("ssh_ls", "SFTP列远端目录(一级)", "JSON: {\"conn\":\"连接名\",\"path\":\"/目录\"}"),
        ToolSpec("web_download", "下载网页/文件并保存到手机工作目录; 返回\"下载成功\"即表示文件已落盘, 直接向用户报告结果, 不要再调用 workdir_list 等工具重复验证; site_auth.json 已配置的域名 Cookie 会自动注入", "JSON: {\"url\":\"https://...\",\"name\":\"可选文件名\"}"),
        ToolSpec("workdir_list", "列出手机工作目录(Download/agent_work)文件", "无参数"),
        ToolSpec("workdir_read", "读取手机工作目录文本文件内容", "JSON: {\"name\":\"文件名\"}"),
        ToolSpec("workdir_write", "写入手机工作目录文本文件(同名覆盖)", "JSON: {\"name\":\"文件名\",\"content\":\"内容\"}"),
        ToolSpec("workdir_grep", "全文搜索工作目录文本文件(批量, 一次代替多次 workdir_read); 扫描项目/找关键词优先用它", "JSON: {\"kw\":\"关键词\",\"ext\":\"可选按扩展名过滤如 .kt\",\"case\":false}"),
        ToolSpec("workdir_head", "读工作目录文件前 N 行/前 N 字符(批量查看, 代替全文读取防上下文爆炸)", "JSON: {\"name\":\"文件名\",\"lines\":50} 或 {\"name\":\"文件名\",\"chars\":3000}"),
        ToolSpec("workdir_stats", "工作目录统计概览(文件数/总大小/按类型分布), 扫描前先看全貌", "无参数")
    )

    interface Callback {
        fun onThinkingStart()          // 思考段开始
        fun onThinkingDelta(text: String)  // 思考内容增量(打字机)
        fun onThinkingEnd()            // 思考段完成 -> UI 收缩
        fun onTool(name: String, arg: String)  // 正在执行工具
        fun onToolResult(name: String, result: String) {}  // 工具执行完成(结果回填, 默认空实现)
        fun onDelta(text: String)      // 正文增量(流式)
        fun onDone(reply: String)
        fun onError(msg: String)
    }

    /** 流式入口: 主线程调用, 回调全部发生在调用线程(工作线程), UI 需自行 post */
    fun chat(context: Context, history: String, cb: Callback, attachments: List<Attachment> = emptyList()) {
        try {
            if (ApiConfig.apiKey().isBlank() && !ApiConfig.isCurrentLocal()) {
                cb.onError("未配置 API Key, 请先到「设置 → 模型配置」填写")
                return
            }
            if (ApiConfig.isCurrentLocal()) {
                cb.onError("本地 GGUF 推理引擎待接入，请先在模型配置中切换回 API 模型")
                return
            }
            val messages = buildRouteMessages(context, history, attachments)
            var toolCount = 0
            var retriedEmpty = false
            val full = StringBuilder()

            while (true) {
                if (cancelRequested) {
                    cb.onDone("")
                    return
                }
                val res = streamOnce(context, messages, cb)
                val toolCall = res.toolCall
                if (toolCall != null) {
                    if (toolCount >= MAX_TOOL_CALLS) {
                        cb.onError("工具调用超过 $MAX_TOOL_CALLS 次, 已停止")
                        return
                    }
                    toolCount++
                    val (name, arg) = toolCall
                    cb.onTool(name, arg)
                    val result = executeTool(context, name, arg)
                    cb.onToolResult(name, result)
                    // 累积对话: assistant 已输出内容(含 TOOL 行) + 工具结果, 保证上下文完整
                    val asstContent = if (res.accumulated.isBlank()) "TOOL:$name|$arg"
                        else res.accumulated.trimEnd() + "\nTOOL:$name|$arg"
                    messages.put(JSONObject().put("role", "assistant").put("content", asstContent))
                    messages.put(JSONObject().put("role", "user").put("content",
                        "工具结果: $result\n" +
                        "请继续: 若还需要调用工具, 输出 TOOL:工具名|参数; 若已能回答用户, 直接输出最终答案。"))
                    continue
                }
                if (toolCall == null && res.accumulated.isBlank() && toolCount > 0 && !retriedEmpty) {
                    // 模型对工具结果无有效输出: 引导重试一次, 避免"调了工具却没结果"
                    retriedEmpty = true
                    messages.put(JSONObject().put("role", "user").put("content",
                        "你刚才没有输出任何内容。请直接根据已知信息回答用户, 如仍需工具请输出 TOOL:工具名|参数。"))
                    continue
                }
                full.append(res.accumulated)
                cb.onDone(full.toString())
                return
            }
        } catch (e: Exception) {
            if (cancelRequested) {
                // 用户主动停止: 不视为错误
                android.util.Log.i("agent", "chat cancelled by user")
                cb.onDone("")
                return
            }
            android.util.Log.e("agent", "chat error", e)
            cb.onError(e.message ?: "未知错误")
        }
    }

    /** 单次 SSE 流式请求, 返回累积文本 + (可选)工具调用 */
    private fun streamOnce(context: Context, messages: JSONArray, cb: Callback): StreamResult {
        val body = JSONObject()
        body.put("model", ApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.3)
        body.put("stream", true)
        // DeepSeek 支持流式返回真实 usage(最后一块); 其余厂商未知, 不加避免报错, 靠本地估算
        if (ApiConfig.providerId() == "deepseek") {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        // 思考强度: 六档按当前供应商/模型映射厂商参数; 不支持/自动 时返回 null 不传
        ApiConfig.thinkingEffortParams(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.thinkingEffortOf(ApiConfig.providerId()))?.let { params ->
            val keys = params.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                body.put(k, params.get(k))
            }
        }
        // token 统计: 无法拿到真实 usage 时用字符估算(中文约 1 token/字符, 英文约 4 字符/token, 取 /3 折中)
        val promptEst = body.toString().length / 3
        var usedPrompt = 0L
        var usedCompletion = 0L

        val conn = URL(ApiConfig.chatUrl()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${ApiConfig.apiKey()}")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        activeConn = conn
        val accumulated = StringBuilder()
        var toolCall: Pair<String, String>? = null
        try {
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            // errorStream 可能为 null(网络层异常无响应体), 直接包 BufferedReader 会 NPE
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "(无响应体)"
            conn.disconnect()
            throw RuntimeException("API $code: $err")
        }
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val lineBuf = StringBuilder()
        var mode = MODE_NONE
        var aborted = false

        try {
        while (true) {
            if (cancelRequested) throw CancellationException("cancelled by user")
            val line = reader.readLine() ?: break
            android.util.Log.i("agent", "SSE: $line")
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val parsed = try { JSONObject(data) } catch (e: Exception) { null } ?: continue
            val choices = parsed.optJSONArray("choices")
            val usage = parsed.optJSONObject("usage")
            // 流式结束的 usage chunk: 携带真实 token 数(DeepSeek 在 finish_reason=stop 的块返回 usage)
            if (usage != null && usage.optLong("prompt_tokens", 0) > 0) {
                usedPrompt = usage.optLong("prompt_tokens", 0)
                usedCompletion = usage.optLong("completion_tokens", 0)
            }
            val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
            // 推理模型(如小米MiMo): 思考内容在 reasoning_content, 正文在 content
            // 注意: org.json 的 optString 对 JSON null 返回字符串 "null", 必须用 isNull 判空
            val rText = if (delta != null && !delta.isNull("reasoning_content")) delta.optString("reasoning_content") else ""
            if (rText.isNotEmpty()) {
                if (mode != MODE_THINKING) {
                    mode = MODE_THINKING
                    cb.onThinkingStart()
                }
                // 思考内容只进思考区, 绝不累积进正文/reply
                cb.onThinkingDelta(rText)
                continue
            }
            val text = if (delta != null && !delta.isNull("content")) delta.optString("content") else ""
            if (text.isEmpty()) continue
            // content 出现即思考结束(API 侧思考段已完结, content 为正文)
            if (mode == MODE_THINKING) {
                mode = MODE_NONE
                cb.onThinkingEnd()
            }

            lineBuf.append(text)
            // 按完整行处理
            while (true) {
                val nl = lineBuf.indexOf("\n")
                if (nl < 0) break
                // 只去行尾空白, 保留行首缩进(嵌套列表/缩进代码块是 Markdown 语法的一部分)
                val row = lineBuf.substring(0, nl).trimEnd()
                lineBuf.delete(0, nl + 1)
                if (row.isBlank()) {
                    // 正文阶段的空行 = Markdown 段落分隔, 必须下发, 否则段落全被粘成一段
                    if (mode == MODE_CONTENT) { cb.onDelta("\n"); accumulated.append('\n') }
                    continue
                }
                val stop = processRow(row, mode, accumulated, cb) { m -> mode = m }
                if (stop != null) { toolCall = stop; break }
            }
            if (toolCall != null) break
        }
        } catch (e: SocketException) {
            // 服务端/网络中途重置连接: 保留已输出内容, 不中断任务
            aborted = true
            android.util.Log.w("agent", "SSE connection aborted: ${e.message}")
        } catch (e: IOException) {
            aborted = true
            android.util.Log.w("agent", "SSE read error: ${e.message}")
        }
        // 连接中断收尾: 已有正文/工具调用则保留正常继续, 无任何输出则转为可读错误
        if (aborted) {
            if (mode == MODE_THINKING) cb.onThinkingEnd()
            if (toolCall == null && accumulated.isBlank()) {
                throw RuntimeException("模型连接中断(网络波动)，请重试")
            }
            if (toolCall == null && accumulated.isNotBlank()) {
                cb.onDelta("\n\n[连接中断，以上内容已保留]")
            }
        }
        // 末尾残余(无换行的最后一段)
        android.util.Log.i("agent", "EOF lineBuf=[$lineBuf] mode=$mode toolCall=$toolCall aborted=$aborted")
        if (toolCall == null && lineBuf.isNotBlank()) {
            val stop = processRow(lineBuf.toString().trimEnd(), mode, accumulated, cb) { m -> mode = m }
            if (stop != null) toolCall = stop
        }
        android.util.Log.i("agent", "streamOnce done acc=[$accumulated] toolCall=$toolCall")
        // 思考段自然结束
        if (toolCall == null && mode == MODE_THINKING) cb.onThinkingEnd()
        // token 统计: 有真实 usage 用真实值, 否则本地估算(仅成功请求计入)
        val promptTokens = if (usedPrompt > 0) usedPrompt else promptEst.toLong()
        val completionTokens = if (usedCompletion > 0) usedCompletion else accumulated.length / 3L
        TokenStore.record(context, promptTokens.toInt().coerceAtLeast(0), completionTokens.toInt().coerceAtLeast(0))
        reader.close()
        conn.disconnect()
        } finally {
            activeConn = null
        }
        return StreamResult(accumulated.toString(), toolCall)
    }

    /** 处理一行输出, 返回 null 继续, 返回 Pair 表示命中工具行需打断 */
    private fun processRow(
        row: String,
        mode: Int,
        accumulated: StringBuilder,
        cb: Callback,
        setMode: (Int) -> Unit
    ): Pair<String, String>? {
        when {
            row.startsWith("TOOL:") || row.startsWith("TOOL：") -> {
                if (mode == MODE_THINKING) cb.onThinkingEnd()
                setMode(MODE_NONE)
                return parseToolSpec(row.substring(5))
            }
            mode == MODE_NONE -> {
                when {
                    row.startsWith("思考:") || row.startsWith("思考：") -> {
                        if (ApiConfig.thinkingEffortOf(ApiConfig.providerId()) == ApiConfig.THINK_OFF) {
                            setMode(MODE_NONE)
                            return null
                        }
                        setMode(MODE_THINKING)
                        cb.onThinkingStart()
                        val body = row.substring(3).trim()
                        // 容错: 模型可能把 TOOL 嵌在思考行内, 拆分
                        val ti = body.indexOf("TOOL:")
                        if (ti >= 0) {
                            val pre = body.substring(0, ti).trim()
                            if (pre.isNotEmpty()) cb.onThinkingDelta(pre)
                            cb.onThinkingEnd()
                            return parseToolSpec(body.substring(ti + 5))
                        }
                        if (body.isNotEmpty()) cb.onThinkingDelta(body)
                    }
                    row.startsWith("答案:") || row.startsWith("答案：") -> {
                        setMode(MODE_CONTENT)
                        val t = row.substring(3).trim()
                        // 行尾换行随内容一起下发: UI 逐行拼接缺换行会把所有行粘成一行, Markdown 失效
                        if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                    }
                    else -> {
                        setMode(MODE_CONTENT)
                        cb.onDelta(row + "\n")
                        accumulated.append(row).append('\n')
                    }
                }
            }
            mode == MODE_THINKING -> {
                when {
                    row.startsWith("答案:") || row.startsWith("答案：") -> {
                        setMode(MODE_CONTENT)
                        cb.onThinkingEnd()
                        val t = row.substring(3).trim()
                        if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                    }
                    else -> {
                        // 容错: 模型可能把"答案:"或"TOOL:"嵌在思考行中间(未换行)
                        val ti = row.indexOf("TOOL:")
                        if (ti > 0) {
                            val pre = row.substring(0, ti)
                            if (pre.isNotBlank()) cb.onThinkingDelta(pre)
                            cb.onThinkingEnd()
                            return parseToolSpec(row.substring(ti + 5))
                        }
                        val idx = row.indexOf("答案:")
                        if (idx > 0) {
                            val pre = row.substring(0, idx)
                            if (pre.isNotBlank()) cb.onThinkingDelta(pre)
                            setMode(MODE_CONTENT)
                            cb.onThinkingEnd()
                            val t = row.substring(idx + 3).trim()
                            if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                        } else {
                            cb.onThinkingDelta(row)
                        }
                    }
                }
            }
            else -> { // CONTENT
                cb.onDelta(row + "\n")
                accumulated.append(row).append('\n')
            }
        }
        return null
    }

    /** 解析 TOOL: 后的规格: name|arg / name arg / name{json} / name:arg 全兼容 */
    private fun parseToolSpec(spec: String): Pair<String, String> {
        val t = spec.trim()
        val name = t.substringBefore("|").substringBefore(" ").substringBefore("{").trim()
        var arg = t.substringAfter("|", "").trim()
        if (arg.isEmpty()) {
            // 非竖线格式: 取工具名之后的所有内容作为参数
            val rest = t.removePrefix(name).trim()
            if (rest.isNotEmpty()) arg = rest.removePrefix("|").trim()
        }
        return normalizeToolName(name) to arg
    }

    private fun buildRouteMessages(context: Context, history: String, attachments: List<Attachment> = emptyList()): JSONArray {
        val messages = JSONArray()
        val sshList = SshConfigStore.load(context).joinToString("\n") {
            "- ${it.name}: ${it.user}@${it.host}:${it.port}" +
                (if (it.hasProxy) " (经跳板 ${it.proxyHost}:${it.proxyPort})" else "")
        }
        val sshHint = if (sshList.isBlank()) "无(可在SSH配置中添加)" else "\n$sshList"
        val thinkingOff = ApiConfig.thinkingEffortOf(ApiConfig.providerId()) == ApiConfig.THINK_OFF
        val fmtRules = if (thinkingOff) {
            "输出格式约定(重要):\n" +
            "1. 直接回答, 禁止输出『思考:』前缀或任何思考过程;\n" +
            "2. 需要调用工具时, 新起一行『TOOL:工具名|参数』;\n" +
            "3. 需要正式回答时, 新起一行『答案:』后输出正文;\n" +
            "4. 无需工具直接回答时, 直接输出正文, 不要前缀。\n" +
            "收到工具结果后可继续输出『TOOL:』或『答案:』。一次只能一个TOOL。"
        } else {
            "输出格式约定(重要):\n" +
            "1. 需要先思考时, 第一行以『思考:』开头输出思考内容, 一行一句;\n" +
            "2. 思考结束要调用工具时, 新起一行『TOOL:工具名|参数』;\n" +
            "3. 思考结束要回答时, 新起一行『答案:』后输出正文;\n" +
            "4. 无需思考直接回答时, 直接输出正文, 不要前缀。\n" +
            "收到工具结果后可继续『思考:』『TOOL:』或『答案:』。一次只能一个TOOL。"
        }
        messages.put(JSONObject().put("role", "system").put("content",
            "你是agent。根据用户需求选择工具。工具清单:\n" +
            toolRegistry.joinToString("\n") { "- ${it.name}: ${it.desc} (参数: ${it.params})" } +
            "\n可用SSH连接:$sshHint\n" + fmtRules))
        if (attachments.isEmpty()) {
            messages.put(JSONObject().put("role", "user").put("content", history))
        } else {
            // 多模态: content 为 parts 数组, 文本在前, 附件按类型映射为标准 OpenAI 兼容格式
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", history))
            for (a in attachments) {
                when {
                    a.mime.startsWith("image/") ->
                        parts.put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.base64}")))
                    a.mime.startsWith("audio/") -> {
                        // format/MIME 归一化: m4a/mp4/x-m4a→m4a, x-wav/wave→wav, mp3/mpeg→mp3, x-flac→flac, opus→ogg
                        val norm: Pair<String, String> = when {
                            a.mime.startsWith("audio/wav") || a.mime.startsWith("audio/x-wav") || a.mime.startsWith("audio/wave") -> "audio/wav" to "wav"
                            a.mime.startsWith("audio/mpeg") || a.mime.startsWith("audio/mp3") -> "audio/mpeg" to "mp3"
                            a.mime.startsWith("audio/mp4") || a.mime.startsWith("audio/x-m4a") || a.mime.startsWith("audio/m4a") -> "audio/mp4" to "m4a"
                            a.mime.startsWith("audio/flac") || a.mime.startsWith("audio/x-flac") -> "audio/flac" to "flac"
                            a.mime.startsWith("audio/ogg") || a.mime.startsWith("audio/opus") -> "audio/ogg" to "ogg"
                            else -> a.mime to a.mime.removePrefix("audio/").substringBefore("+").substringBefore(";")
                        }
                        parts.put(JSONObject().put("type", "input_audio")
                            .put("input_audio", JSONObject().put("data", "data:${norm.first};base64,${a.base64}").put("format", norm.second)))
                    }
                    a.mime.startsWith("video/") -> {
                        // MiMo 视频理解: content 数组 type=video_url, url 用 data:{mime};base64 内联
                        // 官方限制: base64 后 ≤50MB(原始约 ≤37MB), 支持 MP4/MOV/AVI/WMV
                        val vurl = JSONObject().put("url", "data:${a.mime};base64,${a.base64}")
                        parts.put(JSONObject().put("type", "video_url")
                            .put("video_url", vurl)
                            .put("fps", 2)
                            .put("media_resolution", "default"))
                    }
                    else -> {
                        // 文档类附件(PDF/Office/txt 等): 本地已提取纯文本并随 history 注入时不再发二进制
                        // (MiMo 不支持 input_file 会直接 500); 发送前在 MainActivity 已保证:
                        //   提取成功 -> 文本注入 history, 不发二进制
                        //   提取失败 -> PDF 渲染为图片 / 或直接拒绝发送
                        // 故此处仅对异常透传的文档做文字占位, 绝不回退 input_file
                        if (a.text.isNullOrBlank()) {
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[附件 ${a.name} 无法解析文本内容]"))
                        }
                    }
                }
            }
            // 若所有附件都只有本地解析文本(parts 仅剩 text 一项): 某些模型对纯文本 content 数组会 500,
            // 且附件文本已在 doSend 注入 history, 直接退化为纯字符串 content
            if (parts.length() == 1) {
                messages.put(JSONObject().put("role", "user").put("content", history))
            } else {
                messages.put(JSONObject().put("role", "user").put("content", parts))
            }
        }
        return messages
    }

    private fun normalizeToolName(name: String): String = name.replace(Regex("_+"), "_")

    private fun executeTool(context: Context, name: String, arg: String): String {
        return when (name) {
            "get_time" -> MemoryTools.getTime()
            "calc" -> MemoryTools.calc(arg)
            "memory_search" -> MemoryTools.search(context, arg)
            "ssh_run" -> SshTools.run(context, arg)
            "file_list" -> FileTools.list(context, arg)
            "file_read" -> FileTools.read(context, arg)
            "file_info" -> FileTools.info(context, arg)
            "file_write" -> FileTools.write(context, arg)
            "ssh_upload" -> SshTools.upload(context, arg)
            "ssh_download" -> SshTools.download(context, arg)
            "ssh_ls" -> SshTools.ls(context, arg)
            "web_download" -> WebTools.save(context, arg)
            "workdir_list" -> WorkTools.list(context, arg)
            "workdir_read" -> WorkTools.read(context, arg)
            "workdir_write" -> WorkTools.write(context, arg)
            // 修复: 08-31 加了 ToolSpec 注册但漏了这里分发, 模型调用必返回"未知工具"
            "workdir_grep" -> WorkTools.grep(context, arg)
            "workdir_head" -> WorkTools.head(context, arg)
            "workdir_stats" -> WorkTools.stats(context, arg)
            "web_search" -> WebTools.search(arg)
            "web_fetch" -> WebTools.fetch(context, arg)
            "site_auth" -> WebTools.siteAuth(context, arg)
            else -> "未知工具: $name"
        }
    }

    private data class StreamResult(val accumulated: String, val toolCall: Pair<String, String>?)

    data class ToolSpec(val name: String, val desc: String, val params: String)

    private const val MODE_NONE = 0
    private const val MODE_THINKING = 1
    private const val MODE_CONTENT = 2
}
