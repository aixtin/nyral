package io.github.aixtin.droidagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * LocalEngine: 本地两步式路由 + 流式输出 + 工具调用循环 (v3.0)
 *
 * v3.0 核心变更(2026-09-10): 工具调度层从"自研文本协议"迁移到「原生 function calling」。
 *   - 旧版(v2.x)靠模型输出 TOOL:/思考:/答案: 前缀 + XML <tool_call> 兜底解析识别工具,
 *     模型输出稍有偏差(代码块包裹/JSON 包裹/多行/伪标签)即解析失败, 是"输出一半停、幻觉工具、
 *     tool_calls 不一致"的总根因;
 *   - 新版把全部工具(内置+MCP动态)注册为 OpenAI 兼容 tools JSON Schema, 请求携带 tools 字段,
 *     模型按原生 tool_calls 原语返回结构化 name+arguments, SSE 解析 delta.tool_calls 增量累积,
 *     工具结果按 role=tool + tool_call_id 回填, 走标准多轮迭代。
 *   - 兼容兜底: 请求返回 4xx 提示 tools 不支持时, 进程内降级为无 tools 纯文本模式,
 *     并按 v2 文本协议(TOOL:/思考:/答案:)兜底识别工具调用, 保证旧/第三方模型不丢功能。
 *   - UI Callback / Attachment / AttachmentStore / 记忆注入 / 断线重连 / token 统计全部保持不变。
 */
object LocalEngine {

    // 40: 适配扫描项目/批量检索类长任务(40 次足够覆盖 workdir_grep->head->read->write->upload 全链路)
    private const val MAX_TOOL_CALLS = 40

    // 请求级断线自动重连: 模型请求因网络中断零输出时自动重发一次(共 2 次尝试), 无需用户手动"继续"
    private const val MAX_NET_RETRY = 2

    // 单次完整回复输出上限(字符): 防模型超长输出/多轮 tool 累积导致上下文顶爆窗口; 超限截断并提示
    private const val MAX_OUTPUT_CHARS = 60000

    /** 输出截断: 超过上限截断并追加提示, 防单条超长注入顶爆上下文窗口 */
    private fun capOut(s: String): String =
        if (s.length > MAX_OUTPUT_CHARS) s.take(MAX_OUTPUT_CHARS) + "\n…[输出过长已截断]" else s

    /** 网络层可重试异常: 请求级断线, 整个 chat 流程自动重跑一次 */
    private class RetryableException(msg: String) : RuntimeException(msg)

    /** 当前 provider 不支持 tools: 已降级文本协议标志, 立即重跑请求(不计入网络重试额度) */
    private class NoToolsException(msg: String) : RuntimeException(msg)

    /** 发送附件: mime 类型 + Base64 内容 + 文件名; 图片走 image_url, 音频走 input_audio, 其余走 input_file;
     *  text 为附件本地解析出的纯文本(如 PDF 提取内容), 非空时随 history 一并注入给模型;
     *  isVoice 标记该音频来自本地录音(需展示微信式语音气泡), 上传的音频文件为 false(展示为文件卡片) */
    data class Attachment(val mime: String, val base64: String, val name: String = "attachment", val text: String? = null, val isVoice: Boolean = false, val pdfSourceName: String? = null)

    /** 取消状态: requestCancel() 置 true, 引擎在流式读取/工具循环处检查并中断 */
    @Volatile
    var cancelRequested = false

    /** 浏览器页桥接: AI 调用 open_browser 时由 MainActivity 注册回调打开全屏浏览器页(需主线程执行) */
    @Volatile
    var onOpenBrowser: ((url: String?) -> Unit)? = null

    /** 浏览器页操作桥接(scan/click/type): MainActivity 注册回调, 内部 post 主线程执行并同步返回文本结果 */
    @Volatile
    var onBrowserScan: (() -> String)? = null
    @Volatile
    var onBrowserClick: ((Int) -> String)? = null
    @Volatile
    var onBrowserType: ((Int, String) -> String)? = null
    @Volatile
    var onBrowserUpload: ((Int, String) -> String)? = null

    /** 浏览器页清缓存桥接: AI 调用 browser_clear_cache 时由 MainActivity 注册回调, 内部主线程执行并返回结果 */
    @Volatile
    var onBrowserClear: ((Boolean) -> String)? = null

    /** 浏览器登录态 Cookie 回灌桥接: AI 调用 browser_save_cookies 时取浏览器当前登录 Cookie(主线程), site 非空按该域、为空取当前页域名 */
    @Volatile
    var onBrowserSaveCookies: ((site: String?) -> String)? = null

    /** 当前活跃连接, 取消时 disconnect 以打断阻塞读 */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 请求停止当前 AI 输出/工具循环 */
    fun requestCancel() {
        cancelRequested = true
        activeConn?.disconnect()
    }

