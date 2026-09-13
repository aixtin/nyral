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
    /** site_auth.json 内元信息键(带 __ 前缀, 所有站点遍历均跳过): 最近一次保存时间戳(epoch ms) */
    private const val SITE_AUTH_META = "__updated_at"
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    // Bing 搜索用桌面 UA: 移动 UA 下 cn.bing.com 返回非标准结构, 无法解析
    private val SEARCH_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 参数: {"q":"搜索关键词","max_results":5}
     * 返回: 多引擎网页搜索结果(标题+链接+摘要), 最多 max_results 条。
     * 引擎优先级: 搜狗移动端 -> 必应RSS -> 必应网页 -> 百度, 单个引擎空结果/被反爬时自动切换下一个。
     */
    fun search(arg: String): String {
        val json = try { JSONObject(arg) } catch (e: Exception) { null }
        val query = json?.optString("q")?.takeIf { it.isNotBlank() } ?: arg.trim()
        val maxResults = (json?.optInt("max_results", 5) ?: 5).coerceIn(1, 10)
        if (query.isBlank()) return "错误: 搜索关键词为空"
        if (query.length > 200) return "错误: 关键词过长"

        val attempts = listOf(
            "搜狗移动端" to { sogouSearch(query) },
            "必应RSS" to { bingRssSearch(query) },
            "必应网页" to { bingHtmlSearch(query) },
            "百度" to { baiduSearch(query) },
        )
        val failures = mutableListOf<String>()
        for ((name, fn) in attempts) {
            val hits = try { fn() } catch (e: Exception) { emptyList<SearchHit>() }
            val effective = hits.take(maxResults)
            if (effective.isNotEmpty()) {
                return formatSearchResults("搜索结果来源:【$name】", effective)
            }
            failures += name
        }
        return "未找到搜索结果(已依次尝试: ${failures.joinToString("→")}, 均为空或被拦截)。" +
                "\n建议换更精确的关键词重试, 或直接 web_fetch 访问百度百科/豆瓣/猫眼等已知站点。"
    }

    private data class SearchHit(val title: String, val link: String, val snippet: String)

    private fun formatSearchResults(tag: String, hits: List<SearchHit>): String {
        val sb = StringBuilder(tag)
        hits.forEachIndexed { i, h ->
            sb.append("\n${i + 1}. ${h.title}\n   ${h.link}\n   ${if (h.snippet.isEmpty()) "(无摘要)" else h.snippet}")
        }
        return sb.toString().trim()
    }

    /** 去除 HTML 标签 + 解码实体 + 挤压空白 */
    private fun cleanHtml(s: String): String {
        return s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'").replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanUrl(u: String?): String =
        (u ?: "").trim().replace("&amp;", "&").replace(Regex("\\s+"), "")

    /** 反爬/验证页特征: 命中任一视为该引擎抓取失败, 交给下一引擎 */
    private fun blocked(html: String): Boolean =
        listOf("安全验证", "验证码", "captcha", "请开启JavaScript", "访问过于频繁", "发生错误")
            .any { html.contains(it, ignoreCase = true) }

    /** 相对链接解析为绝对链接 */
    private fun toAbs(base: String, href: String): String? {
        val h = href.trim()
        if (h.isEmpty()) return null
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("/")) {
            val root = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: return null
            return root + h
        }
        return null
    }

    /** 引擎1: 必应 RSS 接口(format=rss), 标准 XML 结构, 最稳定 */
    private fun bingRssSearch(query: String): List<SearchHit> {
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=rss&setlang=zh-cn"
        val raw = download(url, SEARCH_UA) ?: return emptyList()
        if (blocked(raw) || !raw.contains("<item")) return emptyList()
        val out = mutableListOf<SearchHit>()
        for (item in Regex("(?is)<item>.*?</item>").findAll(raw)) {
            val m = item.value
            val title = Regex("(?is)<title>(.*?)</title>").find(m)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim() ?: continue
            // RSS 里 link 常为必应重定向, description 内嵌真实链接优先取
            val descRaw = Regex("(?is)<description>(.*?)</description>").find(m)?.groupValues?.get(1).orEmpty()
            val realLink = Regex("(?i)href=\"(https?://[^\"]+)\"").find(descRaw)?.groupValues?.get(1)
            val link = realLink ?: Regex("(?is)<link>(.*?)</link>").find(m)?.groupValues?.get(1)?.trim()
            if (link != null && title.isNotEmpty()) out += SearchHit(title, link, cleanHtml(descRaw))
        }
        return out
    }

    /** 引擎2: 必应网页版, 解析 b_algo 结果块, 对中文冷门词命中率高于 RSS */
    private fun bingHtmlSearch(query: String): List<SearchHit> {
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=zh-cn"
        val raw = download(url, SEARCH_UA) ?: return emptyList()
        if (blocked(raw)) return emptyList()
        val out = mutableListOf<SearchHit>()
        for (block in Regex("(?is)<li class=\"b_algo\".*?</li>").findAll(raw)) {
            val b = block.value
            val a = Regex("(?is)<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(b) ?: continue
            val link = cleanUrl(toAbs(url, a.groupValues[1])).takeIf { it.isNotEmpty() } ?: continue
            val title = cleanHtml(a.groupValues[2])
            if (title.isEmpty()) continue
            val snippet = Regex("(?is)<p[^>]*>(.*?)</p>").find(b)?.groupValues?.get(1).orEmpty()
            out += SearchHit(title, link, cleanHtml(snippet))
        }
        return out
    }

    /** 引擎3: 百度网页版, 解析 result c-container 结果块, 中文命中率最高但反爬最严 */
    private fun baiduSearch(query: String): List<SearchHit> {
        val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, "UTF-8") + "&rn=10"
        val raw = download(url, SEARCH_UA) ?: return emptyList()
        if (blocked(raw)) return emptyList()
        val out = mutableListOf<SearchHit>()
        for (block in Regex("(?is)<div class=\"result c-container\".*?</div>").findAll(raw)) {
            val b = block.value
            val a = Regex("(?is)<h3[^>]*>.*?<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(b) ?: continue
            val link = cleanUrl(toAbs(url, a.groupValues[1])).takeIf { it.isNotEmpty() } ?: continue
            val title = cleanHtml(a.groupValues[2])
            if (title.isEmpty()) continue
            val snippet = Regex("(?is)<(?:span|div)[^>]*class=\"[^\"]*(?:content-right|c-abstract)[^\"]*\"[^>]*>(.*?)</(?:span|div)>")
                .find(b)?.groupValues?.get(1).orEmpty()
            out += SearchHit(title, link, cleanHtml(snippet))
        }
        return out
    }

    /** 引擎1: 搜狗移动端 (m.sogou.com), 国内直连稳定、无验证码、无需cookie。
     *  实测冷门影片名稳定返回 6~10 条相关结果(标题+真实链接+摘要)。
     *  真实链接藏在 href 的 url= / pcurl= 参数里(URL 编码), 解析失败则用搜狗跳转链接兜底。 */
    private fun sogouSearch(query: String): List<SearchHit> {
        val url = "https://m.sogou.com/web/searchList.jsp?keyword=" + URLEncoder.encode(query, "UTF-8")
        val raw = download(url, SEARCH_UA) ?: return emptyList()
        if (blocked(raw)) return emptyList()
        val out = mutableListOf<SearchHit>()
        val aRe = Regex("(?is)<a class=\"resultLink[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
        for (am in aRe.findAll(raw)) {
            val href = am.groupValues[1]
            val title = cleanHtml(am.groupValues[2])
            if (title.isEmpty() || listOf("大家还在搜", "相关搜索").any { title.contains(it) }) continue
            // 真实链接藏在 href 的 url= / pcurl= 参数里(URL编码), 解析失败则用搜狗跳转链接
            val real = Regex("[?&](?:url|pcurl)=([^&]+)").find(href)
                ?.groupValues?.get(1)?.let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { null }
                }?.takeIf { it.startsWith("http") }
                ?: toAbs(url, href) ?: continue
            // 摘要: 该结果 a 标签之后最近的一个 txt-summary 块
            val after = raw.substring(am.range.last + 1, minOf(raw.length, am.range.last + 1 + 4000))
            val snippet = Regex("(?is)class=\"txt-summary[^\"]*\"[^>]*>\\s*<div class=\"[^\"]*\">(.*?)</div>")
                .find(after)?.groupValues?.get(1)?.let { cleanHtml(it) }.orEmpty()
            out += SearchHit(title, real, snippet)
        }
        return out
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
            val headers = mergeHeaders(context, url, parseHeaders(json))
            val raw = download(url, UA, headers)
                ?: return "错误: 下载失败(超时或网络不可用)"
            val text = extractText(raw)
            val cleaned = text.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isEmpty()) return "网页无可见文本(可能是JS渲染页面, 建议用浏览器查看)"
            val body = if (cleaned.length > maxChars) cleaned.substring(0, maxChars) + "\n...[已截断]" else cleaned
            val hint = loginExpiredHint(context, url, headers, cleaned)
            if (hint != null) "$body\n\n[提示] $hint" else body
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
                var cnt = 0
                while (it.hasNext()) {
                    val k = it.next()
                    if (k.startsWith("__")) continue
                    cnt++
                    sb.append("- $k: ${maskCookie(auth.optString(k))}\n")
                }
                if (cnt == 0) return "未配置任何站点凭据"
                val ts = auth.optLong(SITE_AUTH_META, 0L)
                if (ts > 0) sb.append("最近更新: ${formatTs(ts)}\n")
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

    /** 登录态失效特征文本(命中任一即疑似登录页, 仅对 site_auth 已配置域名检测, 避免公开页误报) */
    private val LOGIN_EXPIRED_HINTS = listOf(
        "请登录", "请先登录", "请重新登录", "登录后查看", "登录后继续", "登录后可", "登录后即可",
        "登录过期", "登录已过期", "会话已过期", "登录状态已失效", "登录失效",
        "please log in", "please sign in", "please login", "login required",
        "log in to continue", "sign in to continue", "login to continue"
    )

    /** 时间戳转展示串: "MM-dd HH:mm" */
    private fun formatTs(ts: Long): String {
        return try {
            val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            df.format(java.util.Date(ts))
        } catch (e: Exception) { ts.toString() }
    }

    /**
     * 检测抓取正文是否疑似登录失效页: 仅当该 URL 域名在 site_auth 中配置过(应带登录态) 且实际注入了 Cookie,
     * 且正文出现登录页特征文本时, 返回提示文案; 否则返回 null。用于登录态过期时引导 AI 走浏览器重登自愈。
     */
    private fun loginExpiredHint(context: Context, url: String, headers: Map<String, String>, body: String): String? {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return null
        val auth = loadSiteAuth(context) ?: return null
        var configured = false
        val it = auth.keys()
        while (it.hasNext()) {
            val site = it.next()
            if (site.startsWith("__")) continue
            if (host == site || host.endsWith(".$site")) { configured = true; break }
        }
        if (!configured) return null
        val injected = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        if (injected.isNullOrBlank()) return null
        val low = body.lowercase()
        if (!LOGIN_EXPIRED_HINTS.any { low.contains(it.lowercase()) }) return null
        return "抓取结果疑似登录失效页(该站点在 site_auth 中配置了登录态): 登录态可能已过期, 建议用 open_browser 打开重新登录后 browser_save_cookies 更新"
    }

    /** 读取工作目录 site_auth.json; 不存在或解析失败返回 null */
    private fun loadSiteAuth(context: Context): JSONObject? {
        val bytes = WorkDir.read(context, SITE_AUTH_FILE) ?: return null
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    /** 写入 site_auth.json (保留原文件未覆盖的其它字段); 统一记录最近保存时间戳 */
    private fun saveSiteAuth(context: Context, auth: JSONObject): Boolean {
        if (auth != null) auth.put(SITE_AUTH_META, System.currentTimeMillis())
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
            if (site.startsWith("__")) continue
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
