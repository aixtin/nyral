package io.github.aixtin.nyral

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
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import android.view.Choreographer
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
import androidx.media3.exoplayer.ExoPlayer
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
    }

    private val TAG = "Nyral"
    internal val executor = Executors.newSingleThreadExecutor()
    internal val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
            .usePlugin(RoundedTablePlugin.create(TableTheme.buildWithDefaults(this)
                    .tableCellPadding((10 * resources.displayMetrics.density).toInt())
                    .tableBorderWidth((1 * resources.displayMetrics.density).toInt())
                    .tableBorderColor(Ui.DIVIDER)
                    .tableHeaderRowBackgroundColor(Ui.INPUT_BG)
                    .tableOddRowBackgroundColor(Ui.INPUT_BG)
                    .build(), resources.displayMetrics.density))
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
    private lateinit var chatRec: RecyclerView
    private var streamingRow: ChatRow.Streaming? = null
    /** 请求代际(阶段2 流式竞态治理): 每次发起新请求/取消当前请求(切会话)自增,
     *  流式回调进入主线程后先校验代际一致才操作 holder/滚动, 天然拦截迟到回调 */
    private var requestEpoch = 0L
    private var scrollUserScrolled = false   // 用户手动上翻后不再自动拉底(不打扰阅读)
    private lateinit var root: FrameLayout
    // 独立固定全屏背景层: 壁纸/渐变背景挂此层(不随键盘压缩上移), root 为透明壳
    private lateinit var bgLayer: FrameLayout
    private lateinit var bodyWrap: LinearLayout
    // 消息区独立 FrameLayout: browserBar 悬浮 overlay 不占位, 聊天区高度恒定防气泡抖动
    private lateinit var chatArea: FrameLayout
    private lateinit var inputBar: LinearLayout
    // 浏览器控制条(输入框上方): 悬浮聊天时显示欢迎文字+接管按钮
    private lateinit var browserBar: LinearLayout
    private lateinit var browserBarStatus: TextView
    private lateinit var browserBarTakeover: TextView
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
    internal lateinit var sendBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var stopSpin: ArcRingDrawable      // 停止按钮上的无限旋转加载环(带缺口弧)
    private var stopSpinAnim: ValueAnimator? = null        // 旋转动画驱动
    internal lateinit var drawerPanel: LinearLayout
    internal lateinit var drawerMask: View
    internal lateinit var sessionList: LinearLayout
    internal var drawerOpen = false
    private lateinit var swipeDetector: GestureDetector
    /** 抽屉跟手拖拽控制器(微信式): root 拦截水平边缘/遮罩手势, 1:1 跟随 + 抬手吸附 */
    private lateinit var drawerDrag: DrawerDragController
    /** 右侧整屏浏览器操作页(自研 Agent 浏览器雏形): 全屏 WebView + AI 状态条/高亮圈/思考摘要 */
    internal lateinit var browserPage: BrowserPage
    private val autoSavedHinted = java.util.HashSet<String>()
    fun browserPageReady(): Boolean = ::browserPage.isInitialized
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
        // 应用当前主题（默认=现有视觉零变化）：全局语义色 + 气泡色
        val theme = ThemeManager.current(this)
        Ui.applyTheme(theme)
        applyBubbleTheme(theme)
        BUBBLE_USER = theme.bubbleUser
        BUBBLE_USER_TEXT = theme.bubbleUserText
        instance = this
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
        // 状态栏图标明暗随主题底亮度自适应: 浅底深图标, 深底浅图标
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dark = (Color.red(Ui.SURFACE) + Color.green(Ui.SURFACE) + Color.blue(Ui.SURFACE)) / 3 < 128
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
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


        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (drawerDrag.isDragging()) return false
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) * 1.5f && abs(dx) > dp(60).toFloat() && abs(velocityX) > 500f) {
                    if (dx < 0 && drawerOpen) closeDrawer()
                    else if (dx > 0 && !drawerOpen && !browserPage.open) openDrawer()
                    return true
                }
                return false
            }
        })

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
                // 键盘弹起时, 点击输入区以外(消息区/背景/标题栏)收起键盘并清光标; 未弹键盘或点击输入框/按钮时不影响
                if (ev.action == MotionEvent.ACTION_UP && ::inputBar.isInitialized) {
                    val i3 = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (i3?.isActive == true) {
                        val loc = IntArray(2)
                        inputBar.getLocationInWindow(loc)
                        val inInputBar = ev.rawY.toInt() >= loc[1] - dp(8)
                        android.util.Log.d("KBDDBG", "DISPATCH rawY=${ev.rawY.toInt()} loc1=${loc[1]} inIB=$inInputBar active=${i3.isActive}")
                        if (!inInputBar) {
                            i3.hideSoftInputFromWindow(input.windowToken, 0)
                            if (input.isFocused) input.clearFocus()
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
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 透明: 让挂在 bgLayer 的聊天背景(预设渐变/自定义图)透出来, 否则不透明底色会盖住背景
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 标题栏
        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(8), dp(8), dp(8))
            setBackgroundColor(Ui.SURFACE)
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(TextView(this).apply {
            text = "☰"
            textSize = 24f
            setTextColor(Ui.TEXT)
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

        // 消息区: RecyclerView 可回收传送带——只保留屏幕内可见的气泡, 滚出屏幕即回收销毁,
        // 滚回复用同一框架塞新内容, 不随聊天变长无限堆叠 View(解决 ScrollView+LinearLayout 长会话卡顿/内存增长)
        chatAdapter = ChatAdapter(chatRows) { row -> buildRowView(row) }
        chatRec = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
            setPadding(dp(12), dp(10), dp(12), dp(10))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            // 用户一旦手动滑动(上翻阅读), 标记后不再被自动滚动打断
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) scrollUserScrolled = true
                false
            }
            // 用户滚回底部附近时恢复自动追底(上翻阅读仅在离开底部期间让位, 复活旧 ScrollView 版 scrollUserScrolled 语义)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!scrollUserScrolled) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val count = rv.adapter?.itemCount ?: return
                    if (last >= count - 2) scrollUserScrolled = false
                }
            })
        }
        chatAdapter.recyclerView = chatRec
        applyChatBackground()
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
        bodyWrap.addView(attachPreviewWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 输入区: 按钮在输入框右侧, 底对齐固定在右下角
        inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Ui.SURFACE)
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
                            android.util.Log.d("KBDDBG", "commitText NL dropped (down already inserted)")
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
            // 回车放行 DOWN(系统默认 KeyListener 插入 \n, 百度 sendKeyEvent 路径); 吞掉 UP/MULTIPLE 防重复。
            // 微信输入法在 DOWN 系统插入后还会 commitText("\n"), 由 InputConnection 包装层去重丢弃, 避免双换行。
            setOnKeyListener { v, keyCode, e ->
                if (keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> {
                        downPassed = true
                        android.util.Log.d("KBDDBG", "enter DOWN passThrough downPassed=true")
                        false
                    }
                    KeyEvent.ACTION_UP -> {
                        val now = android.os.SystemClock.uptimeMillis()
                        android.util.Log.d("KBDDBG", "enter UP consumed dt=${now - lastEnterInsert}")
                        true
                    }
                    KeyEvent.ACTION_MULTIPLE -> {
                        android.util.Log.d("KBDDBG", "enter MULTIPLE consumed")
                        true
                    }
                    else -> false
                }
            }
            // 键盘收起(光标已清)后再次点击: 只恢复焦点与键盘; 不再强制 setSelection 到末尾,
            // 光标定位交给系统默认(点哪光标哪), 否则无法点击定位到任意文本位置
            setOnClickListener { view ->
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
                    if (n > nlCount) {
                        val now = android.os.SystemClock.uptimeMillis()
                        android.util.Log.d("KBDDBG", "NL inserted nl=$n dt=${now - lastEnterInsert}")
                        lastEnterInsert = now
                    }
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
            visibility = View.GONE
            setOnClickListener { showAttachSheet() }
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
            setOnClickListener { showAttachSheet() }
        }
        attachWrap.addView(attachBtn)
        sendBtn = Button(this).apply {
            text = getString(R.string.ma_send)
            textSize = 13f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setTextColor(Color.WHITE)
            background = rounded(dp(16), Ui.PRIMARY)
            setPadding(dp(2), dp(5), dp(2), dp(5))
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                bottomMargin = dp(10)
            }
            // 默认隐藏发送按钮, 输入文字时切换显示 (见 updateInputMode); 与附件按钮原地替换
            visibility = View.GONE
            setOnClickListener { onSend() }
            Ui.press(this)
        }
        attachWrap.addView(sendBtn)
        stopBtn = Button(this).apply {
            text = "■"
            textSize = 13f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setTextColor(Color.WHITE)
            background = rounded(dp(18), Ui.DANGER)
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            // 固定 36dp, 与附件/发送同槽位叠放
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener {
                LocalEngine.requestCancel()
                stopBtn.isEnabled = false
                stopBtn.text = "…"
                stopSpinAnim?.cancel()
                android.util.Log.i("Nyral", "stop clicked, cancelRequested=${LocalEngine.cancelRequested}")
            }
            Ui.press(this)
        }
        stopSpin = ArcRingDrawable(dp(16), dp(3), Color.WHITE)
        stopBtn.setCompoundDrawablesWithIntrinsicBounds(stopSpin, null, null, null)
        attachWrap.addView(stopBtn)
        inputBar.addView(attachWrap)
        // 浏览器控制条: 悬浮聊天时位于输入框上方(欢迎文字 + 接管按钮), 浏览器关闭时隐藏
        browserBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(Ui.SURFACE)
            visibility = View.GONE
        }
        browserBarStatus = TextView(this).apply {
            text = getString(R.string.ma_welcome)
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        browserBar.addView(browserBarStatus)
        browserBarTakeover = TextView(this).apply {
            text = getString(R.string.br_takeover)
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
        }
        browserBar.addView(browserBarTakeover)
        // 悬浮 overlay 挂聊天区底部(输入框上方), 不占位挤压 chatRec; bottomMargin 在 setChatFloatMode 中动态对齐 inputBar
        chatArea.addView(browserBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bodyWrap.addView(inputBar)
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
                // 悬浮 overlay: 控制条底部精确对齐输入框顶部(实测坐标差), 消除渲染缝隙, 视觉贴合
                val blp = browserBar.layoutParams as FrameLayout.LayoutParams
                val cLoc = IntArray(2)
                val iLoc = IntArray(2)
                chatArea.getLocationInWindow(cLoc)
                inputBar.getLocationInWindow(iLoc)
                blp.bottomMargin = (cLoc[1] + chatArea.height - iLoc[1]).coerceAtLeast(0)
                browserBar.layoutParams = blp
                // 直接显示不淡入: onPreOpen 与 onOpenChange 会连续两次调用本函数, 每次 alpha 归零重做
                // 淡入动画 = 打开浏览器控制条"闪一下"; 幂等保护: 已可见则跳过, 并取消可能残留的关闭动画
                browserBar.animate().cancel()
                if (browserBar.visibility != View.VISIBLE) {
                    browserBar.alpha = 1f
                    browserBar.visibility = View.VISIBLE
                }
            } else {
                browserBar.animate().alpha(0f).setDuration(140).withEndAction {
                    browserBar.visibility = View.GONE
                }.start()
            }
            animateBubbleFloat(float)
        }
        // 浏览器窗口收缩到聊天内容区(titleBar 下 ~ browserBar/inputBar 上), 接管/悬浮均保持该尺寸
        fun adjustBrowserWindow() {
            if (!browserPage.root.isAttachedToWindow) return
            val tLoc = IntArray(2)
            titleBar.getLocationInWindow(tLoc)
            val top = tLoc[1] + titleBar.height
            // browserBar 打开时才 VISIBLE(布局未跑 height=0): 手动 measure 取真实高度, 避免取到未布局位置导致收缩错位
            if (browserBar.height == 0) {
                browserBar.measure(
                    View.MeasureSpec.makeMeasureSpec(titleBar.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            }
            val iLoc = IntArray(2)
            inputBar.getLocationInWindow(iLoc)
            val bottom = iLoc[1] - browserBar.measuredHeight
            val h = (bottom - top).coerceAtLeast(dp(120))
            browserPage.setWindowRect(top, h)
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
                adjustBrowserWindow()
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
            browserBarTakeover.text = if (taken) "🤖 交还 AI" else "✋ 接管"
            browserBarStatus.text = if (taken) "你已接管，可点击网页（如验证码）" else "欢迎回来 · 一切就绪"
            browserPage.setBottomTakeoverVisible(false)
        }
        browserPage.onHamburgerChange = { open ->
            // 汉堡面板已提升到 root 最顶层, 直接覆盖聊天层; 接管时消息区已隐藏, 无需再 GONE main
            browserPage.setBottomTakeoverVisible(false)
        }
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
            dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        browserPage.hamburgerPanel.translationX = dp(300).toFloat()
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
        setContentView(root)
        // 全面屏手势导航(Android10+): 左边缘横滑默认是系统"返回", 会抢走抽屉跟手手势。
        // 学 AndroidX DrawerLayout / QQ 侧边栏: 把整块抽屉区域(左缘 0..280dp 宽, 全屏高)声明为系统手势排除区,
        // 该区域内系统返回全程让位, 边缘慢拖 1:1 跟手; 区域之外(屏幕右侧/中间)系统返回照常。
        // (系统文档称每边沿边长度限 200dp, 但 DrawerLayout 式整块排除在 Android12+ 及国产 ROM 实践中全屏有效, 微信/QQ 同款)
        if (Build.VERSION.SDK_INT >= 29) {
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.height > 0) {
                    // 左侧整块抽屉 + 右侧 1/3 触发区(浏览器/汉堡面板) 声明为系统手势排除区, 系统返回让位
                    v.systemGestureExclusionRects = listOf(
                        Rect(0, 0, DRAWER_WIDTH, v.height),
                        Rect(v.width - v.width / 3, 0, v.width, v.height)
                    )
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
                    val setMargin = { btn: Button ->
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
        // 键盘检测: WindowInsets.ime()(API30+) 精确报告键盘高度(adjustNothing 下不被窗口吸收)。
        // setDecorFitsSystemWindows(false) 让系统不自动消化 insets, 完整派发到内容层(含 IME), 我们统一处理:
        // 主内容避开状态栏/导航栏(fitsSystemWindows 的替代), 键盘弹起 bodyWrap 高度压缩到键盘顶,
        // 键盘收起(返回键/下滑/点空白) bodyWrap 恢复满高且输入框光标跟随关闭, 再次点击输入框可恢复继续输入
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            var imeShown = false
            var imeFloating = false  // QQ 式悬浮模式: 惯性滚动中弹键盘, bodyWrap 不压缩, 仅输入区悬浮到键盘顶
            var imeAnimator: android.animation.ValueAnimator? = null
            var bodyWrapFullH = 0
            var lastImeH = 0
            var prevCompressH = 0  // 压缩模式上一帧 bodyWrap 高度, 用于末条不可见时按压缩增量滚动
            var inImeAnim = false  // 系统键盘 insets 动画进行中, 压缩高度由 WindowInsetsAnimation 逐帧驱动
            var lastProgressImeH = 0  // onProgress 最后一帧键盘高度, onEnd 据此判断动画方向(收起方向需兜底恢复)
            var imeFloatingListener: RecyclerView.OnScrollListener? = null
            root.setOnApplyWindowInsetsListener { v, insets ->
                val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                main.setPadding(0, sb.top, 0, sb.bottom)
                // 抽屉悬浮于 root 上, 不参与 main 的 insets 派发, 需自行避开状态栏/导航栏,
                // 否则 setDecorFitsSystemWindows(false) 下顶部标题栏被状态栏遮挡、底部被导航栏顶低
                if (::drawerPanel.isInitialized) drawerPanel.setPadding(0, sb.top, 0, sb.bottom)
                val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                lastImeH = imeH
                val lp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                android.util.Log.d("KBDDBG", "INSETS imeH=$imeH imeShown=$imeShown focused=${input.isFocused} bh=${bodyWrap.height}")
                if (imeH > dp(80)) {
                    if (!imeShown) {
                        bodyWrapFullH = bodyWrap.height.coerceAtLeast(1)
                        // QQ 式双分支: 列表正在惯性滚动(DRAGGING/SETTLING) → 不压缩 bodyWrap、消息不抬起,
                        // 仅输入区悬浮到键盘顶, 惯性滚动自然继续不被干扰; 列表静止(IDLE) → 压缩 bodyWrap, 消息原位顶起
                        imeFloating = chatRec.scrollState != RecyclerView.SCROLL_STATE_IDLE
                        if (imeFloating) {
                            // 悬浮模式: 输入区平移量需补偿 main 底部导航栏 padding, 否则悬在键盘顶上方一段距离
                            inputBar.translationY = -(imeH - sb.bottom).coerceAtLeast(0).toFloat()
                            if (attachPreviewWrap.visibility == View.VISIBLE) attachPreviewWrap.translationY = -(imeH - sb.bottom).coerceAtLeast(0).toFloat()
                        } else {
                            lp.weight = 0f; lp.height = bodyWrapFullH
                            bodyWrap.layoutParams = lp
                            prevCompressH = bodyWrapFullH
                        }
                    }
                    imeShown = true
                    if (imeFloating) {
                        // 悬浮模式: 键盘高度继续变化时同步输入区位置(补偿导航栏 padding); bodyWrap 不压缩 → 消息不抬起、惯性滚动不受干扰
                        inputBar.translationY = -(imeH - sb.bottom).coerceAtLeast(0).toFloat()
                        if (attachPreviewWrap.visibility == View.VISIBLE) attachPreviewWrap.translationY = -(imeH - sb.bottom).coerceAtLeast(0).toFloat()
                        // 惯性滚动停止后自动切回压缩模式, 让消息原位顶起(QQ 行为: 滚动中不顶, 滚停即顶)
                        if (imeFloatingListener == null) {
                            val listener = object : RecyclerView.OnScrollListener() {
                                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                                    if (imeFloating && imeShown && newState == RecyclerView.SCROLL_STATE_IDLE) {
                                        imeFloating = false
                                        rv.removeOnScrollListener(this)
                                        if (imeFloatingListener === this) imeFloatingListener = null
                                        val lp2 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                                        lp2.weight = 0f
                                        lp2.height = bodyWrapFullH
                                        bodyWrap.layoutParams = lp2
                                        inputBar.animate().translationY(0f).setDuration(150).start()
                                        if (attachPreviewWrap.visibility == View.VISIBLE) attachPreviewWrap.animate().translationY(0f).setDuration(150).start()
                                        val tb2 = IntArray(2)
                                        titleBar.getLocationInWindow(tb2)
                                        val titleBottom2 = tb2[1] + titleBar.height
                                        val targetH2 = ((root.height - kotlin.math.max(lastImeH, sb.bottom)) - titleBottom2).coerceAtLeast(dp(60))
                                        imeAnimator?.cancel()
                                        val from2 = bodyWrapFullH
                                        var prevH2 = from2
                                        imeAnimator = android.animation.ValueAnimator.ofInt(from2, targetH2).apply {
                                            duration = 100
                                            interpolator = android.view.animation.DecelerateInterpolator()
                                            addUpdateListener {
                                                val lp3 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                                                val newH3 = it.animatedValue as Int
                                                val dh3 = prevH2 - newH3
                                                lp3.height = newH3
                                                bodyWrap.layoutParams = lp3
                                                chatRec.post {
                                                    val lm3 = chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
                                                    val lastPos3 = (chatRec.adapter?.itemCount ?: 0) - 1
                                                    val lv3 = lm3.findViewByPosition(lastPos3)
                                                    if (lv3 != null) {
                                                        val gap3 = lv3.bottom - (chatRec.height - chatRec.paddingBottom)
                                                        if (gap3 != 0) chatRec.scrollBy(0, gap3)
                                                    } else if (dh3 != 0) {
                                                        chatRec.scrollBy(0, dh3)
                                                    }
                                                }
                                                prevH2 = newH3
                                            }
                                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                                override fun onAnimationEnd(a: android.animation.Animator) {
                                                    prevCompressH = targetH2
                                                }
                                            })
                                            start()
                                        }
                                    }
                                }
                            }
                            imeFloatingListener = listener
                            chatRec.addOnScrollListener(listener)
                        }
                    } else {
                        // 键盘 insets 动画期间高度由 WindowInsetsAnimation.Callback 逐帧驱动(与键盘弹起同帧);
                        // 此处仅在无动画(键盘瞬时出现/动画结束后最终 insets 兜底)时设置最终高度, 避免提前跳变
                        if (!inImeAnim) {
                            val tb = IntArray(2)
                            titleBar.getLocationInWindow(tb)
                            val titleBottom = tb[1] + titleBar.height
                            val targetH = ((root.height - kotlin.math.max(imeH, sb.bottom)) - titleBottom).coerceAtLeast(dp(60))
                            val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                            val dh = prevCompressH - targetH
                            prevCompressH = targetH
                            if (lpp.height != targetH) {
                                lpp.height = targetH
                                bodyWrap.layoutParams = lpp
                            }
                            // 顶起: 布局稳定后锚定末条贴回视口内容底(保留 paddingBottom, 滚动量=压缩量, 单动作无闪烁);
                            // 末条不可见(翻历史)才按压缩增量滚动, 把当前位置内容顶到键盘上方
                            chatRec.post {
                                val lm = chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
                                val lastPos = (chatRec.adapter?.itemCount ?: 0) - 1
                                val lv = lm.findViewByPosition(lastPos)
                                if (lv != null) {
                                    val gap = lv.bottom - (chatRec.height - chatRec.paddingBottom)
                                    if (gap != 0) chatRec.scrollBy(0, gap)
                                } else if (dh != 0) {
                                    chatRec.scrollBy(0, dh)
                                }
                            }
                        }
                    }
                } else if (imeShown) {
                    // 键盘收起动画期间 insets 可能中途回调, 高度已由 WindowInsetsAnimation 逐帧恢复, 此处等动画结束后最终 insets 再收尾
                    if (inImeAnim) return@setOnApplyWindowInsetsListener insets
                    imeShown = false
                    if (imeFloatingListener != null) {
                        chatRec.removeOnScrollListener(imeFloatingListener!!)
                        imeFloatingListener = null
                    }
                    if (imeFloating) {
                        // 收起悬浮模式: 输入区落回原位, 列表视口从未变化无需恢复
                        imeFloating = false
                        inputBar.animate().translationY(0f).setDuration(180)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                        if (attachPreviewWrap.visibility == View.VISIBLE)
                            attachPreviewWrap.animate().translationY(0f).setDuration(180)
                                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    } else {
                        // 收起同步: insets 动画期间每帧已随 imeH 递减同步恢复 bodyWrap 高度, 此处只需还原为权重撑满
                        if (imeAnimator?.isRunning == true) imeAnimator?.cancel()
                        val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                        lpp.height = 0; lpp.weight = 1f
                        bodyWrap.layoutParams = lpp
                        prevCompressH = bodyWrapFullH
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
                    if (!imeShown) {
                        // 键盘开始弹出: 记录全高并进入压缩/悬浮初始状态(滚动中→悬浮, 静止→压缩)
                        imeShown = true
                        bodyWrapFullH = bodyWrap.height.coerceAtLeast(1)
                        imeFloating = chatRec.scrollState != RecyclerView.SCROLL_STATE_IDLE
                        if (!imeFloating) {
                            val lp0 = bodyWrap.layoutParams as LinearLayout.LayoutParams
                            lp0.weight = 0f; lp0.height = bodyWrapFullH
                            bodyWrap.layoutParams = lp0
                            prevCompressH = bodyWrapFullH
                        }
                    }
                }
                override fun onProgress(insets: android.view.WindowInsets, runningAnimations: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets {
                    val imeAnim = runningAnimations.firstOrNull { (it.typeMask and android.view.WindowInsets.Type.ime()) != 0 } ?: return insets
                    // 当前帧真实 ime 高度(动画中间值), 与键盘视觉逐帧同步
                    val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    lastProgressImeH = imeH  // 记录最后一帧键盘高度, onEnd 据此判断动画方向
                    if (imeShown && !imeFloating) {
                        // 压缩顶起/恢复: 高度随键盘动画进度逐帧同步, 锚定末条保留底部留白
                        val tb = IntArray(2)
                        titleBar.getLocationInWindow(tb)
                        val titleBottom = tb[1] + titleBar.height
                        val targetH = ((root.height - kotlin.math.max(imeH, sb.bottom)) - titleBottom).coerceAtLeast(dp(60))
                        val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                        val dh = prevCompressH - targetH
                        prevCompressH = targetH
                        if (lpp.height != targetH) {
                            lpp.height = targetH
                            bodyWrap.layoutParams = lpp
                        }
                        chatRec.post {
                            val lm = chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
                            val lastPos = (chatRec.adapter?.itemCount ?: 0) - 1
                            val lv = lm.findViewByPosition(lastPos)
                            if (lv != null) {
                                val gap = lv.bottom - (chatRec.height - chatRec.paddingBottom)
                                if (gap != 0) chatRec.scrollBy(0, gap)
                            } else if (dh != 0) {
                                chatRec.scrollBy(0, dh)
                            }
                        }
                    } else if (imeShown && imeFloating) {
                        // 悬浮模式: 输入区随键盘动画进度同步平移(补偿导航栏 padding)
                        inputBar.translationY = -(imeH - sb.bottom).coerceAtLeast(0).toFloat()
                        if (attachPreviewWrap.visibility == View.VISIBLE) attachPreviewWrap.translationY = -(imeH - sb.bottom).coerceAtLeast(0).toFloat()
                    }
                    return insets
                }
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    if ((animation.typeMask and android.view.WindowInsets.Type.ime()) == 0) return
                    inImeAnim = false
                    // 收起动画结束: 最终 insets 回调常被动画期间挡掉(imeShown 仍 true), bodyWrap 可能停在固定高度。
                    // v8 曾按 lastProgressImeH<=80 判断方向, 但动画被中途打断(如点击视频弹窗抢焦点)时
                    // lastProgressImeH 停在中间值判定不恢复 -> 输入框卡在压缩中间位悬浮半空。
                    // 改为立即复查真实键盘状态: 键盘已收(insets≈0)或输入框已失焦(键盘必然在收/已收) => 强制恢复权重撑满。
                    // 立即执行(下一帧)而非延迟, 避免 bodyWrap 在中间高度停 300ms 造成"两段式"掉底观感。
                    root.post {
                        if (!imeShown || inImeAnim) return@post
                        val curImeH = try {
                            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                        } catch (e: Exception) { 0 }
                        if (curImeH <= dp(80) || !input.isFocused) {
                            if (imeFloating) {
                                imeFloating = false
                                inputBar.translationY = 0f
                                if (attachPreviewWrap.visibility == View.VISIBLE) attachPreviewWrap.translationY = 0f
                            }
                            val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                            if (lpp.weight == 0f) {
                                lpp.height = 0; lpp.weight = 1f
                                bodyWrap.layoutParams = lpp
                                prevCompressH = bodyWrapFullH
                            }
                            imeShown = false
                            if (input.isFocused) input.clearFocus()
                        }
                    }
                }
            })
        }
        // 键盘弹起时点击输入区以外收起键盘: 在 root.dispatchTouchEvent 实现(见 root 定义处), 无其它点击监听
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendWelcomeIntro()
        // 恢复最近一次会话，避免杀后台后聊天记录与列表丢失
        val recent = db.listSessions(1, ModeConfig.modeValue())
        if (recent.isNotEmpty() && db.loadSessionMessages(recent[0].id).isNotEmpty()) {
            openSession(recent[0].id)
        }
        // 开发者调试服务: 默认关闭; 设置开启且为 debug 构建时在 onCreate 末尾拉起
        DebugServer.init(this)
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

    // ===================== 左侧抽屉 =====================


    // ===================== 会话全文搜索 =====================


    // ===================== 多会话 =====================

    /** 取消当前 AI 请求(切会话/新会话调用): 代际自增使迟到回调全部失效, 立即恢复输入态,
     *  不依赖迟到 onDone/onError 清理状态(阶段2 流式竞态治理) */
    private fun cancelActiveRequest() {
        // 丢弃流式行引用(无论 AI 是否还在输出): 防止全量重建(切模式/开会话/新会话)时
        // buildRowsFromMessages 兜底把已收尾的 Streaming 行再次塞回 → 跨模式串写/AI回复重复
        streamingRow = null
        if (!aiBusy) return
        LocalEngine.requestCancel()
        requestEpoch++
        aiBusy = false
        TaskService.stop(this@MainActivity)
        updateInputMode()
        stopBtn.visibility = View.GONE
        stopSpinAnim?.cancel()
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
        messages.clear()
        messages.addAll(msgs)
        currentSaved = true
        currentSessionId = id
        currentSessionTitle = db.sessionTitleOf(id)
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
                scrollToBottom()
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
        // 发送后焦点/键盘恢复: 必须在下一帧(点击事件分发结束后)执行, 否则 requestFocus 未生效、showSoftInput 被忽略
        if (!voiceMode) {
            input.post {
                input.showSoftInputOnFocus = true
                input.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(input, 0)
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
                    when {
                        a.mime.startsWith("image/") -> "[图片]$link"
                        a.isVoice -> "[音频]$link"                       // 本地录音: 保留语音气泡形态
                        a.mime.startsWith("audio/") -> "[文件:${a.name}]$link"  // 上传音频文件: 按文件卡片展示
                        a.mime.startsWith("video/") -> "[视频:${a.name}]$link"
                        else -> "[文件:${a.name}]$link"
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
        startStopSpin()
        executor.execute {
            val holder = AiBubbleHolder(this@MainActivity)
            uiScope.launch {
                // 流式行: 新增 Streaming 占位行(回收传送带末位), AiBubbleHolder 气泡盒挂到该行 item 容器
                val row = ChatRow.Streaming(nextTempRowId(), holder)
                streamingRow = row
                chatAdapter.add(row)
                val box = holder.createStreamingBox()
                row.bubbleBox = box
                chatAdapter.attachStreaming(chatRows.size - 1)
                holder.showStatus(getString(R.string.ma_thinking))
                scrollToBottom(true)
            }
            LocalEngine.chat(this@MainActivity, history, object : LocalEngine.Callback {
                override fun onThinkingStart() {
                    LogStore.i(LogStore.MAIN, "开始思考")
                    AITerminal.push("thinking", "开始思考…")
                    debugSseSink?.invoke("thinking_start", "")
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.showThinking(getString(R.string.ma_thinking_prefix)) }
                }
                override fun onThinkingDelta(text: String) {
                    debugSseSink?.invoke("thinking", text)
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.appendThinking(text); scrollToBottom(true) }
                }
                override fun onThinkingEnd() {
                    AITerminal.push("thinking", "思考结束，进入作答")
                    debugSseSink?.invoke("thinking_end", "")
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.collapseThinking() }
                }
                override fun onTool(name: String, arg: String) {
                    LogStore.i(LogStore.MAIN, "调用工具: $name")
                    AITerminal.push("tool", "$name $arg")
                    debugSseSink?.invoke("tool", "$name|$arg")
                    uiScope.launch {
                        if (epoch != requestEpoch) return@launch
                        holder.showTool(name, arg)
                        // 进入工具调用即表示本段思考已结束: 折叠思考区, 避免一直停在"思考中"
                        holder.collapseThinking()
                        scrollToBottom(true)
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
                    uiScope.launch { if (epoch != requestEpoch) return@launch; holder.appendContent(text); scrollToBottom(true) }
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
                        if (LocalEngine.cancelRequested) {
                            // 用户主动停止: 不写入对话/记忆
                            holder.appendContent("\n(已停止)")
                            LocalEngine.cancelRequested = false
                        } else {
                            // 思考内容随回复一起持久化, 切回会话可恢复思考区
                            // 竞态防护: 回调排队期间用户可能已切会话, 不能把回复写进新会话历史
                            if (replySessionId == currentSessionId) {
                                messages.add(MemoryDb.SessionMsg("assistant", reply, holder.thinkingSnapshot(), holder.toolsSnapshot(), holder.timelineSnapshot(), System.currentTimeMillis()))
                                MemoryKeeper.push("assistant", reply)
                                currentSaved = false
                                // 回复完成即时落库, 防止进程被杀丢失最后一条回复
                                maybeSaveCurrent()
                                // 兜底: 引擎重试后仍无正文时给出明确提示, 避免"思考了但没输出"静默空白
                                // 仅在仍是原会话时追加, 防止切会话后提示写入新会话
                                if (reply.isBlank()) appendSys(getString(R.string.ma_sys_no_reply))
                            }
                        }
                        holder.finishContent()
                        // 流式行收尾: 已完成回复内容已落库至 messages, 移除 Streaming 行并重建为静态 AI 行;
                        // 不清理的话, 切模式/开会话全量重建时该行会被 buildRowsFromMessages 兜底再次塞回,
                        // 表现为"切 Agent 串消息 / 切回聊天 AI 回复变两条"(重启进程 streamingRow 归零即恢复)
                        val doneRow = streamingRow
                        streamingRow = null
                        if (!LocalEngine.cancelRequested && doneRow != null) {
                            chatAdapter.remove(doneRow)
                            buildRowsFromMessages()
                        }
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                        stopSpinAnim?.cancel()
                        scrollToBottom(true)
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
                        stopSpinAnim?.cancel()
                        scrollToBottom(true)
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
            val c = if (m.content.length > MAX_MSG_HISTORY) m.content.take(MAX_MSG_HISTORY) + "\n…[该消息过长已截断]" else m.content
            val t = MemoryDb.fmtTs(m.ts)
            if (t.isEmpty()) "${m.role}: $c" else "${m.role}[$t]: $c"
        })
        // 注意: 不再原地截断 messages —— 否则 onStop 保存时会把被截断的列表写回数据库, 造成旧消息永久丢失。
        // 历史裁剪交给 buildHistory 的 takeLast 窗口即可, 完整历史始终保留在 messages/数据库里。
        return sb.toString()
    }

    // ===================== 气泡渲染 =====================

    /** 新消息气泡入场动画: 上移 + 淡入(全局动画 A 范围: 气泡入场) */
    internal fun enterBubble(v: View) {
        v.post {
            v.alpha = 0f
            v.translationY = dp(14).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }
    }

    private fun appendUser(content: String) {
        // 阶段4 时间标签: 首条消息或距上条消息 >=30 分钟时, 先插分组标签(appendUser 前 messages 已含本条)
        val last = messages.getOrNull(messages.size - 2)
        val ts = System.currentTimeMillis()
        if (last == null || ts - last.ts >= TIME_TAG_GAP) {
            chatAdapter.add(ChatRow.TimeTag(nextTempRowId(), formatTimeTag(ts)))
        }
        chatAdapter.add(ChatRow.User(nextTempRowId(), content))
        scrollToBottom()
    }

    private fun appendSys(content: String) {
        chatAdapter.add(ChatRow.Sys(nextTempRowId(), content))
        scrollToBottom(true)
    }

    /** 新会话开场介绍卡片：首启/新建会话时展示（文案集中在 strings.xml 便于迭代，预留可进化接口） */
    private fun appendWelcomeIntro() {
        chatAdapter.add(ChatRow.Welcome(nextTempRowId()))
        scrollToBottom()
    }

    /** RecyclerView 行渲染分发: 每条 ChatRow 对应一个气泡(复用既有 bubble/aiBubbleWithThinking 渲染, 不重造轮子) */
    internal fun buildRowView(row: ChatRow): View = when (row) {
        is ChatRow.User -> chatWrap(bubble(row.content, isUser = true), true)
        is ChatRow.Ai -> chatWrap(bubble(row.content, isUser = false), false)
        is ChatRow.AiRich -> aiBubbleWithThinking(row.thinking, row.content, row.tools, row.timeline)
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
        for (m in messages) {
            // 阶段4 sanitizeMessages: 丢弃全空消息(防 DB 残留空白行污染界面)
            if (m.content.isBlank() && m.thinking.isBlank() && m.tools.isBlank()) continue
            // 阶段4 时间标签: 首条消息或距上条消息 >=30 分钟时插入分组标签(纯展示层, 不动数据)
            if (lastTs == 0L || m.ts - lastTs >= TIME_TAG_GAP) {
                chatRows.add(ChatRow.TimeTag(nextTempRowId(), formatTimeTag(m.ts)))
            }
            lastTs = m.ts
            chatRows.add(
                when {
                    m.role == "user" -> ChatRow.User(sessionBaseSeq + chatRows.size.toLong(), m.content)
                    m.thinking.isNotBlank() -> ChatRow.AiRich(sessionBaseSeq + chatRows.size.toLong(), m.thinking, m.content, m.tools, m.timeline)
                    else -> ChatRow.Ai(sessionBaseSeq + chatRows.size.toLong(), m.content)
                })
        }
        // 阶段2 兜底: 仅 AI 输出中触发全量重建(如窗口外回退)时追加流式行到末尾不丢失气泡盒;
        // 输出完成后(aiBusy=false)不再塞回, 防止已收尾的 Streaming 行变成僵尸行重复渲染
        if (aiBusy) {
            streamingRow?.let { if (it !in chatRows) chatRows.add(it) }
        }
        chatAdapter.submit(chatRows.toList(), onCommitted)
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
                markwon.setMarkdown(this, getString(R.string.welcome_intro_body))
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
    private fun bubble(content: String, isUser: Boolean): View {
        val maxW = chatMaxW()
        // Agent 模式用户右气泡最大宽=chatBox内容宽(屏宽-左右padding 12dp*2), 与AI同为全屏幅宽且左右对称;
        // 不可用全屏w: 全屏w+END右对齐且可用区<气泡宽时左边缘偏移为负→左边越出屏幕
        val userMaxW = if (ModeConfig.chatMode()) maxW else (maxW - dp(24)).coerceAtLeast(dp(120))
        // 接收端气泡循环播放管线: 纯视频单附件消息 -> 气泡内嵌 PlayerView 自动循环播放(动图/多帧媒体
        // 播放完一次自动重播 loop), 对齐微信"大动图循环视频"; 非纯视频/异常回退原缩略图渲染
        val loopView = try { videoLoopBubble(content, isUser, if (isUser) userMaxW else maxW) } catch (e: Exception) { null }
        if (loopView != null) return loopView
        // 纯图片附件消息: 贴边不留白; 纯文件/上传音频保留正常内边距(文件卡片角标+文件名需留白)
        val pureImage = isUser && content.replace(Regex("""\[[^\]]+\]\(att://[^)]+\)"""), "").trim().isEmpty() &&
            content.contains("[图片]")
        // 纯视频消息: 与图片同机制, 首帧缩略图内嵌气泡, 四边边距无限接近 0
        val pureVideo = isUser && content.replace(Regex("""\[[^\]]+\]\(att://[^)]+\)"""), "").trim().isEmpty() &&
            content.contains("[视频:")
        // 纯语音消息: 微信式语音气泡, 加大内边距 + 最小宽度, 保证可点区域够大
        val pureAudio = isUser && content.replace(Regex("""\[[^\]]+\]\(att://[^)]+\)"""), "").trim().isEmpty() &&
            content.contains("[音频]")
        // 纯文件卡片消息(文件/视频/上传音频): 文件名在渲染时已按参考宽度手动中间省略(短名自适应, 长名保留首尾+后缀)
        val isFileCard = isUser && (content.contains("[文件:") || content.contains("[视频:")) &&
            content.replace(Regex("""\[[^\]]+\]\(att://[^)]+\)"""), "").trim().isEmpty()
        return TextView(this).apply {
            if (isUser) {
                text = renderUserContent(content)
                movementMethod = LinkMovementMethod.getInstance()
                // 视频缩略图异步就绪后重建本气泡文本以显示画面缩略图(原: 系统栈取帧失败→ 仅文件卡片)
                val vkN = Regex("""\[视频:[^\]]+\]\(att://([^)]+)\)""").find(content)?.groupValues?.get(1)
                if (vkN != null && isThumbPending(vkN)) {
                    registerThumbRefresh(vkN) {
                        try { text = renderUserContent(content) } catch (_: Throwable) {}
                    }
                }
            } else if (ModeConfig.chatPlainText()) text = ModeConfig.stripChatProtocolPrefix(content.trim())
            else markwon.setMarkdown(this, ModeConfig.stripChatProtocolPrefix(content.trim()))
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
    private fun videoLoopBubble(content: String, isUser: Boolean, maxW: Int): View? {
        if (!isUser) return null
        // 仅纯视频附件消息: 去掉附件占位后无其余文本, 且不足两个附件
        val re = Regex("""\[[^\]]+\]\(att://([^)]+)\)""")
        val marks = Regex("""\[[^\]]+\]\(att://[^)]+\)""").findAll(content).toList()
        if (marks.size != 1) return null
        if (content.replace(Regex("""\[[^\]]+\]\(att://[^)]+\)"""), "").trim().isNotEmpty()) return null
        if (!marks[0].value.startsWith("[视频:")) return null
        val file = re.find(content)?.groupValues?.get(1) ?: return null
        val f = AttachmentStore.fileOf(this, file)
        if (f == null || !f.exists() || f.length() <= 0L) return null
        // 超大视频/前端直传长视频不内嵌(解码耗电), 走原缩略图+点击进弹窗(弹窗内已 loop)
        if (f.length() > 60L * 1024 * 1024) return null
        if (!AttachmentStore.mimeOf(file).startsWith("video/")) return null
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
        val exo = ExoPlayer.Builder(this@MainActivity).build()
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        // 循环播放: 播放完一次自动重播(loop), 静音自动播放(与动图无声语义一致), 点击气泡进全屏弹窗
        exo.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        exo.volume = 0f
        exo.prepare()
        val pv = PlayerView(this).apply {
            this.player = exo
            useController = false
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
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
            // 生命周期: 视图从窗口 detach(会话重建/滚动回收/布局变化)时仅暂停不释放,
            // attach 回来仍能自动续播, 避免播放器被 release 后黑屏(需点击才重建)。
            // 真正销毁由 GC/进程回收兜底; 静音循环体积小, 会话级泄漏可接受
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    try { exo.play() } catch (_: Exception) {}
                }
                override fun onViewDetachedFromWindow(v: View) {
                    try { exo.pause() } catch (_: Exception) {}
                }
            })
        }
        return frame
    }

    /** 用户气泡渲染: [图片](att://file) 直接内嵌缩略图(点击打开原图); 其他附件保持蓝色链接, 其余文本原样 */
    internal fun renderUserContent(content: String): CharSequence {
        val sb = SpannableStringBuilder(content)
        try {
            val re = Regex("\\[([^\\]]+)\\]\\(att://([^)]+)\\)")
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
                val mime = if (f != null) AttachmentStore.mimeOf(file) else ""
                val bmp = if (f != null && mime.startsWith("image/"))
                    decodeAttachmentBitmap(f, resources.displayMetrics.density) else null
                // 视频: 取首帧缩略图+播放三角, 像图片一样内嵌气泡; 取帧失败回退文件卡片
                val vtb = if (f != null && mime.startsWith("video/"))
                    decodeVideoThumbnail(f, resources.displayMetrics.density, this) else null
                android.util.Log.i("Nyral", "renderAtt file=$file mime=$mime exists=${f != null} bmp=${bmp != null} vtb=${vtb != null}")
                if (bmp != null) {
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
                    val rb = roundedBitmap(vtb, dp(14))
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
                setColor(Color.parseColor("#4A90D9"))
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

    private fun aiBubbleWithThinking(thinking: String, content: String, toolsJson: String = "", timelineJson: String = ""): LinearLayout {
        val maxW = chatMaxW()
        // 思考折叠气泡(独立一行, 点击展开全文) —— 与流式 ThinkingBlock 一致
        fun thinkingRow(text: String): TextView = TextView(this@MainActivity).apply {
            var localExpanded = false
            val count = text.codePointCount(0, text.length)
            this.text = getString(R.string.think_expand, count)
            textSize = 14f
            setTextColor(THINK_TEXT)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(dp(10), floatBubbleColor(THINK_BG))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            maxWidth = maxW
            setOnClickListener {
                localExpanded = !localExpanded
                this.text = if (localExpanded) getString(R.string.think_expanded, text) else getString(R.string.think_expand, count)
            }
            // 交互行不启用 textIsSelectable, 保证首次点击即展开(否则被选择机制吞掉需点两次)
        }
        // 工具折叠气泡(一次工具调用独立一行, 点击展开参数与结果) —— 与流式 ToolBlock 一致
        fun toolRow(name: String, arg: String, result: String): TextView = TextView(this@MainActivity).apply {
            this.text = getString(R.string.tool_collapsed, name)
            textSize = 14f
            setTextColor(THINK_TEXT)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(dp(10), floatBubbleColor(THINK_BG))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            maxWidth = maxW
            isClickable = true
            var toolExpanded = false
            setOnClickListener {
                toolExpanded = !toolExpanded
                this.text = buildString {
                    append("🔧 工具：$name")
                    if (toolExpanded) {
                        if (arg.isNotBlank()) append("\n参数：$arg")
                        if (result.isNotBlank()) append("\n结果：$result")
                    }
                }
            }
            // 交互行不启用 textIsSelectable, 保证首次点击即展开(否则被选择机制吞掉需点两次)
        }
        // 正文独立气泡(浅色背景, 不折叠), 与流式 appendContent 一致; 阶段2 分片: 超长正文按段落切多段渲染
        fun contentRow(seg: String): TextView = TextView(this@MainActivity).apply {
            textSize = 15f
            val renderContent = ModeConfig.stripChatProtocolPrefix(seg)
            if (ModeConfig.chatPlainText()) {
                text = renderContent.trimEnd()
            } else {
                // 先设基础字号再渲染 Markdown, 保证 HeadingSpan 的倍率基于正确 textSize 生效
                markwon.setMarkdown(this, renderContent)
            }
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(BUBBLE_AI_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(dp(12), floatBubbleColor(BUBBLE_AI))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            maxWidth = maxW
            makeCopyable(this) { seg }
        }
        val contentSegs = splitLongContent(content)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Agent 模式不要头像(仅聊天模式并排头像); 聊天模式头像由 chatWrap 负责
            // 独立气泡容器: 不包裹大气泡背景, 思考/工具/正文各自成气泡, 与流式 attach() 一致
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.START
                topMargin = dp(6)
            }
            // 思考功能开启时: 与流式气泡同一最小宽度基准, 防止恢复时也横向跳动
            if (ApiConfig.thinkingEffortOf(ApiConfig.providerId()) != ApiConfig.THINK_OFF) {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
                }
                minimumWidth = (tp.measureText("💭 已思考 1000 字，点按展开") + dp(48)).toInt()
            }
            // 恢复渲染: 按交错时间线逐条渲染(v8, 思考/工具/正文保持真实交替顺序)
            val timeline = parseTimeline(timelineJson)
            var contentPlaced = false
            if (timeline.isNotEmpty()) {
                timeline.forEach { (type, thinkText, name, arg, result) ->
                    when (type) {
                        "think" -> addView(chatWrap(thinkingRow(thinkText), false))
                        "tool" -> addView(chatWrap(toolRow(name, arg, result), false))
                        "content" -> if (thinkText.isNotBlank()) {
                            // 新格式(阶段2): content 事件携带该段正文, 逐段精确还原
                            addView(chatWrap(contentRow(thinkText), false))
                            contentPlaced = true
                        } else if (content.isNotBlank()) {
                            // 旧格式: content 事件无文本, 回退渲染全部分片一次
                            contentSegs.forEach { addView(chatWrap(contentRow(it), false)) }
                            contentPlaced = true
                        }
                    }
                }
                // 兜底: timeline 无 content 事件但正文非空(旧数据), 追加末尾
                if (!contentPlaced && content.isNotBlank()) {
                    contentSegs.forEach { addView(chatWrap(contentRow(it), false)) }
                }
            } else {
                // 旧数据回退: 无 timeline 时按历史行为 思考折叠区 + 全部工具行 + 正文
                if (thinking.isNotBlank()) {
                    addView(chatWrap(thinkingRow(thinking), false))
                }
                parseTools(toolsJson).forEach { (name, arg, result) ->
                    addView(chatWrap(toolRow(name, arg, result), false))
                }
                if (content.isNotBlank()) {
                    contentSegs.forEach { addView(chatWrap(contentRow(it), false)) }
                }
            }
        }
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

    /** 滚到最新一条; auto=true 为流式自动追底——用户手动上翻阅读时让位不打断, 滚回底部附近自动恢复追底 */
    internal fun scrollToBottom(auto: Boolean = false) {
        if (auto && scrollUserScrolled) return
        if (chatAdapter.itemCount == 0) return
        chatRec.post { if (chatAdapter.itemCount > 0) chatRec.scrollToPosition(chatAdapter.itemCount - 1) }
    }

    /** 展开/收起气泡时保持当前阅读位置: RecyclerView 行内高度变化由 RV 自身测量处理, 这里仅确保该行仍在视口 */
    internal fun keepReadingPosition(view: View) {
        val holder = chatRec.findContainingViewHolder(view) ?: return
        chatRec.post { chatRec.scrollToPosition(holder.bindingAdapterPosition.coerceAtLeast(0)) }
    }

    /** 长按进入"原文本模式": 弹窗展示该条消息的原始文本, 在该模式下自由选择/复制全文或片段 */
    internal fun makeCopyable(tv: TextView, textProvider: () -> String = { tv.text.toString() }) {
        // 拦截长按, 进入原文本模式(不启用系统文本选择, 避免两套交互冲突)
        tv.setOnLongClickListener {
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
        if (::browserPage.isInitialized) browserPage.destroy()
        // 调试服务随 Activity 销毁关闭, 并清引用避免泄漏
        DebugServer.stop()
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

    private fun startPick(action: String, type: String, code: Int, title: String) {
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
        val MAX = 6
        if (uris.size > MAX) {
            Toast.makeText(this, getString(R.string.toast_att_max_trim, MAX, MAX), Toast.LENGTH_SHORT).show()
        }
        uris.take(MAX).forEach { attachmentSender.sendAttachmentFromUri(it) }
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 气泡最大宽度: 聊天模式=到对方头像内侧(屏幕宽-两侧padding/头像/间距, 左右对称对齐); Agent 模式=屏幕*0.78(原样) */
    internal fun chatMaxW(): Int {
        val w = resources.displayMetrics.widthPixels
        // Agent 模式=全屏宽; 聊天模式=到对方头像内侧(屏幕宽-两侧padding/头像/间距, 左右对称对齐)
        return if (ModeConfig.chatMode()) (w - dp(120)).coerceAtLeast(dp(100)) else w
    }

    /** 停止按钮加载环: 无限旋转(0->360度), 驱动 RotateDrawable 的 level */
    private fun startStopSpin() {
        stopSpinAnim?.cancel()
        stopSpinAnim = ValueAnimator.ofInt(0, 360).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { stopSpin.setAngle(it.animatedValue as Int) }
            start()
        }
    }

    // ===================== AI 流式气泡容器 =====================

    /**
     * AI 回复的动态气泡:
     * [思考区(打字机->收缩)] [工具行] [正文(流式)] 都在同一个左对齐气泡内
     */

}
