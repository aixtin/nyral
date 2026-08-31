package io.github.aixtin.droidagent

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.net.URLEncoder

/**
 * WebTools: 网页抓取工具。
 * 直接使用手机本地网络抓取网页, 提取正文文本, 限长返回。
 * 不依赖 SSH/远端, 手机有网即可用。
 *
 * 站点凭据: 工作目录 site_auth.json 按域名存 Cookie, 抓取/下载时自动注入,
 * 无需每次由 AI 传参; 过期后用 auth 工具或直接改该文件即可。
 */
object WebTools {

    private const val DEFAULT_MAX_CHARS = 3000
    private const val MAX_BYTES = 2 * 1024 * 1024 // 最多下载 2MB, 防拉爆流量
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000
    private const val SITE_AUTH_FILE = "site_auth.json"
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    // Bing 搜索用桌面 UA: 移动 UA 下 cn.bing.com 返回非标准结构, 无法解析
    private val SEARCH_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 参数: {"q":"搜索关键词","max_results":5}
     * 返回: Bing 搜索结果(标题+链接+摘要), 最多 max_results 条
     */
    fun search(arg: String): String {
        val json = try { JSONObject(arg) } catch (e: Exception) { null }
        val query = json?.optString("q")?.takeIf { it.isNotBlank() } ?: arg.trim()
        val maxResults = (json?.optInt("max_results", 5) ?: 5).coerceIn(1, 10)
        if (query.isBlank()) return "错误: 搜索关键词为空"
        if (!query.matches(Regex("[\\s\\S]{1,200}"))) return "错误: 关键词过长"
        // 走必应 RSS 接口(format=rss), 返回标准 XML, 比抓 HTML 页解析更稳定
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=rss"
        return try {
            val raw = download(url, SEARCH_UA) ?: return "错误: 搜索失败(超时或网络不可用)"
            parseRssResults(raw, maxResults)
        } catch (e: Exception) {
            "错误: ${e.message}"
        }
    }

    /** 解析必应 RSS 结果(标准 <item> 结构: title/link/description) */
    private fun parseRssResults(xml: String, maxResults: Int): String {
        val items = Regex("(?is)<item>.*?</item>").findAll(xml).take(maxResults).toList()
        if (items.isEmpty()) return "未找到搜索结果"
        val sb = StringBuilder()
        var idx = 0
        for (item in items) {
            val m = item.value
            val title = Regex("(?is)<title>(.*?)</title>").find(m)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim() ?: continue
            // RSS 里 link 常为必应 ck/a 重定向, description 内嵌真实链接优先取
            val descRaw = Regex("(?is)<description>(.*?)</description>").find(m)?.groupValues?.get(1).orEmpty()
            val realLink = Regex("(?i)href=\"(https?://[^\"]+)\"").find(descRaw)?.groupValues?.get(1)
            val link = realLink ?: Regex("(?is)<link>(.*?)</link>").find(m)?.groupValues?.get(1)?.trim()
            val snippet = descRaw
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
            if (link != null && title.isNotEmpty()) {
                idx++
                sb.append("$idx. $title\n   $link\n   $snippet\n")
            }
        }
        return if (sb.isEmpty()) "未找到可解析的搜索结果" else sb.toString().trim()
    }

