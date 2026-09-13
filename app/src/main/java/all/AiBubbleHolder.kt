package io.github.aixtin.nyral

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** 正文位置标记: 正文首次出现时记录其真实插入位置, 恢复时按原位渲染(不再固定末尾) */
internal object ContentMarker

// AI 气泡配色: 提出为文件级常量, 与 MainActivity 共享(原为 MainActivity 私有成员)
internal val BUBBLE_AI = Color.parseColor("#F1F2F4")
internal val BUBBLE_AI_TEXT = Color.parseColor("#1A1A1A")
internal val THINK_TEXT = Color.parseColor("#8A8A8A")
internal val THINK_BG = Color.parseColor("#E7E8EA")

internal class AiBubbleHolder(private val host: MainActivity) : TypewriterTickable {
    private var bubbleBox: LinearLayout? = null
    private var statusView: TextView? = null
    // 思考块列表: 每段思考独立一行, 按真实顺序竖向排列成时间线
    private val thinkingBlocks = ArrayList<ThinkingBlock>()
    private var activeThinking: ThinkingBlock? = null
    // 工具块列表: 每个工具调用独立一行, 折叠点击展开
    private val toolBlocks = ArrayList<ToolBlock>()
    // 交错时间线: 思考块/工具块/正文标记按真实出现顺序追加, 供持久化恢复交替顺序(v8)
    private val timelineEvents = ArrayList<Any>()
    private var done = false
    private var loadingRow: View? = null   // 思考完成等待正文时的加载指示行(chatMode 下为 chatWrap 外层行)
    private var maxW = 0
    // 正文多段化: 思考/工具穿插时正文拆成多个独立气泡, 每段独立打字机(v9)
    // 打字机(游标+帧驱动): text 只累积不删除, shownLen 控制显示进度
    private inner class ContentBlock {
        val text = StringBuilder()
        var view: TextView? = null
        var done = false               // 该段已封段/整体完成: 不再追加, 剩余字符打完即收尾排版
        var shownLen = 0
        var lastFrameNs = 0L
        var typeActive = false
        var typeFinishedRender = false
        var lastAdvanceNs = 0L         // 上次实际推进字符的时间戳(防长时间冻结)
    }
    private val contentBlocks = ArrayList<ContentBlock>()
    private var activeContent: ContentBlock? = null   // 当前正在接收 delta 的正文段
    // 打字速率自适应: 跟随模型实际吐字节奏(字符/秒 EMA), 避免"急打急停"的顿挫卡顿感
    private var modelRate = 0.0          // 模型吐字速率(字符/秒) EMA
    private var lastDeltaNs = 0L         // 上次收到 delta 的时间戳
    private var charBudget = 0.0         // 浮点字符预算累积器(严格按速率推进, 消除"每帧+1"的强制快打)

    // 帧级节流: 高频 delta 合并到 16ms 一帧刷新一次, 避免全量 setText + 滚动积压导致卡顿/拖影
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshPending = false

    /** 一段思考: 折叠态只显示字数摘要, 点击展开该段全文 */
    private inner class ThinkingBlock {
        val text = StringBuilder()
        var view: TextView? = null
        var collapsed = false   // 该段思考是否已完成(折叠)
        var expanded = false    // 用户是否点击展开全文
        val count: Int get() = text.codePointCount(0, text.length)
        fun summary(): String = "💭 已思考${count}字，点按展开"
    }

