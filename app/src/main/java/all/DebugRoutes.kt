package io.github.aixtin.nyral

/**
 * DebugServer 鉴权/路由/SSE 帧纯函数对照(2026-10-05):
 * 与 DebugServer.handle 的 when 路由表与鉴权逻辑一致(Kotlin 对照实现, 不改运行时行为, 零回归)。
 * 端点列表须与 DebugServer.kt 保持一致, 新增端点时同步补登。
 */
object DebugRoutes {

    /** 请求行路径规范化: 剥离 ?query, 返回 (path, query); 无 query 时 query 为空串 */
    data class PathInfo(val path: String, val query: String)

    fun splitPath(raw: String): PathInfo {
        val qIdx = raw.indexOf('?')
        return if (qIdx >= 0) PathInfo(raw.substring(0, qIdx), raw.substring(qIdx + 1))
        else PathInfo(raw, "")
    }

    /** 端点路由: method+path 命中返回端点名(与 DebugServer when 一致), 未命中返回 null */
    fun routeOf(method: String, path: String): String? = when {
        method == "GET" && path == "/v1/state" -> "GET /v1/state"
        method == "GET" && path == "/v1/ui" -> "GET /v1/ui"
        method == "GET" && path == "/v1/logs" -> "GET /v1/logs"
        method == "POST" && path == "/v1/mem/search" -> "POST /v1/mem/search"
        method == "POST" && path == "/v1/chat" -> "POST /v1/chat"
        method == "GET" && path == "/v1/screen" -> "GET /v1/screen"
        method == "POST" && path == "/v1/touch" -> "POST /v1/touch"
        method == "POST" && path == "/v1/key" -> "POST /v1/key"
        method == "POST" && path == "/v1/input" -> "POST /v1/input"
        method == "GET" && path == "/v1/ping" -> "GET /v1/ping"
        method == "GET" && path == "/v1/browser" -> "GET /v1/browser"
        method == "POST" && path == "/v1/browser/open" -> "POST /v1/browser/open"
        method == "POST" && path == "/v1/browser/close" -> "POST /v1/browser/close"
        method == "POST" && path == "/v1/browser/status" -> "POST /v1/browser/status"
        method == "POST" && path == "/v1/browser/think" -> "POST /v1/browser/think"
        method == "POST" && path == "/v1/browser/scan" -> "POST /v1/browser/scan"
        method == "POST" && path == "/v1/browser/highlight" -> "POST /v1/browser/highlight"
        method == "POST" && path == "/v1/browser/highlight/xy" -> "POST /v1/browser/highlight/xy"
        method == "POST" && path == "/v1/browser/click" -> "POST /v1/browser/click"
        method == "POST" && path == "/v1/browser/type" -> "POST /v1/browser/type"
        method == "POST" && path == "/v1/browser/scroll" -> "POST /v1/browser/scroll"
        method == "POST" && path == "/v1/browser/eval" -> "POST /v1/browser/eval"
        method == "POST" && path == "/v1/app/scan" -> "POST /v1/app/scan"
        method == "POST" && path == "/v1/app/click" -> "POST /v1/app/click"
        method == "POST" && path == "/v1/app/type" -> "POST /v1/app/type"
        method == "POST" && path == "/v1/app/back" -> "POST /v1/app/back"
        method == "POST" && path == "/v1/app/home" -> "POST /v1/app/home"
        method == "POST" && path == "/v1/app/launch" -> "POST /v1/app/launch"
        method == "POST" && path == "/v1/app/installed" -> "POST /v1/app/installed"
        method == "POST" && path == "/v1/app/tap" -> "POST /v1/app/tap"
        method == "GET" && path == "/v1/app/screenshot" -> "GET /v1/app/screenshot"
        method == "POST" && path == "/v1/js/run" -> "POST /v1/js/run"
        method == "POST" && path == "/v1/sh/run" -> "POST /v1/sh/run"
        method == "GET" && path == "/v1/status" -> "GET /v1/status"
        method == "GET" && path == "/v1/file/read" -> "GET /v1/file/read"
        else -> null
    }

    /** 鉴权: 仅 header X-Auth-Token 与期望 token 精确相等才算通过; query token 已移除(防日志泄露) */
    fun authorized(got: String?, expect: String): Boolean = got != null && got == expect

    /** 连接复用: 仅 GET 普通端点且客户端声明 keep-alive(与 DebugServer 一致) */
    fun reusable(method: String, keepAlive: Boolean): Boolean = method == "GET" && keepAlive
}
