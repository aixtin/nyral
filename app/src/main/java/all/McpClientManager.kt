package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MCP(Model Context Protocol) Streamable HTTP 客户端。
 *
 * 语义: 远程 MCP server(如 MT 管理器的 APK 分析服务)暴露一组工具,
 * 本类负责: 握手(initialize) → 拉工具清单(listTools) → 调用(tools/call)。
 * 从 server 拉到的工具运行时注册进 toolRegistry, 实现"工具动态挂入"。
 *
 * 已知约定:
 *  - 协议版本 2025-06-18(MT APK MCP 实测支持)
 *  - 响应通常为纯 JSON, 兼容 SSE(data: {...}) 两种形态
 *  - MT 服务无状态(不返回 Mcp-Session-Id), 故不做 session 管理, 每次请求独立
 *  - 工具名冲突: 默认用 server 原始工具名; 若与内置/已注册重名则加前缀 "<serverName>_"
 */
object McpClientManager {

    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 60000

    private var loaded = false
    private var lastError: String? = null

    /** serverName -> 该 server 的工具清单 */
    private val toolsByServer = mutableMapOf<String, List<McpTool>>()
    /** toolName -> 工具(含归属 server 的连接信息), 供调用时分发 */
    private val toolsByName = mutableMapOf<String, McpTool>()

    data class McpTool(
        val name: String,
        val description: String,
        val schema: JSONObject,
        val serverName: String,
        val serverUrl: String,
        val serverToken: String?,
        val origName: String = ""   // 服务器端原始工具名(不带本地前缀), 调用时须用它
    )

    // ===== 生命周期 =====

    fun clearCache() {
        loaded = false
        lastError = null
        toolsByServer.clear()
        toolsByName.clear()
    }

    fun lastError(): String? = lastError
    fun isLoaded(): Boolean = loaded
    fun hasTools(): Boolean = toolsByName.isNotEmpty()

    /** 进程内只拉取一次; 首次失败记录错误, 下次 chat 仍可重试 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        reload(context)
    }

    /** 强制重拉(设置页保存/测试后调用) */
    fun reload(context: Context) {
        toolsByServer.clear()
        toolsByName.clear()
        loaded = false
        lastError = null
        val servers = McpConfigStore.load(context)
        val usedNames = mutableSetOf<String>()
        for (s in servers) {
            if (s.url.isBlank()) continue
            try {
                val tools = listTools(s.url, s.token)
                toolsByServer[s.name] = tools
                var added = 0
                // 统一前缀: 无论是否重名, 每个工具都带「消毒后的服务名_」前缀,
                // 保证工具名一定能回溯到所属 MCP 服务, 多服务时模型靠前缀区分
                val prefix = sanitizeName(s.name)
                for (t in tools) {
                    var finalName = if (prefix.isEmpty()) t.name else "${prefix}_${t.name}"
                    // 防御: 极端情况下(如两个服务名消毒后相同)仍防撞名
                    while (!usedNames.add(finalName)) finalName = "${finalName}_2"
                    toolsByName[finalName] = t.copy(name = finalName, serverName = s.name)
                    added++
                }
                lastError = lastError ?: null
            } catch (e: Exception) {
                lastError = (lastError?.let { "$it; " } ?: "") + "「${s.name}」${e.message}"
            }
        }
        loaded = true
    }

    /** 服务名 → 工具名前缀: 保留字母/数字/中文/下划线, 其余转下划线, 去首尾下划线 */
    private fun sanitizeName(s: String): String {
        val sb = StringBuilder()
        for (c in s.trim()) {
            sb.append(if (c.isLetterOrDigit() || c == '_') c else '_')
        }
        return sb.toString().trim('_')
    }

    fun serverCount(context: Context): Int = McpConfigStore.load(context).count { it.url.isNotBlank() }

    // ===== 供 LocalEngine 集成 =====

    /** system 工具索引用的 name+描述行(描述前缀标注归属服务, 模型靠它区分多服务) */
    fun indexLines(): List<Pair<String, String>> =
        toolsByName.entries.map { (n, t) ->
            n to "[MCP服务「${t.serverName}」] ${t.description}"
        }

    /** MCP 服务概览行: 服务名 / URL / 工具数 / 前缀示例, 注入 system prompt 首部让模型知道有哪些服务 */
    fun serverOverview(): List<String> =
        toolsByServer.entries.map { (sn, tools) ->
            val prefix = sanitizeName(sn)
            val ex = tools.firstOrNull()?.let { "${prefix}_${it.name}" } ?: "-"
            "「$sn」${tools.size}个工具(前缀 ${prefix.ifEmpty { "无" }}, 如 $ex)"
        }