    /** 一次工具调用: 折叠态只显示工具名, 点击展开参数与结果 */
    private inner class ToolBlock(val name: String, val arg: String) {
        var result: String? = null
        var view: TextView? = null
        var expanded = false
        fun collapsedText(): String = "🔧 工具：$name"
        fun expandedText(): String = buildString {
            append("🔧 工具：$name")
            if (arg.isNotBlank()) append("\n参数：$arg")
            result?.takeIf { it.isNotBlank() }?.let { append("\n结果：$it") }
        }
    }

    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        uiHandler.postDelayed({
            refreshPending = false
            flush()
        }, 16)
    }

    /** 一次性应用最新文本 + 滚动到底, 每帧最多一次; 正文渲染交给打字机(typeTick), 此处只管思考区, 避免双重 setMarkdown 互抢导致抽搐 */
    private fun flush() {
        val b = activeThinking ?: return
        val tv = b.view ?: return
        if (!b.collapsed) {
            tv.text = "思考中: " + tailThinking(b)
            host.scrollToBottom()
        }
    }

    /** 思考块内多段只显示最后一段, 避免 "思考中: 思考一思考二..." 眼花 */
    private fun tailThinking(b: ThinkingBlock): String {
        val s = b.text.toString()
        val idx = s.lastIndexOf("\n\n")
        return if (idx >= 0) s.substring(idx + 2).trim() else s
    }

    fun attach(parent: LinearLayout) {
        maxW = host.chatMaxW()
        // 独立气泡容器: 不再包裹大气泡背景, 思考/工具/正文各自成为独立气泡, 按真实顺序竖向排列
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
                topMargin = host.dp(6)
                // 每个子气泡自带背景, 宽度由 maxWidth(屏幕*0.78) 封顶
            }
        }
        // 头像逐条内嵌: 每条子气泡(思考/工具/正文)在聊天模式下由 chatWrap 各自并排头像, Agent 模式不带
        parent.addView(box)
        bubbleBox = box
        host.enterBubble(box)
    }

    /** 聊天模式下给气泡逐条并排 AI 头像(Agent 模式原样直插, 无头像) */
    private fun addChatBubble(view: View?): View? {
        if (view == null) return null
        return if (ModeConfig.chatMode()) {
            val w = host.chatWrap(view, false)
            bubbleBox?.addView(w)
            w
        } else {
            bubbleBox?.addView(view)
            view
        }
    }

    fun showStatus(s: String) {
        statusView = addLine(s, THINK_TEXT, italic = true)
    }

    /** 思考完成、正文未开始前显示加载指示, 避免误以为卡住 */
    private fun startLoading() {
        if (loadingRow != null || contentBlocks.isNotEmpty()) return
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(2), host.dp(8), host.dp(2), host.dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(ProgressBar(host, null, android.R.attr.progressBarStyleSmall).apply {
            val lp = LinearLayout.LayoutParams(host.dp(14), host.dp(14))
            lp.rightMargin = host.dp(6)
            layoutParams = lp
        })
        row.addView(TextView(host).apply {
            text = "生成中…"
            textSize = 12f
            setTextColor(THINK_TEXT)
        })
        loadingRow = addChatBubble(row)
        host.scrollToBottom()   // 加载行可能加在屏幕外, 必须滚到底才可见
        android.util.Log.i("agent", "startLoading blocks=" + contentBlocks.size)
    }

    private fun stopLoading() {
        loadingRow?.let { bubbleBox?.removeView(it) }
        loadingRow = null
    }

    /** 思考段开始: 若上一段已折叠则新建一块(多轮思考->工具->正文按真实顺序竖向排列), 否则沿用当前块 */
    fun showThinking(prefix: String) {
        sealCurrentContent()   // 先冻结当前正文段, 后续正文新起气泡(思考/工具与正文交错时正文拆段)
        removeStatus()
        stopLoading()
        var b = activeThinking
        if (b == null || b.collapsed) {
            // 新建思考块: 独立气泡(深色背景), 可点击折叠展开
            b = ThinkingBlock()
            thinkingBlocks.add(b)
            timelineEvents.add(b)
            activeThinking = b
            // 独立气泡: 各自 WRAP_CONTENT 自适应宽度(maxWidth=maxW 封顶), 互不影响
            val tv = TextView(host).apply {
                text = prefix
                textSize = 14f
                setTextColor(THINK_TEXT)
                setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                background = rounded(host.dp(10), THINK_BG)
                maxWidth = maxW
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = host.dp(6)
                    bottomMargin = host.dp(4)
                }
            }
            addChatBubble(tv)
            // 折叠行是点击展开/收起的交互控件, 不启用 textIsSelectable(否则首次点击被吞需点两次)
            b.view = tv
        } else {
            // 同段内续写: 追加分隔而非新建气泡, 避免多条堆叠
            b.text.append("\n\n")
        }
        scheduleRefresh()
    }

    fun appendThinking(text: String) {
        activeThinking?.let { it.text.append(text) }
        scheduleRefresh()
    }

    /** 思考完成 -> 收缩为一行字数摘要(不展示思考正文), 点击可展开/收起查看该段全部思考 */
    fun collapseThinking() {
        val b = activeThinking ?: return
        if (b.collapsed) return
        b.collapsed = true
        b.expanded = false
        b.view?.let { tv ->
            tv.text = b.summary()
            tv.setOnClickListener { toggleThinking(b) }
        }
        if (contentBlocks.isEmpty()) startLoading()   // 思考完但正文未开始: 提示生成中
    }

    private fun toggleThinking(b: ThinkingBlock) {
        b.expanded = !b.expanded
        val tv = b.view ?: return
        tv.text = if (b.expanded) "💭 " + b.text else b.summary()
        host.keepReadingPosition(tv)   // 展开/收起后保持阅读位置, 不跳回底部
    }

    /** 工具调用开始: 独立气泡(深色背景), 折叠为一行"🔧 工具：名称", 点击展开参数与结果 */
    fun showTool(name: String, arg: String) {
        sealCurrentContent()   // 工具调用同样先冻结当前正文段, 与恢复时间线一致
        removeStatus()
        stopLoading()
        val b = ToolBlock(name, arg)
        toolBlocks.add(b)
        timelineEvents.add(b)
        val tv = TextView(host).apply {
            text = b.collapsedText()
            textSize = 14f
            setTextColor(THINK_TEXT)
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            background = rounded(host.dp(10), THINK_BG)
            maxWidth = maxW
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = host.dp(6)
                bottomMargin = host.dp(4)
            }
        }
        addChatBubble(tv)
        // 交互行(点击展开/收起)不能 textIsSelectable: 该模式下首次点击会被文本选择机制吞掉, 需点两次才响应
        tv.setOnClickListener { toggleTool(b) }
        b.view = tv
    }

    /** 工具结果回填: 若该工具块已展开则刷新显示结果 */
    fun setToolResult(name: String, result: String) {
        val b = toolBlocks.lastOrNull() ?: return
        b.result = result
        if (b.expanded) b.view?.text = b.expandedText()
    }

    private fun toggleTool(b: ToolBlock) {
        b.expanded = !b.expanded
        val tv = b.view ?: return
        tv.text = if (b.expanded) b.expandedText() else b.collapsedText()
        host.keepReadingPosition(tv)   // 展开/收起后保持阅读位置, 不再强制滚到底部
    }

    /** 冻结当前正文段(一段确定不再追加): 思考/工具插入前调用; 无正文或已冻结时忽略 */
    private fun sealCurrentContent() {
        val b = activeContent ?: return
        if (b.done) return
        b.done = true
        activeContent = null
        if (!b.typeActive) finishTypeRender(b)   // 从未进入打字(无持续输出): 直接完稿排版
        // 若正在打字: done 后由 tickFrame 在追平剩余字符时自动收尾, 保持打字机节奏
    }

    fun appendContent(text: String) {
        removeStatus()
        stopLoading()
        var b = activeContent
        if (b == null || b.done) {
            // 新正文段: 独立气泡(浅色背景), 与思考/工具深色气泡区分
            b = ContentBlock()
            activeContent = b
            contentBlocks.add(b)
            b.view = TextView(host).apply {
                textSize = 15f
                setTextColor(BUBBLE_AI_TEXT)
                setLineSpacing(host.dp(3).toFloat(), 1f)
                setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                background = rounded(host.dp(12), BUBBLE_AI)
                maxWidth = maxW
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = host.dp(6)
                    bottomMargin = host.dp(4)
                }
            }
            val block = b
            b.view?.let { host.makeCopyable(it) {
                val rawB = ModeConfig.stripChatProtocolPrefix(block.text.toString())
                if (ModeConfig.chatPlainText()) stripMarkdownForChat(rawB) else rawB
            } }
            addChatBubble(b.view)
            // 记录每段正文真实插入位置, 恢复时按原位渲染(不固定末尾)
            timelineEvents.add(ContentMarker)
        }
        b.text.append(text)
        // 跟踪模型吐字速率: 用本次 delta 的字符数/间隔 更新 EMA(字符/秒), 供 tickFrame 自适应打字速度
        val nowNs = System.nanoTime()
        if (lastDeltaNs != 0L) {
            val dt = (nowNs - lastDeltaNs) / 1_000_000_000.0
            if (dt > 0.001) {
                val inst = text.length / dt
                modelRate = if (modelRate <= 0) inst else modelRate * 0.7 + inst * 0.3
            }
        }
        lastDeltaNs = nowNs
        if (!b.typeActive) {
            b.typeActive = true
            b.typeFinishedRender = false
            b.lastFrameNs = 0L
            TypewriterCenter.register(this)
        }
    }

    /** 帧回调(Choreographer): 依次推进各正文段打字机, 全部段完成收尾后停止驱动 */
    override fun tickFrame(frameNs: Long): Boolean {
        var running = false
        for (b in contentBlocks) {
            if (b.typeFinishedRender) continue
            if (!b.typeActive) continue
            running = true
            stepBlock(b, frameNs)
        }
        if (!running) {
            TypewriterCenter.unregister(this)   // 无活跃打字段: 退出帧循环
            return false
        }
        return true
    }

    /** 单段打字机推进(逻辑承接原单一 contentText 打字机, 状态内聚到段内) */
    private fun stepBlock(b: ContentBlock, frameNs: Long): Boolean {
        val total = b.text.length
        if (b.shownLen >= total) {
            b.lastFrameNs = frameNs
            if (b.done) {
                finishTypeRender(b)
                return false
            }
            return true   // 已追平当前已收文本, 静止等待新 delta(不再渲染闪烁光标)
        }
        val elapsed = if (b.lastFrameNs == 0L) 1.0 / 60.0 else (frameNs - b.lastFrameNs) / 1_000_000_000.0
        b.lastFrameNs = frameNs
        // 打字速率自适应: 跟随模型吐字节奏(不超过模型速率1.5倍, 下限8字/秒)
        var rate = typeSpeed(total).toDouble()
        if (modelRate > 0) rate = minOf(rate, modelRate * 1.5)
        rate = maxOf(rate, 8.0)
        charBudget += rate * elapsed
        var budget = charBudget.toInt()
        if (budget >= 1) charBudget -= budget
        // 防冻结: 距上次推进超800ms仍无预算时强制推进1个, 避免慢模型下长时间无响应
        if (budget < 1 && b.shownLen < total && frameNs - b.lastAdvanceNs > 800_000_000L) {
            budget = 1
            charBudget = 0.0
        }
        var idx = b.shownLen
        while (budget > 0 && idx < total) {
            val n = Character.charCount(b.text.codePointAt(idx))  // 不拆散 emoji/代理对
            idx += n
            budget -= n
        }
        if (idx > b.shownLen) {
            b.shownLen = idx
            b.lastAdvanceNs = frameNs
            b.view?.let { tv ->
                // 打字期间只更新纯文本, 收尾一次性 markdown 排版(避免每帧全量解析抽搐)
                tv.text = b.text.substring(0, b.shownLen)
                host.scrollToBottom()
            }
        }
        if (b.shownLen >= total && b.done) {
            finishTypeRender(b)
            return false
        }
        return true
    }

    /** 分级变速(参考 assistant SpeedTier): 短文本慢打有打字感, 长文本极速掠过 */
    private fun typeSpeed(total: Int): Int = when {
        total <= 15 -> 80
        total <= 40 -> 160
        total <= 80 -> 240
        total <= 160 -> 320
        else -> 480
    }

    /** 收尾: 一次性 Markdown 排版, 每段只执行一次 */
    private fun finishTypeRender(b: ContentBlock) {
        if (b.typeFinishedRender) return
        b.typeFinishedRender = true
        b.typeActive = false
        b.view?.let {
            if (ModeConfig.chatPlainText()) it.text = stripMarkdownForChat(b.text.toString()).trimEnd()
            else host.markwon.setMarkdown(it, ModeConfig.stripChatProtocolPrefix(b.text.toString()))
        }
        host.scrollToBottom()
    }

    /** 聊天模式兜底: 把模型手滑输出的 Markdown 标记剥成纯文本(软约束失效时的硬兜底)。
     *  仅处理成对/行首的常见 MD 标记, 不误伤正常文本(2*3、外贸价 10-5 等不配对星号不受影响)。 */
    private fun stripMarkdownForChat(raw: String): String {
        var s = raw.ifBlank { return raw }
        // 代码块: 去掉围栏行, 保留内容
        s = s.replace(Regex("(?s)```[^\\r\\n]*\\r?\\n?(.*?)```"), "$1")
        // 行内代码: `x` -> x
        s = s.replace(Regex("`([^`]+)`"), "$1")
        // 粗斜体/加粗/斜体: ***x*** -> x, **x** -> x, *x* -> x
        s = s.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
        // 删除线: ~~x~~ -> x
        s = s.replace(Regex("~~(.+?)~~"), "$1")
        // 图片/链接: ![alt](url) -> alt, [text](url) -> text
        s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // 标题: 行首 # -> 去掉
        s = s.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
        // 引用: 行首 > -> 去掉
        s = s.replace(Regex("(?m)^\\s*>+\\s*"), "")
        // 无序/有序列表: 行首 - * + 或 1. -> 去掉标记
        s = s.replace(Regex("(?m)^\\s*(?:[-*+]|\\d{1,3}\\.)\\s+"), "")
        // 表格分隔行(|---|---|)整行去掉; 竖线 -> 空格, 并清理行尾多余空格
        s = s.replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$"), "")
        s = s.replace("|", " ")
        s = s.replace(Regex("(?m)[ \\t]+$"), "")
        // 协议前缀(全半角)剥离: 正文不应残留 思考/答案/TOOL 前缀(原生tools分支不做行级剥离, 此处渲染兜底)
        // 思考/TOOL 行整体剔除(思考已由thinking区承载), 答案行仅去前缀保留正文
        s = s.replace(Regex("(?m)^\\s*思考\\s*[:：].*\\r?\\n?"), "")
            .replace(Regex("(?m)^\\s*TOOL\\s*[:：].*\\r?\\n?"), "")
            .replace(Regex("(?m)^\\s*答案\\s*[:：]\\s*"), "")
        // 压缩多余空行, 保留段落分隔
        s = s.replace(Regex("\\n{3,}"), "\n\n").trim()
        return s
    }

    fun finishContent() {
        done = true
        stopLoading()
        if (contentBlocks.isEmpty() && toolBlocks.isEmpty() && thinkingBlocks.isEmpty()) {
            addLine("(无内容)", BUBBLE_AI_TEXT, italic = false)
            return
        }
        // 未封段的正文章节在此一并收尾(整体完成不再追加)
        activeContent?.let { it.done = true }
        activeContent = null
        for (b in contentBlocks) {
            b.done = true
            if (!b.typeActive) finishTypeRender(b)   // 未在打字(从未收到正文或已打完): 直接排版
            // 正在打字的段: done 后由帧回调追上剩余字符自动收尾
        }
    }

    /** 取全部思考块全文(供持久化到会话历史, 切回会话时恢复思考区) */
    fun thinkingSnapshot(): String =
        thinkingBlocks.joinToString("\n\n") { it.text.toString() }

    /** 取工具调用序列(供持久化到会话历史, 切回会话时恢复工具行), JSON 数组, 每条含 name/arg/result */
    fun toolsSnapshot(): String {
        if (toolBlocks.isEmpty()) return ""
        val sb = StringBuilder("[")
        toolBlocks.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append('{')
                .append("\"name\":").append(jsonEscape(b.name))
                .append(",\"arg\":").append(jsonEscape(b.arg))
                .append(",\"result\":").append(jsonEscape(b.result ?: ""))
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * 取思考/工具/正文交错时间线(供持久化到会话历史, 切回会话时恢复交替顺序), JSON 数组:
     * [{"type":"think","text":...},{"type":"tool","name":...,"arg":...,"result":...},{"type":"content"}, ...]
     */
    fun timelineSnapshot(): String {
        if (timelineEvents.isEmpty()) return ""
        val sb = StringBuilder("[")
        timelineEvents.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            when (e) {
                is ThinkingBlock -> sb.append('{')
                    .append("\"type\":\"think\"")
                    .append(",\"text\":").append(jsonEscape(e.text.toString()))
                    .append('}')
                is ToolBlock -> sb.append('{')
                    .append("\"type\":\"tool\"")
                    .append(",\"name\":").append(jsonEscape(e.name))
                    .append(",\"arg\":").append(jsonEscape(e.arg))
                    .append(",\"result\":").append(jsonEscape(e.result ?: ""))
                    .append('}')
                ContentMarker -> sb.append("{\"type\":\"content\"}")
            }
        }
        sb.append(']')
        return sb.toString()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun showError(msg: String) {
        removeStatus()
        stopLoading()
        contentBlocks.forEach { it.typeActive = false }   // 出错即停打
        TypewriterCenter.unregister(this)
        addLine(msg, Color.parseColor("#D93025"), italic = false)
        done = true
    }

    private fun removeStatus() {
        statusView?.let { bubbleBox?.removeView(it) }
        statusView = null
    }

    private fun addLine(text: String, color: Int, italic: Boolean, selectable: Boolean = true): TextView {
        val tv = TextView(host).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            if (italic) setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC))
            setPadding(0, host.dp(2), 0, host.dp(2))
            // 自适应上限: 与正文气泡同一基准(Agent=全屏 / 聊天=chatMaxW)
            maxWidth = host.chatMaxW()
        }
        bubbleBox?.addView(tv)
        // 交互行(点击展开/收起)不能 textIsSelectable: 该模式下首次点击会被文本选择机制吞掉, 需点两次才响应
        if (selectable) host.makeCopyable(tv)
        return tv
    }
}
