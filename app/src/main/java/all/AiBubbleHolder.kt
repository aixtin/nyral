package io.github.aixtin.nyral

import android.util.Log
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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

/** 收尾/历史摘要文案: "已思考X字 · N个工具"(缺项自动省略) */
internal fun statusSummaryText(thinkChars: Int, toolCount: Int): String = buildString {
    if (thinkChars > 0) append("已思考").append(thinkChars).append("字")
    if (toolCount > 0) {
        if (isNotEmpty()) append(" · ")
        append(toolCount).append("个工具")
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
        // 左列脉络: 珠子行顶, 竖线从珠子下方留白处起(线不穿珠), 行间空隙处连续
        val spine = FrameLayout(host)
        spine.clipChildren = false   // 中间珠子负边距上移出界可见
        val iconH = host.dp(18)
        val beadGap = host.dp(4)   // 珠子下方留白: 线不贴珠子(给表情留一段)
        val beadTop = 0   // 珠子行顶: 行顶=上一行底, 中间珠子自然嵌在行间空隙(无负边距, 不被裁剪)
        // 竖线: 从珠子底边下方留白处起(跟随珠子); 非末行下端避开下一行上移的珠子
        spine.addView(View(host).apply { setBackgroundColor(lineColor) },
            FrameLayout.LayoutParams(host.dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = beadTop + iconH + beadGap
                bottomMargin = beadGap   // 每行下端留白, 行间珠子处断开(不穿珠)
            })
        val bead = ImageView(host).apply {
            val ic = if (e.type == "think") Ui.lucideBrain(host, THINK_TEXT, 14)
            else Ui.lucideWrench(host, THINK_TEXT, 14)
            setImageDrawable(ic)
            contentDescription = if (e.type == "think") "思考" else "工具"
        }
        spine.addView(bead, FrameLayout.LayoutParams(iconH, iconH).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = beadTop
        })
        // 内容块: 与原思考/工具深色气泡同款
        val body = TextView(host).apply {
            textSize = 14f
            setTextColor(THINK_TEXT)
            maxWidth = host.chatMaxW()
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
            text = if (e.type == "think") {
                if (e.text.isBlank()) "…" else e.text
            } else {
                buildString {
                    append("工具：").append(e.name)
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
            clipChildren = false   // 中间珠子负边距上移出界可见
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(spine, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = host.dp(4)
            topMargin = if (i == 0) host.dp(10) else host.dp(20)   // 首行内容在珠子下10dp; 中间行内容在珠子下20dp(珠子18+2留白)
            bottomMargin = 0   // 行间空隙由下一行 body topMargin 承担, 此处不留行底空隙
        })
        box.addView(row)
    }
    // 轨道末尾: route 结尾标记(流程终点), 与珠子同列对齐(行顶)(09-25)
    val endRow = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 0   // route 紧贴末行收尾, 底部不留空白行
        }
    }
    val left = FrameLayout(host)
    endRow.addView(left, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
    val ic = ImageView(host).apply {
        setImageDrawable(Ui.lucideRoute(host, THINK_TEXT, 16))
        contentDescription = "完成"
    }
    left.addView(ic, FrameLayout.LayoutParams(host.dp(18), host.dp(18)).apply {
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
    })
    box.addView(endRow)
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
        var lastRenderNs = 0L          // 上次真正 setText 渲染的时间戳(批量渲染节流, 消除蹦迪)
        val streamRenderer = MdStreamRenderer()  // D路线: 流式 MD 渲染(块缓存+截断尾部)
        // 静默分层(阶段4): 正文输出期不上屏, 排版与 UI 解耦——封段/收尾生成成品暂存, 收尾原位显现
        var renderedSilent = false              // 是否已在静默期排版暂存(pendingBlocks)
        var pendingBlocks: List<MdRenderBlock>? = null // 方案B 块化排版产物暂存(收尾直接上屏, 不再二次排版)
        var blocksView: MdBlocksView? = null    // 方案B 块化成品视图(表格/文本独立子块)
        var pendingSpanned: CharSequence? = null // 旧路径排版产物暂存(附件 att:// 用, 收尾直接上屏)
        var revealed = false                    // 收尾显现动画已执行(三点淡出+文字淡入)
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
    // 墨水节奏(方案2): 瞬时速率环形窗(最近 4 次 delta 的字符数/间隔),
    // 区别于钝 EMA, 直接反映"模型这一瞬在快吐还是停顿", 供打字机弹性映射
    private val instChars = DoubleArray(4)
    private val instDts = DoubleArray(4)
    private var instHead = 0
    private var instFill = 0
    private var charBudget = 0.0         // 浮点字符预算累积器(严格按速率推进, 消除"每帧+1"的强制快打)

    // 帧级节流: 高频 delta 合并到 16ms 一帧刷新一次, 避免全量 setText + 滚动积压导致卡顿/拖影
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshPending = false

    // ==== 单行状态行(09-24 重构): 流式期间思考/工具不再建独立大气泡, 统一进单行状态行 ====
    // 形态: [💭/🔧 片段轮播(TextSwitcher 淡入淡出)] + [字数/工具计数实时跳动], 点击原地展开脉络时间线
    // statusCol = chatWrap 外层行内的竖容器(状态行+展开的时间线同属一个头像行)
    private var statusCol: LinearLayout? = null
    private var statusWrap: View? = null         // 状态行整行挂到 bubbleBox 的实际 view(chatWrap 外层)
    // ==== 行动轨道(方案A 原型): 时间轴分轨 —— 行动珠子轨道 + 无框文本轨道 ====
    // 开启 actionTrack 时, 思考/工具不再折叠进轮播状态行, 而是沿时间轴实时补珠子;
    // 正文走无框文本轨道(去气泡), 打字机机制原样复用。
    private var trackCol: LinearLayout? = null   // 轨道容器(行动行/正文无框行的纵向容器)
    private var trackEndRow: View? = null        // 轨道末尾 route 结尾标记行(流程终点)
    private var trackWrap: View? = null          // 轨道整条挂到 bubbleBox 的实际 view(chatWrap 外层)
    private var statusSwitcher: TextSwitcher? = null
    private var statusCounter: TextView? = null
    private var dbgCnt = 0
    private var statusExpanded = false          // 状态行是否已展开脉络时间线
    private var statusToggleAnim: ValueAnimator? = null  // 展开/收起动画(09-24)
    private var timelineView: View? = null      // 展开的时间线容器
    private var carouselRunnable: Runnable? = null   // 片段轮播驱动
    private var lastSnippet: String? = null     // 当前展示片段(相同不重切, 避免动画空转)
    private var lastThinkChars = 0              // 思考封段时的字数(完成→收尾间计数过渡展示)
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
        var titleView: TextView? = null   // 行动轨道: 标题行(尾部摘要, 实时刷新)
        val count: Int get() = text.codePointCount(0, text.length)
    }

    /** 一次工具调用: 名称/参数/结果进脉络时间线 */
    private inner class ToolBlock(val name: String, val arg: String) {
        var result: String? = null
        var tlView: TextView? = null   // 脉络时间线里的详情块(展开状态下结果回填实时刷新)
        var titleView: TextView? = null   // 行动轨道: 标题行(工具名)
        fun expandedText(): String = buildString {
            append("工具：$name")
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
        // 跳过尾部空段: 思考文本常以 "\n\n" 分隔/结尾, 直接取末段会拿到空串
        // → snippet 走 ifBlank 兜底成 "…", 状态行永远三个点
        val full = b.text.toString()
        var s = full
        while (true) {
            val idx = s.lastIndexOf("\n\n")
            if (idx < 0) break
            val tail = s.substring(idx + 2).trim()
            if (tail.isNotEmpty()) { s = tail; break }
            s = s.substring(0, idx)
        }
        return s.trim().ifEmpty { full.trim() }
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
        // v8.7 呼吸闪烁降噪: 原三点错相位无限呼吸(900ms 循环 alpha 0.25~1.0)在聊天模式下
        // 与头像气泡相邻, 视觉呈"头像跟气泡呼吸式闪烁"(用户反馈); 改为静态三点保持加载语义,
        // 消息到达后由 stopLoading 移除, 不再循环明暗
        loadingAnim = null
        loadingRow = addChatBubble(makeDotsRow())
        android.util.Log.i("Nyral", "startLoading blocks=" + contentBlocks.size)
    }

    /** 三点占位行(等待/正文静默共用): 请求期由 showLoading 挂 loadingRow;
     *  正文输出期由 ensureBodyDots 接管为 bodyDots, 收尾原位淡出 */
    private fun makeDotsRow(): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(2), host.dp(8), host.dp(2), host.dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        repeat(3) { i ->
            row.addView(View(host).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(THINK_TEXT)
                }
                val lp = LinearLayout.LayoutParams(host.dp(6), host.dp(6))
                if (i > 0) lp.leftMargin = host.dp(5)
                layoutParams = lp
            })
        }
        return row
    }

    private var bodyDots: View? = null   // 静默分层: 正文输出期三点占位(收尾原位切换成真实气泡)

    /** 静默分层: 确保正文三点占位在气泡盒末尾(请求期 loadingRow 直接接管, 避免重建闪烁) */
    private fun ensureBodyDots() {
        if (bodyDots != null) return
        if (loadingRow != null) {
            bodyDots = loadingRow
            loadingRow = null
            return
        }
        bodyDots = addChatBubble(makeDotsRow())
    }

    private fun removeBodyDots() {
        bodyDots?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bodyDots = null
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
        if (ModeConfig.actionTrack()) {
            appendTrackEvent(b)   // 行动轨道: 实时补思考珠子
        } else {
            ensureStatusRow()
            showSnippet()   // 思考事件即时上屏, 不等轮播周期
            driveCarousel()
        }
        scheduleRefresh()
        host.onStatusGrown()   // 状态行出现/移动: 通知主层锚底跟随(09-24)
    }

    fun appendThinking(text: String) {
        activeThinking?.let { it.text.append(text) }
        if (ModeConfig.actionTrack()) {
            // 行动轨道: 标题行实时滚动尾部摘要(保留轮播感), 展开态全文同步
            activeThinking?.titleView?.let { tv ->
                val s = snippet(tailThinking(activeThinking!!).replace("\n", " ").trim())
                if (tv.text.toString() != s) tv.text = s
            }
            activeThinking?.tlView?.let { it.text = activeThinking!!.text.toString() }
        }
        scheduleRefresh()
        host.onStatusGrown()   // 思考内容增长: 通知主层锚底跟随(09-24)
    }

    /** 思考段结束: 封段等待下一事件(正文/工具/新思考), 状态行保持轮播 */
    fun collapseThinking() {
        val b = activeThinking ?: return
        if (b.collapsed) return
        b.collapsed = true
        lastThinkChars = b.count   // 记住封段字数: 计数从"N字"过渡到"已思考N字", 不瞬间断尾
        activeThinking = null
        // 状态行形态下状态行本身即"生成中"指示, 不再另起三点加载行
    }

    // ===================== 行动轨道(方案A 原型) =====================

    /** 确保行动轨道容器存在并停在 bubbleBox 末尾(跟随最新活动) */
    private fun ensureTrackRow() {
        if (trackCol != null) {
            // 已存在: 整条移到末尾, 新一轮思考/工具时轨道跟随最新输出位置
            trackWrap?.let { w ->
                (w.parent as? ViewGroup)?.let { p ->
                    p.removeView(w)
                    bubbleBox?.addView(w)
                }
            }
            return
        }
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        trackCol = col
        trackWrap = addChatBubble(col)
    }

    /** 行动轨道: 追加一行事件(思考💭/工具🔧), 复用 spine/bead 语言(竖线穿内容行+珠子悬于行间空隙);
     *  标题行默认展示(尾部摘要/工具名), 点击标题或珠子展开/收起详情块 */
    private fun appendTrackEvent(b: Any) {
        ensureTrackRow()
        val col = trackCol ?: return
        var thinkB: ThinkingBlock? = null
        var toolB: ToolBlock? = null
        val titleText: String
        val detailText: String
        when (b) {
            is ThinkingBlock -> {
                thinkB = b
                titleText = snippet(tailThinking(b).replace("\n", " ").trim())
                detailText = b.text.toString()
            }
            is ToolBlock -> {
                toolB = b
                titleText = b.name
                detailText = b.expandedText()
            }
            else -> return
        }
        val iconH = host.dp(18)
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false   // 中间珠子负边距上移出界可见
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // 左列 spine: 珠子行顶, 竖线从珠子底边下方留白处起(跟随珠子, 线不穿珠)
        val spine = FrameLayout(host)
        spine.clipChildren = false   // 中间珠子负边距上移出界可见
        val lineColor = (THINK_TEXT and 0x00FFFFFF) or (0x55 shl 24)
        val beadGap = host.dp(4)   // 珠子下方留白: 线不贴珠子(给表情留一段)
        val beadTop = 0   // 珠子行顶: 行顶=上一行底, 中间珠子自然嵌在行间空隙(无负边距, 不被裁剪)
        // 竖线: 从珠子底边下方留白处起(跟随珠子); 本行为当前末行, 下端留白 4dp(不再被视作末行时再增大)
        spine.addView(View(host).apply { setBackgroundColor(lineColor) },
            FrameLayout.LayoutParams(host.dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = beadTop + iconH + beadGap
                bottomMargin = beadGap
            })
        val bead = ImageView(host).apply {
            val ic = if (b is ThinkingBlock) Ui.lucideBrain(host, THINK_TEXT, 15)
            else Ui.lucideWrench(host, THINK_TEXT, 15)
            setImageDrawable(ic)
            contentDescription = if (b is ThinkingBlock) "思考" else "工具"
        }
        spine.addView(bead, FrameLayout.LayoutParams(iconH, iconH).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = beadTop
        })
        // 右列 body: 标题行 + 详情块(默认收起, 点击展开)
        val bodyCol = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = host.dp(4)
                topMargin = if (col.childCount == 0) host.dp(10) else host.dp(20)   // 首行内容在珠子下10dp; 中间行内容在珠子下20dp(珠子18+2留白)
                bottomMargin = 0   // 行间空隙由下一行 body topMargin 承担, 此处不留行底空隙
            }
        }
        val title = TextView(host).apply {
            text = titleText
            textSize = 14f
            setTextColor(THINK_TEXT)
            maxWidth = host.chatMaxW() - host.dp(24)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(host.dp(4), host.dp(2), host.dp(4), host.dp(2))
        }
        val detail = TextView(host).apply {
            textSize = 14f
            setTextColor(THINK_TEXT)
            maxWidth = host.chatMaxW() - host.dp(24)
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
            text = detailText
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        val toggle: () -> Unit = {
            detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            host.onStatusGrown()   // 展开/收起高度变化: 通知主层锚底跟随
        }
        title.setOnClickListener { toggle() }
        bead.setOnClickListener { toggle() }
        bodyCol.addView(title)
        bodyCol.addView(detail)
        row.addView(spine, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(bodyCol, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 行间空隙由 body topMargin 承担, 行底不再留空隙(无需旧末行恢复)
        col.addView(row)
        ensureTrackEnd(col)   // 追加事件后把 route 结尾标记重挂到轨道最末
        // 末行(route 前最后事件行)紧贴 route 收尾: 去掉行间空隙, 底部不留多余空白行
        val evtCnt = col.childCount
        if (evtCnt >= 3) {
            val lastLp = (col.getChildAt(evtCnt - 2) as? LinearLayout)
                ?.getChildAt(1)?.layoutParams as? LinearLayout.LayoutParams
            lastLp?.bottomMargin = 0
        }
        if (thinkB != null) { thinkB.titleView = title; thinkB.tlView = detail }
        if (toolB != null) { toolB.titleView = title; toolB.tlView = detail }
    }

    /** 行动轨道末尾: route 结尾标记(流程终点), 与珠子同列对齐(行顶), 每次追加事件后重挂到最末 */
    private fun ensureTrackEnd(col: LinearLayout) {
        trackEndRow?.let { col.removeView(it) }
        val iconH = host.dp(18)
        val end = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 0   // route 紧贴末行收尾, 底部不留空白行
            }
        }
        val left = FrameLayout(host)
        end.addView(left, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
        val ic = ImageView(host).apply {
            setImageDrawable(Ui.lucideRoute(host, THINK_TEXT, 16))
            contentDescription = "完成"
        }
        left.addView(ic, FrameLayout.LayoutParams(iconH, iconH).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        })
        col.addView(end)
        trackEndRow = end
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
                        // 宽度封顶(行整体不超 chatMaxW 且给右侧计数预留空间):
                        // LinearLayout 无 maxWidth, 移到内部 TextView; 若片段撑满 chatMaxW,
                        // 横向布局会把 wrap 计数 TextView 压成 AT_MOST 窄条导致"已思考N字"竖排(09-25)
                        maxWidth = host.chatMaxW() - host.dp(96)
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    }
                }
                inAnimation = AlphaAnimation(0f, 1f).apply { duration = (180L * TypewriterCenter.slowMul()).toLong() }
                outAnimation = AlphaAnimation(1f, 0f).apply { duration = (180L * TypewriterCenter.slowMul()).toLong() }
                // 固定最小宽: 片段文本长短变化时行宽稳定不抖
                minimumWidth = host.dp(64)   // 只兜住短片段("💭 …"量级), 避免 140dp 撑出大片右侧留白
            }
            val counter = TextView(host).apply {
                textSize = 12f
                setTextColor(THINK_TEXT)
                maxLines = 1   // 兜底: 即使被压缩也不竖排(正常态有 sw maxWidth 预留, 不会触发截断)
                ellipsize = TextUtils.TruncateAt.END
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
        // 挂载前先填充首帧内容(setCurrentText 无动画): 避免空壳先上屏、内容随后淡入(慢放下呈"空气泡先出现")
        statusCol = c
        statusSwitcher = shell.getChildAt(0) as? TextSwitcher
        statusCounter = shell.getChildAt(1) as? TextView
        lastSnippet = null
        lastThinkChars = 0
        currentSnippet()?.let { statusSwitcher?.setCurrentText(it); lastSnippet = it }
        updateStatusCounter()
        statusWrap = addChatBubble(c)
    }

    /** 当前应展示的片段: 活跃思考 tail / 最新工具(无结果=参数, 有结果=结果) */
    private fun currentSnippet(): String? {
        activeThinking?.let { b ->
            if (!b.collapsed) {
                return snippet(tailThinking(b).replace("\n", " ").trim())
            }
        }
        toolBlocks.lastOrNull()?.let { tb ->
            val r = tb.result?.trim().orEmpty()
            return if (r.isEmpty()) "${tb.name} · ${snippet(tb.arg)}"
            else "${tb.name} → ${snippet(r)}"
        }
        thinkingBlocks.lastOrNull()?.let {
            return snippet(tailThinking(it).replace("\n", " ").trim())
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
        uiHandler.postDelayed(r, (STATUS_CAROUSEL_MS * TypewriterCenter.slowMul()).toLong())
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
        else if (toolBlocks.isNotEmpty()) "工具 ${toolBlocks.size}"
        else if (lastThinkChars > 0) "已思考${lastThinkChars}字"
        else null
        if (t != null) { tv.text = t; tv.visibility = View.VISIBLE } else tv.visibility = View.GONE
    }

    /** 状态行展开/收起动画时长(ms): 跟随全局慢放倍数, 慢放时时间线展开同步变慢(09-24) */
    private fun statusToggleAnimMs(): Long =
        (220L * TypewriterCenter.slowMul()).toLong().coerceAtLeast(1L)

    /** 状态行点击: 原地展开/收起脉络时间线(竖线+圆点+思考/工具块, 非弹窗) */
    private fun toggleStatusExpand() {
        Log.d("SlowDbg", "statusToggle expand=" + statusExpanded + " slowMul=" + TypewriterCenter.slowMul() + " dur=" + statusToggleAnimMs())
        val col = statusCol ?: return
        host.markUserTakeover()   // 展开/收起视同用户接管, 防自动滚动追底(09-24)
        statusToggleAnim?.cancel()
        val colH0 = col.height
        val colBaseY = IntArray(2).also { col.getLocationInWindow(it) }[1]
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
            val tl = timelineView ?: return
            // 预测量 wrap 高度(尚未 addView, 用 col 宽度约束)
            tl.measure(
                View.MeasureSpec.makeMeasureSpec(col.width - col.paddingLeft - col.paddingRight, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetH = tl.measuredHeight.coerceAtLeast(1)
            val lp = tl.layoutParams
            col.addView(tl, 1)
            lp.height = 0
            tl.alpha = 0f
            val anim = ValueAnimator.ofInt(0, targetH).apply {
                duration = statusToggleAnimMs()
                interpolator = DecelerateInterpolator()
                addUpdateListener { v ->
                    lp.height = v.animatedValue as Int
                    tl.alpha = v.animatedFraction.coerceIn(0f, 1f)
                    col.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        tl.alpha = 1f
                        statusToggleAnim = null
                        host.compensateStatusToggle(col, colH0, colBaseY)
                    }
                    override fun onAnimationCancel(a: Animator) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        tl.alpha = 1f
                        statusToggleAnim = null
                    }
                })
            }
            statusToggleAnim = anim
            anim.start()
        } else {
            val tl = timelineView
            if (tl != null && tl.parent === col) {
                val startH = tl.height.coerceAtLeast(1)
                val anim = ValueAnimator.ofInt(startH, 0).apply {
                    duration = statusToggleAnimMs()
                    interpolator = AccelerateInterpolator()
                    addUpdateListener { v ->
                        tl.layoutParams.height = v.animatedValue as Int
                        tl.alpha = (1f - v.animatedFraction).coerceIn(0f, 1f)
                        col.requestLayout()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: Animator) {
                            col.removeView(tl)
                            statusToggleAnim = null
                            host.compensateStatusToggle(col, colH0, colBaseY)
                        }
                        override fun onAnimationCancel(a: Animator) {
                            statusToggleAnim = null
                        }
                    })
                }
                statusToggleAnim = anim
                anim.start()
            } else {
                host.compensateStatusToggle(col, colH0, colBaseY)
            }
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
                    val t = e.text.toString()
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
        if (ModeConfig.actionTrack()) {
            appendTrackEvent(b)   // 行动轨道: 实时补工具珠子
        } else {
            ensureStatusRow()
            showSnippet()   // 工具事件即时上屏
            driveCarousel()
        }
        host.onStatusGrown()   // 工具状态行出现/移动: 通知主层锚底跟随(09-24)
    }

    /** 工具结果回填: 展开态刷新时间线块, 片段即时切到结果 */
    fun setToolResult(name: String, result: String) {
        val b = toolBlocks.lastOrNull() ?: return
        b.result = result
        b.tlView?.let { it.text = b.expandedText() }
        if (ModeConfig.actionTrack()) {
            // 行动轨道: 标题加完成标记(右侧 badge-check 图标), 详情含结果(展开可见)
            b.titleView?.let { tv ->
                val s = b.name
                if (tv.text.toString() != s) {
                    tv.text = s
                    tv.setCompoundDrawablesWithIntrinsicBounds(null, null, Ui.lucideBadgeCheck(host, THINK_TEXT, 14), null)
                    tv.compoundDrawablePadding = host.dp(3)
                }
            }
        } else {
            showSnippet()
        }
        host.onStatusGrown()   // 工具结果回填: 通知主层锚底跟随(09-24)
    }

    /** 冻结当前正文段(一段确定不再追加): 思考/工具插入前调用; 无正文或已冻结时忽略 */
    private fun sealCurrentContent() {
        val b = activeContent ?: return
        if (b.done) return
        b.done = true
        activeContent = null
        // 静默分层(阶段4): 封段不再立即排版上屏——排版暂存(渲染压力分散), UI 保持三点占位;
        // 挂空占位保持时间线顺序(其后可能有思考/工具/新正文段)
        if (b.text.isNotBlank()) {
            sealContentPlaceholder(b)
            renderSilent(b)
        }
        // 空气泡修复(09-24): 全空白且无视图的段(懒建跳过的 preamble)直接丢弃,
        // 不进收尾/时间线, 防止空段持久化后历史重建再渲染出空气泡
        if (b.view == null && b.text.isBlank()) {
            contentBlocks.remove(b)
            timelineEvents.remove(b)
        }
    }

    fun appendContent(text: String) {
        removeStatus()
        // 静默分层(阶段4): 正文输出期不停止三点占位——内容静默累积后台排版,
        // 用户视野保持"正在输入"; 占位在收尾原位切换成真实气泡+打字显现
        ensureBodyDots()
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
            // 静默分层: 视图挂载进一步推迟——未封段段收尾才挂, 封段段由 sealContentPlaceholder 挂占位
            timelineEvents.add(b)
        }
        b.text.append(text)
        // 阶段2 超长分片: 累计超过阈值立即封段另起气泡(渲染压力分散——静默期排版暂存,
        // 收尾直接上屏), 避免单段超大 TextView 收尾一次性全量 markdown 解析卡顿
        if (b.text.length >= SPLIT_CONTENT_LEN) {
            b.done = true
            activeContent = null
            sealContentPlaceholder(b)   // 挂占位保持时间线顺序
            renderSilent(b)             // 静默排版暂存
        }
        // 跟踪模型吐字速率: 用本次 delta 的字符数/间隔 更新 EMA(字符/秒), 供 tickFrame 自适应打字速度
        val nowNs = System.nanoTime()
        if (lastDeltaNs != 0L) {
            val dt = (nowNs - lastDeltaNs) / 1_000_000_000.0
            if (dt > 0.001) {
                val inst = text.length / dt
                modelRate = if (modelRate <= 0) inst else modelRate * 0.7 + inst * 0.3
                // 墨水节奏(方案2): 环形窗记录 (字符数, 间隔), 覆盖最旧采样
                instChars[instHead] = text.length.toDouble()
                instDts[instHead] = dt
                instHead = (instHead + 1) % instChars.size
                if (instFill < instChars.size) instFill++
            }
        }
        lastDeltaNs = nowNs
        // 静默分层(阶段4): 不启动打字机(TypewriterCenter)——正文不外显半成品,
        // 收尾排版完成后占位原位切换+打字显现(alpha 淡入), 打字机节奏逻辑整体让位
    }

    /** 懒挂正文气泡视图(09-24 空气泡修复): 首个非空白字符到达才创建, 挂载后立即渲染
     *  已推进的打字机进度(空白期 shownLen 已推进但无视图可渲), 并通知主层正文顶定位。
     *  静默分层(阶段4): 视图挂载推迟到收尾/封段占位, 本函数仅收尾成品挂载使用,
     *  不再显示打字机进度(静默期无打字), 内容由 finishTypeRender 一次性填充 */
    private fun attachContentView(b: ContentBlock): TextView? {
        val tv = TextView(host).apply {
            textSize = 15f
            setTextColor(BUBBLE_AI_TEXT)
            setLineSpacing(host.dp(3).toFloat(), 1f)
            includeFontPadding = false
            if (ModeConfig.actionTrack()) {
                // 行动轨道(方案A): 正文走无框文本轨道, 缩进到珠子列右侧, 打字机照旧
                setPadding(host.dp(4), host.dp(2), host.dp(4), host.dp(2))
                maxWidth = maxW - host.dp(24)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = host.dp(24)   // 与行动行珠子列对齐(spine 20dp + margin 4dp)
                    topMargin = host.dp(2)
                    bottomMargin = host.dp(6)
                }
            } else {
                setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                background = rounded(host.dp(12), floatBubbleColor(BUBBLE_AI))
                maxWidth = maxW
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = host.dp(6)
                    bottomMargin = host.dp(4)
                }
            }
        }
        val block = b
        host.makeCopyable(tv) {
            val rawB = ModeConfig.stripChatProtocolPrefix(block.text.toString())
            if (ModeConfig.chatPlainText()) stripMarkdownForChat(rawB) else rawB
        }
        b.view = tv
        addChatBubble(tv)
        return tv
    }

    /** 是否存在正在打字(未收尾)的正文段: 供 MainActivity.scrollToBottom 判断打字期分支 */
    fun hasActiveTypewriter(): Boolean = contentBlocks.any { it.typeActive }

    // ===== 静默分层(阶段4): 正文输出期排版与 UI 解耦, 收尾原位显现 =====

    /** 封段占位: 段在输出中被截断(思考/工具/超长分片插入)时, 挂空占位保持时间线顺序,
     *  成品留待收尾 revealContent 原位显现; 未挂过视图且非空白才挂(空白段走空气泡修复丢弃) */
    private fun sealContentPlaceholder(b: ContentBlock) {
        if (b.view != null || b.text.isBlank()) return
        val ph = TextView(host).apply {
            textSize = 15f
            setTextColor(BUBBLE_AI_TEXT)
            setLineSpacing(host.dp(3).toFloat(), 1f)
            includeFontPadding = false
            if (ModeConfig.actionTrack()) {
                setPadding(host.dp(4), host.dp(2), host.dp(4), host.dp(2))
                maxWidth = maxW - host.dp(24)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = host.dp(24)
                    topMargin = host.dp(2)
                    bottomMargin = host.dp(6)
                }
            } else {
                setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                background = rounded(host.dp(12), floatBubbleColor(BUBBLE_AI))
                maxWidth = maxW
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = host.dp(6)
                    bottomMargin = host.dp(4)
                }
            }
        }
        b.view = ph
        addChatBubble(ph)
        b.shownLen = b.text.length   // 占位即全文: 封段段收尾不再有打字节奏(打字机已让位)
    }

    /** 静默排版(方案B 块化): 完整文本块化解析一次暂存(pendingBlocks), 收尾直接上屏不再二次排版;
     *  含附件(att://)因卡片挂载依赖视图, 推迟到收尾由 finishTypeRender 走 markwon */
    private fun renderSilent(b: ContentBlock) {
        if (b.renderedSilent || b.text.isBlank()) return
        b.renderedSilent = true
        if (ModeConfig.chatPlainText()) return
        val stripped = ModeConfig.stripChatProtocolPrefix(b.text.toString()).trim()
        if (stripped.isEmpty()) return
        if (stripped.contains("att://")) return   // 附件走收尾 markwon(需视图挂卡片)
        // 方案B: 文本流统一转块列表(文本块/表格块), 表格不再走 RoundedTableRowSpan 自绘
        b.pendingBlocks = MdToBlocks.render(stripped)
    }

    /** 收尾显现: 段成品原位上屏(占位淡出+文字淡入), 每段只执行一次;
     *  方案B 块化: pendingBlocks 非空 -> 占位淡出后挂 MdBlocksView 成品(块化布局),
     *  无块化(att:///异常)由 finishTypeRender 先上屏, 本函数兜底直接可见(无闪帧) */
    private fun revealContent(b: ContentBlock) {
        if (b.revealed) return
        b.revealed = true
        val tv = b.view ?: return
        val blocks = b.pendingBlocks
        if (blocks != null) {
            // 块化成品: 空占位(alpha 1, 无内容)淡出 -> 块容器淡入原位切换
            android.util.Log.i("NyralTbl", "reveal blocks=" + blocks.size + " tvAttached=" + tv.isAttachedToWindow + " tvAlpha=" + tv.alpha)
            val fadeOut = tv.animate().alpha(0f).setDuration(120).setInterpolator(DecelerateInterpolator())
            fadeOut.withEndAction {
                android.util.Log.i("NyralTbl", "fadeEnd start replace")
                val bv = ensureBlocksView(b, tv)
                bv.bindBlocks(blocks)
                keepBubbleWidth(b, bv)
                bv.animate().alpha(1f).setDuration(140).setInterpolator(AccelerateInterpolator()).start()
                android.util.Log.i("NyralTbl", "fadeEnd done bvAlpha=" + bv.alpha + " bvAttached=" + bv.isAttachedToWindow)
            }
            fadeOut.start()
        } else if (b.pendingSpanned != null) {
            // 旧路径暂存(att:// 走 markwon 等): 占位淡出 -> 成品淡入
            val spanned = b.pendingSpanned
            val fadeOut = tv.animate().alpha(0f).setDuration(120).setInterpolator(DecelerateInterpolator())
            fadeOut.withEndAction {
                tv.text = spanned
                keepBubbleWidth(b, tv)
                tv.animate().alpha(1f).setDuration(140).setInterpolator(AccelerateInterpolator()).start()
            }
            fadeOut.start()
        } else {
            // 无暂存段(att:// 走 markwon 上屏等): 文本已就位, 直接可见, 不做二次动画
            tv.alpha = 1f
        }
        removeBodyDots()
    }

    /** 方案B: 把当前占位 TextView 原位替换为 MdBlocksView 块容器(气泡外壳与占位一致),
     *  已创建则直接复用(幂等); 边框/表头底色与 mdTableTheme 同源(Ui.PRIMARY/Ui.INPUT_BG) */
    private fun ensureBlocksView(b: ContentBlock, tv: TextView): MdBlocksView {
        b.blocksView?.let { return it }
        val bv = MdBlocksView(host).apply {
            if (ModeConfig.actionTrack()) {
                // 行动轨道(方案A): 正文无框文本轨道, 块容器缩进与占位一致
                setPadding(host.dp(4), host.dp(2), host.dp(4), host.dp(2))
                layoutParams = tv.layoutParams
            } else {
                setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                background = rounded(host.dp(12), floatBubbleColor(BUBBLE_AI))
                layoutParams = tv.layoutParams
            }
        }
        // 替换: 占位淡出后摘除原 TextView, 在原位挂块容器(保持气泡顺序/边距)
        val parent = tv.parent as? ViewGroup
        android.util.Log.i("NyralTbl", "ensureBV parent=" + (parent?.javaClass?.simpleName ?: "NULL") + " tvAttached=" + tv.isAttachedToWindow + " hasBv=" + (b.blocksView != null))
        if (parent != null) {
            val idx = parent.indexOfChild(tv)
            parent.removeView(tv)
            parent.addView(bv, idx)
            android.util.Log.i("NyralTbl", "REPLACED idx=" + idx + " bvAttached=" + bv.isAttachedToWindow + " bvAlpha=" + bv.alpha)
        } else {
            android.util.Log.i("NyralTbl", "PARENT_NULL skipReplace")
        }
        // 可用宽 = 占位 maxWidth - 块容器自身左右 padding(表格实际可摆放空间,
        // 否则表格按未减 padding 的宽度计算 -> 恒超宽被横滑裁剪, 右边框缺失)
        val padH = if (ModeConfig.actionTrack()) host.dp(8) else host.dp(24)
        bv.setRenderContext(tv.maxWidth, tv.maxWidth - padH, BUBBLE_AI_TEXT, Ui.PRIMARY, Ui.INPUT_BG)
        // 长按复制(2026-10-02): 块容器取代占位 TextView 后原 makeCopyable 监听随 TextView 摘除,
        // 需在块容器上重建长按入口(与用户侧一致弹原文本对话框)
        val rawB = ModeConfig.stripChatProtocolPrefix(b.text.toString())
        bv.setOnLongClickListener {
            if (host.aiTypewriting()) return@setOnLongClickListener false
            host.openRawText(rawB)
            true
        }
        bv.isLongClickable = true
        b.blocksView = bv
        return bv
    }

    /** 静默分层正文快照(持久化用): 富文本上屏前取其文本, 收尾显现后直接取成品文本 */
    fun contentSnapshot(): String {
        val sb = StringBuilder()
        for (b in contentBlocks) {
            if (b.text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            // 方案B: 块化段成品在 blocksView, 原占位已摘除(无 text), 直接取原始 MD(所见即原文);
            // 未块化段沿用旧逻辑(成品 text 优先)
            if (b.blocksView != null) {
                sb.append(ModeConfig.stripChatProtocolPrefix(b.text.toString()).trim())
                continue
            }
            val v = b.view   // 局部捕获: 成员 var 不能智能转换
            if (v != null && b.revealed) {
                sb.append(v.text?.toString() ?: b.text.toString())
            } else {
                sb.append(ModeConfig.stripChatProtocolPrefix(b.text.toString()).trim())
            }
        }
        return sb.toString()
    }

    /** 静默分层成品快照(写回 rendered 用): 与 finishTypeRender 同一排版路径, 保证落库=所见;
     *  方案B: 含表格段块化渲染, span 快照无意义 -> 落原始 MD(恢复时重新块化);
     *  无表格段保持 markwon/streamRenderer 富文本快照(恢复零解析) */
    fun renderedSnapshot(): CharSequence? {
        if (ModeConfig.chatPlainText()) return null
        // 方案B 收尾(10-02): 块化渲染段(TextBlock/TableBlock)无法 span 序列化,
        // 落原始 MD 会在重建时 decode 回显源码 -> 统一不写回, 重建走块化路径
        if (contentBlocks.any { it.pendingBlocks != null }) return null
        var any = false
        val sb = SpannableStringBuilder()
        for (b in contentBlocks) {
            if (b.text.isBlank()) continue
            any = true
            val stripped = ModeConfig.stripChatProtocolPrefix(b.text.toString()).trim()
            if (stripped.isEmpty()) continue
            if (stripped.contains("att://") || b.pendingBlocks?.any { it is MdRenderBlock.TableBlock } == true) {
                // 附件走 markwon 卡片快照? 附件不可复用(att:// 路径渲染依赖视图), 兜底纯文本;
                // 含表格段: 块化成品 -> 落原始 MD, 恢复时重新块化
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(stripped)
                continue
            }
            val tv = b.view
            val spanned = when {
                tv != null && !b.revealed -> {
                    MdSpannable.tableMaxWidth = tv.maxWidth
                    b.streamRenderer.render(stripped)
                }
                else -> null
            }
            val chunk = spanned ?: SpannableStringBuilder(stripped)   // 附件/异常兜底纯文本, 保证落库完整
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(chunk)
        }
        return if (any) sb else null
    }

    // 触摸冻结(09-25 方案2.1): 用户触摸列表(ACTION_DOWN)即冻结打字机渲染/推进,
    // 布局立即稳定, 上滑滚动实时跟手(不再"延迟一下"——原方案1 flush 追平让用户
    // 感觉"摁了没反应", 本版冻结保留当前半截文字, 布局完全静止; 静态(输出完成)
    // 后才允许长按复制窗, 打字中长按不弹窗避免抢占触摸)
    @Volatile var userTakeover = false

    /** 触摸冻结: 置接管标志, stepBlock 直接跳过渲染/推进(布局零变化, 可立即拖动) */
    fun freezeTypewriter() {
        userTakeover = true
    }

    /** 恢复打字: 先一次性追平冻结期间积压的已收文本(布局追上模型), 再继续逐字慢打 */
    fun resumeTypewriter() {
        userTakeover = false
        for (b in contentBlocks) {
            if (b.typeFinishedRender || !b.typeActive) continue
            if (b.shownLen >= b.text.length) continue
            b.shownLen = b.text.length
            b.lastFrameNs = 0L
            b.view?.let { tv ->
                // 09-28 后渲染: 打字期间纯文本(含卡片/表情占位), markdown 富文本收尾一次到位
                tv.text = suppressCards(b.text.toString())
                keepBubbleWidth(b, tv)
            }
            if (b.done) {
                finishTypeRender(b)
                continue
            }
        }
    }

    /** 状态行是否活跃(思考/工具阶段单行状态行在屏): 供 MainActivity 判断状态行锚底(09-24) */
    fun hasStatusRow(): Boolean = statusCol != null

    /** 当前模型吐字速率(字符/秒) EMA 值, 供涌动滚动分支使用 */
    fun currentModelRate(): Double = modelRate

    /** 墨水节奏(方案2): 最近环形窗瞬时速率(字符/秒), 直接反映当前吐字快慢;
     *  窗未填满或全为停顿采样时回落 EMA, 保证起步期不误判 */
    private fun instRate(): Double {
        if (instFill == 0) return modelRate
        var c = 0.0
        var d = 0.0
        for (i in 0 until instFill) {
            val idx = (instHead - 1 - i + instChars.size) % instChars.size
            c += instChars[idx]
            d += instDts[idx]
        }
        return if (d > 0) c / d else modelRate
    }

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
    private fun keepBubbleWidth(b: ContentBlock, tv: View) {
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
        // 触摸冻结(09-25): 用户正在拖动列表, 本帧不渲染不推进, 布局零变化;
        // 积压的已收文本由 resumeTypewriter 一次性追平, 此处只管"停"
        if (userTakeover) {
            b.lastFrameNs = frameNs
            return true
        }
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
        // 墨水节奏(方案2): 打字速率跟随模型瞬时吐字速率弹性波动(2026-09-25 替换原
        // modelRate EMA 钝映射): 模型这一瞬吐得快 -> 打字机提速跟紧, 吐得慢/停顿(思考/
        // 工具间隙) -> 打字机降速半拍, 消除"模型急停/急喷时打字机匀速无视"的脱节感
        var rate = typeSpeed(total).toDouble()
        val ir = instRate()
        if (ir > 0) {
            // 瞬时速率归一映射: 0~12字/秒(停顿/慢吐) 压到基准 0.25 倍, >=80字/秒(涌出) 拉到 1.8 倍,
            // 中间平滑过渡; 上限不封死模型爆发, 下限仍保视觉连续
            val t = ((ir - 12.0) / (80.0 - 12.0)).coerceIn(0.0, 1.0)
            rate *= (0.25 + t * 1.55)
        }
        // 等墨降速(方案2): 距上次收到 delta 超 200ms(模型思考/工具间隙/吐字中断),
        // 打字机随之慢半拍等墨, 恢复吐字后由瞬时窗自然提速 —— 墨水节奏的灵魂
        if (lastDeltaNs != 0L && (frameNs - lastDeltaNs) > 200_000_000L) {
            rate *= 0.35
        }
        rate = maxOf(rate, 16.0)
        // 墨水微抖(方案2): ±20% 随机抖动消除匀速机器感, 让字迹跟随"脑内节奏"呼吸
        rate *= (0.8 + 0.4 * Math.random())
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
        if (dbgCnt % 30 == 0) {
            val vis = if (tv0 != null) { val r = android.graphics.Rect(); tv0.getGlobalVisibleRect(r); r.height() == 0 } else false
            android.util.Log.d("SlowDbg", "step mul=" + TypewriterCenter.slowMul() +
                " rate=" + rate + " ir=" + String.format(java.util.Locale.US, "%.1f", instRate()) +
                " el=" + String.format(java.util.Locale.US, "%.5f", elapsed) +
                " b=" + budget + " shown=" + b.shownLen + "/" + total + " off=" + vis)
        }
        dbgCnt++
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
                // 09-28 后渲染: 打字期间只推纯文本(卡片/表情占位), 不跑 markdown 渲染器;
                // 富文本由 finishTypeRender 收尾一次性渲染 —— 气泡延伸与输出节奏彻底解耦,
                // 消除逐帧 markdown 重排导致的延伸卡顿/高度抖动
                tv.text = suppressCards(b.text.substring(0, b.shownLen))
                keepBubbleWidth(b, tv)
                // 纯文本阶段无 markdown span, 表格 invalidator 收尾渲染时再挂
                // 追底已去除(2026-09-23): 打字期间不再自动滚动, 用户自由阅读; 收尾 force 贴底对齐
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
        b.view?.let { tv ->
            val stripped = ModeConfig.stripChatProtocolPrefix(b.text.toString()).trim()
            // 空气泡兜底(09-25): 整段剥离协议前缀后为空(纯 思考:/TOOL: 行误入正文, 常见于思考->工具间隙),
            // 不再保留空壳气泡: 摘除视图即可(不动 contentBlocks, 避免 tickFrame 遍历中改列表)
            if (stripped.isEmpty() && b.text.isNotBlank()) {
                (tv.parent as? ViewGroup)?.removeView(tv)
                return
            }
            if (ModeConfig.chatPlainText()) {
                tv.text = stripMarkdownForChat(b.text.toString()).trimEnd()
            } else if (stripped.contains("att://")) {
                // 含附件(att://): 卡片挂载依赖视图, 静默期未排版, 收尾走 markwon 渲染真实卡片
                b.streamRenderer.clearCache()
                host.markwon.setMarkdown(tv, stripped)
                host.makeCopyable(tv) { ModeConfig.stripChatProtocolPrefix(b.text.toString()) }
                keepBubbleWidth(b, tv)
            } else {
                // 方案B 块化主路径: 静默期已块化暂存(pendingBlocks)直接上屏, 未暂存现场渲染兜底;
                // 块容器替换占位(内部文本块/表格块独立), 不再走 span 自绘表格
                val blocks = b.pendingBlocks ?: MdToBlocks.render(stripped)
                val bv = ensureBlocksView(b, tv)
                bv.bindBlocks(blocks)
                keepBubbleWidth(b, bv)
            }
            // 输出完成渐亮: 暗色态(打字中)段在此亮起, 恢复渲染/从未暗色的段不受影响;
            // 块化路径 blocksView 全亮新建(暗色仅打字占位), 无需再渐亮
            if (b.dimmed && b.blocksView == null) brightenBlock(b, tv)
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
            // 静默分层(阶段4): 未封段段此刻才挂视图(封段段 sealContentPlaceholder 已挂占位)
            if (b.view == null && b.text.isNotBlank()) attachContentView(b)
            // 未封段段此刻排版暂存(封段段幂等跳过), 有暂存走动画原位显现, 无暂存(att://)现场排版
            renderSilent(b)
            if (b.pendingSpanned != null) {
                revealContent(b)      // 占位淡出+成品淡入(打字显现), 不再 finishTypeRender 避免闪帧
            } else {
                finishTypeRender(b)   // att:// 走 markwon / 纯文本直接上屏
                revealContent(b)      // 兜底直接可见
            }
        }
    }

    /** 收尾定格: 停轮播藏计数, 状态行定格为摘要(可点击展开时间线);
     *  无思考且无工具(纯正文)则整行移除 */
    private fun sealStatusSummary() {
        statusSealed = true
        cancelCarousel()
        if (ModeConfig.actionTrack()) return   // 行动轨道常显, 不折叠为摘要(方案A)
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
        if (ModeConfig.actionTrack()) return   // 行动轨道随消息保留(方案A)
        statusWrap?.let { w -> (w.parent as? ViewGroup)?.removeView(w) }
        statusWrap = null
        statusCol = null
        statusSwitcher = null
        statusCounter = null
        timelineView = null
        statusExpanded = false
        lastSnippet = null
        lastThinkChars = 0
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
