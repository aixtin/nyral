#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Nyral 危险工具门禁 v3 改造 patch(2026-10-04) - 重写版, 统一 ''' 包裹"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

BASE = "/home/ymz/Nyral/android-agent-app/app/src/main/java"

def patch(path, old, new, must=True):
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    if old not in src:
        if must:
            print(f"[FAIL] anchor not found in {path}: {old[:60]!r}")
            sys.exit(1)
        else:
            print(f"[SKIP] anchor missing (optional): {path}: {old[:60]!r}")
            return
    src = src.replace(old, new, 1)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print(f"[OK] patched {path}")

# ============ 1. ChatAdapter.kt: 加 SecurityConfirm 行类型 ============
ca = BASE + "/all/ChatAdapter.kt"
patch(ca,
'''    class Streaming(val rowId: Long, val holder: AiBubbleHolder) : ChatRow(rowId) {
        var bubbleBox: View? = null
    }
}''',
'''    class Streaming(val rowId: Long, val holder: AiBubbleHolder) : ChatRow(rowId) {
        var bubbleBox: View? = null
    }
    /** 危险工具安全确认行(2026-10-04): 待确认申请的气泡行; 决策后留态(已允许/已拒绝/超时拒绝) */
    data class SecurityConfirm(
        val rowId: Long,
        val requestId: String,
        val tool: String,
        val arg: String,
        val status: String,   // PENDING / ALLOWED / REJECTED / TIMEOUT
        val risk: String = "HIGH"
    ) : ChatRow(rowId)
}''')

# ============ 2. LocalEngine.kt ============
le = BASE + "/all/LocalEngine.kt"

# 2a. security_set ToolSpec 描述
patch(le,
'''        ToolSpec("security_set", "安全管理开关(2026-10-03): 配置危险操作确认与root自动补权。参数JSON: {\\"danger_confirm\\":true/false} 开启/关闭危险工具确认门禁(默认开); {\\"root_auto_grant\\":true/false} 开启/关闭root静默自动补权(默认关); {\\"ssh_trust\\":\\"连接名\\"} 信任待确认的SSH主机密钥(SSH安全告警后调用)", "JSON: {\\"danger_confirm\\":false} 或 {\\"root_auto_grant\\":true} 或 {\\"ssh_trust\\":\\"vps\\"}")''',
'''        ToolSpec("security_set", "安全管理开关(2026-10-04): 配置危险门禁三档与root自动补权。参数JSON: {\\"danger_mode\\":\\"strict|auto|off\\"} 三档门禁(默认auto): strict=每次确认, auto=高危确认+5分钟窗口期复用, off=关闭门禁; {\\"danger_confirm\\":true/false} 兼容旧开关(true→auto, false→off); {\\"root_auto_grant\\":true/false} 开启/关闭root静默自动补权(默认关); {\\"ssh_trust\\":\\"连接名\\"} 信任待确认的SSH主机密钥(SSH安全告警后调用)", "JSON: {\\"danger_mode\\":\\"strict\\"} 或 {\\"danger_confirm\\":false} 或 {\\"root_auto_grant\\":true} 或 {\\"ssh_trust\\":\\"vps\\"}")''')

# 2b. security_set 提示
patch(le,
'''        "security_set" to "安全管理开关: 危险操作确认门禁/root自动补权/SSH主机密钥信任"''',
'''        "security_set" to "安全管理开关: 门禁三档(strict/auto/off)/root自动补权/SSH主机密钥信任"''')

