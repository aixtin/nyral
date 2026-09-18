package io.github.aixtin.nyral

import android.util.Log
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// AI 气泡配色: 文件级变量, 随当前主题刷新(MainActivity 启动/切换时调用 applyBubbleTheme)
internal var BUBBLE_AI: Int = DefaultTheme.bubbleAi
internal var BUBBLE_AI_TEXT: Int = DefaultTheme.bubbleAiText
internal var THINK_TEXT: Int = DefaultTheme.thinkText
internal var THINK_BG: Int = DefaultTheme.thinkBg

// 浏览器悬浮模式: 打开浏览器时文字类气泡背景透明化(看清内容同时透出浏览器), 图片/视频气泡不参与(背景为 null 或打 NO_FLOAT_TAG 标记)
internal var chatFloatMode = false
internal const val FLOAT_BUBBLE_ALPHA = 140   // 悬浮模式气泡背景 alpha, 约55%不透明, 可调
/** 视频气泡等要求始终不透明的 view 打此 tag, 悬浮动画收集背景时跳过 */
internal const val NO_FLOAT_TAG = "nyral_no_float_alpha"

/** 悬浮模式取色: 文字类气泡背景色带 alpha, 普通模式原色 */
internal fun floatBubbleColor(base: Int): Int =
    if (chatFloatMode) (base and 0x00FFFFFF) or (FLOAT_BUBBLE_ALPHA shl 24) else base

/** 单段正文上限(字符): 流式累计超过立即封段另起气泡, 历史恢复按同阈值分片,
 *  避免超长回复单行超大 TextView 一次性全量 markdown 渲染卡顿(阶段2 content 分片) */
internal const val SPLIT_CONTENT_LEN = 4000

