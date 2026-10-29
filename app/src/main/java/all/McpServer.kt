package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机端 MCP Server(最小实现, 只读)。
 *
 * 目的: 反方向使用 MCP —— 让桌面端 MCP 客户端(Claude Desktop / Cursor / 任意 HTTP 客户端)
 * 把手机当成一个工具端点, 直接读手机上的状态、记忆与屏幕。
 *
 * 传输: POST /mcp, JSON-RPC 2.0 单请求单响应(application/json)。
 * 鉴权: 复用 DebugServer 的 X-Auth-Token, 同时兼容 Authorization: Bearer <token>。
 * 开关: 跟随"调试服务"总开关, 默认关闭; 服务未启动时不监听任何端口。
 * 边界: 仅暴露只读工具。点击 / 输入 / shell / 发送消息等写操作一律不开放。
 */
object McpServer {
    const val PATH = "/mcp"
    private const val PROTOCOL = "2025-06-18"
    private const val SERVER_NAME = "nyral-phone"

    /**
     * 处理一条 JSON-RPC 消息。返回 null 表示通知类消息, 无需响应体。
     * 传参 c 用于读取本地状态; 调用方负责 HTTP 状态码与写出。
     */
    fun handle(c: Context, body: String): JSONObject? {
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return error(null, -32700, "parse error")
        }
        val id = if (req.has("id")) req.opt("id") else null
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()
        if (method.isBlank()) return error(id, -32600, "invalid request")

        // 通知类(notifications/initialized 等)无 id, 按协议不回响应体
        if (method.startsWith("notifications/")) return null