    /**
     * 参数: {"url":"https://...","max_chars":3000,"headers":{"Cookie":"...","Referer":"..."}}
     * 返回: 提取后的网页正文文本(截断到 max_chars)
     * headers 可选: 登录态/反爬站点可注入 Cookie、Referer、User-Agent 等自定义请求头;
     * 若未显式传 Cookie 且 site_auth.json 中配置了该域名, 自动注入
     */
    fun fetch(context: Context, arg: String): String {
        val json = try { JSONObject(arg) } catch (e: Exception) { null }
        val url = json?.optString("url")?.takeIf { it.isNotBlank() } ?: arg.trim()
        val maxChars = json?.optInt("max_chars", DEFAULT_MAX_CHARS) ?: DEFAULT_MAX_CHARS
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误: URL必须以http://或https://开头"
        }
        return try {
            val raw = download(url, UA, mergeHeaders(context, url, parseHeaders(json)))
                ?: return "错误: 下载失败(超时或网络不可用)"
            val text = extractText(raw)
            val cleaned = text.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isEmpty()) return "网页无可见文本(可能是JS渲染页面, 建议用浏览器查看)"
            if (cleaned.length > maxChars) cleaned.substring(0, maxChars) + "\n...[已截断]" else cleaned
        } catch (e: Exception) {
            "错误: ${e.message}"
        }
    }

    /**
     * 站点凭据管理工具。
     * 参数: {"action":"list"} 列出已配置站点
     *       {"action":"set","site":"annas-archive.org","cookie":"session=xxx"}
     *       {"action":"del","site":"annas-archive.org"}
     * 保存到工作目录 site_auth.json; web_fetch/web_download 按域名自动注入 Cookie
     */
    fun siteAuth(context: Context, arg: String): String {
        val json = try { JSONObject(arg) } catch (e: Exception) { null }
        val action = json?.optString("action")?.takeIf { it.isNotBlank() } ?: "list"
        val auth = loadSiteAuth(context)
        when (action) {
            "list" -> {
                if (auth == null || auth.length() == 0) return "未配置任何站点凭据"
                val sb = StringBuilder("已配置站点凭据 (site_auth.json):\n")
                val it = auth.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    sb.append("- $k: ${maskCookie(auth.optString(k))}\n")
                }
                return sb.toString().trim()
            }
            "set" -> {
                val site = json?.optString("site")?.trim().orEmpty()
                val cookie = json?.optString("cookie")?.trim().orEmpty()
                if (site.isEmpty() || cookie.isEmpty()) return "错误: set 需提供 site 和 cookie"
                val newAuth = auth ?: JSONObject()
                newAuth.put(site, cookie)
                if (!saveSiteAuth(context, newAuth)) return "错误: 写入 site_auth.json 失败"
                return "已保存凭据: $site"
            }
            "del" -> {
                val site = json?.optString("site")?.trim().orEmpty()
                if (site.isEmpty()) return "错误: del 需提供 site"
                val newAuth = auth ?: JSONObject()
                if (!newAuth.has(site)) return "未找到该站点凭据: $site"
                newAuth.remove(site)
                if (!saveSiteAuth(context, newAuth)) return "错误: 写入 site_auth.json 失败"
                return "已删除凭据: $site"
            }
            else -> return "错误: 未知 action, 支持 list/set/del"
        }
    }

    /** 读取工作目录 site_auth.json; 不存在或解析失败返回 null */
    private fun loadSiteAuth(context: Context): JSONObject? {
        val bytes = WorkDir.read(context, SITE_AUTH_FILE) ?: return null
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    /** 写入 site_auth.json (保留原文件未覆盖的其它字段) */
    private fun saveSiteAuth(context: Context, auth: JSONObject): Boolean {
        return WorkDir.write(context, SITE_AUTH_FILE, auth.toString().toByteArray(Charsets.UTF_8))
    }

    /** 合并 headers: 若调用方未显式带 Cookie 且命中 site_auth 域名, 自动注入 */
    private fun mergeHeaders(context: Context, url: String, extra: Map<String, String>): Map<String, String> {
        if (extra.keys.any { it.equals("Cookie", true) }) return extra
        val host = try { URL(url).host } catch (e: Exception) { return extra }
        val auth = loadSiteAuth(context) ?: return extra
        val it = auth.keys()
        while (it.hasNext()) {
            val site = it.next()
            if (host == site || host.endsWith(".$site")) {
                val merged = LinkedHashMap(extra)
                merged["Cookie"] = auth.optString(site)
                return merged
            }
        }
        return extra
    }

    /** 脱敏显示 Cookie: 只保留前若干字符 */
    private fun maskCookie(cookie: String): String {
        if (cookie.length <= 12) return "***"
        return cookie.substring(0, 6) + "..." + cookie.takeLast(4)
    }

    /** 从工具参数 JSON 中解析自定义请求头: 支持 "headers":{...} 或顶层 "cookie":"..." 两种写法 */
    private fun parseHeaders(json: JSONObject?): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (json == null) return map
        val headers = json.optJSONObject("headers")
        if (headers != null) {
            val it = headers.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = headers.optString(k)
            }
        }
        val cookie = json.optString("cookie").takeIf { it.isNotBlank() }
        if (cookie != null) map["Cookie"] = cookie
        return map
    }

    /**
     * 参数: {"url":"https://...","name":"可选保存文件名","headers":{"Cookie":"...","Referer":"..."}}
     * 返回: 下载结果, "下载成功"即已落盘, 无需再验证
     * 下载 URL 内容并保存到手机工作目录 Download/DroidAgent_work/(二进制安全)。
     * 未指定 name 时从 URL 末尾或 Content-Disposition 推断文件名。
     */
    fun save(context: Context, arg: String): String {
        val json = try { JSONObject(arg) } catch (e: Exception) { null }
        val url = json?.optString("url")?.takeIf { it.isNotBlank() } ?: arg.trim()
        val givenName = json?.optString("name").orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误: URL必须以http://或https://开头"
        }
        if (givenName.isNotEmpty() && givenName.contains('/')) {
            return "错误: 保存文件名不能含路径分隔符, 只能填文件名"
        }
        val headers = mergeHeaders(context, url, parseHeaders(json))
        var lastErr: String? = null
        // 下载中断(SocketException)自动重试 1 次: ddos-guard 等防护墙常先重置连接再允许访问
        for (attempt in 1..2) {
            val r = saveOnce(context, url, givenName, headers)
            if (r.success) return r.msg
            lastErr = r.msg
            if (r.retryable && attempt == 1) {
                android.util.Log.w("DroidAgent", "web_download interrupted, retry #1")
                try { Thread.sleep(800) } catch (_: InterruptedException) {}
                continue
            }
            break
        }
        return "错误: $lastErr"
    }

    /** 单次下载尝试; retryable=true 表示连接被重置可重试, false 为确定性失败无需重试 */
    private data class DlResult(val success: Boolean, val msg: String, val retryable: Boolean = false)

    private fun saveOnce(context: Context, url: String, givenName: String, headers: Map<String, String>): DlResult {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return DlResult(false, e.message ?: "打开连接失败")
        }
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT * 2
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                return DlResult(false, "HTTP $code")
            }
            val disposition = conn.getHeaderField("Content-Disposition")
            var name = givenName
            if (name.isEmpty()) {
                // 依次尝试: Content-Disposition filename -> URL 路径末段
                name = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(disposition.orEmpty())
                    ?.groupValues?.get(1)?.trim()
                    ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "download" }
            }
            name = WorkDir.sanitize(name).ifEmpty { "download" }
            val contentLen = conn.getHeaderFieldLong("Content-Length", -1)
            if (contentLen > 100L * 1024 * 1024) return DlResult(false, "文件超过 100MB 上限 (${contentLen} 字节)")
            val bytes = try {
                conn.inputStream.use { it.readBytes() }
            } catch (e: SocketException) {
                return DlResult(false, "下载被服务器中断(${e.message})，可能文件过大或防护墙拦截，已自动重试一次", retryable = true)
            }
            if (bytes.size > 100 * 1024 * 1024) return DlResult(false, "文件超过 100MB 上限")
            if (!WorkDir.write(context, name, bytes)) return DlResult(false, "写入工作目录失败: $name")
            return DlResult(true, "下载成功: ${WorkDir.displayPath}$name (${bytes.size} 字节)")
        } catch (e: SocketException) {
            return DlResult(false, "下载被服务器中断(${e.message})", retryable = true)
        } catch (e: Exception) {
            return DlResult(false, e.message ?: "未知错误")
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun download(url: String, ua: String = UA, extraHeaders: Map<String, String> = emptyMap()): String? {
        var current = url
        var redirects = 0
        while (redirects <= 5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                conn.disconnect()
                current = if (loc.startsWith("http")) loc else {
                    val base = URL(current)
                    URL(base, loc).toString()
                }
                redirects++
                continue
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) { conn.disconnect(); return null }
            // 修复: 多字节 UTF-8(中文)跨 read 块边界时按块 String() 解码会产生乱码,
            // 先累积字节再整体转字符串
            val byteOut = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            stream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) break
                    byteOut.write(buf, 0, n)
                }
            }
            conn.disconnect()
            val text = byteOut.toString("UTF-8")
            return if (total > MAX_BYTES) text + "\n[下载超过2MB已截断]" else text
        }
        return null
    }

    /** 粗略HTML转文本: 去script/style/标签, 解码实体, 保留标题 */
    private fun extractText(html: String): String {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)</(p|div|h[1-6]|li|tr)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'")
        return if (title.isNotEmpty()) "标题: $title\n$s" else s
    }
}