internal fun applyBubbleTheme(t: AppTheme) {
    BUBBLE_AI = t.bubbleAi
    BUBBLE_AI_TEXT = t.bubbleAiText
    THINK_TEXT = t.thinkText
    THINK_BG = t.thinkBg
}

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
    private var loadingAnim: ValueAnimator? = null   // 三点呼吸灯动画(阶段3: 对齐 assistant isLoading 呼吸灯)
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
        fun summary(): String = host.getString(R.string.think_expand, count)
    }

    /** 一次工具调用: 折叠态只显示工具名, 点击展开参数与结果 */
    private inner class ToolBlock(val name: String, val arg: String) {
        var result: String? = null
        var view: TextView? = null
        var expanded = false
        fun collapsedText(): String = host.getString(R.string.tool_collapsed, name)
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
            tv.text = host.getString(R.string.think_prefix, tailThinking(b))
            host.scrollToBottom(true)
        }
    }

    /** 思考块内多段只显示最后一段, 避免 "思考中: 思考一思考二..." 眼花 */
    private fun tailThinking(b: ThinkingBlock): String {
        val s = b.text.toString()
        val idx = s.lastIndexOf("\n\n")
        return if (idx >= 0) s.substring(idx + 2).trim() else s
    }

    fun createStreamingBox(): LinearLayout {
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
        // 不再直接挂父容器: 气泡盒由 ChatRow.Streaming 持有, 由 ChatAdapter.attachStreaming 挂到 item 容器
        bubbleBox = box
        host.enterBubble(box)
        return box
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

    /** 思考完成、正文未开始前显示加载指示(阶段3: 三点呼吸灯, 对齐 assistant isLoading ValueAnimator 循环动画), 避免误以为卡住 */
    private fun startLoading() {
        if (loadingRow != null || contentBlocks.isNotEmpty()) return
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(2), host.dp(8), host.dp(2), host.dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dots = ArrayList<View>(3)
        repeat(3) { i ->
            dots.add(View(host).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(THINK_TEXT)
                }
                val lp = LinearLayout.LayoutParams(host.dp(6), host.dp(6))
                if (i > 0) lp.leftMargin = host.dp(5)
                layoutParams = lp
            }.also { row.addView(it) })
        }
        // v8.7 呼吸闪烁降噪: 原三点错相位无限呼吸(900ms 循环 alpha 0.25~1.0)在聊天模式下
        // 与头像气泡相邻, 视觉呈"头像跟气泡呼吸式闪烁"(用户反馈); 改为静态三点保持加载语义,
        // 消息到达后由 stopLoading 移除, 不再循环明暗
        loadingAnim = null
        loadingRow = addChatBubble(row)
        host.scrollToBottom(true)   // 加载行可能加在屏幕外, 必须滚到底才可见
        android.util.Log.i("Nyral", "startLoading blocks=" + contentBlocks.size)
    }

    private fun stopLoading() {
        loadingRow?.let { bubbleBox?.removeView(it) }
        loadingRow = null
        loadingAnim?.cancel()
        loadingAnim = null
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
                background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
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
        Log.d("NyralSink", "collapseThinking")
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
        Log.d("NyralSink", "toggleThinking expanded=${b.expanded}")
        val tv = b.view ?: return
        tv.text = if (b.expanded) "💭 " + b.text else b.summary()
        host.keepReadingPosition(tv)   // 展开/收起后保持阅读位置, 不跳回底部
    }

    /** 工具调用开始: 独立气泡(深色背景), 折叠为一行"🔧 工具：名称", 点击展开参数与结果 */
    fun showTool(name: String, arg: String) {
        Log.d("NyralSink", "showTool $name")
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
            background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
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
        Log.d("NyralSink", "toggleTool expanded=${b.expanded}")
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
                background = rounded(host.dp(12), floatBubbleColor(BUBBLE_AI))
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
            // 记录每段正文真实插入位置, 恢复时按原位渲染(不固定末尾); 事件持有段对象,
            // 持久化时输出该段正文, 恢复渲染逐段精确还原(阶段2 分片配套)
            timelineEvents.add(b)
        }
        b.text.append(text)
        // 阶段2 超长分片: 累计超过阈值立即封段另起气泡(已收内容立即排版可见, 渲染压力分散),
        // 避免单段超大 TextView 收尾一次性全量 markdown 解析卡顿
        if (b.text.length >= SPLIT_CONTENT_LEN) {
            b.done = true
            activeContent = null
            b.shownLen = b.text.length   // 直接追平剩余字符: 该段内容已确定, 立即排版而非继续打字
            finishTypeRender(b)
        }
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
                // 阶段3 pending 抑制: 打字期间隐藏文件/产品卡标记裸文本(占位/半截隐藏), 完成后由 markdown 渲染真实卡片
                tv.text = suppressCards(b.text.substring(0, b.shownLen))
                host.scrollToBottom(true)
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
        host.scrollToBottom(true)
    }

    /**
     * 阶段3 pending 抑制文件/产品卡: AI 流式打字期间(消息未完成, isLoading 态)不展示卡片标记的裸文本,
     * 避免用户看到 "[文件:xxx" 这类半截 markdown 或 "[文件:xxx](att://..)" 原文;
     * 完成(onDone→finishTypeRender)后用完整原文 markdown 渲染真实卡片。
     * 仅作用于打字显示(shownLen 前缀), 不改动累积原文 b.text, 完成后渲染不受影响。
     */
    private fun suppressCards(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        // 完整附件标记 -> 轻量占位(不打字机逐字显示 markdown 原文)
        s = s.replace(Regex("""\[文件:[^\]]*\]\(att://[^)]*\)"""), "\uD83D\uDCCE 附件")
        s = s.replace(Regex("""\[视频:[^\]]*\]\(att://[^)]*\)"""), "\uD83D\uDCCE 视频")
        s = s.replace(Regex("""\[图片\]\(att://[^)]*\)"""), "\uD83D\uDDBC\uFE0F 图片")
        s = s.replace(Regex("""\[音频\]\(att://[^)]*\)"""), "\uD83C\uDFA4 音频")
        // 完整 markdown 图片/产品图 -> 占位(URL 尚在流式中或已完整均不展开)
        s = s.replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "\uD83D\uDDBC\uFE0F 图片")
        // 半截未闭合标记(如 "[文件:" / "[文件:xxx" / "![图片](url") -> 隐藏, 避免显示半成品
        s = s.replace(Regex("""\[(?:文件|视频|图片|音频)[^\]\n]*$"""), "")
        s = s.replace(Regex("""!\[[^\]\n]*$"""), "")
        s = s.replace(Regex("""\[[^\]\n]*\(att://[^)]*$"""), "")
        return s
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
                is ContentBlock -> sb.append('{')
                    .append("\"type\":\"content\"")
                    .append(",\"text\":").append(jsonEscape(e.text.toString()))
                    .append('}')
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
