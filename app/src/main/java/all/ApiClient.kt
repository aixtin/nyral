package io.github.aixtin.nyral

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ApiClient: 统一 JSON 网络封装（2026-09-05 代码体检后新增）。
 * 收敛散落在各文件的 HttpURLConnection 手写样板：
 *   - 统一 connect/read 超时参数化
 *   - 统一响应体读取（UTF-8）、错误流解析与非 2xx 语义
 *   - 统一日志留痕（ApiClient TAG），网络排障从此有据可查
 *   - 异常一律转 Result.failure，不吞异常；鉴权头由调用点构造（兼容 Bearer / x-api-key / 自定义 header）
 *
 * 收敛不适用场景（保持原实现）：
 *   - 流式 SSE 主对话链路(LocalEngine)
 *   - 文件上传/下载、MCP 长连接协议
 *
 * 典型用法：
 *   ApiClient.postJson(url, body, headers = ApiClient.bearer(key), readMs = 20_000)
 *     .getOrElse { throw /* 或就地处理 */ }; 错误对象可用 as? ApiClient.HttpError 得到 code+body。
 */
object ApiClient {

    private const val TAG = "ApiClient"

    /** 非 2xx 响应：code 为状态码，body 为响应体原文(截断)。 */
    class HttpError(val code: Int, val body: String) :
        RuntimeException("HTTP $code: ${body.take(300)}")

    /** 构造 Bearer 鉴权头。 */
    fun bearer(token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token")

    /** 常规模板头(JSON 请求)。 */
    private fun baseHeaders(extra: Map<String, String>): Map<String, String> {
        val m = HashMap(extra)
        if (m.containsKey("Content-Type").not()) m["Content-Type"] = "application/json"
        m["Accept"] = "application/json"
        return m
    }

    /** POST JSON body，解析响应为 JSONObject。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 10_000,
        readMs: Int = 60_000,
    ): Result<JSONObject> =
        request("POST", url, body, headers, connectMs, readMs) { JSONObject(it) }

    /** GET 并解析响应为 JSONObject。 */
    fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 10_000,
        readMs: Int = 15_000,
    ): Result<JSONObject> =
        request("GET", url, null, headers, connectMs, readMs) { JSONObject(it) }

    private fun <T> request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        connectMs: Int,
        readMs: Int,
        parse: (String) -> T,
    ): Result<T> {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            baseHeaders(headers).forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = if (stream != null) BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText() else ""
            Log.w(TAG, "$method $url -> $code")
            if (code !in 200..299) {
                Log.w(TAG, "  非2xx body: ${resp.take(500)}")
                return Result.failure(HttpError(code, resp))
            }
            try {
                Result.success(parse(resp))
            } catch (e: Exception) {
                Result.failure(RuntimeException("响应解析失败: ${e.message}", e))
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method $url 请求异常: ${e.message}")
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
