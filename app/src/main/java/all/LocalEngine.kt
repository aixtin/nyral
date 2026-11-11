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

private const val MS_PER_SEC = 1000L

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

    // 请求级断线自动重连: 模型请求因网络中断零输出时自动重发(共 3 次尝试, 指数退避), 无需用户手动"继续";
    // 弱网场景: 有输出后断线也走"续跑重连"(携带已输出内容续写), 避免半截回复莫名停止
    private const val MAX_NET_RETRY = 3

    // 单次完整回复输出上限(字符): 防模型超长输出/多轮 tool 累积导致上下文顶爆窗口; 超限截断并提示
    private const val MAX_OUTPUT_CHARS = 60000

    /** 输出截断: 超过上限截断并追加提示, 防单条超长注入顶爆上下文窗口 */
    private fun capOut(s: String): String =
        if (s.length > MAX_OUTPUT_CHARS) s.take(MAX_OUTPUT_CHARS) + "\n…[输出过长已截断]" else s

    /** 工具结果失败判定(2026-10-10): 命中明确失败/拒绝前缀即视为未成功, 回填时显式告知模型 */
    private fun looksLikeToolFailure(s: String): Boolean =
        s.startsWith("错误") || s.startsWith("失败") || s.startsWith("【安全确认】") ||
        s.startsWith("未知工具") || s.startsWith("参数格式错误") || s.startsWith("未找到") ||
        s.startsWith("请指定") || s.startsWith("已拦截") || s.startsWith("浏览器桥接未初始化") ||
        s.startsWith("未获取到有效站点信息")

    /** 工具结果包装(2026-10-10): 失败结果追加显式失败声明, 防模型把失败当成功继续瞎编 */
    private fun wrapToolResult(name: String, result: String): String =
        if (looksLikeToolFailure(result)) {
            "$result\n\n【注意】工具 $name 未成功执行, 结果不可用。不要假设它成功了, 请根据情况自行补救或向用户如实说明。"
        } else result

    /** 请求 messages 中是否含 video_url 内容块(视频直发降级判定用) */
    private fun hasVideoPart(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val parts = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                if (parts.optJSONObject(j)?.optString("type") == "video_url") return true
            }
        }
        return false
    }

    /** 请求 messages 中是否含 input_audio 内容块(音频直发降级判定用) */
    private fun hasAudioPart(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val parts = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                if (parts.optJSONObject(j)?.optString("type") == "input_audio") return true
            }
        }
        return false
    }

    /** 剥离 messages 中的 video_url 内容块(同条的文字索引卡保留): 防止工具循环多轮重复上传原视频 */
    private fun stripVideoParts(messages: JSONArray): Int {
        var total = 0
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val parts = msg.optJSONArray("content") ?: continue
            val kept = JSONArray()
            var hit = 0
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j)
                if (p?.optString("type") == "video_url") { hit++; continue }
                if (p != null) {
                    // 直发卡的"禁止抽帧"措辞仅对看得见视频的首轮有效; 剥离后必须同步改写,
                    // 否则后续轮次模型拿着"禁止调 video_frame"的卡却已看不到视频, 抽帧退路断裂
                    if (p.optString("type") == "text") {
                        val t = p.optString("text")
                        if (t.contains(DIRECT_VIDEO_MARK)) {
                            val name = Regex("att://[^\\s|\"]+").find(t)?.value ?: ""
                            p.put("text", "[视频附件 $name | 已落私有附件库 $name | 原视频字节本轮已移除, 需看画面调 video_frame(name=\"$name\", times=[毫秒,毫秒]) 抽帧]")
                        }
                    }
                    kept.put(p)
                }
            }
            if (hit > 0) { msg.put("content", kept); total += hit }
        }
        return total
    }

    /** 网络层可重试异常: 请求级断线, 整个 chat 流程自动重跑一次 */
    private class RetryableException(msg: String) : RuntimeException(msg)

    /** 当前 provider 不支持 tools: 已降级文本协议标志, 立即重跑请求(不计入网络重试额度) */
    private class NoToolsException(msg: String) : RuntimeException(msg)

    /** 视频直发被服务端拒绝(格式/时长/大小不支持): 已置降级标志, 立即重跑请求改走抽帧索引卡 */
    private class VideoDirectRejectedException(msg: String) : RuntimeException(msg)

    /** 视频直发被拒的 provider|model 组合(2026-10-26): 命中后该组合一律走抽帧索引卡, 避免每轮重试 400 */
    @Volatile private var videoDirectDisabledKey: String? = null

    /** 音频直发被拒的 provider|model 组合(2026-10-26): 命中后该组合一律走 audio_read 索引卡 */
    @Volatile private var audioDirectDisabledKey: String? = null

    /** 直发索引卡措辞标记: stripVideoParts 靠它识别"直发卡"并在剥离后改写为抽帧指引 */
    private const val DIRECT_VIDEO_MARK = "已直发原视频可直接观看"

    /** 发送附件: mime 类型 + Base64 内容 + 文件名; 图片走 image_url, 音频走 input_audio, 其余走 input_file;
     *  text 为附件本地解析出的纯文本(如 PDF 提取内容), 非空时随 history 一并注入给模型;
     *  isVoice 标记该音频来自本地录音(需展示微信式语音气泡), 上传的音频文件为 false(展示为文件卡片);
     *  isEmoji 标记该附件来自表情库(渲染端走专门表情气泡: 96dp 小图贴边, 动图循环播放) */
    data class Attachment(val mime: String, val base64: String, val name: String = "attachment", val text: String? = null, val isVoice: Boolean = false, val pdfSourceName: String? = null, val stored: Boolean = false, val isEmoji: Boolean = false)

    /** 取消状态: requestCancel() 置 true, 引擎在流式读取/工具循环处检查并中断 */
    /** C1 修复(2026-10-20): 对话单飞锁——同一时刻只允许一个 chat 运行(主对话与定时任务互斥) */
    private val chatLock = Any()

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

    /** 本轮全部活跃连接(2026-10-31 刹车加固): 多连接同时登记, 取消时逐个断开, 防漏断致读阻塞卡死 */
    private val activeConns = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>())

    /** 请求停止当前 AI 输出/工具循环(软停: 断链 + 进度打断) */
    fun requestCancel() {
        cancelRequested = true
        activeConn?.let { try { it.disconnect() } catch (_: Throwable) {} }
        for (c in activeConns.toList()) { try { c.disconnect() } catch (_: Throwable) {} }
        // 进程级刹车: 本机脚本进程组整组终止(异步, 不阻塞 UI 线程)
        ProcGuard.killAllAsync()
    }

    /** 强制停止(第二击 / 兜底): 再杀一遍进程组, 已有的 UI 渲染队列由 cancelRequested 统一作废 */
    fun requestCancelHard() {
        cancelRequested = true
        activeConn?.let { try { it.disconnect() } catch (_: Throwable) {} }
        for (c in activeConns.toList()) { try { c.disconnect() } catch (_: Throwable) {} }
        ProcGuard.killAllNow()
    }

    /** 可取消退避: 弱网重试的 sleep 支持被停止按钮提前打断(旧实现 Thread.sleep 不可中断, 最坏拖 4s) */
    private fun sleepCancellable(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cancelRequested) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    internal val toolRegistry = listOf(
        ToolSpec("web", "联网三合一(2026-11-11 合并 web_search/web_fetch/web_download): action=search 后台静默联网搜索(Bing), 结果仅供AI参考阅读, 用户看不到页面(用户想看搜索结果页/网页请改用 browser(action=open)); action=fetch 抓取网页并提取正文文本, site_auth.json 已配置该域名 Cookie 会自动注入; action=download 下载网页/文件并保存到手机工作目录 下载/ 子目录, 返回\"下载成功\"即表示已落盘, 直接向用户报告结果, 不要再调 workdir 重复验证", "JSON: {\"action\":\"search|fetch|download\",\"q\":\"搜索关键词(action=search)\",\"url\":\"https://...(action=fetch|download)\",\"max_results\":5,\"max_chars\":3000,\"name\":\"可选文件名(action=download)\"}"),
        ToolSpec("browser", "全屏浏览器控制(在用户手机上打开可见浏览器页): action=open 打开URL/搜索词(用户要求搜索/查资料/看网页时优先用), scan 读可操作元素清单(带索引), text 读整页文字, scroll 滚动(delta 像素正下负上), click 点击第N元素(index), type 向输入框输入文本(index+text), upload 上传工作目录文件到文件选择框(index+local), clear_cache 清缓存刷新(full=true 连登录Cookie一起清), save_cookies 把当前登录Cookie存入site_auth(site 可选域名)。页面遇验证码/登录墙时提示用户点\"接管\"手动完成。", "JSON: {\"action\":\"open|scan|text|scroll|click|type|upload|clear_cache|save_cookies\",...}"),
        ToolSpec("app", "第三方App控制(需先授权无障碍): action=scan 扫描当前屏幕可操作元素清单(带索引, 空树返回EMPTY_TREE标记), click 点击第N元素(index), text 向输入框输入文本(index+text), tap 坐标注入点击(x+y, 视觉兜底用), screenshot 截当前屏保存工作目录返回路径(视觉兜底用), back 模拟返回键, home 回桌面, launch 按包名启动App(pkg), installed 列出已安装第三方应用", "JSON: {\"action\":\"scan|click|text|tap|screenshot|back|home|launch|installed\",...}"),
        ToolSpec("workdir", "手机工作目录(Download/Nyral_work)文件操作: action=list 列文件, read 读文本文件(name), write 写文件(name+content, 同名覆盖), grep 全文搜索(kw, 可选ext扩展名过滤/case大小写), head 读前N行或N字符(name+lines或chars), stats 统计概览", "JSON: {\"action\":\"list|read|write|grep|head|stats\",...}"),
        ToolSpec("file", "远端文件操作(SSH连接): action=list 列目录(conn+path), read 读文件(conn+path+lines), info 查看文件详情(conn+path), write 写/追加文件(conn+path+content+append), upload SFTP上传(conn+local+remote), download SFTP下载(conn+remote+local可选), ls SFTP列目录(conn+path)", "JSON: {\"action\":\"list|read|info|write|upload|download|ls\",\"conn\":\"连接名\",...}"),
        ToolSpec("site_auth", "管理站点登录凭据(存 site_auth.json, 供 web(action=fetch/download) 自动注入 Cookie)", "JSON: {\"action\":\"list\"} 或 {\"action\":\"set\",\"site\":\"域名\",\"cookie\":\"完整Cookie字符串\"} 或 {\"action\":\"del\",\"site\":\"域名\"}"),
        ToolSpec("get_time", "获取当前日期时间", "无参数"),
        ToolSpec("calc", "数学计算", "JSON: {\"expr\":\"表达式\"} 如 {\"expr\":\"17*23\"}"),
        ToolSpec("read_file_audit", "只读审计: 查看附件删除历史(时间/落库文件名/大小/mime/是否AI产物/删除来源), 供AI排查被删文件; 仅文件管理器私有目录, 用户不可删", "JSON: {\"limit\":50}"),
        ToolSpec("device_snapshot", "读取手机设备面板实时快照(只读): CPU 占用与最高核频率、内存、SoC 温度、电池、负载、数据分区余量、近5分钟峰值。用户问手机现在什么状态/为什么卡/烫不烫/还剩多少空间时调用; 动态指标不会自动进上下文, 需要时才取", "JSON: {\"fields\":[\"cpu\",\"mem\",\"temp\"]} (可省略=全部; 可选 profile/cpu/mem/temp/batt/load/disk/series)", CapabilityScope.ZONE_READONLY),
        ToolSpec("device_probe", "只读探针追单个对象: 传包名或进程名, 返回安装/版本/pid/常驻内存 RSS/线程数/进程状态/是否有同名内核模块; 权限通道不足的字段会如实说明缺哪个通道", "JSON: {\"target\":\"com.tencent.mm\"} (或 system_server 这类进程名)", CapabilityScope.ZONE_READONLY),
        ToolSpec("memory_search", "语义检索本地记忆", "JSON: {\"query\":\"查询内容\"}"),
        ToolSpec("ssh_run", "SSH 动作工具(2026-11-11 吸收 ssh_config_add): action=exec 通过SSH在远程主机执行命令, 格式: 连接名:命令(连接名见下方可用SSH连接); 参数名 command, 与本机 sh_run(参数名 script)不同, 不要互相混用; 经跳板机(标注\"经跳板\")的连接只需指定连接名, 跳板自动处理, 不要自行添加跳板参数。action=add_config 新增/更新SSH连接配置(直接写入配置库, 写后即可用 exec 连接): 必填 name/host/user, 端口默认22, 同名 name 覆盖旧配置; 可选 password/privateKey/passphrase, 密码与私钥至少给其一(都没有时仅保存连接, 连接会因缺凭据失败); 跳板机可选 proxyHost/proxyPort(默认22)/proxyUser/proxyPassword/proxyPrivateKey/proxyPassphrase", "JSON: {\"action\":\"exec|add_config\",\"conn\":\"连接名\",\"command\":\"命令\",\"name\":\"连接名\",\"host\":\"主机\",\"port\":22,\"user\":\"用户\"}"),
        ToolSpec("js_run", "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 无文件/网络权限, 断网可用不依赖服务器)", "JSON: {\"code\":\"要执行的JS脚本\",\"timeoutMs\":8000}"),
        ToolSpec("sh_run", "本机系统级执行 Shell 脚本(就地, 断网可用): 设备已 root 则 su -c 提权执行, 未 root 降级普通 sh 执行; 危险命令(rm -rf / /mkfs/dd 写设备/重启等)自动整脚本拦截; 返回 exit code + 输出(超2万字符截断)。用于清目录/查系统/禁自启/冻结App等系统级操作", "JSON: {\"script\":\"脚本内容\",\"timeout_ms\":15000} 参数名是 script(传 command/cmd 也会被自动兼容)。本工具在手机本机就地执行, 不需要任何 SSH 连接, 与远端 ssh_run 完全不同"),
        ToolSpec("ask_user", "当用户指令模糊/多义/缺关键信息、无法可靠推断时, 向用户当面澄清: 在手机弹原生选择框, 列出候选选项让用户点选(可选自定义输入)。用户的选择会作为本工具结果返回, 据此继续。仅在确实拿不准时才调用, 不要滥用", "JSON: {\"question\":\"要确认的问题\",\"options\":[\"选项1\",\"选项2\"],\"allow_custom\":true}"),
        ToolSpec("tool_detail", "查询未在回调列表中列出的工具的完整规格(描述+参数格式)并临时激活; 激活后该工具会加入本轮回调列表, 可直接 function calling 调用。当你想用 system 索引里看到但不在回调列表中的工具时, 先调本工具获取规格。", "JSON: {\"name\":\"工具名\"}"),
        ToolSpec("attach_read", "分块读取对话中收到的落库附件的文本内容(2026-10-26 扩展): 支持 *.txt 等纯文本, 也支持 Word(docx)/Excel(xlsx)/PPT(pptx)/压缩包(zip/7z/rar/gz) 自动提取文本; PDF 请用 pdf_page。参数 {name: 附件文件名或 att:// 引用, offset: 起始字符偏移(默认0), limit: 本次最多返回字符数(默认4000, 最大50000)}。超大附件按需分段读, 禁止一次读全文; 读完后如需继续传 offset=上次offset+已读长度。", "JSON: {\"name\":\"附件文件名\",\"offset\":0,\"limit\":4000}"),
        ToolSpec("pdf_page", "从已落盘附件PDF按需读取指定页(按需翻页): 参数 {name: 附件文件名或 att:// 引用, page: 页码从1开始, mode: \"image\"渲染该页为图片注入对话(默认, 适合支持识图的模型) | \"text\"返回该页文字(适合无识图能力模型, 扫描件无文本层时会明确告知)}。PDF 附件发送时已落私有附件库, 首轮仅预览前几页; 需要看任意页(如用户指定页码)时调用, 按自身识图能力自行选择 mode", "JSON: {\"name\":\"att://xxx.pdf\",\"page\":89,\"mode\":\"text\"}"),
        ToolSpec("video_frame", "从已落盘附件视频抽画面帧(按需观看): 参数 {name: 附件文件名或 att:// 引用, timeMs: 时间点毫秒(选填; 不传时自动多帧采样 0/30%/60%/95% 时间点全部注入, 供整体判断视频内容; 传了则只抽该时间点单帧)}。帧图会自动注入当前对话供模型参考; 仅支持已落盘附件(统一落库后所有视频均已落盘)。用户要求拿到/交付帧图或视频文件时, 抽帧后必须调用 file_export 导出原视频。", "JSON: {\"name\":\"att://xxx.mp4\"} 或 {\"name\":\"att://xxx.mp4\",\"timeMs\":8000}"),
        ToolSpec("image_view", "从已落库附件图片按需注入查看: 参数 {name: 附件文件名或 att:// 引用}。识图模型注意: 当前消息里的图片已直接内联可见, 无需调用本工具; 本工具主要用于查看历史消息中的旧图片(历史仅存 att:// 引用不发字节)。注入后图片仅供识图模型参考。", "JSON: {\"name\":\"att://xxx.jpg\"}"),
        ToolSpec("audio_read", "从已落库附件音频按需注入听取(供支持音频输入的模型): 参数 {name: 附件文件名或 att:// 引用}。音频模型注意: 当前消息里的小音频(≤10MB 且 ≤120s)已直接内联可听, 无需调用; 本工具主要用于听取历史消息中的旧音频、超限大音频或直发被拒后的音频。不支持音频输入的模型不要调用, 应提示用户 file_export 导出。", "JSON: {\"name\":\"att://xxx.mp3\"}"),
        ToolSpec("file_export", "导出私有附件库文件到公共工作目录(Download/Nyral_work), 供用户直接查看/使用: 参数 {name: 附件文件名或 att:// 引用}。附件默认私有(用户看不到), 显式导出是唯一公开途径; 用户明确要求把附件/帧图放到下载目录或交付给自己时, 必须调用本工具导出, 并在导出成功后向用户回报导出文件的完整路径", "JSON: {\"name\":\"att://xxx.mp4\"}"),
        ToolSpec("security_set", "安全管理开关(2026-10-30 收紧): 模型侧仅保留 ssh_trust —— 信任待确认的SSH主机密钥。危险门禁三档(danger_mode)与 root 自动补权(root_auto_grant)已从模型侧收回: 调用这些参数会被直接拒绝, 只能由用户在 设置 → 安全中心 手动修改; 用户提出关/改门禁时应引导其自行前往该页面, 不要代为操作", "JSON: {\"ssh_trust\":\"连接名\"}"),
        ToolSpec("write_clipboard", "把文本写入手机系统剪贴板(零权限, 无自动化特征), 供用户切换到其他应用后长按粘贴使用。当用户需要把内容(AI生成的文案/代码/文本)搬到其他App(抖音/B站/微信/网页输入框等)时调用, 调用后明确告知用户\"内容已复制, 去目标应用长按粘贴\"", "JSON: {\"text\":\"要复制到剪贴板的完整文本\"}"),
        ToolSpec("artifact", "产物登记通道(2026-11-11 合并 artifact_save/artifact_save_bin): kind=text 交付纯文本成品(代码/HTML/SVG/Markdown/PDF源码), kind=binary 交付二进制成品(图片/音频/视频/压缩包, data 为 Base64 且解码后不超过 512KB)。成品全文不要直接贴在回复里。必须调用场景: 用户要完整代码文件/可运行的HTML网页/成品文档/SVG, 或连续代码超过30行, 或要求保存/导出/交付文件。不要滥用: 简短代码片段/解释性示例/纯思路讨论不登记。content 与 data 二选一(kind=text 用 content, kind=binary 用 data); content 必须是产物文件本身正文, 不要把工具调用指令/JSON包装/解释文字混进 content。同名再登记自动覆盖升版, 历史卡片展示最新版。登记成功后必须立即在回复中单独一行原样输出 [产物:文件名](att://返回的引用) 展示卡片, 不要重复贴全文", "JSON: {\"name\":\"产物文件名(带扩展名)\",\"kind\":\"text|binary\",\"content\":\"kind=text 的正文\",\"data\":\"kind=binary 的Base64\"}"),
ToolSpec("timer_task_create", "创建定时岗位任务(2026-10-14 严格key校验): 用户要求\"定时/每天/循环/自动执行某任务\"时调用, 需用户当面确认才生效。参数: name 任务名, goal 任务目标(要 AI 做什么), cron 触发时间(格式 \"每天 HH:mm\" 如 \"每天 09:00\"), allow_tools 危险工具授权清单(数组, 每项 {\"key\":工具key,\"reason\":具体用途})。硬性要求: 1) 先分析 goal 判断完成任务真正需要哪些危险工具, 只申请必需项, 严禁过度打包; 2) 每个授权项必须写清具体用途 reason(如 workdir:write -> \"写入早报文件 Download/Nyral_work/xxx.txt\"), 禁止空话(如\"需要执行命令\"); 3) 用户批准后任务才创建并预授权, 拒绝则零残留; 4) 任务执行后若发现还需其它工具, 不要直接静默使用, 返回提示用户补充授权。", "JSON: {\"name\":\"每日早报\",\"goal\":\"每天早上整理新闻早报\",\"cron\":\"每天 09:00\",\"allow_tools\":[{\"key\":\"workdir:write\",\"reason\":\"把整理好的早报写入工作目录 Download/Nyral_work/morning_report.txt\"}]}"),
        ToolSpec("gen_image", "AI 生图(2026-11-04, 成本门禁, 多厂商契约驱动): 根据画面描述生成图片, 产物自动落盘并作为图片卡片回显。当前可用厂商、模型与额度元信息由本机配置动态注入。用户明确要求\"画一张/生成图片/配图/封面/示意图/插图\"时调用; 每次调用需用户确认, 且受月度限额保护。", "JSON: {\"prompt\":\"画面描述(必填, 尽量详细: 主体/风格/构图/光线)\",\"size\":\"图片尺寸(默认 1024*1024, 长*宽)\",\"n\":\"本次生成张数 1~4(默认 1, 计入月度限额)\",\"provider\":\"可选: 指定厂商显示名(如 通义万相/腾讯混元), 不填走默认\"}")
    )

    /**
     * 上下文瘦身(2026-08-31): system 只注入"瘦索引"(工具名+一句话用途),
     * 完整参数 schema 在原生 function calling 模式下由 tools 字段承载(请求级),
     * 不占用对话 token; 文本协议降级模式才按需把完整 desc+params 追加进上下文。
     */
    private val toolIndex: Map<String, String> = mapOf(
        "web" to "联网三合一: search 后台静默检索(Bing, 结果仅AI参考, 用户看不到页面; 想看搜索页用 browser(action=open)), fetch 抓网页正文, download 下载文件到工作目录",
        "browser" to "全屏浏览器控制(用户可见): open 打开网页/搜索词, scan 扫元素, text 读文字, scroll 滚动, click 点击, type 输入, upload 上传文件, clear_cache 清缓存, save_cookies 存登录Cookie",
        "app" to "第三方App控制: scan 扫屏幕元素(空树EMPTY_TREE), click 点击, text 输入, tap 坐标点击(x+y), screenshot 截图, back 返回, home 桌面, launch 启动App, installed 查已装",
        "workdir" to "手机工作目录文件: list 列文件, read 读, write 写, grep 搜索, head 读前N行, stats 统计",
        "file" to "远端文件(SSH): list 列目录, read 读, info 详情, write 写, upload 上传, download 下载, ls 列目录",
        "site_auth" to "管理站点登录 Cookie(site_auth.json)",
        "get_time" to "获取当前日期时间",
        "calc" to "数学计算",
        "memory_search" to "语义检索本地记忆",
        "read_file_audit" to "只读: 附件删除审计历史(时间/落库名/大小/mime/是否AI产物/来源), 排查被删文件",
        "device_snapshot" to "设备面板实时快照(CPU/最高核频率/内存/SoC温度/电池/负载/存储/近5分钟峰值), 只读",
        "device_probe" to "只读探针: 包名或进程名 → pid/RSS/线程/状态/同名内核模块",
        "ssh_run" to "SSH 远程执行命令",
        "artifact" to "产物登记: kind=text 文本成品 / kind=binary 二进制成品, 以卡片交付用户",
        "js_run" to "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 断网可用)",
        "sh_run" to "本机系统级执行 Shell 脚本(root 自动 su 提权, 危险命令拦截)",
        "ask_user" to "需求模糊/多义/缺关键信息时弹窗向用户澄清(候选选项+可选自定义输入), 用户选择作为结果返回",
        "tool_detail" to "查询未列出工具的完整规格并激活(激活后可直接调用)",
        "security_set" to "信任SSH主机密钥(ssh_trust); 门禁档位/root补权仅用户UI可改, 模型调用会被拒绝",
        "timer_task_create" to "创建定时岗位任务(name/goal/cron + 危险工具授权清单[每项必须给具体用途]), 需用户确认, 拒绝零残留",
        "gen_image" to "AI 生图(多厂商契约驱动, 按张计费, 每次需确认+月度限额): 根据画面描述生成图片并落盘回显"
    )

    // ===== Top N 动态装载(2026-09-16): 白名单+热度常驻, 冷门工具经 tool_detail 按需激活 =====
    private const val TOOL_DETAIL = "tool_detail"
    private const val BUILTIN_TOP_N = 16   // 内置工具常驻数(含 tool_detail 本身); 第二刀合并后共16个全量常驻
    private const val MCP_TOP_N = 4        // MCP 工具按热度常驻数
    /** 跨场景核心工具白名单: 永远注入完整 schema, 防冷启动雪藏 */
    private val TOOL_WHITELIST = setOf(
        "browser", "app", "workdir", "file", "web", "ssh_run", "memory_search",
        "sh_run", "js_run", "ask_user", "attach_read", "artifact"
    )

    interface Callback {
        fun onThinkingStart()          // 思考段开始
        fun onThinkingDelta(text: String)  // 思考内容增量(打字机)
        fun onThinkingEnd()            // 思考段结束 -> UI 收缩
        /** P2-1(2026-11-07): 工具参数仍在流式生成(尚无正文/思考增量), 用于显示"正在生成 xxx · N KB" */
        fun onToolPending(name: String, bytes: Int) {}
        fun onTool(name: String, arg: String)  // 正在执行工具
        fun onToolResult(name: String, result: String) {}  // 工具执行完成(结果回填, 默认空实现)
        fun onDelta(text: String)      // 正文增量(流式)
        fun onDone(reply: String)
        fun onError(msg: String)
    }

    /**
     * 停止后现场取舍 —— 方案B(2026-10-31 拍板): 保留现场。
     *
     * 旧实现(方案A)取消时统一 cb.onDone(""), 已流式显示的内容既不落库也不回填,
     * 表现为"停止后切会话/重启, 刚看到的一大段输出凭空消失"; 而 StreamUiBridge 又把
     * cancelRequested 重置为 false 后照常重建行, 气泡还会在停止瞬间被清空。
     * 现方案: 取消异常携带已产生正文(partial), 主循环拼上跨轮累计(full)交给 onDone,
     * UI 侧落库并在尾部追加〔已中断〕标记 —— 内容可回溯、可复制、可据此续跑。
     * 只有确实零输出时才维持"不落库"(避免每次误点停止都产生空气泡)。
     */
    class CancelWithPartial(val partial: String) : CancellationException("cancelled by user") {
        companion object {
            /** 拼接跨轮累计与当前轮现场: partial 已含于 full 尾部时不重复追加 */
            fun merge(full: String, partial: String): String {
                val p = partial.trim()
                return when {
                    p.isEmpty() -> full.trimEnd()
                    full.isBlank() -> p
                    full.trimEnd().endsWith(p) -> full.trimEnd()
                    else -> full.trimEnd() + "\n\n" + p
                }
            }
        }
    }

    // ================= 工具 Schema 注册(原生 function calling) =================

    /**
     * tools 能力降级(2026-11-07 加固): 旧实现是进程级 boolean, 任一偶发 4xx 命中
     * looksLikeToolsUnsupported 就会静默废掉整个会话的工具能力(所有工具退化为文本协议,
     * 用户侧表现为"AI 突然不会用工具"), 且只有切换 provider 才恢复。
     * 现改为按 providerId|model 组合记录 + 冷却期重探:
     *   - 组合不同(换供应商/换模型) 立即视为可用, 互不污染;
     *   - 降级后经 TOOLS_BACKOFF_MIN_MS 冷却, 到期自动放行一次重探, 失败则退避三倍递增,
     *     上限 TOOLS_BACKOFF_MAX_MS, 避免长期 4xx 时反复撞墙;
     *   - 任一次带 tools 的请求成功即清零降级状态与退避档位。
     */
    @Volatile
    private var toolsUnsupportedCombo: String? = null

    /** 降级置位时刻(currentTimeMillis), 与 toolsBackoffMs 共同决定冷却是否到期 */
    @Volatile
    private var toolsUnsupportedAt = 0L

    /** 当前退避档位: 0 表示从未降级; 每次重新置位乘以 3, 上限 TOOLS_BACKOFF_MAX_MS */
    @Volatile
    private var toolsBackoffMs = 0L

    /** 当前 provider|model 组合是否仍处于"不支持 tools"降级窗口内 */
    @Suppress("ReturnCount") // 三层早退（无降级/未到点/组合不符）比嵌套 if 更直白
    private fun toolsDegradedNow(): Boolean {
        val combo = ApiConfig.providerId() + "|" + ApiConfig.model()
        if (toolsUnsupportedCombo != combo) return false
        if (System.currentTimeMillis() - toolsUnsupportedAt >= toolsBackoffMs) {
            // 冷却到期: 放行一次重探; 若仍不支持, markToolsUnsupported() 会重新置位并加倍退避
            toolsUnsupportedCombo = null
            android.util.Log.i("Nyral", "tools 降级冷却到期, 重探组合: " + combo)
            return false
        }
        return true
    }

    /** 记录当前组合不支持 tools, 并推进退避档位 */
    private fun markToolsUnsupported() {
        toolsBackoffMs = if (toolsBackoffMs == 0L) TOOLS_BACKOFF_MIN_MS
                        else (toolsBackoffMs * 3).coerceAtMost(TOOLS_BACKOFF_MAX_MS)
        toolsUnsupportedCombo = ApiConfig.providerId() + "|" + ApiConfig.model()
        toolsUnsupportedAt = System.currentTimeMillis()
        android.util.Log.w("Nyral", "tools 降级置位, 退避 " + (toolsBackoffMs / MS_PER_SEC) + "s: " + toolsUnsupportedCombo)
    }

    /** 本轮尚未产出任何工具调用（断流/泄漏判定里反复出现的组合条件） */
    private fun noCalls(toolCalls: Collection<*>, nativeAcc: ToolCallAccumulator): Boolean =
        toolCalls.isEmpty() && nativeAcc.isEmpty()

    /** 带 tools 的请求成功: 清零降级状态与退避档位 */
    private fun clearToolsUnsupported() {
        if (toolsUnsupportedCombo != null || toolsBackoffMs != 0L) {
            android.util.Log.i("Nyral", "tools 能力恢复正常, 清零降级状态")
        }
        toolsUnsupportedCombo = null
        toolsBackoffMs = 0L
    }

    /** tools 降级后的首次冷却时长 */
    private const val TOOLS_BACKOFF_MIN_MS = 10 * 60_000L

    /** 退避上限: 有效 provider 因长期 4xx 被标记, 最长 2 小时重探一次 */
    private const val TOOLS_BACKOFF_MAX_MS = 2 * 60 * 60_000L

    /**
     * 工具自热度排序(2026-09-14): 按(调用次数 desc, 最近使用 desc)排序,
     * 冷启动无数据保持注册默认顺序; 对末尾从未调用的冷门工具压缩描述为 toolIndex 瘦索引,
     * 省 token 且仍可正常调用(不真删)。
     */
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
        // 能力域过滤(2026-10-30 P3): 体检会话只读 → 只下发只读工具; 特权工具任何域都不下发
        val visibleRegistry = toolRegistry.filter { CapabilityScope.allows(it.name, it.zone) }
        val ordered = if (hot.isEmpty()) visibleRegistry else visibleRegistry.sortedWith(
            compareByDescending<ToolSpec> { hot[it.name]?.count ?: 0 }
                .thenByDescending { hot[it.name]?.lastTs ?: 0L }
        )
        val reserved = visibleRegistry.filter { it.name in TOOL_WHITELIST || it.name == TOOL_DETAIL }
        val activated = ordered.filter { it.name in hotLoaded }
        val rest = ordered.filter { it.name !in TOOL_WHITELIST && it.name != TOOL_DETAIL && it.name !in hotLoaded }
        val top = reserved + activated + rest.take((BUILTIN_TOP_N - reserved.size).coerceAtLeast(0))
        for (spec in top) {
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", spec.name)
                    .put("description", if (spec.name == "gen_image") buildGenImageDesc(context) else spec.desc)
                    .put("parameters", builtinSchema(spec.name))))
        }
        // MCP 动态工具: 按热度取前 MCP_TOP_N + hotLoaded 激活项; schema 直接用 server 下发的 JSON Schema
        val mcpSpecs = McpClientManager.specEntries().map { (n, d, p) -> ToolSpec(n, d, p) }
            .filter { CapabilityScope.allows(it.name) }
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

    /** 动态生成 gen_image 描述: 注入本机已启用厂商元信息(不含 Key) */
    private fun buildGenImageDesc(context: Context): String =
        "AI 生图(多厂商契约驱动): 根据画面描述生成图片, 产物落盘回显。当前可用生图厂商:\n" +
        GenConfig.describeForAI(context) + "\n" +
        "用户不指定厂商时走 [默认]; 每次调用需用户确认, 受月度限额保护。"

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
                "script" to str("要执行的 Shell 脚本内容(多行用 \\n)。参数名必须是 script 而不是 command(sh_run 与 ssh_run 参数名不同)"),
                "timeout_ms" to int("超时毫秒(默认15000)", 15000))

            "web" -> obj(listOf("action"),
                "action" to str("操作: search 后台静默搜索 / fetch 抓取网页正文 / download 下载到工作目录", enums = listOf("search", "fetch", "download")),
                "q" to str("search 时: 搜索关键词", required = false),
                "url" to str("fetch/download 时: 目标地址, 以 http:// 或 https:// 开头", required = false),
                "max_results" to int("search 时: 返回结果条数(1-10)", 5),
                "max_chars" to int("fetch 时: 提取正文最大字符数(默认3000)", 3000),
                "name" to str("download 时: 可选保存文件名(不含路径分隔符)", required = false))
            "site_auth" -> obj(listOf("action"),
                "action" to str("操作: list 列出 / set 保存 / del 删除", enums = listOf("list", "set", "del")),
                "site" to str("站点域名, 如 annas-archive.org", required = false),
                "cookie" to str("完整 Cookie 字符串", required = false))
            "get_time" -> obj()
            "calc" -> obj(listOf("expr"), "expr" to str("数学表达式, 如 17*23"))
            "memory_search" -> obj(listOf("query"), "query" to str("要检索的记忆查询内容"))
            "read_file_audit" -> obj(emptyList(),
                "limit" to int("返回最近N条(默认50)", 50))
            "device_snapshot" -> obj(emptyList(),
                "fields" to JSONObject().put("type", "array")
                    .put("description", "要返回的字段: profile/cpu/mem/temp/batt/load/disk/series; 省略=全部")
                    .put("items", JSONObject().put("type", "string")))
            "device_probe" -> obj(listOf("target"),
                "target" to str("包名或进程名, 如 com.tencent.mm / system_server"))
            "ssh_run" -> obj(listOf("action"),
                "action" to str("操作: exec 远程执行命令 / add_config 新增或更新连接配置", enums = listOf("exec", "add_config")),
                "conn" to str("exec 时: 连接名", required = false),
                "command" to str("exec 时: 命令; 也可整体写成 连接名:命令 单字段形式", required = false),
                "name" to str("add_config 时: 连接名, 同名覆盖", required = false),
                "host" to str("add_config 时: 主机地址", required = false),
                "port" to int("add_config 时: 端口(默认22)", 22),
                "user" to str("add_config 时: 用户名", required = false),
                "password" to str("add_config 时: 密码(与 privateKey 至少给一个)", required = false),
                "privateKey" to str("add_config 时: 私钥内容", required = false),
                "passphrase" to str("add_config 时: 私钥口令", required = false),
                "proxyHost" to str("add_config 时: 跳板主机(选填)", required = false),
                "proxyPort" to int("add_config 时: 跳板端口(默认22)", 22),
                "proxyUser" to str("add_config 时: 跳板用户名", required = false))
            "file" -> obj(listOf("action"),
                "action" to str("操作", enums = listOf("list", "read", "info", "write", "upload", "download", "ls")),
                "conn" to str("SSH 连接名"),
                "path" to str("list/info/read/ls 时: 远程路径(目录或文件), 目录默认 ~", required = false),
                "lines" to int("read 时: 最多读取行数(默认200)", 200),
                "content" to str("write 时: 要写入的文件内容", required = false),
                "append" to bool("write 时: true 追加, false 覆盖(默认false)"),
                "local" to str("upload/download 时: 手机工作目录文件名(本地端)", required = false),
                "remote" to str("upload/download 时: 远端绝对路径", required = false))
            "attach_read" -> obj(listOf("name"),
                "name" to str("附件文件名(可带 att:// 前缀)"),
                "offset" to int("起始字符偏移, 默认0", 0),
                "limit" to int("本次最多返回字符数, 默认4000, 最大50000", 4000))
            "video_frame" -> obj(listOf("name", "timeMs"),
                "name" to str("附件文件名(可带 att:// 前缀)"),
                "timeMs" to int("目标时间点毫秒(默认0)", 0))
            "image_view" -> obj(listOf("name"),
                "name" to str("附件文件名(可带 att:// 前缀)"))
            "audio_read" -> obj(listOf("name"),
                "name" to str("附件文件名(可带 att:// 前缀)"))
            "write_clipboard" -> obj(listOf("text"),
                "text" to str("要复制到手机系统剪贴板的完整文本(用户可粘贴到其他 App)"))
            "artifact" -> obj(listOf("name", "kind"),
                "name" to str("产物文件名(带扩展名, 如 demo.html / icon.png)"),
                "kind" to str("产物类型: text 纯文本 / binary 二进制", enums = listOf("text", "binary")),
                "content" to str("kind=text 时: 完整产物内容(整文件一次给全, 不要截断)", required = false),
                "data" to str("kind=binary 时: 完整文件内容的 Base64(标准字母表, 不要换行前缀), 原始文件不超过 512KB", required = false))
            "gen_image" -> obj(listOf("prompt"),
                "prompt" to str("画面描述(必填, 尽量详细: 主体/风格/构图/光线)"),
                "size" to str("图片尺寸(默认 1024*1024, 长*宽)", required = false),
                "n" to int("本次生成张数 1~4(默认 1, 计入月度限额)", 1),
                "provider" to str("可选: 指定厂商显示名(如 通义万相/腾讯混元), 不填走默认", required = false))
            "tool_detail" -> obj(listOf("name"),
                "name" to str("要查询并临时激活的工具名(来自 system 工具索引)"))
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
    /**
     * C1 修复(2026-10-20): 主对话与定时任务并发共享单例状态(cancelRequested/activeConn)会互相干扰。
     * 改为全局单飞: 同一时刻只允许一个对话运行, 并发请求(定时任务)阻塞等待前一个对话结束;
     * cancelRequested 在持锁后统一重置, 避免等待线程误清正在运行对话的取消状态。
     */
    fun chat(context: Context, history: String, cb: Callback, attachments: List<Attachment> = emptyList(), silentMode: Boolean = false, allowTools: Set<String> = emptySet(), taskId: Long = 0L) {
        synchronized(chatLock) {
            cancelRequested = false
            chatImpl(context, history, cb, attachments, silentMode, allowTools, taskId)
        }
    }

    private fun chatImpl(context: Context, history: String, cb: Callback, attachments: List<Attachment> = emptyList(), silentMode: Boolean = false, allowTools: Set<String> = emptySet(), taskId: Long = 0L) {
        var attempt = 0
        // 跨轮累计正文: 声明在 try 外, 取消/重试 catch 分支也能带回已输出内容
        val full = StringBuilder()
        // 阶段3(2026-10-11): 任务指纹 + 路径沉淀命中, 轮首注入 system 复用成功工具序列
        val patSig = PatternStore.signatureOf(PatternStore.lastUserText(history))
        val patHit = PatternStore.match(context, patSig)
        val patHint = if (patHit != null && patHit.okCount >= 2 && patHit.okCount > patHit.failCount) {
            PatternStore.bumpHit(context, patSig)
            "\n【历史同类任务经验】此前同类任务用工具序列 " + patHit.toolSeq.joinToString(" → ") +
                " 成功完成过。若本次任务性质相同, 可优先复用该序列减少无效探索; 若已能直接回答也可直接回答, 不必强行调用工具。\n"
        } else null
        // 本轮实际执行过的工具名序列(保序去重), 结束按成败写入 PatternStore
        val toolSeqUsed = LinkedHashSet<String>()
        while (true) {
        try {
            McpClientManager.ensureLoaded(context)
            if (ApiConfig.apiKey().isBlank() && !ApiConfig.isCurrentLocal()) {
                // 2026-11-08: 区分“真没配”与“配置读不出来”(Keystore 解密失败会静默回退预设)
                val msg = if (ApiConfig.loadFailed) {
                    "模型配置读取失败(密钥库异常), 请到「设置 → 模型配置」重新填写 API Key"
                } else {
                    "未配置 API Key, 请先到「设置 → 模型配置」填写"
                }
                cb.onError(msg)
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
            val messages = buildRouteMessages(context, history, attachments, memInject, patHint)
            var toolCount = 0
            var retriedEmpty = false
            // 文本协议降级模式下已注入完整 schema 的工具集
            val injectedTools = mutableSetOf<String>()
            // Top N 动态装载(2026-09-16): 经 tool_detail 激活的冷门工具, 本轮内额外注入回调列表
            val hotLoaded = mutableSetOf<String>()

            while (true) {
                if (cancelRequested) {
                    // 方案B: 轮间停止也带回已累计正文(旧实现 onDone("") 丢弃)
                    cb.onDone(scrubXmlLeak(full.toString()))
                    return
                }
                val res = streamOnce(context, messages, cb, answerGate, injectedTools, hotLoaded, taskId = taskId)
                // 弱网续跑: 流中断但已有部分输出 -> 携带已输出内容让模型从断点续写, 不重来不静默
                if (res.interrupted && res.accumulated.isNotBlank()) {
                    attempt++
                    if (attempt >= MAX_NET_RETRY) {
                        cb.onError("网络连接中断，已自动重试多次仍失败，请检查网络后重试")
                        return
                    }
                    full.append(capOut(res.accumulated))
                    cb.onDelta("\n\n[网络中断，正在续跑…]")
                    messages.put(JSONObject().put("role", "assistant").put("content", capOut(res.accumulated.trimEnd())))
                    messages.put(JSONObject().put("role", "user").put("content",
                        "你刚才的回答因网络中断未能完整输出。请严格从断点继续完整输出剩余内容，不要重复已输出的部分。"))
                    // 指数退避: 与零输出重连对齐(1s/2s/4s), 给弱网恢复窗口
                    sleepCancellable(1000L shl (attempt - 1))
                    continue
                }
                val toolCalls = res.toolCalls
                if (toolCalls.isNotEmpty()) {
                    if (toolCount >= MAX_TOOL_CALLS) {
                        // 收尾轮(2026-10-10): 预算耗尽不再硬断报错, 注入强制总结指令 + 无工具请求, 兜底交付已有输出
                        android.util.Log.i("Nyral", "BudgetExhaustedSummary")
                        val finalMsg = "工具调用预算已耗尽(已达 $MAX_TOOL_CALLS 次上限)。请仅基于已获得的工具执行结果, 直接向用户输出最终答案总结。不要发起任何工具调用, 不要重复执行工具, 不要输出思考过程。"
                        messages.put(JSONObject().put("role", "user").put("content", finalMsg))
                        val resFinal = streamOnce(context, messages, cb, answerGate, injectedTools, hotLoaded, forceNoTools = true, taskId = taskId)
                        val finalText = resFinal.accumulated.trim()
                        if (finalText.isNotBlank()) {
                            full.append(capOut(finalText))
                        } else {
                            cb.onDelta("\n\n[工具预算已耗尽, 未能生成最终总结; 以上为已完成的执行结果]")
                        }
                        PatternStore.record(context, patSig, toolSeqUsed.toList(), ok = true)
                        cb.onDone(scrubXmlLeak(full.toString()))
                        return
                    }
                    // 正则化执行本轮全部工具(可能并行多 call), 结果逐条回填 role=tool
                    val execResults = ArrayList<Pair<String, String>>() // (自定义显示行, 工具结果文本)
                    // 截断配对: 只执行额度内子集, 回填用同一子集保证 assistant tool_calls 与 tool 结果数量对齐
                    val execCalls = ToolCallPairer.allowedSubset(toolCalls, toolCount, MAX_TOOL_CALLS)
                    toolCount += execCalls.size
                    val schemaBlocks = StringBuilder()
                    for ((callIdx, call) in execCalls.withIndex()) {
                        val name = call.first
                        val arg = call.second
                        // 2026-10-31: 停止在工具循环内同样生效——已取消则剪枝剩余调用, 不再执行
                        // 方案B: 携带本轮已产出正文(工具轮通常为空, 但引导语要保留)
                        if (cancelRequested) {
                            // P2-2(2026-11-07): 中断补偿——本批里参数已完整收到的幂等写产物调用先执行,
                            // 避免模型花几十秒生成的长文产物因一次停止/断线全部作废(实测故障场景)。
                            salvageOnCancel(context, execCalls.drop(callIdx), cb, hotLoaded, silentMode, allowTools)
                            throw CancelWithPartial(res.accumulated)
                        }
                        cb.onTool(name, arg)
                        val result = ToolExecutor.execute(context, name, arg, hotLoaded, silentMode, allowTools)
                        // 工具执行返回后立刻判定: 长耗时工具(sh_run/ssh_run)期间按了停止, 不再回填+继续下一个
                        if (cancelRequested) throw CancelWithPartial(res.accumulated)
                        cb.onToolResult(name, result)
                        execResults.add(name to result)
                        toolSeqUsed.add(name)
                        // 上下文瘦身仅文本协议模式用; 原生模式的 schema 在 tools 字段, 此处仅兜底补全
                        val schemaHint = if (!toolsDegradedNow() && injectedTools.add(name)) "" else {
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
                            val clean = if (name == "video_frame" || name == "pdf_page" || name == "image_view") result.substringBefore(" FRAME:data:image/jpeg;base64,")
                            else if (name == "audio_read") result.substringBefore(" AUDIO:data:")
                            else result
                            messages.put(JSONObject().put("role", "user").put("content",
                                "[工具 $name 执行结果]\n" + capOut(wrapToolResult(name, clean))))
                        }
                    } else {
                        messages.put(buildAssistantToolMessage(res, execCalls))
                        for ((i, er) in execResults.withIndex()) {
                            // 按 toolCalls 序号取对应 id: 并行同名工具各自独立, 防回填重复 tool_call_id
                            val toolCallId = res.idAt(i) ?: "call_${er.first}_$toolCount"
                            // video_frame 帧图剥离: tool 消息只回填文本摘要, 帧图由下方 user 消息注入(防 capOut 截断)
                            val clean = if (er.first == "video_frame" || er.first == "pdf_page" || er.first == "image_view") er.second.substringBefore(" FRAME:data:image/jpeg;base64,")
                            else if (er.first == "audio_read") er.second.substringBefore(" AUDIO:data:")
                            else er.second
                            messages.put(JSONObject().put("role", "tool")
                                .put("tool_call_id", toolCallId)
                                .put("content", capOut(wrapToolResult(er.first, clean))))
                        }
                    }
                    // 媒体注入: 按当前模型能力分流 —— 有 CAP_IMAGE 才注入 image_url, 有 CAP_AUDIO 才注入 input_audio;
                    // 无对应能力时不发字节, 改为注入能力边界提示(避免非视觉/非音频模型收到媒体占位符误判通道故障)
                    val curPid = ApiConfig.providerId()
                    val curModel = ApiConfig.model()
                    val canSeeImage = ApiConfig.modelHasCap(curPid, curModel, ApiConfig.CAP_IMAGE)
                    val canHearAudio = ApiConfig.modelHasCap(curPid, curModel, ApiConfig.CAP_AUDIO)
                    for ((vName, vResult) in execResults) {
                        if ((vName == "video_frame" || vName == "pdf_page" || vName == "image_view") && vResult.contains(" FRAME:data:image/jpeg;base64,")) {
                            if (canSeeImage) {
                                val b64 = vResult.substringAfter(" FRAME:data:image/jpeg;base64,").trim()
                                val imgArr = JSONArray()
                                    .put(JSONObject().put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                                    .put(JSONObject().put("type", "text").put("text", "（以上为工具读取的画面图，请结合查看后继续）"))
                                messages.put(JSONObject().put("role", "user").put("content", imgArr))
                            } else {
                                messages.put(JSONObject().put("role", "user").put("content",
                                    "（工具 $vName 读取的画面图未能注入：当前模型 $curModel 不支持图片输入。请告知用户：当前模型无法识图，如需查看图片请切换到支持图片输入的模型，或使用 file_export 导出图片查看。）"))
                            }
                        } else if (vName == "audio_read" && vResult.contains(" AUDIO:data:")) {
                            if (canHearAudio) {
                                // data uri 形如 data:{mime};base64,{b64}, format 从 mime 归一化
                                val dataUri = vResult.substringAfter(" AUDIO:data:").trim()
                                val mime = dataUri.substringBefore(";base64,").removePrefix("data:")
                                val b64 = dataUri.substringAfter(";base64,")
                                val norm: Pair<String, String> = when {
                                    mime.startsWith("audio/wav") || mime.startsWith("audio/x-wav") || mime.startsWith("audio/wave") -> "audio/wav" to "wav"
                                    mime.startsWith("audio/mpeg") || mime.startsWith("audio/mp3") -> "audio/mpeg" to "mp3"
                                    mime.startsWith("audio/mp4") || mime.startsWith("audio/x-m4a") || mime.startsWith("audio/m4a") -> "audio/mp4" to "m4a"
                                    mime.startsWith("audio/flac") || mime.startsWith("audio/x-flac") -> "audio/flac" to "flac"
                                    mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") -> "audio/ogg" to "ogg"
                                    else -> mime to mime.removePrefix("audio/").substringBefore("+").substringBefore(";")
                                }
                                val audArr = JSONArray()
                                    .put(JSONObject().put("type", "input_audio")
                                        .put("input_audio", JSONObject().put("data", "data:${norm.first};base64,$b64").put("format", norm.second)))
                                    .put(JSONObject().put("type", "text").put("text", "（以上为工具读取的音频，请结合听取后继续）"))
                                messages.put(JSONObject().put("role", "user").put("content", audArr))
                            } else {
                                messages.put(JSONObject().put("role", "user").put("content",
                                    "（工具 audio_read 读取的音频未能注入：当前模型 $curModel 不支持音频输入。请告知用户：当前模型无法听取音频，如需听取请切换到支持音频输入的模型，或使用 file_export 导出音频。）"))
                            }
                        }
                    }
                    // 若无有效输出且未达上限, 给模型一个继续指令
                    val schemaBlock = if (schemaBlocks.isEmpty()) "" else "本工具参数说明:\n$schemaBlocks"
                    // 视频直发只保留首轮: 后续工具轮次剥离 video_url(保留索引卡), 避免原视频被重复上传计费
                    val stripped = stripVideoParts(messages)
                    if (stripped > 0) android.util.Log.i("Nyral", "strip $stripped video_url part(s) after first round, follow-up via video_frame")
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
                PatternStore.record(context, patSig, toolSeqUsed.toList(), ok = true)
                cb.onDone(scrubXmlLeak(full.toString()))
                return
            }
        } catch (e: NoToolsException) {
            // 当前 provider 不支持 tools: 已置降级标志, 立即重跑请求(不计入网络重试额度), 文本协议兜底接管
            android.util.Log.w("Nyral", "no-tools fallback retry (attempt=${attempt})")
            continue
        } catch (e: VideoDirectRejectedException) {
            // 视频直发被拒: 已记下 provider|model 组合, 重跑时 attachments 改走索引卡(附件已落库不丢),
            // 模型会按索引卡指引调 video_frame 抽帧
            android.util.Log.w("Nyral", "video direct rejected, fallback to frame extraction (attempt=${attempt})")
            continue
        } catch (e: RetryableException) {
            // 取消优先于自动重试: 取消引发的断线若被吞成重试, 会先污染 UI("[网络波动]")再空 onDone 丢整轮内容
            if (cancelRequested) {
                android.util.Log.i("Nyral", "chat cancelled during net-retry (skip retry)")
                cb.onDone(scrubXmlLeak(full.toString()))
                return
            }
            // 请求级断线: 自动重连一次, 全程无输出, 无需用户手动"继续"
            attempt++
            if (attempt >= MAX_NET_RETRY) {
                android.util.Log.e("Nyral", "chat network retry exhausted", e)
                PatternStore.record(context, patSig, toolSeqUsed.toList(), ok = false)
                cb.onError(e.message ?: "网络连接中断，请检查网络后重试")
                return
            }
            android.util.Log.w("Nyral", "chat network interrupted, auto-retry #$attempt: ${e.message}")
            cb.onDelta("\n\n[网络波动，已自动重连一次]")
            // 指数退避: 1s/2s/4s, 给弱网恢复窗口
            sleepCancellable(1000L shl (attempt - 1))
            continue
        } catch (e: CancelWithPartial) {
            // 方案B(2026-10-31): 停止保留现场 —— 跨轮累计 + 本轮已流式产出的正文一并交回 UI,
            // 由 StreamUiBridge 落库并追加〔已中断〕标记(空内容时仍按"不落库"处理)
            val body = scrubXmlLeak(CancelWithPartial.merge(full.toString(), e.partial))
            android.util.Log.i("Nyral", "cancel body hasLeak=" + body.contains("</tool_call>") + " partialHasLeak=" + e.partial.contains("</tool_call>"))
            android.util.Log.i("Nyral", "chat cancelled by user, keep partial len=${body.length}")
            cb.onDone(body)
            return
        } catch (e: Exception) {
            if (cancelRequested) {
                // 用户主动停止: 不视为错误; 方案B 带上已累计正文, 不再空 onDone 丢弃现场
                android.util.Log.i("Nyral", "chat cancelled by user, keep len=${full.length}")
                cb.onDone(scrubXmlLeak(full.toString()))
                return
            }
            android.util.Log.e("Nyral", "chat error", e)
            PatternStore.record(context, patSig, toolSeqUsed.toList(), ok = false)
            cb.onError(e.message ?: "未知错误")
            return
        }
        }
    }

    /**
     * 取消/断线补偿执行(P2-2, 2026-11-07)。
     * 仅抢救"参数已完整 + 语义幂等"的产物写入类调用(artifact_save / artifact_save_bin / workdir write):
     * 这类调用重放无副作用, 结果就是文件落盘, 用户等待几十秒的产物不该被一次取消抹掉。
     * 其余工具(联网、改设备状态、App 操作等)一律不补偿——中断时的部分语义可能不安全。
     */
    @Suppress("LongParameterList") // 补偿执行需同时持有回调/上下文/热加载集合，拆对象反而更绕
    private fun salvageOnCancel(context: Context, remaining: List<Pair<String, String>>, cb: Callback,
                                hotLoaded: MutableSet<String>, silentMode: Boolean, allowTools: Set<String>) {
        if (silentMode || remaining.isEmpty()) return
        for ((name, arg) in remaining) {
            if (!CancelSalvagePolicy.isEligible(name, arg)) continue
            android.util.Log.i("Nyral", "SalvageOnCancel: $name len=${arg.length}")
            cb.onTool(name, arg)
            runCatching { ToolExecutor.execute(context, name, arg, hotLoaded, silentMode, allowTools) }
                .onSuccess {
                    cb.onToolResult(name, it)
                    LogStore.w(LogStore.MAIN, "已中断，补偿保存产物: $name")
                }
                .onFailure { android.util.Log.w("Nyral", "SalvageOnCancel fail: $name", it) }
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
    @Suppress(
        "MaxLineLength", // EOF/断流日志刻意单行，便于 grep 现场
        "LongMethod", // 369 行解析状态机，拆分待 P2-4（真机回归兜底充分后再动）
        "CyclomaticComplexMethod", // 同上：分片/泄漏/断流三态交织，天然分支多
        "NestedBlockDepth", // 同上
        "ThrowsCount", // 断流/泄漏重试需抛可重试异常，属既定控制流语义
    )
    private fun streamOnce(
        context: Context,
        messages: JSONArray,
        cb: Callback,
        answerGate: () -> String?,
        injectedTools: MutableSet<String>,
        hotLoaded: Set<String>,
        forceNoTools: Boolean = false,
        taskId: Long = 0L
    ): StreamResult {
        val body = JSONObject()
        body.put("model", ApiConfig.model())
        body.put("messages", messages)
        body.put("temperature", 0.3)
        body.put("stream", true)
        // 原生 function calling: 请求携带 tools(模型支持时); probe 失败过则降级纯文本
        val useTools = !toolsDegradedNow() && !forceNoTools
        if (useTools) body.put("tools", buildToolsArray(context, hotLoaded))
        // 默认厂商支持流式返回真实 usage(最后一块); 其余厂商未知, 不加避免报错, 靠本地估算
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
        // 定时岗位任务来源标识(2026-10-13): 静默执行带 taskId, 远端可识别请求归属
        if (taskId > 0L) conn.setRequestProperty("X-Nyral-Task-ID", taskId.toString())
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        activeConn = conn
        activeConns.add(conn)
        val accumulated = StringBuilder()
        val toolCalls = mutableListOf<Pair<String, String>>()
        // 与 toolCalls 按序一一对应的 tool_call_id(并行同名工具必须各自独立 id, 按 name 建映射会覆盖
        // 导致回填重复 id -> 服务端 400 Duplicate tool_call_id -> 误降级文本协议 -> DSML 泄漏, 2026-09-18)
        val toolCallIds = ArrayList<String>()
        val restartOut = ArrayList<String>()
        // DSML 泄漏原文(解析成功时捕获, 供主循环判定走文本回填链; 需声明在 try 外, return 要用)
        var dsmlRaw: String? = null
        var reader: BufferedReader? = null
        /** 流中断(连接断开/EOF)但已有部分输出: 主循环据此续跑重连(声明在 try 外, return 要用) */
        var interruptedOut = false
        try {
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "(无响应体)"
            conn.disconnect()
            // 4xx 中疑似"不支持 tools/function calling"的报错 -> 降级纯文本模式重试一次
            if (code in 400..499 && useTools && looksLikeToolsUnsupported(err)) {
                markToolsUnsupported()
                android.util.Log.w("Nyral", "provider 不支持 tools, 降级文本协议: ${err.take(200)}")
                throw NoToolsException("PROVIDER_NO_TOOLS")
            }
            // 视频直发被拒(2026-10-26): 请求里带 video_url 且 4xx -> 记下该 provider|model 组合,
            // 立即重跑; 重跑时附件改发索引卡, 由 AI 调 video_frame 抽帧(附件已落库, 不丢内容)
            if (code in 400..499 && hasVideoPart(messages)) {
                videoDirectDisabledKey = ApiConfig.providerId() + "|" + ApiConfig.model()
                android.util.Log.w("Nyral", "video_url 直发被拒($code), 降级抽帧: ${err.take(200)}")
                throw VideoDirectRejectedException("VIDEO_DIRECT_REJECTED")
            }
            // 音频直发被拒(2026-10-26): 请求里带 input_audio 且 4xx -> 记组合, 重跑改走 audio_read 索引卡
            if (code in 400..499 && hasAudioPart(messages)) {
                audioDirectDisabledKey = ApiConfig.providerId() + "|" + ApiConfig.model()
                android.util.Log.w("Nyral", "input_audio 直发被拒($code), 降级 audio_read: ${err.take(200)}")
                throw VideoDirectRejectedException("AUDIO_DIRECT_REJECTED")
            }
            throw RuntimeException("API $code: $err")
        }
        // 带 tools 的请求拿到 2xx: 能力已恢复, 清零降级状态
        if (useTools) clearToolsUnsupported()
        reader = BufferedReader(InputStreamReader(conn.inputStream))
        val lineBuf = StringBuilder()
        // XML 工具调用累积缓冲: 部分模型输出 <tool_call><tool_name>x</tool_name><param>...</param></tool_call> 跨行格式
        var xmlBuf: StringBuilder? = null
        // DSML 泄漏拦截缓冲(2026-09-18): deepseek-flash 间歇性把工具调用以 DSML 文本写进 content 通道
        // (而非 delta.tool_calls), 命中后进缓冲不显示, 块闭合后解析回工具调用; 单/双竖线变体均兼容
        var dsmlBuf: StringBuilder? = null
        // XML 泄漏拦截缓冲(2026-11-01): deepseek-flash 把工具调用以 <tool_call>名<arg_key>..</arg_key><arg_value>..</arg_value></tool_call>
        // 平铺格式泄漏到 content 通道(而非 delta.tool_calls), 原生 tools 模式命中后进缓冲, 块闭合后解析回工具调用
        var xmlLeakBuf: StringBuilder? = null
        // 2026-11-09: 本轮曾因泄漏块解析不出有效调用而被丢弃(整轮被吞光时需要触发重试, 不能给用户空气泡)
        var xmlLeakDropped = false
        // 2026-11-09: 代码围栏跟踪——围栏内不判泄漏, 防止让 AI 贴 XML/工具标签示例时被当成泄漏吞掉
        var inFence = false
        var mode = MODE_NONE
        var aborted = false
        var seenDone = false   // 收到 [DONE] 才算服务端正常结束
        var eofAborted = false // readLine null(连接被服务端/网关关闭)视为异常中断
        // 缺陷1(2026-11-07): 厂商声明的终态标志(含 stop/tool_calls/length), 与 [DONE] 共同构成"流完整结束"判定
        var sawFinishReason = false
        // 原生 function calling 增量累积(index -> id/name/arguments): 逻辑抽取到 ToolCallAccumulator,
        // 与 JVM fuzz(StreamPipelineFuzzTest) 共用同一份实现, 保证测的就是线上代码
        val nativeAcc = ToolCallAccumulator()
        // P2-1(2026-11-07) 参数生成进度上报节流: 512 字节或 800ms 触发一次, 防高频 delta 刷 UI
        val pendingGate = PendingProgressGate(byteStep = 512, timeStepMs = 800L)
        var finishedByToolCalls = false

        try {
        while (true) {
            if (cancelRequested) throw CancelWithPartial(accumulated.toString())
            val rd = reader ?: break
            val line = rd.readLine()
            if (line == null) { eofAborted = true; break }
            android.util.Log.v("Nyral", "SSE chunk len=${line.length}")
            val data = SseLine.payload(line) ?: continue
            if (SseLine.isDone(data)) { seenDone = true; break }
            val parsed = try { JSONObject(data) } catch (e: Exception) { null } ?: continue
            val choices = parsed.optJSONArray("choices")
            val usage = parsed.optJSONObject("usage")
            // 流式结束的 usage chunk: 携带真实 token 数(默认厂商在 finish_reason=stop 的块返回 usage)
            if (usage != null && usage.optLong("prompt_tokens", 0) > 0) {
                usedPrompt = usage.optLong("prompt_tokens", 0)
                usedCompletion = usage.optLong("completion_tokens", 0)
            }
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                // 缺陷1 补丁(2026-11-07): Android 的 optString 会把 JSON null 转成字符串 "null",
                // 而 OpenAI 兼容流的中间帧普遍携带 "finish_reason": null。原写法会让 sawFinishReason 每帧置真,
                // "流是否完整" 判定恒成立 → 断流截断参数仍被当完整参数 drain 执行。必须先区分 null 与真值。
                val frRaw = choice.opt("finish_reason")
                val fr = if (frRaw == null || frRaw == JSONObject.NULL) "" else frRaw.toString()
                if (fr.isNotBlank()) sawFinishReason = true
                if (fr == "tool_calls") finishedByToolCalls = true
                val delta = choice.optJSONObject("delta")
                // 原生 function calling: delta.tool_calls 增量(分片 arguments)
                if (delta != null && !delta.isNull("tool_calls")) {
                    val calls = delta.getJSONArray("tool_calls")
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val idx = c.optInt("index", i)
                        val fn = c.optJSONObject("function")
                        nativeAcc.onDelta(
                            idx,
                            if (c.isNull("id")) null else c.optString("id"),
                            if (fn == null || fn.isNull("name")) null else fn.optString("name"),
                            if (fn == null || fn.isNull("arguments")) null else fn.optString("arguments"),
                        )
                    }
                    // 命中 tool_calls 增量即说明思考终结
                    if (mode == MODE_THINKING) { mode = MODE_NONE; cb.onThinkingEnd() }
                    // P2-1(2026-11-07): 模型可能把长文整段塞进写操作参数(如 artifact_save 的 content),
                    // 该阶段既无思考也无正文增量, 用户侧等同"点了没反应"。此处按字节/时间节流上报进度。
                    val totalPending = nativeAcc.totalArgBytes()
                    if (pendingGate.shouldReport(totalPending, System.currentTimeMillis())) {
                        cb.onToolPending(nativeAcc.firstNonBlankName(), totalPending)
                    }
                    continue
                }
                // 推理模型(如小米MiMo): 思考内容在 reasoning_content, 正文在 content
                val rText = if (delta != null && !delta.isNull("reasoning_content")) delta.optString("reasoning_content") else ""
                if (rText.isNotEmpty()) {
                    android.util.Log.d("Nyral", "SSE-THINK reasoning_content len=" + rText.length + " head=" + rText.take(40).replace("\n", " "))
                    if (mode != MODE_THINKING) {
                        mode = MODE_THINKING
                        cb.onThinkingStart()
                    }
                    cb.onThinkingDelta(rText)
                    // 2026-11-09 修复: 同帧 reasoning_content 与 content 并存时, 原实现 continue 直接跳过正文分支
                    // (NVIDIA nemotron 等模型首帧常同时给思考尾巴+正文开头), 导致每次回答首字被吞
                }
                val text = if (delta != null && !delta.isNull("content")) delta.optString("content") else ""
                if (text.isEmpty()) continue
                android.util.Log.d("Nyral", "SSE-CONTENT len=" + text.length + " head=" + text.take(60).replace("\n", " "))
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
                        // 2026-11-09: 文本协议路径同步白名单制(此前只认 <tool_call/<tool_name 前缀,
                        // 漏判 </tool_call> 孤立闭合、<function_call name=..>、<tool_detail>裸名 -> 落库污染上下文)
                        val fRow = row.trimStart()
                        if (fRow.startsWith("```") && Regex("```").findAll(fRow).count() == 1) inFence = !inFence
                        if (xmlBuf != null || (!inFence && isXmlLeakRow(row))) {
                            val xbuf = (xmlBuf ?: StringBuilder()).append(row).append('\n')
                            xmlBuf = xbuf
                            val bufStr = xbuf.toString()
                            if (bufStr.contains("</tool_call>") || xmlTagsClosed(bufStr)) {
                                val xml = bufStr
                                xmlBuf = null
                                val t = parseXmlToolCall(xml)
                                if (t != null) {
                                    if (mode == MODE_THINKING) cb.onThinkingEnd()
                                    mode = MODE_NONE
                                    toolCalls.add(t)
                                    toolCallIds.add("xml_${toolCalls.size}")
                                    break
                                }
                                // 无有效调用的元标签块: 静默丢弃, 不回填正文(泄漏文本会落库污染下一轮上下文)
                                android.util.Log.w("Nyral", "XML 泄漏块无有效工具调用已丢弃(text): " + bufStr.take(120))
                                xmlLeakDropped = true
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
                        // XML 泄漏拦截(2026-11-01): deepseek-flash 偶发把工具调用以泄漏文本写进 content 通道,
                        // 实测形态: <tool_call>名<arg_key>..</arg_key><arg_value>..</arg_value></tool_call>
                        // 以及 <工具名>{"arg":"val"}</工具名>(如 <memory_search>{"query":".."}</memory_search>),
                        // 命中后进缓冲不显示, 块闭合后解析回工具调用
                        // 2026-11-09: 围栏行只翻转状态; 围栏内的 <...> 一律当正文(不判泄漏)
                        val fenceRow = row.trimStart()
                        if (fenceRow.startsWith("```") && Regex("```").findAll(fenceRow).count() == 1) inFence = !inFence
                        if (xmlLeakBuf != null || (!inFence && isXmlLeakRow(row))) {
                            val xbuf = (xmlLeakBuf ?: StringBuilder()).append(row).append('\n')
                            xmlLeakBuf = xbuf
                            val bufStr = xbuf.toString()
                            val closed = bufStr.contains("</tool_call>") || xmlTagsClosed(bufStr)
                            if (closed) {
                                xmlLeakBuf = null
                                val t = parseXmlToolCall(bufStr)
                                if (t != null) {
                                    if (mode == MODE_THINKING) cb.onThinkingEnd()
                                    mode = MODE_NONE
                                    toolCalls.add(t)
                                    toolCallIds.add("xml_${toolCalls.size}")
                                    android.util.Log.w("Nyral", "XML 泄漏已拦截解析: ${t.first} argsLen=${t.second.length}")
                                    break
                                }
                                // 全为元标签(tool_detail 等)或解析失败: 静默丢弃, 不污染 UI, 并记下本轮曾丢弃以便收尾重试
                                android.util.Log.w("Nyral", "XML 泄漏块无有效工具调用已丢弃: ${bufStr.take(120)}")
                                xmlLeakDropped = true
                            }
                            continue
                        }
                        if (row.startsWith("思考:") || row.startsWith("思考：")) {
                            android.util.Log.d("Nyral", "SSE-THINK text-protocol prefix row")
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
                            if (mode == MODE_THINKING) {
                                // 思考续行(无前缀): 追加进思考区, 不切正文; 正文仅由"答案:"前缀行触发
                                // (2026-10-11 修复: 此前续行被强制切正文 -> 思考气泡不完整 + 思考泄漏进正文)
                                cb.onThinkingDelta(row)
                                continue
                            }
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
            if (useTools && finishedByToolCalls && nativeAcc.isNotEmpty()) break
        }
        } catch (e: SocketException) {
            aborted = true
            android.util.Log.w("Nyral", "SSE connection aborted: ${e.message}")
        } catch (e: IOException) {
            aborted = true
            android.util.Log.w("Nyral", "SSE read error: ${e.message}")
        }
        // EOF 无 [DONE] 视为中断: 服务端/网关提前关闭连接(运营商 NAT 掐断/弱网断流), 不再静默当正常结束
        if (eofAborted && !seenDone) {
            aborted = true
            android.util.Log.w("Nyral", "SSE EOF without [DONE] (connection closed by server/gateway)")
        }
        // 连接中断收尾
        if (aborted) {
            // 断流瞬间的末尾残余(整行无换行)先并入, 否则已有内容也会被误判成零输出而丢弃
            val tailResidue = lineBuf.isNotBlank() && !DSML_MARK_RE.containsMatchIn(lineBuf)
            if (toolCalls.isEmpty() && nativeAcc.isEmpty() && tailResidue) {
                val tail = lineBuf.toString().trimEnd()
                lineBuf.setLength(0)
                if (tail.isNotEmpty()) {
                    if (mode != MODE_CONTENT) {
                        if (mode == MODE_THINKING) cb.onThinkingEnd()
                        mode = MODE_CONTENT
                    }
                    if (useTools) {
                        cb.onDelta(tail); accumulated.append(tail)
                    } else {
                        val stop = processRow(tail, mode, accumulated, cb, { m -> mode = m }, answerGate, restartOut)
                        if (stop != null) {
                            toolCalls.add(stop)
                            toolCallIds.add("txt_${toolCalls.size}")
                        }
                    }
                }
            }
            if (mode == MODE_THINKING) cb.onThinkingEnd()
            if (toolCalls.isEmpty() && nativeAcc.isEmpty() && accumulated.isBlank()) {
                throw RetryableException("模型连接中断(网络波动)")
            }
            if (toolCalls.isEmpty() && nativeAcc.isEmpty() && accumulated.isNotBlank()) {
                // 有输出断线: 不在此追加提示, 主循环按 interrupted 续跑重连(统一提示, 不静默不丢内容)
                interruptedOut = true
            }
        }
        android.util.Log.i("Nyral", "EOF lineBuf=[$lineBuf] mode=$mode nativeAcc=${nativeAcc.size()} toolCalls=${toolCalls.size} aborted=$aborted")
        // 末尾残余(无换行的最后一段)
        if (toolCalls.isEmpty() && nativeAcc.isEmpty() && lineBuf.isNotBlank()) {
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
        if (toolCalls.isEmpty() && nativeAcc.isEmpty() && dsmlBuf != null) {
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
        // EOF 时 XML 泄漏整块留在 lineBuf(全程无换行): 直接尽力解析, 解析出即采纳
        val noToolOutput = useTools && noCalls(toolCalls, nativeAcc)
        if (noToolOutput && xmlLeakBuf == null && isXmlLeakRow(lineBuf.toString())) {
            val t = parseXmlToolCall(lineBuf.toString())
            if (t != null) {
                if (mode == MODE_THINKING) cb.onThinkingEnd()
                mode = MODE_NONE
                toolCalls.add(t)
                toolCallIds.add("xml_${toolCalls.size}")
                android.util.Log.w("Nyral", "XML 泄漏 lineBuf 整块 EOF 解析: ${t.first} argsLen=${t.second.length}")
            }
        }
        // EOF 时 XML 泄漏缓冲残留(原生模式半截/流被切断): 追加 lineBuf 残余后尽力解析, 解析出即采纳为工具调用
        if (useTools && noCalls(toolCalls, nativeAcc) && xmlLeakBuf != null) {
            xmlLeakBuf.append(lineBuf)
            val t = parseXmlToolCall(xmlLeakBuf.toString())
            if (t != null) {
                if (mode == MODE_THINKING) cb.onThinkingEnd()
                mode = MODE_NONE
                toolCalls.add(t)
                toolCallIds.add("xml_${toolCalls.size}")
                android.util.Log.w("Nyral", "XML 泄漏半截 EOF 尽力解析: ${t.first} argsLen=${t.second.length}")
            } else {
                android.util.Log.w("Nyral", "XML 泄漏残留解析失败已丢弃: ${xmlLeakBuf.toString().take(120)}")
            }
            xmlLeakBuf = null
        }
        // 泄漏重试(2026-11-01): 原生模式整轮只吐工具泄漏文本、且没解析出任何调用或正文
        // → 模型输出格式抽风(deepseek-flash 偶发), 抛重试让上层自动重连碰原生 tool_calls
        val leakPending = isXmlLeakRow(lineBuf.toString()) || xmlLeakBuf != null || dsmlBuf != null || xmlLeakDropped
        if (noToolOutput && accumulated.isBlank() && leakPending) {
            android.util.Log.w("Nyral", "模型输出工具调用格式异常(<tool_call>泄漏未解析), 触发自动重试")
            throw RetryableException("模型输出工具调用格式异常，自动重试")
        }
        // 原生 tool_calls: 流结束把累积结果转出(即使 finish_reason 未明确 tool_calls)
        // 缺陷1(2026-11-07): 必须区分"正常收尾"与"断连截断"。只有收到 finish_reason 或 [DONE] 才算完整;
        // 否则(readLine 返回 null 的 EOF / 网关掐断) arguments 可能停在半句, 整轮丢弃,
        // 绝不把截断参数当完整参数执行(此前 {"expr": "1 这类残片会被真的算一遍并落盘)。
        if (useTools && nativeAcc.isNotEmpty()) {
            if (!seenDone && !sawFinishReason) {
                android.util.Log.w("Nyral", "流未完整结束(DONE/finish_reason 均缺, eofAborted=$eofAborted)，丢弃原生工具调用 ${nativeAcc.size()} 个 name=${nativeAcc.firstNonBlankName()} argsLen=${nativeAcc.totalArgBytes()}")
                nativeAcc.clear()
            } else {
                for (call in nativeAcc.drain()) {
                    toolCalls.add(call.name to call.args)
                    toolCallIds.add(call.id)
                }
            }
        }
        android.util.Log.v("Nyral", "streamOnce done accLen=${accumulated.length} toolCalls=$toolCalls")
        // 思考段自然结束
        if (toolCalls.isEmpty() && mode == MODE_THINKING) cb.onThinkingEnd()
        // token 统计
        val promptTokens = if (usedPrompt > 0) usedPrompt else promptEst.toLong()
        val completionTokens = if (usedCompletion > 0) usedCompletion else accumulated.length / 3L
        TokenStore.record(context, promptTokens.toInt().coerceAtLeast(0), completionTokens.toInt().coerceAtLeast(0))
        } catch (e: IOException) {
            // 连接阶段失败(网络不可达/DNS/连接超时): 与读取阶段中断同等对待, 转可重试
            android.util.Log.w("Nyral", "SSE connect failed: ${e.message}")
            throw RetryableException("模型连接失败(网络不可达): ${e.message}")
        } finally {
            try { reader?.close() } catch (_: Throwable) {}
            try { conn.disconnect() } catch (_: Throwable) {}
            activeConn = null
            activeConns.clear()
        }
        return StreamResult(accumulated.toString(), toolCalls, restartOut.firstOrNull(), toolCallIds, dsmlRaw,
            interrupted = interruptedOut)
    }

    /** 判断 4xx 响应是否疑似"不支持 tools" */
    private fun looksLikeToolsUnsupported(err: String): Boolean {
        val e = err.lowercase()
        // 回填构造缺陷(如重复 tool_call_id)是请求组装 bug 而非 provider 能力问题, 降级重试也无法恢复,
        // 且会切换文本协议放大 DSML 泄漏, 必须排除(2026-09-18 实测默认厂商该报错含 "invalid"+400 曾被误判)
        if (e.contains("tool_call_id")) return false
        return e.contains("tool") && (e.contains("not support") || e.contains("unsupported") ||
            e.contains("does not support") || e.contains("unknown parameter") || e.contains("extra parameter") ||
            e.contains("invalid") || e.contains("unknown field") || e.contains("additional properties") ||
            e.contains("400"))
    }

    /** 原生 tool_call 增量累积器 */

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

    /** 解析 XML 工具调用: 兼容四形态
     *  A) <tool_call><tool_name>x</tool_name><param>...</param></tool_call> (标准/单行/跨行/属性)
     *  B) <tool_call>x<arg_key>k</arg_key><arg_value>v</arg_value>...</tool_call> (deepseek-flash 平铺泄漏变体, 2026-11-01)
     *  C) <tool_call><tool_name>x</tool_name><arg_key>k</arg_key><arg_value>v</arg_value></tool_call> (混合)
     *  D) <工具名>{"arg":"val"}</工具名> (JSON 包裹泄漏, 如 <memory_search>{"query":".."}</memory_search>; 过滤元标签)
     */
    private fun parseXmlToolCall(xml: String): Pair<String, String>? {
        val name = Regex("<tool_name>\\s*([^<]+?)\\s*</tool_name>").find(xml)?.groupValues?.get(1)?.trim()
        if (name != null) {
            val arg = Regex("<param>([\\s\\S]*?)</param>").find(xml)?.groupValues?.get(1)?.trim()
            if (arg != null) return normalizeToolName(name) to arg
            // 标准名 + arg_key 参数(混合形态 C)
            val parsed = parseArgKeyArgs(xml)
            if (parsed != null) return normalizeToolName(name) to parsed
            return normalizeToolName(name) to ""
        }
        // 变体 B: 工具名裸写在 <tool_call> 后(可能带 <tool_name> 包裹)
        val naked = Regex("<tool_call>\\s*(?:<tool_name>\\s*)?([^<]+?)(?:\\s*</tool_name>)?\\s*(?=<arg_key|</tool_call>)").find(xml)?.groupValues?.get(1)?.trim()
        if (naked != null) {
            val nm = normalizeToolName(naked)
            if (nm.isEmpty()) return null
            val parsed = parseArgKeyArgs(xml)
            return nm to (parsed ?: "")
        }
        // 变体 E: <function_call name="工具名">参数或裸名</function_call>(OpenAI/Anthropic 风格壳, 2026-11-09)
        Regex("<(?:function_call|tool_use|invoke)\\s+name=\"([^\"]+)\"\\s*>([\\s\\S]*?)</(?:function_call|tool_use|invoke)>")
            .find(xml)?.let { m ->
                val nm = normalizeToolName(m.groupValues[1])
                val body = m.groupValues[2].trim()
                if (nm.isNotEmpty()) {
                    val arg = when {
                        body.isEmpty() -> ""
                        body.startsWith("{") -> body
                        else -> JSONObject().put("name", body).toString()
                    }
                    return nm to arg
                }
            }
        // 变体 F: 元标签内是裸工具名或 JSON(<tool_detail>timer_task_create</tool_detail>)
        // 2026-11-09: 不再静默丢弃——归一化为真实的 tool_detail 调用, 让它真拿到规格并激活,
        // 否则模型会一直重复写这个空壳(实测三轮逐级退化)
        Regex("<tool_detail>\\s*([^<]+?)\\s*</tool_detail>").find(xml)?.let { m ->
            val body = m.groupValues[1].trim()
            if (body.isNotEmpty()) {
                val arg = if (body.startsWith("{")) body else JSONObject().put("name", body).toString()
                return TOOL_DETAIL to arg
            }
        }
        // 变体 D: <工具名>{"arg":"val"}</工具名> 逐标签扫描, 跳过元标签, 取第一个有效工具
        val metaTags = setOf("tool_call", "tool_name", "tool_detail", "tool_param", "param", "arg_key", "arg_value", "calls")
        val jsonTags = Regex("<([a-z][a-z0-9_]*)>\\s*(\\{[\\s\\S]*?\\})\\s*</\\1>").findAll(xml).toList()
        for (m in jsonTags) {
            val tag = m.groupValues[1].trim()
            if (tag in metaTags) continue
            val nm = normalizeToolName(tag)
            if (nm.isEmpty()) continue
            return nm to m.groupValues[2].trim()
        }
        return null
    }

    /** 正文里合法出现的标签白名单(Markdown 渲染用), 不在此列表的标签一律视为泄漏嫌疑 */
    private val xmlBodyTags = setOf(
        "br", "b", "i", "u", "s", "em", "strong", "code", "pre", "a", "img",
        "p", "ul", "ol", "li", "hr", "blockquote", "span", "div", "del", "sup", "sub",
        "small", "font", "details", "summary", "table", "thead", "tbody", "tr", "td", "th",
        "h1", "h2", "h3", "h4", "h5", "h6"
    )

    /**
     * 出口兜底净化(2026-11-09): 入口拦截按模式有多条路径, 实测仍有单行漏网
     * (如孤立 </tool_call> 绕过缓冲直接进 accumulated, 落库后污染下一轮上下文,
     * 导致模型自我模仿逐级退化)。落库前对最终正文按同一白名单逐行剔除, 幂等无害。
     */
    private fun scrubXmlLeak(s: String): String {
        if (s.isEmpty() || !s.contains('<')) return s
        val sb = StringBuilder()
        var inFence = false
        for (line in s.split("\n")) {
            val f = line.trimStart()
            if (f.startsWith("```") && Regex("```").findAll(f).count() == 1) inFence = !inFence
            if (!inFence && isXmlLeakRow(line)) {
                android.util.Log.w("Nyral", "落库前剔除泄漏行: " + line.trim().take(80))
                continue
            }
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 行是否疑似 XML 泄漏(工具调用以泄漏文本进入 content 通道)。
     * 2026-11-09: 由「黑名单枚举」改为「白名单制」——deepseek-flash 的泄漏形态每轮都在换
     * (实测新增 </tool_call> 孤立闭合、<function_call name=..> 带属性壳、<tool_detail>裸工具名),
     * 枚举式判定注定持续漏判, 故只放行已知正文标签, 其余一律进泄漏缓冲。
     */
    private fun isXmlLeakRow(row: String): Boolean {
        val r = row.trimStart()
        if (!r.startsWith("<")) return false
        // Markdown 自动链接(如 <https://x.com>)不是标签, 直接放行
        if (Regex("^</?(?:https?|ftp|mailto):").containsMatchIn(r)) return false
        // 1) 已知调用壳前缀(含 OpenAI/Anthropic 风格变体与孤立闭合标签)
        val shells = arrayOf(
            "<tool_call", "<tool_name", "<tool_calls", "<tool_use", "<tool_result",
            "<function_call", "<function_calls", "<invoke", "<antml",
            "</tool_call", "</tool_name", "</tool_use", "</function_call", "</invoke",
            "<tool_detail", "</tool_detail", "<memory_search"
        )
        for (p in shells) if (r.startsWith(p)) return true
        // 2) 取首个标签名(支持属性/自闭合), 不在正文白名单即视为泄漏嫌疑
        val tag = Regex("^</?([a-z][a-z0-9_]*)(?:\\s+[a-z_]+=\"[^\"]*\")*\\s*/?>").find(r)
            ?.groupValues?.get(1) ?: return false
        return tag !in xmlBodyTags
    }

    /** 缓冲中所有开标签均有对应闭合标签(用于判定泄漏块完整); 支持带属性标签 */
    private fun xmlTagsClosed(s: String): Boolean {
        val open = Regex("<([a-z][a-z0-9_]*)(?:\\s+[a-z_]+=\"[^\"]*\")*\\s*/?>").findAll(s)
            .map { it.groupValues[1] }.filter { it !in setOf("br", "hr", "img") }.toList()
        if (open.isEmpty()) return false
        return open.all { tag -> Regex("</$tag>").containsMatchIn(s) }
    }

    /** 解析 <arg_key>k</arg_key><arg_value>v</arg_value> 成对参数为 JSON 字符串; 无配对返回 null */
    private fun parseArgKeyArgs(xml: String): String? {
        val keys = Regex("<arg_key>([\\s\\S]*?)</arg_key>").findAll(xml).map { it.groupValues[1].trim() }.toList()
        val vals = Regex("<arg_value>([\\s\\S]*?)</arg_value>").findAll(xml).map { it.groupValues[1].trim() }.toList()
        if (keys.isEmpty()) return null
        val args = JSONObject()
        val n = minOf(keys.size, vals.size)
        for (i in 0 until n) {
            val k = keys[i]
            if (k.isEmpty()) continue
            val v = vals[i]
            // 参数值智能定标: 合法 JSON 字面量(对象/数组/数字/布尔)按原类型入参, 其余按字符串
            args.put(k, try {
                when (val parsed = org.json.JSONTokener(v).nextValue()) {
                    is JSONObject, is JSONArray, is Int, is Long, is Double, is Boolean -> parsed
                    else -> v
                }
            } catch (e: Exception) { v })
        }
        return args.toString()
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

    private fun buildRouteMessages(context: Context, history: String, attachments: List<Attachment> = emptyList(), memInject: String? = null, patHint: String? = null): JSONArray {
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
            "1. 思考过程必须逐行以『思考:』开头输出, 严禁把思考文本直接输出到正文(未标记『思考:』的内容会原样展示给用户, 思考泄漏=严重错误); 若不想思考可省略, 直接输出正文;\n" +
            "2. 需要调用工具时, 使用系统提供的原生函数调用(function calling), 结构化的工具名与参数;\n" +
            "3. 思考结束要回答时, 直接输出正文(可用『答案:』开头), 不要写『TOOL:』。\n" +
            "收到工具结果后可继续思考或直接回答。一次(轮)可并行发起多个工具。"
        }
        val personaName = PersonaConfig.aiName()
        val persona = PersonaConfig.aiPersona()
        UserConfig.init(context)
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
        // 用户档案块: 对话对象基本信息, 仅作背景参考; 两项都空则不注入
        val uname = UserConfig.userName()
        val uhobby = UserConfig.userHobby()
        val userBlock = if (uname.isBlank() && uhobby.isBlank()) "" else buildString {
            append("\n【用户档案】你的对话对象的基本信息(仅作背景参考, 平时无需复述):\n")
            if (uname.isNotBlank()) append("- 名字: $uname\n")
            if (uhobby.isNotBlank()) append("- 爱好: $uhobby\n")
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
        // 视觉/视频多帧分析规则(2026-10-20): 根治"抽多帧仍按首帧概括全程"的模型端偷懒
        val visionBlock = "\n【视频/多帧画面分析规则(重要)】当收到 video_frame 抽出的多帧画面(帧图带 @秒数 标签)或需要描述视频内容时:\n" +
            "1. 必须逐帧分别描述: 按时间顺序逐帧列出一句话(如 帧1@0s:..., 帧2@4s:...), 明确指出各帧内容与光线/色彩差异;\n" +
            "2. 禁止用第一帧或开头画面代表整个视频; 下“全程/整段/整体如何”结论前, 必须先确认你实际看到的所有时间点帧画面确实一致;\n" +
            "3. 若各帧差异明显(明暗、饱和度、内容变化等), 必须如实指出前后反差, 严禁说“全程一样”;\n" +
            "4. 视频整体判断必须基于全部已注入帧, 而不是只看其中一帧。\n"
        messages.put(JSONObject().put("role", "system").put("content",
            personaBlock + userBlock +
            "根据用户需求选择工具。工具清单(名称+用途):\n" +
            "搜索策略(重要·三级)：1) 一般搜索默认先用 web(action=search) 后台静默快查(不打断用户界面)，拿到标题+摘要直接汇报，用户没要求看页面就不要开浏览器展示页；2) 仅当用户明确要\"看页面/看结果页/进某站\"，或 web(action=search) 无有效结果、需要登录态、卡验证码/登录墙时，才升级调用 browser(action=open) 打开全屏浏览器页(用户可见)；3) 浏览器页内遇到验证码/登录墙：不要硬点，停下提示用户点底部\"接管\"按钮手动完成(输验证码/登录)，用户再点\"交还 AI\"后你可继续 browser 的 scan/click/type 等操作；用户登录成功后调用 browser(action=save_cookies) 把该站点登录 Cookie 存入 site_auth.json，此后 web(action=fetch/download) 静默抓取自动带登录态，无需再开浏览器。\n" +
            hotToolIndex(context).joinToString("\n") { (n, d) -> "- $n: $d" } +
            "注: 回调列表仅常驻常用工具; 想用索引中未列出的工具时, 先调 tool_detail(name) 获取规格并激活, 激活后即可直接调用。\n" +
            buildMcpIndex() +
            "\n可用SSH连接:$sshHint\n" +
            "调用方式: 使用系统提供的 function calling 原生工具调用(工具名与 JSON 参数已由系统给出 schema), 一次(轮)可并行发起多个工具; 不要自己发明不存在的工具名。" +
            "记忆使用: 若所需信息可能来自与此用户过去的对话且当前上下文未提及, 应调用 memory_search 查证后再回答; 工具调用过程中拿不准时也先查记忆再作答。\n" +
            (if (memInject.isNullOrBlank()) "" else memInject + "\n") +
            (if (patHint.isNullOrBlank()) "" else patHint + "\n") + fmtRules + mdBan + emojiBlock + visionBlock))
        if (attachments.isEmpty()) {
            messages.put(JSONObject().put("role", "user").put("content", history))
        } else {
            // 多模态: content 为 parts 数组, 文本在前, 附件按类型映射为标准 OpenAI 兼容格式
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", history))
            for (a in attachments) {
                when {
                    a.mime.startsWith("image/") -> {
                        if (a.stored) {
                            // 图片统一落私有附件库(2026-10-18): 不发 base64, 只放索引卡, AI 按需 image_view 注入
                            val meta = try { AttachmentStore.metaOf(context, a.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
                            val sizeB = meta?.optLong("size", 0L) ?: 0L
                            val sizeStr = if (sizeB >= 1024 * 1024) String.format("%.1fMB", sizeB / 1024.0 / 1024.0)
                                else if (sizeB >= 1024) String.format("%.1fKB", sizeB / 1024.0)
                                else "${sizeB}B"
                            val disp = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: a.name
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[图片附件 $disp | ${a.mime} | $sizeStr | 已落私有附件库 att://${a.name} | 图片字节未发送, 需看图片时调 image_view(name=\"att://${a.name}\") 注入查看]"))
                        } else {
                            parts.put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.base64}")))
                        }
                    }
                    a.mime.startsWith("audio/") -> {
                        if (a.stored) {
                            // 音频统一落私有附件库(2026-10-18): 不发 base64, 只放索引卡, AI 按需 audio_read(支持音频输入的模型) / file_export
                            val meta = try { AttachmentStore.metaOf(context, a.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
                            val durS = meta?.optLong("durationSec", 0L) ?: 0L
                            val sizeB = meta?.optLong("size", 0L) ?: 0L
                            val sizeStr = if (sizeB >= 1024 * 1024) String.format("%.1fMB", sizeB / 1024.0 / 1024.0)
                                else if (sizeB >= 1024) String.format("%.1fKB", sizeB / 1024.0)
                                else "${sizeB}B"
                            val disp = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: a.name
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[音频附件 $disp | ${a.mime} | $sizeStr | 时长约${durS}s | 已落私有附件库 att://${a.name} | 音频字节未发送: 支持音频输入的模型可调 audio_read(name=\"att://${a.name}\") 注入听取; 否则调 file_export(name=\"att://${a.name}\") 导出到公共工作目录]"))
                        } else {
                            val audioOff = audioDirectDisabledKey == ApiConfig.providerId() + "|" + ApiConfig.model()
                            if (audioOff) {
                                // 音频直发被服务端拒过(本会话): 改走索引卡, 由 AI 按需 audio_read 注入
                                parts.put(JSONObject().put("type", "text")
                                    .put("text", "[音频附件 ${a.name} | 已落私有附件库 att://${a.name} | 音频直发被服务端拒绝, 支持音频输入的模型可调 audio_read(name=\"att://${a.name}\") 注入听取]"))
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
                    }
                    a.mime.startsWith("video/") -> {
                        if (a.stored) {
                            // 视频统一落私有附件库(2026-10-18): 不发 base64, 只放索引卡, AI 按需 video_frame 抽帧 / file_export 导出
                            val meta = try { AttachmentStore.metaOf(context, a.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
                            val durS = meta?.optLong("durationSec", 0L) ?: 0L
                            val sizeB = meta?.optLong("size", 0L) ?: 0L
                            val sizeStr = if (sizeB >= 1024 * 1024) String.format("%.1fMB", sizeB / 1024.0 / 1024.0)
                                else if (sizeB >= 1024) String.format("%.1fKB", sizeB / 1024.0)
                                else "${sizeB}B"
                            val disp = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: a.name
                            parts.put(JSONObject().put("type", "text")
                                .put("text", "[视频附件 $disp | ${a.mime} | $sizeStr | 时长约${durS}s | 已落私有附件库 att://${a.name} | 视频字节未发送, 需看画面时调 video_frame(name=\"att://${a.name}\", timeMs) 抽帧, 或调 file_export(name=\"att://${a.name}\") 导出到公共工作目录]"))
                        } else if (a.isEmoji) {
                            // 表情动图(2026-10-26): 给用户看的装饰, 模型无需消费, 不直发 video_url 省 token/带宽
                            parts.put(JSONObject().put("type", "text").put("text", "[用户发送了一个表情动画]"))
                        } else {
                            // 直发视频(2026-10-26): 支持视频输入的模型内联 video_url, 服务端自行抽帧理解;
                            // 先放文字索引, 万一服务端拒绝直发(400)降级后这行仍在, 可指引 video_frame 抽帧
                            val canVideo = ApiConfig.modelHasCap(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.CAP_VIDEO)
                            val directOff = videoDirectDisabledKey == ApiConfig.providerId() + "|" + ApiConfig.model()
                            if (!canVideo || directOff) {
                                parts.put(JSONObject().put("type", "text")
                                    .put("text", "[视频附件 ${a.name} | 已落私有附件库 att://${a.name} | 视频字节未直发, 需看画面调 video_frame(name=\"att://${a.name}\", times=[毫秒,毫秒]) 抽帧]"))
                            } else {
                                // 索引卡与直发并存: 首轮模型直接看视频(官方服务端采样), 措辞禁止多余抽帧调用;
                                // 后续工具轮次 stripVideoParts 剥离 video_url 并改写本卡为抽帧指引(DIRECT_VIDEO_MARK 标记)
                                parts.put(JSONObject().put("type", "text")
                                    .put("text", "[视频附件 ${a.name} | 已落私有附件库 att://${a.name} | $DIRECT_VIDEO_MARK, 描述视频直接基于你看到的画面即可, 禁止调用 video_frame 抽帧(仅当用户明确要求特定时间点特写画面时才调 video_frame(name=\"att://${a.name}\", times=[毫秒]))]"))
                                // MiMo 视频理解: content 数组 type=video_url, url 用 data:{mime};base64 内联
                                // 官方限制: base64 后 ≤50MB(原始约 ≤37MB), 支持 MP4/MOV/AVI/WMV
                                val vurl = JSONObject().put("url", "data:${a.mime};base64,${a.base64}")
                                parts.put(JSONObject().put("type", "video_url")
                                    .put("video_url", vurl)
                                    .put("fps", 2)
                                    .put("media_resolution", "default"))
                            }
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

    private data class StreamResult(
        val accumulated: String,
        val toolCalls: List<Pair<String, String>>,
        val restartWith: String? = null,
        /** 与 toolCalls 按序一一对应的 tool_call_id(并行同名工具各自独立) */
        val toolCallIds: List<String> = emptyList(),
        /** 非 null 表示本轮工具调用来自 DSML 泄漏拦截(content 通道文本解析), 回填需走文本链 */
        val dsmlRaw: String? = null,
        /** 流被中断(连接断开/EOF)但已有部分输出: 主循环据此续跑重连 */
        val interrupted: Boolean = false
    ) {
        fun idAt(idx: Int): String? = toolCallIds.getOrNull(idx)
    }

    /** zone: 能力域标注(2026-10-30 P3), -1 表示未标注 → 由 CapabilityScope 白名单推导;
     *  READONLY 会读不会改, WRITE 常规执行, PRIVILEGED 特权(永不下发给模型) */
    data class ToolSpec(val name: String, val desc: String, val params: String, val zone: Int = -1)

    private const val MODE_NONE = 0
    private const val MODE_THINKING = 1
    private const val MODE_CONTENT = 2

    /** 调试服务/状态查询: 暴露工具清单(名称+描述+参数说明, 含 MCP 动态工具) */
    fun toolList(): List<ToolSpec> =
        toolRegistry + McpClientManager.specEntries().map { (n, d, p) -> ToolSpec(n, d, p) }

    // ===== 执行域兼容转发(2026-10-04 拆分): 逻辑已迁 ToolExecutor, 保留门面供单测/外部反射 =====
    /** 参数归一: 转发 ToolExecutor(calc/memory_search/ssh_run 抽字段为裸字符串) */
    fun normalizeArgs(name: String, argRaw: String): String = ToolExecutor.normalizeArgs(name, argRaw)

    /** 旧工具名映射表: 转发 ToolExecutor(带 backing field, 供反射 field 读取) */
    internal val LEGACY_TOOL_ACTION: Map<String, Pair<String, String>> = ToolExecutor.LEGACY_TOOL_ACTION
}