# 2c. 内置工具门禁段 -> 三档
patch(le,
'''        // H3 硬门禁 v2(2026-10-03): 危险工具确认开关(默认开) + 阻塞式用户决策
        // 命中危险工具时本线程在此挂起: 弹系统确认框等待用户决策——
        // 用户允许 → 签发票据并继续执行本调用; 用户拒绝 → 立即返回拒绝, 不执行工具。
        // 票据为窗口期复用(5分钟, 绑定工具+参数): 窗口期内同参数再次调用直接放行, 不重复弹窗。
        if (SecurityConfig.dangerConfirm(context)) {
            val jo0 = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
            val act0 = jo0?.optString("action", "").orEmpty()
            val key0 = if (act0.isNotEmpty()) "$n:$act0" else n
            if (key0 in DANGER_CONFIRM_TOOLS || n in DANGER_CONFIRM_TOOLS) {
                if (SecurityConfig.hasTicket(context, n, arg)) {
                    // 窗口期内同参数直接放行, 不重复弹窗
                } else {
                    // 阻塞式确认: 弹窗期间本线程挂起, 用户决策后才继续
                    when (SecurityUi.requestConfirm(context, n, arg)) {
                        true -> { /* 允许: 票据已在弹窗回调中签发, 继续执行本调用 */ }
                        false -> return "【安全确认】用户拒绝了工具 [$n] 的执行请求，本次调用已停止。如需执行，请用户重新发起。"
                        null -> return "【安全确认】当前无前台界面可弹出确认框，请在前台打开 App 后重试本调用。"
                    }
                }
            }
        }''',
'''        // 硬门禁 v3(2026-10-04): 三档(strict/auto/off) + 双通道(气泡/通知) + 并发队列
        // 命中危险工具时本线程在此挂起: 等待用户决策——
        // 用户允许 → 签发票据并继续执行本调用; 用户拒绝 → 立即返回拒绝, 不执行工具;
        // 超时(2分钟) → 自动拒绝。strict 档每次确认(无窗口期), auto 档窗口期票据复用(5分钟)。
        if (SecurityConfig.needsConfirm(context, n, arg)) {
            val jo0 = try { JSONObject(arg.trim()) } catch (e: Exception) { null }
            val act0 = jo0?.optString("action", "").orEmpty()
            val key0 = if (act0.isNotEmpty()) "$n:$act0" else n
            if (key0 in DANGER_CONFIRM_TOOLS || n in DANGER_CONFIRM_TOOLS) {
                val risk = SecurityConfig.riskOf(n)
                when (SecurityUi.requestConfirm(context, n, arg, risk)) {
                    true -> { /* 允许: 票据已在决策回调中签发, 继续执行本调用 */ }
                    false -> return "【安全确认】用户拒绝了工具 [$n] 的执行请求，本次调用已停止。如需执行，请用户重新发起。"
                    null -> return "【安全确认】确认通道不可用(应用不在前台且通知被禁用)，请在打开 App 后重试本调用。"
                }
            }
        }''')

# 2d. MCP 门禁段 -> 三档
patch(le,
'''                if (McpClientManager.spec(name) != null) {
                    // 安全审查修复(2026-10-03): MCP 工具纳入 H3 硬门禁, 与内置危险工具同款确认+票据机制
                    if (SecurityConfig.dangerConfirm(context)) {
                        val mcpKey = "mcp:$name"
                        if (SecurityConfig.hasTicket(context, mcpKey, arg)) {
                            McpClientManager.callTool(context, name, arg)
                        } else when (SecurityUi.requestConfirm(context, mcpKey, arg)) {
                            true -> McpClientManager.callTool(context, name, arg)
                            false -> "【安全确认】用户拒绝了工具 [$name] 的执行请求，本次调用已停止。如需执行，请用户重新发起。"
                            null -> "【安全确认】当前无前台界面可弹出确认框，请在前台打开 App 后重试本调用。"
                        }
                    } else {
                        McpClientManager.callTool(context, name, arg)
                    }
                } else "未知工具: $name"''',
'''                if (McpClientManager.spec(name) != null) {
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
                } else "未知工具: $name"''')
print("[OK] LocalEngine patched")

# ============ 3. MainActivity.kt ============
ma = BASE + "/io/github/aixtin/nyral/MainActivity.kt"

