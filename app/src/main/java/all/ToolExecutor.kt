package io.github.aixtin.nyral

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * ToolExecutor: 工具执行域(2026-10-04 从 LocalEngine 拆出)。
 * 负责 executeTool 分发、browser 系列、ask_user、参数归一与危险工具名单;
 * 工具注册表 toolRegistry / 桥接回调 onBrowser* / onAskUser / ToolSpec 仍由 LocalEngine 持有。
 */
object ToolExecutor {

    /**
     * 执行工具。args 为 JSON 字符串(原生 function calling 的 arguments), 兼容旧裸字符串格式。
     * 特殊转发: calc/memory_search/ssh_run 需抽字段为裸字符串传给旧实现。
     */
    /** 第二刀工具合并(2026-09-16): 旧工具名 -> (新复合工具, action), 兼容文本协议/历史调用 */
    internal val LEGACY_TOOL_ACTION = mapOf(
        "open_browser" to ("browser" to "open"),
        "browser_scan" to ("browser" to "scan"),
        "browser_text" to ("browser" to "text"),
        "browser_scroll" to ("browser" to "scroll"),
        "browser_click" to ("browser" to "click"),
        "browser_type" to ("browser" to "type"),
        "browser_upload" to ("browser" to "upload"),
        "browser_clear_cache" to ("browser" to "clear_cache"),
        "browser_save_cookies" to ("browser" to "save_cookies"),
        "app_scan" to ("app" to "scan"),
        "app_click" to ("app" to "click"),
        "app_text" to ("app" to "text"),
        "app_back" to ("app" to "back"),
        "app_home" to ("app" to "home"),
        "app_launch" to ("app" to "launch"),
        "app_installed" to ("app" to "installed"),
        "app_tap" to ("app" to "tap"),
        "app_screenshot" to ("app" to "screenshot"),
        "workdir_list" to ("workdir" to "list"),
        "workdir_read" to ("workdir" to "read"),
        "workdir_write" to ("workdir" to "write"),
        "workdir_grep" to ("workdir" to "grep"),
        "workdir_head" to ("workdir" to "head"),
        "workdir_stats" to ("workdir" to "stats"),
        "file_list" to ("file" to "list"),
        "file_read" to ("file" to "read"),
        "file_info" to ("file" to "info"),
        "file_write" to ("file" to "write"),
        "ssh_upload" to ("file" to "upload"),
        "ssh_download" to ("file" to "download"),
        "ssh_ls" to ("file" to "ls")
    )

    /** H3(2026-10-03): 危险工具确认名单: 工具名 或 工具:action; 命中即需用户确认 */
    private val DANGER_CONFIRM_TOOLS = setOf(
        "ssh_run", "sh_run", "js_run", "web_download",
        "file:write", "file:upload", "workdir:write",
        "browser:click", "browser:type", "browser:upload", "browser:clear_cache", "browser:save_cookies",
        "app:click", "app:text", "app:tap", "app:launch",
        "security_set"
    )

