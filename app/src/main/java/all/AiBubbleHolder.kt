package io.github.aixtin.nyral

import android.util.Log
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextSwitcher
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

// ==== 单行状态行(09-24 重构) ====
/** 片段轮播停留时长(ms): 展示→停约1s→切下一片段(快速刷新形态) */
internal const val STATUS_CAROUSEL_MS = 1000L
/** 片段最大字符数: 超长截断加省略号 */
internal const val STATUS_SNIPPET_LEN = 20

internal fun applyBubbleTheme(t: AppTheme) {
    BUBBLE_AI = t.bubbleAi
    BUBBLE_AI_TEXT = t.bubbleAiText
    THINK_TEXT = t.thinkText
    THINK_BG = t.thinkBg
}

/** 脉络时间线事件(流式 AiBubbleHolder 与历史 AiRich 重建共用的展示数据, 09-24 重构):
 *  type = "think"(text=全文) | "tool"(name/arg/result) */
internal class StatusEvent(val type: String, val text: String, val name: String, val arg: String, val result: String)

/** 单行状态行外壳(流式与历史同形态): 深色圆角胶囊行, 内容由调用方填充 */
internal fun makeStatusShell(host: MainActivity): LinearLayout = LinearLayout(host).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
    background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = host.dp(6)
        bottomMargin = host.dp(4)
    }
}

/** 收尾/历史摘要文案: "💭 已思考X字 · 🔧 N个工具"(缺项自动省略) */
internal fun statusSummaryText(thinkChars: Int, toolCount: Int): String = buildString {
    if (thinkChars > 0) append("💭 已思考").append(thinkChars).append("字")
    if (toolCount > 0) {
        if (isNotEmpty()) append(" · ")
        append("🔧 ").append(toolCount).append("个工具")
    }
}

/** 脉络时间线(原地展开形态): 左侧竖线贯穿 + 段首 9dp 圆点衔接(手串意象),
 *  思考段=💭头深色块全文 / 工具段=🔧头深色块(名称/参数/结果), 事件间以圆点分段;
 *  onBody(事件序号, 内容块): 流式侧借此建立 block->视图映射实现展开态实时刷新 */
