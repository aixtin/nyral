package io.github.aixtin.droidagent

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
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
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

    private val TAG = "DroidAgent"
    internal val executor = Executors.newSingleThreadExecutor()
    internal val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Markdown 本地渲染 (Markwon, 开源/无网络/不接第三方服务)
    internal val markwon by lazy {
        Markwon.builder(this)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    // 代码块/行内代码统一浅灰底; CodeBlockSpan 由 markwon 默认 factory 按 theme 整块绘制
                    builder.codeBackgroundColor(0xFFE8E8E8.toInt())
                    builder.codeBlockBackgroundColor(0xFFE8E8E8.toInt())
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
                    .tableBorderColor(0xFF9CA3AF.toInt())
                    .tableHeaderRowBackgroundColor(0xFFDEE3EA.toInt())
                    .tableOddRowBackgroundColor(0xFFF3F4F6.toInt())
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
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private var scrollUserScrolled = false   // 用户手动上翻后不再自动拉底(不打扰阅读)
    private lateinit var root: FrameLayout
    // 独立固定全屏背景层: 壁纸/渐变背景挂此层(不随键盘压缩上移), root 为透明壳
    private lateinit var bgLayer: FrameLayout
    private lateinit var bodyWrap: LinearLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var input: EditText
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
    private val REQ_RECORD = 1005
    private val REQ_NOTIF = 1006  // Android 13+ 通知权限(前台服务通知展示用)
    private val REQ_GUIDE_PERMS = 1007  // 首启权限引导: 一次申请运行时权限
    // 图片压缩上限: 最长边/质量
    internal val maxFileBytes: Int get() = UploadConfig.maxMb() * 1024 * 1024
    // 录音: 上限 60 秒 / 10MB(语音消息足够, 超限直接拒)
    private val MAX_RECORD_MS = 60_000L
    private val MAX_AUDIO_BYTES = 10 * 1024 * 1024
    // 录音状态
    private lateinit var micBtn: Button
    private var audioRecord: AudioRecord? = null
    private var recPcm: ByteArrayOutputStream? = null
    private var recStop = false
    private var recThread: Thread? = null
    private var recStartMs = 0L
    private var recTimer: Runnable? = null
    // 必须与 postDelayed 使用同一 Handler 实例: removeCallbacks 要求消息 target 匹配, 新建实例移除会静默失败
    private var recHandler: Handler? = null
    private lateinit var speakBar: TextView
    private var voiceMode = false
    private var speaking = false
    private var speakCancel = false
    private var audioPlayer: MediaPlayer? = null
    private var playingFileName: String? = null
    // 纯语音气泡注册表(弱引用): 播放状态变化时统一刷新播放/暂停图标
    private val audioBubbles = mutableListOf<WeakReference<TextView>>()
    // 语音气泡内声波动画共享相位 + 驱动动画 (WaveState 已抽离 BubbleSpans.kt)
    private val waveState = WaveState()
    private var waveAnim: ValueAnimator? = null
    private fun startWaveAnim() {
        if (waveAnim != null) return
        waveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                waveState.phase = it.animatedValue as Float
                for (wr in audioBubbles) wr.get()?.invalidate()
            }
            start()
        }
    }
    private fun stopWaveAnim() {
        waveAnim?.cancel(); waveAnim = null
        waveState.phase = 0f
        for (wr in audioBubbles) wr.get()?.invalidate()
    }
    private fun refreshAudioBubbles() {
        val it = audioBubbles.iterator()
        while (it.hasNext()) {
            val tv = it.next().get() ?: run { it.remove(); null } ?: continue
            val c = tv.tag as? String ?: continue
            tv.text = renderUserContent(c)
        }
    }
    internal var modelPopup: PopupWindow? = null
    internal var modelClosing = false   // 弹窗收起动画进行中, 防重复触发
    internal var attachPopup: PopupWindow? = null
    internal var attachClosing = false  // 附件弹窗收起动画进行中, 防重复触发
    private lateinit var sendBtn: Button
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
    private val BUBBLE_USER = Color.parseColor("#0B93F6")   // 用户: 深蓝
    private val BUBBLE_USER_TEXT = Color.WHITE
    private val SYS_TEXT = Color.parseColor("#999999")

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
        window.statusBarColor = Color.WHITE
        // 白底必须配深色状态栏图标, 否则时间/信号等白色图标在白底上不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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
        LocalEngine.onBrowserClick = { i -> browserPage.clickIndex(i) }
        LocalEngine.onBrowserType = { i, t -> browserPage.typeIndex(i, t) }
        LocalEngine.onBrowserUpload = { i, local -> browserPage.uploadIndex(i, local) }
        LocalEngine.onBrowserClear = { full -> browserPage.clearCacheForAi(full) }
        LocalEngine.onBrowserSaveCookies = { site -> browserPage.cookieStringFor(site) }
        // 自动落盘: 页面加载后检测到当前域 Cookie 变化即写入 site_auth.json(浏览器登录一次, 静默通道自动带登录态)
        browserPage.autoSaveCookie = { host, cookie ->
            val ok = runCatching {
                val jo = org.json.JSONObject()
                    .put("action", "set").put("site", host).put("cookie", cookie)
                WebTools.siteAuth(this, jo.toString())
            }.getOrNull()?.contains("已保存") == true
            if (ok && autoSavedHinted.add(host)) {
                Toast.makeText(this, "已自动保存 $host 登录态(site_auth)", Toast.LENGTH_SHORT).show()
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
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(TextView(this).apply {
            text = "☰"
            textSize = 24f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { openDrawer() }
            Ui.press(this)
        })
        titleBar.addView(TextView(this).apply {
            text = TitleConfig.mainTitle()
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { mainTitleText = it })
        titleBar.addView(TextView(this).apply {
            text = "∑"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { toggleTokenPanel() }
            Ui.press(this)
        })
        main.addView(titleBar)

        // 消息区
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scroll = ScrollView(this).apply {
            addView(chatBox)
            isFillViewport = true
            // 用户一旦手动滑动(上翻阅读), 标记后不再被自动滚动打断
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) scrollUserScrolled = true
                false
            }
        }
        applyChatBackground()
        // 内容容器: scroll+inputBar 整体, 键盘弹出时高度动画缩小 = 消息+输入框上移, 标题栏与背景不动(同微信)
        bodyWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bodyWrap.addView(scroll, LinearLayout.LayoutParams(
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
            setBackgroundColor(Color.WHITE)
        }
        input = EditText(this).apply {
            var enterHandled = false
            hint = getString(R.string.ma_hint_input)
            textSize = 15f
            // 显式声明多行文本类型: 未设 MULTI_LINE 时部分输入法会错误地把回车按两次插入
            setInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(dp(22), Color.parseColor("#EFEFF1"))
            setTextColor(Color.parseColor("#1A1A1A"))
            setHintTextColor(Color.parseColor("#B0B0B0"))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            // 回车深度拦截: 吞掉 DOWN 防止系统默认 KeyListener 先插入一次; UP/MULTIPLE 以
            // enterHandled 去重, 按下一个回车只插入单个 \n, 修正"回车换两行"
            setOnKeyListener { v, keyCode, e ->
                if (keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
                val et = v as EditText
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> { enterHandled = false; true }
                    KeyEvent.ACTION_UP -> {
                        if (!enterHandled) {
                            val st = et.selectionStart.coerceAtLeast(0)
                            val en = et.selectionEnd.coerceAtLeast(st)
                            et.text.replace(st, en, "\n")
                            et.setSelection((st + 1).coerceAtMost(et.text.length))
                            enterHandled = true
                        }
                        true
                    }
                    KeyEvent.ACTION_MULTIPLE -> {
                        if (!enterHandled) {
                            val st = et.selectionStart.coerceAtLeast(0)
                            val en = et.selectionEnd.coerceAtLeast(st)
                            et.text.replace(st, en, "\n")
                            et.setSelection((st + 1).coerceAtMost(et.text.length))
                            enterHandled = true
                        }
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
                override fun afterTextChanged(s: Editable?) { updateInputMode() }
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
            background = rounded(dp(16), Color.parseColor("#0B93F6"))
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
            background = rounded(dp(18), Color.parseColor("#E5484D"))
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
                android.util.Log.i("DroidAgent", "stop clicked, cancelRequested=${LocalEngine.cancelRequested}")
            }
            Ui.press(this)
        }
        stopSpin = ArcRingDrawable(dp(16), dp(3), Color.WHITE)
        stopBtn.setCompoundDrawablesWithIntrinsicBounds(stopSpin, null, null, null)
        attachWrap.addView(stopBtn)
        inputBar.addView(attachWrap)
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

        root.addView(main)
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
        // 右侧整屏浏览器操作页: 挂 root 最上层(Gravity.END), 右缘左滑整页推入
        root.addView(browserPage.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
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
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, DRAWER_WIDTH, v.height))
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
            var imeAnimator: android.animation.ValueAnimator? = null
            var bodyWrapFullH = 0
            root.setOnApplyWindowInsetsListener { v, insets ->
                val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                main.setPadding(0, sb.top, 0, sb.bottom)
                // 抽屉悬浮于 root 上, 不参与 main 的 insets 派发, 需自行避开状态栏/导航栏,
                // 否则 setDecorFitsSystemWindows(false) 下顶部标题栏被状态栏遮挡、底部被导航栏顶低
                if (::drawerPanel.isInitialized) drawerPanel.setPadding(0, sb.top, 0, sb.bottom)
                val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val lp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                android.util.Log.d("KBDDBG", "INSETS imeH=$imeH imeShown=$imeShown focused=${input.isFocused} bh=${bodyWrap.height}")
                if (imeH > dp(80)) {
                    // 键盘弹起改为"高度压缩"模式: bodyWrap 顶部固定贴标题栏底(整体不再上移, 不会盖住标题栏),
                    // 高度压缩到键盘顶, 底边(inputBar)精确贴键盘顶无间隙
                    if (!imeShown) {
                        bodyWrapFullH = bodyWrap.height.coerceAtLeast(1)
                        lp.weight = 0f; lp.height = bodyWrapFullH
                        bodyWrap.layoutParams = lp
                    }
                    imeShown = true
                    val tb = IntArray(2)
                    titleBar.getLocationInWindow(tb)
                    val titleBottom = tb[1] + titleBar.height
                    val targetH = ((root.height - imeH) - titleBottom).coerceAtLeast(dp(60))
                    if (imeAnimator?.isRunning == true) imeAnimator?.cancel()
                    val from = bodyWrap.height
                    imeAnimator = android.animation.ValueAnimator.ofInt(from, targetH).apply {
                        duration = 180
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                            lpp.height = it.animatedValue as Int
                            bodyWrap.layoutParams = lpp
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                // 键盘弹起收尾: 滚到最新一条, 让最新消息贴输入框上方(微信式跟随), 不会被顶出可视区
                                scrollToBottom()
                            }
                        })
                        start()
                    }
                } else if (imeShown) {
                    imeShown = false
                    if (imeAnimator?.isRunning == true) imeAnimator?.cancel()
                    val from = bodyWrap.height
                    imeAnimator = android.animation.ValueAnimator.ofInt(from, bodyWrapFullH).apply {
                        duration = 180
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                            lpp.height = it.animatedValue as Int
                            bodyWrap.layoutParams = lpp
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                val lpp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                                lpp.height = 0; lpp.weight = 1f
                                bodyWrap.layoutParams = lpp
                            }
                        })
                        start()
                    }
                    // 键盘已收起: 输入框光标跟随关闭
                    if (input.isFocused) input.clearFocus()
                }
                insets
            }
        }
        // 键盘弹起时点击输入区以外收起键盘: 在 root.dispatchTouchEvent 实现(见 root 定义处), 无其它点击监听
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendSys(getString(R.string.ma_sys_engine_started))
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
            lastModeValue = ModeConfig.modeValue()
            maybeSaveCurrent()
            messages.clear()
            chatBox.removeAllViews()
            currentSaved = true
            currentSessionId = null
            currentSessionTitle = null
            val recent = db.listSessions(1, ModeConfig.modeValue())
            if (recent.isNotEmpty() && db.loadSessionMessages(recent[0].id).isNotEmpty()) {
                openSession(recent[0].id)
            } else {
                appendSys(getString(R.string.ma_sys_engine_started))
                refreshSessionList()
            }
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
            else -> android.graphics.drawable.ColorDrawable(Color.parseColor("#F7F7F8"))
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

    internal fun startNewSession() {
        // AI 正在输出时切会话: 先取消引擎, 防止其把未完成的回复写进新会话历史
        if (aiBusy) LocalEngine.requestCancel()
        maybeSaveCurrent()
        messages.clear()
        sessionBaseSeq = 0
        chatBox.removeAllViews()
        currentSaved = true
        currentSessionId = null
        currentSessionTitle = null
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendSys(getString(R.string.ma_sys_engine_started))
        refreshSessionList()
        closeDrawer()
    }

    internal fun openSession(id: Long, locateSeq: Int? = null) {
        // AI 正在输出时切会话: 先取消引擎, 防止其把未完成的回复写进新会话历史
        if (aiBusy) LocalEngine.requestCancel()
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
        chatBox.removeAllViews()
        if (sessionBaseSeq > 0) {
            appendSys(getString(R.string.ma_sys_window_hint, MEM_WINDOW))
        }
        msgs.forEachIndexed { i, m ->
            val v = when {
                m.role == "user" -> chatWrap(bubble(m.content, true), true)
                m.thinking.isNotBlank() -> aiBubbleWithThinking(m.thinking, m.content, m.tools, m.timeline)
                else -> chatWrap(bubble(m.content, false), false)
            }
            // 按 DB 全局 seq 打 tag(窗口化时 tag=baseSeq+i), 供搜索命中后精准定位滚动
            v.tag = sessionBaseSeq + i
            chatBox.addView(v)
        }
        if (locateSeq != null) {
            // 定位到命中消息, 短暂高亮提示
            scroll.post {
                val target = chatBox.getChildAt(locateSeq - sessionBaseSeq)
                if (target != null) {
                    val top = target.top - scroll.height / 3
                    scroll.smoothScrollTo(0, top.coerceAtLeast(0))
                    val orig = target.alpha
                    target.animate().alpha(0.25f).setDuration(180).withEndAction {
                        target.animate().alpha(orig).setDuration(500).start()
                    }.start()
                } else if (locateSeq < sessionBaseSeq) {
                    Toast.makeText(this, R.string.toast_loaded_far_history, Toast.LENGTH_LONG).show()
                    // 窗口外命中: 临时全量加载该会话(仅本次, 定位后恢复窗口) —— 直接回退到旧行为一次
                    chatBox.removeAllViews()
                    sessionBaseSeq = 0
                    val full = db.loadSessionMessages(id)
                    messages.clear(); messages.addAll(full)
                    full.forEachIndexed { i, m ->
                        val v = when {
                            m.role == "user" -> chatWrap(bubble(m.content, true), true)
                            m.thinking.isNotBlank() -> aiBubbleWithThinking(m.thinking, m.content, m.tools, m.timeline)
                            else -> chatWrap(bubble(m.content, false), false)
                        }
                        v.tag = i
                        chatBox.addView(v)
                    }
                    val t2 = chatBox.getChildAt(locateSeq)
                    if (t2 != null) {
                        val top = t2.top - scroll.height / 3
                        scroll.smoothScrollTo(0, top.coerceAtLeast(0))
                        val orig = t2.alpha
                        t2.animate().alpha(0.25f).setDuration(180).withEndAction {
                            t2.animate().alpha(orig).setDuration(500).start()
                        }.start()
                    }
                }
            }
        } else {
            scrollToBottom()
        }
        refreshSessionList()
        closeDrawer()
    }

    /** 换头像后刷新当前会话旧气泡: 头像版本戳变化时按 messages 重建 chatBox, 保留滚动位置 */
    private fun refreshChatAvatars() {
        val stamp = AvatarConfig.avatarStamp()
        if (stamp == lastAvatarStamp) return
        lastAvatarStamp = stamp
        // AI 正在输出时跳过, 避免打断流式渲染(其后的新气泡自然使用新头像)
        if (aiBusy || messages.isEmpty() || !::chatBox.isInitialized) return
        val y = scroll.scrollY
        chatBox.removeAllViews()
        messages.forEachIndexed { i, m ->
            val v = when {
                m.role == "user" -> chatWrap(bubble(m.content, true), true)
                m.thinking.isNotBlank() -> aiBubbleWithThinking(m.thinking, m.content, m.tools, m.timeline)
                else -> chatWrap(bubble(m.content, false), false)
            }
            v.tag = i
            chatBox.addView(v)
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun maybeSaveCurrent() {
        if (!currentSaved && messages.isNotEmpty()) {
            val title = messages.firstOrNull { it.role == "user" }?.content
                ?.replace("\n", " ")?.take(20) ?: getString(R.string.ma_unnamed_session)
            val sid = currentSessionId
            if (sid != null) {
                db.updateSession(sid, title, messages, ModeConfig.modeValue(), sessionBaseSeq)
            } else {
                currentSessionId = db.saveSession(title, messages, ModeConfig.modeValue())
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
                setTextColor(Color.parseColor("#BBBBBB"))
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
                    setTextColor(Color.parseColor("#1A1A1A"))
                })
                addView(TextView(this@MainActivity).apply {
                    text = (if (s.pinned) getString(R.string.ma_pinned_prefix) else "") + fmtTime(s.updatedAt)
                    textSize = 11f
                    setTextColor(Color.parseColor("#AAAAAA"))
                    setPadding(0, dp(2), 0, 0)
                })
            })
            sessionList.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#F0F0F2"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }




    override fun onBackPressed() {
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
    private fun currentModelSupportsVoice(): Boolean =
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
    private fun updateInputMode() {
        applyInputMode()
    }

    private fun doSend(attachments: List<LocalEngine.Attachment>) {
        val text = input.text.toString().trim()
        android.util.Log.i("DroidAgent", "onSend text=[$text] aiBusy=$aiBusy attachments=${attachments.size}")
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
                        AttachmentStore.save(this@MainActivity, a.name, a.mime, Base64.decode(a.base64, Base64.NO_WRAP))
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

        // 文档类附件本地解析文本: 全部注入 history(而非仅第一个), 模型据此理解文档内容
        // 防炸: 单附件截断到 MAX_ATTACH_TEXT, 全部附件累计截断到 MAX_DOC_TOTAL
        val docParts = ArrayList<String>()
        var docTotal = 0
        for (a in attachments) {
            val t = a.text ?: ""
            if (t.isBlank()) continue
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
        val docTexts = docParts.joinToString("\n")
        val history = if (docTexts.isBlank()) buildHistory() else buildHistory() + "\n$docTexts\n"
        aiBusy = true
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
                holder.attach(chatBox)
                holder.showStatus(getString(R.string.ma_thinking))
                scrollToBottom()
            }
            LocalEngine.chat(this@MainActivity, history, object : LocalEngine.Callback {
                override fun onThinkingStart() {
                    LogStore.i(LogStore.MAIN, "开始思考")
                    AITerminal.push("thinking", "开始思考…")
                    debugSseSink?.invoke("thinking_start", "")
                    uiScope.launch { holder.showThinking(getString(R.string.ma_thinking_prefix)) }
                }
                override fun onThinkingDelta(text: String) {
                    debugSseSink?.invoke("thinking", text)
                    uiScope.launch { holder.appendThinking(text); scrollToBottom() }
                }
                override fun onThinkingEnd() {
                    AITerminal.push("thinking", "思考结束，进入作答")
                    debugSseSink?.invoke("thinking_end", "")
                    uiScope.launch { holder.collapseThinking() }
                }
                override fun onTool(name: String, arg: String) {
                    LogStore.i(LogStore.MAIN, "调用工具: $name")
                    AITerminal.push("tool", "$name $arg")
                    debugSseSink?.invoke("tool", "$name|$arg")
                    uiScope.launch {
                        holder.showTool(name, arg)
                        // 进入工具调用即表示本段思考已结束: 折叠思考区, 避免一直停在"思考中"
                        holder.collapseThinking()
                        scrollToBottom()
                    }
                }
                override fun onToolResult(name: String, result: String) {
                    LogStore.i(LogStore.MAIN, "工具结果: $name")
                    AITerminal.push("tool_result", "$name → ${result.trim()}")
                    debugSseSink?.invoke("tool_result", "$name|$result")
                    uiScope.launch { holder.setToolResult(name, result) }
                }
                override fun onDelta(text: String) {
                    android.util.Log.i("DroidAgent", "onDelta=[$text]")
                    AITerminal.push("delta", text)
                    debugSseSink?.invoke("delta", text)
                    uiScope.launch { holder.appendContent(text); scrollToBottom() }
                }
                override fun onDone(reply: String) {
                    android.util.Log.i("DroidAgent", "onDone len=${reply.length}")
                    if (LocalEngine.cancelRequested) {
                        LogStore.w(LogStore.MAIN, "用户停止输出")
                        AITerminal.push("stop", "已停止")
                    } else {
                        LogStore.i(LogStore.MAIN, "回复完成 len=${reply.length} 会话=$replySessionId")
                        AITerminal.push("done", "回复完成 len=${reply.length}")
                    }
                    uiScope.launch {
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
                            }
                        }
                            // 兜底: 引擎重试后仍无正文时给出明确提示, 避免"思考了但没输出"静默空白
                            if (reply.isBlank()) appendSys(getString(R.string.ma_sys_no_reply))
                        holder.finishContent()
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                        stopSpinAnim?.cancel()
                        scrollToBottom()
                    }
                    debugSseSink?.invoke("done", reply)
                    debugChatDone?.invoke()
                }
                override fun onError(msg: String) {
                    LogStore.e(LogStore.MAIN, "错误: $msg")
                    AITerminal.push("error", msg)
                    uiScope.launch {
                        holder.showError(getString(R.string.ma_error_fmt, msg))
                        LocalEngine.cancelRequested = false
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                        stopSpinAnim?.cancel()
                        scrollToBottom()
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
    internal fun submitDebugChat(text: String, onDone: () -> Unit): Boolean {
        if (aiBusy) return false
        debugChatDone = onDone
        TokenStore.currentSessionId = currentSessionId
        LocalEngine.cancelRequested = false
        aiBusy = true
        continueSend(text, listOf(text), emptyList())
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
        val v = chatWrap(bubble(content, isUser = true), true)
        chatBox.addView(v)
        enterBubble(v)
        scrollToBottom()
    }

    private fun appendSys(content: String) {
        val v = TextView(this).apply {
            text = content
            textSize = 12f
            setTextColor(SYS_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        chatBox.addView(v)
        enterBubble(v)
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
            background = if (edgeImage) null else rounded(dp(14), if (isUser) BUBBLE_USER else BUBBLE_AI)
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
        val tb = decodeVideoThumbnail(f, resources.displayMetrics.density, this, allowGrab = false)
        val dims = if (tb != null) null else videoDimensionsFast(f)
        var w = tb?.width ?: dims?.first ?: 0
        var h = tb?.height ?: dims?.second ?: 0
        if (w <= 0 || h <= 0) { w = dp(200); h = (w * 9 / 16).coerceAtLeast(dp(60)) }  // 兜底 16:9
        val maxH = dp(340)
        if (w > maxW || h > maxH) {
            val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h, 1f)
            w = (w * scale).toInt().coerceAtLeast(dp(80))
            h = (h * scale).toInt().coerceAtLeast(dp(60))
        }
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
            background = rounded(dp(14), if (isUser) BUBBLE_USER else BUBBLE_AI)
            clipToOutline = true
            addView(pv)
            // 点击整块进全屏弹窗预览(弹窗内同样循环播放)
            setOnClickListener { this@MainActivity.openAttachmentPreview(listOf(file), 0) }
            // 生命周期: 视图从窗口 detach(会话重建/滚动回收)即释放播放器, 防止内存/解码泄漏
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    try { exo.play() } catch (_: Exception) {}
                }
                override fun onViewDetachedFromWindow(v: View) {
                    try { exo.release() } catch (_: Exception) {}
                }
            })
        }
        return frame
    }

    /** 用户气泡渲染: [图片](att://file) 直接内嵌缩略图(点击打开原图); 其他附件保持蓝色链接, 其余文本原样 */
    private fun renderUserContent(content: String): CharSequence {
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
                android.util.Log.i("DroidAgent", "renderAtt file=$file mime=$mime exists=${f != null} bmp=${bmp != null} vtb=${vtb != null}")
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
    /** AI 头像: 圆形深灰蓝底 + 当前供应商首字符, 放气泡上方(与 MD 排版解耦) */
    private fun aiAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val custom = AvatarConfig.avatarDrawable(AvatarConfig.aiAvatarFile(), s)
        if (custom != null) {
            background = custom
        } else {
            val label = ApiConfig.providerLabel(ApiConfig.providerId())
            text = (label.take(1).ifBlank { "A" }).uppercase()
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
    }

    /** 用户头像: 圆形蓝底"我", 聊天模式用户消息右侧并排 */
    private fun userAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val custom = AvatarConfig.avatarDrawable(AvatarConfig.userAvatarFile(), s)
        if (custom != null) {
            background = custom
        } else {
            text = "我"
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
            gravity = if (isUser) Gravity.END else Gravity.START
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
            this.text = "💭 已思考${count}字，点按展开"
            textSize = 14f
            setTextColor(THINK_TEXT)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(dp(10), THINK_BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            maxWidth = maxW
            setOnClickListener {
                localExpanded = !localExpanded
                this.text = if (localExpanded) "💭 $text" else "💭 已思考${count}字，点按展开"
            }
            // 交互行不启用 textIsSelectable, 保证首次点击即展开(否则被选择机制吞掉需点两次)
        }
        // 工具折叠气泡(一次工具调用独立一行, 点击展开参数与结果) —— 与流式 ToolBlock 一致
        fun toolRow(name: String, arg: String, result: String): TextView = TextView(this@MainActivity).apply {
            this.text = "🔧 工具：$name"
            textSize = 14f
            setTextColor(THINK_TEXT)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(dp(10), THINK_BG)
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
        // 正文独立气泡(浅色背景, 不折叠), 与流式 appendContent 一致
        fun contentRow(): TextView = TextView(this@MainActivity).apply {
            textSize = 15f
            val renderContent = ModeConfig.stripChatProtocolPrefix(content)
            if (ModeConfig.chatPlainText()) {
                text = renderContent.trimEnd()
            } else {
                // 先设基础字号再渲染 Markdown, 保证 HeadingSpan 的倍率基于正确 textSize 生效
                markwon.setMarkdown(this, renderContent)
            }
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(BUBBLE_AI_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(dp(12), BUBBLE_AI)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            maxWidth = maxW
            makeCopyable(this) { content }
        }
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
                        "content" -> if (content.isNotBlank()) { addView(chatWrap(contentRow(), false)); contentPlaced = true }
                    }
                }
                // 兜底: timeline 无 content 事件但正文非空(旧数据), 追加末尾
                if (!contentPlaced && content.isNotBlank()) addView(chatWrap(contentRow(), false))
            } else {
                // 旧数据回退: 无 timeline 时按历史行为 思考折叠区 + 全部工具行 + 正文
                if (thinking.isNotBlank()) {
                    addView(chatWrap(thinkingRow(thinking), false))
                }
                parseTools(toolsJson).forEach { (name, arg, result) ->
                    addView(chatWrap(toolRow(name, arg, result), false))
                }
                if (content.isNotBlank()) addView(chatWrap(contentRow(), false))
            }
        }
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

    private var scrollPending = false
    internal fun scrollToBottom() {
        // 不能用 fullScroll(FOCUS_DOWN): 它会把焦点交给滚动方向上第一个可聚焦子 view(气泡 setTextIsSelectable 后可聚焦),
        // 导致发送后焦点被气泡抢走、输入框失焦无法继续打字。改用 scrollTo 纯滚动不碰焦点。
        // 时序修正: setText 后立即 post 滚动会读到旧高度, 布局完成高度变化后再滚到新位置, 来回交错造成"变长又缩回"抖动;
        // 改为布局完成后(OnGlobalLayout)再滚动, 一次到位; 同帧多次调用合并, 避免 post 堆积。
        // 兜底: 冷启动恢复会话时若 ScrollView 此刻无待布局事件, OnGlobalLayout 不触发(无 dirty),
        // 气泡渲染完也不滚动 → 重启后停在历史顶部; 故延时后直接落底, 保证"重启后停在最新消息"。
        if (scrollPending) return
        scrollPending = true
        val v = scroll
        val h = android.os.Handler(Looper.getMainLooper())
        var lastBottom = -1
        // 瞬移落底(无动画): 仅在布局回调内/首帧绘制前执行, 用户看不到顶部, 无"蹦"的跳变
        fun snap() {
            val child = v.getChildAt(0) ?: return
            lastBottom = child.bottom
            val maxY = (child.bottom - v.height).coerceAtLeast(0)
            if (maxY != v.scrollY) v.scrollTo(0, maxY)
        }
        // 平滑修正(有动画): 渲染分帧导致底部高度后移时温和滚过去, 避免硬跳;
        // 高度无变化或用户已手动滑动则不再干预
        fun ease() {
            val child = v.getChildAt(0) ?: return
            if (child.bottom == lastBottom) { if (scrollPending) scrollPending = false; return }
            lastBottom = child.bottom
            if (scrollUserScrolled) { if (scrollPending) scrollPending = false; return }
            val maxY = (child.bottom - v.height).coerceAtLeast(0)
            if (maxY != v.scrollY) v.smoothScrollTo(0, maxY)
        }
        v.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (scrollPending) { scrollPending = false; snap() }
            }
        })
        // 首帧兜底 + 多档平滑兜底: 冷启动恢复会话若 OnGlobalLayout 未触发或无 dirty,
        // 由 post/smooth 依次温和落底, 保证"重启后停在最新消息"且无瞬跳感
        v.post { snap() }
        h.postDelayed({ ease() }, 250)
        h.postDelayed({ ease() }, 700)
        h.postDelayed({ ease() }, 1500)
    }

    /** 展开/收起气泡时保持当前阅读位置: 记录某 view 顶部相对视口的偏移, 布局变化后恢复滚动, 避免 ScrollView 内容高度骤变被 clamp 回底部 */
    internal fun keepReadingPosition(view: View) {
        val relTop = view.top - scroll.scrollY   // 展开前该气泡顶部相对视口顶部偏移
        scroll.post {
            val child = scroll.getChildAt(0) ?: return@post
            val maxY = (child.bottom - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, (view.top - relTop).coerceIn(0, maxY))
        }
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
            setTextColor(0xFF1F2328.toInt())
            setTextIsSelectable(true)
            setHighlightColor(0x6633B5E5)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            typeface = Typeface.MONOSPACE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0xFFFAFAFA.toInt())
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
    private fun toggleVoiceMode() {
        if (speaking) return
        if (!voiceMode && !currentModelSupportsVoice()) return
        voiceMode = !voiceMode
        if (voiceMode) {
            // input 用 INVISIBLE 而非 GONE: 仍在 inputArea 中占位, 收起时切回 VISIBLE 不触发重排, 无闪框
            input.visibility = View.INVISIBLE
            speakBar.visibility = View.VISIBLE
            // 仿搜索框展开动画: 回弹极低, 突出展开过程; 锚定右边缘(切换按钮侧), 从右往左展开
            speakBar.scaleX = 0.3f
            speakBar.alpha = 1f
            speakBar.post {
                speakBar.pivotX = speakBar.width.toFloat()
                speakBar.animate().scaleX(1f).setDuration(300)
                    .setInterpolator(OvershootInterpolator(0.1f)).withLayer().start()
            }
            micBtn.background = micIconBg(false, true, resources.displayMetrics.density)
            sendBtn.visibility = View.GONE
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        } else {
            // 收起: 动画期间不动文本/背景(避免动画前同步 resetSpeakBar 触发重绘卡顿),
            // 从右往左缩回+淡出结束后再复位胶囊并恢复输入框
            val onEnd = {
                speakBar.visibility = View.GONE
                speakBar.scaleX = 1f
                speakBar.alpha = 1f
                resetSpeakBar()
                input.visibility = View.VISIBLE
                micBtn.background = micIconBg(false, false, resources.displayMetrics.density)
                updateInputMode()
            }
            speakBar.pivotX = speakBar.width.toFloat()
            speakBar.animate().scaleX(0.3f).alpha(0f).setDuration(220)
                .setInterpolator(OvershootInterpolator(0.1f))
                .withEndAction { onEnd() }.start()
        }
    }

    /** 按住说话手势: 按下开始录音, 上滑进入取消区, 松手发送/取消 */
    private fun handleSpeakTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.toast_req_mic_perm, Toast.LENGTH_SHORT).show()
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD)
                    return true
                }
                speakCancel = false
                speaking = true
                startRecording()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!speaking) return true
                val loc = IntArray(2)
                speakBar.getLocationOnScreen(loc)
                val cancelZone = ev.rawY < loc[1] - dp(90)
                if (cancelZone != speakCancel) {
                    speakCancel = cancelZone
                    speakBar.text = if (speakCancel) "松开手指，取消发送" else "松开 发送"
                    speakBar.background = rounded(dp(22),
                        if (speakCancel) Color.parseColor("#9AA0A6") else Color.parseColor("#07C160"))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!speaking) return true
                if (speakCancel) discardRecording() else finishSpeakAndSend()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (speaking) discardRecording()
            }
        }
        return true
    }

    /** 开始录音(语音模式): 计时显示在按住说话条, 超 60 秒自动发送 */
    private fun startRecording() {
        try {
            // 录音统一 AudioRecord 采 PCM16 单声道 44100Hz, 发送前封装 WAV:
            // 云端多模态 API 仅接受 mp3/flac/m4a/wav/ogg; MediaRecorder 的 MPEG_4+AAC 是 mp4 容器
            // (冒充 m4a 被 400 拒), OGG/VORBIS 又因设备 HAL 不支持 start 失败, 故 AudioRecord 最稳
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf * 2, 8192)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioRecord init failed")
            }
            val pcm = ByteArrayOutputStream()
            ar.startRecording()
            audioRecord = ar
            recPcm = pcm
            recStop = false
            recStartMs = System.currentTimeMillis()
            recThread = Thread {
                val buf = ByteArray(bufSize)
                try {
                    while (!recStop) {
                        val n = ar.read(buf, 0, buf.size)
                        if (n > 0) pcm.write(buf, 0, n)
                        else if (n < 0) break
                    }
                } catch (e: Exception) { /* 停止时 read 抛错: 忽略 */ }
            }.also { it.isDaemon = true; it.start() }
            speakBar.text = "松开 发送"
            speakBar.background = rounded(dp(22), Color.parseColor("#07C160"))
            // 复用同一成员 Handler 入队: 复位时才能用 removeCallbacks 停表(target 匹配)
            recHandler = Handler(Looper.getMainLooper())
            val handler = recHandler!!
            recTimer = object : Runnable {
                override fun run() {
                    val elapsed = System.currentTimeMillis() - recStartMs
                    // 取消态下文字保持"松开手指，取消发送", 不被计时器覆盖
                    if (!speakCancel && elapsed >= 1000) speakBar.text = "松开 发送 ${elapsed / 1000}s"
                    if (elapsed >= MAX_RECORD_MS) {
                        Toast.makeText(this@MainActivity, R.string.toast_voice_60s, Toast.LENGTH_SHORT).show()
                        finishSpeakAndSend()
                    } else handler.postDelayed(this, 200)
                }
            }
            handler.postDelayed(recTimer!!, 200)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_rec_start_fail, e.message), Toast.LENGTH_SHORT).show()
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    /** 结束录音并整理结果: 过短(<1秒)/超限(10MB)/失败返回 null, 正常返回(字节,文件名,时长) */
    private fun finalizeRecord(): Triple<ByteArray, String, Long>? {
        val ar = audioRecord ?: return null
        val startMs = recStartMs
        recStop = true
        recThread?.join(1000)
        recThread = null
        audioRecord = null
        recTimer?.let { recHandler?.removeCallbacks(it) }
        recTimer = null
        recHandler = null
        try { ar.stop() } catch (e: Exception) { /* 过短时 stop 抛错: 静默丢弃 */ }
        try { ar.release() } catch (e: Exception) {}
        val pcm = recPcm?.toByteArray()
        recPcm = null
        val durationMs = System.currentTimeMillis() - startMs
        if (pcm == null || pcm.size == 0) return null
        if (durationMs < 1000) return null
        if (pcm.size > MAX_AUDIO_BYTES - 44) return null
        val bytes = toWav(pcm, 44100)
        return Triple(bytes, "语音_${System.currentTimeMillis()}.wav", durationMs)
    }

    /** PCM16 单声道 → WAV 封装 (已抽离 UiKit.toWav) */

    /** 松手发送: 直接作为语音消息发送, 不进附件预览条 */
    private fun finishSpeakAndSend() {
        // 先复位说话条再发送: 发送链路偶发异常时也不会残留"松开 发送"录音态
        resetSpeakBar()
        val res = finalizeRecord()
        if (res == null) {
            Toast.makeText(this, R.string.toast_voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        val (bytes, name, durationMs) = res
        if (aiBusy) {
            Toast.makeText(this, R.string.toast_ai_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val att = LocalEngine.Attachment(
            mime = "audio/wav",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            name = name,
            text = "[语音消息]",
            isVoice = true
        )
        try {
            doSend(listOf(att))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_voice_send_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** 取消发送: 停止并删除录音, 重置说话条 */
    private fun discardRecording() {
        val ar = audioRecord ?: run { resetSpeakBar(); return }
        recStop = true
        recThread?.join(1000)
        recThread = null
        audioRecord = null
        recPcm = null
        recTimer?.let { recHandler?.removeCallbacks(it) }
        recTimer = null
        recHandler = null
        try { ar.stop() } catch (e: Exception) {}
        try { ar.release() } catch (e: Exception) {}
        resetSpeakBar()
    }

    /** 说话条复位到待命态 */
    private fun resetSpeakBar() {
        // 必须停表: recTimer 每 200ms 会把文本改回"松开 发送 Ns", 不清掉松手后会继续残留计时
        recTimer?.let { recHandler?.removeCallbacks(it) }
        recTimer = null
        recHandler = null
        speaking = false
        speakCancel = false
        speakBar.text = getString(R.string.ma_hold_to_speak)
        speakBar.background = rounded(dp(22), Color.parseColor("#9AA0A6"))
    }

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

    /** 音频附件点击: 播放/停止当前 m4a 文件 */
    private fun togglePlayAudio(fileName: String) {
        if (playingFileName == fileName && audioPlayer?.isPlaying == true) {
            audioPlayer?.stop()
            playingFileName = null
            stopWaveAnim()
            animateVoiceBubble(fileName, false)
            refreshAudioBubbles()
            return
        }
        audioPlayer?.release()
        audioPlayer = null
        playingFileName = null
        val f = AttachmentStore.fileOf(this, fileName) ?: return
        try {
            val p = MediaPlayer()
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener {
                it.release()
                if (audioPlayer === it) { audioPlayer = null; playingFileName = null }
                uiScope.launch { stopWaveAnim(); animateVoiceBubble(fileName, false); refreshAudioBubbles() }
            }
            p.prepare()
            p.start()
            audioPlayer = p
            playingFileName = fileName
            startWaveAnim()
            animateVoiceBubble(fileName, true)
            refreshAudioBubbles()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_play_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** 语音气泡播放动画: 播放时整体轻微缩小(0.96)后回弹循环, 停止/播完恢复原尺寸 */
    private fun animateVoiceBubble(fileName: String, playing: Boolean) {
        for (wr in audioBubbles) {
            val tv = wr.get() ?: continue
            val c = tv.tag as? String ?: continue
            val fname = Regex("""\(att://([^)]+)\)""").find(c)?.groupValues?.get(1) ?: continue
            if (fname != fileName) continue
            tv.clearAnimation()
            tv.scaleX = 1f
            tv.scaleY = 1f
            if (playing) {
                tv.startAnimation(android.view.animation.ScaleAnimation(
                    1f, 0.96f, 1f, 0.96f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 220
                    repeatCount = 2
                    repeatMode = android.view.animation.Animation.REVERSE
                    interpolator = android.view.animation.DecelerateInterpolator()
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