    /** H3 确认指纹: sha256(工具名+参数) 前12位; 参数变更则指纹失效需重新确认 */
    private fun dangerFingerprint(name: String, arg: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val d = md.digest("$name\n$arg".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(12)
    }

    fun execute(context: Context, name: String, argRaw: String, hotLoaded: MutableSet<String>): String {
        val arg0 = normalizeArgs(name, argRaw)
        // 旧工具名兼容: 映射到新复合工具并注入 action
        val legacy = LEGACY_TOOL_ACTION[name.trim('_')]
        val n = if (legacy != null) legacy.first
                else LocalEngine.toolRegistry.firstOrNull { it.name == name }?.name ?: name.trim('_')
        val arg = if (legacy != null) {
            try {
                val jo = JSONObject(arg0.trim())
                jo.put("action", legacy.second)
                jo.toString()
            } catch (e: Exception) {
                // 裸字符串参数(文本协议): open 按 URL 处理, 其余仅带 action
                if (legacy.second == "open" && arg0.isNotBlank()) "{\"action\":\"open\",\"url\":\"${arg0.trim()}\"}"
                else "{\"action\":\"${legacy.second}\"}"
            }
        } else arg0
        // 自热度统计(2026-09-14): 有效工具执行即 +1(含 MCP 动态工具), 纯本地不上云
        if (LocalEngine.toolRegistry.any { it.name == n } || McpClientManager.spec(n) != null) {
            ToolHotStore.recordHit(context, n)
        }
        // 硬门禁 v3(2026-10-04): 三档(strict/auto/off) + 双通道(气泡/通知) + 并发队列
        // 命中危险工具时本线程在此挂起: 等待用户决策——
        // 用户允许 → 签发票据并继续执行本调用; 用户拒绝 → 立即返回拒绝, 不执行工具;
        // 超时(2分钟) → 自动拒绝。strict 档每次确认(无窗口期), auto 档窗口期票据复用(5分钟)。
        // R3-1 修复(2026-10-04): 门禁判定/风险评级/票据 key 统一用复合键 name:action(如 browser:click)。
        // 原实现先用裸工具名(n=browser)判 needsConfirm → riskOf 匹配不上高危名单中的 browser:click,
        // auto 档恒 MEDIUM 直接放行, 内层复合键检查不可达; 现在 key0 先算好再进判定, 票据同 key 闭环。
        val jo0 = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
        val act0 = jo0?.optString("action", "").orEmpty()
        val key0 = if (act0.isNotEmpty()) "$n:$act0" else n
        if (SecurityConfig.needsConfirm(context, key0, arg)) {
            if (key0 in DANGER_CONFIRM_TOOLS || n in DANGER_CONFIRM_TOOLS) {
                val risk = SecurityConfig.riskOf(key0)
                when (SecurityUi.requestConfirm(context, key0, arg, risk)) {
                    true -> { /* 允许: 票据已在决策回调中签发, 继续执行本调用 */ }
                    false -> return "【安全确认】用户拒绝了工具 [$n] 的执行请求，本次调用已停止。如需执行，请用户重新发起。"
                    null -> return "【安全确认】确认通道不可用(应用不在前台且通知被禁用)，请在打开 App 后重试本调用。"
                }
            }
        }
        return when (n) {
            "get_time" -> MemoryTools.getTime()
            "calc" -> MemoryTools.calc(arg)
            "memory_search" -> MemoryTools.search(context, arg)
            "ssh_run" -> SshTools.run(context, arg)
            "file" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                when (jo?.optString("action")) {
                    "list" -> FileTools.list(context, arg)
                    "read" -> FileTools.read(context, arg)
                    "info" -> FileTools.info(context, arg)
                    "write" -> FileTools.write(context, arg)
                    "upload" -> SshTools.upload(context, arg)
                    "download" -> SshTools.download(context, arg)
                    "ls" -> SshTools.ls(context, arg)
                    else -> "file 需指定 action: list/read/info/write/upload/download/ls"
                }
            }
            "web_download" -> WebTools.save(context, arg)
            "attach_read" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                val raw = jo?.optString("name") ?: ""
                val name = raw.removePrefix("att://")
                if (name.isBlank()) return@run "错误: 缺少 name(附件文件名)"
                val offset = jo?.optInt("offset", 0) ?: 0
                val limit = jo?.optInt("limit", 4000) ?: 4000
                val total = AttachmentStore.textLength(context, name)
                if (total < 0) return@run "错误: 附件不存在或非UTF-8文本: $name"
                val chunk = AttachmentStore.readTextChunk(context, name, offset, limit)
                    ?: return@run "错误: 读取失败或 offset 超出文件末尾(共 $total 字符)"
                val next = (offset + chunk.length).coerceAtMost(total)
                "[${name}] 第 ${offset + 1}..$next 字符 / 共 $total 字符(已读 ${((next.toFloat() / total) * 100).toInt()}%):\n$chunk"
            }
            "workdir" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                when (jo?.optString("action")) {
                    "list" -> WorkTools.list(context, arg)
                    "read" -> WorkTools.read(context, arg)
                    "write" -> WorkTools.write(context, arg)
                    "grep" -> WorkTools.grep(context, arg)
                    "head" -> WorkTools.head(context, arg)
                    "stats" -> WorkTools.stats(context, arg)
                    else -> "workdir 需指定 action: list/read/write/grep/head/stats"
                }
            }
            "video_frame" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                val rawName = (jo?.optString("name") ?: arg.trim()).removePrefix("att://")
                if (rawName.isBlank()) return@run "错误: 缺少 name(附件视频文件名)"
                val timeMs = jo?.optLong("timeMs", 0L) ?: 0L
                val f = AttachmentStore.fileOf(context, rawName) ?: return@run "错误: 附件未落盘(超预算大视频落库后才可抽帧): $rawName"
                val frame = videoFrameAt(f, timeMs) ?: return@run "错误: 取帧失败(时间点超出范围或解码不支持): $rawName"
                val w = frame.width; val h = frame.height
                val maxSide = maxOf(w, h)
                val scale = if (maxSide > 640) 640f / maxSide else 1f
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(frame, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else frame
                if (bmp != frame) frame.recycle()
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                bmp.recycle()
                val sec = (timeMs + 500) / 1000
                "[video_frame] $rawName @${sec}s 已抽帧(${w}x${h}), 帧图已注入本轮供查看 FRAME:data:image/jpeg;base64,$b64"
            }
            "file_export" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                val raw = (jo?.optString("name") ?: arg.trim()).removePrefix("att://")
                if (raw.isBlank()) return@run "错误: 缺少 name(附件文件名)"
                val f = AttachmentStore.fileOf(context, raw) ?: return@run "错误: 附件不存在: $raw"
                val bytes = try { f.readBytes() } catch (e: Exception) { return@run "错误: 读取失败: $raw" }
                val ok = WorkDir.write(context, raw, bytes, WorkDir.SUB_DIR_FILES)
                if (ok) "已导出到工作目录 ${WorkDir.displaySubPath(WorkDir.SUB_DIR_FILES)}$raw (用户可见可改)" else "错误: 导出失败(工作目录不可写)"
            }
            "browser" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                when (jo?.optString("action")) {
                    "open" -> openBrowser(arg)
                    "scan" -> browserScan()
                    "text" -> browserText()
                    "scroll" -> browserScroll(arg)
                    "click" -> browserClick(arg)
                    "type" -> browserType(arg)
                    "upload" -> browserUpload(context, arg)
                    "clear_cache" -> browserClearCache(arg)
                    "save_cookies" -> browserSaveCookies(context, arg)
                    else -> "browser 需指定 action: open/scan/text/scroll/click/type/upload/clear_cache/save_cookies"
                }
            }
            "web_search" -> WebTools.search(arg)
            "web_fetch" -> WebTools.fetch(context, arg)
            "site_auth" -> WebTools.siteAuth(context, arg)
            "app" -> run {
                val jo = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
                when (jo?.optString("action")) {
                    "scan" -> UiControlService.scan()
                    "click" -> {
                        val idx = jo.optInt("index", -1)
                        if (idx < 0) "请指定 index(来自 app_scan 结果)" else UiControlService.click(idx)
                    }
                    "text" -> {
                        val idx = jo.optInt("index", -1)
                        val txt = jo.optString("text", "").orEmpty()
                        if (idx < 0 || txt.isEmpty()) "请指定 index 与 text(来自 app_scan 结果)" else UiControlService.type(idx, txt)
                    }
                    "back" -> UiControlService.back()
                    "home" -> UiControlService.home()
                    "launch" -> {
                        val pkg = jo.optString("pkg", "").orEmpty().trim()
                        if (pkg.isEmpty()) "请指定要启动的应用包名 pkg" else UiControlService.launch(context, pkg)
                    }
                    "installed" -> UiControlService.installed(context)
                    "tap" -> {
                        val x = jo.optInt("x", -1)
                        val y = jo.optInt("y", -1)
                        if (x < 0 || y < 0) "请指定坐标 x/y(px, 来自 app_screenshot 视觉识别)" else UiControlService.tap(x, y)
                    }
                    "screenshot" -> UiControlService.screenshot()
                    else -> "app 需指定 action: scan/click/text/tap/screenshot/back/home/launch/installed"
                }
            }
            "ask_user" -> askUser(context, argRaw)
            "js_run" -> run {
                val jo = try { JSONObject(argRaw.trim()) } catch (e: Exception) { null }
                val code = jo?.optString("code", "").orEmpty()
                val timeout = jo?.optLong("timeoutMs", 8000L) ?: 8000L
                if (code.isBlank()) "请指定 code(要执行的 JS 脚本)" else ScriptEngine.runJs(code, timeout)
            }
            "sh_run" -> ScriptEngine.runShConfirmed(context, arg)
            "security_set" -> SecurityConfig.set(context, arg)
            "tool_detail" -> {
                val tName = try { JSONObject(arg.trim()).optString("name", "").trim() } catch (e: Exception) { "" }
                if (tName.isEmpty()) return "请指定要查询的工具名 name(可参考 system 中的工具索引)"
                val spec = LocalEngine.toolRegistry.firstOrNull { it.name == tName }
                    ?: McpClientManager.spec(tName)?.let { (n, d, p) -> LocalEngine.ToolSpec(n, d, p) }
                    ?: return "未找到工具: $tName"
                hotLoaded.add(tName)
                return "工具 [$tName] 已临时激活并加入本轮回调列表, 现在可直接 function calling 调用。\n描述: ${spec.desc}\n参数格式: ${spec.params}"
            }
            else -> {
                // MCP 动态工具: 已注册则分发到对应服务, 未注册报未知
                if (McpClientManager.spec(name) != null) {
                    // 硬门禁 v3(2026-10-04): MCP 动态工具纳入门禁, 与内置危险工具同款三档+队列确认
                    val mcpKey = "mcp:$name"
                    if (SecurityConfig.needsConfirm(context, mcpKey, arg)) {
                        when (SecurityUi.requestConfirm(context, mcpKey, arg, SecurityConfig.riskOf(mcpKey))) {
                            true -> McpClientManager.callTool(context, name, arg)
                            false -> "【安全确认】用户拒绝了工具 [$name] 的执行请求，本次调用已停止。如需执行，请用户重新发起。"
                            null -> "【安全确认】确认通道不可用(应用不在前台且通知被禁用)，请在打开 App 后重试本调用。"
                        }
                    } else {
                        McpClientManager.callTool(context, name, arg)
                    }
                } else "未知工具: $name"
            }
        }
    }

    /**
     * 参数归一: 原生 function calling 的 arguments 是 JSON 字符串(如 {"expr":"17*23"}),
     * 而 calc/memory_search/ssh_run 的旧实现期望裸字符串, 这里抽字段转换;
     * 其余工具旧实现本身吃 JSON 字符串, 原样透传(JSON 与非 JSON 均兼容)。
     */
    /** open_browser 工具: 解析 url/搜索词参数并回调 MainActivity 打开全屏浏览器页 */
    private fun openBrowser(argRaw: String): String {
        var url: String? = null
        val t = argRaw.trim()
        if (t.startsWith("{")) {
            val json = try { org.json.JSONObject(t) } catch (e: Exception) { null }
            url = json?.optString("url")?.takeIf { it.isNotBlank() }
        } else if (t.isNotEmpty()) {
            url = t
        }
        // 空参数时返回当前状态, 不误开默认页
        if (url == null) return "请指定要打开的 URL 或搜索词"
        val cb = LocalEngine.onOpenBrowser
        if (cb == null) return "浏览器桥接未初始化"
        cb(url)
        return "已在全屏浏览器页打开: $url"
    }

    /** browser_text 工具: 读取整页可见文字 */
    private fun browserText(): String {
        val cb = LocalEngine.onBrowserText ?: return "浏览器桥接未初始化"
        return cb()
    }

    /** browser_scroll 工具: 滚动浏览器页 */
    private fun browserScroll(argRaw: String): String {
        val delta = try { JSONObject(argRaw.trim()).optInt("delta", 0) } catch (e: Exception) { 0 }
        if (delta == 0) return "请指定非零 delta(像素, 正数向下/负数向上)"
        val cb = LocalEngine.onBrowserScroll ?: return "浏览器桥接未初始化"
        return cb(delta)
    }

    /** browser_scan 工具: 触发重扫并同步返回当前元素清单 */
    private fun browserScan(): String {
        val cb = LocalEngine.onBrowserScan ?: return "浏览器桥接未初始化"
        return cb()
    }

    /** browser_click 工具: 点击第 index 个元素 */
    private fun browserClick(argRaw: String): String {
        val idx = try { JSONObject(argRaw.trim()).optInt("index", -1) } catch (e: Exception) { -1 }
        if (idx < 0) return "请指定 index(来自 browser_scan 结果)"
        val cb = LocalEngine.onBrowserClick ?: return "浏览器桥接未初始化"
        return cb(idx)
    }

    /** browser_type 工具: 向第 index 个元素输入文本 */
    private fun browserType(argRaw: String): String {
        val j = try { JSONObject(argRaw.trim()) } catch (e: Exception) { return "参数格式错误" }
        val idx = j.optInt("index", -1); val text = j.optString("text", "")
        val cb = LocalEngine.onBrowserType ?: return "浏览器桥接未初始化"
        if (idx < 0) return "请指定 index(来自 browser_scan 结果)"
        if (text.isBlank()) return "请指定要输入的 text"
        return cb(idx, text)
    }

    /** browser_upload 工具: 把工作目录文件注入浏览器页第 N 个 file input(配合 browser_scan 定位); 返回指令结果 */
    private fun browserUpload(context: Context, argRaw: String): String {
        val j = try { JSONObject(argRaw.trim()) } catch (e: Exception) { return "参数格式错误" }
        val idx = j.optInt("index", 0)
        val local = j.optString("local", "").trim()
        if (local.isBlank()) return "请指定要上传的工作目录文件名(local)"
        if (!WorkDir.exists(context, local)) return "工作目录不存在该文件: $local (可先用 workdir_list 查看可上传文件)"
        // M3 修复(2026-10-03): 上传前过敏感文件名规则, 防止 key/凭证等泄露到浏览器
        if (WorkDir.isSensitiveName(local)) return "已拦截: 文件名 [$local] 命中敏感文件规则(key/pem/凭证等), 禁止上传到浏览器, 防止泄露"
        val cb = LocalEngine.onBrowserUpload ?: return "浏览器桥接未初始化"
        return cb(idx, local)
    }

    /** browser_clear_cache 工具: 清浏览器页缓存(+登录Cookie), 交由 MainActivity 主线程执行并返回结果 */
    private fun browserClearCache(argRaw: String): String {
        val full = try { JSONObject(argRaw.trim()).optBoolean("full", false) } catch (e: Exception) { false }
        val cb = LocalEngine.onBrowserClear ?: return "浏览器桥接未初始化"
        return cb(full)
    }

    /** browser_save_cookies 工具: 把浏览器当前登录 Cookie 回灌进 site_auth.json(供 web_fetch/web_download 静默注入) */
    private fun browserSaveCookies(context: Context, argRaw: String): String {
        val site = runCatching { JSONObject(argRaw.trim()).optString("site").trim().ifBlank { null } }.getOrNull()
        val cb = LocalEngine.onBrowserSaveCookies ?: return "浏览器桥接未初始化"
        val raw = cb(site)
        if (raw.isBlank()) return "浏览器页未打开或该站点无登录态 Cookie 可取；请先用 open_browser 打开并登录目标站点后再调用"
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") }
        return if (site != null) {
            WebTools.siteAuth(context, "{\"action\":\"set\",\"site\":\"${esc(site)}\",\"cookie\":\"${esc(raw)}\"}")
        } else {
            // 未显式指定 site: BrowserPage 约定返回 "域名\tcookie"
            val idx = raw.indexOf('\t')
            if (idx <= 0) return "未获取到有效站点信息: $raw"
            val d = raw.substring(0, idx); val ck = raw.substring(idx + 1)
            if (ck.isBlank()) return "浏览器当前页($d)无登录态 Cookie; 请先在浏览器页登录该站点"
            WebTools.siteAuth(context, "{\"action\":\"set\",\"site\":\"${esc(d)}\",\"cookie\":\"${esc(ck)}\"}")
        }
    }

    /** ask_user 工具: 解析 question/options/allow_custom, 经 LocalEngine.onAskUser 桥接弹原生选择框, 返回用户选择(或降级说明) */
    private fun askUser(context: Context, argRaw: String): String {
        val j = try { JSONObject(argRaw.trim()) } catch (e: Exception) { return "参数格式错误(需 JSON: question/options/allow_custom)" }
        val q = j.optString("question").trim()
        if (q.isEmpty()) return "请指定 question(要向用户确认的问题)"
        val opts = ArrayList<String>()
        val arr = j.optJSONArray("options")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optString(i).trim()
            if (o.isNotEmpty()) opts.add(o)
        }
        if (opts.size < 2) return "请提供至少 2 个候选选项(options)"
        val allowCustom = j.optBoolean("allow_custom", true)
        // 总开关: 用户关闭澄清后, AI 不得再弹窗, 自行判断继续
        if (!AskUserConfig.enabled(context)) {
            return "[ask_user 已关闭] 用户开启了「无需澄清」模式, 请基于已有信息自行判断继续, 不要再次调用 ask_user"
        }
        val cb = LocalEngine.onAskUser
        if (cb == null) {
            return "[ask_user] 需要向你确认: $q (当前无界面可弹窗, AI 请基于已有信息自行判断继续)"
        }
        return cb(q, opts, allowCustom)
    }

    internal fun normalizeArgs(name: String, argRaw: String): String {
        val t = argRaw.trim()
        if (!t.startsWith("{")) return t  // 已是裸字符串(文本协议兜底路径)
        val json = try { JSONObject(t) } catch (e: Exception) { return t }
        return when (name) {
            "calc" -> json.optString("expr").takeIf { it.isNotBlank() } ?: t
            "memory_search" -> json.optString("query").takeIf { it.isNotBlank() } ?: t
            "ssh_run" -> {
                val cmd = json.optString("command").takeIf { it.isNotBlank() }
                if (cmd != null) cmd else {
                    // 兼容传 conn+cmd 拆分写法
                    val conn = json.optString("conn").takeIf { it.isNotBlank() }
                    val c = json.optString("cmd").takeIf { it.isNotBlank() }
                    when {
                        conn != null && c != null -> "$conn:$c"
                        else -> t
                    }
                }
            }
            else -> t
        }
    }


}
