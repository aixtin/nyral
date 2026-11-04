package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 多模态生成工具集(2026-11-04 v2 契约版): 按 GenConfig.GenContract 执行任意平台生图。
 * - 协议由契约描述: 同步/异步、prompt/chat 模板、鉴权头、取图路径, 冷门/自建平台也能跑
 * - 成本门禁: 调用本身挂 DANGER_CONFIRM_TOOLS 需用户确认; 月度限额先查后消耗(成功才计数)
 */
object GenTools {

    private const val TAG = "GenTools"
    private const val MAX_POLL_MS = 120 * 1000L

    /** 生图入口(ToolExecutor 分发): arg = {"prompt":"...","size":"1024*1024","n":1,"provider":"可选厂商id"} */
    fun genImage(context: Context, arg: String): String {
        val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
        val prompt = jo?.optString("prompt", "")?.trim().orEmpty()
        if (prompt.isEmpty()) return "错误: 缺少 prompt(要生成的画面描述), 如 {\"prompt\":\"赛博朋克城市夜景\"}"
        val size = jo?.optString("size", "1024*1024").orEmpty()
        val n = (jo?.optInt("n", 1) ?: 1).coerceIn(1, 4)
        val providerId = jo?.optString("provider", "")?.trim().orEmpty()

        // 成本门禁第一道: 月度限额检查(不消耗)
        if (!GenConfig.canConsume(context)) {
            val used = GenConfig.monthUsed(context)
            val max = GenConfig.quotaMax(context)
            return "错误: 本月 AI 生图额度已用尽($used/$max 张)。请到 设置 → AI 生成 调高月度限额, 或下月再试。"
        }

        // 选厂商: 指定 id 或默认
        val entry = if (providerId.isNotEmpty()) {
            GenConfig.entryById(context, providerId)?.takeIf { it.enabled }
                ?: return "错误: 找不到已启用的厂商配置(id=$providerId)。请到 设置 → AI 生成 检查。"
        } else {
            GenConfig.defaultEntry(context)
                ?: return "错误: 未配置任何生图厂商。请到 设置 → AI 生成 粘贴官方示例添加厂商。"
        }
        val key = GenConfig.keyOf(context, entry)
        if (key.isBlank()) {
            return "错误: 厂商 [${entry.displayName}] 未填 API Key。请到 设置 → AI 生成 补填。"
        }

        return try {
            val imgUrl = when (entry.contract.mode) {
                "async" -> asyncGenerate(entry, key, prompt, size, n)
                else -> syncGenerate(entry, key, prompt, size, n)
            } ?: return "错误: 生图请求失败(厂商 ${entry.displayName})。本次未计费, 请检查配置或稍后重试。"
            val bytes = download(imgUrl)
                ?: return "错误: 图片下载失败(厂商 ${entry.displayName})。本次未计费。"
            val fn = ArtifactStore.saveBytes(context, "gen_image_${System.currentTimeMillis()}.png", bytes)
            if (fn.startsWith("错误:")) return fn
            // 成本门禁第二道: 落盘成功才计数
            GenConfig.consume(context)
            SecurityConfig.audit(context, "gen_image", "provider=${entry.displayName} prompt=${prompt.take(40)} size=$size n=$n", "ai", "bubble")
            val used = GenConfig.monthUsed(context)
            val max = GenConfig.quotaMax(context)
            "生图成功(${bytes.size / 1024}KB, ${entry.displayName}, 本月第 $used/$max 张)。请立即在回复正文中单独一行原样输出 [产物:AI生成图片.png](att://$fn) 向用户展示图片卡片。若用户想要更多图或调整画面, 可直接再次调用。"
        } catch (e: Exception) {
            android.util.Log.e(TAG, "genImage fail", e)
            "错误: 生图失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 测试连接(设置页用): 发最小请求, 返回可读诊断。成功不计数、不落盘 */
    fun testConnection(context: Context, entry: GenConfig.Entry, key: String): String {
        val c = entry.contract
        if (c.endpoint.isBlank() && c.submitEndpoint.isBlank()) return "错误: 端点为空"
        if (c.mode == "async" && c.submitEndpoint.isBlank()) return "错误: 异步模式缺少提交端点"
        val prompt = "一只橙色小猫坐在窗台上看向镜头"
        return try {
            if (c.mode == "async") {
                val body = buildBody(c, prompt, "512*512", 1)
                val submitUrl = c.submitEndpoint.ifBlank { c.endpoint }
                val sr = request("POST", submitUrl, c, key, body.toString())
                if (sr == null) return "失败: 提交请求异常(端点不可达/超时/证书)"
                if (sr.first !in 200..299) return "失败: 提交 HTTP ${sr.first}\n${sr.second.take(200)}"
                val taskId = extractPath(JSONObject(sr.second), c.taskIdPath)
                if (taskId.isBlank()) return "失败: 提交成功但未取到任务ID(当前路径 ${c.taskIdPath})。\n响应体前200字: ${sr.second.take(200)}"
                val imgUrl = pollOnce(c, key, taskId)
                if (imgUrl.isNullOrBlank()) "失败: 轮询未到成功状态(任务ID $taskId)。请检查状态路径/成功值契约。"
                else "成功: 已连通并取到图片地址, 可保存配置后正式使用。\n${imgUrl.take(120)}"
            } else {
                val body = buildBody(c, prompt, "512*512", 1)
                val resp = request("POST", c.endpoint, c, key, body.toString())
                if (resp == null) return "失败: 请求异常(端点不可达/超时/证书)"
                if (resp.first !in 200..299) return "失败: HTTP ${resp.first}\n${resp.second.take(200)}"
                val imgUrl = extractPath(JSONObject(resp.second), c.imageUrlPath)
                if (imgUrl.isBlank()) "失败: HTTP ${resp.first} 已响应但未取到图片地址(当前路径 ${c.imageUrlPath})。\n响应体前300字: ${resp.second.take(300)}"
                else "成功: 已连通并取到图片地址, 可保存配置后正式使用。\n${imgUrl.take(120)}"
            }
        } catch (e: Exception) {
            "失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------- 同步协议 ----------
    private fun syncGenerate(entry: GenConfig.Entry, key: String, prompt: String, size: String, n: Int): String? =
        syncGenerateRaw(entry.contract, key, prompt, size, n)

    private fun syncGenerateRaw(c: GenConfig.GenContract, key: String, prompt: String, size: String, n: Int): String? {
        val body = buildBody(c, prompt, size, n)
        val resp = request("POST", c.endpoint, c, key, body.toString())
        if (resp == null || resp.first !in 200..299) {
            android.util.Log.w(TAG, "sync ${resp?.first} : ${resp?.second?.take(300)}")
            return null
        }
        return extractPath(JSONObject(resp.second), c.imageUrlPath)
    }

    // ---------- 异步协议(提交 + 轮询) ----------
    private fun asyncGenerate(entry: GenConfig.Entry, key: String, prompt: String, size: String, n: Int): String? {
        val c = entry.contract
        val taskId = submit(c, key, prompt, size, n) ?: return null
        val deadline = System.currentTimeMillis() + MAX_POLL_MS
        while (System.currentTimeMillis() < deadline) {
            val status = pollStatus(c, key, taskId)
            if (status == null) return null
            val (state, bodyText) = status
            if (state == c.successValue) {
                return extractPath(JSONObject(bodyText), c.imageUrlPath)
            }
            if (state in listOf("FAILED", "CANCELED", "UNKNOWN", "error", "Error")) return null
            Thread.sleep(1500)
        }
        return null
    }

    private fun submit(c: GenConfig.GenContract, key: String, prompt: String, size: String, n: Int): String? {
        val body = buildBody(c, prompt, size, n)
        val url = c.submitEndpoint.ifBlank { c.endpoint }
        val resp = request("POST", url, c, key, body.toString()) ?: return null
        if (resp.first !in 200..299) {
            android.util.Log.w(TAG, "submit ${resp.first}: ${resp.second.take(300)}")
            return null
        }
        return extractPath(JSONObject(resp.second), c.taskIdPath).takeIf { it.isNotBlank() }
    }

    /** 轮询一次, 返回 (状态值, 响应原文) */
    private fun pollStatus(c: GenConfig.GenContract, key: String, taskId: String): Pair<String, String>? {
        val url = c.pollEndpoint.replace("{task_id}", taskId)
        val resp = request("GET", url, c, key, null) ?: return null
        if (resp.first !in 200..299) {
            android.util.Log.w(TAG, "poll ${resp.first}: ${resp.second.take(200)}")
            return null
        }
        val jo = JSONObject(resp.second)
        val state = extractPath(jo, c.statusPath).ifEmpty { jo.optString("task_status") }
        return state to resp.second
    }

    private fun pollOnce(c: GenConfig.GenContract, key: String, taskId: String): String? {
        // 测试连接用: 最多等 15 秒
        val deadline = System.currentTimeMillis() + 15 * 1000L
        while (System.currentTimeMillis() < deadline) {
            val status = pollStatus(c, key, taskId) ?: return null
            if (status.first == c.successValue) return extractPath(JSONObject(status.second), c.imageUrlPath)
            if (status.first in listOf("FAILED", "CANCELED", "UNKNOWN", "error", "Error")) return null
            Thread.sleep(1500)
        }
        return null
    }

    // ---------- 请求体/鉴权/响应路径 ----------
    private fun buildBody(c: GenConfig.GenContract, prompt: String, size: String, n: Int): JSONObject {
        val body = JSONObject()
        if (c.modelId.isNotBlank()) body.put(c.modelField, c.modelId)
        if (c.template == "chat") {
            // OpenAI Chat 风格: content 为数组, 元素 {type:"text", text:...} (混元 v3.5 官方格式)
            body.put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", JSONArray().put(
                    JSONObject().put("type", "text").put("text", prompt)
                ))
            ))
        } else {
            body.put(c.promptField, prompt)
        }
        if (size.isNotBlank() && c.sizeField.isNotBlank()) {
            val sz = if (c.sizeSeparator == "x") size.replace("*", "x") else size
            body.put(c.sizeField, sz)
        }
        if (n > 1) body.put("n", n)
        return body
    }