    /** 完整工具规格(name, desc, paramsJson), 首次调用时补全注入 */
    fun specEntries(): List<Triple<String, String, String>> =
        toolsByName.entries.map { (n, t) -> Triple(n, t.description, t.schema.toString()) }

    fun spec(name: String): Triple<String, String, String>? {
        val t = toolsByName[name] ?: return null
        return Triple(name, t.description, t.schema.toString())
    }

    // ===== 协议实现 =====

    /** 拉取单个 server 的全部工具 */
    private fun listTools(url: String, token: String?): List<McpTool> {
        // 1. initialize 握手
        val init = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "Nyral")
                    put("version", "1.0")
                })
            })
        }
        rpc(url, token, init)
        // 2. initialized 通知(fire-and-forget, 短超时, 失败忽略)
        try {
            sendNotification(url, token, "notifications/initialized")
        } catch (_: Exception) { }
        // 3. 工具清单
        val list = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
            put("params", JSONObject())
        }
        val res = rpc(url, token, list)
        val result = res.getJSONObject("result")
        val tools = result.optJSONArray("tools") ?: JSONArray()
        val out = mutableListOf<McpTool>()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            val schema = t.optJSONObject("inputSchema") ?: JSONObject()
            out.add(McpTool(
                name = t.optString("name"),
                description = t.optString("description", ""),
                schema = schema,
                serverName = "",
                serverUrl = url,
                serverToken = token,
                origName = t.optString("name")
            ))
        }
        return out
    }

    /** 测试连接: 握手+列工具, 成功返回工具数描述, 失败抛异常 */
    fun testConnection(url: String, token: String?): String {
        val tools = listTools(url, token)
        return "连接成功, 共 ${tools.size} 个工具: " +
            tools.joinToString(", ") { it.name }.take(200)
    }

    /** 调用工具, 返回文本结果(供 executeTool 分发) */
    fun callTool(context: Context, toolName: String, arg: String): String {
        val t = toolsByName[toolName]
            ?: return "MCP 工具未加载: $toolName (请检查设置→MCP 服务配置与连接)"
        return try {
            val arguments = if (arg.isBlank()) JSONObject() else {
                val trimmed = arg.trim()
                if (trimmed.startsWith("{")) JSONObject(trimmed)
                else JSONObject().put("value", trimmed)
            }
            val call = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 3)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    // 关键: 发给服务器必须是原始工具名(不带本地服务名前缀), 否则服务器回 Unknown tool
                    put("name", t.origName.ifEmpty { t.name })
                    put("arguments", arguments)
                })
            }
            val res = rpc(t.serverUrl, t.serverToken, call)
            val result = res.getJSONObject("result")
            val isError = result.optBoolean("isError", false)
            val content = result.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                sb.append(c.optString("text", ""))
                if (i < content.length() - 1 && sb.isNotEmpty()) sb.append("\n")
            }
            val text = sb.toString().ifEmpty { "(无返回内容)" }
            if (isError) "MCP 工具执行失败: $text" else text
        } catch (e: Exception) {
            "MCP 调用异常: ${e.message}"
        }
    }

    // ===== 底层 JSON-RPC =====

    /** POST JSON-RPC, 返回完整响应体 JSON */
    private fun rpc(url: String, token: String?, payload: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("Mcp-Protocol-Version", PROTOCOL_VERSION)
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val body = payload.toString()
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(200)}")
            val json = parseJson(text)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                throw RuntimeException("MCP error ${err.optString("code")}: ${err.optString("message")}")
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /** 通知类消息: 不等待响应, 短超时, 独立连接 */
    private fun sendNotification(url: String, token: String?, method: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", JSONObject())
            }.toString()
            val bytes = payload.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            conn.inputStream?.close()
        } finally {
            conn.disconnect()
        }
    }

    /** 兼容纯 JSON 与 SSE(data: ...) 两种响应形态 */
    private fun parseJson(text: String): JSONObject {
        val s = text.trim()
        if (s.startsWith("{")) return JSONObject(s)
        val sb = StringBuilder()
        s.lines().forEach { line ->
            val l = line.trim()
            if (l.startsWith("data:")) {
                val v = l.removePrefix("data:").trim()
                if (v.isNotEmpty() && v != "[DONE]") sb.append(v)
            }
        }
        if (sb.isNotEmpty()) return JSONObject(sb.toString())
        throw RuntimeException("无法解析 MCP 响应: ${text.take(200)}")
    }
}
