package io.github.aixtin.nyral

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
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
     *  isVoice 标记该音频来自本地录音(需展示微信式语音气泡), 上传的音频文件为 false(展示为文件卡片);
     *  isEmoji 标记该附件来自表情库(渲染端走专门表情气泡: 96dp 小图贴边, 动图循环播放) */
    data class Attachment(val mime: String, val base64: String, val name: String = "attachment", val text: String? = null, val isVoice: Boolean = false, val pdfSourceName: String? = null, val stored: Boolean = false, val isEmoji: Boolean = false)

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
    var onBrowserText: (() -> String)? = null
    var onBrowserScroll: ((Int) -> String)? = null
    var onBrowserClear: ((Boolean) -> String)? = null

    /** 浏览器登录态 Cookie 回灌桥接: AI 调用 browser_save_cookies 时取浏览器当前登录 Cookie(主线程), site 非空按该域、为空取当前页域名 */
    @Volatile
    var onBrowserSaveCookies: ((site: String?) -> String)? = null

    /** 澄清询问桥接: AI 调用 ask_user 时由 MainActivity 注册回调, 主线程弹原生选择框等待用户点选(阻塞 work 线程同步返回用户选择) */
    @Volatile
    var onAskUser: ((question: String, options: List<String>, allowCustom: Boolean) -> String)? = null

    /** 当前活跃连接, 取消时 disconnect 以打断阻塞读 */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 请求停止当前 AI 输出/工具循环 */
    fun requestCancel() {
        cancelRequested = true
        activeConn?.disconnect()
    }

    private val toolRegistry = listOf(
        ToolSpec("web_search", "后台静默联网搜索(Bing), 结果仅供AI参考阅读, 用户看不到页面; 若用户想看搜索结果页/网页请改用 browser(action=open)", "JSON: {\"q\":\"搜索关键词\",\"max_results\":5}"),
        ToolSpec("browser", "全屏浏览器控制(在用户手机上打开可见浏览器页): action=open 打开URL/搜索词(用户要求搜索/查资料/看网页时优先用), scan 读可操作元素清单(带索引), text 读整页文字, scroll 滚动(delta 像素正下负上), click 点击第N元素(index), type 向输入框输入文本(index+text), upload 上传工作目录文件到文件选择框(index+local), clear_cache 清缓存刷新(full=true 连登录Cookie一起清), save_cookies 把当前登录Cookie存入site_auth(site 可选域名)。页面遇验证码/登录墙时提示用户点\"接管\"手动完成。", "JSON: {\"action\":\"open|scan|text|scroll|click|type|upload|clear_cache|save_cookies\",...}"),
        ToolSpec("app", "第三方App控制(需先授权无障碍): action=scan 扫描当前屏幕可操作元素清单(带索引, 空树返回EMPTY_TREE标记), click 点击第N元素(index), text 向输入框输入文本(index+text), tap 坐标注入点击(x+y, 视觉兜底用), screenshot 截当前屏保存工作目录返回路径(视觉兜底用), back 模拟返回键, home 回桌面, launch 按包名启动App(pkg), installed 列出已安装第三方应用", "JSON: {\"action\":\"scan|click|text|tap|screenshot|back|home|launch|installed\",...}"),
        ToolSpec("workdir", "手机工作目录(Download/Nyral_work)文件操作: action=list 列文件, read 读文本文件(name), write 写文件(name+content, 同名覆盖), grep 全文搜索(kw, 可选ext扩展名过滤/case大小写), head 读前N行或N字符(name+lines或chars), stats 统计概览", "JSON: {\"action\":\"list|read|write|grep|head|stats\",...}"),
        ToolSpec("file", "远端文件操作(SSH连接): action=list 列目录(conn+path), read 读文件(conn+path+lines), info 查看文件详情(conn+path), write 写/追加文件(conn+path+content+append), upload SFTP上传(conn+local+remote), download SFTP下载(conn+remote+local可选), ls SFTP列目录(conn+path)", "JSON: {\"action\":\"list|read|info|write|upload|download|ls\",\"conn\":\"连接名\",...}"),
        ToolSpec("web_fetch", "抓取网页并提取正文文本; 若 site_auth.json 已配置该域名 Cookie 会自动注入, 无需重复传", "JSON: {\"url\":\"https://...\",\"max_chars\":3000}"),
        ToolSpec("site_auth", "管理站点登录凭据(存 site_auth.json, 供 web_fetch/web_download 自动注入 Cookie)", "JSON: {\"action\":\"list\"} 或 {\"action\":\"set\",\"site\":\"域名\",\"cookie\":\"完整Cookie字符串\"} 或 {\"action\":\"del\",\"site\":\"域名\"}"),
        ToolSpec("get_time", "获取当前日期时间", "无参数"),
        ToolSpec("calc", "数学计算", "JSON: {\"expr\":\"表达式\"} 如 {\"expr\":\"17*23\"}"),
        ToolSpec("memory_search", "语义检索本地记忆", "JSON: {\"query\":\"查询内容\"}"),
        ToolSpec("ssh_run", "通过SSH在远程主机执行命令, 格式: 连接名:命令(连接名见下方可用SSH连接); 经跳板机(标注\"经跳板\")的连接只需指定连接名, 跳板自动处理, 不要自行添加跳板参数", "JSON: {\"command\":\"连接名:命令\"} 如 {\"command\":\"vps:ls /\"}"),
        ToolSpec("web_download", "下载网页/文件并保存到手机工作目录 下载/ 子目录; 返回\"下载成功\"即表示文件已落盘, 直接向用户报告结果, 不要再调用 workdir 等工具重复验证; site_auth.json 已配置的域名 Cookie 会自动注入", "JSON: {\"url\":\"https://...\",\"name\":\"可选文件名\"}"),
        ToolSpec("js_run", "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 无文件/网络权限, 断网可用不依赖服务器)", "JSON: {\"code\":\"要执行的JS脚本\",\"timeoutMs\":8000}"),
        ToolSpec("sh_run", "本机系统级执行 Shell 脚本(就地, 断网可用): 设备已 root 则 su -c 提权执行, 未 root 降级普通 sh 执行; 危险命令(rm -rf / /mkfs/dd 写设备/重启等)自动整脚本拦截; 返回 exit code + 输出(超2万字符截断)。用于清目录/查系统/禁自启/冻结App等系统级操作", "JSON: {\"script\":\"脚本内容\",\"timeout_ms\":15000}"),
        ToolSpec("ask_user", "当用户指令模糊/多义/缺关键信息、无法可靠推断时, 向用户当面澄清: 在手机弹原生选择框, 列出候选选项让用户点选(可选自定义输入)。用户的选择会作为本工具结果返回, 据此继续。仅在确实拿不准时才调用, 不要滥用", "JSON: {\"question\":\"要确认的问题\",\"options\":[\"选项1\",\"选项2\"],\"allow_custom\":true}"),
        ToolSpec("tool_detail", "查询未在回调列表中列出的工具的完整规格(描述+参数格式)并临时激活; 激活后该工具会加入本轮回调列表, 可直接 function calling 调用。当你想用 system 索引里看到但不在回调列表中的工具时, 先调本工具获取规格。", "JSON: {\"name\":\"工具名\"}"),
        ToolSpec("attach_read", "分块读取对话中收到的附件解析文本(仅超预算附件落盘的 *.txt 文本): 参数 {name: 附件文件名或 att:// 引用, offset: 起始字符偏移(默认0), limit: 本次最多返回字符数(默认4000, 最大50000)}。超大附件按需分段读, 禁止一次读全文; 读完后如需继续传 offset=上次offset+已读长度。", "JSON: {\"name\":\"附件文件名\",\"offset\":0,\"limit\":4000}"),
        ToolSpec("video_frame", "从已落盘附件视频抽指定时间点画面帧(按需观看): 参数 {name: 附件文件名或 att:// 引用, timeMs: 时间点毫秒(默认0)}。帧图会自动注入当前对话供模型参考; 仅支持已落盘附件(超预算大视频)。", "JSON: {\"name\":\"att://xxx.mp4\",\"timeMs\":10000}"),
        ToolSpec("file_export", "导出私有附件库文件到公共工作目录(Download/Nyral_work), 供用户直接查看/使用: 参数 {name: 附件文件名或 att:// 引用}。附件默认私有(用户看不到), 显式导出是唯一公开途径; 大视频/大文本落库后如需交付用户先调本工具", "JSON: {\"name\":\"att://xxx.mp4\"}")
    )

    /**
     * 上下文瘦身(2026-08-31): system 只注入"瘦索引"(工具名+一句话用途),
     * 完整参数 schema 在原生 function calling 模式下由 tools 字段承载(请求级),
     * 不占用对话 token; 文本协议降级模式才按需把完整 desc+params 追加进上下文。
     */
    private val toolIndex: Map<String, String> = mapOf(
        "web_search" to "后台静默检索(Bing), 结果仅AI参考, 用户看不到页面; 用户想看搜索页时用 browser(action=open)",
        "browser" to "全屏浏览器控制(用户可见): open 打开网页/搜索词, scan 扫元素, text 读文字, scroll 滚动, click 点击, type 输入, upload 上传文件, clear_cache 清缓存, save_cookies 存登录Cookie",
        "app" to "第三方App控制: scan 扫屏幕元素(空树EMPTY_TREE), click 点击, text 输入, tap 坐标点击(x+y), screenshot 截图, back 返回, home 桌面, launch 启动App, installed 查已装",
        "workdir" to "手机工作目录文件: list 列文件, read 读, write 写, grep 搜索, head 读前N行, stats 统计",
        "file" to "远端文件(SSH): list 列目录, read 读, info 详情, write 写, upload 上传, download 下载, ls 列目录",
        "web_fetch" to "抓取网页提取正文",
        "site_auth" to "管理站点登录 Cookie(site_auth.json)",
        "get_time" to "获取当前日期时间",
        "calc" to "数学计算",
        "memory_search" to "语义检索本地记忆",
        "ssh_run" to "SSH 远程执行命令",
        "web_download" to "下载网页/文件到手机工作目录",
        "js_run" to "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 断网可用)",
        "sh_run" to "本机系统级执行 Shell 脚本(root 自动 su 提权, 危险命令拦截)",
        "ask_user" to "需求模糊/多义/缺关键信息时弹窗向用户澄清(候选选项+可选自定义输入), 用户选择作为结果返回",
        "tool_detail" to "查询未列出工具的完整规格并激活(激活后可直接调用)"
    )

    // ===== Top N 动态装载(2026-09-16): 白名单+热度常驻, 冷门工具经 tool_detail 按需激活 =====
    private const val TOOL_DETAIL = "tool_detail"
    private const val BUILTIN_TOP_N = 16   // 内置工具常驻数(含 tool_detail 本身); 第二刀合并后共16个全量常驻
    private const val MCP_TOP_N = 4        // MCP 工具按热度常驻数
    /** 跨场景核心工具白名单: 永远注入完整 schema, 防冷启动雪藏 */
    private val TOOL_WHITELIST = setOf(
        "web_search", "browser", "app", "workdir", "file", "web_fetch", "memory_search",
        "ssh_run", "ask_user", "get_time", "calc", "js_run", "attach_read", "video_frame", "file_export"
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

    /**
     * 工具自热度排序(2026-09-14): 按(调用次数 desc, 最近使用 desc)排序,
     * 冷启动无数据保持注册默认顺序; 对末尾从未调用的冷门工具压缩描述为 toolIndex 瘦索引,
     * 省 token 且仍可正常调用(不真删)。
     */
    private fun hotOrder(context: Context, specs: List<ToolSpec>): List<ToolSpec> {
        val hot = ToolHotStore.load(context)
        if (hot.isEmpty()) return specs  // 冷启动: 默认注册顺序
        val ordered = specs.sortedWith(
            compareByDescending<ToolSpec> { hot[it.name]?.count ?: 0 }
                .thenByDescending { hot[it.name]?.lastTs ?: 0L }
        )
        // 压缩描述: 取末尾从未调用(热度0)的一批(不超过 1/3), 防雪藏过度
        val zeroHotTail = ordered.takeLast(ordered.size / 3)
            .filter { (hot[it.name]?.count ?: 0) == 0 }
            .map { it.name }
            .toSet()
        if (zeroHotTail.isEmpty()) return ordered
        return ordered.map { spec ->
            if (spec.name in zeroHotTail) {
                val short = toolIndex[spec.name]
                if (short != null) ToolSpec(spec.name, short, spec.params) else spec
            } else spec
        }
    }

    /** toolIndex 瘦索引按热度排序(与 function calling 的 tools 排序保持一致) */
    private fun hotToolIndex(context: Context): List<Pair<String, String>> {
        val hot = ToolHotStore.load(context)
        if (hot.isEmpty()) return toolIndex.entries.map { it.key to it.value }
        return toolIndex.entries.sortedWith(
            compareByDescending<Map.Entry<String, String>> { hot[it.key]?.count ?: 0 }
                .thenByDescending { hot[it.key]?.lastTs ?: 0L }
        ).map { it.key to it.value }
    }

    /**
     * 构建 OpenAI 兼容 tools 数组(2026-09-16 动态装载):
     * 内置 = 白名单 + tool_detail 常驻完整 schema, 其余按热度(冷启动按注册序)补位到 BUILTIN_TOP_N;
     * hotLoaded(经 tool_detail 激活)的冷门工具额外注入, 用完当轮即失效;
     * MCP = 按热度取 MCP_TOP_N + hotLoaded 激活项。
     */
    private fun buildToolsArray(context: Context, hotLoaded: Set<String>): JSONArray {
        val arr = JSONArray()
        val hot = ToolHotStore.load(context)
        val ordered = if (hot.isEmpty()) toolRegistry else toolRegistry.sortedWith(
            compareByDescending<ToolSpec> { hot[it.name]?.count ?: 0 }
                .thenByDescending { hot[it.name]?.lastTs ?: 0L }
        )
        val reserved = toolRegistry.filter { it.name in TOOL_WHITELIST || it.name == TOOL_DETAIL }
        val activated = ordered.filter { it.name in hotLoaded }
        val rest = ordered.filter { it.name !in TOOL_WHITELIST && it.name != TOOL_DETAIL && it.name !in hotLoaded }
        val top = reserved + activated + rest.take((BUILTIN_TOP_N - reserved.size).coerceAtLeast(0))
        for (spec in top) {
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.desc)
                    .put("parameters", builtinSchema(spec.name))))
        }
        // MCP 动态工具: 按热度取前 MCP_TOP_N + hotLoaded 激活项; schema 直接用 server 下发的 JSON Schema
        val mcpSpecs = McpClientManager.specEntries().map { (n, d, p) -> ToolSpec(n, d, p) }
        val mcpOrdered = if (hot.isEmpty()) mcpSpecs else mcpSpecs.sortedWith(
            compareByDescending<ToolSpec> { hot[it.name]?.count ?: 0 }
                .thenByDescending { hot[it.name]?.lastTs ?: 0L }
        )
        val mcpTop = (mcpOrdered.take(MCP_TOP_N) + mcpOrdered.filter { it.name in hotLoaded }).distinctBy { it.name }
        for (spec in mcpTop) {
            val params = try { JSONObject(spec.params) } catch (e: Exception) { JSONObject() }
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.desc)
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
        fun arr(desc: String, itemDesc: String, min: Int = 2, max: Int = 5): JSONObject =
            JSONObject()
                .put("type", "array")
                .put("description", desc)
                .put("items", JSONObject().put("type", "string").put("description", itemDesc))
                .put("minItems", min)
                .put("maxItems", max)

        return when (name) {
            "ask_user" -> obj(listOf("question", "options"),
                "question" to str("要向用户确认的问题(把用户的模糊需求/你理解的候选方案写清楚, 让用户一看就懂)"),
                "options" to arr("候选选项(2~5 个), 用户从中点选其一", "候选选项文本"),
                "allow_custom" to bool("是否允许用户输入自定义答案(默认 true)"))
            "browser" -> obj(listOf("action"),
                "action" to str("操作", enums = listOf("open", "scan", "text", "scroll", "click", "type", "upload", "clear_cache", "save_cookies")),
                "url" to str("open 时: 要打开的URL(以http开头)或搜索关键词", required = false),
                "delta" to int("scroll 时: 滚动像素(正数向下, 负数向上)"),
                "index" to int("click/type/upload 时: 元素索引(0 起, 来自 scan)"),
                "text" to str("type 时: 要输入的文本", required = false),
                "local" to str("upload 时: 工作目录文件名(Download/Nyral_work 下)", required = false),
                "full" to bool("clear_cache 时: true=连登录Cookie一起清除(退出所有网站登录); false=仅清页面缓存(默认)"),
                "site" to str("save_cookies 时: 目标域名, 不传则自动取浏览器当前页域名", required = false))
            "app" -> obj(listOf("action"),
                "action" to str("操作", enums = listOf("scan", "click", "text", "back", "home", "launch", "installed", "tap", "screenshot")),
                "index" to int("click/text 时: 元素索引(0 起, 来自 scan)"),
                "text" to str("text 时: 要输入的文本", required = false),
                "pkg" to str("launch 时: 要启动的应用包名, 如 com.tencent.mm", required = false),
                "x" to int("tap 时: 屏幕横坐标(px, 0 起)"),
                "y" to int("tap 时: 屏幕纵坐标(px, 0 起)"))
            "js_run" -> obj(listOf("code"),
                "code" to str("要执行的 JS 脚本(应用内就地, 纯计算/逻辑/数据操作)"),
                "timeoutMs" to int("超时毫秒, 默认 8000, 防死循环", 8000))
            "sh_run" -> obj(listOf("script"),
                "script" to str("要执行的 Shell 脚本内容(多行用 \\n)"),
                "timeout_ms" to int("超时毫秒(默认15000)", 15000))

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
            "file" -> obj(listOf("action"),
                "action" to str("操作", enums = listOf("list", "read", "info", "write", "upload", "download", "ls")),
                "conn" to str("SSH 连接名"),
                "path" to str("list/info/read/ls 时: 远程路径(目录或文件), 目录默认 ~", required = false),
                "lines" to int("read 时: 最多读取行数(默认200)", 200),
                "content" to str("write 时: 要写入的文件内容", required = false),
                "append" to bool("write 时: true 追加, false 覆盖(默认false)"),
                "local" to str("upload/download 时: 手机工作目录文件名(本地端)", required = false),
                "remote" to str("upload/download 时: 远端绝对路径", required = false))
            "web_download" -> obj(listOf("url"),
                "url" to str("要下载的 URL"),
                "name" to str("可选保存文件名(不含路径分隔符)", required = false))
            "attach_read" -> obj(listOf("name"),
                "name" to str("附件文件名(可带 att:// 前缀)"),
                "offset" to int("起始字符偏移, 默认0", 0),
                "limit" to int("本次最多返回字符数, 默认4000, 最大50000", 4000))
            "video_frame" -> obj(listOf("name", "timeMs"),
                "name" to str("附件文件名(可带 att:// 前缀)"),
                "timeMs" to int("目标时间点毫秒(默认0)", 0))
            "workdir" -> obj(listOf("action"),
                "action" to str("操作", enums = listOf("list", "read", "write", "grep", "head", "stats")),
                "name" to str("read/write/head 时: 工作目录下的文件名", required = false),
                "content" to str("write 时: 文件内容", required = false),
                "kw" to str("grep 时: 要搜索的关键词", required = false),
                "ext" to str("grep 时: 可选扩展名过滤, 如 .kt", required = false),
                "case" to bool("grep 时: 是否区分大小写(默认false)"),
                "lines" to int("head 时: 读取前 N 行", -1),
                "chars" to int("head 时: 读取前 N 字符", -1))
            else -> obj()
        }
    }

    // ================= 流式主流程 =================

    /** 流式入口: 主线程调用, 回调全部发生在调用线程(工作线程), UI 需自行 post */
    fun chat(context: Context, history: String, cb: Callback, attachments: List<Attachment> = emptyList()) {
        var attempt = 0
        // 跨轮累计正文: 声明在 try 外, 取消/重试 catch 分支也能带回已输出内容
        val full = StringBuilder()
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
            // Top N 动态装载(2026-09-16): 经 tool_detail 激活的冷门工具, 本轮内额外注入回调列表
            val hotLoaded = mutableSetOf<String>()
            // provider 切换时重置 tools 能力探测(同一 provider 保持上次结果, 防止重复降级死循环)
            maybeResetToolsCapability()

            while (true) {
                if (cancelRequested) {
                    cb.onDone("")
                    return
                }
                val res = streamOnce(context, messages, cb, answerGate, injectedTools, hotLoaded)
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
                        val result = executeTool(context, name, arg, hotLoaded)
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
                    if (res.dsmlRaw != null) {
                        // DSML 泄漏拦截来源: tool_call_id 为本地伪造, 走原生回填链可能被服务端配对校验拒绝;
                        // 改纯文本链——assistant 简述(不回填 DSML 原文, 防模型模仿泄漏格式) + 工具结果以 user 消息回填
                        messages.put(JSONObject().put("role", "assistant").put("content",
                            "(已发起工具调用: " + toolCalls.joinToString(", ") { it.first } + ")"))
                        for ((name, result) in execResults) {
                            val clean = if (name == "video_frame") result.substringBefore(" FRAME:data:image/jpeg;base64,") else result
                            messages.put(JSONObject().put("role", "user").put("content",
                                "[工具 $name 执行结果]\n" + capOut(clean)))
                        }
                    } else {
                        messages.put(buildAssistantToolMessage(res, toolCalls))
                        for ((i, er) in execResults.withIndex()) {
                            // 按 toolCalls 序号取对应 id: 并行同名工具各自独立, 防回填重复 tool_call_id
                            val toolCallId = res.idAt(i) ?: "call_${er.first}_$toolCount"
                            // video_frame 帧图剥离: tool 消息只回填文本摘要, 帧图由下方 user 消息注入(防 capOut 截断)
                            val clean = if (er.first == "video_frame") er.second.substringBefore(" FRAME:data:image/jpeg;base64,") else er.second
                            messages.put(JSONObject().put("role", "tool")
                                .put("tool_call_id", toolCallId)
                                .put("content", capOut(clean)))
                        }
                    }
                    // video_frame 帧图注入: 抽帧 base64 作为 image_url 追加 user 消息, 让模型本轮看到画面
                    for ((vName, vResult) in execResults) {
                        if (vName == "video_frame" && vResult.contains(" FRAME:data:image/jpeg;base64,")) {
                            val b64 = vResult.substringAfter(" FRAME:data:image/jpeg;base64,").trim()
                            val imgArr = JSONArray()
                                .put(JSONObject().put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                                .put(JSONObject().put("type", "text").put("text", "（以上为 video_frame 抽取的视频帧图，请结合查看后继续）"))
                            messages.put(JSONObject().put("role", "user").put("content", imgArr))
                        }
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
            android.util.Log.w("Nyral", "no-tools fallback retry (attempt=${attempt})")
            continue
        } catch (e: RetryableException) {
            // 取消优先于自动重试: 取消引发的断线若被吞成重试, 会先污染 UI("[网络波动]")再空 onDone 丢整轮内容
            if (cancelRequested) {
                android.util.Log.i("Nyral", "chat cancelled during net-retry (skip retry)")
                cb.onDone(full.toString())
                return
            }
            // 请求级断线: 自动重连一次, 全程无输出, 无需用户手动"继续"
            attempt++
            if (attempt >= MAX_NET_RETRY) {
                android.util.Log.e("Nyral", "chat network retry exhausted", e)
                cb.onError(e.message ?: "模型连接中断(网络波动)，请重试")
                return
            }
            android.util.Log.w("Nyral", "chat network interrupted, auto-retry #$attempt: ${e.message}")
            cb.onDelta("\n\n[网络波动，已自动重连一次]")
            continue
        } catch (e: Exception) {
            if (cancelRequested) {
                // 用户主动停止: 不视为错误
                android.util.Log.i("Nyral", "chat cancelled by user")
                cb.onDone("")
                return
            }
            android.util.Log.e("Nyral", "chat error", e)
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
                .put("id", res.idAt(idx) ?: "call_${name}_$idx")
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
        injectedTools: MutableSet<String>,
        hotLoaded: Set<String>
    ): StreamResult {
        val body = JSONObject()
        body.put("model", ApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.3)
        body.put("stream", true)
        // 原生 function calling: 请求携带 tools(模型支持时); probe 失败过则降级纯文本
        val useTools = !toolsUnsupported
        if (useTools) body.put("tools", buildToolsArray(context, hotLoaded))
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
        // 与 toolCalls 按序一一对应的 tool_call_id(并行同名工具必须各自独立 id, 按 name 建映射会覆盖
        // 导致回填重复 id -> 服务端 400 Duplicate tool_call_id -> 误降级文本协议 -> DSML 泄漏, 2026-09-18)
        val toolCallIds = ArrayList<String>()
        val restartOut = ArrayList<String>()
        // DSML 泄漏原文(解析成功时捕获, 供主循环判定走文本回填链; 需声明在 try 外, return 要用)
        var dsmlRaw: String? = null
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
                android.util.Log.w("Nyral", "provider 不支持 tools, 降级文本协议: ${err.take(200)}")
                throw NoToolsException("PROVIDER_NO_TOOLS")
            }
            throw RuntimeException("API $code: $err")
        }
        reader = BufferedReader(InputStreamReader(conn.inputStream))
        val lineBuf = StringBuilder()
        // XML 工具调用累积缓冲: 部分模型输出 <tool_call><tool_name>x</tool_name><param>...</param></tool_call> 跨行格式
        var xmlBuf: StringBuilder? = null
        // DSML 泄漏拦截缓冲(2026-09-18): deepseek-flash 间歇性把工具调用以 DSML 文本写进 content 通道
        // (而非 delta.tool_calls), 命中后进缓冲不显示, 块闭合后解析回工具调用; 单/双竖线变体均兼容
        var dsmlBuf: StringBuilder? = null
        var mode = MODE_NONE
        var aborted = false
        // 原生 function calling 增量累积: index -> (id, name, arguments)
        val nativeCalls = HashMap<Int, NativeCallAcc>()
        var finishedByToolCalls = false

        try {
        while (true) {
            if (cancelRequested) throw CancellationException("cancelled by user")
            val rd = reader ?: break
            val line = rd.readLine() ?: break
            android.util.Log.v("Nyral", "SSE: $line")
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
                    if (dsmlBuf != null || DSML_MARK_RE.containsMatchIn(row)) {
                        val buf = (dsmlBuf ?: StringBuilder()).append(row).append('\n')
                        dsmlBuf = buf
                        if (DSML_CLOSE_RE.containsMatchIn(buf)) {
                            val parsed = parseDsmlToolCalls(buf.toString())
                            if (parsed.isNotEmpty()) {
                                if (mode == MODE_THINKING) cb.onThinkingEnd()
                                for (t in parsed) {
                                    toolCalls.add(t)
                                    toolCallIds.add("dsml_" + toolCalls.size)
                                }
                                dsmlRaw = buf.toString()
                                dsmlBuf = null
                                android.util.Log.w("Nyral", "DSML 泄漏已拦截解析: " + parsed.size + " 个调用 [" + parsed.joinToString { it.first } + "]")
                                break
                            }
                            dsmlBuf = null
                        }
                        continue
                    }
                    // 文本协议兜底(仅无 tools 模式启用): 不再解析 XML/TOOL: 作为主要路径,
                    // 但保留兼容——若模型仍按旧协议输出可识别
                    if (!useTools) {
                        if (xmlBuf != null || row.trimStart().startsWith("<tool_call") || row.trimStart().startsWith("<tool_name>")) {
                            val xbuf = (xmlBuf ?: StringBuilder()).append(row).append('\n')
                            xmlBuf = xbuf
                            if (xbuf.contains("</tool_call>")) {
                                val xml = xbuf.toString()
                                xmlBuf = null
                                val t = parseXmlToolCall(xml)
                                if (t != null) {
                                    if (mode == MODE_THINKING) cb.onThinkingEnd()
                                    mode = MODE_NONE
                                    toolCalls.add(t)
                                    toolCallIds.add("xml_${toolCalls.size}")
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
                            toolCallIds.add("txt_${toolCalls.size}")
                        }
                        if (restartOut.isNotEmpty() || toolCalls.isNotEmpty()) break
                    } else {
                        // 原生 tools 模式: content 纯正文直接流式(无需行级协议解析, 但保留换行)
                        if (row.startsWith("思考:") || row.startsWith("思考：")) {
                            // 思考->工具间隙空气泡修复(09-25): 原生 tools 模式兼容 content 通道的 思考:/答案: 前缀行,
                            // 前缀行识别进 thinking 通道不再当正文渲染(否则收尾剥离前缀 -> 文字变空 -> 空气泡)
                            if (mode != MODE_THINKING) {
                                mode = MODE_THINKING
                                cb.onThinkingStart()
                            }
                            val body = row.substring(3).trim()
                            if (body.isNotEmpty()) cb.onThinkingDelta(body)
                            continue
                        }
                        if (row.startsWith("答案:") || row.startsWith("答案：")) {
                            if (mode == MODE_THINKING) cb.onThinkingEnd()
                            mode = MODE_CONTENT
                            val t = row.substring(3).trim()
                            if (t.isNotEmpty()) { cb.onDelta(t + "\n"); accumulated.append(t).append('\n') }
                            continue
                        }
                        if (mode != MODE_CONTENT) {
                            if (mode == MODE_THINKING) cb.onThinkingEnd()
                            mode = MODE_CONTENT
                        }
                        val out = if (row.endsWith("\r")) row.dropLast(1) else row
                        cb.onDelta(out + "\n"); accumulated.append(out).append('\n')
                    }
                }
                if (restartOut.isNotEmpty()) break
                // 文本协议或 DSML 拦截: 已解析出工具调用即尽早打断剩余流
                // (DSML 泄漏时 finish_reason=stop, 不能依赖 finishedByToolCalls 判定)
                if (toolCalls.isNotEmpty()) break
            }
            if (!useTools && (toolCalls.isNotEmpty() || restartOut.isNotEmpty())) break
            if (useTools && finishedByToolCalls && nativeCalls.isNotEmpty()) break
        }
        } catch (e: SocketException) {
            aborted = true
            android.util.Log.w("Nyral", "SSE connection aborted: ${e.message}")
        } catch (e: IOException) {
            aborted = true
            android.util.Log.w("Nyral", "SSE read error: ${e.message}")
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
        android.util.Log.i("Nyral", "EOF lineBuf=[$lineBuf] mode=$mode nativeCalls=${nativeCalls.size} toolCalls=${toolCalls.size} aborted=$aborted")
        // 末尾残余(无换行的最后一段)
        if (toolCalls.isEmpty() && nativeCalls.isEmpty() && lineBuf.isNotBlank()) {
            if (DSML_MARK_RE.containsMatchIn(lineBuf)) {
                // 残余含 DSML 半截(流被服务端提前切断的现场场景): 并入缓冲交由下方 EOF 尽力解析, 不显示乱码
                dsmlBuf = (dsmlBuf ?: StringBuilder()).append(lineBuf.toString().trimEnd()).append('\n')
            } else if (useTools) {
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
                    toolCallIds.add("txt_${toolCalls.size}")
                }
            }
        }
        // EOF 时 XML 缓冲残留
        if (!useTools && toolCalls.isEmpty() && xmlBuf != null) {
            val t = parseXmlToolCall(xmlBuf.toString())
            if (t != null) {
                toolCalls.add(t)
                toolCallIds.add("xml_${toolCalls.size}")
            }
        }
        // EOF 时 DSML 缓冲残留(未闭合半截/流被切断): 尽力解析, 解析出至少一个 invoke 即采纳为工具调用
        if (toolCalls.isEmpty() && nativeCalls.isEmpty() && dsmlBuf != null) {
            val parsed = parseDsmlToolCalls(dsmlBuf.toString())
            if (parsed.isNotEmpty()) {
                if (mode == MODE_THINKING) cb.onThinkingEnd()
                for (t in parsed) {
                    toolCalls.add(t)
                    toolCallIds.add("dsml_${toolCalls.size}")
                }
                dsmlRaw = dsmlBuf.toString()
                android.util.Log.w("Nyral", "DSML 半截 EOF 尽力解析: ${parsed.size} 个调用 [${parsed.joinToString { it.first }}]")
            } else {
                android.util.Log.w("Nyral", "DSML 残留解析失败已丢弃: ${dsmlBuf.toString().take(120)}")
            }
            dsmlBuf = null
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
                toolCallIds.add(acc.id.ifEmpty { "call_${name}_$idx" })
            }
            nativeCalls.clear()
        }
        android.util.Log.v("Nyral", "streamOnce done acc=[$accumulated] toolCalls=$toolCalls")
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
        return StreamResult(accumulated.toString(), toolCalls, restartOut.firstOrNull(), toolCallIds, dsmlRaw)
    }

    /** 判断 4xx 响应是否疑似"不支持 tools" */
    private fun looksLikeToolsUnsupported(err: String): Boolean {
        val e = err.lowercase()
        // 回填构造缺陷(如重复 tool_call_id)是请求组装 bug 而非 provider 能力问题, 降级重试也无法恢复,
        // 且会切换文本协议放大 DSML 泄漏, 必须排除(2026-09-18 实测 DeepSeek 该报错含 "invalid"+400 曾被误判)
        if (e.contains("tool_call_id")) return false
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

    // ==== DSML 泄漏拦截(2026-09-18) ====
    // deepseek-flash 间歇性把工具调用以 DSML 文本(内部协议标记泄漏到 content 通道, 而非 delta.tool_calls)
    // 输出且流常被提前切断。实测格式(单/双竖线、全/半角变体均兼容):
    //   <｜｜DSML｜｜ calls>
    //     <｜｜DSML｜｜ invoke name="workdir">
    //       <｜｜DSML｜｜ parameter name="action" string="true">list</｜｜DSML｜｜ parameter>
    //     </｜｜DSML｜｜ invoke>
    //   </｜｜DSML｜｜ calls>
    /** DSML 块开标记(仅匹配 < 后紧跟竖线+DSML, 不会误配闭标签) */
    private val DSML_MARK_RE = Regex("<[｜|]{1,2}DSML")
    /** DSML 块闭标记 */
    private val DSML_CLOSE_RE = Regex("</[｜|]{1,2}DSML[｜|]{1,2}\\s*calls>")

    /** 解析 DSML 文本中的工具调用: 返回 (工具名, JSON 参数字符串) 列表; 无有效 invoke 返回空列表 */
    fun parseDsmlToolCalls(text: String): List<Pair<String, String>> {
        val calls = mutableListOf<Pair<String, String>>()
        val invokeRe = Regex("<[｜|]{1,2}DSML[｜|]{1,2}\\s*invoke\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)(?=<[｜|]{1,2}DSML[｜|]{1,2}\\s*invoke|</[｜|]{1,2}DSML|$)")
        val paramRe = Regex("<[｜|]{1,2}DSML[｜|]{1,2}\\s*parameter\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</[｜|]{1,2}DSML")
        for (m in invokeRe.findAll(text)) {
            val name = normalizeToolName(m.groupValues[1].trim())
            if (name.isEmpty()) continue
            val args = JSONObject()
            for (p in paramRe.findAll(m.groupValues[2])) {
                val key = p.groupValues[1].trim()
                val v = p.groupValues[2].trim()
                if (key.isEmpty()) continue
                // 参数值智能定标: 合法 JSON 字面量(对象/数组/数字/布尔)按原类型入参, 其余按字符串
                args.put(key, try {
                    when (val parsed = org.json.JSONTokener(v).nextValue()) {
                        is JSONObject, is JSONArray, is Int, is Long, is Double, is Boolean -> parsed
                        else -> v
                    }
                } catch (e: Exception) { v })
            }
            calls.add(name to args.toString())
        }
        return calls
    }

    /** 剥除文本中的 DSML 泄漏块(含未闭合半截): 防止会话历史脏数据回流上下文引发模型模仿(恶性循环) */
    fun stripDsml(s: String): String {
        if (!s.contains("DSML")) return s
        var text = s
        while (true) {
            val startM = DSML_MARK_RE.find(text) ?: break
            val start = startM.range.first
            val end = DSML_CLOSE_RE.find(text.substring(start))?.let { start + it.range.last + 1 } ?: text.length
            text = text.removeRange(start, end)
        }
        return text.trim()
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
        // 表情气泡约定(2026-09-20): 仅聊天模式注入; Agent 模式不注入(模型不会主动输出表情标记)
        val emojiBlock = if (ModeConfig.emojiEnabled()) {
            val names = try {
                val a = JSONArray(context.getSharedPreferences("emoji_drawer", Context.MODE_PRIVATE).getString("lib_items", "[]") ?: "[]")
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }
            } catch (e: Exception) { emptyList() }
            if (names.isEmpty()) ""
            else "\n表情气泡(仅当前聊天模式可用): 你可以在回复中穿插 [表情:名] 标记来发送独立表情气泡, 名字必须是: " +
                names.joinToString("/") +
                "。标记所在位置即气泡顺序(在正文前=表情先出现, 在末尾=正文先出现); 每轮最多 1~2 个, 仅在你觉得自然时使用, 严禁滥用或连续刷屏。\n"
        } else ""
        messages.put(JSONObject().put("role", "system").put("content",
            personaBlock +
            "根据用户需求选择工具。工具清单(名称+用途):\n" +
            "搜索策略(重要·三级)：1) 一般搜索默认先用 web_search 后台静默快查(不打断用户界面)，拿到标题+摘要直接汇报，用户没要求看页面就不要开浏览器展示页；2) 仅当用户明确要\"看页面/看结果页/进某站\"，或 web_search 无有效结果、需要登录态、卡验证码/登录墙时，才升级调用 browser(action=open) 打开全屏浏览器页(用户可见)；3) 浏览器页内遇到验证码/登录墙：不要硬点，停下提示用户点底部\"接管\"按钮手动完成(输验证码/登录)，用户再点\"交还 AI\"后你可继续 browser 的 scan/click/type 等操作；用户登录成功后调用 browser(action=save_cookies) 把该站点登录 Cookie 存入 site_auth.json，此后 web_fetch/web_download 静默抓取自动带登录态，无需再开浏览器。\n" +
            hotToolIndex(context).joinToString("\n") { (n, d) -> "- $n: $d" } +
            "注: 回调列表仅常驻常用工具; 想用索引中未列出的工具时, 先调 tool_detail(name) 获取规格并激活, 激活后即可直接调用。\n" +
            buildMcpIndex() +
            "\n可用SSH连接:$sshHint\n" +
            "调用方式: 使用系统提供的 function calling 原生工具调用(工具名与 JSON 参数已由系统给出 schema), 一次(轮)可并行发起多个工具; 不要自己发明不存在的工具名。" +
            "记忆使用: 若所需信息可能来自与此用户过去的对话且当前上下文未提及, 应调用 memory_search 查证后再回答; 工具调用过程中拿不准时也先查记忆再作答。\n" +
            (if (memInject.isNullOrBlank()) "" else memInject + "\n") + fmtRules + mdBan + emojiBlock))
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
                        if (a.stored) {
                            // 大音频落私有附件库: 不发 base64, 只放索引卡, AI 按需 file_export 导出
                            val meta = try { AttachmentStore.metaOf(context, a.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
                            val durS = meta?.optLong("durationSec", 0L) ?: 0L
                            val sizeB = meta?.optLong("size", 0L) ?: 0L
                            val sizeStr = if (sizeB >= 1024 * 1024) String.format("%.1fMB", sizeB / 1024.0 / 1024.0)
                                else if (sizeB >= 1024) String.format("%.1fKB", sizeB / 1024.0)
                                else "${sizeB}B"
                            val disp = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: a.name
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[大音频附件 $disp | ${a.mime} | $sizeStr | 时长约${durS}s | 已落私有附件库 att://${a.name} | 音频字节未发送, 需处理时调 file_export(name=\"att://${a.name}\") 导出到公共工作目录]"))
                        } else {
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
                    }
                    a.mime.startsWith("video/") -> {
                        if (a.stored) {
                            // 大视频落私有附件库(阶段C): 不发 base64, 只放索引卡, AI 按需 video_frame 抽帧 / file_export 导出
                            val meta = try { AttachmentStore.metaOf(context, a.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
                            val durS = meta?.optLong("durationSec", 0L) ?: 0L
                            val sizeB = meta?.optLong("size", 0L) ?: 0L
                            val sizeStr = if (sizeB >= 1024 * 1024) String.format("%.1fMB", sizeB / 1024.0 / 1024.0)
                                else if (sizeB >= 1024) String.format("%.1fKB", sizeB / 1024.0)
                                else "${sizeB}B"
                            val disp = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: a.name
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[大视频附件 $disp | ${a.mime} | $sizeStr | 时长约${durS}s | 已落私有附件库 att://${a.name} | 视频字节未发送, 需看画面时调 video_frame(name=\"att://${a.name}\", timeMs) 抽帧, 或调 file_export(name=\"att://${a.name}\") 导出到公共工作目录]"))
                        } else {
                            // MiMo 视频理解: content 数组 type=video_url, url 用 data:{mime};base64 内联
                            // 官方限制: base64 后 ≤50MB(原始约 ≤37MB), 支持 MP4/MOV/AVI/WMV
                            val vurl = JSONObject().put("url", "data:${a.mime};base64,${a.base64}")
                            parts.put(JSONObject().put("type", "video_url")
                                .put("video_url", vurl)
                                .put("fps", 2)
                                .put("media_resolution", "default"))
                        }
                    }
                    else -> {
                        if (a.stored) {
                            // 文件落私有附件库: 不发二进制, 只放索引卡, AI 按需 attach_read 分块读取 / file_export 导出
                            val meta = try { AttachmentStore.metaOf(context, a.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
                            val sizeB = meta?.optLong("size", 0L) ?: 0L
                            val sizeStr = if (sizeB >= 1024 * 1024) String.format("%.1fMB", sizeB / 1024.0 / 1024.0)
                                else if (sizeB >= 1024) String.format("%.1fKB", sizeB / 1024.0)
                                else "${sizeB}B"
                            val disp = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: a.name
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[文件附件 $disp | ${a.mime} | $sizeStr | 已落私有附件库 att://${a.name} | 需读内容调 attach_read(name=\"att://${a.name}\", offset, limit) 分块读取(限 UTF-8 文本), 或调 file_export(name=\"att://${a.name}\") 导出到公共工作目录后用 workdir 工具处理]"))
                        } else if (a.text.isNullOrBlank()) {
                            // 文档类附件(PDF/Office/txt 等): 本地已提取纯文本并随 history 注入时不再发二进制
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
    /** 第二刀工具合并(2026-09-16): 旧工具名 -> (新复合工具, action), 兼容文本协议/历史调用 */
    private val LEGACY_TOOL_ACTION = mapOf(
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

    private fun executeTool(context: Context, name: String, argRaw: String, hotLoaded: MutableSet<String>): String {
        val arg0 = normalizeArgs(name, argRaw)
        // 旧工具名兼容: 映射到新复合工具并注入 action
        val legacy = LEGACY_TOOL_ACTION[name.trim('_')]
        val n = if (legacy != null) legacy.first
                else toolRegistry.firstOrNull { it.name == name }?.name ?: name.trim('_')
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
        if (toolRegistry.any { it.name == n } || McpClientManager.spec(n) != null) {
            ToolHotStore.recordHit(context, n)
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
            "sh_run" -> ScriptEngine.runSh(context, arg)
            "tool_detail" -> {
                val tName = try { JSONObject(arg.trim()).optString("name", "").trim() } catch (e: Exception) { "" }
                if (tName.isEmpty()) return "请指定要查询的工具名 name(可参考 system 中的工具索引)"
                val spec = toolRegistry.firstOrNull { it.name == tName }
                    ?: McpClientManager.spec(tName)?.let { (n, d, p) -> ToolSpec(n, d, p) }
                    ?: return "未找到工具: $tName"
                hotLoaded.add(tName)
                return "工具 [$tName] 已临时激活并加入本轮回调列表, 现在可直接 function calling 调用。\n描述: ${spec.desc}\n参数格式: ${spec.params}"
            }
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

    /** browser_text 工具: 读取整页可见文字 */
    private fun browserText(): String {
        val cb = onBrowserText ?: return "浏览器桥接未初始化"
        return cb()
    }

    /** browser_scroll 工具: 滚动浏览器页 */
    private fun browserScroll(argRaw: String): String {
        val delta = try { JSONObject(argRaw.trim()).optInt("delta", 0) } catch (e: Exception) { 0 }
        if (delta == 0) return "请指定非零 delta(像素, 正数向下/负数向上)"
        val cb = onBrowserScroll ?: return "浏览器桥接未初始化"
        return cb(delta)
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

    /** ask_user 工具: 解析 question/options/allow_custom, 经 onAskUser 桥接弹原生选择框, 返回用户选择(或降级说明) */
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
        val cb = onAskUser
        if (cb == null) {
            return "[ask_user] 需要向你确认: $q (当前无界面可弹窗, AI 请基于已有信息自行判断继续)"
        }
        return cb(q, opts, allowCustom)
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
        /** 与 toolCalls 按序一一对应的 tool_call_id(并行同名工具各自独立) */
        val toolCallIds: List<String> = emptyList(),
        /** 非 null 表示本轮工具调用来自 DSML 泄漏拦截(content 通道文本解析), 回填需走文本链 */
        val dsmlRaw: String? = null
    ) {
        fun idAt(idx: Int): String? = toolCallIds.getOrNull(idx)
    }

    data class ToolSpec(val name: String, val desc: String, val params: String)

    private const val MODE_NONE = 0
    private const val MODE_THINKING = 1
    private const val MODE_CONTENT = 2

    /** 调试服务/状态查询: 暴露工具清单(名称+描述+参数说明, 含 MCP 动态工具) */
    fun toolList(): List<ToolSpec> =
        toolRegistry + McpClientManager.specEntries().map { (n, d, p) -> ToolSpec(n, d, p) }
}