    private val toolRegistry = listOf(
        ToolSpec("web_search", "后台静默联网搜索(Bing), 结果仅供AI参考阅读, 用户看不到页面; 若用户想看搜索结果页/网页请改用 open_browser", "JSON: {\"q\":\"搜索关键词\",\"max_results\":5}"),
        ToolSpec("open_browser", "在用户手机上打开全屏浏览器页并加载网页; 若传入的是非URL文本则自动作为搜索词打开百度搜索。当用户要求搜索/查资料/看网页时优先用本工具, 直接在手机屏幕展示可看到的搜索页(用户可见)", "JSON: {\"url\":\"https://... 或 搜索词\"}"),
        ToolSpec("browser_scan", "读取全屏浏览器页当前已识别的可操作元素清单(带 [索引+坐标]), 供后续 browser_click/browser_type 定位; 页面刚加载时若返回'暂无元素'可稍后再调一次(页面加载完成后自动扫描)", "无参数"),
        ToolSpec("browser_click", "在全屏浏览器页点击第 N 个可操作元素(索引来自 browser_scan 结果, 0 起); 点击后如需确认页面变化可再调 browser_scan", "JSON: {\"index\":0}"),
        ToolSpec("browser_type", "向全屏浏览器页第 N 个可操作元素(输入框/富文本)输入文本, 索引来自 browser_scan 结果, 0 起; 支持搜索框/登录表单等", "JSON: {\"index\":0,\"text\":\"要输入的文本\"}"),
        ToolSpec("browser_upload", "向全屏浏览器页第 N 个文件选择框上传工作目录(Download/DroidAgent_work)里的文件/图片; index 来自 browser_scan(若扫描不到 file input 则按页面第 N 个 input[type=file] 定位, 默认0), local 为工作目录内文件名", "JSON: {\"index\":0,\"local\":\"文件名\"}"),
        ToolSpec("browser_clear_cache", "清除全屏浏览器页的缓存并强制刷新当前页; full=true 时额外清除全部站点登录Cookie(会退出所有网站登录)。页面样式错乱/数据过期/正常刷新无效时使用。默认 false 只清普通缓存不动登录", "JSON: {\"full\":false}"),
        ToolSpec("browser_save_cookies", "把全屏浏览器页当前登录态的 Cookie 存入 site_auth.json(供 web_fetch/web_download 静默抓取自动注入登录态); site 传目标域名, 不传则自动取浏览器当前页域名。用于\"浏览器登录一次→静默通道带登录态\": 先 open_browser 登录目标站点, 再调用本工具回灌", "JSON: {\"site\":\"可选域名\"}"),
        ToolSpec("web_fetch", "抓取网页并提取正文文本; 若 site_auth.json 已配置该域名 Cookie 会自动注入, 无需重复传", "JSON: {\"url\":\"https://...\",\"max_chars\":3000}"),
        ToolSpec("site_auth", "管理站点登录凭据(存 site_auth.json, 供 web_fetch/web_download 自动注入 Cookie)", "JSON: {\"action\":\"list\"} 或 {\"action\":\"set\",\"site\":\"域名\",\"cookie\":\"完整Cookie字符串\"} 或 {\"action\":\"del\",\"site\":\"域名\"}"),
        ToolSpec("get_time", "获取当前日期时间", "无参数"),
        ToolSpec("calc", "数学计算", "JSON: {\"expr\":\"表达式\"} 如 {\"expr\":\"17*23\"}"),
        ToolSpec("memory_search", "语义检索本地记忆", "JSON: {\"query\":\"查询内容\"}"),
        ToolSpec("ssh_run", "通过SSH在远程主机执行命令, 格式: 连接名:命令(连接名见下方可用SSH连接); 经跳板机(标注\"经跳板\")的连接只需指定连接名, 跳板自动处理, 不要自行添加跳板参数", "JSON: {\"command\":\"连接名:命令\"} 如 {\"command\":\"vps:ls /\"}"),
        ToolSpec("file_list", "列出远程目录文件", "JSON: {\"conn\":\"连接名\",\"path\":\"/目录\"}"),
        ToolSpec("file_read", "读取远程文件内容", "JSON: {\"conn\":\"连接名\",\"path\":\"/文件\",\"lines\":200}"),
        ToolSpec("file_info", "查看远程文件详情(类型/大小/权限/修改时间)", "JSON: {\"conn\":\"连接名\",\"path\":\"/文件\"}"),
        ToolSpec("file_write", "写入或追加远程文件内容", "JSON: {\"conn\":\"连接名\",\"path\":\"/文件\",\"content\":\"内容\",\"append\":false}"),
        ToolSpec("ssh_upload", "SFTP上传: 把手机工作目录文件传到远端", "JSON: {\"conn\":\"连接名\",\"local\":\"工作目录文件名\",\"remote\":\"/远端/绝对/路径\"}"),
        ToolSpec("ssh_download", "SFTP下载: 把远端文件拉到手机工作目录", "JSON: {\"conn\":\"连接名\",\"remote\":\"/远端/绝对/路径\",\"local\":\"可选本地文件名(默认取远端文件名)\"}"),
        ToolSpec("ssh_ls", "SFTP列远端目录(一级)", "JSON: {\"conn\":\"连接名\",\"path\":\"/目录\"}"),
        ToolSpec("web_download", "下载网页/文件并保存到手机工作目录; 返回\"下载成功\"即表示文件已落盘, 直接向用户报告结果, 不要再调用 workdir_list 等工具重复验证; site_auth.json 已配置的域名 Cookie 会自动注入", "JSON: {\"url\":\"https://...\",\"name\":\"可选文件名\"}"),
        ToolSpec("workdir_list", "列出手机工作目录(Download/DroidAgent_work)文件", "无参数"),
        ToolSpec("workdir_read", "读取手机工作目录文本文件内容", "JSON: {\"name\":\"文件名\"}"),
        ToolSpec("workdir_write", "写入手机工作目录文本文件(同名覆盖)", "JSON: {\"name\":\"文件名\",\"content\":\"内容\"}"),
        ToolSpec("workdir_grep", "全文搜索工作目录文本文件(批量, 一次代替多次 workdir_read); 扫描项目/找关键词优先用它", "JSON: {\"kw\":\"关键词\",\"ext\":\"可选按扩展名过滤如 .kt\",\"case\":false}"),
        ToolSpec("workdir_head", "读工作目录文件前 N 行/前 N 字符(批量查看, 代替全文读取防上下文爆炸)", "JSON: {\"name\":\"文件名\",\"lines\":50} 或 {\"name\":\"文件名\",\"chars\":3000}"),
        ToolSpec("workdir_stats", "工作目录统计概览(文件数/总大小/按类型分布), 扫描前先看全貌", "无参数")
    )

    /**
     * 上下文瘦身(2026-08-31): system 只注入"瘦索引"(工具名+一句话用途),
     * 完整参数 schema 在原生 function calling 模式下由 tools 字段承载(请求级),
     * 不占用对话 token; 文本协议降级模式才按需把完整 desc+params 追加进上下文。
     */
    private val toolIndex: Map<String, String> = mapOf(
        "web_search" to "后台静默检索(Bing), 结果仅AI参考, 用户看不到页面; 用户想看搜索页时用 open_browser",
        "open_browser" to "打开全屏浏览器页(用户可见): 用户要求搜索/查资料/看网页时优先用它, 传URL打开网页, 传搜索词直接打开百度搜索",
        "browser_scan" to "读浏览器页可操作元素清单(带索引), 供 click/type 定位",
        "browser_click" to "点击浏览器页第 N 个元素(...)",
        "browser_type" to "向浏览器页输入框输入文本(...)",
        "browser_upload" to "向网页文件选择框上传工作目录文件(配合 browser_scan 定位)",
        "browser_clear_cache" to "清浏览器页缓存并刷新(full=true 连登录Cookie一起清)",
        "browser_save_cookies" to "把浏览器当前登录 Cookie 存入 site_auth(供静默抓取带登录态); site 可选域名, 缺省取当前页域名",
        "web_fetch" to "抓取网页提取正文",
        "site_auth" to "管理站点登录 Cookie(site_auth.json)",
        "get_time" to "获取当前日期时间",
        "calc" to "数学计算",
        "memory_search" to "语义检索本地记忆",
        "ssh_run" to "SSH 远程执行命令",
        "file_list" to "列远程目录文件",
        "file_read" to "读远程文件内容",
        "file_info" to "查看远程文件详情",
        "file_write" to "写/追加远程文件",
        "ssh_upload" to "SFTP 上传(手机工作目录→远端)",
        "ssh_download" to "SFTP 下载(远端→手机工作目录)",
        "ssh_ls" to "SFTP 列远端目录",
        "web_download" to "下载网页/文件到手机工作目录",
        "workdir_list" to "列手机工作目录文件",
        "workdir_read" to "读工作目录文本文件",
        "workdir_write" to "写工作目录文本文件(覆盖)",
        "workdir_grep" to "全文搜索工作目录(批量)",
        "workdir_head" to "读文件前 N 行/字符(防上下文爆炸)",
        "workdir_stats" to "工作目录统计概览"
    )

    interface Callback {
        fun onThinkingStart()          // 思考段开始
        fun onThinkingDelta(text: String)  // 思考内容增量(打字机)
        fun onThinkingEnd()            // 思考段结束 -> UI 收缩
        fun onTool(name: String, arg: String)  // 正在执行工具
        fun onToolResult(name: String, result: String) {}  // 工具执行完成(结果回填, 默认空实现)
        fun onDelta(text: String)      // 正文增量(流式)
        fun onDone(reply: String)
        fun onError(msg: String)
    }

    // ================= 工具 Schema 注册(原生 function calling) =================

    /** 是否已探测到当前 provider 不支持 tools(进程级缓存): 命中后走文本协议纯文本模式 */
    @Volatile
    private var toolsUnsupported = false

    /** 上次探测 tools 能力的 providerId: 切换供应商时才重新探测, 避免本轮内重复 4xx 探测 */
    @Volatile
    private var toolsProbeProvider: String? = null