        return when (method) {
            "initialize" -> ok(id, JSONObject().apply {
                // 回客户端声明的版本, 缺省用本实现支持的版本
                put("protocolVersion", params.optString("protocolVersion", PROTOCOL))
                put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", versionName(c)))
            })
            "ping" -> ok(id, JSONObject())
            "tools/list" -> ok(id, JSONObject().put("tools", toolList()))
            "tools/call" -> callTool(c, id, params)
            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun toolList(): JSONArray {
        val noArg = JSONObject().put("type", "object").put("properties", JSONObject())
        return JSONArray().apply {
            put(JSONObject().apply {
                put("name", "phone_status")
                put("description", "读取手机端当前状态: 模型与供应商、记忆条数、工具统计、会话信息。只读。")
                put("inputSchema", noArg)
            })
            put(JSONObject().apply {
                put("name", "phone_memory_search")
                put("description", "在手机本地长期记忆库中做语义检索(端侧向量召回 + 关键词召回), 返回相关记忆片段及时间。只读。")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().put("query", JSONObject()
                        .put("type", "string")
                        .put("description", "自然语言检索语句, 例如: 上周定下来的部署方案")))
                    put("required", JSONArray().put("query"))
                })
            })
            put(JSONObject().apply {
                put("name", "phone_diag_list")
                put("description", "列出手机只读体检项: 每项的 id、名称、说明与所需特权等级(0=公开API, 1=Shizuku, 2=root)。只读。")
                put("inputSchema", noArg)
            })
            put(JSONObject().apply {
                put("name", "phone_diag_run")
                put("description", "执行手机只读体检并返回结构化诊断结果(结论 verdict + 明细 lines + 建议 hint)。全程只读: 不修改设置、不写文件、不执行可写命令。传 all=true 采集全部项。")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject()
                        .put("id", JSONObject().put("type", "string")
                            .put("description", "体检项 id: battery / storage / crash / appcrash / net / thermal / rootenv"))
                        .put("all", JSONObject().put("type", "boolean")
                            .put("description", "为 true 时采集全部体检项(特权不足的项会返回跳过原因)")))
                    put("required", JSONArray())
                })
            })
            put(JSONObject().apply {
                put("name", "phone_screen")
                put("description", "截取手机当前界面, 返回 PNG 图像。只读。注意: 画面可能包含敏感信息, 仅在你确认安全的环境下调用。")
                put("inputSchema", noArg)
            })
        }
    }

    private fun callTool(c: Context, id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return when (name) {
            "phone_status" -> {
                val st = try {
                    statusBrief(c)
                } catch (e: Exception) {
                    return ok(id, errContent("读取状态失败: ${e.message}"))
                }
                ok(id, okContent(textContent(st)))
            }
            "phone_memory_search" -> {
                val q = args.optString("query", "").trim()
                if (q.isEmpty()) return ok(id, errContent("缺少 query 参数"))
                val r = try {
                    MemoryTools.search(c, q)
                } catch (e: Exception) {
                    return ok(id, errContent("检索失败: ${e.message}"))
                }
                ok(id, okContent(textContent(r)))
            }
            "phone_diag_list" -> {
                val arr = JSONArray()
                for (it in Diagnostics.ITEMS) {
                    arr.put(JSONObject().put("id", it.id).put("title", it.title)
                        .put("desc", it.desc).put("level", it.level))
                }
                val lv = Diagnostics.currentLevel()
                ok(id, okContent(textContent(JSONObject()
                    .put("privilege", lv)
                    .put("privilegeName", Diagnostics.levelName(lv))
                    .put("items", arr)
                    .toString(2))))
            }
            "phone_diag_run" -> {
                val all = args.optBoolean("all", false)
                val results: List<Diagnostics.Result> = if (all) {
                    Diagnostics.runAll(c)
                } else {
                    val one = args.optString("id", "").trim()
                    if (one.isEmpty()) return ok(id, errContent("缺少 id 参数; 或传 all=true 采集全部"))
                    listOf(Diagnostics.run(c, one))
                }
                val arr = JSONArray()
                for (r in results) {
                    arr.put(JSONObject().apply {
                        put("id", r.id)
                        put("title", r.title)
                        put("level", r.level)
                        put("ok", r.ok)
                        put("verdict", r.verdict)
                        put("lines", JSONArray(r.lines))
                        put("hint", r.hint)
                        put("error", r.error)
                    })
                }
                val lv = Diagnostics.currentLevel()
                ok(id, okContent(textContent(JSONObject()
                    .put("privilege", lv)
                    .put("privilegeName", Diagnostics.levelName(lv))
                    .put("results", arr)
                    .toString(2))))
            }
            "phone_screen" -> {
                val s = DebugServer.screenJson(c)
                if (!s.optBoolean("ok", false)) {
                    return ok(id, errContent("截图失败: " + s.optString("error", "unknown")))
                }
                val arr = JSONArray().apply {
                    put(JSONObject().put("type", "text")
                        .put("text", "${s.optInt("width")}x${s.optInt("height")} PNG"))
                    put(JSONObject().put("type", "image")
                        .put("data", s.optString("image_png_base64"))
                        .put("mimeType", "image/png"))
                }
                ok(id, JSONObject().put("content", arr).put("isError", false))
            }
            else -> error(id, -32602, "unknown tool: $name")
        }
    }

    // ---- 组装辅助 ----

    /**
     * 状态白名单摘要: 只给模型、记忆计数、工具名与会话态。
     * 刻意不转发 SSH 连接信息、API 端点、端口等基建细节 —— MCP 结果会进入第三方模型上下文。
     */
    private fun statusBrief(c: Context): String {
        val raw = DebugServer.stateJson(c)
        val o = JSONObject()
        raw.optJSONObject("model")?.let { m ->
            o.put("model", JSONObject()
                .put("provider", m.optString("provider"))
                .put("model", m.optString("model"))
                .put("is_local", m.optBoolean("is_local")))
        }
        raw.optJSONObject("memory")?.let { m ->
            o.put("memory", JSONObject()
                .put("count", m.optInt("count"))
                .put("pending", m.optInt("pending")))
        }
        raw.optJSONArray("tools")?.let { arr ->
            val names = JSONArray()
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("name", "") ?: ""
                if (n.isNotBlank()) names.put(n)
            }
            o.put("tool_count", names.length())
            o.put("tools", names)
        }
        raw.optJSONObject("session")?.let { s ->
            o.put("session", JSONObject()
                .put("title", s.optString("current_session_title", ""))
                .put("ai_busy", s.optBoolean("ai_busy"))
                .put("message_count", s.optInt("message_count")))
        }
        o.put("now", System.currentTimeMillis())
        return o.toString(2)
    }

    private fun ok(id: Any?, result: JSONObject): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("result", result)
    }

    private fun error(id: Any?, code: Int, msg: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().put("code", code).put("message", msg))
    }

    private fun okContent(t: JSONObject): JSONObject =
        JSONObject().put("content", JSONArray().put(t)).put("isError", false)

    private fun errContent(msg: String): JSONObject =
        JSONObject().put("content", JSONArray().put(textContent(msg))).put("isError", true)

    private fun textContent(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    private fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