    private fun applyAuth(conn: HttpURLConnection, c: GenConfig.GenContract, key: String) {
        when (c.authType) {
            "none" -> {}
            "header" -> conn.setRequestProperty(c.authHeaderName, key)
            else -> conn.setRequestProperty(c.authHeaderName, c.authPrefix + key)
        }
    }

    /** 通用 HTTP 请求, 返回 (code, bodyText); 失败返回 null */
    private fun request(method: String, url: String, c: GenConfig.GenContract, key: String, body: String?): Pair<Int, String>? {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
        try {
            conn.requestMethod = method
            if (key.isNotBlank()) applyAuth(conn, c, key)
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
            }
            c.extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = readStream(if (code in 200..299) conn.inputStream else conn.errorStream ?: return code to "")
            return code to text
        } finally { conn.disconnect() }
    }

    /** 按点路径取 JSON 字段, 支持数组索引: data[0].url / choices[0].delta.image.url / output.results[0].url */
    fun extractPath(root: JSONObject, path: String): String {
        if (path.isBlank()) return ""
        var cur: Any = root
        for (s in path.split(".")) {
            val m = Regex("""(.+?)\[(\d+)\]""").find(s)
            if (m != null) {
                val arr = (cur as? JSONObject)?.optJSONArray(m.groupValues[1]) ?: return ""
                val idx = m.groupValues[2].toIntOrNull() ?: return ""
                if (idx < 0 || arr.length() <= idx) return ""
                cur = arr.get(idx)
            } else {
                cur = (cur as? JSONObject)?.opt(s) ?: return ""
            }
        }
        return cur?.toString() ?: ""
    }

    private fun download(url: String): ByteArray? {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            return conn.inputStream.use { readBytes(it) }
        } finally { conn.disconnect() }
    }

    private fun readStream(ins: InputStream): String =
        ins.bufferedReader().use { it.readText() }

    private fun readBytes(ins: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) {
            val r = ins.read(tmp)
            if (r < 0) break
            buf.write(tmp, 0, r)
        }
        return buf.toByteArray()
    }
}