    /** provider 切换时重置 tools 能力探测(同一 provider 保持上次结果, 防止重复降级死循环) */
    private fun maybeResetToolsCapability() {
        val pid = ApiConfig.providerId()
        if (toolsProbeProvider != pid) {
            toolsProbeProvider = pid
            toolsUnsupported = false
        }
    }

    /** 构建 OpenAI 兼容 tools 数组: 内置工具 + MCP 动态工具 */
    private fun buildToolsArray(): JSONArray {
        val arr = JSONArray()
        for (spec in toolRegistry) {
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.desc)
                    .put("parameters", builtinSchema(spec.name))))
        }
        // MCP 动态工具: schema 直接用 server 下发的 JSON Schema
        for ((n, d, p) in McpClientManager.specEntries()) {
            val params = try { JSONObject(p) } catch (e: Exception) { JSONObject() }
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", n)
                    .put("description", d)
                    .put("parameters", params)))
        }
        return arr
    }

    /** 内置工具参数 JSON Schema(与 executeTool 的字段解析严格对应) */
    private fun builtinSchema(name: String): JSONObject {
        fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject {
            val properties = JSONObject()
            for ((k, v) in props) properties.put(k, v)
            return JSONObject().put("type", "object").put("properties", properties)
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
        }
        fun str(desc: String, required: Boolean = true, enums: List<String>? = null): JSONObject {
            val s = JSONObject().put("type", "string").put("description", desc)
            if (enums != null) s.put("enum", JSONArray(enums))
            return s
        }
        fun int(desc: String, def: Int? = null): JSONObject {
            val s = JSONObject().put("type", "integer").put("description", desc)
            if (def != null) s.put("default", def)
            return s
        }
        fun bool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)

        return when (name) {
            "open_browser" -> obj(listOf("url"), "url" to str("要打开的URL(以http开头)或直接填搜索关键词"))
            "browser_scan" -> obj()
            "browser_click" -> obj(listOf("index"),
                "index" to int("要点击的元素索引(0 起, 来自 browser_scan)"))
            "browser_type" -> obj(listOf("index", "text"),
                "index" to int("要输入的元素索引(0 起, 来自 browser_scan)"),
                "text" to str("要输入的文本"))
            "browser_upload" -> obj(listOf("index", "local"),
                "index" to int("文件选择框元素索引(0 起, 来自 browser_scan); 扫描不到 file input 时表示页面第 N 个 file input"),
                "local" to str("要上传的工作目录文件名(Download/DroidAgent_work 下)"))
            "browser_clear_cache" -> obj(listOf("full"),
                "full" to bool("true=连登录Cookie一起清除(退出所有网站登录); false=仅清页面缓存(默认)"))
            "browser_save_cookies" -> obj(listOf("site"),
                "site" to str("目标域名, 不传则自动取浏览器当前页域名"))

            "web_search" -> obj(listOf("q"),
                "q" to str("搜索关键词"),
                "max_results" to int("返回结果条数(1-10)", 5))
            "web_fetch" -> obj(listOf("url"),
                "url" to str("要抓取的网页 URL, 以 http:// 或 https:// 开头"),
                "max_chars" to int("提取正文最大字符数(默认3000)", 3000))
            "site_auth" -> obj(listOf("action"),
                "action" to str("操作: list 列出 / set 保存 / del 删除", enums = listOf("list", "set", "del")),
                "site" to str("站点域名, 如 annas-archive.org", required = false),
                "cookie" to str("完整 Cookie 字符串", required = false))
            "get_time" -> obj()
            "calc" -> obj(listOf("expr"), "expr" to str("数学表达式, 如 17*23"))
            "memory_search" -> obj(listOf("query"), "query" to str("要检索的记忆查询内容"))
            "ssh_run" -> obj(listOf("command"),
                "command" to str("连接名:命令, 连接名见可用SSH连接; 例如 vps:ls / 或 dev188:free -h"))
            "file_list" -> obj(listOf("conn"),
                "conn" to str("SSH 连接名"),
                "path" to str("远程目录路径, 默认 ~", required = false))
            "file_read" -> obj(listOf("conn"),
                "conn" to str("SSH 连接名"),
                "path" to str("远程文件绝对路径"),
                "lines" to int("最多读取行数(默认200)", 200))
            "file_info" -> obj(listOf("conn"),
                "conn" to str("SSH 连接名"),
                "path" to str("远程文件路径"))
            "file_write" -> obj(listOf("conn"),
                "conn" to str("SSH 连接名"),
                "path" to str("远程文件绝对路径"),
                "content" to str("要写入的文件内容"),
                "append" to bool("true 追加, false 覆盖(默认false)"))
            "ssh_upload" -> obj(listOf("conn", "local", "remote"),
                "conn" to str("SSH 连接名"),
                "local" to str("手机工作目录文件名"),
                "remote" to str("远端绝对路径"))
            "ssh_download" -> obj(listOf("conn", "remote"),
                "conn" to str("SSH 连接名"),
                "remote" to str("远端绝对路径"),
                "local" to str("可选本地文件名, 默认取远端文件名", required = false))
            "ssh_ls" -> obj(listOf("conn"),
                "conn" to str("SSH 连接名"),
                "path" to str("远端目录, 默认 ~", required = false))
            "web_download" -> obj(listOf("url"),
                "url" to str("要下载的 URL"),
                "name" to str("可选保存文件名(不含路径分隔符)", required = false))
            "workdir_list" -> obj()
            "workdir_read" -> obj(listOf("name"), "name" to str("工作目录下的文件名"))
            "workdir_write" -> obj(listOf("name"),
                "name" to str("文件名"),
                "content" to str("文件内容"))
            "workdir_grep" -> obj(listOf("kw"),
                "kw" to str("要搜索的关键词"),
                "ext" to str("可选扩展名过滤, 如 .kt", required = false),
                "case" to bool("是否区分大小写(默认false)"))
            "workdir_head" -> obj(listOf("name"),
                "name" to str("文件名"),
                "lines" to int("读取前 N 行", -1),
                "chars" to int("读取前 N 字符", -1))
            "workdir_stats" -> obj()
            else -> obj()
        }
    }

    // ================= 流式主流程 =================

    /** 流式入口: 主线程调用, 回调全部发生在调用线程(工作线程), UI 需自行 post */
    fun chat(context: Context, history: String, cb: Callback, attachments: List<Attachment> = emptyList()) {
        var attempt = 0
        while (true) {
        try {
            McpClientManager.ensureLoaded(context)
            if (ApiConfig.apiKey().isBlank() && !ApiConfig.isCurrentLocal()) {
                cb.onError("未配置 API Key, 请先到「设置 → 模型配置」填写")
                return
            }
            if (ApiConfig.isCurrentLocal()) {
                cb.onError("本地 GGUF 推理引擎待接入，请先在模型配置中切换回 API 模型")
                return
            }
            // 轮首(每轮): 辅助模型判定是否需翻记忆, YES 才本地检索并注入主请求; 不依赖主模型自觉
            val memInject = MemoryGate.recall(context, history.takeLast(1500))
            var memoryInjected = memInject != null
            // 收尾兜底(仅"答案:"行触发): 本轮从未注入记忆时, 本地快检(毫秒级, 不依赖辅助模型), 命中则拦截重发
            val answerGate: () -> String? = {
                if (!memoryInjected) {
                    val q = MemoryGate.lastUserText(history)
                    val hits = MemoryTools.search(context, q)
                    if (hits.isNotBlank() && !hits.startsWith("记忆中暂无") && !hits.startsWith("记忆检索失败")) {
                        memoryInjected = true
                        "${MemoryGate.REF_PREFIX}(可能相关,以对话为准):\n$hits"
                    } else null
                } else null
            }
            val messages = buildRouteMessages(context, history, attachments, memInject)
            var toolCount = 0
            var retriedEmpty = false
            // 文本协议降级模式下已注入完整 schema 的工具集
            val injectedTools = mutableSetOf<String>()
            // provider 切换时重置 tools 能力探测(同一 provider 保持上次结果, 防止重复降级死循环)
            maybeResetToolsCapability()
            val full = StringBuilder()

            while (true) {
                if (cancelRequested) {
                    cb.onDone("")
                    return
                }
                val res = streamOnce(context, messages, cb, answerGate, injectedTools)
                val toolCalls = res.toolCalls
                if (toolCalls.isNotEmpty()) {
                    if (toolCount >= MAX_TOOL_CALLS) {
                        cb.onError("工具调用超过 $MAX_TOOL_CALLS 次, 已停止")
                        return
                    }
                    // 正则化执行本轮全部工具(可能并行多 call), 结果逐条回填 role=tool
                    val execResults = ArrayList<Pair<String, String>>() // (自定义显示行, 工具结果文本)
                    val schemaBlocks = StringBuilder()
                    for ((name, arg) in toolCalls) {
                        if (toolCount >= MAX_TOOL_CALLS) break
                        toolCount++
                        cb.onTool(name, arg)
                        val result = executeTool(context, name, arg)
                        cb.onToolResult(name, result)
                        execResults.add(name to result)
                        // 上下文瘦身仅文本协议模式用; 原生模式的 schema 在 tools 字段, 此处仅兜底补全
                        val schemaHint = if (!toolsUnsupported && injectedTools.add(name)) "" else {
                            toolRegistry.find { it.name == name }?.let { spec ->
                                "[参数说明:$name] ${spec.desc}\n参数格式: ${spec.params}"
                            } ?: McpClientManager.spec(name)?.let { (n, d, p) ->
                                "[参数说明:$n] $d\n参数格式(JSON Schema): $p"
                            } ?: ""
                        }
                        if (schemaHint.isNotBlank()) schemaBlocks.append(schemaHint).append("\n")
                    }
                    // 回填 assistant tool_calls 消息(含原始 toolCalls) + 逐条 tool 结果消息
                    messages.put(buildAssistantToolMessage(res, toolCalls))
                    for ((name, result) in execResults) {
                        val toolCallId = res.idOf(name) ?: "call_${name}_$toolCount"
                        messages.put(JSONObject().put("role", "tool")
                            .put("tool_call_id", toolCallId)
                            .put("content", capOut(result)))
                    }
                    // 若无有效输出且未达上限, 给模型一个继续指令
                    val schemaBlock = if (schemaBlocks.isEmpty()) "" else "本工具参数说明:\n$schemaBlocks"
                    messages.put(JSONObject().put("role", "user").put("content",
                        schemaBlock +
                        "已执行上述工具调用并返回结果。请根据结果继续: 若还需调用工具请继续发起 tool_calls; 若已能回答用户直接输出最终答案。"))
                    continue
                }
                if (res.restartWith != null) {
                    // 答案行被拦截: 追加已输出(思考等) + 记忆参考引导, 重发让模型基于记忆重新作答
                    memoryInjected = true
                    val asstContent = capOut(res.accumulated.trimEnd()).ifBlank { "（已进入作答阶段）" }
                    messages.put(JSONObject().put("role", "assistant").put("content", asstContent))
                    messages.put(JSONObject().put("role", "user").put("content",
                        "你刚才正要作答。请核对以下可能与问题相关的记忆后再回答" +
                            "(如记忆与当前事实冲突, 以当前信息为准; 若确实无关可忽略):\n${res.restartWith}"))
                    continue
                }
                if (toolCalls.isEmpty() && res.accumulated.isBlank() && toolCount > 0 && !retriedEmpty) {
                    // 模型对工具结果无有效输出: 引导重试一次, 避免"调了工具却没结果"
                    retriedEmpty = true
                    messages.put(JSONObject().put("role", "user").put("content",
                        "你刚才没有输出任何内容。请直接根据工具返回结果回答用户, 如仍需调用工具请继续发起 tool_calls。"))
                    continue
                }
                full.append(capOut(res.accumulated))
                cb.onDone(full.toString())
                return
            }
        } catch (e: NoToolsException) {
            // 当前 provider 不支持 tools: 已置降级标志, 立即重跑请求(不计入网络重试额度), 文本协议兜底接管
            android.util.Log.w("DroidAgent", "no-tools fallback retry (attempt=${attempt})")
            continue
        } catch (e: RetryableException) {
            // 请求级断线: 自动重连一次, 全程无输出, 无需用户手动"继续"
            attempt++
            if (attempt >= MAX_NET_RETRY) {
                android.util.Log.e("DroidAgent", "chat network retry exhausted", e)
                cb.onError(e.message ?: "模型连接中断(网络波动)，请重试")
                return
            }
            android.util.Log.w("DroidAgent", "chat network interrupted, auto-retry #$attempt: ${e.message}")
            cb.onDelta("\n\n[网络波动，已自动重连一次]")
            continue
        } catch (e: Exception) {
            if (cancelRequested) {
                // 用户主动停止: 不视为错误
                android.util.Log.i("DroidAgent", "chat cancelled by user")
                cb.onDone("")
                return
            }
            android.util.Log.e("DroidAgent", "chat error", e)
            cb.onError(e.message ?: "未知错误")
            return
        }
        }
    }

    /** 组装 assistant 的 tool_calls 消息(标准 OpenAPI 多轮回填必需) */
    private fun buildAssistantToolMessage(res: StreamResult, toolCalls: List<Pair<String, String>>): JSONObject {
        val tcArr = JSONArray()
        val accumulated = res.accumulated
        var idx = 0
        for ((name, arg) in toolCalls) {
            tcArr.put(JSONObject()
                .put("id", res.idOf(name) ?: "call_${name}_$idx")
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("arguments", arg)))
            idx++
        }
        // assistant 消息: content 可为空字符串(Native tool_calls 时通常为空); 若有残余正文也带上
        val content = if (accumulated.isBlank()) "" else capOut(accumulated.trimEnd())
        return JSONObject().put("role", "assistant")
            .put("content", content)
            .put("tool_calls", tcArr)
    }

    /**
     * 单次 SSE 流式请求, 返回累积文本 + 工具调用列表 + 记忆拦截。
     * 原生 function calling: 解析 delta.tool_calls 增量累积(支持并行多个 call)。
     * 文本协议兜底: 无 tools 时解析 TOOL:/思考:/答案: 前缀与 XML <tool_call>。
     */
    private fun streamOnce(
        context: Context,
        messages: JSONArray,
        cb: Callback,
        answerGate: () -> String?,
        injectedTools: MutableSet<String>
    ): StreamResult {
        val body = JSONObject()
        body.put("model", ApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.3)
        body.put("stream", true)
        // 原生 function calling: 请求携带 tools(模型支持时); probe 失败过则降级纯文本
        val useTools = !toolsUnsupported
        if (useTools) body.put("tools", buildToolsArray())
        // DeepSeek 支持流式返回真实 usage(最后一块); 其余厂商未知, 不加避免报错, 靠本地估算
        if (ApiConfig.providerId() == "deepseek") {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        // 思考强度: 六档按当前供应商/模型映射厂商参数; 不支持/自动 时返回 null 不传
        ApiConfig.thinkingEffortParams(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.thinkingEffortOf(ApiConfig.providerId()))?.let { params ->
            val keys = params.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                body.put(k, params.get(k))
            }
        }
        // token 统计: 无法拿到真实 usage 时用字符估算(中文约 1 token/字符, 英文约 4 字符/token, 取 /3 折中)
        val promptEst = body.toString().length / 3
        var usedPrompt = 0L
        var usedCompletion = 0L

        val conn = URL(ApiConfig.chatUrl()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        when (ApiConfig.authTypeOf(ApiConfig.providerId())) {
            "x-api-key" -> conn.setRequestProperty("x-api-key", ApiConfig.apiKey())
            "header" -> conn.setRequestProperty(ApiConfig.authHeaderOf(ApiConfig.providerId()), ApiConfig.apiKey())
            else -> conn.setRequestProperty("Authorization", "Bearer ${ApiConfig.apiKey()}")
        }
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        activeConn = conn
        val accumulated = StringBuilder()
        val toolCalls = mutableListOf<Pair<String, String>>()
        val toolCallIds = HashMap<String, String>()  // name -> tool_call_id (原生模式回填用)
        val restartOut = ArrayList<String>()
        var reader: BufferedReader? = null
        try {
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "(无响应体)"
            conn.disconnect()
            // 4xx 中疑似"不支持 tools/function calling"的报错 -> 降级纯文本模式重试一次
            if (code in 400..499 && useTools && looksLikeToolsUnsupported(err)) {
                toolsUnsupported = true
                android.util.Log.w("DroidAgent", "provider 不支持 tools, 降级文本协议: ${err.take(200)}")
                throw NoToolsException("PROVIDER_NO_TOOLS")
            }
            throw RuntimeException("API $code: $err")
        }
        reader = BufferedReader(InputStreamReader(conn.inputStream))
        val lineBuf = StringBuilder()
        // XML 工具调用累积缓冲: 部分模型输出 <tool_call><tool_name>x</tool_name><param>...</param></tool_call> 跨行格式
        var xmlBuf: StringBuilder? = null
        var mode = MODE_NONE
        var aborted = false
        // 原生 function calling 增量累积: index -> (id, name, arguments)
        val nativeCalls = HashMap<Int, NativeCallAcc>()
        var finishedByToolCalls = false

        try {
        while (true) {
            if (cancelRequested) throw CancellationException("cancelled by user")
            val line = reader!!.readLine() ?: break
            android.util.Log.v("DroidAgent", "SSE: $line")
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val parsed = try { JSONObject(data) } catch (e: Exception) { null } ?: continue
            val choices = parsed.optJSONArray("choices")
            val usage = parsed.optJSONObject("usage")
            // 流式结束的 usage chunk: 携带真实 token 数(DeepSeek 在 finish_reason=stop 的块返回 usage)
            if (usage != null && usage.optLong("prompt_tokens", 0) > 0) {
                usedPrompt = usage.optLong("prompt_tokens", 0)
                usedCompletion = usage.optLong("completion_tokens", 0)
            }
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val fr = choice.optString("finish_reason")
                if (fr == "tool_calls") finishedByToolCalls = true
                val delta = choice.optJSONObject("delta")
                // 原生 function calling: delta.tool_calls 增量(分片 arguments)
                if (delta != null && !delta.isNull("tool_calls")) {
                    val calls = delta.getJSONArray("tool_calls")
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val idx = c.optInt("index", i)
                        val acc = nativeCalls.getOrPut(idx) { NativeCallAcc() }
                        if (!c.isNull("id")) acc.id = c.optString("id")
                        val fn = c.optJSONObject("function")
                        if (fn != null) {
                            if (!fn.isNull("name") && fn.optString("name").isNotBlank()) acc.name = fn.optString("name")
                            if (!fn.isNull("arguments")) acc.arguments.append(fn.optString("arguments"))
                        }
                    }
                    // 命中 tool_calls 增量即说明思考终结
                    if (mode == MODE_THINKING) { mode = MODE_NONE; cb.onThinkingEnd() }
                    continue
                }
                // 推理模型(如小米MiMo): 思考内容在 reasoning_content, 正文在 content
                val rText = if (delta != null && !delta.isNull("reasoning_content")) delta.optString("reasoning_content") else ""
                if (rText.isNotEmpty()) {
                    if (mode != MODE_THINKING) {
                        mode = MODE_THINKING
                        cb.onThinkingStart()
                    }
                    cb.onThinkingDelta(rText)
                    continue
                }
                val text = if (delta != null && !delta.isNull("content")) delta.optString("content") else ""
                if (text.isEmpty()) continue
                // content 出现即思考结束
                if (mode == MODE_THINKING) {
                    mode = MODE_NONE
                    cb.onThinkingEnd()
                }

                lineBuf.append(text)
                while (true) {
                    val nl = lineBuf.indexOf("\n")
                    if (nl < 0) break
                    val row = lineBuf.substring(0, nl).trimEnd()
                    lineBuf.delete(0, nl + 1)
                    // 文本协议兜底(仅无 tools 模式启用): 不再解析 XML/TOOL: 作为主要路径,
                    // 但保留兼容——若模型仍按旧协议输出可识别
                    if (!useTools) {
                        if (xmlBuf != null || row.trimStart().startsWith("<tool_call") || row.trimStart().startsWith("<tool_name>")) {
                            if (xmlBuf == null) xmlBuf = StringBuilder()
                            xmlBuf!!.append(row).append('\n')
                            if (xmlBuf!!.contains("</tool_call>")) {
                                val xml = xmlBuf!!.toString()
                                xmlBuf = null
                                val t = parseXmlToolCall(xml)
                                if (t != null) {
                                    if (mode == MODE_THINKING) cb.onThinkingEnd()
                                    mode = MODE_NONE
                                    toolCalls.add(t)
                                    toolCallIds[t.first] = "xml_${toolCalls.size}"
                                    break
                                }
                                if (mode == MODE_CONTENT) { cb.onDelta(xml); accumulated.append(xml) }
                            }
                            continue
                        }
                        if (row.isBlank()) {
                            if (mode == MODE_CONTENT) { cb.onDelta("\n"); accumulated.append('\n') }
                            continue
                        }
                        val stop = processRow(row, mode, accumulated, cb, { m -> mode = m }, answerGate, restartOut)
                        if (stop != null) {
                            val t = stop
                            // 原生名称归一: 文本协议也可能输出带引号/引用的工具名
                            toolCalls.add(t)
                            toolCallIds[t.first] = "txt_${toolCalls.size}"
                        }
                        if (restartOut.isNotEmpty() || toolCalls.isNotEmpty()) break
                    } else {
                        // 原生 tools 模式: content 纯正文直接流式(无需行级协议解析, 但保留换行)
                        if (mode != MODE_CONTENT) {
                            if (mode == MODE_THINKING) cb.onThinkingEnd()
                            mode = MODE_CONTENT
                        }
                        val out = if (row.endsWith("\r")) row.dropLast(1) else row
                        cb.onDelta(out + "\n"); accumulated.append(out).append('\n')
                    }
                }
                if (restartOut.isNotEmpty()) break
                // 原生模式: delta.tool_calls 出现即本轮应进入工具循环, 尽早打断剩余流(通常已无正文)
                if (!useTools && toolCalls.isNotEmpty()) break
            }
            if (!useTools && (toolCalls.isNotEmpty() || restartOut.isNotEmpty())) break
            if (useTools && finishedByToolCalls && nativeCalls.isNotEmpty()) break
        }
        } catch (e: SocketException) {
            aborted = true
            android.util.Log.w("DroidAgent", "SSE connection aborted: ${e.message}")
        } catch (e: IOException) {
            aborted = true
            android.util.Log.w("DroidAgent", "SSE read error: ${e.message}")
        }
        // 连接中断收尾
        if (aborted) {
            if (mode == MODE_THINKING) cb.onThinkingEnd()
            if (toolCalls.isEmpty() && nativeCalls.isEmpty() && accumulated.isBlank()) {
                throw RetryableException("模型连接中断(网络波动)")
            }
            if (toolCalls.isEmpty() && nativeCalls.isEmpty() && accumulated.isNotBlank()) {
                cb.onDelta("\n\n[连接中断，以上内容已保留]")
            }
        }
        android.util.Log.i("DroidAgent", "EOF lineBuf=[$lineBuf] mode=$mode nativeCalls=${nativeCalls.size} toolCalls=${toolCalls.size} aborted=$aborted")
        // 末尾残余(无换行的最后一段)
        if (toolCalls.isEmpty() && nativeCalls.isEmpty() && lineBuf.isNotBlank()) {
            if (useTools) {
                // 原生模式: 残余即纯正文直接流式
                if (mode != MODE_CONTENT) {
                    if (mode == MODE_THINKING) cb.onThinkingEnd()
                    mode = MODE_CONTENT
                }
                val tail = lineBuf.toString().trimEnd()
                cb.onDelta(tail); accumulated.append(tail)
            } else {
                val stop = processRow(lineBuf.toString().trimEnd(), mode, accumulated, cb, { m -> mode = m }, answerGate, restartOut)
                if (stop != null) {
                    toolCalls.add(stop)
                    toolCallIds[stop.first] = "txt_${toolCalls.size}"
                }
            }
        }
        // EOF 时 XML 缓冲残留
        if (!useTools && toolCalls.isEmpty() && xmlBuf != null) {
            val t = parseXmlToolCall(xmlBuf!!.toString())
            if (t != null) {
                toolCalls.add(t)
                toolCallIds[t.first] = "xml_${toolCalls.size}"
            }
        }
        // 原生 tool_calls: 流结束把累积结果转出(即使 finish_reason 未明确 tool_calls)
        if (useTools && nativeCalls.isNotEmpty()) {
            val idxs = nativeCalls.keys.sorted()
            for (idx in idxs) {
                val acc = nativeCalls[idx] ?: continue
                val name = acc.name.trim()
                if (name.isEmpty()) continue
                val args = acc.arguments.toString().trim()
                toolCalls.add(name to (if (args.isEmpty()) "{}" else args))
                toolCallIds[name] = acc.id.ifEmpty { "call_${name}_$idx" }
            }
            nativeCalls.clear()
        }
        android.util.Log.v("DroidAgent", "streamOnce done acc=[$accumulated] toolCalls=$toolCalls")
        // 思考段自然结束
        if (toolCalls.isEmpty() && mode == MODE_THINKING) cb.onThinkingEnd()
        // token 统计
        val promptTokens = if (usedPrompt > 0) usedPrompt else promptEst.toLong()
        val completionTokens = if (usedCompletion > 0) usedCompletion else accumulated.length / 3L
        TokenStore.record(context, promptTokens.toInt().coerceAtLeast(0), completionTokens.toInt().coerceAtLeast(0))
        } finally {
            try { reader?.close() } catch (_: Throwable) {}
            try { conn.disconnect() } catch (_: Throwable) {}
            activeConn = null
        }
        return StreamResult(accumulated.toString(), toolCalls, restartOut.firstOrNull(), toolCallIds)
    }

    /** 判断 4xx 响应是否疑似"不支持 tools" */
    private fun looksLikeToolsUnsupported(err: String): Boolean {
        val e = err.lowercase()
        return e.contains("tool") && (e.contains("not support") || e.contains("unsupported") ||
            e.contains("does not support") || e.contains("unknown parameter") || e.contains("extra parameter") ||
            e.contains("invalid") || e.contains("unknown field") || e.contains("additional properties") ||
            e.contains("400"))
    }

    /** 原生 tool_call 增量累积器 */
    private class NativeCallAcc {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    /** 处理一行输出(v2 文本协议兜底), 返回 null 继续, 返回 Pair 表示命中工具行需打断 */
    @Suppress("SameParameterValue")
    private fun processRow(
        row: String,
        mode: Int,
        accumulated: StringBuilder,
        cb: Callback,
        setMode: (Int) -> Unit,
        answerGate: () -> String?,
        restartOut: MutableList<String>
    ): Pair<String, String>? {
        when {
            row.startsWith("TOOL:") || row.startsWith("TOOL：") -> {
                if (mode == MODE_THINKING) cb.onThinkingEnd()
                setMode(MODE_NONE)
                return parseToolSpec(row.substring(5))
            }
            mode == MODE_NONE -> {
                when {
                    row.startsWith("思考:") || row.startsWith("思考：") -> {
                        if (ApiConfig.thinkingEffortOf(ApiConfig.providerId()) == ApiConfig.THINK_OFF) {
                            setMode(MODE_NONE)
                            return null
                        }
                        setMode(MODE_THINKING)
                        cb.onThinkingStart()
                        val body = row.substring(3).trim()
                        val ti = body.indexOf("TOOL:")
                        if (ti >= 0) {
                            val pre = body.substring(0, ti).trim()
                            if (pre.isNotEmpty()) cb.onThinkingDelta(pre)
                            cb.onThinkingEnd()
                            return parseToolSpec(body.substring(ti + 5))
                        }
                        if (body.isNotEmpty()) cb.onThinkingDelta(body)
                    }
                    row.startsWith("答案:") || row.startsWith("答案：") -> {
                        val gate = answerGate()
                        if (gate != null) {
                            restartOut.add(gate)
                            setMode(MODE_CONTENT)
                            return null
                        }
                        setMode(MODE_CONTENT)
                        val t = row.substring(3).trim()
                        if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                    }
                    else -> {
                        setMode(MODE_CONTENT)
                        cb.onDelta(row + "\n")
                        accumulated.append(row).append('\n')
                    }
                }
            }
            mode == MODE_THINKING -> {
                when {
                    row.startsWith("答案:") || row.startsWith("答案：") -> {
                        val gate = answerGate()
                        if (gate != null) {
                            restartOut.add(gate)
                            cb.onThinkingEnd()
                            setMode(MODE_CONTENT)
                            return null
                        }
                        setMode(MODE_CONTENT)
                        cb.onThinkingEnd()
                        val t = row.substring(3).trim()
                        if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                    }
                    else -> {
                        val ti = row.indexOf("TOOL:")
                        if (ti > 0) {
                            val pre = row.substring(0, ti)
                            if (pre.isNotBlank()) cb.onThinkingDelta(pre)
                            cb.onThinkingEnd()
                            return parseToolSpec(row.substring(ti + 5))
                        }
                        val idx = row.indexOf("答案:")
                        if (idx > 0) {
                            val pre = row.substring(0, idx)
                            if (pre.isNotBlank()) cb.onThinkingDelta(pre)
                            setMode(MODE_CONTENT)
                            cb.onThinkingEnd()
                            val t = row.substring(idx + 3).trim()
                            if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                        } else {
                            cb.onThinkingDelta(row)
                        }
                    }
                }
            }
            else -> { // CONTENT
                cb.onDelta(row + "\n")
                accumulated.append(row).append('\n')
            }
        }
        return null
    }

    /** 解析 TOOL: 后的规格: name|arg / name arg / name{json} / name:arg 全兼容 */
    private fun parseToolSpec(spec: String): Pair<String, String> {
        val t = spec.trim()
        val name = t.substringBefore("|").substringBefore(" ").substringBefore("{").trim()
        var arg = t.substringAfter("|", "").trim()
        if (arg.isEmpty()) {
            val rest = t.removePrefix(name).trim()
            if (rest.isNotEmpty()) arg = rest.removePrefix("|").trim()
        }
        return normalizeToolName(name) to arg
    }

    /** 解析 XML 工具调用: <tool_call><tool_name>x</tool_name><param>...</param></tool_call> (兼容单行/跨行/属性) */
    private fun parseXmlToolCall(xml: String): Pair<String, String>? {
        val name = Regex("<tool_name>\\s*([^<]+?)\\s*</tool_name>").find(xml)?.groupValues?.get(1)?.trim()
            ?: return null
        val arg = Regex("<param>([\\s\\S]*?)</param>").find(xml)?.groupValues?.get(1)?.trim() ?: ""
        return normalizeToolName(name) to arg
    }

    private fun buildRouteMessages(context: Context, history: String, attachments: List<Attachment> = emptyList(), memInject: String? = null): JSONArray {
        val messages = JSONArray()
        val sshList = SshConfigStore.load(context).joinToString("\n") {
            "- ${it.name}: ${it.user}@${it.host}:${it.port}" +
                (if (it.hasProxy) " (经跳板 ${it.proxyHost}:${it.proxyPort})" else "")
        }
        val sshHint = if (sshList.isBlank()) "无(可在SSH配置中添加)" else "\n$sshList"
        val thinkingOff = ApiConfig.thinkingEffortOf(ApiConfig.providerId()) == ApiConfig.THINK_OFF
        // 原生 function calling 的格式约定: 模型无需手动输出 TOOL: 前缀, 直接走原生 tool_calls;
        // 保留文本协议说明作为降级时模型兜底写法
        val fmtRules = if (thinkingOff) {
            "输出格式约定(重要):\n" +
            "1. 直接回答, 禁止输出『思考:』前缀或任何思考过程;\n" +
            "2. 需要调用工具时, 由系统提供的原生函数调用(function calling)发起, 无需手写『TOOL:』;\n" +
            "3. 需要正式回答时, 直接输出正文, 不要前缀。\n" +
            "收到工具结果后可继续发起函数调用或直接回答。一次(轮)可并行发起多个工具。"
        } else {
            "输出格式约定(重要):\n" +
            "1. 需要先思考时, 第一行以『思考:』开头输出思考内容, 一行一句(或使用模型原生 reasoning 能力);\n" +
            "2. 需要调用工具时, 使用系统提供的原生函数调用(function calling), 结构化的工具名与参数;\n" +
            "3. 思考结束要回答时, 直接输出正文(可用『答案:』开头), 不要写『TOOL:』。\n" +
            "收到工具结果后可继续思考或直接回答。一次(轮)可并行发起多个工具。"
        }
        val personaName = PersonaConfig.aiName()
        val persona = PersonaConfig.aiPersona()
        // 人设块: 独立成段置于 system 最前部, 强约束措辞; 留空使用内置默认人设
        val personaBlock = buildString {
            append("【人设设定·必须严格遵守, 优先于其他一切文本约定】\n")
            append("你的名字是" + personaName + "。")
            if (persona.isNotEmpty()) {
                append("\n" + persona)
            } else {
                append("\n你是一个智能 AI 助手, 名叫" + personaName + ", 回答干练、简洁、直接。")
            }
            append("\n请在每一轮回复中都切实遵守上述人设: 以该身份的口吻与行为方式回应, 不要脱离设定, 也不要复述本设定本身。\n\n")
        }
        // 聊天模式且关闭 Markdown 时: 强制纯文本正文, 禁止 Markdown(放开 MD 后长文答案自然用 MD 排版, 不再加禁令)
        val mdBan = if (ModeConfig.chatPlainText())
            "\n当前为聊天模式: 一律用纯文本自然语言回答, 禁止输出任何 Markdown 标记(如 # 标题、**加粗**、`代码`、- 列表、[链接](url)、表格等), 直接输出正文。"
        else
            "\n回答时鼓励使用 Markdown(如 # 标题、- 列表、`代码`、代码块、表格等)增强可读性; 涉及对比或数据时优先用表格呈现。"
        messages.put(JSONObject().put("role", "system").put("content",
            personaBlock +
            "根据用户需求选择工具。工具清单(名称+用途):\n" +
            "搜索策略(重要·三级)：1) 一般搜索默认先用 web_search 后台静默快查(不打断用户界面)，拿到标题+摘要直接汇报，用户没要求看页面就不要开浏览器展示页；2) 仅当用户明确要\"看页面/看结果页/进某站\"，或 web_search 无有效结果、需要登录态、卡验证码/登录墙时，才升级调用 open_browser 打开全屏浏览器页(用户可见)；3) 浏览器页内遇到验证码/登录墙：不要硬点，停下提示用户点底部\"接管\"按钮手动完成(输验证码/登录)，用户再点\"交还 AI\"后你可继续 browser_* 操作；用户登录成功后调用 browser_save_cookies 把该站点登录 Cookie 存入 site_auth.json，此后 web_fetch/web_download 静默抓取自动带登录态，无需再开浏览器。\n" +
            toolIndex.entries.joinToString("\n") { (n, d) -> "- $n: $d" } +
            buildMcpIndex() +
            "\n可用SSH连接:$sshHint\n" +
            "调用方式: 使用系统提供的 function calling 原生工具调用(工具名与 JSON 参数已由系统给出 schema), 一次(轮)可并行发起多个工具; 不要自己发明不存在的工具名。" +
            "记忆使用: 若所需信息可能来自与此用户过去的对话且当前上下文未提及, 应调用 memory_search 查证后再回答; 工具调用过程中拿不准时也先查记忆再作答。\n" +
            (if (memInject.isNullOrBlank()) "" else memInject + "\n") + fmtRules + mdBan))
        if (attachments.isEmpty()) {
            messages.put(JSONObject().put("role", "user").put("content", history))
        } else {
            // 多模态: content 为 parts 数组, 文本在前, 附件按类型映射为标准 OpenAI 兼容格式
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", history))
            for (a in attachments) {
                when {
                    a.mime.startsWith("image/") ->
                        parts.put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.base64}")))
                    a.mime.startsWith("audio/") -> {
                        // format/MIME 归一化: m4a/mp4/x-m4a→m4a, x-wav/wave→wav, mp3/mpeg→mp3, x-flac→flac, opus→ogg
                        val norm: Pair<String, String> = when {
                            a.mime.startsWith("audio/wav") || a.mime.startsWith("audio/x-wav") || a.mime.startsWith("audio/wave") -> "audio/wav" to "wav"
                            a.mime.startsWith("audio/mpeg") || a.mime.startsWith("audio/mp3") -> "audio/mpeg" to "mp3"
                            a.mime.startsWith("audio/mp4") || a.mime.startsWith("audio/x-m4a") || a.mime.startsWith("audio/m4a") -> "audio/mp4" to "m4a"
                            a.mime.startsWith("audio/flac") || a.mime.startsWith("audio/x-flac") -> "audio/flac" to "flac"
                            a.mime.startsWith("audio/ogg") || a.mime.startsWith("audio/opus") -> "audio/ogg" to "ogg"
                            else -> a.mime to a.mime.removePrefix("audio/").substringBefore("+").substringBefore(";")
                        }
                        parts.put(JSONObject().put("type", "input_audio")
                            .put("input_audio", JSONObject().put("data", "data:${norm.first};base64,${a.base64}").put("format", norm.second)))
                    }
                    a.mime.startsWith("video/") -> {
                        // MiMo 视频理解: content 数组 type=video_url, url 用 data:{mime};base64 内联
                        // 官方限制: base64 后 ≤50MB(原始约 ≤37MB), 支持 MP4/MOV/AVI/WMV
                        val vurl = JSONObject().put("url", "data:${a.mime};base64,${a.base64}")
                        parts.put(JSONObject().put("type", "video_url")
                            .put("video_url", vurl)
                            .put("fps", 2)
                            .put("media_resolution", "default"))
                    }
                    else -> {
                        // 文档类附件(PDF/Office/txt 等): 本地已提取纯文本并随 history 注入时不再发二进制
                        if (a.text.isNullOrBlank()) {
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[附件 ${a.name} 无法解析文本内容]"))
                        }
                    }
                }
            }
            if (parts.length() == 1) {
                messages.put(JSONObject().put("role", "user").put("content", history))
            } else {
                messages.put(JSONObject().put("role", "user").put("content", parts))
            }
        }
        return messages
    }

    private fun normalizeToolName(name: String): String = name.trim('_')

    /** MCP 工具索引块: 服务概览 + 各工具(描述已标注服务名), 无 MCP 工具时不输出 */
    private fun buildMcpIndex(): String {
        if (!McpClientManager.hasTools()) return ""
        val overview = McpClientManager.serverOverview().joinToString("; ")
        val lines = McpClientManager.indexLines().joinToString("\n") { (n, d) -> "  - $n: $d" }
        return "\n◆ MCP 外部工具(来自配置的 MCP 服务, 前缀即服务名, 多服务请按名称区分):\n" +
            (if (overview.isNotEmpty()) "  服务概览: $overview\n" else "") + lines + "\n"
    }

    /**
     * 执行工具。args 为 JSON 字符串(原生 function calling 的 arguments), 兼容旧裸字符串格式。
     * 特殊转发: calc/memory_search/ssh_run 需抽字段为裸字符串传给旧实现。
     */
    private fun executeTool(context: Context, name: String, argRaw: String): String {
        val arg = normalizeArgs(name, argRaw)
        // 归一工具名(允许下划线变体)
        val n = toolRegistry.firstOrNull { it.name == name }?.name ?: name.trim('_')
        return when (n) {
            "get_time" -> MemoryTools.getTime()
            "calc" -> MemoryTools.calc(arg)
            "memory_search" -> MemoryTools.search(context, arg)
            "ssh_run" -> SshTools.run(context, arg)
            "file_list" -> FileTools.list(context, arg)
            "file_read" -> FileTools.read(context, arg)
            "file_info" -> FileTools.info(context, arg)
            "file_write" -> FileTools.write(context, arg)
            "ssh_upload" -> SshTools.upload(context, arg)
            "ssh_download" -> SshTools.download(context, arg)
            "ssh_ls" -> SshTools.ls(context, arg)
            "web_download" -> WebTools.save(context, arg)
            "workdir_list" -> WorkTools.list(context, arg)
            "workdir_read" -> WorkTools.read(context, arg)
            "workdir_write" -> WorkTools.write(context, arg)
            "workdir_grep" -> WorkTools.grep(context, arg)
            "workdir_head" -> WorkTools.head(context, arg)
            "workdir_stats" -> WorkTools.stats(context, arg)
            "open_browser" -> openBrowser(arg)
            "browser_scan" -> browserScan()
            "browser_click" -> browserClick(arg)
            "browser_type" -> browserType(arg)
            "browser_upload" -> browserUpload(context, arg)
            "browser_clear_cache" -> browserClearCache(arg)
            "browser_save_cookies" -> browserSaveCookies(context, arg)
            "web_search" -> WebTools.search(arg)
            "web_fetch" -> WebTools.fetch(context, arg)
            "site_auth" -> WebTools.siteAuth(context, arg)
            else -> {
                // MCP 动态工具: 已注册则分发到对应服务, 未注册报未知
                if (McpClientManager.spec(name) != null) McpClientManager.callTool(context, name, arg)
                else "未知工具: $name"
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
        val cb = onOpenBrowser
        if (cb == null) return "浏览器桥接未初始化"
        cb(url)
        return "已在全屏浏览器页打开: $url"
    }

    /** browser_scan 工具: 触发重扫并同步返回当前元素清单 */
    private fun browserScan(): String {
        val cb = onBrowserScan ?: return "浏览器桥接未初始化"
        return cb()
    }

    /** browser_click 工具: 点击第 index 个元素 */
    private fun browserClick(argRaw: String): String {
        val idx = try { JSONObject(argRaw.trim()).optInt("index", -1) } catch (e: Exception) { -1 }
        if (idx < 0) return "请指定 index(来自 browser_scan 结果)"
        val cb = onBrowserClick ?: return "浏览器桥接未初始化"
        return cb(idx)
    }

    /** browser_type 工具: 向第 index 个元素输入文本 */
    private fun browserType(argRaw: String): String {
        val j = try { JSONObject(argRaw.trim()) } catch (e: Exception) { return "参数格式错误" }
        val idx = j.optInt("index", -1); val text = j.optString("text", "")
        val cb = onBrowserType ?: return "浏览器桥接未初始化"
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
        val cb = onBrowserUpload ?: return "浏览器桥接未初始化"
        return cb(idx, local)
    }

    /** browser_clear_cache 工具: 清浏览器页缓存(+登录Cookie), 交由 MainActivity 主线程执行并返回结果 */
    private fun browserClearCache(argRaw: String): String {
        val full = try { JSONObject(argRaw.trim()).optBoolean("full", false) } catch (e: Exception) { false }
        val cb = onBrowserClear ?: return "浏览器桥接未初始化"
        return cb(full)
    }

    /** browser_save_cookies 工具: 把浏览器当前登录 Cookie 回灌进 site_auth.json(供 web_fetch/web_download 静默注入) */
    private fun browserSaveCookies(context: Context, argRaw: String): String {
        val site = runCatching { JSONObject(argRaw.trim()).optString("site").trim().ifBlank { null } }.getOrNull()
        val cb = onBrowserSaveCookies ?: return "浏览器桥接未初始化"
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

    private fun normalizeArgs(name: String, argRaw: String): String {
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

    private data class StreamResult(
        val accumulated: String,
        val toolCalls: List<Pair<String, String>>,
        val restartWith: String? = null,
        val toolCallIds: Map<String, String> = emptyMap()
    ) {
        fun idOf(name: String): String? = toolCallIds[name]
    }

    data class ToolSpec(val name: String, val desc: String, val params: String)

    private const val MODE_NONE = 0
    private const val MODE_THINKING = 1
    private const val MODE_CONTENT = 2

    /** 调试服务/状态查询: 暴露工具清单(名称+描述+参数说明, 含 MCP 动态工具) */
    fun toolList(): List<ToolSpec> =
        toolRegistry + McpClientManager.specEntries().map { (n, d, p) -> ToolSpec(n, d, p) }
}