internal fun buildStatusTimeline(
    host: MainActivity, events: List<StatusEvent>,
    onBody: ((Int, TextView) -> Unit)? = null
): View {
    val lineColor = (THINK_TEXT and 0x00FFFFFF) or (0x55 shl 24)   // 竖线: 文字色 1/3 透明度
    val box = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(4)
        }
    }
    events.forEachIndexed { i, e ->
        val first = i == 0
        val last = i == events.size - 1
        // 左列脉络: 竖线(首行从圆点起/末行到圆点止) + 9dp 圆点压在段首
        val spine = FrameLayout(host)
        val line = View(host).apply { setBackgroundColor(lineColor) }
        spine.addView(line, FrameLayout.LayoutParams(host.dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = if (first) host.dp(12) else 0
            bottomMargin = if (last) host.dp(14) else 0
        })
        val bead = View(host).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(THINK_TEXT)
            }
        }
        spine.addView(bead, FrameLayout.LayoutParams(host.dp(9), host.dp(9)).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = host.dp(8)
        })
        // 内容块: 与原思考/工具深色气泡同款
        val body = TextView(host).apply {
            textSize = 14f
            setTextColor(THINK_TEXT)
            maxWidth = host.chatMaxW()
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
            text = if (e.type == "think") {
                if (e.text.isBlank()) "💭" else "💭 " + e.text
            } else {
                buildString {
                    append("🔧 工具：").append(e.name)
                    if (e.arg.isNotBlank()) append("\n参数：").append(e.arg)
                    if (e.result.isNotBlank()) append("\n结果：").append(e.result)
                }
            }
            setTextIsSelectable(true)
        }
        onBody?.invoke(i, body)
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (last) 0 else host.dp(10)
            }
        }
        row.addView(spine, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = host.dp(4)
        })
        box.addView(row)
    }
    return box
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
    private var loadingAnim: ValueAnimator? = null   // 三点呼吸灯动画(阶段3: 对齐 Marvis isLoading 呼吸灯)
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
        var lastRenderNs = 0L          // 上次真正 setText 渲染的时间戳(批量渲染节流, 消除蹦迪)
        val streamRenderer = MdStreamRenderer()  // D路线: 流式 MD 渲染(块缓存+截断尾部)
        var maxShownW = 0              // 本段气泡历史最大测量宽度(px): 单向性约束, 文本变短只扩不缩防跳动
        var dimmed = false             // 输出中暗色态: 打字期间气泡+文字调暗, 完成后渐亮
        var dimAnim: ValueAnimator? = null   // 完成渐亮动画
        var dimBg: GradientDrawable? = null  // 暗色态背景 drawable(渐亮直接 setColor, 不重建)
        var dimBaseBg = 0              // 原始亮背景色
        var dimBaseText = 0            // 原始亮文字色
        var dimFactor = 0f             // 本段实际暗度因子(随模型速率动态)
        var brightenMs = 0L            // 本段实际渐亮时长(随模型速率动态)
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

    // ==== 单行状态行(09-24 重构): 流式期间思考/工具不再建独立大气泡, 统一进单行状态行 ====
    // 形态: [💭/🔧 片段轮播(TextSwitcher 淡入淡出)] + [字数/工具计数实时跳动], 点击原地展开脉络时间线
    // statusCol = chatWrap 外层行内的竖容器(状态行+展开的时间线同属一个头像行)
    private var statusCol: LinearLayout? = null
    private var statusWrap: View? = null         // 状态行整行挂到 bubbleBox 的实际 view(chatWrap 外层)
    private var statusSwitcher: TextSwitcher? = null
    private var statusCounter: TextView? = null
    private var statusExpanded = false          // 状态行是否已展开脉络时间线
    private var timelineView: View? = null      // 展开的时间线容器
    private var carouselRunnable: Runnable? = null   // 片段轮播驱动
    private var lastSnippet: String? = null     // 当前展示片段(相同不重切, 避免动画空转)
    private var statusSealed = false            // 收尾定格后不再轮播

    // 输出中暗色态(解决闪感): AI 流式输出期间气泡背景+文字亮度压低(暗色降低逐字刷新感知),
    // 输出完成(finishTypeRender)后渐亮回原始色, 视觉上"安静打字 -> 完成后亮起";
    // 参数随模型速率动态: 快模型轻暗+短过渡(避免"闪一下"), 慢模型重暗+长缓出(避免"突亮")
    private val TYPE_DIM_FACTOR_SLOW = 0.40f   // 慢模型(<=10字/s): 重暗
    private val TYPE_DIM_FACTOR_FAST = 0.60f   // 快模型(>=40字/s): 轻暗
    private val TYPE_BRIGHTEN_MS_SLOW = 1400L  // 慢模型: 长缓出
    private val TYPE_BRIGHTEN_MS_FAST = 500L   // 快模型: 短过渡

    /** 一段思考: 全文进脉络时间线, 字数供计数与收尾摘要 */
    private inner class ThinkingBlock {
        val text = StringBuilder()
        var collapsed = false   // 该段思考是否已封段
        var tlView: TextView? = null   // 脉络时间线里的全文块(展开状态下流式实时刷新)
        val count: Int get() = text.codePointCount(0, text.length)
    }

    /** 一次工具调用: 名称/参数/结果进脉络时间线 */
    private inner class ToolBlock(val name: String, val arg: String) {
        var result: String? = null
        var tlView: TextView? = null   // 脉络时间线里的详情块(展开状态下结果回填实时刷新)
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

    /** 一次性应用最新文本 + 滚动到底, 每帧最多一次; 正文渲染交给打字机(typeTick), 此处只管状态行(计数/展开块),
     *  片段轮播独立节奏(driveCarousel), 避免双重 setText 互抢导致抽搐 */
    private fun flush() {
        updateStatusCounter()
        refreshExpandedTimeline()
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

    /** 生成等待指示(阶段1 请求/翻记忆期): 三点指示器; 状态行存在时状态行即活动指示, 不再重复 */
    fun showLoading() {
        if (loadingRow != null || contentBlocks.isNotEmpty() || statusCol != null) return
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
        android.util.Log.i("Nyral", "startLoading blocks=" + contentBlocks.size)
    }

    private fun stopLoading() {
        loadingRow?.let { bubbleBox?.removeView(it) }
        loadingRow = null
        loadingAnim?.cancel()
        loadingAnim = null
    }

    /** 思考段开始: 新建思考块(多轮思考按真实顺序进脉络时间线), 状态行切 💭 态启动片段轮播 */
    fun showThinking() {
        sealCurrentContent()   // 先冻结当前正文段, 后续正文新起气泡(思考/工具与正文交错时正文拆段)
        removeStatus()
        stopLoading()
        statusSealed = false
        var b = activeThinking
        if (b == null || b.collapsed) {
            b = ThinkingBlock()
            thinkingBlocks.add(b)
            timelineEvents.add(b)
            activeThinking = b
        } else {
            // 同段内续写: 追加分隔
            b.text.append("\n\n")
        }
        ensureStatusRow()
        showSnippet()   // 思考事件即时上屏, 不等轮播周期
        driveCarousel()
        scheduleRefresh()
        host.onStatusGrown()   // 状态行出现/移动: 通知主层锚底跟随(09-24)
    }

    fun appendThinking(text: String) {
        activeThinking?.let { it.text.append(text) }
        scheduleRefresh()
        host.onStatusGrown()   // 思考内容增长: 通知主层锚底跟随(09-24)
    }

    /** 思考段结束: 封段等待下一事件(正文/工具/新思考), 状态行保持轮播 */
    fun collapseThinking() {
        val b = activeThinking ?: return
        if (b.collapsed) return
        b.collapsed = true
        activeThinking = null
        // 状态行形态下状态行本身即"生成中"指示, 不再另起三点加载行
    }

    // ===================== 单行状态行(09-24 重构) =====================

    /** 确保状态行存在并停在 bubbleBox 末尾(跟随最新活动): [💭/🔧 片段轮播] + [计数跳动] */
    private fun ensureStatusRow() {
        if (statusCol != null) {
            // 已存在: 整行(含 chatWrap 外层)移到末尾, 新一轮思考/工具时状态行跟随最新输出位置
            statusWrap?.let { w ->
                (w.parent as? ViewGroup)?.let { p ->
                    p.removeView(w)
                    bubbleBox?.addView(w)
                }
            }
            statusSealed = false
            statusCounter?.visibility = View.VISIBLE
            return
        }
        val c = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val shell = makeStatusShell(host).apply {
            val sw = TextSwitcher(host).apply {
                setFactory {
                    TextView(host).apply {
                        textSize = 14f
                        setTextColor(THINK_TEXT)
                        maxLines = 1
                        // 宽度封顶(行整体不超 chatMaxW): LinearLayout 无 maxWidth, 移到内部 TextView
                        maxWidth = host.chatMaxW()
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
                inAnimation = AlphaAnimation(0f, 1f).apply { duration = 180L }
                outAnimation = AlphaAnimation(1f, 0f).apply { duration = 180L }
                // 固定最小宽: 片段文本长短变化时行宽稳定不抖
                minimumWidth = host.dp(140)
            }
            val counter = TextView(host).apply {
                textSize = 12f
                setTextColor(THINK_TEXT)
            }
            addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(counter, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = host.dp(8)
            })
            // 交互行不启用 textIsSelectable, 保证首次点击即展开
            setOnClickListener { toggleStatusExpand() }
        }
        c.addView(shell)
        statusWrap = addChatBubble(c)
        statusCol = c
        statusSwitcher = shell.getChildAt(0) as? TextSwitcher
        statusCounter = shell.getChildAt(1) as? TextView
    }

    /** 当前应展示的片段: 活跃思考 tail / 最新工具(无结果=参数, 有结果=结果) */
    private fun currentSnippet(): String? {
        activeThinking?.let { b ->
            if (!b.collapsed) {
                return "💭 " + snippet(tailThinking(b).replace("\n", " ").trim())
            }
        }
        toolBlocks.lastOrNull()?.let { tb ->
            val r = tb.result?.trim().orEmpty()
            return if (r.isEmpty()) "🔧 ${tb.name} · ${snippet(tb.arg)}"
            else "🔧 ${tb.name} → ${snippet(r)}"
        }
        thinkingBlocks.lastOrNull()?.let {
            return "💭 " + snippet(tailThinking(it).replace("\n", " ").trim())
        }
        return null
    }

    private fun snippet(s: String): String =
        if (s.codePointCount(0, s.length) > STATUS_SNIPPET_LEN) {
            val end = s.offsetByCodePoints(0, STATUS_SNIPPET_LEN)
            s.substring(0, end).trimEnd() + "…"
        } else s.ifBlank { "…" }

    /** 片段轮播(快速刷新形态): 每 1s 取最新片段, 与当前不同才切换(淡入淡出) */
    private fun driveCarousel() {
        if (statusSealed) return
        cancelCarousel()
        val r = Runnable {
            carouselRunnable = null
            if (statusSealed) return@Runnable
            showSnippet()
            driveCarousel()
        }
        carouselRunnable = r
        uiHandler.postDelayed(r, STATUS_CAROUSEL_MS)
    }

    private fun cancelCarousel() {
        carouselRunnable?.let { uiHandler.removeCallbacks(it) }
        carouselRunnable = null
    }

    private fun showSnippet() {
        if (statusSealed) return
        val s = currentSnippet() ?: return
        if (s == lastSnippet) return   // 内容没变不重切, 动画不空转
        lastSnippet = s
        statusSwitcher?.setText(s)
    }

    /** 状态行计数实时跳动: 思考态=当前段字数 / 工具态=累计工具数 */
    private fun updateStatusCounter() {
        if (statusSealed) return
        val tv = statusCounter ?: return
        val b = activeThinking
        val t = if (b != null && !b.collapsed) "${b.count}字"
        else if (toolBlocks.isNotEmpty()) "🔧 ${toolBlocks.size}"
        else null
        if (t != null) { tv.text = t; tv.visibility = View.VISIBLE } else tv.visibility = View.GONE
    }

    /** 状态行点击: 原地展开/收起脉络时间线(竖线+圆点+思考/工具块, 非弹窗) */
    private fun toggleStatusExpand() {
        val col = statusCol ?: return
        host.markUserTakeover()   // 展开/收起视同用户接管, 防自动滚动追底(09-24)
        statusExpanded = !statusExpanded
        if (statusExpanded) {
            if (timelineView == null) {
                val pairs = statusEvents()
                timelineView = buildStatusTimeline(host, pairs.map { it.first }) { i, tv ->
                    // 建立 block->时间线块映射, 流式追加实时刷新(只 setText 不重建)
                    when (val src = pairs.getOrNull(i)?.second) {
                        is ThinkingBlock -> src.tlView = tv
                        is ToolBlock -> src.tlView = tv
                    }
                }
            }
            col.addView(timelineView, 1)
        } else {
            timelineView?.let { col.removeView(it) }
        }
        col.requestLayout()
    }

    /** 流式事件 -> 时间线展示数据(思考/工具; 正文段已有独立气泡, 不进时间线), 附源 block 引用 */
    private fun statusEvents(): List<Pair<StatusEvent, Any?>> = timelineEvents.mapNotNull { e ->
        when (e) {
            is ThinkingBlock -> StatusEvent("think", e.text.toString(), "", "", "") to e
            is ToolBlock -> StatusEvent("tool", "", e.name, e.arg, e.result ?: "") to e
            else -> null
        }
    }

    /** 展开状态下流式刷新: 思考全文/工具结果实时更新到时间线块(只 setText 不重建, 无变化跳过防重排) */
    private fun refreshExpandedTimeline() {
        if (!statusExpanded) return
        for (e in timelineEvents) {
            when (e) {
                is ThinkingBlock -> e.tlView?.let {
                    val t = "💭 " + e.text.toString()
                    if (it.text.length != t.length) it.text = t
                }
                is ToolBlock -> e.tlView?.let {
                    val t = e.expandedText()
                    if (it.text.toString() != t) it.text = t
                }
                else -> {}
            }
        }
    }

    /** 工具调用开始: 状态行切 🔧 态(轮播工具名+参数), 工具块进脉络时间线 */
    fun showTool(name: String, arg: String) {
        sealCurrentContent()   // 工具调用同样先冻结当前正文段, 与恢复时间线一致
        removeStatus()
        stopLoading()
        statusSealed = false
        val b = ToolBlock(name, arg)
        toolBlocks.add(b)
        timelineEvents.add(b)
        ensureStatusRow()
        showSnippet()   // 工具事件即时上屏
        driveCarousel()
        host.onStatusGrown()   // 工具状态行出现/移动: 通知主层锚底跟随(09-24)
    }

    /** 工具结果回填: 展开态刷新时间线块, 片段即时切到结果 */
    fun setToolResult(name: String, result: String) {
        val b = toolBlocks.lastOrNull() ?: return
        b.result = result
        b.tlView?.let { it.text = b.expandedText() }
        showSnippet()
        host.onStatusGrown()   // 工具结果回填: 通知主层锚底跟随(09-24)
    }

    /** 冻结当前正文段(一段确定不再追加): 思考/工具插入前调用; 无正文或已冻结时忽略 */
    private fun sealCurrentContent() {
        val b = activeContent ?: return
        if (b.done) return
        b.done = true
        activeContent = null
        if (!b.typeActive) finishTypeRender(b)   // 从未进入打字(无持续输出): 直接完稿排版
        // 若正在打字: done 后由 tickFrame 在追平剩余字符时自动收尾, 保持打字机节奏
        // 空气泡修复(09-24): 全空白且无视图的段(懒建跳过的 preamble)直接丢弃,
        // 不进收尾/时间线, 防止空段持久化后历史重建再渲染出空气泡
        if (b.view == null && b.text.isBlank()) {
            contentBlocks.remove(b)
            timelineEvents.remove(b)
        }
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
            // 空气泡修复(09-24): 模型在"思考结束->调用工具"之间常先吐一小段空白正文
            // preamble(纯 \n\n 或被表情标记掩码成空), 旧实现建段即建空气泡(纯内边距
            // 无文字), 空白段永远填不上字 -> 空气泡停在思考与工具之间。改为懒建:
            // 首个非空白字符到达才真正创建视图挂气泡(attachContentView), 纯空白段
            // 不产生空气泡。时间线仍在建段时记录(保持事件顺序), 快照时过滤空段。
            timelineEvents.add(b)
        }
        b.text.append(text)
        if (b.view == null && b.text.isNotBlank()) attachContentView(b)
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

    /** 懒挂正文气泡视图(09-24 空气泡修复): 首个非空白字符到达才创建, 挂载后立即渲染
     *  已推进的打字机进度(空白期 shownLen 已推进但无视图可渲), 并通知主层正文顶定位 */
    private fun attachContentView(b: ContentBlock) {
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
        if (b.shownLen > 0) {
            b.view?.let { tv ->
                tv.text = if (ModeConfig.chatPlainText()) suppressCards(b.text.substring(0, b.shownLen))
                else b.streamRenderer.render(b.text.substring(0, b.shownLen))
            }
        }
    }

    /** 是否存在正在打字(未收尾)的正文段: 供 MainActivity.scrollToBottom 判断打字期分支 */
    fun hasActiveTypewriter(): Boolean = contentBlocks.any { it.typeActive }

    /** 状态行是否活跃(思考/工具阶段单行状态行在屏): 供 MainActivity 判断状态行锚底(09-24) */
    fun hasStatusRow(): Boolean = statusCol != null

    /** 当前模型吐字速率(字符/秒) EMA 值, 供涌动滚动分支使用 */
    fun currentModelRate(): Double = modelRate

    /** 帧回调(Choreographer): 依次推进各正文段打字机, 全部段完成收尾后停止驱动 */
    override fun tickFrame(frameNs: Long): Boolean {
        var running = false
        var advanced = false
        var finished = false
        for (b in contentBlocks) {
            if (b.typeFinishedRender) continue
            if (!b.typeActive) continue
            running = true
            val before = b.shownLen
            stepBlock(b, frameNs)
            if (b.shownLen > before) advanced = true
            if (b.typeFinishedRender) finished = true   // 本帧刚收尾排版(短正文常见: 推完即收尾)
        }
        // 帧合并锚底(2026-09-23): 本帧内容真实增长过 -> 通知主层在同一个 preDraw 里
        // scrollBy 补偿增长量, 视口锚底 = 内容原地生长, 不再依赖 delta 事件事后追滚;
        // 收尾帧(09-24): finished 一并上报, 允许 preDraw 在 typing 已结束时仍补偿最后一次排版增长
        if (advanced || finished) host.onTypeGrownFrame(finished)
        if (!running) {
            TypewriterCenter.unregister(this)   // 无活跃打字段: 退出帧循环
            return false
        }
        return true
    }

    /** 气泡宽度单向性: 文本更新后布局完成时测量真实宽度, 记忆本段历史最大宽(不超过 chatMaxW 封顶),
     *  后续文本变短(排版变化/静默重渲染/append 变窄)时保持 minimumWidth 不缩, 避免 AI 输出长短变化
     *  导致气泡整体放大放小左右跳动; 只在变宽时更新, 窄了不动 */
    private fun keepBubbleWidth(b: ContentBlock, tv: TextView) {
        tv.post {
            val w = tv.width
            if (w > b.maxShownW) {
                b.maxShownW = minOf(w, maxW)
                tv.minimumWidth = b.maxShownW
            }
        }
    }

    /** 输出中暗色态: 新正文段创建即调用, 气泡背景+文字亮度压暗(随模型速率动态),
     *  暗色降低逐字刷新/换行重排的视觉"闪感"; 原始亮色与参数存段上, 完成时渐亮恢复 */
    private fun dimBlock(b: ContentBlock, tv: TextView) {
        if (b.dimmed) return
        b.dimmed = true
        b.dimBaseBg = floatBubbleColor(BUBBLE_AI)
        b.dimBaseText = BUBBLE_AI_TEXT
        // 速率映射: 慢模型重暗+长缓出, 快模型轻暗+短过渡; modelRate 未建立(0)时取中间默认
        val mr = modelRate.toFloat()
        val t = if (mr <= 0f) 0.5f else ((mr - 10f) / 30f).coerceIn(0f, 1f)
        b.dimFactor = TYPE_DIM_FACTOR_SLOW + (TYPE_DIM_FACTOR_FAST - TYPE_DIM_FACTOR_SLOW) * t
        b.brightenMs = TYPE_BRIGHTEN_MS_SLOW + ((TYPE_BRIGHTEN_MS_FAST - TYPE_BRIGHTEN_MS_SLOW) * t).toLong()
        val bg = rounded(host.dp(12), dimColor(b.dimBaseBg, b.dimFactor))
        b.dimBg = bg
        tv.background = bg
        tv.setTextColor(dimColor(b.dimBaseText, b.dimFactor))
    }

    /** 输出完成渐亮: 按本段速率参数时长缓出(DecelerateInterpolator 先快后缓, 收尾柔和),
     *  取消/结束都落回全亮, 不残留暗色 */
    private fun brightenBlock(b: ContentBlock, tv: TextView) {
        b.dimAnim?.cancel()
        val bg = b.dimBg
        val fromBg = dimColor(b.dimBaseBg, b.dimFactor)
        val fromText = dimColor(b.dimBaseText, b.dimFactor)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = b.brightenMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                bg?.setColor(blendColor(fromBg, b.dimBaseBg, t))
                tv.setTextColor(blendColor(fromText, b.dimBaseText, t))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    b.dimAnim = null
                    b.dimmed = false
                }
                override fun onAnimationCancel(a: Animator) {
                    bg?.setColor(b.dimBaseBg)
                    tv.setTextColor(b.dimBaseText)
                    b.dimAnim = null
                    b.dimmed = false
                }
            })
        }
        b.dimAnim = anim
        anim.start()
    }

    /** 颜色亮度压低(factor), 保留原 alpha(悬浮模式半透明背景不受影响) */
    private fun dimColor(c: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] *= factor
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    /** ARGB 线性插值: 渐亮动画用 */
    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val ta = (a ushr 24) and 0xFF
        val tr = (a ushr 16) and 0xFF
        val tg = (a ushr 8) and 0xFF
        val tb = a and 0xFF
        val ba = (b ushr 24) and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        return ((ta + ((ba - ta) * t).toInt()) shl 24) or
                ((tr + ((br - tr) * t).toInt()) shl 16) or
                ((tg + ((bg - tg) * t).toInt()) shl 8) or
                (tb + ((bb - tb) * t).toInt())
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
            return true   // 已追平当前已收文本, 静止等待新 delta
        }
        val elapsed = if (b.lastFrameNs == 0L) 1.0 / 60.0 else (frameNs - b.lastFrameNs) / 1_000_000_000.0
        b.lastFrameNs = frameNs
        // 打字速率自适应: 跟随模型吐字节奏(不超过模型速率1.5倍, 下限16字/秒保证视觉连续)
        var rate = typeSpeed(total).toDouble()
        if (modelRate > 0) rate = minOf(rate, modelRate * 1.5)
        rate = maxOf(rate, 16.0)
        // 超屏冲刺(2026-09-23): 气泡完全滚出视口(不可见)时不留余地, 本帧直接追平已收文本;
        // 屏内保留分级变速(短慢长快)打字感, 超屏说明用户不在看输出(上翻阅读/吸顶顶出), 全力追上模型
        val tv0 = b.view
        if (tv0 != null) {
            val vr = android.graphics.Rect()
            tv0.getGlobalVisibleRect(vr)
            if (vr.height() == 0) rate = 1e9
        }
        charBudget += rate * elapsed
        var budget = charBudget.toInt()
        if (budget >= 1) charBudget -= budget
        // 视觉连续兜底: 距上次推进超50ms(≈20Hz)仍无预算时强制推进1个, 消除慢模型/高帧率下
        // 攒预算等待造成的步进感(原800ms在90/120Hz高刷下会明显一顿一顿)
        if (budget < 1 && b.shownLen < total && frameNs - b.lastAdvanceNs > 50_000_000L) {
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
                // 全速前进: 每帧即时渲染 (2026-09-23 移除 renderSkip 跳帧: 每个 token 到达的下一帧
                // 即重渲染, 半截态停留时间最小化, 对齐 DeepSeek 实时渲染"吐出来就是 MD 结构+中途微调")
                // 层1 涌动滚动(2026-09-22): 打字期间改为蓄放+正弦波滚动, 不再瞬间贴底;
                // 收尾 finishTypeRender 仍 force 贴底对齐
                // 阶段3 pending 抑制: 打字期间隐藏文件/产品卡标记裸文本(占位/半截隐藏), 完成后由 markdown 渲染真实卡片
                MdSpannable.tableMaxWidth = tv.maxWidth
                tv.text = if (ModeConfig.chatPlainText()) suppressCards(b.text.substring(0, b.shownLen))
                else b.streamRenderer.render(b.text.substring(0, b.shownLen))
                keepBubbleWidth(b, tv)
                RoundedTablePlugin.attachInvalidators(tv)
                // 追底已去除(2026-09-23): 打字期间不再自动滚动, 用户自由阅读; 收尾 force 贴底对齐
            }
        }
        if (b.shownLen >= total && b.done) {
            finishTypeRender(b)
            return false
        }
        return true
    }

    /** 分级变速(参考 Marvis SpeedTier): 短文本慢打有打字感, 长文本极速掠过 */
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
            else {
                val raw = ModeConfig.stripChatProtocolPrefix(b.text.toString())
                MdSpannable.tableMaxWidth = it.maxWidth
                // 全程流式: 无附件时不切换 markwon, 流式渲染器已对齐表格/标题/代码块视觉,
                // 直接渲染完整文本消除收尾视觉突变; 含附件(att://)仍走 markwon 渲染真实卡片
                if (raw.contains("att://")) {
                    b.streamRenderer.clearCache()
                    host.markwon.setMarkdown(it, raw)
                } else {
                    it.text = b.streamRenderer.render(raw)
                }
            }
            keepBubbleWidth(b, it)
            RoundedTablePlugin.attachInvalidators(it)
            // 输出完成渐亮: 暗色态(打字中)段在此亮起, 恢复渲染/从未暗色的段不受影响
            if (b.dimmed) brightenBlock(b, it)
            // 收尾贴底已去除(2026-09-23): 输出完成后不再强制滚动, 用户自由阅读
        }
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
        sealStatusSummary()   // 收尾定格: 状态行定格为"💭 已思考X字 · 🔧 N个工具"
        // 未封段的正文章节在此一并收尾(整体完成不再追加)
        activeContent?.let { it.done = true }
        activeContent = null
        for (b in contentBlocks) {
            b.done = true
            if (!b.typeActive) finishTypeRender(b)   // 未在打字(从未收到正文或已打完): 直接排版
            // 正在打字的段: done 后由帧回调追上剩余字符自动收尾
        }
    }

    /** 收尾定格: 停轮播藏计数, 状态行定格为摘要(可点击展开时间线);
     *  无思考且无工具(纯正文)则整行移除 */
    private fun sealStatusSummary() {
        statusSealed = true
        cancelCarousel()
        val thinkChars = thinkingBlocks.sumOf { it.count }
        val tools = toolBlocks.size
        if (thinkChars <= 0 && tools <= 0) {
            removeStatusRow()
            return
        }
        statusCounter?.visibility = View.GONE
        lastSnippet = statusSummaryText(thinkChars, tools)
        statusSwitcher?.setText(lastSnippet)
    }

    private fun removeStatusRow() {
        cancelCarousel()
        statusWrap?.let { w -> (w.parent as? ViewGroup)?.removeView(w) }
        statusWrap = null
        statusCol = null
        statusSwitcher = null
        statusCounter = null
        timelineView = null
        statusExpanded = false
        lastSnippet = null
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
                is ContentBlock -> if (e.text.isNotBlank()) sb.append('{')   // 空白段不持久化(09-24 空气泡修复)
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
        removeStatusRow()   // 出错: 状态行(轮播/计数)整体移除, 不再残留活动指示
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