# 3a. 变量声明区加 securityDim(chatArea 声明后)
patch(ma,
'''    private lateinit var chatArea: FrameLayout''',
'''    private lateinit var chatArea: FrameLayout
    /** 安全确认微暗遮罩(2026-10-04): 有待确认申请时聊天区背景暗化提示 */
    private lateinit var securityDim: View''')

# 3b. titleBar 加审计入口(∑ 图标后)
patch(ma,
'''        titleBar.addView(TextView(this).apply {
            text = "∑"
            textSize = 22f
            setTextColor(Ui.TEXT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { toggleTokenPanel() }
            Ui.press(this)
        })
        main.addView(titleBar)''',
'''        titleBar.addView(TextView(this).apply {
            text = "∑"
            textSize = 22f
            setTextColor(Ui.TEXT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { toggleTokenPanel() }
            Ui.press(this)
        })
        // 审计入口(2026-10-04): 顶栏右侧图标 → 安全审计历史页
        titleBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_log)
            setColorFilter(Ui.TEXT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { startActivity(Intent(this@MainActivity, AuditActivity::class.java)) }
            Ui.press(this)
        })
        main.addView(titleBar)''')

# 3c. chatArea 加遮罩(chatRec addView 之后)
patch(ma,
'''        chatArea = FrameLayout(this)
        chatArea.addView(chatRec, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))''',
'''        chatArea = FrameLayout(this)
        chatArea.addView(chatRec, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 安全确认微暗遮罩: 盖在消息区上(低于输入框), 有待确认申请时可见
        securityDim = View(this).apply {
            setBackgroundColor(Color.argb(70, 15, 15, 20))
            visibility = View.GONE
        }
        chatArea.addView(securityDim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))''')

# 3d. SecurityUi.register 后注册队列回调
patch(ma,
'''        SecurityUi.register(this)''',
'''        SecurityUi.register(this)
        // 安全确认队列回调(2026-10-04): 气泡行同步 + 聊天区背景微暗
        SecurityUi.onQueueChanged = { list ->
            chatRec?.post { syncSecurityRows(list) }
        }''')

# 3e. poolType 加 SecurityConfirm
patch(ma,
'''        is ChatRow.Welcome -> ChatAdapter.PT_WELCOME
        is ChatRow.AiRich -> ChatAdapter.PT_AI_RICH
        else -> ChatAdapter.PT_NONE''',
'''        is ChatRow.Welcome -> ChatAdapter.PT_WELCOME
        is ChatRow.AiRich -> ChatAdapter.PT_AI_RICH
        is ChatRow.SecurityConfirm -> ChatAdapter.PT_NONE
        else -> ChatAdapter.PT_NONE''')

# 3f. buildRowView 加 SecurityConfirm 分支
patch(ma,
'''        is ChatRow.Sys -> TextView(this).apply {
            text = row.text
            textSize = 12f
            setTextColor(SYS_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }''',
'''        is ChatRow.Sys -> TextView(this).apply {
            text = row.text
            textSize = 12f
            setTextColor(SYS_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        is ChatRow.SecurityConfirm -> buildSecurityRow(row)''')

