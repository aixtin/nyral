package io.github.aixtin.nyral

import android.graphics.Outline
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.os.Handler
import android.view.LayoutInflater
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.MediaController
// Media3 ExoPlayer: 自带纯 Java MP4/容器解析, 绕开系统 MediaPlayer/MediaExtractor 对部分转发视频的拒绝
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.widget.SeekBar
import android.widget.VideoView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import io.noties.markwon.SpanFactory
import io.noties.markwon.RenderProps
import io.noties.markwon.MarkwonConfiguration
import org.commonmark.node.Code
import android.text.style.TypefaceSpan

/** 帧驱动打字机回调接口与全局打字机中心已抽离至 Typewriter.kt */

class MainActivity : Activity() {
    companion object {
        @Volatile var instance: MainActivity? = null
        /** 阶段5 渲染幂等标记: TextView keyed tag 存"已上屏 md", 池化复用同内容时跳过重复渲染 */
        private const val KEY_RENDER_MD = 0x4E594D44  // "NYMD"
    }

    private val TAG = "Nyral"
    internal val executor = Executors.newSingleThreadExecutor()
    // Markdown 专用并行编译池(2026-09-18 吸底跳变复发根治): 旧版 bind miss 的兜底编译任务在单线程
    // executor 里排在 prewarm 整条长循环之后(FIFO), 占位可持续数秒, 替换落在用户惯性滚动中 → 高度突变挤动;
    // 并行化后: prewarm 拆单消息粒度 3 线程消化(全量提速3倍), bind miss 编译立即获得空闲线程不排队
    internal val mdExecutor = Executors.newFixedThreadPool(3)
    internal val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 全程流式: 流式渲染器与 markwon 收尾共用的表格主题(圆角+斑马纹+居中) */
    internal val mdTableTheme by lazy {
        TableTheme.buildWithDefaults(this)
            .tableCellPadding((7.5f * resources.displayMetrics.scaledDensity).toInt()) // 2026-09-30 老板: 单元格内边距改半个字
            .tableBorderWidth((1 * resources.displayMetrics.density).toInt())
            .tableBorderColor(Ui.PRIMARY)
            .tableHeaderRowBackgroundColor(Ui.INPUT_BG)
            .tableOddRowBackgroundColor(Ui.INPUT_BG)
            .build()
    }
    /** markwon 渲染互斥锁(2026-09-27): 主线程同步渲染与后台预热(mdExecutor 3线程)并发调用同一 markwon 单例,
     *  RoundedTablePlugin 的 TableVisitor 持有共享 pendingTableRow, 并发渲染表格时一边遍历一边 add
     *  → ConcurrentModificationException 首启闪退(二次打开正常, 概率性)。解析/渲染段统一串行化 */
    private val markwonRenderLock = Any()

