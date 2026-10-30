package io.github.aixtin.nyral

/**
 * 能力域隔离(2026-10-30 P3)
 *
 * 三态:
 *  - READONLY   只查不改: 搜索/抓取/计算/读取附件/记忆检索/提问。不改系统状态、不落盘、不跑命令。
 *  - WRITE      常规执行域: 文件读写、浏览器交互、应用操作、定时任务等(默认)。
 *  - PRIVILEGED 特权域: 手机体检采集、安全门禁改写。只允许体检页内部与外部 MCP 客户端调用,
 *               **App 内模型在任何域下都不可见**, 模型没有提权通道(见 ToolExecutor 的 MODEL_FORBIDDEN_SECURITY_KEYS)。
 *
 * 域状态不落盘、不进记忆、不随会话持久化: 进程重启回默认; 由会话类型推导(体检会话 = READONLY)。
 * 降级自动(进体检会话即只读), 升级由用户离开该会话自然完成, 模型无法自行把域抬高。
 */
object CapabilityScope {
    const val ZONE_READONLY = 0
    const val ZONE_WRITE = 1
    const val ZONE_PRIVILEGED = 2

    /** 只读工具白名单: 纯查询, 无副作用。未列出的工具一律按 WRITE 处理(保守默认) */
    private val READONLY_TOOLS = setOf(
        "web_search", "web_fetch", "get_time", "calc", "memory_search",
        "image_view", "video_frame", "audio_read", "attach_read", "pdf_page",
        "tool_detail", "ask_user"
    )

    /** 特权工具: 永不下发给 App 内模型(体检采集会跑 shell, security_set 能自开门禁) */
    private val PRIVILEGED_TOOLS = setOf(
        "phone_diag_list", "phone_diag_run", "phone_diag_setup", "security_set"
    )

    /** 当前会话类型(仅内存): 0 普通 / 1 定时任务 / 2 体检 */
    @Volatile private var sessionKind = MemoryDb.KIND_NORMAL

    /** 工具所属域; declared >= 0 时以 ToolSpec 显式标注优先 */
    fun zoneOf(tool: String, declared: Int = -1): Int {
        if (declared >= 0) return declared
        if (tool in PRIVILEGED_TOOLS) return ZONE_PRIVILEGED
        if (tool in READONLY_TOOLS) return ZONE_READONLY
        return ZONE_WRITE
    }

    /** 当前域: 体检会话降为只读, 其余会话为常规执行域 */
    fun currentZone(): Int =
        if (sessionKind == MemoryDb.KIND_DIAG) ZONE_READONLY else ZONE_WRITE

    fun isReadOnly(): Boolean = currentZone() == ZONE_READONLY

    /** 切会话时同步域(2026-10-30): 打开会话/新建会话处调用 */
    fun setSessionKind(kind: Int) { sessionKind = kind }

    /** 模型可见性: 特权域任何情况都不下发; 其余按当前域收敛 */
    fun allows(tool: String, declared: Int = -1): Boolean {
        val z = zoneOf(tool, declared)
        if (z == ZONE_PRIVILEGED) return false
        return z <= currentZone()
    }
}