# 3g. 新增方法(buildRowView 定义之前插入)
patch(ma,
'''    internal fun buildRowView(row: ChatRow): View = when (row) {''',
'''    // ===== 安全确认气泡行(2026-10-04): 红色警示头 + 工具/参数摘要 + ✔/✘ 决策按钮 =====
    private fun buildSecurityRow(row: ChatRow.SecurityConfirm): View {
        val maxW = chatMaxW()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.rounded(0xFFFDF0F0, 14, this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        }
        // 头部: 红色三角警示 + 标题 + 状态徽标
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "▲"
            textSize = 13f
            setTextColor(0xFFD32F2F.toInt())
            setPadding(0, 0, dp(6), 0)
        })
        head.addView(TextView(this).apply {
            text = "安全确认"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(securityStatusBadge(row.status))
        box.addView(head)
        // 工具名
        box.addView(TextView(this).apply {
            text = "工具: ${row.tool}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1A1A1A.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        // 参数摘要
        val argText = row.arg.trim().ifBlank { "(无参数)" }
        box.addView(TextView(this).apply {
            text = if (argText.length > 120) argText.take(120) + "…" else argText
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            maxLines = 4
            setPadding(0, dp(2), 0, 0)
        })
        // 决策按钮(PENDING 才显示)
        if (row.status == "PENDING") {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
            }
            btnRow.addView(TextView(this).apply {
                text = "✘ 拒绝"
                textSize = 14f
                setTextColor(0xFFD32F2F.toInt())
                gravity = Gravity.CENTER
                background = Ui.rounded(0xFFFFE3E3, 10, this@MainActivity)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                Ui.press(this)
                setOnClickListener { SecurityUi.decide(this@MainActivity, row.requestId, false) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
            btnRow.addView(TextView(this).apply {
                text = "✔ 允许执行"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                background = Ui.rounded(0xFF2E8B57, 10, this@MainActivity)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                Ui.press(this)
                setOnClickListener { SecurityUi.decide(this@MainActivity, row.requestId, true) }
            })
            box.addView(btnRow)
        }
        return box
    }

    private fun securityStatusBadge(status: String): TextView = TextView(this).apply {
        val (txt, color, bg) = when (status) {
            "PENDING" -> Triple("待确认", 0xFFB26A00.toInt(), 0xFFFFF3D6.toInt())
            "ALLOWED" -> Triple("已允许", 0xFF2E8B57.toInt(), 0xFFE3F2E5.toInt())
            "REJECTED" -> Triple("已拒绝", 0xFFD32F2F.toInt(), 0xFFFFE3E3.toInt())
            "TIMEOUT" -> Triple("超时拒绝", 0xFFD32F2F.toInt(), 0xFFFFE3E3.toInt())
            else -> Triple(status, 0xFF666666.toInt(), 0xFFEEEEEE.toInt())
        }
        text = txt
        textSize = 11f
        setTextColor(color)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = Ui.rounded(bg, 8, this@MainActivity)
    }

    /** 安全确认队列同步(2026-10-04): 全量重建 SecurityConfirm 行(移除旧行, 按 FIFO 重建), 并控制微暗遮罩 */
    private fun syncSecurityRows(list: List<SecurityUi.PendingRequest>) {
        chatRows.removeAll { it is ChatRow.SecurityConfirm }
        list.forEach { req ->
            chatRows.add(ChatRow.SecurityConfirm(
                rowId = nextTempRowId(),
                requestId = req.requestId,
                tool = req.tool,
                arg = req.arg,
                status = req.status,
                risk = req.risk.name
            ))
        }
        if (chatRows.isNotEmpty()) chatAdapter.submit(chatRows.toList())
        if (::securityDim.isInitialized) {
            securityDim.visibility = if (list.any { it.status == "PENDING" }) View.VISIBLE else View.GONE
        }
        if (list.isNotEmpty()) scrollToBottom()
    }

    internal fun buildRowView(row: ChatRow): View = when (row) {''')

print("[OK] MainActivity patched")

# ============ 4. AndroidManifest.xml ============
am = "/home/ymz/Nyral/android-agent-app/app/src/main/AndroidManifest.xml"
patch(am,
'''        <receiver
            android:name=".TaskDismissReceiver"
            android:exported="false" />''',
'''        <receiver
            android:name=".TaskDismissReceiver"
            android:exported="false" />

        <!-- 安全确认通知通道接收器(2026-10-04): 处理后台通知的允许/拒绝 Action -->
        <receiver
            android:name="all.SecurityConfirmReceiver"
            android:exported="false" />

        <!-- 安全审计历史页(2026-10-04): 顶栏入口, 展示 filesDir/nyral_security_audit.log -->
        <activity
            android:name="all.AuditActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Material.Light.NoActionBar">
        </activity>''')

print("ALL PATCH DONE")
