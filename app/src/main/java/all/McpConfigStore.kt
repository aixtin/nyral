package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务配置存储 — SharedPreferences 持久化(名称+地址+Token)。
 * 与 SshConfigStore 同风格; Token 为可选项(MT 管理器等本地服务通常无需鉴权)。
 */
object McpConfigStore {

    data class McpServer(
        val name: String,
        val url: String,
        val token: String? = null
    )

    private const val PREFS = "mcp_configs"

    fun load(context: Context): List<McpServer> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("servers_json", null) ?: return emptyList()
        // 安全加固: Token 含 MCP 服务密钥, 加密落盘; 旧明文兼容(解密失败按明文解析, 并迁移为加密)
        val plain = Secrets.decrypt(context, raw) ?: raw
        if (plain == raw && !raw.trimStart().startsWith("[")) return emptyList()
        return try {
            val arr = JSONArray(plain)
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                McpServer(
                    name = o.optString("name", ""),
                    url = o.optString("url", ""),
                    token = o.optString("token", "").ifEmpty { null }
                )
            }
            if (plain == raw) runCatching { save(context, list) } // 明文 -> 加密迁移
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, servers: List<McpServer>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
                put("token", s.token ?: "")
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("servers_json", Secrets.encrypt(context, arr.toString())).apply()
    }
}
