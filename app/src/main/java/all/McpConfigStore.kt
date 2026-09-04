package io.github.aixtin.droidagent

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
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                McpServer(
                    name = o.optString("name", ""),
                    url = o.optString("url", ""),
                    token = o.optString("token", "").ifEmpty { null }
                )
            }
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
            .edit().putString("servers_json", arr.toString()).apply()
    }
}