    // Markdown 本地渲染 (Markwon, 开源/无网络/不接第三方服务)
    internal val markwon by lazy {
        Markwon.builder(this)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    // 代码块/行内代码统一浅灰底; CodeBlockSpan 由 markwon 默认 factory 按 theme 整块绘制
                    builder.codeBackgroundColor(Ui.INPUT_BG)
                    builder.codeBlockBackgroundColor(Ui.INPUT_BG)
                    // 标题: 显式设置字号倍率+加粗, 避免默认倍率缺失导致标题不放大
                    builder.headingTextSizeMultipliers(floatArrayOf(1.5f, 1.35f, 1.2f, 1.1f, 1.05f, 1.0f))
                    builder.headingTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                }
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // 代码块圆角背景 + 选中时淡化背景让系统高亮可见 (替换 markwon 默认直角整行背景)
                    val codeBlockFactory = object : SpanFactory {
                        override fun getSpans(configuration: MarkwonConfiguration, props: RenderProps): Any? {
                            return RoundedCodeBlockSpan(configuration.theme(), resources.displayMetrics.density)
                        }
                    }
                    builder.setFactory(FencedCodeBlock::class.java, codeBlockFactory)
                    builder.setFactory(IndentedCodeBlock::class.java, codeBlockFactory)
                }
            })
            .usePlugin(StrikethroughPlugin.create())
            .build()
    }
    // role, content, thinking(assistant 思考内容, 持久化到会话以便切回时恢复思考区), tools(工具调用序列 JSON)
    private var lastModeValue = -1
    internal val messages = mutableListOf<MemoryDb.SessionMsg>()
    /** 超长会话内存瘦身: 仅最近 WINDOW 条消息载入内存渲染, 更早消息保留 DB 供搜索/回溯, 上下文由 summary 承担 */
    private val MEM_WINDOW = 150
    /** 单附件本地解析文本注入上限(字符): 超过截断防单条请求 token 突增 */
    private val MAX_ATTACH_TEXT = 40000
    /** 单次提交全部附件解析文本总量上限(字符): 超过部分丢弃防上下文炸裂 */
    private val MAX_DOC_TOTAL = 120000
    /** 单条消息注入 history 的长度上限(字符): 超长单条消息(如超长回答/粘贴)截断 */
    private val MAX_MSG_HISTORY = 8000
    /** 当前会话窗口化加载时, 窗口起点在 DB 中的全局 seq(=总条数-窗口条数); 非窗口化时为 0, 保存时据此保留窗口外旧消息 */
    private var sessionBaseSeq = 0
    /** 上次检测的头像版本戳, 换头像返回时比对变化以刷新旧气泡 */
    private var lastAvatarStamp = 0L
    private val chatRows = ArrayList<ChatRow>()
    private lateinit var chatAdapter: ChatAdapter
    lateinit var chatRec: RecyclerView
    private var streamingRow: ChatRow.Streaming? = null
    /** AI 表情流式掩码缓冲: 保存跨 delta 分片的未闭合 [表情: 尾巴 */
    private var emojiMaskTail = ""
    /** 请求代际(阶段2 流式竞态治理): 每次发起新请求/取消当前请求(切会话)自增,
     *  流式回调进入主线程后先校验代际一致才操作 holder/滚动, 天然拦截迟到回调 */
    private var requestEpoch = 0L
    private var scrollUserScrolled = false   // 用户手动上翻/交互接管后不再自动拉底(不打扰阅读)
    // 占位→渲染替换异步在途计数(09-27 修复2): 渲染未完成时列表高度偏小,
    // canScrollVertically(1) 会长时间"伪贴底", 二次确认照样通过 → 误解除守卫,
    // 渲染完成瞬间自动追底猛推 = 录屏末尾"静止→瞬移→底部结论"跳变;
    // 在途 >0 期间禁止解除守卫, 渲染完成且真贴底才恢复自动追底
    private val mdRenderPending = java.util.concurrent.atomic.AtomicInteger(0)
    // 滑动中渲染完成的替换挂起队列(09-27 根治): 滑动中占位→markdown 高度突变(1500+px)
    // 无人补偿会推挤视口内多行=多气泡跳; 渲染完成先挂起, 滚动完全停止(IDLE)后统一替换,
    // 此时 watchHeightDrift 正常补偿钉住阅读位置, 与手势无打架
    private val pendingMdReplacements = ArrayList<Runnable>()
    private val FLUSH_BATCH = 6   // IDLE 后每帧最多执行的挂起替换数(09-28 第二波分批)
    // 触摸即冻结(09-25): 记录 DOWN 坐标, UP 按位移区分轻按(恢复慢打)/滑动(保持接管)
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlopPx by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }
    private var aiStage = 0            // AI 输出阶段: 0=idle 1=thinking 2=tool 3=content
    private var contentFollow = false  // 正文跟随模式: FAB 一键到底后 true; 正文默认停滚, 右下角出现一键到底
    private var jumpFab: android.widget.TextView? = null
    private var activeAiHolder: AiBubbleHolder? = null   // 当前流式会话的 AiBubbleHolder: scrollToBottom 打字期滚动分支判据
    private var pendingAlign = 0             // scrollToBottom 的 preDraw 对齐待执行计数(防重复注册泄漏)
    private var lastAutoScrollTs = 0L        // 流式追底去抖时间戳

    /** 历史 markdown 气泡预编译缓存(2026-09-18 长气泡吸底跳跃修复):
     *  根因: 上翻历史首次 bind 长气泡时 onBindViewHolder 里同步 markwon.setMarkdown(全文),
     *  commonmark 全量解析+span 化在主线程耗 50-300ms = 掉帧跳变; 打开会话初始布局只覆盖底部(最新消息),
     *  上翻才首次 bind 更旧消息 → "只上翻出现/每条只第一次/重启复发" 全部吻合。
     *  修法: 会话打开后在后台线程预编译存此缓存, bind 时命中直接 set(主线程零解析);
     *  未命中(预热未完成/新消息)回落同步渲染, 行为与旧版一致不劣化 */
    private val mdCache = LruCache<String, Spanned>(512)
    /** 渲染高度缓存(09-28 一次到位): md分片+当前气泡宽度 -> 渲染后真实高度(px)。
     *  长文未命中 mdCache 时查此缓存做"等高占位"(minimumHeight=真实高度), 异步替换瞬间高度不变=零跳变;
     *  宽度分档(key 拼 chatMaxW), 键盘弹起气泡变窄时高度档不同不串用 */
    private val mdHeightCache = LruCache<String, Int>(2048)
    /** 预渲染测量队列: prewarm 后台渲染完成的长文分片排队, 主线程用探测 TextView 测最终高度入 mdHeightCache */
    private val mdMeasureQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Spanned>>()
    @Volatile private var mdMeasureBusy = false
    private var mdProbeTv: TextView? = null
    // 预热代数(openSession 主线程自增): 切走会话后旧预热任务检测代数不符即自杀, 不空耗CPU不占编译线程
    @Volatile private var mdPrewarmGen = 0L

    /** 占位净化(09-28 气泡缩短修复): 长文本未命中/滑动中 defer 时上屏的占位, 剔除
     *  markdown 渲染后不占行的源码行(``` 围栏、表格分隔行), 使占位高度≈渲染后高度,
     *  消除"占位(高)→渲染(矮)"的二次刷新视觉缩短; 校验随之改为比较净化占位文本 */
    private fun mdPlaceholder(md: String): String {
        val sb = StringBuilder(md.length)
        for (line in md.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("```")) continue
            if (t.contains("|") && t.contains("-") && t.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }) continue
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** 高度缓存 key: md 分片内容 + 当前气泡宽度(px) 分档, 宽度变化不串档 */
    private fun heightKey(md: String) = md + "@" + chatMaxW()

    /** 探测 TextView(复用): 完全复刻真实 AiRich 分片气泡配置, 用于预渲染完成后离线测量最终高度 */
    private fun ensureMdProbe(): TextView {
        mdProbeTv?.let { return it }
        return TextView(this@MainActivity).apply {
            // 必须有 LayoutParams: setText->checkForRelayout 会读 mLayoutParams.width, null 即 NPE
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            includeFontPadding = false
            setTextColor(BUBBLE_AI_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxWidth = chatMaxW()
        }.also { mdProbeTv = it }
    }

    /** 预渲染完成入队测量: 主线程串行消化, 每条双层 post(等表格二次 setText)后测量最终高度入 mdHeightCache */
    private fun enqueueMdMeasure(md: String, spanned: Spanned) {
        if (mdHeightCache.get(heightKey(md)) != null) return
        mdMeasureQueue.add(md to spanned)
        if (!mdMeasureBusy) scheduleMdMeasure()
    }

    private fun scheduleMdMeasure() {
        mdMeasureBusy = true
        chatRec.post {
            val item = mdMeasureQueue.poll()
            if (item == null) { mdMeasureBusy = false; return@post }
            val (md, spanned) = item
            val key = heightKey(md)
            if (mdHeightCache.get(key) != null) { scheduleMdMeasure(); return@post }
            val probe = ensureMdProbe()
            probe.maxWidth = chatMaxW()
            // setParsedMarkdown 内部对表格插件 post 二次 setText(布局最终高度), 双 post 确保其先执行再测量
            markwon.setParsedMarkdown(probe, spanned)
            chatRec.post {
                probe.measure(
                    View.MeasureSpec.makeMeasureSpec(probe.maxWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                mdHeightCache.put(key, probe.measuredHeight)
                scheduleMdMeasure()   // 串行处理下一条
            }
        }
    }

    /** 真实气泡渲染/替换完成后记录最终高度(双层 post 等表格二次测量后的最终布局) */
    private fun recordMdHeight(tv: TextView, md: String) {
        if (tv.height <= 0) return
        chatRec.post {
            chatRec.post {
                if (tv.height > 0) mdHeightCache.put(heightKey(md), tv.height)
            }
        }
    }

    /** 历史气泡渲染统一入口: 命中预编译缓存直接 set(主线程零解析), 未命中同步渲染(旧路径)并回填缓存;
     *  所有路径挂高度漂移补偿, 抵御 Markwon 表格 span 布局后二次测量 */

    internal fun setMarkdownCached(tv: TextView, md: String, rendered: String? = null, writeback: ((Spanned) -> Unit)? = null) {
        // 方案B(2026-10-01): 历史渲染统一块化, 本入口只应收无表格文本;
        // 若仍收到表格语法说明有渲染点漏走块化预判 -> 告警便于发现(表格会退化为 span 旧路径)
        if (containsTableSyntax(md)) android.util.Log.w("NyralTbl", "setMarkdownCached got table md len=" + md.length)
        // 高度漂移补偿: 表格/复杂 span 的二次测量发生在首次布局之后(post 同文本再 setText),
        // 高度突增推挤视口内容 = 上翻"突然加速"; watcher 每帧 draw 前反向补偿钉住阅读位置
        // 09-27 修复: watcher 必须在文本设置之后注册——若先注册, 基线记的是 RV 复用残留的旧内容
        // (或空内容)高度, setMarkdown 替换文本瞬间的高度差(可达几百px)被误当"生长量"补偿,
        // 产生 delta=-236/+310 的上下跳闪(DRIFTDBG 实测)
        // 命中路径必须走 setParsedMarkdown(内部跑 beforeSetText/afterSetText 全流程),
        // 直接 tv.text=spanned 会绕过 Markwon 的 TextView 生命周期钩子, 列表等 span 测量异常 = 气泡右侧被截断
        // 阶段1 rendered 三级读取第②级: 内存 miss 时反序列化落库产物回填缓存(零 markwon 解析)

        // 阶段5 span 宽度现算: 表格行宽度固化在 span 里, 渲染/反序列化前必须注入当前 TextView 可用宽,
        // 与 AiBubbleHolder 一致, 保证池化复用/重启直读与现场渲染同宽(消除表格二次跳变)
        val renderW = if (tv.maxWidth > 0) tv.maxWidth else MdSpannable.tableMaxWidth
        if (mdCache.get(md) == null && !rendered.isNullOrBlank()) {
            val dec = RenderedCodec.decode(
                rendered,
                markwon.configuration().theme(),
                mdTableTheme,
                resources.displayMetrics.density,
                renderW
            )
            if (dec != null) {
                // 坏数据防护(10-02): 块化段落库 rendered 是"源码纯文本 JSON"(无 span 且 text==md),
                // decode 回显源码会覆盖正常渲染 -> 识别并忽略, 走现场 markwon 渲染
                val decPlain = dec.getSpans(0, dec.length, Any::class.java).isEmpty() && dec.toString().trim() == md.trim()
                if (!decPlain) { mdCache.put(md, dec); android.util.Log.i("DbgMd", "decode mdLen=" + md.length + " decLen=" + dec.length) }
                else android.util.Log.i("DbgMd", "decode bad mdLen=" + md.length + " ignore")
            }
        }
        mdCache.get(md)?.let {
            // 阶段5 bind 幂等: 池化复用同内容行时 tv 已上屏同 md 成品(非占位), 跳过重复 setParsedMarkdown
            // (每次 setParsedMarkdown 都会重建 span 树+触发 layout, 是同形态复用滑动的最大残余成本)
            if (tv.getTag(KEY_RENDER_MD) == md) {
                writeback?.invoke(it)
                return
            }
            // 阶段1: 内存缓存命中同样写回——prewarm/本进程渲染产物 DB 可能尚未落库,
            // 命中即视为"渲染完成", 幂等写回(内容相同会跳过)
            writeback?.invoke(it)
            if (sScrolling) {
                // 根治(09-27): 缓存命中路径滑动中同样 defer——直接 setParsedMarkdown 替换
                // 会产生占位→渲染高度突变(实测 delta=-755), 滑动中无人补偿会推挤视口=多气泡跳;
                // 挂起等 IDLE 统一替换+补偿
                // 修复(09-28 第三波): 挂起前必须先设纯文本占位(与长文本路径一致)——池化行复用后
                // tv 文本已被清空, 若保持空白, IDLE flush 的 text 校验(tv.text==md)必失败,
                // 替换被丢弃 = 正文永久空白气泡(用户实测: 只剩思考气泡, 正文全空白)
                val ph = mdPlaceholder(md)
                if (tv.text?.toString() != ph) tv.text = ph
                pendingMdReplacements.add {
                    if (tv.text?.toString() == ph) {
                        markwon.setParsedMarkdown(tv, it)
                        tv.setTag(KEY_RENDER_MD, md)
                    }
                    tryResumeBottomIfTrueBottom()
                    recordMdHeight(tv, md)
                }
                schedulePendingFlush()
            } else {
                markwon.setParsedMarkdown(tv, it)
                tv.setTag(KEY_RENDER_MD, md)
            }
            watchHeightDrift(tv)
            recordMdHeight(tv, md)
            return
        }
        if (md.length < 600) {   // 短文本同步渲染本就不卡, 直接走旧路径并回填缓存
            MdSpannable.tableMaxWidth = renderW
            synchronized(markwonRenderLock) { markwon.setMarkdown(tv, md) }
            watchHeightDrift(tv)
            (tv.text as? Spanned)?.let { mdCache.put(md, it); writeback?.invoke(it) }
            tv.setTag(KEY_RENDER_MD, md)
            recordMdHeight(tv, md)
            return
        }
        // 长文本未命中(预热未跑到: 切会话/冷启动后立刻上翻):
        // 09-28 一次到位三档处理:
        //  1) 高度缓存命中 -> 等高占位(minimumHeight=渲染高度), 异步替换瞬间高度不变=零跳变
        //  2) 无高度缓存且非滚动/非流式 -> 主线程同步渲染, 直接显示最终结果(消灭"过会儿气泡缩短")
        //  3) 滚动/流式中 -> 纯占位异步(旧路径), 替换高度突变由 drift watcher 补偿
        val ph = mdPlaceholder(md)
        val hKey = heightKey(md)
        val cachedH = mdHeightCache.get(hKey)
        if (cachedH != null) {
            // 档1: 等高占位
            tv.minimumHeight = cachedH
            tv.text = ph
            watchHeightDrift(tv)
            mdRenderPending.incrementAndGet()   // 占位已上屏, 渲染在途: 贴底判定冻结(09-27 修复2)
            mdExecutor.execute {
                MdSpannable.tableMaxWidth = renderW   // 阶段5: 异步现场渲染前注入同款表格宽度(与主线程现算一致)
                val spanned = try {
                    if (mdCache.get(md) == null) mdCache.put(md, synchronized(markwonRenderLock) { markwon.toMarkdown(md) })
                    mdCache.get(md)
                } catch (e: Exception) { null }
                if (spanned != null) runOnUiThread {
                    // holder 可能已被 RV 回收复用: 校验 tv 仍挂着本条占位文本才替换, 否则丢弃(幂等安全)
                    if (tv.text?.toString() != ph) { mdRenderPending.decrementAndGet(); return@runOnUiThread }
                    val replace = {
                        if (tv.text?.toString() == ph) {
                            markwon.setParsedMarkdown(tv, spanned)
                            tv.minimumHeight = 0   // 等高占位使命完成: 解除固定高度, 内容自然接管
                            tv.setTag(KEY_RENDER_MD, md)
                            writeback?.invoke(spanned)   // 阶段1: 渲染完成写回 rendered
                        }
                        mdRenderPending.decrementAndGet()   // 替换执行/丢弃均归还计数
                        tryResumeBottomIfTrueBottom()
                        recordMdHeight(tv, md)
                    }
                    if (sScrolling) {
                        // 滑动中 defer: 停止后 flush 统一替换, watchHeightDrift 补偿钉住阅读位置
                        pendingMdReplacements.add(replace)
                        schedulePendingFlush()
                    } else {
                        replace()
                    }
                } else {
                    mdRenderPending.decrementAndGet()   // 编译失败也归还计数(罕见)
                }
            }
            return
        }
        if (!sScrolling && !aiBusy) {
            // 档2: 首屏同步兜底——打开会话冷启动, 占位会带来"过会儿气泡缩短/跳变",
            // 直接主线程同步渲染显示最终结果, 从根上消灭占位→渲染高度突变;
            // 仅打开会话瞬间发生且 prewarm 通常已命中大部分, 少数同步渲染小卡可接受;
            // 滚动中/流式中不走(防掉帧)
            MdSpannable.tableMaxWidth = renderW   // 阶段5: 同步现场渲染前注入同款表格宽度
            synchronized(markwonRenderLock) { markwon.setMarkdown(tv, md) }
            watchHeightDrift(tv)
            (tv.text as? Spanned)?.let { mdCache.put(md, it); writeback?.invoke(it) }
            tv.setTag(KEY_RENDER_MD, md)
            recordMdHeight(tv, md)
            return
        }
        // 档3: 纯占位异步(滚动/流式中快速遇到未渲染长文, 旧路径)
        tv.minimumHeight = 0   // 防池化行复用残留上一轮等高占位的固定高度
        tv.text = ph
        watchHeightDrift(tv)
        mdRenderPending.incrementAndGet()   // 占位已上屏, 渲染在途: 贴底判定冻结(09-27 修复2)
        mdExecutor.execute {
            MdSpannable.tableMaxWidth = renderW   // 阶段5: 异步现场渲染前注入同款表格宽度(与主线程现算一致)
            val spanned = try {
                if (mdCache.get(md) == null) mdCache.put(md, synchronized(markwonRenderLock) { markwon.toMarkdown(md) })
                mdCache.get(md)
            } catch (e: Exception) { null }
            if (spanned != null) runOnUiThread {
                // holder 可能已被 RV 回收复用: 校验 tv 仍挂着本条占位文本才替换, 否则丢弃(幂等安全)
                if (tv.text?.toString() != ph) { mdRenderPending.decrementAndGet(); return@runOnUiThread }
                val replace = {
                    if (tv.text?.toString() == ph) {
                        markwon.setParsedMarkdown(tv, spanned)
                        tv.setTag(KEY_RENDER_MD, md)
                        writeback?.invoke(spanned)   // 阶段1: 渲染完成写回 rendered
                    }
                    mdRenderPending.decrementAndGet()   // 替换执行/丢弃均归还计数
                    tryResumeBottomIfTrueBottom()
                    recordMdHeight(tv, md)
                }
                if (sScrolling) {
                    // 根治(09-27): 滑动中不上屏替换——占位→markdown 高度突变(1500+px)在滑动中
                    // 无人补偿会推挤视口内多行内容=多气泡跳; 挂起到滚动停止统一替换, 停止后
                    // watchHeightDrift 正常补偿钉住阅读位置(与手势无打架)
                    // 注意: mdRenderPending 保持 >0, 渲染未上屏期间贴底判定继续冻结
                    pendingMdReplacements.add(replace)
                    schedulePendingFlush()
                } else {
                    replace()
                }
            } else {
                mdRenderPending.decrementAndGet()   // 编译失败也归还计数(罕见)
            }
        }
    }

    /** 阶段1: 渲染完成写回 rendered —— 内存 messages(下次落库携带) + DB 增量(防中途退出丢失)。
     *  ChatRow.id = sessionBaseSeq + msgIdx, msgIdx 与 messages 索引一一对应(全空消息同样占位) */
    private fun writeBackRendered(seq: Long, spanned: Spanned) {
        val json = RenderedCodec.encode(spanned)
        if (json == null) { android.util.Log.w(TAG, "writeback encode null seq=$seq len=${spanned.length}"); return }
        if (json.isBlank()) return
        val idx = (seq - sessionBaseSeq).toInt()

        if (idx in messages.indices) {
            val m = messages[idx]
            if (m.rendered != json) {
                messages[idx] = m.copy(rendered = json, renderedVersion = RenderedCodec.VERSION)
                currentSessionId?.let { sid ->
                    try { db.updateRendered(sid, seq.toInt(), json, RenderedCodec.VERSION) }
                    catch (e: Exception) { android.util.Log.w(TAG, "db fail seq=$seq", e) }
                }
            }
        } else android.util.Log.w(TAG, "idx OOB seq=$seq idx=$idx")
    }

    /** 渲染替换完成/贴底后: 若用户曾上翻且此刻真贴底, 主动恢复自动追底(09-27) */
    private fun tryResumeBottomIfTrueBottom() {
        // 替换完成瞬间高度已稳定(下一帧布局后): 若用户曾上翻且此刻真贴底,
        // 主动恢复自动追底; 在中间阅读位则守卫保持, 不被自动追底拉走
        if (scrollUserScrolled && !chatRec.canScrollVertically(1)) {
            chatRec.postOnAnimation {
                if (mdRenderPending.get() == 0 && !chatRec.canScrollVertically(1)) {
                    scrollUserScrolled = false
                    activeAiHolder?.resumeTypewriter()
                    updateJumpFab()
                }
            }
        }
    }

    /** 高度漂移补偿(2026-09-18 幽灵跳变终版): Markwon 表格 span 官方调度(RoundedTablePlugin
     *  afterSetText→post 同文本 setText)在首次布局后触发二次测量, 高度突变推挤视口内下方内容
     *  = 滚动方向上的一次额外加速("正常→加速→正常"), 预编译缓存命中也无法避免(二次测量是
     *  每次 TextView 挂载的行为, 不是编译期), 故前几轮修复全部打空。
     *  本 watcher 每帧 preDraw 比对 tv 高度: tv.parent 相对 bottom 不随 RV 滚动变化, 增量即纯生长量;
     *  视口重叠的漂移在 draw 前反向 scrollBy 钉住——气泡底边不动、向上生长, 阅读位置纹丝不动。
     *  对表格二次测量/占位→渲染替换/任何未来高度突变源统一生效。
     *  生命周期: tv 离屏(detach)摘除, 重挂(缓存行复用)重置基线重新观察, 每帧成本一次整数比较 */
    // 单气泡单 watcher 登记表(09-27 去重): 同一 tv 流式更新/内容替换会反复进入 watchHeightDrift,
    // 每次 new 的 preDraw listener 若只 add 不摘除, 会在 chatRec observer 上累积 N 个,
    // 一次高度变化被 N 个 watcher 各自 scrollBy(delta) 叠加补偿 = 上滑闪跳(顶部边框下滑又闪回);
    // 先摘旧再挂新 + detach 清理登记表, 保证任意时刻单 watcher 无泄漏
    private val driftPreDraws = HashMap<android.view.View, android.view.ViewTreeObserver.OnPreDrawListener>()
    private val driftAttaches = HashMap<android.view.View, android.view.View.OnAttachStateChangeListener>()

    private fun watchHeightDrift(tv: TextView) {
        driftPreDraws.remove(tv)?.let { chatRec.viewTreeObserver.removeOnPreDrawListener(it) }
        driftAttaches.remove(tv)?.let { tv.removeOnAttachStateChangeListener(it) }
        var lastBottom = Int.MIN_VALUE
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (tv.height <= 0) return true
                val b = tv.bottom
                if (lastBottom != Int.MIN_VALUE && b != lastBottom) {
                    val delta = b - lastBottom
                    // 09-27 跳变修复2: 用户滑动/惯性中(DRAGGING/SETTLING)不做补偿——
                    // 滑动中 bind 历史长文(占位→渲染替换, delta 可达 1500+) 若仍 scrollBy,
                    // 会把正在滚动的列表猛推/拉回 = 录屏 8~9s 的上下反复跳变;
                    // 滚动中只更新基线, 停止后高度已稳定, 无增量自然不补, 位置即 RV 布局自然位
                    if (sScrolling) {
                        Log.d("DRIFTDBG", "skip-scrolling delta=" + delta + " b=" + b + " last=" + lastBottom + " watchers=" + driftPreDraws.size)
                        lastBottom = b; return true
                    }
                    // 09-27 终版(气泡顶跳回标题栏根因): 用户上翻历史浏览(scrollUserScrolled=true)时
                    // 阅读锚点是视口顶部, 底边钉住补偿的 scrollBy(+delta) 会把视口向底部猛拉
                    // (flush 时双气泡替换叠加实测 +1592px)= 气泡顶边从屏幕中间跳回标题栏下方;
                    // 历史浏览态不做补偿, RV 布局自然锚定首可见项顶, 替换气泡向下生长不扰动阅读位;
                    // 补偿仅保留给贴底追读态(scrollUserScrolled=false, 流式输出钉住最新气泡底边)
                    if (scrollUserScrolled) {
                        Log.d("DRIFTDBG", "skip-history delta=" + delta + " b=" + b + " last=" + lastBottom + " watchers=" + driftPreDraws.size)
                        lastBottom = b; return true
                    }
                    var row: android.view.View = tv
                    while (row.parent is android.view.View && row.parent !== chatRec) row = row.parent as android.view.View
                    if (row.parent === chatRec && row.top < chatRec.height && row.bottom > 0) {
                        // 09-28 修复: 占位→渲染大 delta(超长消息 8000px+) 直接 scrollBy 会
                        // 把视口猛推数屏(实测 +8005px≈4屏), 上翻历史时表现为列表跳动/气泡
                        // 异常("过一会儿气泡缩短"); 超过视口高度一半的 delta 视为内容级替换,
                        // 不做钉底补偿, 交由 RV 自然布局(气泡变高向下生长, 不扰动视口);
                        // 流式输出逐帧 delta 很小不受影响(贴底钉住语义保留)
                        val maxComp = chatRec.height / 2
                        if (delta > maxComp || delta < -maxComp) {
                            Log.d("DRIFTDBG", "skip-huge delta=" + delta + " b=" + b + " last=" + lastBottom + " h=" + chatRec.height)
                        } else {
                            Log.d("DRIFTDBG", "compensate delta=" + delta + " b=" + b + " last=" + lastBottom + " watchers=" + driftPreDraws.size)
                            chatRec.scrollBy(0, delta)
                        }
                    }
                }
                lastBottom = b
                return true
            }
        }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                lastBottom = Int.MIN_VALUE   // 重置基线: 重挂后(如缓存行复用)表格会再调度二次测量
                chatRec.viewTreeObserver.addOnPreDrawListener(listener)
                driftPreDraws[tv] = listener   // 同步登记: detach 清 map 后 attach 复活 listener 仍需可去重
            }
            override fun onViewDetachedFromWindow(v: View) {
                chatRec.viewTreeObserver.removeOnPreDrawListener(listener)
                driftPreDraws.remove(tv)
                driftAttaches.remove(tv)
            }
        }
        driftPreDraws[tv] = listener
        driftAttaches[tv] = attach
        chatRec.viewTreeObserver.addOnPreDrawListener(listener)
        tv.addOnAttachStateChangeListener(attach)
    }

    /** 会话打开后后台预编译历史 AI 消息(含 AiRich 分片), 上翻浏览时 bind 直接命中缓存 */
    private fun prewarmMdCache() {
        // 纯文本模式不渲染 Markdown, 预热纯烧 CPU 还加剧会话切换卡顿
        if (ModeConfig.chatPlainText()) return
        val aiMsgs = messages.mapIndexedNotNull { i, m ->
            if (m.role != "user" && m.content.isNotBlank()) i to m else null
        }
        if (aiMsgs.isEmpty()) return
        val gen = ++mdPrewarmGen
        // 倒序拆成单消息粒度任务投并行池(最新→最旧): 3线程同时消化全量时间/3;
        // bind miss 的兜底任务与剩余预热并行执行, 不再排在整条预热循环之后数秒等待
        for ((idx, m) in aiMsgs.asReversed()) {
            mdExecutor.execute {
                if (gen != mdPrewarmGen) return@execute   // 已切走会话: 任务自杀
                // AiRich 分片路径与 contentRow 渲染一致(超长分片阈值), 用户消息不走 markwon
                val parts = if (m.content.length > SPLIT_CONTENT_LEN) splitLongContent(m.content) else listOf(m.content)
                // 阶段3 存量写回判定: 与 fillAiRich singleSeg 一致——timeline content 事件<=1 且
                // content 未分段时整条 rendered 的 span 区间才与单段文本匹配, 可安全写回落库
                val cEvts = if (m.timeline.isBlank()) 0 else runCatching {
                    parseTimeline(m.timeline).count { it[0] == "content" }
                }.getOrDefault(0)
                val writeableSeg = parts.size == 1 && cEvts <= 1 &&
                    (m.renderedVersion < 1 || m.rendered.isBlank())
                var segSpanned: Spanned? = null
                for (p in parts) {
                    val md = ModeConfig.stripChatProtocolPrefix(p.trim())
                    // 短文同步渲染本就不卡, 不走 prewarm 也不写回(bind 短文本路径已含 writeback)
                    if (md.length < 600) { segSpanned = null; break }
                    var spanned = mdCache.get(md)
                    if (spanned == null) {
                        // 阶段2 rendered 直读: DB 已有落库产物(renderedVersion>=1)时反序列化回填缓存,
                        // 打开已渲染会话零 markwon 解析(长文 markwon 最贵), decode 失败回退 markwon
                        if (m.renderedVersion >= 1 && m.rendered.isNotBlank()) {
                            try {
                                val dec = RenderedCodec.decode(m.rendered, markwon.configuration().theme(), mdTableTheme, resources.displayMetrics.density, chatMaxW())
                                if (dec != null) { mdCache.put(md, dec); spanned = dec }
                            } catch (e: Exception) { /* decode 失败回退 markwon 渲染 */ }
                        }
                        if (spanned == null) {
                            try {
                                spanned = synchronized(markwonRenderLock) { markwon.toMarkdown(md) }
                                mdCache.put(md, spanned)

                            } catch (e: Exception) { spanned = null }
                        }
                    } else {

                    }
                    if (spanned != null) {
                        segSpanned = spanned
                        enqueueMdMeasure(md, spanned)
                    } else { segSpanned = null; break }
                }
                // 阶段3: 单段长文且 DB 无 rendered → 渲染完成即写回(打开会话零等待, 防 updateSession 全量重写抹掉)
                if (writeableSeg && segSpanned != null) {
                    val sp = segSpanned!!
                    val seq = sessionBaseSeq + idx
                    runOnUiThread { writeBackRendered(seq.toLong(), sp) }
                }
            }
        }
    }
    internal lateinit var root: FrameLayout
    /** 返回退出二次确认: 上一次触发"再滑动一次退出"提示的时间戳(2秒窗口) */
    private var lastExitPressTime = 0L
    // 独立固定全屏背景层: 壁纸/渐变背景挂此层(不随键盘压缩上移), root 为透明壳
    private lateinit var bgLayer: FrameLayout
    private lateinit var bodyWrap: LinearLayout
    // 层级: bodyWrap = 消息区(chatArea)+输入框整体(dockContent)+表情抽屉(占位, 展开顶起输入框)
    /** 输入框整体(attachPreviewWrap+inputBar), 基础层, 只被键盘 insets 顶起/回落 */
    internal lateinit var dockContent: LinearLayout
    /** 最近一次键盘高度(px): 档位B基准, 键盘收回后小抽屉按键盘高度档展开 */
    internal var imeHeightField = 0
    internal var inImeAnim = false  // 系统键盘 insets 动画进行中(提升为成员, 供抽屉转头判断 inImeAnim)
    internal var lastImeStableAt = 0L  // 键盘 insets 最近一次稳定弹到位时间戳: onPrepare 据此判断 animStartImeH 是否满高
    // 消息区独立 FrameLayout: browserBar 悬浮 overlay 不占位, 聊天区高度恒定防气泡抖动
    private lateinit var chatArea: FrameLayout
    private lateinit var inputBar: LinearLayout
    // 浏览器控制条(输入框上方): 悬浮聊天时显示欢迎文字+接管按钮
    private lateinit var browserBar: LinearLayout
    private lateinit var browserBarStatus: TextView
    private lateinit var browserBarTakeover: TextView
    // 浏览器控制条面板入口按钮: 展开汉堡面板(引擎管理/登录数据/URL), 悬浮模式替代已删除的右缘左滑手势
    private lateinit var browserBarPanel: TextView
    // 浏览器控制条折叠: 输入框上方 ⌃ 手柄(默认收起), 点开展开控制条(状态+接管按钮)
    private lateinit var browserBarToggle: ImageView
    private var browserBarExpanded = false
    private lateinit var collapseMaskWeb: View
    private lateinit var collapseMaskChat: View
    internal lateinit var input: EditText
    internal lateinit var modelBtn: Button
    internal lateinit var attachBtn: Button
    private lateinit var attachBtn2: Button   // 槽A(语音槽内)的附件按钮, 输入文字时显示
    private lateinit var attachWrap: FrameLayout
    // 附件预览条: 选中附件先进入输入框上方预览, 补文字后一并发送(仿主流IM)
    internal lateinit var attachPreviewWrap: HorizontalScrollView
    internal lateinit var attachPreviewRow: LinearLayout
    internal val pendingAttachments = mutableListOf<LocalEngine.Attachment>()
    // 附件发送链路(解析/校验/预览) 已抽离 AttachmentSender
    private val attachmentSender = AttachmentSender(this)
    // 附件选择请求码
    private val REQ_IMAGE = 1001
    private val REQ_FILE = 1002
    private val REQ_AUDIO = 1003
    private val REQ_VIDEO = 1004
    internal val REQ_RECORD = 1005
    private val REQ_NOTIF = 1006  // Android 13+ 通知权限(前台服务通知展示用)
    private val REQ_GUIDE_PERMS = 1007  // 首启权限引导: 一次申请运行时权限
    internal val REQ_EMOJI_PICK = 1008  // 表情库选图: 结果存表情库而非聊天发送
    // 图片压缩上限: 最长边/质量
    internal val maxFileBytes: Int get() = UploadConfig.maxMb() * 1024 * 1024
    // 录音: 上限 60 秒 / 10MB(语音消息足够, 超限直接拒)
    internal val MAX_RECORD_MS = 60_000L
    internal val MAX_AUDIO_BYTES = 10 * 1024 * 1024
    // 录音状态
    internal lateinit var micBtn: Button
    internal var audioRecord: AudioRecord? = null
    internal var recPcm: ByteArrayOutputStream? = null
    internal var recStop = false
    internal var recThread: Thread? = null
    internal var recStartMs = 0L
    internal var recTimer: Runnable? = null
    // 必须与 postDelayed 使用同一 Handler 实例: removeCallbacks 要求消息 target 匹配, 新建实例移除会静默失败
    internal var recHandler: Handler? = null
    internal lateinit var speakBar: TextView
    internal var voiceMode = false
    internal var speaking = false
    internal var speakCancel = false
    internal var audioPlayer: MediaPlayer? = null
    internal var playingFileName: String? = null
    // 纯语音气泡注册表(弱引用): 播放状态变化时统一刷新播放/暂停图标
    internal val audioBubbles = mutableListOf<WeakReference<TextView>>()
    // 语音气泡内声波动画共享相位 + 驱动动画 (WaveState 已抽离 BubbleSpans.kt)
    internal val waveState = WaveState()
    internal var waveAnim: ValueAnimator? = null
    internal var modelPopup: PopupWindow? = null
    internal var modelClosing = false   // 弹窗收起动画进行中, 防重复触发
    internal var attachPopup: PopupWindow? = null
    internal var attachClosing = false  // 附件弹窗收起动画进行中, 防重复触发
    internal lateinit var sendBtn: ImageView
    private lateinit var stopBtn: ImageView
    internal lateinit var drawerPanel: LinearLayout
    internal lateinit var drawerMask: View
    internal lateinit var sessionList: LinearLayout
    internal lateinit var main: LinearLayout
    internal var drawerOpen = false
    internal var imeShown = false  // 键盘弹起状态(提升为成员, 供抽屉联动冻结判断)
    // 抽屉联动: 主界面下沉进度(0=全尺寸 1=完全下沉), 由跟手/动画统一驱动
    private var mainSinkP = 0f
    private var hamburgerSinkBase = 0f
    private var hamburgerSinkAnimator: android.animation.ValueAnimator? = null
    private var drawerAnimator: ValueAnimator? = null
    // 顶栏/底栏对侧圆角背景(常驻内侧两角圆角, 抽屉联动时外侧两角随进度圆角化)
    private lateinit var titleBarBg: GradientDrawable
    private lateinit var inputBarBg: GradientDrawable
    private lateinit var swipeDetector: GestureDetector
    /** 抽屉跟手拖拽控制器(微信式): root 拦截水平边缘/遮罩手势, 1:1 跟随 + 抬手吸附 */
    private lateinit var drawerDrag: DrawerDragController
    /** 右侧整屏浏览器操作页(自研 Agent 浏览器雏形): 全屏 WebView + AI 状态条/高亮圈/思考摘要 */
    internal lateinit var browserPage: BrowserPage
    private val autoSavedHinted = java.util.HashSet<String>()
    fun browserPageReady(): Boolean = ::browserPage.isInitialized

    /** 浏览器是否处于打开状态(供扩展文件判断浏览器控制条显隐) */
    internal fun browserOpen(): Boolean = browserPageReady() && browserPage.open
    /** 浏览器页右侧跟手滑入控制器(镜像抽屉): 右缘左滑整页推入, 左缘右滑/✕/返回键推回 */
    private lateinit var browserSlide: BrowserSlideController
    private var summary: String? = null
    internal lateinit var db: MemoryDb
    internal var aiBusy = false
    /** 调试服务 SSE 事件转发(事件名, 数据): 由 DebugServer 挂载, continueSend 各回调处触发 */
    @Volatile internal var debugSseSink: ((String, String) -> Unit)? = null
    /** 调试请求完成回调(整条链路结束, 含成功/失败/取消) */
    @Volatile internal var debugChatDone: (() -> Unit)? = null
    private var currentSaved = false
    internal var currentSessionId: Long? = null
    /** 当前会话标题(随切会话/保存更新), 供记忆落库时快照会话名 */
    internal var currentSessionTitle: String? = null
    /** 本轮 AI 请求所属的会话 id(发起时快照), 回调落地时若已切走则不写入新会话历史 */
    private var replySessionId: Long? = null
    /** 聊天页标题栏右侧 Token 统计下拉面板 */
    internal var tokenPanel: LinearLayout? = null
    /** Token 面板展开时覆盖全屏的透明点击遮罩: 点空白处收起面板 */
    internal lateinit var tokenMask: View
    internal lateinit var titleBar: LinearLayout
    private lateinit var mainTitleText: TextView
    internal lateinit var drawerTitleText: TextView
    internal lateinit var drawerNoteText: TextView
    internal lateinit var tokenPanelCtx: TextView
    internal lateinit var tokenPanelSess: TextView
    // 抽屉头部右上角搜索迷你框: 初始仅图标, 点击向左展开成可输入态
    internal lateinit var searchEdit: EditText
    internal lateinit var searchWrap: LinearLayout
    internal var searchExpanded = false

    internal val DRAWER_WIDTH by lazy { dp(280) }

    // 记忆分级: 短期=最近KEEP条直接进上下文; 更早的交给 MemoryKeeper(辅助AI) 后台压缩
    private val KEEP = 20

    // 气泡配色: AI 侧 4 色(BUBBLE_AI/BUBBLE_AI_TEXT/THINK_TEXT/THINK_BG)已随 AiBubbleHolder 提取至文件级共享
    // 用户气泡配色: 随主题刷新(onCreate 时同步)
    private var BUBBLE_USER: Int = DefaultTheme.bubbleUser
    private var BUBBLE_USER_TEXT: Int = DefaultTheme.bubbleUserText
    private val SYS_TEXT = Ui.SUB

    // 首启权限引导状态: 0=空闲 1=已弹运行时权限 2/3/4=等待从悬浮窗/所有文件/安装未知来源设置页返回
    private var firstRunGuideState = 0

    // ========== 首启权限引导 ==========
    /** 首次进入时一次性收取该要的权限; 只触发一次, 已收齐后不再打扰 */
    private fun runFirstRunPermissionGuide() {
        if (isFinishing || isDestroyed) return
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("first_run_perms_done", false)) return
        // 1) 运行时权限: 通知(Android 13+)/麦克风 合并一次弹出
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (need.isNotEmpty()) {
            firstRunGuideState = 1
            try {
                requestPermissions(need.toTypedArray(), REQ_GUIDE_PERMS)
                return
            } catch (e: Exception) {
                firstRunGuideState = 0
            }
        }
        // 2) 特殊权限依次引导(无需申请的自动跳过)
        startNextSpecialPermission()
    }

    /** 按 悬浮窗->所有文件->安装未知来源 顺序, 逐个唤起未授权项的系统设置页; 全部满足后收尾 */
    private fun startNextSpecialPermission() {
        if (isFinishing || isDestroyed) return
        // 2.1 悬浮窗(API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            firstRunGuideState = 2
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) { firstRunGuideState = 0 }
            return
        }
        // 2.2 所有文件访问权限(API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            firstRunGuideState = 3
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) { firstRunGuideState = 0 }
            return
        }
        // 2.3 安装未知来源(API 26+, 用于 APP 内自更新安装)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            firstRunGuideState = 4
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) { firstRunGuideState = 0 }
            return
        }
        // 全部满足/跳过: 收尾, 永不再引导
        firstRunGuideState = 0
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("first_run_perms_done", true).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initThemeAndWindow()
        // 全程流式: 尽早注入表格主题/密度, 流式渲染器与 markwon 收尾共用同一视觉(避免 markwon lazy 未触发时表格退化)
        MdSpannable.tableTheme = mdTableTheme
        MdSpannable.density = resources.displayMetrics.density
        MdSpannable.codeBlockBg = Ui.INPUT_BG
        MdSpannable.inlineCodeBg = Ui.INPUT_BG
        initConfigs()
        setupBrowserControllers()
        setupSwipeDetector()
        setupRootLayout()
        setupChatList()
        setupBodyAndInputArea()
        setupBrowserBar()
        setupEmojiDrawerAndSideDrawer()
        setupBrowserLayerAndCallbacks()
        setupTokenAndHamburgerPanels()
        setupSystemGestures()
        setupKeyboardInsets()
        finishCreate()
    }


    private fun initThemeAndWindow() {
        // 应用当前主题（默认=现有视觉零变化）：全局语义色 + 气泡色
        val theme = ThemeManager.current(this)
        Ui.applyTheme(theme)
        applyBubbleTheme(theme)
        BUBBLE_USER = theme.bubbleUser
        BUBBLE_USER_TEXT = theme.bubbleUserText
        instance = this
        // 视频气泡并发配额: 按设备内存动态定级; 名额让出广播挂"对账补建", 弹窗关闭/被踢行不滚动也自愈
        ExoGate.initByDevice(this)
        ExoGate.sOnGateReleased = { chatRec?.post { reconcileVideoBubbles(chatRec); reconcileEmojiBubbles(chatRec) } }
        // 2026-09-14 废弃启动强制权限引导: 权限全权交给 FirstRunSetupActivity 逐项授权页
        // (去授权/已完成 + 进入APP) + 功能按需请求, 不再在启动时把全部权限轰炸一遍。
        // window.decorView.post { runFirstRunPermissionGuide() }
        // 悬浮终端显隐门控: DA 前台(应用内)隐藏悬浮窗, 切到其他 APP/回桌面自动显示
        TerminalGate.register(application)
        // 启动自动检查更新（同一天仅一次，静默；真实更新源开源后替换 UPDATE_URL 即可）
        UpdateChecker.check(this, false)
        // 键盘模式: 全局 adjustNothing, 窗口永不被键盘压缩;
        // 通过 WindowInsets.ime()(API30+) 精确取键盘高度, 手动驱动 bodyWrap 平移上移(同微信),
        // 标题栏与背景(挂 root.background)不动。adjustResize 下窗口被系统压缩, ime insets 会被吸收为0无法检测
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.statusBarColor = Ui.SURFACE
        window.navigationBarColor = Ui.SURFACE
        // 状态栏/导航栏图标明暗随主题底亮度自适应: 浅底深图标, 深底浅图标
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dark = (Color.red(Ui.SURFACE) + Color.green(Ui.SURFACE) + Color.blue(Ui.SURFACE)) / 3 < 128
            var vis = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26 && !dark) vis = vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = vis
        }
    }
    private fun initConfigs() {
        ApiConfig.init(this)
        MemoryApiConfig.init(this)   // 辅助模型配置: 冷启动必须先初始化, 否则归档读不到独立配置, 回退主对话
        TitleConfig.init(this)
        UploadConfig.init(this)
        PersonaConfig.init(this)
        ModeConfig.init(this)
        AvatarConfig.init(this)
        lastAvatarStamp = AvatarConfig.avatarStamp()
        LogStore.init(this)
        MemoryKeeper.init(this)
        db = MemoryDb(this)
        summary = db.loadSummary()
        lastModeValue = ModeConfig.modeValue()
        // 记忆落库时快照当前会话标题, 长期记忆卡片按会话名分组展示
        MemoryTools.sessionTitleProvider = { currentSessionTitle }

    }
    private fun setupBrowserControllers() {
        // 抽屉跟手拖拽: root 拦截水平边缘/遮罩手势, 1:1 跟随 + 抬手吸附(替代旧 fling 固定动画)
        drawerDrag = DrawerDragController(this)
        // 右侧整屏浏览器页 + 右缘滑入控制器
        browserPage = BrowserPage(this)
        browserSlide = BrowserSlideController(this)
        // AI open_browser 工具桥接: 主线程打开全屏浏览器页
        LocalEngine.onOpenBrowser = { url ->
            runOnUiThread { browserPage.open(url) }
            // 等待页面加载完成并同步扫描回填, 确保后续 browser_scan/click 拿到新页元素(慢网/重站点防串页)
            if (browserPage.waitLoaded(10_000)) browserPage.scanSync(4000)
        }
        LocalEngine.onBrowserScan = { val c = browserPage.scanSync(4000); browserPage.elementsSnapshot().toString() }
        LocalEngine.onBrowserText = { browserPage.fetchTextSync(4000) }
        LocalEngine.onBrowserScroll = { d -> browserPage.scrollBySync(d) }
        LocalEngine.onBrowserClick = { i -> browserPage.clickIndex(i) }
        LocalEngine.onBrowserType = { i, t -> browserPage.typeIndex(i, t) }
        LocalEngine.onBrowserUpload = { i, local -> browserPage.uploadIndex(i, local) }
        LocalEngine.onBrowserClear = { full -> browserPage.clearCacheForAi(full) }
        LocalEngine.onBrowserSaveCookies = { site -> browserPage.cookieStringFor(site) }
        // AI ask_user 澄清桥接: 主线程弹原生选择框等待用户点选, work 线程阻塞同步返回用户选择回注模型
        LocalEngine.onAskUser = { question, options, allowCustom ->
            val latch = CountDownLatch(1)
            val answer = arrayOfNulls<String>(1)
            val picked = java.util.concurrent.atomic.AtomicBoolean(false)
            runOnUiThread {
                try {
                    showAskUserDialog(question, options, allowCustom) { sel ->
                        if (picked.compareAndSet(false, true)) {
                            answer[0] = sel
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    if (picked.compareAndSet(false, true)) {
                        answer[0] = "弹窗失败: ${e.message}"
                        latch.countDown()
                    }
                }
            }
            // 阻塞等待用户点选(对话框取消/关闭即返回, 此超时仅为极端兜底)
            latch.await(120, TimeUnit.SECONDS)
            val sel = answer[0] ?: "用户未作答(超时/取消)"
            // 交互留痕: 系统气泡 + 记入 session_msgs(主线程)
            val finalSel = sel
            runOnUiThread {
                appendSys(getString(R.string.ask_user_trace, question, finalSel))
                messages.add(MemoryDb.SessionMsg(
                    "system",
                    getString(R.string.ask_user_trace, question, finalSel),
                    "", "", "", System.currentTimeMillis(), -1))
                currentSaved = false
                maybeSaveCurrent()
            }
            sel
        }
        // 自动落盘: 页面加载后检测到当前域 Cookie 变化即写入 site_auth.json(浏览器登录一次, 静默通道自动带登录态)
        browserPage.autoSaveCookie = { host, cookie ->
            val ok = runCatching {
                val jo = org.json.JSONObject()
                    .put("action", "set").put("site", host).put("cookie", cookie)
                WebTools.siteAuth(this, jo.toString())
            }.getOrNull()?.contains("已保存") == true
            if (ok && autoSavedHinted.add(host)) {
                Toast.makeText(this, getString(R.string.ma_site_saved, host), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun setupSwipeDetector() {
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (drawerDrag.isDragging() || browserSlide.isDragging()) return false
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) * 1.5f && abs(dx) > dp(60).toFloat() && abs(velocityX) > 500f) {
                    // 兜底 fling 与控制器方向一致: 展开态仅反方向快甩才收(汉堡=右滑, 抽屉=左滑);
                    // 控制器拖拽中(isDragging)时让位, 避免兜底 fling 与 snap 动画打架(收到半路又弹出)
                    // 关闭态保持分区触发(抽屉=左1/3右滑, 浏览器=右1/3左滑)
                    if (browserPage.hamburgerOpen && dx > 0) browserPage.collapseHamburger()
                    else if (drawerOpen && dx < 0) closeDrawer()
                    else if (dx > 0 && !browserPage.open && (e1?.x ?: e2.x) < resources.displayMetrics.widthPixels / 3f) openDrawer()
                    return true
                }
                return false
            }
        })
    }
    private fun setupRootLayout() {
        root = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (browserSlide.onIntercept(ev)) return true
                if (drawerDrag.onIntercept(ev)) return true
                return super.onInterceptTouchEvent(ev)
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (browserSlide.onTouch(ev)) return true
                if (drawerDrag.onTouch(ev)) return true
                return super.onTouchEvent(ev)
            }
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                swipeDetector.onTouchEvent(ev)
                // 表情抽屉展开时, 点击抽屉外空白区域(消息区/背景/标题栏)收起抽屉;
                // 点击输入框区域不收(交给 input.onClick 兜底收抽屉+弹键盘), 点击抽屉内部不收(交给抽屉自身交互)
                if (ev.action == MotionEvent.ACTION_UP && emojiOpen && ::inputBar.isInitialized &&
                    !isTouchInsideEmojiDrawer(ev.rawX, ev.rawY) && !isTouchInsideAttachPreview(ev.rawX, ev.rawY)) {
                    val il = IntArray(2)
                    inputBar.getLocationInWindow(il)
                    val inInputBar2 = ev.rawY.toInt() >= il[1] - dp(8)
                    if (!inInputBar2) {
                        post {
                            // 先清焦点再收抽屉: hideEmojiDrawer 内部按 input.isFocused 决定是否弹键盘,
                            // 点空白收抽屉不应弹键盘, 故先清焦点
                            if (input.isFocused) input.clearFocus()
                            hideEmojiDrawer()
                        }
                    }
                }
                // 键盘弹起时, 点击输入区以外(消息区/背景/标题栏)收起键盘并清光标; 未弹键盘或点击输入框/按钮时不影响
                // 优化(2026-09-18 全面扫描): hideSoftInput 的 binder 同步调用会阻塞 UP 分发链, post 到下一帧执行
                if (ev.action == MotionEvent.ACTION_UP && ::inputBar.isInitialized &&
                    !isTouchInsideEmojiDrawer(ev.rawX, ev.rawY) && !isTouchInsideAttachPreview(ev.rawX, ev.rawY) &&
                    android.os.SystemClock.uptimeMillis() > suppressHideImeUntil) {
                    val i3 = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (i3?.isActive == true) {
                        val loc = IntArray(2)
                        inputBar.getLocationInWindow(loc)
                        val inInputBar = ev.rawY.toInt() >= loc[1] - dp(8)
                        if (!inInputBar) {
                            post {
                                i3.hideSoftInputFromWindow(input.windowToken, 0)
                                if (input.isFocused) input.clearFocus()
                            }
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            // root 作为透明壳: 壁纸改挂独立 bgLayer, 避免 adjustResize 下窗口被键盘压缩时背景跟随抬起
            setBackgroundColor(Color.TRANSPARENT)
        }
        // 独立固定背景层: 全屏高度锁死, 键盘弹出压缩 root 时背景保持顶部不动(壁纸不跟随抬起)
        bgLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(bgLayer, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 透明: 让挂在 bgLayer 的聊天背景(预设渐变/自定义图)透出来, 否则不透明底色会盖住背景
            setBackgroundColor(Color.TRANSPARENT)
        }
        // 抽屉展开联动: 主界面整体作为"下沉卡片", 圆角 0→20dp 由 outline 驱动(进度 0 时直角不裁剪)
        main.clipToOutline = true
        main.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, mainSinkP * dp(20))
            }
        }

        // 标题栏
        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(8), dp(8), dp(8))
            // 对侧圆角: 顶栏左下/右下两角圆角(内侧两角), 左上/右上直角贴屏顶; 抽屉联动时外侧两角圆角化
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }.also { titleBarBg = it }
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_menu)
            setColorFilter(Ui.TEXT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { openDrawer() }
            Ui.press(this)
        })
        titleBar.addView(TextView(this).apply {
            text = TitleConfig.mainTitle()
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // 点击主页标题进入外观设置（原设置页入口已移除）
            setOnClickListener { startActivity(Intent(this@MainActivity, AppearanceActivity::class.java)) }
            Ui.press(this)
        }.also { mainTitleText = it })
        titleBar.addView(TextView(this).apply {
            text = "∑"
            textSize = 22f
            setTextColor(Ui.TEXT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { toggleTokenPanel() }
            Ui.press(this)
        })
        main.addView(titleBar)
    }
    private fun setupChatList() {
        // 消息区: RecyclerView 可回收传送带——只保留屏幕内可见的气泡, 滚出屏幕即回收销毁,
        // 滚回复用同一框架塞新内容, 不随聊天变长无限堆叠 View(解决 ScrollView+LinearLayout 长会话卡顿/内存增长)
        chatAdapter = ChatAdapter(chatRows, { row -> buildRowView(row) },
            { row -> chatRowPoolType(row) }, { v, row -> bindPooledView(v, row) })
        chatRec = NyralRecyclerView(this).apply {
            layoutManager = NyralLayoutManager(this@MainActivity)
            adapter = chatAdapter
            setPadding(dp(12), dp(10), dp(12), dp(30))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            // 修复1: 关闭 item 变化动画(流式中气泡高度增长触发重排动画=跳动) + 加大离屏缓存
            itemAnimator = null
            setItemViewCacheSize(20)
            // 用户一旦手动滑动(上翻阅读), 标记后不再被自动滚动打断
            // 触摸即冻结(09-25 方案2.1): DOWN 立即冻结打字机渲染使布局稳定, 上滑实时跟手
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = ev.x
                        touchDownY = ev.y
                        scrollUserScrolled = true
                        activeAiHolder?.freezeTypewriter()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 轻按(位移 <= touchSlop)视为"暂停一下想看", 松手恢复慢打;
                        // 滑动(>= touchSlop)视为上翻阅读, 保持冻结布局稳定
                        val dx = ev.x - touchDownX
                        val dy = ev.y - touchDownY
                        if (dx * dx + dy * dy <= touchSlopPx * touchSlopPx) {
                            activeAiHolder?.resumeTypewriter()
                        }
                    }
                }
                false
            }
            // 用户滚回底部附近时恢复自动追底(上翻阅读仅在离开底部期间让位, 复活旧 ScrollView 版 scrollUserScrolled 语义)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                // fix6: Telegram 式拖动列表时收起键盘(用户翻阅历史意图明确), 文字保留不清空
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    // QQ 式滚动暂停: 滚动起即暂停所有内嵌播放器(冻结帧停解码省资源),
                    // 松手先"原地恢复"在屏播放器(不重建不从头重播), 再错峰补建无播放器的视频行。
                    // 此前松手无条件 notify 所有可见视频行——连正在播的行也整行重建 → 从头重播闪变
                    // + 整行重绑卡顿(09-19 用户反馈"概率性播放、卡顿依旧"主因之一)
                    val wasScrolling = sScrolling
                    sScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                    if (sScrolling && !wasScrolling) {
                        for (p in sBubblePlayers.toList()) { try { p.pause() } catch (_: Exception) {} }
                        // v6: 帧动画统一暂停(滚动中不刷帧), 停止后由对账 resumeIfReady 原地续播
                        EmojiFrameAnimator.onScrollStart()
                    }
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && wasScrolling) {
                        // QQ 式统一对账: 以当前可见行为唯一真相, 杀离屏/续播可见/按序补建,
                        // 名额只发可见行, 不再有"离屏行抢名额"的打架(09-19 15 视频连续发送反馈重构)
                        // 补建放下一帧: 避免松手帧同步连建多个 ExoPlayer prepare 硬解码卡顿
                        // 表情气泡同样需要滚动停止对账: 滑动中降级的表情行在此补建恢复播放
                        rv.post { reconcileVideoBubbles(rv); reconcileEmojiBubbles(rv) }
                    }
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        // 静态上滑时刷新一键到底显隐(09-24): 输出中按钮隐藏后, 显隐刷新只剩
                        // 输出中的高频调用点, 静态滚动后无人调 -> 按钮永远不出现
                        updateJumpFab()
                        // 修复10: 包在 post 里——hideSoftInput 的 binder 同步调用会阻塞 touch 分发链,
                        // 拖动起始帧被卡 = "停顿一瞬才继续滚动"; post 到下一帧执行避开关键帧
                        rv.post {
                            val act = context as? android.app.Activity
                            val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            act?.currentFocus?.let { f ->
                                imm?.hideSoftInputFromWindow(f.windowToken, 0)
                                f.clearFocus()
                            }
                        }
                    }
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        updateJumpFab()   // 惯性滚动停: 按最终位置刷新显隐(09-24)
                        // 根治(09-27): 滚动停止后统一执行滑动中挂起的 markdown 替换,
                        // 此刻 sScrolling=false, watchHeightDrift 逐帧补偿钉住阅读位置,
                        // 滑动中不再出现占位→替换的高度突变推挤(多气泡跳)
                        if (pendingMdReplacements.isNotEmpty()) {
                            Log.d("DRIFTDBG", "flush-pending start n=" + pendingMdReplacements.size + " (idle replace defer, batched)")
                            flushPendingMdReplacements()
                        }
                    }
                }
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    // 覆盖式下拉面板不随 item 滚动: 一滚动立即收起(09-25)
                    // 09-25 崩溃修复: onScrolled 可能在 RV 布局帧内同步回调, 直接 removeView
                    // 会破坏 chatArea 正在进行的 layoutChildren 遍历(child.getVisibility NPE on null);
                    // post 延迟到布局遍历完成后移除, 幂等(removeDropPanel 已判 parent)
                    if (statusDropPanel != null) {
                        chatArea.post { if (statusDropPanel != null) dismissStatusDrop(false) }
                    }
                    // 正文跟随中用户手动上翻 -> 让位停滚, 一键到底按钮重新出现
                    if (scrollUserScrolled && contentFollow) {
                        contentFollow = false
                        updateJumpFab()
                    }
                    // FAB 显隐兜底(09-24): 显隐刷新点不全(视口冻结恢复等程序滚动后无人调),
                    // 每帧轻量比对期望态, 不一致才刷新(程序/用户滚动全覆盖)
                    val fabV = jumpFab
                    if (fabV != null && (fabV.visibility == View.VISIBLE) !=
                        (!aiBusy && !contentFollow && rv.canScrollVertically(1))) {
                        updateJumpFab()
                    }
                    if (!scrollUserScrolled) return
                    // 吸底修复: 原判据 findLastVisibleItemPosition >= count-2 在长气泡场景恒真
                    // (末条比视口高时上翻中视口底端始终"看得见"末条) → 误翻 false → 程序拉底畅通;
                    // 改用 canScrollVertically(1): 真的无法再向下滚才算贴底
                    // 吸底复发修复(2026-09-18 录屏13s形态): 占位→渲染替换使列表高度瞬时变化,
                    // RV clamp 滚动位置会出现单帧"伪贴底"(canScrollVertically(1) 瞬时 false),
                    // 旧逻辑一帧即解除 scrollUserScrolled 守卫, 随后流式/迟到拉底畅通无阻
                    // = 上翻历史被瞬间抽到最新消息(快速吸底);
                    // 改为 postOnAnimation 下一动画帧(当帧布局完成后)二次确认, 布局挤动的
                    // 单帧误判被滤除, 只有真稳定贴底才恢复自动追底
                    // 修复2(09-27): 渲染在途(mdRenderPending>0)期间列表高度偏小会造成
                    // 跨多帧的"伪贴底"(占位高度 < 渲染后高度), 旧二次确认照样放行;
                    // 渲染替换完成那一下高度增长 + 守卫已解除 = 自动追底猛推跳变,
                    // 故在途期间冻结贴底判定, 渲染完成且真贴底才恢复
                    if (!rv.canScrollVertically(1) && mdRenderPending.get() == 0) {
                        rv.postOnAnimation {
                            if (!rv.canScrollVertically(1) && mdRenderPending.get() == 0) {
                                scrollUserScrolled = false
                                activeAiHolder?.resumeTypewriter()   // 滚回底部: 恢复慢打(09-25)
                                updateJumpFab()   // 贴底恢复追底: 按需隐藏按钮(09-24)
                            }
                        }
                    }
                }
            })
        }
        chatAdapter.recyclerView = chatRec
        applyChatBackground()
    }
    private fun setupBodyAndInputArea() {
        // 内容容器: chatRec+inputBar 整体, 键盘弹出时高度动画缩小 = 消息+输入框上移, 标题栏与背景不动(同微信)
        bodyWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 消息区独立 FrameLayout: browserBar 悬浮 overlay 不占位, 聊天区高度恒定防气泡抖动
        chatArea = FrameLayout(this)
        chatArea.addView(chatRec, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bodyWrap.addView(chatArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // assistant 式正文一键到底: 正文输出默认停滚(视口停留, 内容在屏外增长), 右下角悬浮按钮
        // 提示"有新内容", 点击后跳到底部并恢复正文跟随(锚底接管原地生长), 上翻阅读再次让位
        jumpFab = android.widget.TextView(this).apply {
            text = "↓"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
            }
            visibility = View.GONE
            elevation = dp(6).toFloat()
            setOnClickListener {
                // 一键到底(09-24 定稿): 仅静态出现(输出中隐藏), 点击=一次性回最新消息,
                // 无跟随语义(跟随=看打字动画非阅读); 输出中想看进度直接手动滚到底即可
                scrollUserScrolled = false   // 解除上翻守卫, 允许回底
                activeAiHolder?.resumeTypewriter()   // 一键到底: 恢复慢打(09-25)
                scrollToBottom(true, force = true)
                updateJumpFab()
            }
        }
        chatArea.addView(jumpFab, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(14)
            bottomMargin = dp(14)
        })

        // 输入框整体(dockContent)直接挂 bodyWrap 底部(基础层), 表情抽屉在其下占位,
        // 抽屉展开时输入框被顶到抽屉上方(微信式)
        dockContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 附件预览条: 选中附件出现在输入框上方, 可补文字后一并发送; 默认隐藏, 有附件才显示
        attachPreviewWrap = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        attachPreviewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        attachPreviewWrap.addView(attachPreviewRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dockContent.addView(attachPreviewWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 输入区: 按钮在输入框右侧, 底对齐固定在右下角
        inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            // 对侧圆角: 底栏左上/右上两角圆角(内侧两角), 左下/右下直角贴屏底; 抽屉联动时外侧两角圆角化
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }.also { inputBarBg = it }
        }
        input = object : EditText(this) {
            // 回车去重: DOWN 放行后系统默认 KeyListener 已插入 \n; 若 IME 再 commitText 纯 "\n" 则丢弃,
            // 防微信输入法(sendKeyEvent + commitText 双路径)导致的一次回车双换行
            var downPassed = false
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val base = super.onCreateInputConnection(outAttrs) ?: return null
                val self = this
                return object : InputConnectionWrapper(base, true) {
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        val t = text?.toString() ?: ""
                        if ((t == "\n" || t == "\r\n") && self.downPassed) {
                            self.downPassed = false
                            return true
                        }
                        return super.commitText(text, newCursorPosition)
                    }
                }
            }
        }.apply {
            var lastEnterInsert = 0L
            var nlCount = 0
            hint = getString(R.string.ma_hint_input)
            textSize = 15f
            // 显式声明多行文本类型: 未设 MULTI_LINE 时部分输入法会错误地把回车按两次插入
            setInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(dp(22), Ui.INPUT_BG)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.SUB)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) hideEmojiDrawer() }
            // 回车放行 DOWN(系统默认 KeyListener 插入 \n, 百度 sendKeyEvent 路径); 吞掉 UP/MULTIPLE 防重复。
            // 微信输入法在 DOWN 系统插入后还会 commitText("\n"), 由 InputConnection 包装层去重丢弃, 避免双换行。
            setOnKeyListener { v, keyCode, e ->
                if (keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> {
                        downPassed = true
                        false
                    }
                    KeyEvent.ACTION_UP -> true
                    KeyEvent.ACTION_MULTIPLE -> true
                    else -> false
                }
            }
            // 键盘收起(光标已清)后再次点击: 只恢复焦点与键盘; 不再强制 setSelection 到末尾,
            // 光标定位交给系统默认(点哪光标哪), 否则无法点击定位到任意文本位置
            // 抽屉展开时 input 保持聚焦, 再点击不触发 focus change(不会走上面的 hideEmojiDrawer),
            // 必须在这里兜底收起抽屉, 否则抽屉占位残留, 之后收键盘时输入框停在抽屉顶位收不回去
            setOnClickListener { view ->
                if (emojiOpen) {
                    // 先确保聚焦再收抽屉: hideEmojiDrawer 按 input.isFocused 判 willShowIme,
                    // 失焦时走"清占位+恢复动画"与随后键盘 insets 压缩打架 -> 输入框沉底/键盘弹了输入框不升/分不清谁是谁
                    if (!input.isFocused) input.requestFocus()
                    hideEmojiDrawer()
                }
                view.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(view, 0)
            }
            // 底栏交互: 输入文字时隐藏麦克风显示发送按钮, 清空后恢复麦克风
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                // 曾在此把 \n\n 归一为单个 \n 兜底"输入法双行"——但会吞掉用户刻意连续换行/空行分段，
                // 导致回车只能换一行; 现 MULTI_LINE 输入类型 + Enter 深度拦截已防双行, 删除归一以支持自由多行
                override fun afterTextChanged(s: Editable?) {
                    val n = s?.count { it == '\n' } ?: 0
                    if (n > nlCount) lastEnterInsert = android.os.SystemClock.uptimeMillis()
                    nlCount = n
                    updateInputMode()
                }
            })
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // 输入区容器: 输入框与"按住说话"胶囊叠放在同一 FrameLayout 内, speakBar 作为浮层盖在 input 上
        // 语音模式下 input 用 INVISIBLE(仍占位不重排), 收起结束 INVISIBLE->VISIBLE 不触发 layout, 杜绝闪框
        val inputArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputArea.addView(input)
        val modelWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        modelBtn = Button(this).apply {
            text = ""
            // 图标画进 background: 圆角底+居中箭头, 天然居中不偏左
            background = modelIconBg(resources.displayMetrics.density)
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                marginEnd = dp(8)
                bottomMargin = dp(10)
            }
            setOnClickListener { showModelList() }
            Ui.press(this)
        }
        modelWrap.addView(modelBtn)
        inputBar.addView(modelWrap)
        inputBar.addView(inputArea)
        // 按住说话条: 语音模式下以浮层盖在输入框上, 长按录音松手发送, 上滑取消, <1秒不发送
        speakBar = TextView(this).apply {
            text = getString(R.string.ma_hold_to_speak)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(dp(22), Color.parseColor("#9AA0A6"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            setOnTouchListener { _, ev -> handleSpeakTouch(ev); true }
        }
        inputArea.addView(speakBar)
        // 录音入口(麦克风): 点击在 麦克风图标(文字输入) 与 键盘图标(语音模式) 间切换
        // 槽A: 固定 36dp, 与槽B(attachWrap)共同保证输入框左右宽距恒定
        // 无字→语音按钮; 输入文字→切换为附件按钮(原地替换, 不移动)
        val micWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(36), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(8)
            }
        }
        micBtn = Button(this).apply {
            text = ""
            background = micIconBg(false, density = resources.displayMetrics.density)
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                // 底边距由下方 onGlobalLayout 统一精确计算, 此处只给初始占位值
                bottomMargin = dp(10)
            }
            // 仅当前模型支持语音时展示(预设查内置表, 手动按配置勾选)
            visibility = if (currentModelSupportsVoice()) View.VISIBLE else View.GONE
            setOnClickListener { toggleVoiceMode() }
        }
        micWrap.addView(micBtn)
        attachBtn2 = Button(this).apply {
            text = ""
            background = attachIconBg(resources.displayMetrics.density)
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                bottomMargin = dp(10)
            }
            // 默认隐藏: 输入文字后由 updateInputMode 切换为显示(语音槽让位给附件)
            isFocusable = false
            visibility = View.GONE
            setOnClickListener { toggleEmojiDrawer() }
        }
        micWrap.addView(attachBtn2)
        inputBar.addView(micWrap)
        // 附件入口(+): 输入框与发送按钮之间, 点击弹出相册/文件/音频
        attachWrap = FrameLayout(this).apply {
            // 固定 36dp 槽位: 附件/发送/停止 三按钮叠放共用此位置, 原地替换内容,
            // 输入框左右宽距与按钮位置全程恒定, 不再随文字状态跳动
            layoutParams = LinearLayout.LayoutParams(
                dp(36), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(8)
            }
        }
        attachBtn = Button(this).apply {
            text = ""
            background = attachIconBg(resources.displayMetrics.density)
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                // 底边距由 onGlobalLayout 统一计算, 此处只给初始占位值
                bottomMargin = dp(10)
            }
            isFocusable = false
            setOnClickListener { toggleEmojiDrawer() }
        }
        attachWrap.addView(attachBtn)
        sendBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_send)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
            background = rounded(dp(16), Ui.PRIMARY)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                bottomMargin = dp(10)
            }
            // 默认隐藏发送按钮, 输入文字时切换显示 (见 updateInputMode); 与附件按钮原地替换
            visibility = View.GONE
            setOnClickListener {
                // 发送轻震确认(与左抽屉同款, 尊重系统触觉开关, 无需 VIBRATE 权限)
                // 空输入且无附件时不震(与 doSend 判空一致)
                val hasContent = input.text.toString().trim().isNotEmpty() || pendingAttachments.isNotEmpty()
                if (hasContent) {
                    performHapticFeedback(
                        if (android.os.Build.VERSION.SDK_INT >= 27)
                            android.view.HapticFeedbackConstants.CLOCK_TICK
                        else android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
                onSend()
            }
            Ui.press(this)
        }
        attachWrap.addView(sendBtn)
        stopBtn = ImageView(this).apply {
            setImageResource(R.drawable.lucide_square)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
            background = rounded(dp(18), Ui.PRIMARY)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            visibility = View.GONE
            // 固定 36dp, 与附件/发送同槽位叠放
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener {
                LocalEngine.requestCancel()
                stopBtn.isEnabled = false
                android.util.Log.i("Nyral", "stop clicked, cancelRequested=${LocalEngine.cancelRequested}")
            }
            Ui.press(this)
        }
        attachWrap.addView(stopBtn)
        inputBar.addView(attachWrap)
    }
    private fun setupBrowserBar() {
        // 浏览器控制条: 悬浮聊天时位于输入框上方(欢迎文字 + 接管按钮), 浏览器关闭时隐藏
        browserBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            // 展开态四角圆角(与顶/底栏 16dp 同语言); clipToOutline 裁剪子 view 不溢出圆角
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
            visibility = View.GONE
        }
        browserBarStatus = TextView(this).apply {
            text = getString(R.string.ma_welcome)
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        browserBar.addView(browserBarStatus)
        browserBarPanel = TextView(this).apply {
            text = "面板"
            textSize = 13f
            setTextColor(Ui.PRIMARY)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Ui.INPUT_BG)
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { toggleBrowserPanel() }
            Ui.press(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }.apply {
            val d = getDrawable(R.drawable.ic_menu)?.mutate()
            d?.setTint(Ui.PRIMARY)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            compoundDrawablePadding = dp(5)
        }
        browserBar.addView(browserBarPanel)
        browserBarTakeover = TextView(this).apply {
            text = "接管"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Ui.PRIMARY)
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { browserPage.toggleTakeover() }
            Ui.press(this)
        }.apply {
            val d = getDrawable(R.drawable.ic_mouse_pointer_click)?.mutate()
            d?.setTint(Color.WHITE)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            compoundDrawablePadding = dp(5)
        }
        browserBar.addView(browserBarTakeover)
        // 悬浮 overlay 挂聊天区底部(输入框上方), 不占位挤压 chatRec; bottomMargin 在 setChatFloatMode 中动态对齐 inputBar
        chatArea.addView(browserBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // 折叠手柄 ⌃: 贴输入框上边, 默认收起只露手柄, 点开展开控制条(接管按钮+状态), 再点收起
        browserBarToggle = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_up)
            setColorFilter(Ui.SUB)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#22000000"))
                cornerRadius = dp(9).toFloat()
            }
            setOnClickListener { toggleBrowserBar() }
            Ui.press(this)
            visibility = View.GONE
        }
        chatArea.addView(browserBarToggle, FrameLayout.LayoutParams(
            dp(44), dp(18),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        // 展开态点击其它区域自动收回: 透明遮罩盖浏览器页/聊天区(控制条与手柄在其上不受影响), 点击即收起
        collapseMaskChat = View(this).apply {
            setBackgroundColor(0x01000000)
            setOnClickListener { if (browserBarExpanded) toggleBrowserBar() }
        }
        collapseMaskWeb = View(this).apply {
            setBackgroundColor(0x01000000)
            setOnClickListener { if (browserBarExpanded) toggleBrowserBar() }
        }
    }
    private fun setupEmojiDrawerAndSideDrawer() {
        dockContent.addView(inputBar)
        // 键盘/输入框交接处分割线: 颜色取浏览器主页黑蓝渐变(linear-gradient 160deg #0d1b2a→#1b2a4a→#274060)
        dockContent.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF0d1b2a.toInt(), 0xFF1b2a4a.toInt(), 0xFF274060.toInt())
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        // 一体式(定稿): 表情抽屉焊进 dockContent(输入框+表情区=大抽屉), 不再独立占位。
        // 大抽屉总高=输入框高+表情区高; 键盘 insets 压缩 bodyWrap 顶起整个大抽屉,
        // 表情区与键盘互斥占用底部空间(emojiH = emojiDrawerTargetH - imeH), 切换时输入框零位移
        val emojiDrawerRoot = buildEmojiDrawer()
        dockContent.addView(emojiDrawerRoot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0))
        bodyWrap.addView(dockContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        main.addView(bodyWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 左侧抽屉: 遮罩 + 面板
        drawerMask = View(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            alpha = 0f
            visibility = View.GONE
            elevation = dp(8).toFloat()
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerMask, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildDrawer()
        // 全局已 adjustNothing(见 onCreate 开头): 键盘始终纯悬浮, 主界面与抽屉底部都不被顶起, 无需按焦点切换
        root.addView(drawerPanel, FrameLayout.LayoutParams(
            DRAWER_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    }
    private fun setupBrowserLayerAndCallbacks() {
        // 浏览器作底层内容层: 先于 main 挂载(同父容器后 addView 在上), 聊天层悬浮其上
        root.addView(browserPage.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        root.addView(main)
        // 悬浮切换动画结束后: 把所有流式行 bubbleBox 内部文字类气泡背景统一到目标色(颜色通道带目标 alpha),
        // 流式行是独立 View 常驻 bubbleBox, 不随 item 重绘, 需主动收尾(滚出滚回不再闪变)
        // 关键: 只改颜色值, 不再碰 drawable.alpha——否则与颜色里的 alpha 叠加相乘, 会出现 30% 级过透(用户反馈"100→30→50")
        // 注: Kotlin 局部函数不可前向引用, 必须先于 animateBubbleFloat 声明
        fun applyFloatAlphaToStreaming(float: Boolean) {
            val list = chatAdapter.currentList
            for (i in list.indices) {
                val row = list[i]
                if (row !is ChatRow.Streaming) continue
                row.bubbleBox?.let { box ->
                    fun apply(v: View) {
                        if (v.tag == NO_FLOAT_TAG) return
                        (v.background as? android.graphics.drawable.GradientDrawable)?.let { d ->
                            val cur = d.color?.defaultColor ?: 0
                            if (cur != 0) {
                                val rgb = cur and 0x00FFFFFF
                                val g = d.mutate() as android.graphics.drawable.GradientDrawable
                                g.setColor(
                                    if (float) rgb or (FLOAT_BUBBLE_ALPHA shl 24)
                                    else rgb or 0xFF000000.toInt()
                                )
                            }
                        }
                        if (v is ViewGroup) for (j in 0 until v.childCount) apply(v.getChildAt(j))
                    }
                    apply(box)
                }
            }
        }

        // 悬浮模式气泡背景渐变: 打开浏览器 原色→半透明色, 关闭还原; 全程只做颜色值渐变(ofArgb 思路),
        // 与 floatBubbleColor 的"颜色里带 alpha"保持同一通道, 杜绝 drawable.alpha × 颜色 alpha 双重叠加;
        // 图片/视频气泡(背景 null 或 NO_FLOAT_TAG)跳过; 普通消息滚出视野的行由 notifyDataSetChanged 重绘兜底,
        // 流式行(bubbleBox 常驻)由 applyFloatAlphaToStreaming 统一到目标色(滚出滚回一致)
        // 注: Kotlin 局部函数不可前向引用, 两个辅助函数必须先于 setChatFloatMode 声明
        fun animateBubbleFloat(float: Boolean) {
            // 关键: 先切全局取色状态, 动画结束后的 notifyDataSetChanged 重绘/新建气泡才能取到带 alpha 的目标色,
            // 否则动画只改了可见行背景, 重绘瞬间又按普通模式原色弹回(表现为"闪一下恢复原样")
            chatFloatMode = float
            // 注意: 不做 notifyDataSetChanged 全量重绑——重绑+scrollToPositionWithOffset 恢复位置会触发整表重绘两帧,
            // 表现为气泡"闪/抖动"; 普通行滚回时 onBind 会按 chatFloatMode 取目标色, 流式行由 applyFloatAlphaToStreaming 兜底
            val targets = ArrayList<android.graphics.drawable.GradientDrawable>()
            val fromColors = ArrayList<Int>()
            fun collect(v: View) {
                if (v.tag == NO_FLOAT_TAG) return
                (v.background as? android.graphics.drawable.GradientDrawable)?.let { g ->
                    val cur = g.color?.defaultColor ?: 0
                    if (cur != 0) {
                        targets.add(g.mutate() as android.graphics.drawable.GradientDrawable)
                        fromColors.add(cur)
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            for (i in 0 until chatRec.childCount) collect(chatRec.getChildAt(i))
            if (targets.isEmpty()) {
                // 无可见气泡: 流式兜底保证状态一致(不重绑, 避免整表闪动)
                applyFloatAlphaToStreaming(float)
                return
            }
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    val evaluator = android.animation.ArgbEvaluator()
                    for (idx in targets.indices) {
                        val g = targets[idx]
                        val rgb = fromColors[idx] and 0x00FFFFFF
                        val to = if (float) rgb or (FLOAT_BUBBLE_ALPHA shl 24) else rgb or 0xFF000000.toInt()
                        g.setColor(evaluator.evaluate(t, fromColors[idx], to) as Int)
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // 流式行背景统一目标色(普通行滚回 onBind 自动取新色, 不做全量重绑防闪动)
                        applyFloatAlphaToStreaming(float)
                    }
                })
                start()
            }
        }

        // 悬浮聊天模式: 浏览器打开时 main 底部让出接管按钮区并半透明化; 接管时聊天层沉底(GONE)
        fun setChatFloatMode(float: Boolean) {
            // 接管按钮/欢迎文字已上移到输入框上方控制条, main 不再让出底部空间(输入框贴底)
            val lp = main.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = 0
            main.layoutParams = lp
            if (float) {
                // 悬浮 overlay: 手柄贴输入框顶, 控制条悬浮在手柄上方(底边=输入框顶-手柄高), 消除渲染缝隙
                val cLoc = IntArray(2)
                val iLoc = IntArray(2)
                chatArea.getLocationInWindow(cLoc)
                inputBar.getLocationInWindow(iLoc)
                val base = (cLoc[1] + chatArea.height - iLoc[1]).coerceAtLeast(0)
                val tlp = browserBarToggle.layoutParams as FrameLayout.LayoutParams
                tlp.bottomMargin = base
                browserBarToggle.layoutParams = tlp
                val blp = browserBar.layoutParams as FrameLayout.LayoutParams
                blp.bottomMargin = base + dp(18)
                browserBar.layoutParams = blp
                // 手柄常驻; 控制条按展开态显隐(默认收起); 幂等保护 onPreOpen/onOpenChange 双调用
                browserBar.animate().cancel()
                browserBarToggle.visibility = View.VISIBLE
                browserBarToggle.alpha = 1f
                if (browserBarExpanded) {
                    if (browserBar.visibility != View.VISIBLE) {
                        browserBar.alpha = 1f
                        browserBar.visibility = View.VISIBLE
                    }
                } else {
                    browserBar.visibility = View.GONE
                }
            } else {
                browserBar.animate().cancel()
                browserBar.visibility = View.GONE
                browserBarToggle.animate().cancel()
                browserBarToggle.visibility = View.GONE
                // 关闭浏览器时复位控制条展开态: 重开后保持收起(只露手柄), 控制条不自动弹出
                browserBarExpanded = false
                browserBarToggle.rotation = 0f
                removeCollapseMasks()
            }
            animateBubbleFloat(float)
        }
        // 动画开始前先收缩浏览器窗口到中间区域(标题栏下~输入框上), 消除"先全屏再跳变"闪一下
        browserPage.onPreOpen = {
            setChatFloatMode(true)
            browserPage.setBottomTakeoverVisible(false)
            adjustBrowserWindow()
        }
        browserPage.onOpenChange = { open ->
            if (open) {
                setChatFloatMode(true)
                browserPage.setBottomTakeoverVisible(false)  // 悬浮模式: 底部按钮让位于控制条
                // 兜底: 任何路径重开浏览器都按 taken 现状同步控制条按钮文字/状态, 防残留"交还 AI"
                browserBarTakeover.text = if (browserPage.taken) "交还 AI" else "接管"
                browserBarStatus.text = if (browserPage.taken) "你已接管，可点击网页（如验证码）" else "欢迎回来 · 一切就绪"
                adjustBrowserWindow()
                // 抽屉展开中打开浏览器: 窗口按当前 sink 缩放/下沉, 与 main 保持一致, 防整屏错位
                if (mainSinkP > 0f) {
                    val s = 1f - 0.12f * mainSinkP
                    browserPage.root.scaleX = s
                    browserPage.root.scaleY = s
                    browserPage.root.translationY = dp(24) * mainSinkP
                }
            } else {
                setChatFloatMode(false)
                main.visibility = View.VISIBLE
                chatRec.visibility = View.VISIBLE
                browserPage.setBottomTakeoverVisible(false)
                // 窗口尺寸常驻: 不还原全屏, 下次打开/手势拖动直接是中间尺寸, 消除"先全屏再跳变"
            }
        }
        browserPage.onTakeoverChange = { taken ->
            // 窗口化交互: 接管仅隐藏消息区, 标题栏/输入框/控制条保留, 浏览器窗口尺寸不变
            chatRec.visibility = if (taken) View.INVISIBLE else View.VISIBLE
            browserBarTakeover.text = if (taken) "交还 AI" else "接管"
            val d = getDrawable(if (taken) R.drawable.ic_bot else R.drawable.ic_mouse_pointer_click)?.mutate()
            d?.setTint(Color.WHITE)
            browserBarTakeover.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            browserBarStatus.text = if (taken) "你已接管，可点击网页（如验证码）" else "欢迎回来 · 一切就绪"
            browserPage.setBottomTakeoverVisible(false)
        }
        browserPage.onHamburgerChange = { open ->
            // 汉堡面板已提升到 root 最顶层, 直接覆盖聊天层; 接管时消息区已隐藏, 无需再 GONE main
            browserPage.setBottomTakeoverVisible(false)
        }
    }
    private fun setupTokenAndHamburgerPanels() {
        // Token 面板外点遮罩: 全屏透明, 面板展开时可见, 点击即收起; 置于面板之下、聊天内容之上
        tokenMask = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { hideTokenPanel() }
        }
        root.addView(tokenMask, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Token 统计面板改为 root 悬浮层: 锚定标题栏右侧下方, 不参与 main 布局流, 展开时不会推挤聊天记录
        root.addView(buildTokenPanel(), FrameLayout.LayoutParams(
            resources.displayMetrics.widthPixels / 2,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))
        // 汉堡面板提升到 root 最顶层(覆盖聊天层): 从 browserPage.root 移除并挂到 root 最上, 展开时聊天层不再 GONE(消除闪白)
        browserPage.root.removeView(browserPage.hamburgerMask)
        browserPage.root.removeView(browserPage.hamburgerPanel)
        root.addView(browserPage.hamburgerMask, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(browserPage.hamburgerPanel, FrameLayout.LayoutParams(
            dp(300), (resources.displayMetrics.heightPixels * 78 / 100), Gravity.CENTER))
        browserPage.hamburgerPanel.translationX = (resources.displayMetrics.widthPixels + dp(300)) / 2f
        // 右缘也注册系统手势排除区: 避免手势导航把"右缘左滑"误判为系统返回, 与左缘抽屉同策略
        if (Build.VERSION.SDK_INT >= 29) {
            browserPage.root.addOnLayoutChangeListener { _, l, _, r, b, _, _, _, _ ->
                if (browserPage.root.isAttachedToWindow && r - l > 0 && b > 0) {
                    try {
                        browserPage.root.setSystemGestureExclusionRects(listOf(
                            android.graphics.Rect(r - l - dp(72), 0, r - l, b)))
                    } catch (_: Exception) {}
                }
            }
        }
    }
    private var hamburgerExclusionActive = false

    /** 汉堡面板展开时屏蔽整屏系统返回手势: 右缘左滑不再被当返回收起面板; 收起时恢复抽屉/浏览器默认排除区 */
    fun setHamburgerGestureExclusion(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            hamburgerExclusionActive = enabled
            try {
                root.systemGestureExclusionRects = if (enabled) {
                    listOf(Rect(0, 0, root.width, root.height))
                } else {
                    listOf(
                        Rect(0, 0, DRAWER_WIDTH, root.height),
                        Rect(root.width - root.width / 3, 0, root.width, root.height)
                    )
                }
            } catch (_: Exception) {}
        }
    }
    private fun setupSystemGestures() {
        setContentView(root)
        // 全面屏手势导航(Android10+): 左边缘横滑默认是系统"返回", 会抢走抽屉跟手手势。
        // 学 AndroidX DrawerLayout / QQ 侧边栏: 把整块抽屉区域(左缘 0..280dp 宽, 全屏高)声明为系统手势排除区,
        // 该区域内系统返回全程让位, 边缘慢拖 1:1 跟手; 区域之外(屏幕右侧/中间)系统返回照常。
        // (系统文档称每边沿边长度限 200dp, 但 DrawerLayout 式整块排除在 Android12+ 及国产 ROM 实践中全屏有效, 微信/QQ 同款)
        if (Build.VERSION.SDK_INT >= 29) {
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.height > 0) {
                    // 汉堡展开期间保持全屏排除(由 setHamburgerGestureExclusion 管理); 否则恢复默认排除区
                    v.systemGestureExclusionRects = if (hamburgerExclusionActive) {
                        listOf(Rect(0, 0, v.width, v.height))
                    } else {
                        listOf(
                            Rect(0, 0, DRAWER_WIDTH, v.height),
                            Rect(v.width - v.width / 3, 0, v.width, v.height)
                        )
                    }
                }
            }
        }
        // 布局完成后锁定 bgLayer 为全屏高度(首次布局未弹键盘, root.height 即完整高度);
        // 此后键盘弹出压缩 root 时 bgLayer 保持固定, 背景顶部不动、底部被键盘盖住(壁纸不跟随抬起)
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rh = root.height
                if (rh > 0) {
                    val lp = bgLayer.layoutParams as FrameLayout.LayoutParams
                    if (lp.height != rh) {
                        lp.height = rh
                        lp.gravity = Gravity.TOP
                        bgLayer.layoutParams = lp
                    }
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
        // 首次布局完成即预锁定浏览器窗口尺寸(浏览器初始在屏幕外, 不可见): 打开/手势拖动时已是中间尺寸, 消灭"先全屏再跳变"
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (::browserPage.isInitialized && titleBar.height > 0 && inputBar.height > 0) {
                    adjustBrowserWindow()
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
        // 布局完成后(OnGlobalLayout保证高度已测量): 按单行输入框中心精确计算按钮bottomMargin, 只执行一次;
        // 之后输入多行margin不变 -> 按钮相对屏幕底部位置固定, 与输入框居中
        // 所有底栏按钮(micBtn/attachBtn/modelBtn/sendBtn/stopBtn)统一走同一套计算, 不再各自硬编码偏移
        inputBar.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val ibh = inputBar.height
                val ih = input.height
                if (ibh > 0 && ih > 0) {
                    val bh = dp(36)
                    // 以输入框中心为基准算出底边距, 再统一微调 7dp 抵消视觉偏上(作用于全部按钮)
                    val bm = (ibh - inputBar.paddingTop - ih / 2f - bh / 2f).toInt() - dp(7)
                    val setMargin = { btn: View ->
                        btn.layoutParams = (btn.layoutParams as FrameLayout.LayoutParams).apply {
                            gravity = Gravity.BOTTOM
                            bottomMargin = bm
                        }
                    }
                    setMargin(modelBtn)
                    setMargin(micBtn)
                    setMargin(attachBtn2)
                    setMargin(attachBtn)
                    setMargin(sendBtn)
                    setMargin(stopBtn)
                    inputBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }
    private fun setupKeyboardInsets() {
        // 键盘检测: WindowInsets.ime()(API30+) 精确报告键盘高度(adjustNothing 下不被窗口吸收)。
        // setDecorFitsSystemWindows(false) 让系统不自动消化 insets, 完整派发到内容层(含 IME), 我们统一处理:
        // 主内容避开状态栏/导航栏(fitsSystemWindows 的替代), 键盘弹起 bodyWrap 高度压缩到键盘顶,
        // 键盘收起(返回键/下滑/点空白) bodyWrap 恢复满高且输入框光标跟随关闭, 再次点击输入框可恢复继续输入
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            var imeFloating = false  // QQ 式悬浮模式: 惯性滚动中弹键盘, bodyWrap 不压缩, 仅输入区悬浮到键盘顶
            var imeAnimator: android.animation.ValueAnimator? = null
            var bodyWrapFullH = 0
            var lastImeH = 0
            var prevCompressH = 0  // 压缩模式上一帧 bodyWrap 高度, 用于末条不可见时按压缩增量滚动
            var lastProgressImeH = 0  // onProgress 最后一帧键盘高度, onEnd 据此判断动画方向(收起方向需兜底恢复)
            var animSbBottom = 0  // 动画开始前导航栏高快照: 动画期间 systemBars 读数会漂移(实测 sb 48->248), 恢复基准需用快照
            var animStartImeH = 0  // 动画开始前 ime 高快照: onEnd 判断动画方向(弹出/收回), 收回结束立即恢复不等最终 insets 回调
            var imeFloatingListener: RecyclerView.OnScrollListener? = null
            // 键盘收回时 bodyWrap 平滑补完剩余距离: 系统 insets 动画最后几帧 onProgress 可能提前停止,
            // bodyWrap 停在中间高度, 直接恢复权重会从中间跳变到满高(停顿卡一下);
            // 用短动画把剩余高度补完再还原权重, 与键盘收回观感无缝衔接
            fun smoothRestoreBodyWrap() {
                // 补完动画已在跑: 不重启, 交给它收尾(insets 回调与 onEnd post 可能双触发)
                if (imeAnimator?.isRunning == true) return
                val lp0 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                if (lp0.weight == 1f) return
                val remain = bodyWrapFullH - lp0.height
                android.util.Log.i("NyralIme", "smoothRestore remain=$remain bwH=${lp0.height} full=$bodyWrapFullH anim=${imeAnimator?.isRunning}")
                if (remain <= dp(8)) {
                    lp0.height = 0; lp0.weight = 1f
                    bodyWrap.layoutParams = lp0
                    prevCompressH = bodyWrapFullH
                    // fix(09-22): 键盘收回 bodyWrap 恢复全高后聊天区视口变高,
                    // 末条若在压缩态贴底则恢复后底部多出空白(键盘→表情切换截图空隙),
                    // 末条贴底对齐(用户翻历史时末条不可见则不动)
                    alignChatToViewport()
                    return
                }
                val startH = lp0.height
                imeAnimator?.cancel()
                imeAnimator = android.animation.ValueAnimator.ofInt(startH, bodyWrapFullH).apply {
                    duration = 60
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        val lp2 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                        val v = animatedValue as Int
                        lp2.height = v
                        bodyWrap.layoutParams = lp2
                        prevCompressH = v
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            val lp3 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                            lp3.height = 0; lp3.weight = 1f
                            bodyWrap.layoutParams = lp3
                            prevCompressH = bodyWrapFullH
                            if (imeAnimator === a) imeAnimator = null
                            // fix(09-22): 平滑恢复动画结束后视口恢复变高, 末条贴底对齐
                            alignChatToViewport()
                        }
                    })
                    start()
                }
            }
            root.setOnApplyWindowInsetsListener { v, insets ->
                val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                main.setPadding(0, sb.top, 0, sb.bottom)
                // 抽屉悬浮于 root 上, 不参与 main 的 insets 派发, 需自行避开状态栏/导航栏,
                // 否则 setDecorFitsSystemWindows(false) 下顶部标题栏被状态栏遮挡、底部被导航栏顶低
                if (::drawerPanel.isInitialized) drawerPanel.setPadding(0, sb.top, 0, sb.bottom)
                // 汉堡面板悬浮于 root 最顶层: 顶/底不设 padding, URL 底栏贴面板底边(用户要求无黑边)
                // 表情抽屉同样贴底避开导航栏(顶起式, 同键盘)
                val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                lastImeH = imeH
                if (imeH > dp(80)) imeHeightField = imeH  // 记录真实键盘高(一体式: 表情区撑高基准)
                android.util.Log.i("NyralIme", "insets imeH=$imeH shown=$imeShown open=$emojiOpen eddH=${emojiDrawer?.height} tgt=$emojiDrawerTargetH kbH=$emojiDrawerKbH w=${(bodyWrap.layoutParams as LinearLayout.LayoutParams).weight} h=${(bodyWrap.layoutParams as LinearLayout.LayoutParams).height}")
                val lp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                // 一体式互斥公式: 键盘/表情区互斥占用底部空间, 表情区高 = target - imeH;
                // 键盘弹出表情区自动收起到 0, 键盘收回表情区自动撑到 target, 输入框(大抽屉顶)零位移
                val edd = emojiDrawer
                // 动画期间互斥公式由 onProgress 逐帧驱动: 键盘弹起动画开始时 insets 先派发最终 imeH,
                // 此处若立即执行会把表情区瞬间收为 0, 而 bodyWrap 压缩被 inImeAnim 挡住 -> 输入框闪底
                // (表情→键盘切换"先掉底再被顶起" bug)。仅在无动画(瞬时弹收)时作兜底。
                if (!inImeAnim && edd != null && (emojiOpen || (edd.visibility == View.VISIBLE && edd.height > dp(1)))) {
                    val tgt = emojiDrawerTargetH
                    // fix(09-22 键盘调低): 互斥基准用实时 imeHeightField 而非 showEmojiDrawer 时的历史快照,
                    // 键盘档位调低后快照残留旧满高 -> 键盘弹满表情区收不净(整体抬起)、键盘收回又按旧高撑起不收回
                    val base = imeHeightField.coerceAtLeast(tgt)
                    if (base > 0 && tgt > 0) setEmojiDrawerHeight((base - imeH).coerceIn(0, tgt))
                }
                if (imeH > dp(80)) {
                    // 补完动画在跑(键盘收回补尾中): 尊重动画不重新压缩, 防"掉下去又上来又下去"震荡
                    if (imeAnimator?.isRunning == true) return@setOnApplyWindowInsetsListener insets
                    if (!imeShown) {
                        // bodyWrapFullH 全高基准必须用公式计算(与 insets targetH 同款),
                        // 不能用 bodyWrap.height: 抽屉切键盘时 bodyWrap 仍顶在抽屉顶位(压缩态),
                        // 记录压缩高度当全高 -> 键盘收回按错误基准恢复: 输入框先被压到接近顶部、
                        // 再停在中间高度卡顿、恢复权重才回底("收回键后跑到顶部又掉中间" bug)
                        val tbF = IntArray(2)
                        titleBar.getLocationInWindow(tbF)
                        bodyWrapFullH = (root.height - animSbBottom - (tbF[1] + titleBar.height)).coerceAtLeast(dp(60))
                        // fix8/9: 键盘弹出瞬间若列表还在滚动(惯性 SETTLING), 直接打断滚动走压缩分支(QQ 式"滚停即顶"):
                        // 悬浮分支有"输入框一帧顶到最终位与键盘动画分家 + 惯性停止后切压缩掉落重合"两段瞬态,
                        // 用户意图本就是点输入框打字, 惯性打断符合预期; 消息顶起由 onProgress 逐帧跟随, 无跳变
                        if (chatRec.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                            chatRec.stopScroll()
                        }
                        imeFloating = false   // fix8: 悬浮分支彻底禁用(硬编码), 统一走压缩分支
                        lp.weight = 0f; lp.height = bodyWrapFullH
                        bodyWrap.layoutParams = lp
                        prevCompressH = bodyWrapFullH
                    }
                    imeShown = true
                    // 键盘 insets 动画期间高度由 WindowInsetsAnimation.Callback 逐帧驱动(与键盘弹起同帧);
                    // 此处仅在无动画(键盘瞬时出现/动画结束后最终 insets 兜底)时设置最终高度, 避免提前跳变
                    if (!inImeAnim) {
                        // fix(09-22) 转头跳变根因之稳基: 键盘 insets 稳定(无动画)弹到位时记录满高,
                        // 供表情区撑高基准。onPrepare 的 animStartImeH 在"弹出动画中途被切换打断"时
                        // 是动画中途值, 不能作为满高(见 onPrepare 保护注释)
                        imeHeightField = imeH
                        val tb = IntArray(2)
                        titleBar.getLocationInWindow(tb)
                        val titleBottom = tb[1] + titleBar.height
                        // 底部占用=键盘高度(占位模型: 键盘弹出时抽屉已同步收起, 输入框只随键盘顶起)
                        val bottomOccupy = imeH
                        val targetH = ((root.height - kotlin.math.max(bottomOccupy, sb.bottom)) - titleBottom).coerceAtLeast(dp(60))
                        val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                        prevCompressH = targetH
                        if (lpp.height != targetH) {
                            lpp.height = targetH
                            bodyWrap.layoutParams = lpp
                        }
                        // 顶起: 布局稳定后贴底跟随(09-24 静态键盘贴底: 最新消息底贴输入框顶, 仅!aiBusy)
                        imeLiftToBottom()
                    }
                } else if (imeShown) {
                    // 键盘收起动画期间 insets 可能中途回调, 高度已由 WindowInsetsAnimation 逐帧恢复, 此处等动画结束后最终 insets 再收尾
                    // 注意: onApplyWindowInsets 在动画开始时即派发目标值(imeH=0), 不代表键盘已收完;
                    // 若此时提前恢复会让输入框比键盘先到位(动画慢放时明显)。恢复跟随 onProgress 逐帧, onEnd 兜底补尾。
                    if (inImeAnim) return@setOnApplyWindowInsetsListener insets
                    imeShown = false
                    imeFloatingListener?.let { chatRec.removeOnScrollListener(it) }
                    imeFloatingListener = null
                    if (imeFloating) {
                        // 收起悬浮模式: 输入区落回原位, 列表视口从未变化无需恢复
                        imeFloating = false
                        inputBar.animate().translationY(0f).setDuration(180)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                        if (attachPreviewWrap.visibility == View.VISIBLE)
                            attachPreviewWrap.animate().translationY(0f).setDuration(180)
                                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    } else {
                        // 收起同步: insets 动画期间每帧已随 imeH 递减同步恢复 bodyWrap 高度;
                        // 占位模型: 抽屉展开时 bodyWrap 恢复全高, 抽屉弹出后把输入框顶到抽屉上方
                        if (imeAnimator?.isRunning == true) {
                            // 补完动画进行中, 不动, onAnimationEnd 会恢复权重
                        } else {
                            smoothRestoreBodyWrap()
                        }
                    }
                    // 键盘已收起: 输入框光标跟随关闭
                    if (input.isFocused) input.clearFocus()
                }
                insets
            }
            // 键盘 insets 动画逐帧驱动(API30+): 与系统键盘动画精确同帧, 替代独立压缩动画(延迟)与直接跟随(跳变)
            window.decorView.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                    if ((animation.typeMask and android.view.WindowInsets.Type.ime()) == 0) return
                    inImeAnim = true
                    animSbBottom = try {
                        window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
                    } catch (e: Exception) { 0 }
                    animStartImeH = try {
                        window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                    } catch (e: Exception) { 0 }
                    android.util.Log.i("NyralIme", "onPrepare dur=${animation.durationMillis} shown=$imeShown open=$emojiOpen sb=$animSbBottom")
                    // 键盘收回开始: 记录收回前键盘高, 供表情区撑高基准(一体式: 表情区目标高=键盘高)
                    // fix(09-22) 转头跳变根因: 键盘弹出动画中途被 hideSoftInput 打断时, 系统启动的
                    // 收回动画 animStartImeH 是"动画中途值"(非满高), 若覆盖 imeHeightField 会让互斥
                    // 基准变小 -> 抽屉撑不满/输入框位移(用户现场: 键盘上半闪原位、下半闪底再上来)。
                    // 保护: 只有起点不低于已记录满高(容差 24dp)才更新, 中途值保留旧满高;
                    // 满高兜底由 onApplyWindowInsets 无动画稳定分支持续刷新
                    if (animStartImeH > dp(80) && animStartImeH + dp(24) >= imeHeightField) imeHeightField = animStartImeH
                    // 键盘开始弹出: 表情区退出展开态(互斥公式按 h>0 条件驱动收起到 0, 键盘接管底部空间)
                    // 键盘开始弹出: 表情区退出展开态。仅动画前键盘未开(animStartImeH<=80, 真弹起)时清;
                    // 动画前键盘已开(animStartImeH>80)的是收起动画, showEmojiDrawer 刚置的 emojiOpen
                    // 不能被误清, 否则直开表情不展开/聊天不顶起
                    if (!imeShown && emojiOpen && animStartImeH <= dp(80)) emojiOpen = false
                    // 键盘开始弹出(animStartImeH<=80): 无条件进入压缩初始状态。
                    // fix(09-22) 悬空根因: 转头/打断时 imeShown 可能残留 true, 旧逻辑 !imeShown 分支
                    // 不执行 -> weight 保持 1f -> onProgress 设 height 无效(weight 主导分配)
                    // -> bodyWrap 全高 + 键盘弹满 = 键盘比输入框低、输入框悬空可上下滑
                    if (animStartImeH <= dp(80)) {
                        // fix9: onPrepare 先于 onApplyWindowInsets 执行, 只改 insets 侧会被这里绕过
                        // (悬浮挂起 bh 不压缩、惯性停后输入框才被拉起又掉下 = 用户"分家"现场);
                        // 此处同样 stopScroll + 禁用悬浮, 与 insets 侧两处同步拦截
                        if (chatRec.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                            chatRec.stopScroll()
                        }
                        imeShown = true
                        // 全高基准用公式计算(同 insets 回调注释): 抽屉切键盘时 bodyWrap 仍处压缩态,
                        // bodyWrap.height 会记录到抽屉顶压缩高度 -> 键盘收回输入框先顶到顶再卡中间
                        val tbF = IntArray(2)
                        titleBar.getLocationInWindow(tbF)
                        bodyWrapFullH = (root.height - animSbBottom - (tbF[1] + titleBar.height)).coerceAtLeast(dp(60))
                        imeFloating = false
                        val lp0 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                        lp0.weight = 0f; lp0.height = bodyWrapFullH
                        bodyWrap.layoutParams = lp0
                        prevCompressH = bodyWrapFullH
                    }
                }
                override fun onProgress(insets: android.view.WindowInsets, runningAnimations: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets {
                    val imeAnim = runningAnimations.firstOrNull { (it.typeMask and android.view.WindowInsets.Type.ime()) != 0 } ?: return insets
                    // 当前帧真实 ime 高度(动画中间值), 与键盘视觉逐帧同步
                    val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    val prevImeH = lastProgressImeH  // 上一帧 ime 高度(方向判断用, 不覆盖原 last 日志语义)
                    lastProgressImeH = imeH
                    android.util.Log.i("NyralIme", "onProgress imeH=$imeH last=$lastProgressImeH shown=$imeShown")
                    // 一体式互斥公式(每帧): 表情区高 = target - imeH, 与键盘动画同帧互斥, 输入框零位移
                    val eddP = emojiDrawer
                    if (eddP != null && (emojiOpen || (eddP.visibility == View.VISIBLE && eddP.height > dp(1)))) {
                        val tgtP = emojiDrawerTargetH
                        // fix(09-22 键盘调低): 互斥基准=实时键盘满高 imeHeightField(动画开始时 onApply 已刷新为
                        // 本次真实满高), 不再用 showEmojiDrawer 历史快照 kbH: 键盘档位调低后快照残留旧满高,
                        // 键盘弹满时表情区收不净(残留=旧高-新高, 输入框被顶高)、键盘收回时又按旧高把表情区
                        // 撑起而 emojiOpen 已 false -> 表情区不收回去。中途转头保护仍由 onPrepare 容差承担
                        val baseP = imeHeightField.coerceAtLeast(tgtP)
                        if (baseP > 0 && tgtP > 0) setEmojiDrawerHeight((baseP - imeH).coerceIn(0, tgtP))
                    }
                    if (imeShown) {
                        // 压缩顶起/恢复: 高度随键盘动画进度逐帧同步, 锚定末条保留底部留白
                        val tb = IntArray(2)
                        titleBar.getLocationInWindow(tb)
                        val titleBottom = tb[1] + titleBar.height
                        // 占位式: bodyWrap 只随键盘高度压缩(输入框 dockContent 直接挂 bodyWrap 底部,
                        // 随 bodyWrap 底部贴键盘顶; 表情抽屉已随键盘动画收起让位)
                        val targetH = (bodyWrapFullH - (imeH - animSbBottom).coerceAtLeast(0)).coerceAtLeast(dp(60))
                        val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                        prevCompressH = targetH
                        if (lpp.height != targetH) {
                            lpp.height = targetH
                            bodyWrap.layoutParams = lpp
                        }
                        // 09-24 静态键盘贴底: 动画逐帧压缩视口, 每帧 post 下一帧按真实视口算 gap 贴底(仅!aiBusy)
                        imeLiftToBottom()
                    }
                    return insets
                }
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    if ((animation.typeMask and android.view.WindowInsets.Type.ime()) == 0) return
                    inImeAnim = false
                    // 反悔打断保护: 系统取消本动画并启动反向新动画(键盘收回中切回抽屉/抽屉下滑中切回键盘),
                    // 旧 onEnd 的收尾若按过时状态执行(恢复权重/收抽屉)会与反向动画打架,
                    // 表现为键盘视觉"闪下去再出来"(键盘在下半时)或"闪上去再开始"(键盘在上半时)。
                    // 收尾延迟 16ms 执行(见下), 让反向新动画 onPrepare 的 inImeAnim=true 抢先拦截。
                    // 收起动画结束: 最终 insets 回调常被动画期间挡掉(imeShown 仍 true), bodyWrap 可能停在固定高度。
                    // 补完交给最终 insets 回调(imeH=0 确认)执行 smoothRestoreBodyWrap, 不在 onEnd 立即补:
                    // 立即补会与随后到达的 insets 回调(可能带中间残值>80)竞态 -> 补下去又被压回, 来回震荡
                    // root.post 仅作兜底(最终 insets 回调被吞时)
                    // v8 曾按 lastProgressImeH<=80 判断方向, 但动画被中途打断(如点击视频弹窗抢焦点)时
                    // lastProgressImeH 停在中间值判定不恢复 -> 输入框卡在压缩中间位悬浮半空。
                    // 改为立即复查真实键盘状态: 键盘已收(insets≈0)或输入框已失焦(键盘必然在收/已收) => 强制恢复权重撑满。
                    // 立即执行(下一帧)而非延迟, 避免 bodyWrap 在中间高度停 300ms 造成"两段式"掉底观感。
                    // 反悔打断收尾延迟: post 下一帧可能仍抢在反向新动画 onPrepare 之前执行(旧收尾误伤),
                    // 延迟 16ms 让 onPrepare 先置 inImeAnim=true, 上方 !inImeAnim 检查即可拦截过时收尾。
                    root.postDelayed({
                        android.util.Log.i("NyralIme", "onEnd.post imeShown=$imeShown inAnim=$inImeAnim open=$emojiOpen focused=${input.isFocused} bwH=${(bodyWrap.layoutParams as LinearLayout.LayoutParams).height}")
                        // fix(09-22) 转头竞态: 键盘动画强打断已由 requestIme(insetsController) 保证反向
                        // 动画立即 onPrepare, 此处仅需 inImeAnim 拦截过时收尾(闪底/闪没再被拉回)
                        if (!imeShown || inImeAnim) return@postDelayed
                        val curImeH = try {
                            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                        } catch (e: Exception) { 0 }
                        // 用动画方向判据提前恢复: 动画从高位开始且末帧已到低位 => 收回, 不等最终回调
                        val closing = lastProgressImeH <= dp(240) && animStartImeH > dp(240)
                        // fix(09-22) 悬空误收尾: 键盘弹满时若输入框恰好失焦(收尾 clearFocus / 系统抢焦),
                        // 旧 !focused 分支会把 bodyWrap 恢复全高 -> 键盘比输入框低。失焦分支也要求
                        // 键盘确实收到底(curImeH 低位)才允许恢复全高
                        if (curImeH <= dp(80) || (!input.isFocused && curImeH <= dp(240)) || (closing && curImeH <= dp(240))) {
                            // 反悔打断残留: 抽屉半开(高度未到展开位)且键盘最终未重弹时收完, 防"反悔后键盘没弹回来+抽屉挂半空"卡死
                            if (isEmojiDrawerHalfOpen()) {
                                finishEmojiDrawerDrop()
                            }
                            if (imeFloating) {
                                imeFloating = false
                                inputBar.translationY = 0f
                                if (attachPreviewWrap.visibility == View.VISIBLE) attachPreviewWrap.translationY = 0f
                            }
                            // 平滑补完剩余距离(内部自判 weight), 消除中间高度跳变
                            smoothRestoreBodyWrap()
                            imeShown = false
                            if (input.isFocused) input.clearFocus()
                        }
                    }, 16L)
                }
            })
        }
        // 键盘弹起时点击输入区以外收起键盘: 在 root.dispatchTouchEvent 实现(见 root 定义处), 无其它点击监听
    }
    private fun finishCreate() {
        TypewriterCenter.initFromSystem(this)   // 气泡打字机慢放倍数跟随系统动画缩放(09-24)
        if (SlowBall.isEnabled(this)) SlowBall.show(this)   // 重启后恢复悬浮慢放球(09-24)
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendWelcomeIntro()
        // 恢复最近一次会话，避免杀后台后聊天记录与列表丢失
        val recent = db.listSessions(1, ModeConfig.modeValue())
        val canOpen = recent.isNotEmpty() && db.loadSessionMessages(recent[0].id).isNotEmpty()
        Log.d("SCROLLDBG", "finishCreate recent=" + recent.size + " canOpen=" + canOpen + " attached=" + chatRec.isAttachedToWindow + " h=" + chatRec.height + " itemCount=" + chatAdapter.itemCount)
        if (canOpen) {
            openSession(recent[0].id)
        }
        // 开发者调试服务: 默认关闭; 设置开启且为 debug 构建时在 onCreate 末尾拉起
        DebugServer.init(this)
        // 保活: 前台服务防止退后台后进程被冻结, DebugServer accept 线程停摆
        DebugService.start(this)
        // 工作目录 MediaStore 索引自愈: adb push 等非 MediaStore 落盘的文件默认不在索引,
        // 启动时后台触发系统媒体扫描, 让 workdir_list/read 立即可见(修复 site_auth 读取失效)
        Thread { WorkDir.rescan(this) }.start()
    }

    override fun onResume() {
        super.onResume()
        // 首启权限引导: 从特殊权限设置页返回后推进到下一项
        if (firstRunGuideState in 2..4) {
            firstRunGuideState = 0
            window.decorView.post { startNextSpecialPermission() }
        }
        // 从设置/模型配置"保存并应用"返回: 图标按钮无需刷新文字, 仅重刷背景
        applyChatBackground()
        // 从模型配置页返回: 手动模型能力勾选可能变化, 重刷语音切换按钮显隐
        refreshVoiceButton()
        // 从设置页-外观修改标题后返回, 刷新主页标题与侧栏标题
        if (::mainTitleText.isInitialized) mainTitleText.text = TitleConfig.mainTitle()
        if (::drawerTitleText.isInitialized) drawerTitleText.text = TitleConfig.drawerTitle()
        if (::drawerNoteText.isInitialized) drawerNoteText.text = TitleConfig.drawerNote()
        // 聊天/Agent 模式切换: 重新加载当前模式最近会话
        if (lastModeValue != ModeConfig.modeValue()) {
            val prevMode = lastModeValue
            lastModeValue = ModeConfig.modeValue()
            maybeSaveCurrent(prevMode)
            aiStage = 0
            contentFollow = false
            updateJumpFab()
            messages.clear()
            chatRows.clear(); chatAdapter.notifyDataSetChanged()
            currentSaved = true
            currentSessionId = null
            currentSessionTitle = null
            // 模式内续接: 切模式后按当前模式取最近会话续接, 不跨模式串
            // (两种模式会话互不干扰, 仅长期记忆相通; 历史会话从抽屉按模式隔离列表进入)
            appendWelcomeIntro()
            val recent = db.listSessions(1, ModeConfig.modeValue())
            if (recent.isNotEmpty() && db.loadSessionMessages(recent[0].id).isNotEmpty()) {
                openSession(recent[0].id)
            }
            refreshSessionList()
        }
        // 从设置页更换头像返回: 头像文件版本变化时重建当前会话气泡, 旧气泡无需重启即刷新
        refreshChatAvatars()
    }

    /** 应用聊天背景: 预设渐变 / 自定义模糊图 / 默认纯色 */
    private fun applyChatBackground() {
        val type = ChatBackgroundActivity.loadType(this)
        val presets = arrayOf(
            intArrayOf(0xFFF5F7FA.toInt(), 0xFFE4E9F2.toInt()),
            intArrayOf(0xFFFDF6EC.toInt(), 0xFFF5E6D3.toInt()),
            intArrayOf(0xFFF0F7F0.toInt(), 0xFFDCEBDC.toInt()),
            intArrayOf(0xFFF0F4FB.toInt(), 0xFFDCE6F5.toInt()),
            intArrayOf(0xFFFBF0F6.toInt(), 0xFFF0DCE8.toInt()),
            intArrayOf(0xFFF4F0FA.toInt(), 0xFFE4DCF0.toInt())
        )
        // 挂到独立 bgLayer 上: 背景固定, 消息在背景上滚动(类微信)
        bgLayer.background = when (type) {
            "preset" -> {
                val idx = ChatBackgroundActivity.loadPreset(this).coerceIn(0, presets.size - 1)
                GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(presets[idx][0], presets[idx][1]))
            }
            "custom" -> {
                val f = java.io.File(filesDir, ChatBackgroundActivity.CUSTOM_FILE)
                if (f.exists()) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                        if (bmp != null) FixedBgDrawable(bmp) else null
                    } catch (e: Exception) { null }
                } else null
            }
            else -> android.graphics.drawable.ColorDrawable(Ui.BG)
        }
    }

    override fun onStop() {
        super.onStop()
        // 划掉后台/杀进程前兜底保存当前未落库的对话
        maybeSaveCurrent()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 系统内存吃紧: 视频并发配额只降不升, 不杀在播, 新创建按更严名额执行(防 OOM)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) ExoGate.downgrade(level)
    }

    // ===================== 左侧抽屉 =====================


    // ===================== 会话全文搜索 =====================


    // ===================== 多会话 =====================

    /** 胶囊交互卡: 重试动作(通知 Action 携带 ACTION_RETRY 拉起本页) */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == TaskService.ACTION_RETRY) {
            // 重试语义: 先取消当前请求(若在跑), 再重发最后一条用户消息
            cancelActiveRequest()
            val lastUser = messages.lastOrNull { it.role == "user" && it.content.isNotBlank() } ?: return
            input.setText(lastUser.content)
            pendingAttachments.clear()
            onSend()
        }
    }

    /** 取消当前 AI 请求(切会话/新会话调用): 代际自增使迟到回调全部失效, 立即恢复输入态,
     *  不依赖迟到 onDone/onError 清理状态(阶段2 流式竞态治理) */
    private fun cancelActiveRequest() {
        // 丢弃流式行引用(无论 AI 是否还在输出): 防止全量重建(切模式/开会话/新会话)时
        // buildRowsFromMessages 兜底把已收尾的 Streaming 行再次塞回 → 跨模式串写/AI回复重复
        streamingRow = null
        aiStage = 0
        contentFollow = false
        updateJumpFab()
        if (!aiBusy) return
        LocalEngine.requestCancel()
        requestEpoch++
        aiBusy = false
        TaskService.stop(this@MainActivity)
        updateInputMode()
        stopBtn.visibility = View.GONE
        LogStore.i(LogStore.MAIN, "切会话取消进行中请求, 代际=${requestEpoch}")
    }

    internal fun startNewSession() {
        // AI 正在输出时切会话: 取消引擎 + 失效代际, 防止其把未完成的回复写进新会话历史
        cancelActiveRequest()
        maybeSaveCurrent()
        messages.clear()
        sessionBaseSeq = 0
        chatRows.clear(); chatAdapter.notifyDataSetChanged()
        currentSaved = true
        currentSessionId = null
        currentSessionTitle = null
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendWelcomeIntro()
        refreshSessionList()
        closeDrawer()
    }

    internal fun openSession(id: Long, locateSeq: Int? = null) {
        // AI 正在输出时切会话: 取消引擎 + 失效代际, 防止其把未完成的回复写进新会话历史
        cancelActiveRequest()
        maybeSaveCurrent()
        // 超长会话内存瘦身: 消息数 > MEM_WINDOW 时仅载入最近窗口(更早消息保留 DB 供搜索/回溯, 上下文由 summary 承担)
        val total = db.countSessionMessages(id)
        val msgs = if (total > MEM_WINDOW) {
            sessionBaseSeq = total - MEM_WINDOW
            db.loadSessionMessagesTail(id, MEM_WINDOW)
        } else {
            sessionBaseSeq = 0
            db.loadSessionMessages(id)
        }
        if (msgs.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_messages, Toast.LENGTH_SHORT).show()
            return
        }
        // 切会话: 旧会话表情帧动画实例立即统一终结(不等 detach 看门狗 10s), 名额立即归还,
        // 否则切回会话 10s 内新表情 attach 被旧实例占满 MAX_ACTIVE -> 全部降级缩略图不动(09-19 反馈)
        EmojiFrameAnimator.sActive.toList().forEach { c -> try { c.killSelf() } catch (_: Throwable) {} }
        messages.clear()
        // 阶段5 池化清理: 切会话即清形态 View 池, 旧会话 View 树(含文本/rendered Spanned)不滞留复用,
        // 避免串会话内容残留与内存驻留; 会话内全量重建(头像刷新/窗口外回退)不清池, 保留复用收益
        chatAdapter.clearPool()
        // 09-28 第五波: AiRich 分片/头像/wrap 三池一并清空——切会话后池中旧会话 View 树若被新会话复用,
        // 残留 KEY_RENDER_MD tag 会命中幂等跳过渲染(内容相同时)或滞留旧会话 Spanned 引用
        aiRichSegPool.clear()
        aiAvatarPool.clear()
        aiRichWrapPool.clear()
        messages.addAll(msgs)
        currentSaved = true
        currentSessionId = id
        currentSessionTitle = db.sessionTitleOf(id)
        // 吸底修复: 切会话重置用户滚动标记(旧会话的"正在阅读"不应带入新会话), 新会话默认追底
        scrollUserScrolled = false
        activeAiHolder?.resumeTypewriter()   // 切会话: 恢复慢打(09-25)
        aiStage = 0
        contentFollow = false
        updateJumpFab()
        // 滚动时机修复: ListAdapter.submitList 为异步 diff, 滚动必须等 diff 提交后执行,
        // 否则 itemCount 仍是旧会话值→滚到错误位置/直接不滚(表现为"切会话后不在最新, 像自己滚动")
        var scrolled = false
        val scrollAfterCommit = scrollAfterCommit@{
            if (scrolled) return@scrollAfterCommit
            scrolled = true
            if (locateSeq != null) {
                // 定位到命中消息(搜索/跳转): RecyclerView 直接滚到该行 + 短暂高亮
                chatRec.post {
                    if (locateSeq < sessionBaseSeq) {
                        Toast.makeText(this, R.string.toast_loaded_far_history, Toast.LENGTH_LONG).show()
                        // 窗口外命中: 临时全量加载该会话(仅本次, 定位后恢复窗口)
                        sessionBaseSeq = 0
                        val full = db.loadSessionMessages(id)
                        messages.clear(); messages.addAll(full)
                        buildRowsFromMessages()
                        val idx = locateSeq.coerceIn(0, chatRows.lastIndex)
                        chatRec.scrollToPosition(idx)
                        chatRec.post { chatAdapter.highlightRow(chatRows.getOrNull(idx)) }
                    } else {
                        val idx = (locateSeq - sessionBaseSeq).coerceIn(0, chatRows.lastIndex)
                        chatRec.scrollToPosition(idx)
                        chatRec.post { chatAdapter.highlightRow(chatRows.getOrNull(idx)) }
                    }
                }
            } else {
                // 吸底修复: AsyncListDiffer 的 onCommitted 可能迟到(用户切会话后已开始上翻),
                // 用户已触摸列表就让位, 不再拉底打断阅读(不触摸则正常定位底部)
                Log.d("SCROLLDBG", "openSession commit id=" + id + " scrolled=" + scrolled + " uScroll=" + scrollUserScrolled + " itemCount=" + chatAdapter.itemCount + " attached=" + chatRec.isAttachedToWindow + " h=" + chatRec.height)
                if (!scrollUserScrolled) scrollToBottom()
            }
        }
        buildRowsFromMessages {
            if (sessionBaseSeq > 0) {
                // 窗口化提示行插入到历史消息头部(与旧 ScrollView 行为一致: 提示在顶部), 其 diff 提交后再滚动
                chatAdapter.insert(0, ChatRow.Sys(nextTempRowId(), getString(R.string.ma_sys_window_hint, MEM_WINDOW))) { scrollAfterCommit() }
            } else {
                scrollAfterCommit()
            }
        }
        // 长气泡吸底跳跃修复: 后台预编译本会话历史 AI 消息的 markdown, 上翻浏览时 bind 直接命中缓存零解析
        prewarmMdCache()
        refreshSessionList()
        closeDrawer()
    }

    /** 换头像后刷新当前会话旧气泡: 头像版本戳变化时按 messages 重建 chatRows(RecyclerView), 保留滚动位置 */
    private fun refreshChatAvatars() {
        val stamp = AvatarConfig.avatarStamp()
        if (stamp == lastAvatarStamp) return
        lastAvatarStamp = stamp
        // AI 正在输出时跳过, 避免打断流式渲染(其后的新气泡自然使用新头像)
        if (aiBusy || messages.isEmpty() || !::chatAdapter.isInitialized) return
        val lm = chatRec.layoutManager as? LinearLayoutManager
        val pos = lm?.findFirstVisibleItemPosition() ?: 0
        buildRowsFromMessages()
        chatRec.post { lm?.scrollToPosition(pos) }
    }

    private fun maybeSaveCurrent(mode: Int = ModeConfig.modeValue()) {
        if (!currentSaved && messages.isNotEmpty()) {
            val title = messages.firstOrNull { it.role == "user" }?.content
                ?.replace("\n", " ")?.take(20) ?: getString(R.string.ma_unnamed_session)
            val sid = currentSessionId
            if (sid != null) {
                db.updateSession(sid, title, messages, mode, sessionBaseSeq)
            } else {
                currentSessionId = db.saveSession(title, messages, mode)
            }
            currentSessionTitle = title
            currentSaved = true
        }
    }

    internal fun refreshSessionList() {
        sessionList.removeAllViews()
        val list = db.listSessions(20, ModeConfig.modeValue())
        if (list.isEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = getString(R.string.ma_no_sessions)
                textSize = 12f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        list.forEach { s ->
            sessionList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isClickable = true
                setOnClickListener { openSession(s.id) }
                isLongClickable = true
                setOnLongClickListener {
                    showSessionMenu(s)
                    true
                }
                addView(TextView(this@MainActivity).apply {
                    text = (if (s.pinned) "📌 " else "") + s.title
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 14f
                    setTextColor(Ui.TEXT)
                })
                addView(TextView(this@MainActivity).apply {
                    text = (if (s.pinned) getString(R.string.ma_pinned_prefix) else "") + fmtTime(s.updatedAt)
                    textSize = 11f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            sessionList.addView(View(this).apply {
                setBackgroundColor(Ui.DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }


    override fun onBackPressed() {
        if (browserPage.hamburgerOpen) {
            browserPage.collapseHamburger()
            return
        }
        if (browserPage.open) {
            if (!browserPage.goBack()) browserPage.close()
            return
        }
        if (drawerOpen) {
            closeDrawer()
            return
        }
        if (tokenMask.visibility == View.VISIBLE) {
            hideTokenPanel()
            return
        }
        // 二次确认退出: 第一次返回弹提示, 2秒内再返回才真正退到桌面(防误触)
        val now = System.currentTimeMillis()
        if (now - lastExitPressTime > 2000) {
            lastExitPressTime = now
            android.widget.Toast.makeText(this, "再滑动一次退出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lastExitPressTime = 0
        super.onBackPressed()
    }

    // ===================== 发送与引擎 =====================

    private fun onSend() = doSend(pendingAttachments.toList())

    /** 当前模型是否支持语音输入: 预设=内置能力表, 手动=用户配置勾选(与附件弹窗能力判断同一来源) */
    internal fun currentModelSupportsVoice(): Boolean =
        ApiConfig.modelHasCap(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.CAP_AUDIO)

    /** 统一刷新底栏三形态布局(槽位固定/输入框左右宽距恒不动):
     * 支持语音模型: 无字→槽A=语音 槽B=附件(+); 有字→槽A=附件(+) 槽B=发送
     * 不支持语音模型: 无论有无文字→槽A=附件(+) 槽B=发送 (语音槽由附件接管, 不留空白)
     * AI输出/语音模式期间不切换 */
    private fun applyInputMode() {
        if (aiBusy || voiceMode) return
        val hasText = input.text.isNotBlank()
        if (!currentModelSupportsVoice()) {
            // 不支持语音: 恒为 [附件(槽A)][发送(槽B)]
            micBtn.visibility = View.GONE
            attachBtn2.visibility = View.VISIBLE
            attachBtn.visibility = View.GONE
            sendBtn.visibility = View.VISIBLE
            return
        }
        // 槽A(micWrap): 有字→附件按钮; 无字→让语音按钮显示
        attachBtn2.visibility = if (hasText) View.VISIBLE else View.GONE
        // 槽B(attachWrap): 有字→发送; 无字→附件按钮
        attachBtn.visibility = if (hasText) View.GONE else View.VISIBLE
        sendBtn.visibility = if (hasText) View.VISIBLE else View.GONE
        // 有字时语音按钮 INVISIBLE 占位防槽塌陷(槽A由附件按钮接管), 无字显示
        micBtn.visibility = if (hasText) View.INVISIBLE else View.VISIBLE
    }

    /** 刷新语音切换按钮及底栏: 仅当当前模型支持语音时展示语音; 不支持时隐藏并强制退回文字输入 */
    internal fun refreshVoiceButton() {
        if (!currentModelSupportsVoice()) {
            if (voiceMode) {
                voiceMode = false
                input.visibility = View.VISIBLE
                speakBar.visibility = View.GONE
                micBtn.background = micIconBg(false, density = resources.displayMetrics.density)
            }
        }
        applyInputMode()
    }

    /** 底栏交互入口: 输入框文字变化时刷新三形态布局 */
    internal fun updateInputMode() {
        applyInputMode()
    }

    /** 面板入口按钮: 展开右侧汉堡面板(引擎管理/登录数据/URL); 浏览器未开先开再展开, 已开直接展开, 已展开则收起 */
    internal fun toggleBrowserPanel() {
        if (browserPage.hamburgerOpen) {
            browserPage.collapseHamburger()
            return
        }
        // 控制条展开时先收起, 避免与汉堡面板/遮罩叠加
        if (browserBarExpanded) toggleBrowserBar()
        if (!browserPage.open) {
            browserPage.open()
            browserBar.postDelayed({ browserPage.expandHamburger() }, 320L)
        } else {
            browserPage.expandHamburger()
        }
    }

    /** 折叠手柄点按: 展开/收起控制条(接管按钮+状态), 手柄 ⌃→⌄ 旋转 */
    internal fun toggleBrowserBar() {
        browserBarExpanded = !browserBarExpanded
        browserBar.animate().cancel()
        browserBarToggle.animate().cancel()
        if (browserBarExpanded) {
            browserBar.alpha = 0f
            browserBar.translationY = dp(6).toFloat()
            browserBar.visibility = View.VISIBLE
            browserBar.animate().alpha(1f).translationY(0f).setDuration(160).start()
            addCollapseMasks()
        } else {
            browserBar.animate().alpha(0f).translationY(dp(6).toFloat()).setDuration(140).withEndAction {
                browserBar.visibility = View.GONE
            }.start()
            removeCollapseMasks()
        }
        browserBarToggle.animate().rotation(if (browserBarExpanded) 180f else 0f).setDuration(160).start()
    }

    /** 展开控制条时盖住其它区域: 点击浏览器/聊天区自动收回; 控制条与手柄在遮罩之上不受影响 */
    private fun addCollapseMasks() {
        if (!::collapseMaskWeb.isInitialized || !::collapseMaskChat.isInitialized) return
        if (browserPage.root.isAttachedToWindow && collapseMaskWeb.parent == null) {
            browserPage.root.addView(collapseMaskWeb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (collapseMaskChat.parent == null) {
            chatArea.addView(collapseMaskChat,
                chatArea.indexOfChild(browserBar).coerceAtLeast(0),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun removeCollapseMasks() {
        if (::collapseMaskWeb.isInitialized) (collapseMaskWeb.parent as? ViewGroup)?.removeView(collapseMaskWeb)
        if (::collapseMaskChat.isInitialized) (collapseMaskChat.parent as? ViewGroup)?.removeView(collapseMaskChat)
    }

    /** 浏览器窗口固定为中间区域(标题栏下 ~ 输入框顶); 手柄/控制条悬浮覆盖其上, 展开收起不顶起浏览器 */
    internal fun adjustBrowserWindow() {
        if (!browserPage.root.isAttachedToWindow) return
        val tLoc = IntArray(2)
        titleBar.getLocationInWindow(tLoc)
        val gap = dp(10)
        // 上下各留 gap 间距: 顶部下移 gap, 高度再扣 gap, 使窗口离标题栏/输入栏都远一点(底部同步上移)
        val top = tLoc[1] + titleBar.height + gap
        // 固定按"键盘未弹出"的完整高度计算: 键盘/输入框/抽屉浮在浏览器之上自然盖住窗口底部,
        // 不跟随 inputBar 当前位置, 否则键盘弹起瞬间窗口被顶起变矮、收回时又残留半屏
        val sb = try {
            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
                ?: android.graphics.Insets.NONE
        } catch (e: Exception) { android.graphics.Insets.NONE }
        val fullH = (root.height - top - sb.bottom).coerceAtLeast(dp(120))
        val h = (fullH - inputBar.height - gap).coerceAtLeast(dp(120))
        browserPage.setWindowRect(top, h)
    }

    internal fun setBrowserBarVisible(v: Boolean) {
        if (!v) {
            browserBar.visibility = View.GONE
            browserBarToggle.visibility = View.GONE
            browserBarExpanded = false
            browserBarToggle.rotation = 0f
            removeCollapseMasks()
        } else {
            browserBarToggle.visibility = View.VISIBLE
            if (browserBarExpanded) browserBar.visibility = View.VISIBLE
        }
    }

    internal fun doSend(attachments: List<LocalEngine.Attachment>) {
        val text = input.text.toString().trim()
        android.util.Log.i("Nyral", "onSend text=[$text] aiBusy=$aiBusy attachments=${attachments.size}")
        if (text.isEmpty() && attachments.isEmpty()) return
        if (aiBusy) {
            Toast.makeText(this, R.string.toast_ai_typing, Toast.LENGTH_SHORT).show()
            return
        }
        // 会话维度 Token 统计: 引擎 record 时读取
        TokenStore.currentSessionId = currentSessionId
        // 发起新请求前清掉可能残留的取消标记(如切会话时 requestCancel 但引擎未在跑)
        LocalEngine.cancelRequested = false
        // 立即占住 AI 忙碌态: 附件路径走后台异步, 若不提前置位, 用户快速连发时第二个请求会穿透检查
        aiBusy = true
        LogStore.i(LogStore.MAIN, "发送消息 len=${text.length} 附件=${attachments.size} 会话=$currentSessionId")
        // 前置轻量 UI 清理: 清空输入与附件预览(与耗时逻辑无关, 先做保证手感)
        input.setText("")
        pendingAttachments.clear()
        attachPreviewRow.removeAllViews()
        attachPreviewWrap.visibility = View.GONE
        // fix6: 发送后自动收起键盘(用户提议): 流式追底期间键盘高度全程稳定, 消除收回竞态
        // fix7: 原这里有"发送后主动弹回键盘"逻辑, 与收键盘意图相反造成"缩一下又弹出像重启", 已删
        // 收起时必须 clearFocus, 否则焦点残留时 IME 会在 layout 变化后自动弹回
        if (!voiceMode) {
            input.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(input.windowToken, 0)
                if (input.isFocused) input.clearFocus()
            }
        }
        // 前台服务+通知保活: 尽早启动(主线程此刻最空闲), 避免后续附件落盘/历史构建等重活
        // 阻塞主线程导致 startForegroundService 后约5s内未 startForeground, 触发
        // ForegroundServiceDidNotStartInTimeException 闪退; Android 13+ 先请求通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        try {
            TaskService.start(this)
        } catch (e: Exception) {
            LogStore.e(LogStore.MAIN, "前台服务启动失败: ${e.message}")
        }
        // 附件落盘持久化(私有目录)移入后台线程: Base64 解码+写盘在大文件下很耗时,
        // 若留在主线程会阻塞 Service 的 startForeground 处理, 是闪退主因之一
        val doSave = {
            // 显示文本: 附件落盘后转可点击占位标记, 无文本时仅显示附件标记
            // 显示气泡: 附件与文字拆成两条独立气泡(图片/附件一条, 说明文字一条), 无文字时仅一条附件气泡
            val dispList = if (attachments.isEmpty()) listOf(text) else {
                // PDF 扫描件页图(pdfSourceName 非空)仅作发送附件, 不参与气泡显示, 避免多图网格"一通到底"
                val showAtts = attachments.filter { it.pdfSourceName == null }
                val marks = showAtts.map { a ->
                    val fileName = try {
                        // 已落库附件(stored): base64 为空, 直接以落库引用 key 做 att:// 链接, 不重复落盘
                        if (a.stored) a.name
                        else AttachmentStore.save(this@MainActivity, a.name, a.mime, Base64.decode(a.base64, Base64.NO_WRAP))
                    } catch (e: Exception) {
                        null
                    }
                    val link = if (fileName != null) "(att://$fileName)" else ""
                    // 落库附件取 meta 原名(文件名含时间戳/UUID 前缀), 非落库直接用原名
                    val dispName = if (a.stored) AttachmentStore.displayName(this@MainActivity, a.name) else a.name
                    when {
                        a.isEmoji -> "[表情:$dispName]$link"             // 表情库项: 独立表情气泡(96dp小图, 动图循环)
                        a.mime.startsWith("image/") -> "[图片]$link"
                        a.isVoice -> "[音频]$link"                       // 本地录音: 保留语音气泡形态
                        a.mime.startsWith("audio/") -> "[文件:$dispName]$link"  // 上传音频文件: 按文件卡片展示
                        a.mime.startsWith("video/") -> "[视频:$dispName]$link"
                        else -> "[文件:$dispName]$link"
                    }
                }
                // 每个附件独立成一张卡片气泡(含纯图片多图: 已废弃 3 列网格合并)
                val bubbles = marks
                if (text.isEmpty()) bubbles else bubbles + listOf(text)
            }
            dispList
        }
        // 无附件: 直接主线程走完; 有附件: 后台线程落盘后回主线程继续, 避免大附件阻塞主线程
        if (attachments.isEmpty()) {
            continueSend(text, listOf(text), attachments)
        } else {
            executor.execute {
                val dispList = doSave()
                uiScope.launch { continueSend(text, dispList, attachments) }
            }
        }
    }

    /**
     * doSend 的后半段: 渲染气泡 + 落库 + 构建历史 + 发起 AI 请求。
     * 拆出来以支持"附件后台落盘后回主线程继续"这一流程。
     */
    private fun continueSend(text: String, dispList: List<String>, attachments: List<LocalEngine.Attachment>) {
        // 09-24 追底回填: 发送即重置用户接管——上翻阅读旧消息的状态不带入新回复, 本轮流式恢复追底;
        // 发送后用户再上滑, RV onTouch(ACTION_DOWN)重新置位, 守卫即恢复生效
        scrollUserScrolled = false
        activeAiHolder?.resumeTypewriter()   // 发送: 恢复慢打(09-25)
        for (d in dispList) {
            appendUser(d)
            messages.add(MemoryDb.SessionMsg("user", d, "", "", "", System.currentTimeMillis()))
            MemoryKeeper.push("user", d)
        }
        currentSaved = false
        // 即时落库: 不依赖 onStop 兜底, 防止发送后进程被杀(force-stop/划掉后台)导致最后一条消息丢失
        maybeSaveCurrent()

        // 文档类附件本地解析文本: 预算闸门(定稿六.1): 估算 token = 字符数/3, 单轮 ≤2000 直进, 超限走索引卡
        // 直进路径保留原字符级兜底(MAX_ATTACH_TEXT / MAX_DOC_TOTAL); 超预算路径全文落盘, history 只放索引卡
        val ATT_BUDGET = 2000
        val docParts = ArrayList<String>()
        var docTotal = 0
        val docAtts = attachments.filter { !(it.text ?: "").isBlank() }
        val estTokens = docAtts.sumOf { (it.text?.length ?: 0) / 3 }
        if (estTokens <= ATT_BUDGET) {
            for (a in docAtts) {
                val t = a.text ?: ""
                val part = if (t.length > MAX_ATTACH_TEXT) {
                    "[附件 ${a.name} 本地解析文本(已截断, 原件${t.length}字符)]\n${t.take(MAX_ATTACH_TEXT)}"
                } else {
                    "[附件 ${a.name} 本地解析文本]\n$t"
                }
                if (docTotal + part.length > MAX_DOC_TOTAL) {
                    docParts.add("[附件及其他] (已超总量上限, 其余内容跳过)")
                    break
                }
                docTotal += part.length
                docParts.add(part)
            }
        } else {
            // 超预算: 全文落盘 attachments/*.txt, history 只放索引卡(约300字预览), 需细节时调 attach_read 分块读取
            for (a in docAtts) {
                val t = a.text ?: ""
                val txtFile = try {
                    AttachmentStore.save(this, a.name + ".txt", "text/plain", t.toByteArray(Charsets.UTF_8))
                } catch (e: Exception) { null }
                val loc = if (txtFile != null) "att://$txtFile" else "落盘失败"
                docParts.add("[附件 ${a.name} | ${a.mime} | 解析文本${t.length}字符 | 全文已落盘, 需要细节时调 attach_read 按 offset/limit 分块读取 | 存储 $loc]\n[摘要预览]\n${t.take(300)}")
            }
        }
        val docTexts = docParts.joinToString("\n")
        val history = if (docTexts.isBlank()) buildHistory() else buildHistory() + "\n$docTexts\n"
        aiBusy = true
        val epoch = ++requestEpoch   // 新请求代际: 上一轮迟到回调(若存在)全部失效
        replySessionId = currentSessionId  // 快照: 回调回来时若已切会话, 拒绝写入
        attachBtn2.visibility = View.GONE
        attachBtn.visibility = View.GONE
        sendBtn.visibility = View.GONE
        stopBtn.visibility = View.VISIBLE
        stopBtn.isEnabled = true
        executor.execute {
            val holder = AiBubbleHolder(this@MainActivity)
            activeAiHolder = holder
            uiScope.launch {
                // 流式行: 新增 Streaming 占位行(回收传送带末位), AiBubbleHolder 气泡盒挂到该行 item 容器
                val row = ChatRow.Streaming(nextTempRowId(), holder)
                streamingRow = row
                chatAdapter.add(row) { scrollToBottom(true) }
                val box = holder.createStreamingBox()
                row.bubbleBox = box
                chatAdapter.attachStreaming(chatRows.size - 1)
                holder.showLoading()
            }
            LocalEngine.chat(this@MainActivity, history, object : LocalEngine.Callback {
                override fun onThinkingStart() {
                    LogStore.i(LogStore.MAIN, "开始思考")
                    debugSseSink?.invoke("thinking_start", "")
                    uiScope.launch {
                        if (epoch != requestEpoch) return@launch
                        aiStage = 1
                        updateJumpFab()
                        TaskService.updateStage(this@MainActivity, getString(R.string.ts_stage_thinking))
                        AITerminal.push("thinking", "开始思考…")
                        holder.showThinking()
                    }
                }
                override fun onThinkingDelta(text: String) {
                    debugSseSink?.invoke("thinking", text)
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.appendThinking(text) }
                }
                override fun onThinkingEnd() {
                    AITerminal.push("thinking", "思考结束，进入作答")
                    debugSseSink?.invoke("thinking_end", "")
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.collapseThinking() }
                }
                override fun onTool(name: String, arg: String) {
                    LogStore.i(LogStore.MAIN, "调用工具: $name")
                    debugSseSink?.invoke("tool", "$name|$arg")
                    uiScope.launch {
                        if (epoch != requestEpoch) return@launch
                        aiStage = 2
                        updateJumpFab()
                        TaskService.updateStage(this@MainActivity, getString(R.string.ts_stage_tool))
                        AITerminal.push("tool", "$name $arg")
                        holder.showTool(name, arg)
                        // 进入工具调用即表示本段思考已结束: 折叠思考区, 避免一直停在"思考中"
                        holder.collapseThinking()
                    }
                }
                override fun onToolResult(name: String, result: String) {
                    LogStore.i(LogStore.MAIN, "工具结果: $name")
                    AITerminal.push("tool_result", "$name → ${result.trim()}")
                    debugSseSink?.invoke("tool_result", "$name|$result")
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.setToolResult(name, result) }
                }
                override fun onDelta(text: String) {
                    android.util.Log.i("Nyral", "onDelta=[$text]")
                    AITerminal.push("delta", text)
                    debugSseSink?.invoke("delta", text)
                    // AI 表情标记流式掩码: 完整/半截 [表情:名] 均不直接暴露(显示〔表情〕占位),
                    // onDone 收尾拆分落库重建为独立表情气泡
                    val masked = maskAiEmojiMarks(text)
                    uiScope.launch {
                        if (epoch != requestEpoch) return@launch
                        // 正文块开始(首个 token): 阶段推进, 正文默认停滚(视口停留, 一键到底按钮接管)
                        if (aiStage != 3) {
                            aiStage = 3
                            contentFollow = false
                            updateJumpFab()
                        }
                        holder.appendContent(masked)
                        // 正文阶段停滚: 视口停留不跟随, 新内容在屏外增长;
                        // FAB 显隐随内容增长刷新(无滚动帧, onScrolled 兜底覆盖不到)
                        if (aiStage == 3 && !contentFollow) {
                            updateJumpFab()
                        }
                    }
                }
                override fun onDone(reply: String) {
                    android.util.Log.i("Nyral", "onDone len=${reply.length}")
                    if (LocalEngine.cancelRequested) {
                        LogStore.w(LogStore.MAIN, "用户停止输出")
                        AITerminal.push("stop", "已停止")
                    } else {
                        LogStore.i(LogStore.MAIN, "回复完成 len=${reply.length} 会话=$replySessionId")
                        AITerminal.push("done", "回复完成 len=${reply.length}")
                    }
                    uiScope.launch {
                        // 代际校验(阶段2): 切会话/新请求已接管, 迟到回调直接丢弃, 不碰 holder/不写库/不动状态
                        if (epoch != requestEpoch) return@launch
                        aiStage = 0
                        contentFollow = false
                        updateJumpFab()
                        // 阶段4 增量落库索引: 正文首条在 messages 中的位置(写回 rendered 用);
                        // 声明在最外层供 finishContent 后写回使用; 取消/无正文保持 -1 不写
                        var contentIdx = -1
                        if (LocalEngine.cancelRequested) {
                            // 用户主动停止: 不写入对话/记忆
                            holder.appendContent("\n(已停止)")
                            LocalEngine.cancelRequested = false
                        } else {
                            // 思考内容随回复一起持久化, 切回会话可恢复思考区
                            // 竞态防护: 回调排队期间用户可能已切会话, 不能把回复写进新会话历史
                            if (replySessionId == currentSessionId) {
                                // AI 表情气泡(2026-09-20): 按 [表情:名] 白名单标记把回复拆为 正文+独立表情气泡 多条消息;
                                // 第一条(通常正文)挂 thinking/tools 快照, 表情行独立无快照
                                val replyParts = splitAiEmojiReply(reply)
                                contentIdx = if (replyParts.isEmpty()) -1 else messages.size
                                for ((pi, p) in replyParts.withIndex()) {
                                    val snap = if (pi == 0) Triple(holder.thinkingSnapshot(), holder.toolsSnapshot(), holder.timelineSnapshot()) else Triple("", "", "")
                                    messages.add(MemoryDb.SessionMsg("assistant", p, snap.first, snap.second, snap.third, System.currentTimeMillis()))
                                    MemoryKeeper.push("assistant", p)
                                }
                                currentSaved = false
                                // 回复完成即时落库, 防止进程被杀丢失最后一条回复
                                maybeSaveCurrent()
                                // 兜底: 引擎重试后仍无正文时给出明确提示, 避免"思考了但没输出"静默空白
                                // 仅在仍是原会话时追加, 防止切会话后提示写入新会话
                                if (reply.isBlank()) appendSys(getString(R.string.ma_sys_no_reply))
                            }
                        }
                        holder.finishContent()
                        // 阶段4 增量落库: 正文首条写回 rendered(排版产物所见即所得, 与 finishTypeRender
                        // 同一渲染路径), 防重启/重进二次渲染跳变; 取消(cancel)分支不落库不写回
                        if (contentIdx >= 0 && !LocalEngine.cancelRequested) {
                            val spanned = holder.renderedSnapshot()
                            if (spanned != null && spanned.isNotBlank()) {
                                try { writeBackRendered((sessionBaseSeq + contentIdx).toLong(), spanned as android.text.Spanned) }
                                catch (e: Exception) { android.util.Log.w(TAG, "silent writeback fail", e) }
                            }
                        }
                        activeAiHolder = null
                        // 流式行收尾: 已完成回复内容已落库至 messages, 移除 Streaming 行并重建为静态 AI 行;
                        // 不清理的话, 切模式/开会话全量重建时该行会被 buildRowsFromMessages 兜底再次塞回,
                        // 表现为"切 Agent 串消息 / 切回聊天 AI 回复变两条"(重启进程 streamingRow 归零即恢复)
                        val doneRow = streamingRow
                        streamingRow = null
                        if (!LocalEngine.cancelRequested && doneRow != null) {
                            chatAdapter.remove(doneRow)
                            // 流式行移除后重建为静态 AI 行(挂快照); 重建提交(布局稳定)后未上翻
                            // 则统一精确贴底一次到位(09-25: 消除收尾帧中间态补偿造成的两段式抬升;
                            // 键盘收起同样追底——用户复现场景 imeShown=false 时旧条件直接跳过)
                            buildRowsFromMessages(onCommitted = {
                                android.util.Log.i("NyralIme", "onDone.rebuild scroll=$scrollUserScrolled itemCount=${chatAdapter.itemCount} imeShown=$imeShown")
                                if (!scrollUserScrolled) scrollToBottom(auto = true, force = true)
                                // 收尾渲染延迟增高(09-25): rebuild 后 markwon 静态渲染/收尾排版可能在
                                // align 之后才使气泡变高, pendingAlign 已扣到 0 不再补; 延迟再对齐一次
                                // 覆盖最终高度, 防最后几行被输入框遮住(用户现场: 末两行在输入框下边)
                                chatRec.postDelayed({
                                    if (!scrollUserScrolled) scrollToBottom(auto = true, force = true)
                                }, 150)
                            })
                        }
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                    }
                    debugSseSink?.invoke("done", reply)
                    debugChatDone?.invoke()
                }
                override fun onError(msg: String) {
                    LogStore.e(LogStore.MAIN, "错误: $msg")
                    AITerminal.push("error", msg)
                    uiScope.launch {
                        if (epoch != requestEpoch) return@launch
                        holder.showError(getString(R.string.ma_error_fmt, msg))
                        // 错误行收尾: 移除流式行, 错误提示以系统行保留(避免重建时僵尸行重复渲染)
                        val errRow = streamingRow
                        streamingRow = null
                        if (errRow != null) chatAdapter.remove(errRow)
                        appendSys(getString(R.string.ma_error_fmt, msg))
                        LocalEngine.cancelRequested = false
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                    }
                    debugSseSink?.invoke("error", msg)
                    debugChatDone?.invoke()
                }
            }, attachments)
        }
    }

    /**
     * 调试服务入口: 由 DebugServer(/v1/chat) 调用, 走与真实发送一致的完整链路
     * (渲染气泡 -> 落库 -> 构建历史 -> LocalEngine 工具循环 -> SSE 事件转发)。
     * 必须在主线程调用。返回 false 表示 AI 正忙, 请求被拒绝。
     */
    internal fun submitDebugChat(text: String, attachments: List<LocalEngine.Attachment> = emptyList(), onDone: () -> Unit): Boolean {
        if (aiBusy) return false
        debugChatDone = onDone
        TokenStore.currentSessionId = currentSessionId
        LocalEngine.cancelRequested = false
        aiBusy = true
        continueSend(text, listOf(text), attachments)
        return true
    }

    /** 中期摘要(每次实时读库, 辅助AI后台可能已更新) + 短期最近 KEEP 条; 每条消息携带时间戳(如 user[23:05]: ...)让模型建立时间观念 */
    private fun buildHistory(): String {
        summary = db.loadSummary() ?: summary
        val sb = StringBuilder()
        // 首轮/无历史时也提供时间锚点, 让模型始终有时间观念
        sb.append("[当前时间]\n")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
            .append("\n\n")
        summary?.let { sb.append("[历史摘要]\n$it\n\n") }
        val recent = messages.takeLast(KEEP)
        sb.append(recent.joinToString("\n") { m ->
            // DSML 净化(2026-09-18): 剥除历史消息中的 DSML 泄漏块(含半截脏数据),
            // 防止其回流上下文引发模型模仿泄漏格式(恶性循环); 纯 DSML 消息以占位符保留角色时序
            val stripped = LocalEngine.stripDsml(m.content)
            val c = when {
                stripped.isEmpty() -> "(历史工具调用记录)"
                stripped.length > MAX_MSG_HISTORY -> stripped.take(MAX_MSG_HISTORY) + "\n…[该消息过长已截断]"
                else -> stripped
            }
            val t = MemoryDb.fmtTs(m.ts)
            if (t.isEmpty()) "${m.role}: $c" else "${m.role}[$t]: $c"
        })
        // 注意: 不再原地截断 messages —— 否则 onStop 保存时会把被截断的列表写回数据库, 造成旧消息永久丢失。
        // 历史裁剪交给 buildHistory 的 takeLast 窗口即可, 完整历史始终保留在 messages/数据库里。
        return sb.toString()
    }

    /** AI 表情标记流式掩码: 完整 [表情:名] 显示为〔表情〕占位; 跨 delta 分片的半截标记缓冲到 emojiMaskTail */
    private fun maskAiEmojiMarks(delta: String): String {
        val full = emojiMaskTail + delta
        var tail = ""
        val lastOpen = full.lastIndexOf('[')
        val lastClose = full.lastIndexOf(']')
        if (lastOpen > lastClose && full.startsWith("[表情:", lastOpen)) {
            tail = full.substring(lastOpen)
        }
        emojiMaskTail = tail
        val head = if (tail.isNotEmpty()) full.substring(0, lastOpen) else full
        return head.replace(Regex("\\[表情:[^\\]]*\\]"), "〔表情〕")
    }

    /** AI 回复拆分: [表情:名] 命中表情库白名单则拆为独立表情气泡消息; 未命中/Agent 模式(emojiEnabled=false)保留原文 */
    private fun splitAiEmojiReply(reply: String): List<String> {
        if (!ModeConfig.emojiEnabled() || !reply.contains("[表情:")) return listOf(reply)
        val nameToFile = try {
            val a = org.json.JSONArray(getSharedPreferences("emoji_drawer", android.content.Context.MODE_PRIVATE).getString("lib_items", "[]") ?: "[]")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name"); val f = o.optString("file")
                if (n.isNotBlank() && f.isNotBlank()) n to f else null
            }.toMap()
        } catch (e: Exception) { emptyMap() }
        if (nameToFile.isEmpty()) return listOf(reply)
        val re = Regex("\\[表情:([^\\]]+)\\]")
        var last = 0
        var hasSplit = false
        val parts = mutableListOf<String>()
        for (m in re.findAll(reply)) {
            val name = m.groupValues[1].trim()
            val file = nameToFile[name] ?: continue // 白名单外: 不拆, 保留原文
            val head = reply.substring(last, m.range.first)
            if (head.isNotBlank()) parts.add(head)
            parts.add("[表情:$name](att://$file)")
            last = m.range.last + 1
            hasSplit = true
        }
        if (!hasSplit) return listOf(reply)
        val tail = reply.substring(last)
        if (tail.isNotBlank()) parts.add(tail)
        return if (parts.isEmpty()) listOf(reply) else parts
    }

    // ===================== 气泡渲染 =====================

    /** 流式行气泡盒入场: v8.7 去掉"上移+淡入"蹦出动画(用户反馈消息是'蹦'出来的), 直接显示 */
    internal fun enterBubble(v: View) {
        v.post { v.alpha = 1f; v.translationY = 0f }
    }

    private fun appendUser(content: String) {
        // 阶段4 时间标签: 首条消息或距上条消息 >=30 分钟时, 先插分组标签(appendUser 前 messages 已含本条)
        val last = messages.getOrNull(messages.size - 2)
        val ts = System.currentTimeMillis()
        if (last == null || ts - last.ts >= TIME_TAG_GAP) {
            // 稳定 id: 用消息时间戳 ms 作 rowId, 与重建公式一致, 全量重建后 DiffUtil 视为同一行不再"删+增"闪跳
            chatAdapter.add(ChatRow.TimeTag(ts, formatTimeTag(ts)))
        }
        // 稳定 id: 本条在 messages 中的索引(messages.add 在 appendUser 之后), 与重建公式一致;
        // fix4: 追底挂到 diff 提交后(onCommitted), itemCount 已是新值, 否则 skip 判断用旧值误跳过拉底
        chatAdapter.add(ChatRow.User(sessionBaseSeq + messages.size.toLong(), content)) {
            scrollToBottom()   // 用户气泡照常拉底
        }
    }

    /** 一键到底按钮显隐: 正文阶段(未跟随)且可向下滚动时显示 */
    private fun updateJumpFab() {
        val fab = jumpFab ?: return
        // 09-24 定稿: AI 输出中隐藏(跟随=看打字动画不是读消息, 输出中滚到底只是瞄进度,
        // 无按钮语义); 仅静态显示"跳到最新", 点击一次性回底, 无跟随
        val show = !aiBusy && !contentFollow && chatRec.canScrollVertically(1)
        fab.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun appendSys(content: String) {
        chatAdapter.add(ChatRow.Sys(nextTempRowId(), content)) { scrollToBottom(true) }
    }

    /** 新会话开场介绍卡片：首启/新建会话时展示（文案集中在 strings.xml 便于迭代，预留可进化接口） */
    private fun appendWelcomeIntro() {
        chatAdapter.add(ChatRow.Welcome(nextTempRowId())) { scrollToBottom() }
    }

    /** RecyclerView 行渲染分发: 每条 ChatRow 对应一个气泡(复用既有 bubble/aiBubbleWithThinking 渲染, 不重造轮子) */
    // ===== 滑动丝滑优化（09-28）：形态池化 =====
    // 气泡定位标记：chatWrap 容器内唯一标识气泡 View（User/Ai 池化行使用）
    private val POOLED_BUBBLE_TAG = "nyral_pooled_bubble_" + System.identityHashCode(this)
    // ===== 滑动丝滑优化（09-28 第二波）：AiRich 行内部池化 =====
    private val aiRichSegPool = ArrayList<TextView>()      // AiRich 正文分片 TextView 池
    private val aiRichWrapPool = ArrayList<LinearLayout>() // AiRich chatWrap(横向容器)池
    private val aiAvatarPool = ArrayList<View>()           // AiRich chatWrap 的 AI 头像池
    // 行形态稳定且创建成本高的纯文本类行进入形态池：滚出滚回复用同一 View 树，bind 只 setText
    private fun chatRowPoolType(row: ChatRow): Int = when (row) {
        is ChatRow.User -> if (isPoolableUserText(row.content)) ChatAdapter.PT_USER_TEXT else ChatAdapter.PT_NONE
        is ChatRow.Ai -> if (!row.content.contains("att://") && !row.content.contains("[表情:") && !row.content.contains("emoji_lib/"))
            ChatAdapter.PT_AI_TEXT else ChatAdapter.PT_NONE
        is ChatRow.Sys -> ChatAdapter.PT_SYS
        is ChatRow.TimeTag -> ChatAdapter.PT_TAG
        is ChatRow.Welcome -> ChatAdapter.PT_WELCOME
        is ChatRow.AiRich -> ChatAdapter.PT_AI_RICH
        else -> ChatAdapter.PT_NONE
    }

    // 保守等价 bubble() 富媒体分支：含富媒体标记一律不池化（维持原重建路径），纯文本才池化
    private fun isPoolableUserText(content: String): Boolean =
        !content.contains("[表情:") && !content.contains("emoji_lib/") &&
            !content.contains("[视频:") && !content.contains("[图片]") &&
            !content.contains("[音频]") && !content.contains("[文件:")

    // 池化命中时的原地内容更新（View 树不复建，只刷新数据）
    // 注意：User/Ai 行 buildRowView 返回 chatWrap 容器（含头像布局），气泡本身打了
    // POOLED_BUBBLE_TAG 标记，这里 findViewWithTag 穿透容器定位 TextView 再更新
    private fun bindPooledView(v: View, row: ChatRow) {
        when (row) {
            is ChatRow.User -> {
                val b = v.findViewWithTag<View>(POOLED_BUBBLE_TAG)
                if (b is TextView) {
                    b.text = renderUserContent(row.content)
                    val userMaxW = if (ModeConfig.chatMode()) chatMaxW()
                    else (chatMaxW() - dp(24)).coerceAtLeast(dp(120))
                    (b.layoutParams as? LinearLayout.LayoutParams)?.width =
                        if (row.content.length > 60) userMaxW else ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            is ChatRow.Ai -> {
                val b = v.findViewWithTag<View>(POOLED_BUBBLE_TAG)
                if (b is TextView) {
                    if (ModeConfig.chatPlainText()) b.text = ModeConfig.stripMarkdownForChat(row.content.trim())
                    else setMarkdownCached(b, ModeConfig.stripChatProtocolPrefix(row.content.trim()), row.rendered) { spanned -> writeBackRendered(row.id, spanned) }
                }
            }
            is ChatRow.Sys -> if (v is TextView) v.text = row.text
            is ChatRow.TimeTag -> if (v is TextView) v.text = row.text
            is ChatRow.AiRich -> if (v is LinearLayout) bindAiRichPooled(v, row)
            else -> {}
        }
    }

    internal fun buildRowView(row: ChatRow): View = when (row) {
        is ChatRow.User -> chatWrap(bubble(row.content, isUser = true).also { it.tag = POOLED_BUBBLE_TAG }, true)
        is ChatRow.Ai -> chatWrap(bubble(row.content, isUser = false, rendered = row.rendered, writeback = { spanned -> writeBackRendered(row.id, spanned) }).also { it.tag = POOLED_BUBBLE_TAG }, false)
        is ChatRow.AiRich -> aiBubbleWithThinking(row.thinking, row.content, row.tools, row.timeline, row.rendered) { spanned -> writeBackRendered(row.id, spanned) }
        is ChatRow.Sys -> TextView(this).apply {
            text = row.text
            textSize = 12f
            setTextColor(SYS_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        is ChatRow.Welcome -> welcomeCardView()
        is ChatRow.TimeTag -> TextView(this).apply {
            text = row.text
            textSize = 11f
            setTextColor(SYS_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
        }
        is ChatRow.Streaming -> row.bubbleBox ?: View(this)
    }

    /** 按当前 messages 重建 chatRows 并整体提交给 RecyclerView(会话打开/头像刷新/窗口外回退共用) */
    private fun buildRowsFromMessages(onCommitted: (() -> Unit)? = null) {
        chatRows.clear()
        var lastTs = 0L
        var msgIdx = 0
        for (m in messages) {
            // 阶段4 sanitizeMessages: 丢弃全空消息(防 DB 残留空白行污染界面)
            if (m.content.isBlank() && m.thinking.isBlank() && m.tools.isBlank()) { msgIdx++; continue }
            // 阶段4 时间标签: 首条消息或距上条消息 >=30 分钟时插入分组标签(纯展示层, 不动数据);
            // rowId 用消息时间戳 ms, 与运行期 appendUser 公式一致, 重建后 DiffUtil 视为同一行不闪跳
            if (lastTs == 0L || m.ts - lastTs >= TIME_TAG_GAP) {
                chatRows.add(ChatRow.TimeTag(m.ts, formatTimeTag(m.ts)))
            }
            lastTs = m.ts
            // 稳定 id: 与运行期 appendUser 的 sessionBaseSeq+messages.size 公式一致,
            // 重建后 DiffUtil 视为同一行(仅内容变化), 不再"删除+新增"导致滚动闪跳
            chatRows.add(
                when {
                    m.role == "user" -> ChatRow.User(sessionBaseSeq + msgIdx.toLong(), m.content)
                    m.thinking.isNotBlank() || m.tools.isNotBlank() || m.timeline.isNotBlank() ->
                        ChatRow.AiRich(sessionBaseSeq + msgIdx.toLong(), m.thinking, m.content, m.tools, m.timeline, m.rendered)
                    else -> if (containsTableSyntax(ModeConfig.stripChatProtocolPrefix(m.content)))
                        // 方案B(2026-10-01): 无 thinking/tools 的纯正文消息若含表格, 路由到 AiRich 走块化渲染
                        ChatRow.AiRich(sessionBaseSeq + msgIdx.toLong(), "", m.content, "", "", m.rendered)
                    else ChatRow.Ai(sessionBaseSeq + msgIdx.toLong(), m.content, m.rendered)
                })
            msgIdx++
        }
        // 阶段2 兜底: 仅 AI 输出中触发全量重建(如窗口外回退)时追加流式行到末尾不丢失气泡盒;
        // 输出完成后(aiBusy=false)不再塞回, 防止已收尾的 Streaming 行变成僵尸行重复渲染
        if (aiBusy) {
            streamingRow?.let { if (it !in chatRows) chatRows.add(it) }
        }
        chatAdapter.submit(chatRows.toList()) {
            onCommitted?.invoke()
            // 批量加载窗口: 全量重建提交后置窗口, 首帧布局(bind)期间抑制内嵌播放器创建,
            // 全部走缩略图, 避免"大量视频行同一帧同步 inflate+prepare 硬解码"整屏闪烁卡顿;
            // 布局完成(再等一帧)后释放窗口并主动对账补建, 静止首屏也能自动播放
            sBatchLoad = true
            chatRec.post {          // 帧1: diff 提交后的首帧布局+bind(窗口仍生效, 不建播放器)
                chatRec.post {      // 帧2: 布局完成, 释放窗口并补建一次
                    sBatchLoad = false
                    reconcileVideoBubbles(chatRec)
                    reconcileEmojiBubbles(chatRec)   // 表情行同批降级, 一并补建恢复播放
                    // 兜底(2026-09-20): 帧2 对账时 RV 锚点/ViewHolder 可能未就绪(scrollToPosition
                    // 锚点在下一布局帧才生效), 尾部最新两条可能不在可见范围而漏补建; 延迟再对账一次
                    // 覆盖, 幂等安全: 已播放行走续播不重复重建, 无变化则空跑
                    chatRec.postDelayed({
                        if (sScrolling) return@postDelayed
                        reconcileVideoBubbles(chatRec)
                        reconcileEmojiBubbles(chatRec)
                    }, 300)
                }
            }
        }
    }

    /** 运行期新行临时 id: 负数递减, 与 DB 全局 seq(历史行 id=sessionBaseSeq+i)不冲突 */
    private var tempRowSeq = 0L
    private fun nextTempRowId(): Long { tempRowSeq--; return tempRowSeq }

    /** 阶段4 时间标签分组阈值: 消息间隔 >=30 分钟视为新时间段, 插入时间标签 */
    private val TIME_TAG_GAP = 30L * 60L * 1000L

    /** 阶段4 时间标签文案: 当天只显 HH:mm, 跨天补日期 */
    private fun formatTimeTag(ts: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val now = java.util.Calendar.getInstance()
        val sameDay = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        val fmt = if (sameDay) java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(ts)
    }

    /** 新会话开场介绍卡片 View(欢迎卡片本体, 不含外侧气泡壳) */
    private fun welcomeCardView(): View {
        val maxW = chatMaxW()
        val cardW = (maxW * 0.92f).toInt().coerceAtLeast(dp(260))
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(dp(16), floatBubbleColor(BUBBLE_AI))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.welcome_intro_title)
                textSize = 16f
                setTextColor(Ui.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
            addView(TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(BUBBLE_AI_TEXT)
                setLineSpacing(dp(3).toFloat(), 1f)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                synchronized(markwonRenderLock) { markwon.setMarkdown(this, getString(R.string.welcome_intro_body)) }
            })
        }
        inner.layoutParams = LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
        return chatWrap(inner, false)
    }

    /** AI ask_user 澄清对话框: 标题+问题正文+候选选项按钮(全宽浅色), 可选自定义输入; 点外部/返回视为取消 */
    private fun showAskUserDialog(question: String, options: List<String>, allowCustom: Boolean, onPick: (String) -> Unit) {
        val picked = AtomicBoolean(false)
        val (dlg, box) = Ui.dialog(this, getString(R.string.ask_user_title), maxHeightRatio = 0.75)
        dlg.setOnDismissListener {
            if (!picked.get()) {
                picked.set(true)
                onPick(getString(R.string.ask_user_cancel))
            }
        }
        box.addView(Ui.dialogText(this, question))
        options.forEach { opt ->
            box.addView(TextView(this).apply {
                text = opt
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                setTextColor(Ui.PRIMARY)
                background = Ui.rounded(Ui.PRIMARY_LIGHT, 12, this@MainActivity)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                Ui.press(this)
                setOnClickListener {
                    picked.set(true)
                    dlg.dismiss()
                    onPick(getString(R.string.ask_user_pick, opt))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        if (allowCustom) {
            val edit = Ui.input(this, getString(R.string.ask_user_custom_hint))
            box.addView(edit)
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
                addView(Ui.dialogCancelBtn(this@MainActivity, getString(R.string.ask_user_submit), {
                    val t = edit.text.toString().trim()
                    if (t.isEmpty()) {
                        Toast.makeText(this@MainActivity, getString(R.string.ask_user_empty), Toast.LENGTH_SHORT).show()
                        return@dialogCancelBtn
                    }
                    picked.set(true)
                    dlg.dismiss()
                    onPick(getString(R.string.ask_user_typed, t))
                }))
            })
        }
        dlg.show()
    }

    /** 生成一条消息气泡 View (用户右深色 / AI 左浅色) */
    private fun bubble(content: String, isUser: Boolean, rendered: String = "", writeback: ((Spanned) -> Unit)? = null): View {
        val maxW = chatMaxW()
        // Agent 模式用户右气泡最大宽=chatBox内容宽(屏宽-左右padding 12dp*2), 与AI同为全屏幅宽且左右对称;
        // 不可用全屏w: 全屏w+END右对齐且可用区<气泡宽时左边缘偏移为负→左边越出屏幕
        val userMaxW = if (ModeConfig.chatMode()) maxW else (maxW - dp(24)).coerceAtLeast(dp(120))
        // 专门表情气泡(微信式96dp小图贴边): 表情库项统一入口, 优先于视频/图片链路;
        // 识别 [表情:xxx] 新标记 + 历史兼容(emoji_lib/ 路径的视频/图片消息); 非表情/异常回退原链路
        val emojiView = try { emojiBubble(content, isUser, if (isUser) userMaxW else maxW) } catch (e: Exception) { null }
        if (emojiView != null) return emojiView
        // 接收端气泡循环播放管线: 纯视频单附件消息 -> 气泡内嵌 PlayerView 自动循环播放(动图/多帧媒体
        // 播放完一次自动重播 loop), 对齐微信"大动图循环视频"; 非纯视频/异常回退原缩略图渲染
        val loopView = try { videoLoopBubble(content, isUser, if (isUser) userMaxW else maxW) } catch (e: Exception) { null }
        if (loopView != null) return loopView
        // 纯图片附件消息: 贴边不留白; 纯文件/上传音频保留正常内边距(文件卡片角标+文件名需留白)
        val pureImage = isUser && content.replace(Regex("""\[.+?\]\(att://[^)]+\)"""), "").trim().isEmpty() &&
            content.contains("[图片]")
        // 纯视频消息: 与图片同机制, 首帧缩略图内嵌气泡, 四边边距无限接近 0
        val pureVideo = isUser && content.replace(Regex("""\[.+?\]\(att://[^)]+\)"""), "").trim().isEmpty() &&
            content.contains("[视频:")
        // 纯语音消息: 微信式语音气泡, 加大内边距 + 最小宽度, 保证可点区域够大
        val pureAudio = isUser && content.replace(Regex("""\[.+?\]\(att://[^)]+\)"""), "").trim().isEmpty() &&
            content.contains("[音频]")
        // 纯文件卡片消息(文件/视频/上传音频): 文件名在渲染时已按参考宽度手动中间省略(短名自适应, 长名保留首尾+后缀)
        val isFileCard = isUser && (content.contains("[文件:") || content.contains("[视频:")) &&
            content.replace(Regex("""\[.+?\]\(att://[^)]+\)"""), "").trim().isEmpty()
        return TextView(this).apply {
            if (isUser) {
                text = renderUserContent(content)
                movementMethod = LinkMovementMethod.getInstance()
                // 视频缩略图异步就绪后重建本气泡文本以显示画面缩略图(原: 系统栈取帧失败→ 仅文件卡片)
                val vkN = Regex("""\[视频:.+?\]\(att://([^)]+)\)""").find(content)?.groupValues?.get(1)
                if (vkN != null && isThumbPending(vkN)) {
                    registerThumbRefresh(vkN) {
                        try { text = renderUserContent(content) } catch (_: Throwable) {}
                    }
                }
            } else if (ModeConfig.chatPlainText()) text = ModeConfig.stripMarkdownForChat(content.trim())
            else setMarkdownCached(this, ModeConfig.stripChatProtocolPrefix(content.trim()), rendered, writeback)
            textSize = 15f
            val edgeImage = pureImage || pureVideo
            setLineSpacing(if (edgeImage) 0f else dp(3).toFloat(), 1f)
            // 纯图片/纯视频气泡关掉系统字体上下留白, 行高紧贴内容, 消除残余细边
            includeFontPadding = !edgeImage
            setTextColor(if (isUser) BUBBLE_USER_TEXT else BUBBLE_AI_TEXT)
            val padH = if (pureAudio) dp(18) else if (edgeImage) dp(0) else dp(14)
            val padV = if (pureAudio) dp(13) else if (edgeImage) dp(0) else dp(10)
            setPadding(padH, padV, padH, padV)
            if (pureAudio) minWidth = dp(132)
            // 纯语音气泡: 整块点击 播放/暂停 切换; 注册到列表, 状态变化时统一刷新图标
            if (pureAudio) {
                tag = content
                audioBubbles.add(WeakReference(this))
                val fname = Regex("""\(att://([^)]+)\)""").find(content)?.groupValues?.get(1)
                if (fname != null) setOnClickListener { togglePlayAudio(fname) }
            }
            // 纯图/纯视频气泡: 背景改透明, 气泡形态完全由图片圆角(dp14)体现, 彻底消除四角蓝色边线
            background = if (edgeImage) null else rounded(dp(14), floatBubbleColor(if (isUser) BUBBLE_USER else BUBBLE_AI))
            maxWidth = if (isUser) userMaxW else maxW
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 用户气泡>60字符固定宽, 短文本自适应; 文件卡片始终自适应(由 maxWidth 负责超宽省略);
                // AI 气泡始终自适应(与流式路径一致, 短内容收窄, 长内容由 maxWidth 封顶)
                // 纯图片/纯视频气泡(edgeImage): 必须 WRAP_CONTENT —— 宽度由图片 span 决定,
                // 否则长文件名(content>60)触发 maxW 固定宽, 图片 span 在 TextView 内默认左对齐,
                // 整个图片气泡会被推到屏幕左侧(右对齐被固定宽度架空)
                width = when {
                    isUser && content.length > 60 && !isFileCard && !edgeImage -> userMaxW
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                }
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = dp(6)
            }
            // 附件消息(含图片 span)不能用 setTextIsSelectable: 该模式下 ReplacementSpan 图片不绘制;
            // 纯文本消息保持可选中复制
            if (!content.contains("att://")) makeCopyable(this)
        }
    }

    /**
     * 接收端气泡循环播放管线: 纯视频单附件消息 气泡内嵌 PlayerView 自动循环播放。
     * 动图/多帧媒体 播放完一次后自动重播(loop), 对齐微信"大动图循环视频" — 发送后接收端气泡内自己动。
     * v1.3 已启用回退保护: 仅当消息为"纯单视频无其他文本"、文件未损坏且尺寸合理时内嵌; 否则返回 null 走原缩略图渲染。
     */

    /** 递归找行视图树里是否有 PlayerView(= 内嵌播放器活跃): 行结构随模式变化(有无头像包装层), 逐层找最稳 */
    private fun hasActivePlayerView(v: View): Boolean {
        // 只认"持有活播放器"的 PlayerView: killSelf 会把 pv.player 置空, 残留的空 PlayerView 视为无播放器。
        // 若按"存在 PlayerView"判定, killSelf 后离屏未降级的空壳会被补播复核当成"已在播"永久跳过
        // → 视频黑屏且只有切会话/重启(整行重建)才恢复(真机 09-19 02:50 滚动压测复现)
        if (v is PlayerView) return v.player != null
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasActivePlayerView(v.getChildAt(i))) return true
        return false
    }

    /** 纯视频消息判定(与 videoLoopBubble 同规则): 只有纯视频单附件消息才建内嵌播放器,
     *  非纯视频行 notify 重绑也只会得到缩略图, 白耗整行重建 */
    private fun isPureVideoMsg(content: String): Boolean {
        val re = Regex("""\[.+?\]\(att://[^)]+\)""")
        val marks = re.findAll(content).toList()
        if (marks.size != 1 || !marks[0].value.startsWith("[视频:")) return false
        return content.replace(re, "").trim().isEmpty()
    }

    /** 纯表情消息判定(与 emojiBubble 同规则): 单附件 + [表情: 标记, 或历史 emoji_lib/ 路径的 [视频:]/[图片];
     *  供表情对账(reconcileEmojiBubbles)精确识别行, 避免把普通视频/图片行当表情重建 */
    private fun isPureEmojiMsg(content: String): Boolean {
        val re = Regex("""\[.+?\]\(att://[^)]+\)""")
        val marks = re.findAll(content).toList()
        if (marks.size != 1) return false
        val markText = marks[0].value
        val isEmojiMark = markText.startsWith("[表情:")
        val isLegacyEmoji = (markText.startsWith("[视频:") || markText.startsWith("[图片]")) &&
            markText.contains("emoji_lib/")
        if (!isEmojiMark && !isLegacyEmoji) return false
        return content.replace(re, "").trim().isEmpty()
    }

    /** 递归找行视图树里活跃 ExoPlayer(PlayerView.player 非空即活); 与 hasActivePlayerView 同遍历,
     *  对账时用于收集"可见行持有的播放器", 杀离屏只认活跃实例 */
    private fun activePlayerOf(v: View): ExoPlayer? {
        if (v is PlayerView) return v.player as? ExoPlayer
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            activePlayerOf(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** QQ 式统一对账(替换原错峰补建): 滚动停止后以"当前可见行"为唯一真相,
     *  1) 名额重排——可见纯视频行按 position 顺序, 前 INLINE_MAX 个获得播放权(已有则保留续播,
     *     没有则待补建); 超出配额的行即使持有旧播放器也统一 killSelf 让位(否则旧行占坑导致
     *     新行捡剩, 播放顺序乱跳 135/24, 真机 09-19 反馈);
     *  2) 杀离屏——不在可见行的活跃播放器统一 killSelf, 名额立即归还;
     *  3) 按序补建——有播放权的空缺行按顺序 notify, 由入口按配额串行创建。
     *  名额只发可见行, 预取/复用池 attach 的行不再有资格, 根治"离屏行抢走可见行播放权"打架 */
    private fun reconcileVideoBubbles(rv: RecyclerView) {
        // 滑动中一律不做对账(QQ 式): 名额让出广播/滚动停止前都可能触发本函数,
        // 若在此续播/补建会把滑动中已暂停的视频复活, 打断暂停(真机 09-19 滑动中视频复播反馈);
        // 滑动停止时 onScrollStateChanged 会统一调一次对账, 不丢补建
        if (sScrolling) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        // 尾部扩展(2026-09-20): 批量重建后首帧布局锚点未就绪时 findLastVisible 可能不含最新两条,
        // 仅当可见区已接近列表末尾时扩展到末尾覆盖它们; 屏幕外行会被下方中心检测区天然过滤, 不会误建
        val lastVis = lm.findLastVisibleItemPosition()
        val last = if (lastVis >= chatRows.lastIndex - 2) chatRows.lastIndex else lastVis
        if (first < 0 || last < first) return
        android.util.Log.d("ReconcileV", "trigger first=$first last=$last lastVis=$lastVis count=${chatRows.size} sScrolling=$sScrolling sBatchLoad=$sBatchLoad players=${sBubblePlayers.size} gate=${ExoGate.INLINE_MAX}")
        // 中心检测区跟随真实 UI 边界: 顶栏底部 → 底栏顶部(标题栏/输入栏锚定),
        // 标题栏多高遮多高、输入栏多高遮多高, 手机/平板/折叠屏/键盘弹起均自适应;
        // 不再用固定屏幕比例(比例制在平板等大屏会遮掉本可播放区域, 09-19 用户确认改锚定)
        val rvLoc = IntArray(2).also { rv.getLocationInWindow(it) }
        val tLoc = IntArray(2).also { titleBar.getLocationInWindow(it) }
        val iLoc = IntArray(2).also { inputBar.getLocationInWindow(it) }
        val detectTop = (tLoc[1] + titleBar.height - rvLoc[1]).toFloat().coerceAtLeast(0f)
        val detectBottom = (iLoc[1] - rvLoc[1]).toFloat().coerceAtLeast(0f)
        if (detectBottom <= detectTop) return   // 布局异常(顶栏压到底栏)时不对账, 避免区间倒挂
        // 收集候选行(按 position 顺序)及其持有的活跃播放器
        val visibleRows = ArrayList<Pair<Int, ExoPlayer?>>()
        for (pos in first..last) {
            val row = chatRows.getOrNull(pos)
            if (row !is ChatRow.User || !isPureVideoMsg(row.content)) continue
            val vh = rv.findViewHolderForAdapterPosition(pos)
            if (vh == null) { android.util.Log.d("ReconcileV", "cand pos=$pos vh=null"); continue }
            val iv = vh.itemView
            val ivLoc = IntArray(2).also { iv.getLocationInWindow(it) }
            val centerY = ivLoc[1] + iv.height / 2f - rvLoc[1]
            val pass = centerY >= detectTop && centerY <= detectBottom
            android.util.Log.d("ReconcileV", "cand pos=$pos center=$centerY top=$detectTop bottom=$detectBottom pass=$pass")
            if (!pass) continue   // 边缘行不参与名额分配
            visibleRows.add(pos to activePlayerOf(iv))
        }
        // 1) 名额重排: 前 INLINE_MAX 个可见行获得播放权, 其余一律让位
        val keepExos = HashSet<ExoPlayer>()
        val pendingRows = ArrayList<Int>()
        var budget = ExoGate.INLINE_MAX
        for ((pos, exo) in visibleRows) {
            if (budget > 0) {
                if (exo != null) keepExos.add(exo) else pendingRows.add(pos)
                budget--
            }
            // 超出配额的行: 有播放器也会在下面统一被杀(不在 keepExos), 空行保持缩略图不补建
        }
        // 2) 杀离屏 + 杀超配额: 不在 keepExos 的活跃播放器统一终结(名额立即归还, 经统一收口)
        for (p in sBubblePlayers.toList()) {
            if (p !in keepExos) sBubbleKillHooks[p]?.invoke()
        }
        // 3) 续播可见(原地恢复, 不重建不闪变)
        for (p in keepExos) { try { p.play() } catch (_: Exception) {} }
        // 帧动画行同步续播(滚动停止后 attachListener 只触发不播, 对账统一恢复)
        for (pos in first..last) {
            val vh = rv.findViewHolderForAdapterPosition(pos) ?: continue
            EmojiFrameAnimator.controllerOf(vh.itemView)?.let { c -> try { c.play() } catch (_: Exception) {} }
        }
        // 4) 按序补建(错峰逐行, 每行间隔 120ms): 同帧 notify 多行 → 下一帧同步 bind 建多个
        //    ExoPlayer prepare 硬解码, 首屏/切会话大量视频行时明显卡顿; 错峰后逐个建, 观感平滑
        if (pendingRows.isNotEmpty()) {
            android.util.Log.d("ReconcileV", "pending=$pendingRows")
            var delay = 0L
            for (pos in pendingRows) {
                rv.postDelayed({
                    if (sScrolling) return@postDelayed   // post 异步: 执行时可能已重新开始滑动, 同样短路
                    if (sBubblePlayers.size >= ExoGate.INLINE_MAX) return@postDelayed
                    val row = chatRows.getOrNull(pos)
                    if (row !is ChatRow.User || !isPureVideoMsg(row.content)) return@postDelayed
                    val vh = rv.findViewHolderForAdapterPosition(pos) ?: return@postDelayed
                    if (hasActivePlayerView(vh.itemView)) return@postDelayed
                    chatAdapter.notifyItemChanged(pos)
                }, delay)
                delay += 120L
            }
        }
    }

    /** 表情气泡专用对账(与 reconcileVideoBubbles 同框架, 独立于视频配额互不误杀):
     *  重启/切会话后表情行 bind 时若遇 名额满/滑动中/批量加载 会降级静态缩略图且不再补建,
     *  导致"发出去的表情不动"(09-19 用户反馈); 本对账在滚动停止/ExoGate 释放/批量加载结束
     *  三个触发点按可见行重排名额, 让降级行重新 notify 走 emojiBubble 播放链路。
     *  识别: isPureEmojiMsg 精确判定纯表情行; 已有 PlayerView 的行保留播放权, 空缺行按序补建。
     *  配额: 前 INLINE_MAX 个可见表情行获得播放权, 超配额/离屏的统一经 sEmojiKillHooks 让位。 */
    private fun reconcileEmojiBubbles(rv: RecyclerView) {
        if (sScrolling) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        // 尾部扩展(2026-09-20, 对齐视频对账): 批量重建后锚点未就绪时最新两条可能不在可见范围,
        // 仅当可见区接近列表末尾时扩展到末尾覆盖; 表情行无中心检测区, 屏幕外行靠 ViewHolder 缺失自然跳过
        val lastVis = lm.findLastVisibleItemPosition()
        val last = if (lastVis >= chatRows.lastIndex - 2) chatRows.lastIndex else lastVis
        if (first < 0 || last < first) return
        android.util.Log.d("ReconcileE", "trigger first=$first last=$last lastVis=$lastVis count=${chatRows.size} sScrolling=$sScrolling sBatchLoad=$sBatchLoad players=${sEmojiPlayers.size}")
        // 表情行对账放宽: 可见即收(不套视频行的中心检测区)——表情帧动画不占解码器名额,
        // 最新两条贴输入栏/边缘遮挡时也能补建播放, 根治"倒数两条永久缩略图不动"(09-19 反馈)
        val visibleRows = ArrayList<Pair<Int, ExoPlayer?>>()
        for (pos in first..last) {
            val row = chatRows.getOrNull(pos)
            val emojiC = when (row) { is ChatRow.User -> row.content; is ChatRow.Ai -> row.content; else -> null } ?: continue
            if (!isPureEmojiMsg(emojiC)) continue
            val vh = rv.findViewHolderForAdapterPosition(pos)
            if (vh == null) { android.util.Log.d("ReconcileE", "cand pos=$pos vh=null"); continue }
            val iv = vh.itemView
            // 帧动画行视为已有播放权(不占 ExoGate 名额): 跳过预算分配, 防把帧动画行当空缺行重建闪变;
            // 原地续播: 滚动中 attach 时 sScrolling 抑制播放、抽帧完成时同样被抑制的行, 滚动停止在此补播;
            // 已软释放(离屏 30s 看门狗)回屏的行由 resumeIfReady -> startDecode 幂等重抽续上 —— 根治"最新一条不动"
            if (EmojiFrameAnimator.hasActiveFrame(iv)) {
                android.util.Log.d("ReconcileE", "cand pos=$pos frameAnim=active resume")
                EmojiFrameAnimator.controllerOf(iv)?.resumeIfReady()
                continue
            }
            android.util.Log.d("ReconcileE", "cand pos=$pos frameAnim=no player=${activePlayerOf(iv) != null}")
            visibleRows.add(pos to activePlayerOf(iv))
        }
        // 1) 名额重排: 前 INLINE_MAX 个可见表情行获得播放权
        val keepExos = HashSet<ExoPlayer>()
        val pendingRows = ArrayList<Int>()
        var budget = ExoGate.INLINE_MAX
        for ((pos, exo) in visibleRows) {
            if (budget > 0) {
                if (exo != null) keepExos.add(exo) else pendingRows.add(pos)
                budget--
            }
        }
        // 2) 杀超配额/离屏: 不在 keepExos 的表情播放器统一终结(经统一收口归还名额)
        for (p in sEmojiPlayers.toList()) {
            if (p !in keepExos) sEmojiKillHooks[p]?.invoke()
        }
        // 3) 续播可见(原地恢复, 不重建不闪变)
        for (p in keepExos) { try { p.play() } catch (_: Exception) {} }
        // 4) 按序补建(错峰逐行 120ms): 让降级为缩略图的表情行重新走播放链路
        if (pendingRows.isNotEmpty()) {
            android.util.Log.d("ReconcileE", "pending=$pendingRows")
            var delay = 0L
            for (pos in pendingRows) {
                rv.postDelayed({
                    if (sScrolling) return@postDelayed
                    if (sEmojiPlayers.size >= ExoGate.INLINE_MAX) return@postDelayed
                    val row = chatRows.getOrNull(pos)
                    val emojiC2 = when (row) { is ChatRow.User -> row.content; is ChatRow.Ai -> row.content; else -> null } ?: return@postDelayed
                    if (!isPureEmojiMsg(emojiC2)) return@postDelayed
                    val vh = rv.findViewHolderForAdapterPosition(pos) ?: return@postDelayed
                    if (hasActivePlayerView(vh.itemView)) return@postDelayed
                    chatAdapter.notifyItemChanged(pos)
                }, delay)
                delay += 120L
            }
        }
    }

    /**
     * 专门表情气泡(微信式 96dp 小图贴边, 无背景边框): 表情库项统一渲染入口。
     * 识别新标记 [表情:xxx](att://...) ; 历史兼容: [视频:xxx] / [图片] 且 att 路径含 emoji_lib/ 也走表情。
     * mp4 动图 → ExoPlayer 静音循环(复用 ExoGate 名额制 + 看门狗 + attach/detach 生命周期, 与视频气泡同机制);
     * jpg 静态图 → 直接解码小图; 名额满/滚动中/批量加载 → 降级静态缩略图(不黑屏)。
     * 固定方形小尺寸、无背景、点击进全屏预览。返回 null 回退原视频/图片链路。
     */
    private fun emojiBubble(content: String, isUser: Boolean, maxW: Int): View? {
        // AI 表情气泡(2026-09-20): 放开 isUser 限制, 聊天模式 AI 纯表情消息同样渲染表情气泡(靠左);
        // Agent 模式 emojiEnabled=false 一律不渲染表情气泡, 标记按原文显示防泄漏
        if (!ModeConfig.emojiEnabled()) return null
        // 仅纯单附件消息: 去掉附件占位后无其余文本
        val re = Regex("""\[.+?\]\(att://([^)]+)\)""")
        val marks = Regex("""\[.+?\]\(att://[^)]+\)""").findAll(content).toList()
        if (marks.size != 1) return null
        if (content.replace(Regex("""\[.+?\]\(att://[^)]+\)"""), "").trim().isNotEmpty()) return null
        val markText = marks[0].value
        // 新标记 [表情:xxx] 或 历史兼容(emoji_lib/ 路径的 [视频:xxx]/[图片] 消息)
        val isEmojiMark = markText.startsWith("[表情:")
        val isLegacyEmoji = (markText.startsWith("[视频:") || markText.startsWith("[图片]")) &&
            markText.contains("emoji_lib/")
        if (!isEmojiMark && !isLegacyEmoji) return null
        val file = re.find(content)?.groupValues?.get(1) ?: return null
        val f = AttachmentStore.fileOf(this, file)
        if (f == null || !f.exists() || f.length() <= 0L) return null
        val mime = AttachmentStore.mimeOf(this, file)
        val isVideo = mime.startsWith("video/") || file.lowercase().endsWith(".mp4")
        // 微信式小尺寸贴边(不超过气泡可用宽); 非正方形动图/图片按真实宽高比适配(短边保底 40dp), 不再硬裁方形
        val maxSide = dp(96).coerceAtMost(maxW)
        var bubbleW = maxSide
        var bubbleH = maxSide
        var stillBmp: android.graphics.Bitmap? = null
        if (!isVideo) {
            // 静态图表情: 直接解码小图(表情文件小, 内存可控, 无需缩略图管线), 用真实宽高比定气泡尺寸
            stillBmp = try { decodeAttachmentBitmap(f, resources.displayMetrics.density) } catch (e: Exception) { null }
            val b = stillBmp
            if (b == null) return null
            val a = if (b.height > 0) b.width.toFloat() / b.height.toFloat() else 1f
            if (a >= 1f) bubbleH = (maxSide / a).toInt().coerceAtLeast(dp(40)) else bubbleW = (maxSide * a).toInt().coerceAtLeast(dp(40))
        } else {
            // 动图表情: 缩略图缓存/媒体元数据取宽高比(见 videoAspectOf), 竖屏窄条/横屏宽条均按比例显示
            val a = videoAspectOf(f)
            if (a >= 1f) bubbleH = (maxSide / a).toInt().coerceAtLeast(dp(40)) else bubbleW = (maxSide * a).toInt().coerceAtLeast(dp(40))
        }
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(bubbleW, bubbleH).apply {
                topMargin = dp(6)
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            // 表情气泡无背景无圆角, 直接贴聊天背景(微信式); 点击进全屏预览(弹窗内图片查看/视频循环)
            background = null
            // 对账识别标记(reconcileEmojiBubbles 用它判定"行视图已是表情气泡"避免重复重建)
            tag = "emoji_bubble_frame"
            setOnClickListener { this@MainActivity.openAttachmentPreview(listOf(file), 0) }
        }
        if (!isVideo) {
            // 静态图表情: frame 已按宽高比适配, FIT 完整显示原图
            val iv = android.widget.ImageView(this@MainActivity).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setImageBitmap(stillBmp)
            }
            frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return frame
        }
        // 动图表情: 短表情优先帧动画(方案C: 不占 ExoPlayer 名额, 同屏可同时动多个);
        // 长表情/滚动/批量/超限/抽帧失败回退 ExoPlayer 名额制兜底
        // 滚动中 bind 也走帧动画(首帧先行, 不依赖缩略图缓存): 发送滚动到底/浏览历史时新行秒现首帧不闪黑;
        // 滚动中不播放, 停止后由 reconcileEmojiBubbles resumeIfReady 原地续播(v4)
        // v6: isShortEmoji 主线程纯缓存查询(永不碰 MMR), 后台 warmup 补齐探测
        EmojiFrameAnimator.warmup(f)
        // v6.1: 帧动画确认不可用(抽帧失败/损坏)的文件直接静态缩略图, 不落 ExoPlayer 兜底,
        // 防"废柴文件"成批转交解码器造成并发内存洪峰(OOM 放大器, 09-20 闪退根因)
        if (EmojiFrameAnimator.isFailed(f)) { attachEmojiThumb(frame, f, bubbleW, bubbleH); return frame }
        if (!sBatchLoad && EmojiFrameAnimator.isShortEmoji(f)) {
            // 发送闪黑根治: attach 前若有缓存首帧缩略图先铺真图垫底(attach 不清空子视图, 帧动画 iv 叠加其上),
            // 抽帧等待期显示真图不黑; 未命中不铺, 等首帧先行(几十 ms)
            peekVideoThumb(f)?.let { thumb ->
                frame.addView(android.widget.ImageView(this@MainActivity).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(thumb)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
            // 帧动画首帧先行: attach 后抽帧线程取到第 0 帧立即上屏
            if (EmojiFrameAnimator.attach(frame, f)) return frame
        }
        // 表情独立配额满/滚动中/批量加载 → 静态缩略图降级(不黑屏不抢解码; 独立于视频配额, 互不误杀)
        if (sEmojiPlayers.size >= ExoGate.INLINE_MAX) { attachEmojiThumb(frame, f, bubbleW, bubbleH); return frame }
        if (sScrolling) { attachEmojiThumb(frame, f, bubbleW, bubbleH); return frame }
        if (sBatchLoad) { attachEmojiThumb(frame, f, bubbleW, bubbleH); return frame }
        val gateToken = Any()
        if (!ExoGate.tryAcquire(gateToken)) { attachEmojiThumb(frame, f, bubbleW, bubbleH); return frame }
        lateinit var exo: ExoPlayer
        try {
            exo = ExoPlayer.Builder(this@MainActivity).build()
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            // 循环播放 + 静音: 与动图无声循环语义一致; 点击气泡进全屏弹窗(弹窗内同样循环)
            exo.repeatMode = ExoPlayer.REPEAT_MODE_ALL
            exo.volume = 0f
            exo.prepare()
        } catch (e: Exception) {
            try { exo.release() } catch (_: Exception) {}
            ExoGate.release(gateToken)
            attachEmojiThumb(frame, f, bubbleW, bubbleH)
            return frame
        }
        sEmojiPlayers.remove(exo)
        sEmojiPlayers.add(exo)
        // 与视频气泡同款 PlayerView(texture_view 防多实例合成层串扰); frame 已按宽高比适配,
        // 改 FIT 完整显示(共享布局默认 zoom 是给视频气泡横向卡片裁剪用的, 这里单独覆盖)
        val pv = LayoutInflater.from(this).inflate(R.layout.video_bubble_view, null) as PlayerView
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.player = exo
        pv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        frame.addView(pv)
        // 生命周期(复用 videoLoopBubble 机制): 创建即武装看门狗 10s; detach 立即让出全局解码名额;
        // attach 回来重新认领续播; 终结(killSelf)统一收口实例+名额+视图, 防 ExoPlayer 堆积 OOM
        var releasePending = true
        var gateReleased = false
        var exoReleased = false
        var dead = false
        val releaseHandler = Handler(Looper.getMainLooper())
        fun degradeToThumb() { attachEmojiThumb(frame, f, bubbleW, bubbleH) }
        fun killSelf() {
            if (dead) return
            dead = true
            releasePending = false
            releaseHandler.removeCallbacksAndMessages(null)
            sEmojiPlayers.remove(exo)
            sEmojiKillHooks.remove(exo)   // 摘钩子防闭包滞留
            try { exo.release() } catch (_: Exception) {}
            // 摘掉已释放实例: 避免空 PlayerView 持死角播放器, 被补播复核误判为"已在播"
            try { pv.player = null } catch (_: Exception) {}
            if (!gateReleased) { gateReleased = true; ExoGate.release(gateToken) }
            exoReleased = true
            if (frame.isAttachedToWindow) degradeToThumb()   // 仍在屏: 降级防黑屏; 已离屏等 attach 重建
        }
        val watchdog = Runnable { if (releasePending) killSelf() }
        sEmojiKillHooks[exo] = { killSelf() }   // 挂表情终结钩子: 对账按配额踢超限/离屏实例时经统一收口(killSelf 已声明)
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (releasePending) {
                    releasePending = false
                    releaseHandler.removeCallbacksAndMessages(null)
                }
                if (exoReleased) {
                    // 实例已终结: 统一降级缩略图, 补建由对账(reconcileVideoBubbles)按可见性+配额驱动
                    degradeToThumb()
                    return@onViewAttachedToWindow
                } else {
                    if (gateReleased) {
                        if (!ExoGate.tryAcquire(gateToken)) { killSelf(); return@onViewAttachedToWindow }
                        gateReleased = false
                    }
                    sEmojiPlayers.remove(exo)
                    sEmojiPlayers.add(exo)
                    sEmojiKillHooks[exo] = { killSelf() }   // 回屏重新挂钩子(参照视频气泡 detach 摘/回屏挂)
                }
                if (!sScrolling) try { exo.play() } catch (_: Exception) {}
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (dead) return
                try { exo.pause() } catch (_: Exception) {}
                sEmojiPlayers.remove(exo)
                sEmojiKillHooks.remove(exo)   // 离屏摘钩子: 防离屏闭包滞留整棵视图(参照视频气泡)
                if (!gateReleased) {
                    gateReleased = true
                    ExoGate.release(gateToken)
                }
                releasePending = true
                releaseHandler.postDelayed(watchdog, 10_000L)
            }
        })
        releasePending = true
        releaseHandler.postDelayed(watchdog, 10_000L)
        return frame
    }

    /** 表情动图降级: 静态首帧缩略图(frame 已按宽高比适配, FIT 完整显示), 无缓存则触发后台取帧, 就绪后自动刷新 */
    private fun attachEmojiThumb(frame: FrameLayout, f: File, w: Int, h: Int) {
        val key = f.name
        val bmp = peekVideoThumb(f)
        val iv = android.widget.ImageView(this@MainActivity).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            if (bmp != null) setImageBitmap(bmp) else setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        frame.removeAllViews()
        frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (bmp == null) {
            // 取帧就绪后自动刷新为真缩略图(避免长时间深色占位被误判"黑屏")
            registerThumbRefresh(key) {
                val nb = peekVideoThumb(f)
                if (nb != null && frame.isAttachedToWindow) {
                    iv.setImageBitmap(nb)
                    iv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
            decodeVideoThumbnailBg(f, resources.displayMetrics.density, this@MainActivity)
        }
    }

    /** 动图表情宽高比: 优先首帧缩略图缓存(保留原始比例, 零解码开销), 兜底媒体元数据; 取不到按 1:1 */
    private fun videoAspectOf(f: File): Float {
        peekVideoThumb(f)?.let { return if (it.height > 0) it.width.toFloat() / it.height.toFloat() else 1f }
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val w = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                val h = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                if (w > 0f && h > 0f) w / h else 1f
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) { 1f }
    }

    private fun videoLoopBubble(content: String, isUser: Boolean, maxW: Int): View? {
        if (!isUser) return null
        // 仅纯视频附件消息: 去掉附件占位后无其余文本, 且不足两个附件
        val re = Regex("""\[.+?\]\(att://([^)]+)\)""")
        val marks = Regex("""\[.+?\]\(att://[^)]+\)""").findAll(content).toList()
        if (marks.size != 1) return null
        if (content.replace(Regex("""\[.+?\]\(att://[^)]+\)"""), "").trim().isNotEmpty()) return null
        if (!marks[0].value.startsWith("[视频:")) return null
        val file = re.find(content)?.groupValues?.get(1) ?: return null
        val f = AttachmentStore.fileOf(this, file)
        if (f == null || !f.exists() || f.length() <= 0L) return null
        // 超大视频/前端直传长视频不内嵌(解码耗电), 走原缩略图+点击进弹窗(弹窗内已 loop)
        if (f.length() > 60L * 1024 * 1024) return null
        if (!AttachmentStore.mimeOf(this, file).startsWith("video/")) return null
        // 内嵌尺寸: 优先"立即可用"的首帧缩略图宽高(与纯视频缩略图一致), 兜底 16:9 估算; 不超过气泡 maxW 与屏高上限。
        // 关键: 必须 allowGrab=false —— 本气泡已内嵌真实循环播放器, 不需要抓帧缩略图; 若临时取不到缩略图,
        // 绝不触发全屏 ExoPlayer 抓帧(系统取帧失败的转发视频/GIF 转码片走该兜底时, 会在真机全屏闪放视频、
        // 抢占合成层与硬件解码器, 把正在循环播放的气泡渲染顶掉, 表现为 Gif/视频气泡黑块或整体消失, 即本故障根因)
        // 统一横向卡片(用户确认): 宽度撑满可用宽(userMaxW), 高度按 16:9 封顶 maxH,
        // PlayerView ZOOM 等比填满裁掉溢出 —— 横屏 16:9 零裁剪完整显示; 竖屏/方形只露中间段,
        // 点击气泡进全屏弹窗看完整视频。此前切换会话后撑满屏是缩略图缓存返回已回收完整帧的
        // 偶然副作用, 现已固化为有意设计, 切不切换会话观感相同。
        val maxH = dp(340)
        var w = maxW
        var h = (w * 9 / 16).coerceAtMost(maxH)
        w = w.coerceAtLeast(dp(80))
        h = h.coerceAtLeast(dp(60))
        // 名额制: 同屏活跃解码数按设备内存动态定级(ExoGate.INLINE_MAX 低端1/中端2/高端3);
        // 已满直接返回 null 走首帧缩略图渲染(renderUserContent 纯视频分支),
        // 不创建 ExoPlayer, 堵住大量视频消息同时 inflate 时全部 prepare 拉起硬解码打爆堆
        // (真机 09-19 00:08: 启动 8 秒连建 7 个 ExoPlayer 全硬解码, 堆 256MB 打满 native 崩溃)
        if (sBubblePlayers.size >= ExoGate.INLINE_MAX) return null
        // QQ 式滚动暂停: 滑动中不创建播放器, 走缩略图降级(vtb 分支); 滚动停止 notify 刷新后重建自动播放
        if (sScrolling) return null
        // 批量加载窗口: 切会话/重启全量重建提交后首帧布局完成前不创建播放器, 全部走缩略图,
        // 避免"大量视频行同一帧同步 inflate+prepare 硬解码"整屏闪烁卡顿(09-19 用户反馈首屏闪烁);
        // 布局稳定后由 buildRowsFromMessages 主动释放标志并对账补建, 静止首屏也能自动播放
        if (sBatchLoad) return null
        // 全局解码器硬上限(内嵌2 + 弹窗/抓帧1): 弹窗预览/抓帧占满时也拒绝创建, 不因绕过气泡名额堆积 OOM
        // gateToken: 本气泡视图的名额持有者令牌——创建/attach重建/回屏复用同一令牌, detach/终结让出;
        // ExoGate 持有者集合按令牌记账, 双还/双领幂等, 杜绝裸计数漂移导致的全局假满黑屏
        val gateToken = Any()
        if (!ExoGate.tryAcquire(gateToken)) return null
        lateinit var exo: ExoPlayer
        try {
            exo = ExoPlayer.Builder(this@MainActivity).build()
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            // 循环播放: 播放完一次自动重播(loop), 静音自动播放(与动图无声语义一致), 点击气泡进全屏弹窗
            exo.repeatMode = ExoPlayer.REPEAT_MODE_ALL
            exo.volume = 0f
            exo.prepare()
        } catch (e: Exception) {
            // lateinit 未初始化(建 ExoPlayer 即抛)时 exo.release() 抛 UninitializedPropertyAccessException, 一并吞掉
            try { exo.release() } catch (_: Exception) {}
            ExoGate.release(gateToken)
            return null
        }
        // 注册活跃表(入口已保证 size<2, 注册后最多 2 个, 无需再 while 限流)
        sBubblePlayers.remove(exo)
        sBubblePlayers.add(exo)
        // 修复多视频气泡画面串扰(下条竖屏画面"穿越"到上条横屏气泡): PlayerView 默认 surface_view
        // 独立合成层, 列表多实例 Z 序错乱互相穿透; 改用 XML 指定 surface_type=texture_view
        // (参与 View 树绘制), resize_mode=zoom 由 PlayerView 内部等比填满裁剪(无黑边, 统一 16:9 卡片)
        val pv = LayoutInflater.from(this).inflate(R.layout.video_bubble_view, null) as PlayerView
        pv.player = exo
        pv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        // 延时释放/统一终结: 创建或 detach 后 10s 未确认上屏即 killSelf, 防 ExoPlayer 实例堆积 OOM
        var releasePending = true   // 创建即武装看门狗(见 apply 尾部): 预取行可能从未上屏就被重绑
        var gateReleased = false   // 全局解码名额是否已让出: detach 立即让出, attach 认领, 防重复 release
        var exoReleased = false   // 超时释放标记: attach 回来时若已释放则重建播放器, 避免对已 release 实例 play 黑屏
        var dead = false   // 当前实例已终结(killSelf 收口过): detach 等后续回调直接跳过, 防双重归还名额
        val releaseHandler = Handler(Looper.getMainLooper())
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                topMargin = dp(6)
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            // 圆角气泡: 背景套圆角 + clipToOutline 裁剪内嵌播放画面, 与图片/缩略图气泡圆角体系一致
            // 视频气泡悬浮模式始终不透明: 打 NO_FLOAT_TAG 让悬浮动画收集背景时跳过本帧
            background = rounded(dp(14), if (isUser) BUBBLE_USER else BUBBLE_AI)
            tag = NO_FLOAT_TAG
            clipToOutline = true
            addView(pv)
            // 点击整块进全屏弹窗预览(弹窗内同样循环播放)
            setOnClickListener { this@MainActivity.openAttachmentPreview(listOf(file), 0) }

            // 视图降级: 播放器终结但视图仍在屏(被弹窗优先级让位踢掉/名额不足)时,
            // 换成缩略图卡片(无缓存则深色占位并触发后台取帧), 杜绝黑屏冻帧
            fun degradeToThumb() {
                val bmp = peekVideoThumb(f)
                if (bmp == null) decodeVideoThumbnailBg(f, resources.displayMetrics.density, this@MainActivity)
                val iv = android.widget.ImageView(this@MainActivity).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    if (bmp != null) setImageBitmap(bmp) else setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                removeAllViews()
                addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            // 统一终结(实例+名额+视图三者一处收口): 看门狗超时/弹窗让位(killOldestBubblePlayer)
            // 都走这里; 此前外部踢人直接 victim.release()+ExoGate.release(), 被踢者自己的
            // detach 监听之后再 release 一次 → 名额双重归还, ExoGate 计数漂移后 MAX=3 形同虚设
            fun killSelf() {
                if (dead) return
                dead = true
                releasePending = false
                releaseHandler.removeCallbacksAndMessages(null)
                sBubbleKillHooks.remove(exo)
                sBubblePlayers.remove(exo)
                try { exo.release() } catch (_: Exception) {}
                // 摘掉已释放实例: 否则离屏未降级的空壳仍持死角播放器, 会被补播复核误判为"已在播"
                try { pv.player = null } catch (_: Exception) {}
                if (!gateReleased) { gateReleased = true; ExoGate.release(gateToken) }
                exoReleased = true
                if (isAttachedToWindow) degradeToThumb()   // 仍在屏: 降级防黑屏; 已离屏则等 attach 重建
            }

            val watchdog = Runnable { if (releasePending) killSelf() }

            // 生命周期: 创建/detach 后 10s 内 attach 回来取消看门狗继续播; 超时统一 killSelf 终结,
            // attach 回来走重建; 堵住"每气泡 new ExoPlayer 不释放"实例堆积 OOM(09-18 23:35 崩溃栈印证)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    // 10s 内回来(含创建后首次上屏): 取消看门狗
                    if (releasePending) {
                        releasePending = false
                        releaseHandler.removeCallbacksAndMessages(null)
                    }
                    if (exoReleased) {
                        // 实例已终结(超时/让位): 统一降级缩略图, 不在此抢名额重建——
                        // 补建统一由对账(reconcileVideoBubbles)按"可见性+配额"驱动, 根治离屏行抢名额打架;
                        // 关键: 绝不能留"空 PlayerView"在屏——对账复核会误判为已在播而永久跳过,
                        // 表现为视频黑屏, 只有切会话/重启才恢复(真机 09-19 02:50 复现)
                        degradeToThumb()
                        return@onViewAttachedToWindow
                    } else {
                        // 回到可视(10s 内): 滚动/翻页 detach 时已立即让出全局解码名额,
                        // 此处重新认领; 认领失败(被其它行/弹窗抢走)则统一终结降级, 等补播
                        if (gateReleased) {
                            if (!ExoGate.tryAcquire(gateToken)) {
                                killSelf()
                                return@onViewAttachedToWindow
                            }
                            gateReleased = false
                        }
                        sBubblePlayers.remove(exo)
                        sBubblePlayers.add(exo)
                        sBubbleKillHooks[exo] = { killSelf() }   // detach 时已摘钩子, 回屏重新挂
                    }
                    // 滚动中保持暂停(松手统一恢复), 静止时直接续播
                    if (!sScrolling) try { exo.play() } catch (_: Exception) {}
                }
                override fun onViewDetachedFromWindow(v: View) {
                    if (dead) return   // 已终结: 名额/实例均已收口
                    try { exo.pause() } catch (_: Exception) {}
                    sBubblePlayers.remove(exo)   // 暂停即让出气泡名额
                    sBubbleKillHooks.remove(exo)   // 钩子只挂"在屏"实例, 防离屏闭包滞留整棵视图
                    // 立即让出全局解码名额(pause 后不再占用解码器), 否则滚动期 detach 的实例
                    // 占着 ExoGate 直到 10s 超时才释放, 松手后新行 tryAcquire 失败 = "概率不播"
                    if (!gateReleased) {
                        gateReleased = true
                        ExoGate.release(gateToken)
                    }
                    releasePending = true
                    releaseHandler.postDelayed(watchdog, 10_000L)
                }
            })
            // 创建即武装看门狗 + 挂终结钩子: 预取(prefetch)绑定的行可能从未上屏就被重绑——
            // onBindViewHolder 的 removeAllViews 对从未 attach 过的视图不触发 onViewDetachedFromWindow,
            // 旧看门狗只挂在 detach 上永远不响 → 实例与名额永久泄漏(越滚越漏, 后续视频全灭)
            releasePending = true
            releaseHandler.postDelayed(watchdog, 10_000L)
            sBubbleKillHooks[exo] = { killSelf() }
        }
        return frame
    }

    /** 用户气泡渲染: [图片](att://file) 直接内嵌缩略图(点击打开原图); 其他附件保持蓝色链接, 其余文本原样 */
    internal fun renderUserContent(content: String): CharSequence {
        val sb = SpannableStringBuilder(content)
        try {
            val re = Regex("\\[(.+?)\\]\\(att://([^)]+)\\)")
            // 同一条消息的全部附件(保持原文顺序), 供点击后 App 内弹窗预览/左右滑动切换
            val allFiles = re.findAll(content).map { it.groupValues[2] }.toList()
            // 倒序遍历: 文件卡片/音频分支会 sb.replace 改变长度, 正序会让后续附件的 start/end(基于原始 content)失效错乱
            val matches = re.findAll(content).toList()
            for (m in matches.asReversed()) {
                val mark = m.groupValues[1]          // 占位标记文本, 如 [音频] / [文件:xxx.pdf]
                val file = m.groupValues[2]
                val start = m.range.first
                val end = m.range.last + 1
                val f = AttachmentStore.fileOf(this, file)
                val mime = if (f != null) AttachmentStore.mimeOf(this, file) else ""
                val bmp = if (f != null && mime.startsWith("image/"))
                    decodeAttachmentBitmap(f, resources.displayMetrics.density) else null
                // 视频: 取首帧缩略图(纯帧, 不带三角), 像图片一样内嵌气泡; 取帧失败回退文件卡片。
                // 后台版: 只查缓存, 未命中入后台线程 MMR 解码+回调刷新——此前主线程同步取帧
                // 20~100ms/个, 滚动绑定路径掉帧(09-19 用户反馈"卡顿依旧"主因之二)
                val vtb = if (f != null && mime.startsWith("video/"))
                    decodeVideoThumbnailBg(f, resources.displayMetrics.density, this) else null
                if (mark.startsWith("表情:") && f != null) {
                    // 表情库项混合场景(表情与文字同一条气泡, 极少): 微信式 96dp 小图内嵌
                    val es = dp(96)
                    if (mime.startsWith("video/") || file.lowercase().endsWith(".mp4")) {
                        val eBmp = peekVideoThumb(f)
                        if (eBmp == null) decodeVideoThumbnailBg(f, resources.displayMetrics.density, this@MainActivity)
                        if (eBmp != null) {
                            val d = BitmapDrawable(resources, roundedBitmap(centerCropBitmap(eBmp, es, es), 0))
                            d.setBounds(0, 0, es, es)
                            sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) { this@MainActivity.openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    } else {
                        val eBmp = try { decodeAttachmentBitmap(f, resources.displayMetrics.density) } catch (e: Exception) { null }
                        if (eBmp != null) {
                            val d = BitmapDrawable(resources, roundedBitmap(centerCropBitmap(eBmp, es, es), 0))
                            d.setBounds(0, 0, es, es)
                            sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) { this@MainActivity.openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                } else if (bmp != null) {
                    // 图片: 圆角化贴合气泡贴边, 替换为缩略图, 同时保留点击打开原图
                    val rb = roundedBitmap(bmp, dp(14))
                    val d = BitmapDrawable(resources, rb)
                    d.setBounds(0, 0, rb.width, rb.height)
                    sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { this@MainActivity.openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (vtb != null) {
                    // 视频: 首帧缩略图(带播放三角) 像图片一样内嵌气泡, 点击进入 App 内视频预览
                    // 统一 16:9 全宽卡片(与 videoLoopBubble 内嵌播放器同尺寸策略): 名额满/大视频降级路径
                    // 若按 200dp 自然尺寸渲染, 竖屏视频会变成窄条小气泡(用户 09-19 反馈), 故 center-crop 到全宽卡片
                    val tw = (if (ModeConfig.chatMode()) chatMaxW() else chatMaxW() - dp(24)).coerceAtLeast(dp(120))
                    val th = (tw * 9 / 16).coerceAtMost(dp(340)).coerceAtLeast(dp(60))
                    // 纯画面卡片, 不叠加播放三角(用户 09-19 反馈"播放按钮能不能去掉"):
                    // 内嵌播放器本就无按钮, 缩略图保持一致观感, 点击气泡进全屏预览
                    val rb = roundedBitmap(centerCropBitmap(vtb, tw, th), dp(14))
                    val d = BitmapDrawable(resources, rb)
                    d.setBounds(0, 0, rb.width, rb.height)
                    sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { this@MainActivity.openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (f != null && mime.startsWith("video/") && isThumbPending(file)) {
                    // 取帧进行中: 先渲染 16:9 深色占位(与缩略图同尺寸同圆角), 避免"文件卡片(矮)→16:9缩略图"
                    // 整行高度突变导致 重启/切会话/积压消息涌入 时整屏反复跳动闪烁(09-19 用户反馈首屏闪烁);
                    // 取帧完成回调重建文本时只换画面不跳高度, 视觉平滑无闪烁
                    val tw = (if (ModeConfig.chatMode()) chatMaxW() else chatMaxW() - dp(24)).coerceAtLeast(dp(120))
                    val th = (tw * 9 / 16).coerceAtMost(dp(340)).coerceAtLeast(dp(60))
                    val ph = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888)
                    ph.eraseColor(0xFF101318.toInt())
                    val rb = roundedBitmap(ph, dp(14))
                    if (rb !== ph) ph.recycle()
                    val d = BitmapDrawable(resources, rb)
                    d.setBounds(0, 0, rb.width, rb.height)
                    sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { this@MainActivity.openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (f != null && mark == "音频") {
                    // 仅本地录音(语音气泡): 播放/暂停按钮 + 时长, 点击切换; 状态由全局 playingFileName 决定
                    val durMs = audioDurationMs(f)
                    val playing = file == playingFileName && audioPlayer?.isPlaying == true
                    val icon = "\uFFFC"
                    val wave = "\uFFFC"
                    val label = "$icon  $wave  ${fmtDuration(durMs)}  "
                    sb.replace(start, end, label)
                    val s2 = start
                    val e2 = start + label.length
                    sb.setSpan(PlayPauseIconSpan(dp(22), playing), s2, s2 + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(WaveSpan(waveState, playing, resources.displayMetrics.density), s2 + 3, s2 + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // 整段点击切换播放/暂停 (由 bubble 的 setOnClickListener 处理, 不用 ClickableSpan)
                } else {
                    // 文件/视频/上传音频: 渲染为文件卡片 = 类型角标 + 文件名, 整段点击打开附件
                    var disp = mark.removePrefix("文件:").removePrefix("视频:")
                        .ifBlank { file }
                    // 超出参考文件名宽度时, 手动中间省略(保留开头与扩展名后缀), 气泡保持自适应
                    val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 15f * resources.displayMetrics.scaledDensity
                    }
                    val nameMaxW = fileCardNameMaxWidth(resources.displayMetrics.scaledDensity)
                    if (fp.measureText(disp) > nameMaxW.toFloat()) {
                        disp = TextUtils.ellipsize(disp, fp, nameMaxW.toFloat(), TextUtils.TruncateAt.MIDDLE).toString()
                    }
                    val badge = badgeOf(mime, file)
                    val replace = "\uFFFC  $disp"
                    sb.replace(start, end, replace)
                    sb.setSpan(BadgeSpan(badge, resources.displayMetrics.density), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { this@MainActivity.openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                            ds.color = BUBBLE_USER_TEXT
                        }
                    }, start, start + replace.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (e: Exception) {
            // 渲染失败退化为纯文本
        }
        return sb
    }

    /** 视频缩略图 center-crop 到目标卡片尺寸(全宽 16:9 语义, ZOOM 等比填满裁掉溢出) */
    private fun centerCropBitmap(src: android.graphics.Bitmap, tw: Int, th: Int): android.graphics.Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val scale = maxOf(tw / sw, th / sh)
        val m = android.graphics.Matrix()
        m.postScale(scale, scale)
        val tmp = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val x = ((tmp.width - tw) / 2f).toInt().coerceAtLeast(0)
        val y = ((tmp.height - th) / 2f).toInt().coerceAtLeast(0)
        val out = android.graphics.Bitmap.createBitmap(tmp, x, y, tw, th)
        if (tmp !== src) tmp.recycle()
        return out
    }

    /** 在中心覆盖绘制固定大小播放三角(统一缩略图降级气泡的按钮观感) */
    private fun overlayPlayButton(bmp: android.graphics.Bitmap, rPx: Int): android.graphics.Bitmap {
        val out = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val cx = out.width / 2f
        val cy = out.height / 2f
        val r = rPx.toFloat().coerceAtLeast(1f)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        c.drawCircle(cx, cy, r, bg)
        val tri = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val s = r * 0.55f
        val path = android.graphics.Path().apply {
            moveTo(cx - s * 0.4f, cy - s)
            lineTo(cx - s * 0.4f, cy + s)
            lineTo(cx + s * 0.9f, cy)
            close()
        }
        c.drawPath(path, tri)
        return out
    }

    /** 文件卡片类型角标文案: 按 mime 与文件名扩展名判定 (已抽离 UiKit.badgeOf) */

    /** 文件卡片文件名省略参考宽度 (已抽离 UiKit.fileCardNameMaxWidth) */
    /** 附件图片采样解码为气泡缩略图 (已抽离 UiKit.decodeAttachmentBitmap) */
    /** 视频首帧缩略图+播放三角 (已抽离 UiKit.decodeVideoThumbnail) */

    /** 将位图裁剪为圆角 (已抽离 UiKit.roundedBitmap) */

    /** 从会话历史恢复的 AI 回复: 思考/工具/正文各自独立气泡, 按 timeline 记录的真实顺序竖向排列 */
    /** AI 头像: 圆形深灰蓝底 + 固定 N(Nyral), 放气泡上方(与 MD 排版解耦) */
    private fun aiAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val custom = AvatarConfig.avatarDrawable(AvatarConfig.aiAvatarFile(), s)
        if (custom != null) {
            background = custom
        } else {
            text = "N"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5A6478"))
            }
        }
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            bottomMargin = dp(4)
        }
        // 点击 AI 头像进入 AI 个性化（原设置页入口已移除）
        setOnClickListener {
            startActivity(Intent(this@MainActivity, PersonalityActivity::class.java))
        }
    }

    /** 用户头像: 圆形蓝底"我", 聊天模式用户消息右侧并排 */
    private fun userAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val custom = AvatarConfig.avatarDrawable(AvatarConfig.userAvatarFile(), s)
        if (custom != null) {
            background = custom
        } else {
            text = getString(R.string.ma_me)
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.PRIMARY)
            }
        }
        layoutParams = LinearLayout.LayoutParams(s, s)
    }

    /** 聊天模式: 气泡与头像并排(AI头像左/用户头像右); Agent 模式原样返回 */
    internal fun chatWrap(content: View, isUser: Boolean): View {
        if (!ModeConfig.chatMode()) return content
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 禁用 baseline 对齐: 气泡与头像均为 TextView, 默认会按文字基线对齐,
            // 导致无文本头像被下推, 短气泡时头像底部超出行边界被裁剪(下边缺角)
            isBaselineAligned = false
            // 头像固定顶部(TOP): 单行气泡与头像等高近似居中; 两行/长文气泡从顶部向下延伸, 头像停在一行时的位置
            gravity = if (isUser) (Gravity.END or Gravity.TOP) else (Gravity.START or Gravity.TOP)
            val lp = content.layoutParams as? LinearLayout.LayoutParams
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = lp?.topMargin ?: dp(6)
                bottomMargin = lp?.bottomMargin ?: 0
            }
        }
        val lp = content.layoutParams as? LinearLayout.LayoutParams
        val cw = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        // 保留内容的显式高度(如 videoLoopBubble 算出的视频框高 568px):
        // 若改成 WRAP_CONTENT, PlayerView(MATCH_PARENT) 在 WRAP_CONTENT 下无固有高度 → 塌缩成 1px, 气泡整体不可见
        val ch = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (isUser) {
            row.addView(content, LinearLayout.LayoutParams(cw, ch).apply {
                rightMargin = dp(8)
            })
            row.addView(userAvatar(), LinearLayout.LayoutParams(dp(40), dp(40)))
        } else {
            row.addView(aiAvatar(), LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                rightMargin = dp(8)
            })
            row.addView(content, LinearLayout.LayoutParams(cw, ch))
        }
        return row
    }

    private fun aiBubbleWithThinking(thinking: String, content: String, toolsJson: String = "", timelineJson: String = "", rendered: String = "", writeback: ((Spanned) -> Unit)? = null): LinearLayout {
        val container = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            // Agent 模式不要头像(仅聊天模式并排头像); 聊天模式头像由 chatWrap 负责
            // 独立气泡容器: 不包裹大气泡背景, 思考/工具/正文各自成气泡, 与流式 attach() 一致
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.START
                topMargin = dp(6)
            }
        }
        fillAiRich(container, thinking, content, toolsJson, timelineJson, rendered, writeback)
        return container
    }

    /** AiRich 行池化绑定（09-28 第二波）：复用外层容器 View 树，先回收旧子树再原地填充 */
    private fun bindAiRichPooled(container: LinearLayout, row: ChatRow.AiRich) {
        recycleAiRichChildren(container)
        fillAiRich(container, row.thinking, row.content, row.tools, row.timeline, row.rendered) { spanned -> writeBackRendered(row.id, spanned) }
    }

    /** 回收 AiRich 容器子树：chatWrap 容器/分片 TextView/AI 头像各自入池，保证下次 bind 干净复用 */
    private fun recycleAiRichChildren(container: LinearLayout) {
        while (container.childCount > 0) {
            val child = container.getChildAt(0)
            container.removeViewAt(0)
            if (child is LinearLayout && child.orientation == LinearLayout.HORIZONTAL) {
                // chatWrap(AI 侧): [头像, content]; content 可能是分片 TextView 或状态行 col
                for (i in 0 until child.childCount) {
                    val c = child.getChildAt(i)
                    if (i == 0 && c is TextView) {
                        aiAvatarPool.add(c)
                    } else if (c is TextView) {
                        c.text = ""   // 清占位, 防 setMarkdownCached 命中校验误判
                        c.minimumHeight = 0   // 09-28: 清等高占位固定高度残留, 防池化行复用高度异常
                        c.setTag(KEY_RENDER_MD, null)   // 09-28 第五波: 清幂等 tag——text 已清空而 tag 残留时,
                        // 同内容行滚回复用会命中 bind 幂等(tag==md)跳过渲染, 上屏空白气泡(只剩思考气泡)
                        c.setOnLongClickListener(null)
                        aiRichSegPool.add(c)
                    } else if (c is LinearLayout) {
                        c.removeAllViews()   // 状态行 col: 整体丢弃(行数少, 不池化)
                    }
                }
                child.removeAllViews()
                aiRichWrapPool.add(child)
            } else if (child is LinearLayout) {
                child.removeAllViews()
            }
        }
    }

    /** 轻量预判 Markdown 表格语法(竖线表): 首行含 |, 次行为分隔行(|---|) */
    private fun containsTableSyntax(md: String): Boolean {
        val lines = md.split('\n')
        if (lines.size < 2) return false
        for (i in 0 until lines.size - 1) {
            val l0 = lines[i].trim()
            if (l0.startsWith("|") && l0.count { it == '|' } >= 2) {
                val l1 = lines[i + 1].trim()
                if (l1.startsWith("|") && l1.count { it == '|' } >= 2 &&
                    l1.filter { it != '|' && it != '-' && it != ':' && it != ' ' }.isEmpty()) return true
            }
        }
        return false
    }

    /** AiRich 容器原地填充（原 aiBubbleWithThinking apply 体）：分片 TextView/AI 头像/chatWrap 从池取用 */
    private fun fillAiRich(container: LinearLayout, thinking: String, content: String, toolsJson: String, timelineJson: String, rendered: String = "", writeback: ((Spanned) -> Unit)? = null) {
        // 正文独立气泡(浅色背景, 不折叠), 与流式 appendContent 一致; 阶段2 分片: 超长正文按段落切多段渲染
        // 方案B(2026-10-01): 含表格的消息走块化(独立 MdTableView), 不再走 markwon span 表格
        /** 历史正文 TextView 池化获取(含池复用清理) */
        fun obtainSegTv(): TextView = aiRichSegPool.removeLastOrNull() ?: TextView(this@MainActivity).apply {
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            includeFontPadding = false
            setTextColor(BUBBLE_AI_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxWidth = chatMaxW()
            background = rounded(dp(12), floatBubbleColor(BUBBLE_AI))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        }
        fun obtainSeg(seg: String, segRendered: String = "", segWriteback: ((Spanned) -> Unit)? = null): View? {
            val renderContent = ModeConfig.stripChatProtocolPrefix(seg)
            if (renderContent.isBlank()) return null   // 空气泡兜底(09-25): 纯协议前缀段不建视图
            if (ModeConfig.chatPlainText()) {
                val tv = obtainSegTv()
                tv.text = ModeConfig.stripMarkdownForChat(renderContent).trimEnd()
                makeCopyable(tv) { seg }
                return tv
            }
            if (!renderContent.contains("att://")) {
                // 方案B 块化历史恢复: 表格独立 TableView, 边框/底色与流式块化一致
                val blocks = MdToBlocks.render(renderContent)
                if (blocks.isEmpty()) return null
                val bv = MdBlocksView(this@MainActivity).apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(dp(12), floatBubbleColor(BUBBLE_AI))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(4)
                    }
                    // 长按复制(2026-10-02): 与流式块化一致, 长按弹原文本对话框
                    setOnLongClickListener {
                        if (activeAiHolder?.hasActiveTypewriter() == true) return@setOnLongClickListener false
                        openRawText(seg)
                        true
                    }
                    isLongClickable = true
                }
                bv.setRenderContext(chatMaxW(), chatMaxW() - dp(24), BUBBLE_AI_TEXT, Ui.PRIMARY, Ui.INPUT_BG)
                bv.bindBlocks(blocks)
                return bv
            }
            val tv = obtainSegTv()
            // 先设基础字号再渲染 Markdown, 保证 HeadingSpan 的倍率基于正确 textSize 生效
            setMarkdownCached(tv, renderContent, segRendered, segWriteback)
            makeCopyable(tv) { seg }
            return tv
        }
        fun obtainChatWrap(content: View): View {
            if (!ModeConfig.chatMode()) return content
            val row = aiRichWrapPool.removeLastOrNull() ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                // 禁用 baseline 对齐: 气泡与头像均为 TextView, 默认会按文字基线对齐,
                // 导致无文本头像被下推, 短气泡时头像底部超出行边界被裁剪(下边缺角)
                isBaselineAligned = false
                gravity = Gravity.START or Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.START
                    topMargin = dp(6)
                    bottomMargin = 0
                }
            }
            row.removeAllViews()
            val avatar = aiAvatarPool.removeLastOrNull() ?: aiAvatar()
            row.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                rightMargin = dp(8)
            })
            row.addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return row
        }
        val contentSegs = splitLongContent(content)
        // 09-24 单行状态行重构: 思考/工具不再逐条独立气泡, 汇总为单行摘要
        // ("💭 已思考X字 · 🔧 N个工具", 与流式收尾定格同形态), 点击原地展开脉络时间线(竖线+圆点)
        val events = statusEventsOf(timelineJson, thinking, toolsJson)
        if (events.isNotEmpty()) {
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val summary = makeStatusShell(this@MainActivity).apply {
                val tv = TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(THINK_TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = statusSummaryText(
                        events.filter { it.type == "think" }
                            .sumOf { it.text.codePointCount(0, it.text.length) },
                        events.count { it.type == "tool" })
                }
                addView(tv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener {
                    toggleStatusDrop(this, events)   // 09-25 窗帘式下拉面板(覆盖式, 不挤 RV 布局流)
                }
            }
            col.addView(summary)
            container.addView(obtainChatWrap(col))
        }
        // 正文独立气泡: 按 timeline content 事件精确还原交错顺序(新格式), 旧格式回退全部分片
        val timeline = parseTimeline(timelineJson)
        // 阶段1: rendered 直出/写回仅适用"单段渲染"(timeline content 事件<=1 条 且 content 未分段);
        // 多段时整条 rendered 的 span 区间与单段文本不匹配, 强制走现场渲染(与现状一致, 防纯文本降级)
        val contentEvts = timeline.count { it[0] == "content" }
        val singleSeg = contentEvts <= 1 && contentSegs.size <= 1

        val segRendered = if (singleSeg) rendered else ""
        val segWriteback = if (singleSeg) writeback else null
        var contentPlaced = false
        if (timeline.isNotEmpty()) {
            timeline.forEach { (type, thinkText, name, arg, result) ->
                if (type == "content" && thinkText.isNotBlank()) {
                    obtainSeg(thinkText, segRendered, segWriteback)?.let { container.addView(obtainChatWrap(it)); contentPlaced = true }
                }
            }
            if (!contentPlaced && content.isNotBlank()) {
                contentSegs.forEach { obtainSeg(it, segRendered, segWriteback)?.let { r -> container.addView(obtainChatWrap(r)) } }
            }
        } else if (content.isNotBlank()) {
            contentSegs.forEach { obtainSeg(it, segRendered, segWriteback)?.let { r -> container.addView(obtainChatWrap(r)) } }
        }
    }

    /** 滑动停止(IDLE)后分批 flush 挂起的 markdown 替换（09-28 第二波）：
     *  原实现一帧内集中执行全部替换, 长历史一次上翻可能积压几十条 = 停止瞬间集中布局卡顿;
     *  改为每帧最多 FLUSH_BATCH 条, 余量 post 下一帧续跑, 停止后的布局成本摊平 */
    // 兜底(09-28 第四波): pending 替换的 flush 依赖 onScrollStateChanged IDLE, 冷启动/
    // 无滚动场景 IDLE 事件缺失 -> 挂起的替换永不执行 -> 气泡停留占位(矮) = 用户所报
    // "过一会儿气泡缩短"; 编译完成 1.5s 后仍无 IDLE 则主动尝试 flush(去抖单次调度)
    private var pendingFlushScheduled = false
    private fun schedulePendingFlush() {
        if (pendingFlushScheduled) return
        pendingFlushScheduled = true
        chatRec.postDelayed({
            pendingFlushScheduled = false
            if (!sScrolling && pendingMdReplacements.isNotEmpty()) {
                Log.d("DRIFTDBG", "flush-pending timeout n=" + pendingMdReplacements.size)
                flushPendingMdReplacements()
            }
        }, 1500)
    }

    private fun flushPendingMdReplacements() {
        if (pendingMdReplacements.isEmpty() || sScrolling) return
        val n = minOf(FLUSH_BATCH, pendingMdReplacements.size)
        val pend = ArrayList<Runnable>(n)
        repeat(n) { pend.add(pendingMdReplacements.removeAt(0)) }
        for (r in pend) r.run()
        if (pendingMdReplacements.isNotEmpty()) {
            chatRec.post { flushPendingMdReplacements() }
        }
    }

    /** 历史消息 -> 脉络时间线事件(think/tool), 供单行状态行摘要与展开渲染(09-24 新形态);
     *  优先 timeline 交错还原, 旧数据(无 timeline)回退 thinking/tools 扁平还原 */
    private fun statusEventsOf(timelineJson: String, thinking: String, toolsJson: String): List<StatusEvent> {
        val out = ArrayList<StatusEvent>()
        parseTimeline(timelineJson).forEach { (type, text, name, arg, result) ->
            when (type) {
                "think" -> if (text.isNotBlank()) out.add(StatusEvent("think", text, "", "", ""))
                "tool" -> out.add(StatusEvent("tool", "", name, arg, result))
            }
        }
        if (out.isEmpty()) {
            if (thinking.isNotBlank()) out.add(StatusEvent("think", thinking, "", "", ""))
            parseTools(toolsJson).forEach { out.add(StatusEvent("tool", "", it.first, it.second, it.third)) }
        }
        return out
    }

    /** 长正文分片(阶段2 content 分片): 优先按段落边界切块, 每块不超过 SPLIT_CONTENT_LEN;
     *  单段落超长(无空行长文/大段代码)按字符硬切, 与流式 appendContent 自动封段阈值一致 */
    private fun splitLongContent(raw: String): List<String> {
        if (raw.length <= SPLIT_CONTENT_LEN) return listOf(raw)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (para in raw.split("\n\n")) {
            if (sb.length + para.length + 2 > SPLIT_CONTENT_LEN && sb.isNotEmpty()) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
            if (para.length > SPLIT_CONTENT_LEN) {
                // 单段落超长: 先清空缓冲, 再按字符硬切
                if (sb.isNotEmpty()) { out.add(sb.toString().trim()); sb.setLength(0) }
                var rest = para
                while (rest.length > SPLIT_CONTENT_LEN) {
                    out.add(rest.take(SPLIT_CONTENT_LEN))
                    rest = rest.drop(SPLIT_CONTENT_LEN)
                }
                sb.append(rest)
            } else {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(para)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out
    }

    /** 解析持久化的工具序列 JSON -> (name, arg, result) 列表 */
    private fun parseTools(json: String): List<Triple<String, String, String>> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(
                    o.optString("name", ""),
                    o.optString("arg", ""),
                    o.optString("result", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 解析交错时间线 JSON -> (type, text, arg, result) 列表; think 用 text, tool 用 name/arg/result */
    private fun parseTimeline(json: String): List<Array<String>> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type", "")
                arrayOf(
                    type,
                    o.optString("text", ""),
                    o.optString("name", ""),
                    o.optString("arg", ""),
                    o.optString("result", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 键盘弹起贴底跟随(09-24): 静态(非 AI 输出)时键盘顶起压缩视口, 列表同帧上滚让
     *  最新消息底部始终贴住输入框顶; 强制拉回底(翻阅历史中途点输入框也追底——复制粘贴
     *  发送场景必须能看到最新消息)。AI 输出中(aiBusy)完全不响应, 输出结束键盘还开着也
     *  不补抬(保正文阅读位)。post 到下一帧按压缩后真实视口算 gap(同表情抽屉 alignChatToViewport) */
    private fun imeLiftToBottom(force: Boolean = false) {
        if (aiBusy) return
        if (chatAdapter.itemCount == 0) return
        chatRec.post {
            val lmA = chatRec.layoutManager as? LinearLayoutManager ?: return@post
            val n = chatAdapter.itemCount
            if (n == 0) return@post
            val lastChild = lmA.findViewByPosition(n - 1)
            if (lastChild != null) {
                val gap = lastChild.bottom - (chatRec.height - chatRec.paddingBottom)
                // 默认仅内容溢出视口底时下滚贴底; force(输出完成重建后)则无论正负都滚到贴底,
                // 消除"重建后视口留白未滚到底"造成的空隙(09-25)
                if (gap > 0 || (force && gap != 0)) chatRec.scrollBy(0, gap)
            } else {
                lmA.scrollToPosition(n - 1)   // 翻阅历史中: 强制拉回最新
                LogStore.i(LogStore.MAIN, "imeLift jump-to-bottom n=$n")
            }
        }
    }

    // ===== 打字机帧合并锚底(2026-09-23, 对齐 assistant/Kuikly 同帧 diff) =====
    // 打字期间内容每帧增长(stepBlock setText), 本帧 preDraw(布局完成后、绘制前)里
    // scrollBy 补偿该帧增长量 -> 视口锚底, 视觉上内容在底部原地生长、无追滚滞后;
    // 用户上翻阅读(scrollUserScrolled)或打字结束自动停。
    private var typeAnchorActive = false
    private val typeAnchorPreDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (!typeAnchorActive) {
                chatRec.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
            val holder = activeAiHolder
            val typing = holder?.hasActiveTypewriter() == true
            val status = holder?.hasStatusRow() == true
            // 用户接管提前判停(09-24): 上翻阅读(scrollUserScrolled)直接让位, 不再先补偿后停(向上展开)
            if (scrollUserScrolled) {
                typeAnchorActive = false
                chatRec.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
            // 输出完成已置空(09-25): 直接停, 不做中间态补偿, 由 onDone 重建后 imeLiftToBottom 统一贴底
            if (activeAiHolder == null) {
                typeAnchorActive = false
                chatRec.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
            // 短正文追底修复(09-24): 先补偿本帧增长/收尾排版高度变化, 再判停——
            // 收尾帧 stepBlock 在 preDraw 前已 finishTypeRender(typing=false), 旧顺序
            // 直接停导致短正文最后一次增长(常为全部增长)被丢弃, 视口差一截不追底
            val lmA = chatRec.layoutManager as? LinearLayoutManager
            if (lmA != null) {
                val n = chatAdapter.itemCount
                val lastChild = lmA.findViewByPosition(n - 1)
                val viewBottom = chatRec.height - chatRec.paddingBottom
                val offset0 = chatRec.computeVerticalScrollOffset()
                if (lastChild != null) {
                    val gap = lastChild.bottom - viewBottom
                    if (gap > 0) {
                        chatRec.scrollBy(0, gap)   // 本帧直接补偿, 与内容增长同帧
                    }
                } else {
                    val range = chatRec.computeVerticalScrollRange()
                    val extent = chatRec.computeVerticalScrollExtent()
                    val gap2 = (range - extent) - offset0
                    if (gap2 > 0) {
                        chatRec.scrollBy(0, gap2)
                    }
                }
            }
            // 无任何增长源(打字结束且状态行已移除/定格)才停; 思考/工具阶段状态行在屏则继续锚底(09-24)
            if (holder == null || (!typing && !status) || chatAdapter.itemCount == 0) {
                typeAnchorActive = false
                chatRec.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
            return true
        }
    }

    /** 打字机每帧内容增长回调(AiBubbleHolder.tickFrame -> 本方法): 启用同帧锚底;
     *  finished=true 表示本帧刚收尾排版(短正文推完即收尾), typing 已结束也放行注册一次,
     *  由 preDraw 先补偿后停兜住最后一次增长 */
    internal fun onTypeGrownFrame(finished: Boolean = false) {
        // 输出完成已置空(09-25): 收尾帧不再注册锚底, 中间态补偿交给 onDone 重建后统一贴底
        if (activeAiHolder == null) return
        if (typeAnchorActive || scrollUserScrolled || chatAdapter.itemCount == 0) return
        // 09-24 追底回填: 删"正文默认停滚"拦截(思考自动滚动已退役, 无闪烁源), 正文打字恢复锚底;
        // 用户上滑守卫(scrollUserScrolled)仍在前一行生效, 上翻阅读即停不互相打扰
        if (activeAiHolder?.hasActiveTypewriter() != true && !finished) return
        typeAnchorActive = true
        chatRec.viewTreeObserver.addOnPreDrawListener(typeAnchorPreDraw)
    }

    /** 思考/工具状态行增长回调(AiBubbleHolder 状态行更新 -> 本方法): 启用同帧锚底,
     *  与打字锚底同机制; 思考/工具阶段无打字机驱动, 单独注册入口(09-24) */
    internal fun onStatusGrown() {
        if (typeAnchorActive || scrollUserScrolled || chatAdapter.itemCount == 0) return
        if (activeAiHolder?.hasStatusRow() != true) return
        typeAnchorActive = true
        chatRec.viewTreeObserver.addOnPreDrawListener(typeAnchorPreDraw)
    }

    /** 用户接管滚动(09-24): 点击状态行展开/收起思考/工具时间线时, 点击落在可点击子 view 上,
     *  RV 的 onTouch(ACTION_DOWN 置 scrollUserScrolled)不触发, 自动滚动源畅通会把视口拉底;
     *  展开/收起点击视同用户接管, 置标记掐断一切自动拉底(贴底或下轮发送时自动复位) */
    internal fun markUserTakeover() {
        scrollUserScrolled = true
    }

    /** 流式状态行展开/收起后的滚动补偿(09-24): 状态行 col 高度变化引起 RV 布局漂移,
     *  用 getLocationInWindow 实测展开前后 col 顶部窗口位移差, 反向 scrollBy 钉住原位 */
    internal fun compensateStatusToggle(col: View, colH0: Int, colBaseY: Int = -1) {
        chatRec.post {
            val loc = IntArray(2)
            col.getLocationInWindow(loc)
            val delta = if (colBaseY >= 0) loc[1] - colBaseY else col.height - colH0
            if (delta != 0) chatRec.scrollBy(0, delta)
        }
    }

    // ==== 历史状态行窗帘式下拉面板(09-25): 覆盖式挂 chatArea, 不参与 RV 布局流, 杜绝锚点漂移 ====
    private var statusDropPanel: ScrollView? = null    // 当前展开的历史状态行下拉面板(带滚动)
    private var statusDropAnchor: View? = null          // 触发锚点(summary), 同一锚点再点=收起
    private var statusDropAnim: ValueAnimator? = null   // 展开/收起动画
    private var statusDropToken = 0   // 动画代际(09-25 连点竞态修复): 每次新操作递增, 旧动画回调 token 不匹配一律忽略

    internal fun toggleStatusDrop(summary: View, events: List<StatusEvent>) {
        markUserTakeover()   // 展开/收起视同用户接管, 防自动滚动追底(09-24)
        if (statusDropPanel != null && statusDropAnchor === summary) {
            dismissStatusDrop(true)
            return
        }
        // 已有面板先移除(换锚点)
        dismissStatusDrop(false)
        val token = ++statusDropToken   // 本代展开动画标识: 取代一切旧动画回调
        val host = this
        val tl = buildStatusTimeline(host, events)
        val panel = ScrollView(host).apply {
            isFillViewport = true   // 内容宽铺满面板
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(dp(12), floatBubbleColor(THINK_BG))
            elevation = dp(8).toFloat()
            isClickable = true   // 拦截点击不穿透到底层
        }
        panel.addView(tl, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 定位: summary 窗口坐标 -> chatArea 内坐标(锚点正下方, 窗帘向下垂)
        val sLoc = IntArray(2); summary.getLocationInWindow(sLoc)
        val cLoc = IntArray(2); chatArea.getLocationInWindow(cLoc)
        val left = sLoc[0] - cLoc[0]
        val gap = dp(8)   // 面板与气泡间距(09-27 用户反馈: 顶部边框贴太近)
        val top = sLoc[1] - cLoc[1] + summary.height + gap
        // 目标高度: 内容全高 clamp 到聊天区可视下边界(dp(12) 留白), 超高时面板内 ScrollView 可滚动
        val wMax = summary.width.coerceAtLeast(dp(120)).coerceAtMost(chatMaxW())
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(wMax, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val contentH = panel.measuredHeight.coerceAtLeast(1)
        val availBottom = cLoc[1] + chatArea.height - dp(12)
        val maxH = (availBottom - (sLoc[1] + summary.height + gap)).coerceAtLeast(dp(80))
        val targetH = contentH.coerceAtMost(maxH)
        val lp = FrameLayout.LayoutParams(wMax, 0).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        }
        chatArea.addView(panel, lp)
        statusDropPanel = panel
        statusDropAnchor = summary
        val dur = (220L * TypewriterCenter.slowMul()).toLong().coerceAtLeast(1L)
        statusDropAnim = ValueAnimator.ofInt(0, targetH).apply {
            duration = dur
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                if (token != statusDropToken) { va.cancel(); return@addUpdateListener }   // 旧代帧: 自停, 不碰新状态
                lp.height = va.animatedValue as Int
                panel.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (token != statusDropToken) return   // 已被新代取代: 状态归新代管
                    lp.height = targetH   // 保持 clamp 后高度, 防 ScrollView wrap 回内容全高超屏
                    statusDropAnim = null
                }
            })
            start()
        }
    }

    internal fun dismissStatusDrop(animate: Boolean) {
        val panel = statusDropPanel ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val token = ++statusDropToken   // 本代操作: 旧动画回调(token 不匹配)全部失效, 防连点竞态
        val startH = lp.height
        statusDropAnim?.cancel()
        statusDropAnim = null
        if (!animate || startH <= 0) {
            removeDropPanel(panel)
            return
        }
        val dur = (180L * TypewriterCenter.slowMul()).toLong().coerceAtLeast(1L)
        statusDropAnim = ValueAnimator.ofInt(startH, 0).apply {
            duration = dur
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                if (token != statusDropToken) { va.cancel(); return@addUpdateListener }   // 旧代帧: 自停
                lp.height = va.animatedValue as Int
                panel.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (token != statusDropToken) return   // 已被新代取代: 不碰状态
                    removeDropPanel(panel)
                }
            })
            start()
        }
    }

    /** 幂等移除下拉面板(09-25 连点竞态修复): 仅当仍在 chatArea 才移除, 同步清状态引用 */
    private fun removeDropPanel(panel: View) {
        if (panel.parent === chatArea) chatArea.removeView(panel)
        if (statusDropPanel === panel) {
            statusDropPanel = null
            statusDropAnchor = null
        }
        statusDropAnim = null
    }

    /** 状态行展开/收起锚点位移补偿(09-24): RV 布局锚点行为(贴底时保持 item 底部)会把
     *  状态行顶出视口(向上展开); 用 getLocationInWindow 测展开前后状态行顶部屏幕位移差,
     *  反向 scrollBy 补偿, 状态行原位不动、时间线原地向下展开。中间阅读态位移≈0 自动不滚 */
    internal fun scrollToBottom(auto: Boolean = false, force: Boolean = false) {
        Log.d("SCROLLDBG", "scrollToBottom auto=" + auto + " force=" + force + " itemCount=" + chatAdapter.itemCount + " attached=" + chatRec.isAttachedToWindow + " h=" + chatRec.height + " uScroll=" + scrollUserScrolled + " lastVis=" + ((chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.findLastVisibleItemPosition()))
        if (auto && scrollUserScrolled) {
            return
        }
        if (chatAdapter.itemCount == 0) return
        // 流式追底去抖: 高频 delta 合并, 避免滚动请求堆积与反复重定位
        // force=true 用于收尾/静默渲染等低频关键帧: 文本变高后必须重新对齐, 不能被去抖吞掉
        if (auto && !force) {
            val now = System.currentTimeMillis()
            if (now - lastAutoScrollTs < 25) return
            lastAutoScrollTs = now
        }
        // 同步设 pending 位置(抢在会话打开首个数据布局帧之前生效, 消除"先顶后底"闪跳);
        // 先杀惯性: 滑动途中切会话时 fling 会持续覆盖新锚点导致"停在中间"
        val lmX = chatRec.layoutManager as? LinearLayoutManager
        val lastVisX = lmX?.findLastVisibleItemPosition() ?: -1
        chatRec.stopScroll()
        // 用 != 精确匹配: 切会话时 lastVis 是旧列表布局残留(如旧 72 行的 65), 若用 < 比较会误判
        // "已在新末条"而跳过重定位, RV 锚点错乱先渲染中间位置再被 align 拉底 = 打开长消息会话闪一次
        if (lastVisX != chatAdapter.itemCount - 1) {
            chatRec.scrollToPosition(chatAdapter.itemCount - 1)
        }
        // 修复(2026-09-18): 原先整段包在 chatRec.post{} 里, scrollToPosition 的
        // 顶锚布局帧会先渲染一帧, preDraw 注册晚一帧才补拉底 = 长气泡两帧位置天差地别(顶锚→底部
        // 大跳); 短气泡 gap≈0 无感, 这正是"特定长度才触发"的原因。现在同步注册 preDraw:
        // 本帧 layout(顶锚)完成后、绘制前同帧执行 align 拉底, 单帧直达底部无中间态上屏
        if (chatAdapter.itemCount == 0) return
        val n = chatAdapter.itemCount
        // 绝对底部对齐: 末条长文高度>视口时 scrollToPosition 只能让其可见无法贴底,
        // 布局完成后按差值一次拉齐; 用末条子 View 真实像素差而非 range 估算
        // (变高消息下 computeVerticalScrollRange 基于平均行高, 估算严重失真会拉错位置)
        val align = Runnable {
            if (auto && scrollUserScrolled) return@Runnable
            if (chatAdapter.itemCount != n) return@Runnable
            pendingAlign--
            val lmA = chatRec.layoutManager as? LinearLayoutManager
            val lastChild = lmA?.findViewByPosition(n - 1)
                ?: lmA?.getChildAt((lmA?.childCount ?: 1) - 1)
            val gap = if (lastChild != null) {
                lastChild.bottom - (chatRec.height - chatRec.paddingBottom)
            } else {
                val target = chatRec.computeVerticalScrollRange() - chatRec.computeVerticalScrollExtent()
                target - chatRec.computeVerticalScrollOffset()
            }
            if (gap != 0) {
                Log.d("SCROLLDBG", "align gap=" + gap + " n=" + n + " lastChild=" + (lastChild != null) + " h=" + chatRec.height + " lastVis=" + (lmA?.findLastVisibleItemPosition()))
                chatRec.scrollBy(0, gap)
            } else {
                Log.d("SCROLLDBG", "align gap=0 n=" + n + " lastChild=" + (lastChild != null) + " h=" + chatRec.height + " lastVis=" + (lmA?.findLastVisibleItemPosition()))
            }
        }
        pendingAlign++
        val vto = chatRec.viewTreeObserver
        val preDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                chatRec.viewTreeObserver.removeOnPreDrawListener(this)
                if (pendingAlign > 0) align.run()
                return true
            }
        }
        vto.addOnPreDrawListener(preDraw)
        // 兜底: preDraw 未触发时 200ms 后对齐一次(并清掉监听)
        chatRec.postDelayed({
            chatRec.viewTreeObserver.removeOnPreDrawListener(preDraw)
            if (pendingAlign > 0) align.run()
        }, 200)
    }

    /** AI 是否正在打字输出: 供块容器长按复制守卫(与 makeCopyable 打字中不弹窗一致, 2026-10-02) */
    internal fun aiTypewriting(): Boolean = activeAiHolder?.hasActiveTypewriter() == true

    /** 长按进入"原文本模式": 弹窗展示该条消息的原始文本, 在该模式下自由选择/复制全文或片段;
     *  AI 输出(打字机活跃)期间长按不弹窗(09-25): 打字中长按多为"想按住暂停/滑动阅读",
     *  复制窗抢占触摸会打断滚动让位; 静态(输出完成)后再放行长按复制 */
    internal fun makeCopyable(tv: TextView, textProvider: () -> String = { tv.text.toString() }) {
        // 拦截长按, 进入原文本模式(不启用系统文本选择, 避免两套交互冲突)
        tv.setOnLongClickListener {
            if (activeAiHolder?.hasActiveTypewriter() == true) return@setOnLongClickListener false
            openRawText(textProvider())
            true
        }
    }

    /** "原文本模式"对话框: 只读展示原始文本, 支持长按/手柄自由选择复制片段 */
    internal fun openRawText(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Ui.TEXT)
            setTextIsSelectable(true)
            setHighlightColor(0x6633B5E5)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            typeface = Typeface.MONOSPACE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Ui.INPUT_BG)
        }
        val scroll = ScrollView(this).apply {
            background = bg
            addView(tv)
        }
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(scroll)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.7f).toInt())
        }
        d.show()
    }

    /** 切换语音模式: 胶囊 折叠(仅麦克风图标,无背景) <-> 向左果冻展开("按住 说话"胶囊+键盘图标); 录音中禁止切换; 不支持语音禁止进入 */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_mic_granted, Toast.LENGTH_SHORT).show()
            return
        }
        if (requestCode == REQ_GUIDE_PERMS) {
            // 首启引导: 运行时权限结果已回, 继续特殊权限设置页(用户拒了的也不再强制)
            firstRunGuideState = 0
            startNextSpecialPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        uiScope.cancel()
        // 优化(2026-09-18 全面扫描): 单线程 executor 是 Activity 成员, 销毁后任务继续跑会持有 Activity 引用; 立即关停
        executor.shutdownNow()
        if (::browserPage.isInitialized) browserPage.destroy()
        // 调试服务随 Activity 销毁关闭, 并清引用避免泄漏
        DebugServer.stop()
        DebugService.stop(this)
        DebugServer.detach(this)
        audioPlayer?.release()
        audioPlayer = null
        if (audioRecord != null) {
            recStop = true
            try { audioRecord?.stop() } catch (e: Exception) {}
            try { audioRecord?.release() } catch (e: Exception) {}
            audioRecord = null
        }
        recPcm = null
        recThread = null
    }

    /** 音频文件时长(ms) 与时长格式化已抽离 UiKit.audioDurationMs / fmtDuration */


    internal fun pickImage() {
        startPick(Intent.ACTION_GET_CONTENT, "image/*", REQ_IMAGE, "选择图片")
    }

    internal fun pickVideo() {
        startPick(Intent.ACTION_GET_CONTENT, "video/*", REQ_VIDEO, "选择视频")
    }

    internal fun pickFile() {
        startPick(Intent.ACTION_GET_CONTENT, "*/*", REQ_FILE, "选择文件")
    }

    internal fun pickAudio() {
        startPick(Intent.ACTION_GET_CONTENT, "audio/*", REQ_AUDIO, "选择音频")
    }

    /** 表情库项发送转发: attachmentSender 为 private, 扩展函数经此入口调用 */
    internal fun sendEmojiLibItemFile(file: File, name: String) {
        attachmentSender.sendEmojiLibItem(file, name)
    }

    /** 表情库项直发转发: 不经预览条, 立即发出 */
    internal fun sendEmojiLibItemNowFile(file: File, name: String) {
        // 发送即预热首帧缩略图(幂等): 新消息 bind 时滚动中降级/attach 垫底均命中真图, 消除"发送闪黑"
        try { decodeVideoThumbnailBg(file, resources.displayMetrics.density, this) } catch (_: Exception) {}
        attachmentSender.sendEmojiLibItemNow(file, name)
    }

    internal fun startPick(action: String, type: String, code: Int, title: String) {
        val i = Intent(action).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(Intent.createChooser(i, title), code)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_picker_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        // 支持一次多选, 但每次携带上限 6 个: 超出部分丢弃并提示
        // 去重: 部分选择器单选时 data 与 clipData 同时携带同一 URI, 直接收集会重复上传同一文件
        val uris = mutableListOf<Uri>()
        val seen = HashSet<String>()
        data.data?.let { if (seen.add(it.toString())) uris.add(it) }
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) {
                val u = cd.getItemAt(i).uri
                if (seen.add(u.toString())) uris.add(u)
            }
        }
        if (uris.isEmpty()) return
        // 表情库选图分流: 压缩落库 + 强制命名, 不走聊天附件链路
        if (requestCode == REQ_EMOJI_PICK) {
            handleEmojiLibPick(uris)
            return
        }
        val MAX = 6
        if (uris.size > MAX) {
            Toast.makeText(this, getString(R.string.toast_att_max_trim, MAX, MAX), Toast.LENGTH_SHORT).show()
        }
        uris.take(MAX).forEach { attachmentSender.sendAttachmentFromUri(it) }
    }

    /** 抽屉开合统一动画: 面板位移 + 遮罩深度 + 主界面下沉三路同帧驱动(点击开合/跟手吸附/手势取消共用) */
    internal fun animateDrawer(open: Boolean, dur: Long) {
        drawerAnimator?.cancel()
        drawerOpen = open
        val from = mainSinkP
        val to = if (open) 1f else 0f
        if (open) drawerMask.visibility = View.VISIBLE
        var cancelled = false
        drawerAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            interpolator = android.view.animation.DecelerateInterpolator(1.3f)
            addUpdateListener {
                val p = it.animatedValue as Float
                drawerPanel.translationX = -DRAWER_WIDTH.toFloat() * (1f - p)
                drawerMask.alpha = p
                setMainSink(p)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (open) drawerMask.alpha = 1f
                    else {
                        drawerMask.alpha = 0f
                        drawerMask.visibility = View.GONE
                    }
                    drawerAnimator = null
                    // 左抽屉展开到位轻震(与汉堡联动同款, 收起不震, 尊重系统触觉开关)
                    if (open && !cancelled) {
                        root.performHapticFeedback(
                            if (android.os.Build.VERSION.SDK_INT >= 27)
                                android.view.HapticFeedbackConstants.CLOCK_TICK
                            else android.view.HapticFeedbackConstants.CONTEXT_CLICK
                        )
                    }
                }
            })
            start()
        }
    }

    internal fun cancelDrawerAnim() {
        drawerAnimator?.cancel()
        drawerAnimator = null
    }

    /** 主界面下沉联动: p∈[0,1] → scale 1→0.88, translationY 0→24dp, 圆角 0→20dp; 跟手与动画共用 */
    internal fun setMainSink(p: Float) {
        val pp = p.coerceIn(0f, 1f)
        if (pp == mainSinkP) return
        mainSinkP = pp
        if (pp > 0.05f && pp < 0.95f) android.util.Log.i("NyralSink", "setMainSink p=$pp")
        val s = 1f - 0.12f * pp
        main.scaleX = s
        main.scaleY = s
        main.translationY = dp(24) * pp
        main.invalidateOutline()
        // 浏览器窗口挂 root 层(main 之下): 随 main 同步缩放/下沉, 抽屉联动时窗口与标题栏/输入框保持一致
        // (窗口 top≈titleBar 底、pivot 中心缩放, 视觉偏差仅几像素; 网页内容随窗口一起缩小)
        if (::browserPage.isInitialized && browserPage.open) {
            browserPage.root.scaleX = s
            browserPage.root.scaleY = s
            browserPage.root.translationY = dp(24) * pp
        }

        // 顶栏/底栏外侧两角随下沉进度 0→16dp 圆角化, 与主界面圆角同相
        if (::titleBarBg.isInitialized) {
            val r = dp(16).toFloat()
            val lr = r * pp
            titleBarBg.cornerRadii = floatArrayOf(lr, lr, lr, lr, r, r, r, r)
            inputBarBg.cornerRadii = floatArrayOf(r, r, r, r, lr, lr, lr, lr)
        }
    }

    /** 汉堡面板展开联动: 主界面随面板一起下沉(与左抽屉同一 setMainSink), 收起恢复展开前深度 */
    /** 汉堡面板跟手拖动联动起点: 关->开方向拖动开始时记录基准下沉深度(供收起恢复), 已开时保留展开时记录的 base */
    internal fun hamburgerDragBase() {
        hamburgerSinkAnimator?.cancel()
        // 跟手联动期间 main 缓存纹理: 缩放下沉不逐帧重绘(防背景闪烁/掉帧), 吸附动画结束后恢复
        main.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        if (!browserPage.hamburgerOpen) hamburgerSinkBase = mainSinkP
    }

    /** 汉堡联动结束: 释放 main 硬件层, 恢复普通绘制 */
    internal fun hamburgerDragEnd() {
        main.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
    }

    internal fun animateHamburgerSink(open: Boolean, recordBase: Boolean = true) {
        android.util.Log.i("NyralSink", "animateHamburgerSink open=$open from=$mainSinkP")
        hamburgerSinkAnimator?.cancel()
        val from = mainSinkP
        if (open && recordBase) hamburgerSinkBase = mainSinkP
        val to = if (open) 1f else hamburgerSinkBase
        var cancelled = false
        hamburgerSinkAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 240
            interpolator = android.view.animation.DecelerateInterpolator(1.2f)
            addUpdateListener { setMainSink(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    hamburgerSinkAnimator = null
                    if (!cancelled && open) {
                        // 汉堡展开到位轻震(收起不震, 尊重系统触觉开关)
                        root.performHapticFeedback(
                            if (android.os.Build.VERSION.SDK_INT >= 27)
                                android.view.HapticFeedbackConstants.CLOCK_TICK
                            else android.view.HapticFeedbackConstants.CONTEXT_CLICK
                        )
                    }
                }
            })
            start()
        }
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 气泡最大宽度: 聊天模式=到对方头像内侧(屏幕宽-两侧padding/头像/间距, 左右对称对齐); Agent 模式=全屏宽 */
    internal fun chatMaxW(): Int {
        // 聊天模式: 屏幕宽-聊天框padding(12*2)-头像(40*2)-间距(8*2)=w-120dp, 气泡右缘停在对头像内侧(左右对称);
        // Agent 模式: 全屏宽 w (2026-10-02 起 Agent 模式全屏, 聊天模式恢复对称收窄)
        return if (ModeConfig.chatMode()) {
            (resources.displayMetrics.widthPixels - dp(120)).coerceAtLeast(dp(100))
        } else {
            resources.displayMetrics.widthPixels
        }
    }


    // ===================== AI 流式气泡容器 =====================

}
