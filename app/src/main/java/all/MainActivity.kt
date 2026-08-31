package io.github.aixtin.nyral

import android.app.Activity
import android.app.Dialog
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Base64
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
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
import android.widget.SeekBar
import android.widget.VideoView
import android.widget.Toast
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
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Code
import android.text.style.TypefaceSpan

/** 帧驱动打字机回调接口与全局打字机中心已抽离至 Typewriter.kt */

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    // Markdown 本地渲染 (Markwon, 开源/无网络/不接第三方服务)
    private val markwon by lazy {
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
            })
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()
    }
    // role, content, thinking(assistant 思考内容, 持久化到会话以便切回时恢复思考区), tools(工具调用序列 JSON)
    private val messages = mutableListOf<MemoryDb.SessionMsg>()
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var root: FrameLayout
    private lateinit var bodyWrap: LinearLayout
    private lateinit var input: EditText
    internal lateinit var modelBtn: Button
    internal lateinit var attachBtn: Button
    private lateinit var attachWrap: FrameLayout
    // 附件预览条: 选中附件先进入输入框上方预览, 补文字后一并发送(仿主流IM)
    private lateinit var attachPreviewWrap: HorizontalScrollView
    private lateinit var attachPreviewRow: LinearLayout
    private val pendingAttachments = mutableListOf<LocalEngine.Attachment>()
    // 附件选择请求码
    private val REQ_IMAGE = 1001
    private val REQ_FILE = 1002
    private val REQ_AUDIO = 1003
    private val REQ_VIDEO = 1004
    private val REQ_RECORD = 1005
    private val REQ_NOTIF = 1006  // Android 13+ 通知权限(前台服务通知展示用)
    // 图片压缩上限: 最长边/质量
    private val MAX_IMAGE_SIDE = 2048
    private val MAX_FILE_BYTES = 20 * 1024 * 1024
    private val MAX_VIDEO_BYTES = 37 * 1024 * 1024 // MiMo 视频 base64 ≤50MB(原始约 ≤37MB)
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
    private var summary: String? = null
    internal lateinit var db: MemoryDb
    private var aiBusy = false
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

    // 气泡配色
    private val BUBBLE_USER = Color.parseColor("#0B93F6")   // 用户: 深蓝
    private val BUBBLE_USER_TEXT = Color.WHITE
    private val BUBBLE_AI = Color.parseColor("#F1F2F4")     // AI: 浅灰
    private val BUBBLE_AI_TEXT = Color.parseColor("#1A1A1A")
    private val THINK_TEXT = Color.parseColor("#8A8A8A")    // 思考文字(比正文稍浅)
    private val THINK_BG = Color.parseColor("#E7E8EA")       // 思考背景(比 AI 正文浅灰略暗)
    private val SYS_TEXT = Color.parseColor("#999999")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动自动检查更新（同一天仅一次，静默；真实更新源开源后替换 UPDATE_URL 即可）
        UpdateChecker.check(this, false)
        window.statusBarColor = Color.WHITE
        // 白底必须配深色状态栏图标, 否则时间/信号等白色图标在白底上不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        ApiConfig.init(this)
        MemoryApiConfig.init(this)   // 辅助模型配置: 冷启动必须先初始化, 否则归档读不到独立配置, 回退主对话
        TitleConfig.init(this)
        LogStore.init(this)
        MemoryKeeper.init(this)
        db = MemoryDb(this)
        summary = db.loadSummary()
        // 记忆落库时快照当前会话标题, 长期记忆卡片按会话名分组展示
        MemoryTools.sessionTitleProvider = { currentSessionTitle }

        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) * 1.5f && abs(dx) > dp(60).toFloat() && abs(velocityX) > 500f) {
                    if (dx < 0 && drawerOpen) closeDrawer()
                    else if (dx > 0 && !drawerOpen) openDrawer()
                    return true
                }
                return false
            }
        })

        root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                swipeDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#F7F7F8"))
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 透明: 让挂在 root 的聊天背景(预设渐变/自定义图)透出来, 否则不透明底色会盖住背景
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
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        input = EditText(this).apply {
            hint = "输入消息..."
            textSize = 15f
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(dp(22), Color.parseColor("#EFEFF1"))
            setTextColor(Color.parseColor("#1A1A1A"))
            setHintTextColor(Color.parseColor("#B0B0B0"))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
            }
            // 底栏交互: 输入文字时隐藏麦克风显示发送按钮, 清空后恢复麦克风
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
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
            text = "按住 说话"
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
        val micWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
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
                marginEnd = dp(4)
                // 底边距由下方 onGlobalLayout 统一精确计算, 此处只给初始占位值
                bottomMargin = dp(10)
            }
            // 仅当前模型支持语音时展示(预设查内置表, 手动按配置勾选)
            visibility = if (currentModelSupportsVoice()) View.VISIBLE else View.GONE
            setOnClickListener { toggleVoiceMode() }
        }
        micWrap.addView(micBtn)
        inputBar.addView(micWrap)
        // 附件入口(+): 输入框与发送按钮之间, 点击弹出相册/文件/音频
        attachWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
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
                marginEnd = dp(4)
                // 底边距由 onGlobalLayout 统一计算, 此处只给初始占位值
                bottomMargin = dp(10)
            }
            setOnClickListener { showAttachSheet() }
        }
        attachWrap.addView(attachBtn)
        inputBar.addView(attachWrap)
        // 按钮容器: 高度跟随输入框, 按钮自身 layout_gravity=BOTTOM 钉死在右下角
        // 容器跟随输入区高度; 按钮用layout_gravity=BOTTOM+marginBottom=10dp钉死:
        // 单行时与输入框居中, 多行时相对屏幕底部位置不变(不会跑到右上角)
        val btnWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        sendBtn = Button(this).apply {
            text = "发送"
            textSize = 13f
            isAllCaps = false
            // 取消系统默认 minHeight(48dp)/minWidth, 否则气泡被撑大
            minHeight = 0
            minWidth = 0
            setTextColor(Color.WHITE)
            background = rounded(dp(16), Color.parseColor("#0B93F6"))
            setPadding(dp(2), dp(5), dp(2), dp(5))
            // 显式固定宽高(宽度砍至约 3/4, 不再随文字撑宽); 相对底部10dp: 单行与输入框居中, 多行时位置不变
            layoutParams = FrameLayout.LayoutParams(
                dp(44), dp(36), Gravity.BOTTOM).apply {
                marginStart = dp(4)
                bottomMargin = dp(10)
            }
            // 默认隐藏发送按钮, 输入文字时切换显示 (见 updateInputMode)
            visibility = View.GONE
            setOnClickListener { onSend() }
            Ui.press(this)
        }
        btnWrap.addView(sendBtn)
        stopBtn = Button(this).apply {
            text = "■ 停止"
            textSize = 13f
            isAllCaps = false
            // 取消系统默认 minHeight(48dp)/minWidth
            minHeight = 0
            minWidth = 0
            setTextColor(Color.WHITE)
            background = rounded(dp(16), Color.parseColor("#E5484D"))
            setPadding(dp(2), dp(5), dp(2), dp(5))
            visibility = View.GONE
            // 显式固定高度, 与发送按钮一致, 同样相对底部固定
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.BOTTOM).apply {
                marginStart = dp(4)
                bottomMargin = dp(10)
            }
            setOnClickListener {
                LocalEngine.requestCancel()
                stopBtn.isEnabled = false
                stopBtn.text = "停止中..."
                stopSpinAnim?.cancel()
                android.util.Log.i("agent", "stop clicked, cancelRequested=${LocalEngine.cancelRequested}")
            }
            Ui.press(this)
        }
        stopSpin = ArcRingDrawable(dp(16), dp(3), Color.WHITE)
        stopBtn.setCompoundDrawablesWithIntrinsicBounds(stopSpin, null, null, null)
        btnWrap.addView(stopBtn)
        inputBar.addView(btnWrap)
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
        setContentView(root)
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
                    setMargin(attachBtn)
                    setMargin(sendBtn)
                    setMargin(stopBtn)
                    inputBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
        // 输入法平滑过渡动画: adjustNothing 下手动检测键盘高度, ValueAnimator 驱动 bodyWrap 高度渐变,
        // 消息区+输入框整体上移(同微信), 标题栏与背景(挂 root.background)不动
        var kbdShown = false
        var kbdAnimator: android.animation.ValueAnimator? = null
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val r = android.graphics.Rect()
                root.getWindowVisibleDisplayFrame(r)
                val visH = r.bottom - r.top
                val diff = root.height - visH
                val lp = bodyWrap.layoutParams as LinearLayout.LayoutParams
                if (diff > dp(80)) {
                    // 键盘弹出
                    if (!kbdShown) {
                        kbdShown = true
                        val real = bodyWrap.height.coerceAtLeast(1)
                        lp.height = real; lp.weight = 0f
                        bodyWrap.layoutParams = lp
                        kbdAnimator?.cancel()
                        val target = (real - diff).coerceAtLeast(1)
                        kbdAnimator = android.animation.ValueAnimator.ofInt(real, target).apply {
                            duration = 200
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addUpdateListener {
                                lp.height = it.animatedValue as Int
                                bodyWrap.layoutParams = lp
                            }
                            start()
                        }
                    }
                } else if (kbdShown) {
                    // 键盘收起
                    kbdShown = false
                    kbdAnimator?.cancel()
                    val from = bodyWrap.height.coerceAtLeast(1)
                    val target = (main.height - titleBar.height).coerceAtLeast(1)
                    kbdAnimator = android.animation.ValueAnimator.ofInt(from, target).apply {
                        duration = 200
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            lp.height = it.animatedValue as Int
                            bodyWrap.layoutParams = lp
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                lp.height = 0; lp.weight = 1f
                                bodyWrap.layoutParams = lp
                            }
                        })
                        start()
                    }
                }
            }
        })
        summary?.let { appendSys("已加载历史对话摘要，可继续之前的上下文。") }
        appendSys("agent 本地引擎已启动。SSH 目标请先到「SSH 配置」添加。")
        // 恢复最近一次会话，避免杀后台后聊天记录与列表丢失
        val recent = db.listSessions(1)
        if (recent.isNotEmpty() && db.loadSessionMessages(recent[0].id).isNotEmpty()) {
            openSession(recent[0].id)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置/模型配置"保存并应用"返回: 图标按钮无需刷新文字, 仅重刷背景
        applyChatBackground()
        // 从模型配置页返回: 手动模型能力勾选可能变化, 重刷语音切换按钮显隐
        refreshVoiceButton()
        // 从设置页-外观修改标题后返回, 刷新主页标题与侧栏标题
        if (::mainTitleText.isInitialized) mainTitleText.text = TitleConfig.mainTitle()
        if (::drawerTitleText.isInitialized) drawerTitleText.text = TitleConfig.drawerTitle()
        if (::drawerNoteText.isInitialized) drawerNoteText.text = TitleConfig.drawerNote()
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
        // 挂到 ScrollView 上: 背景固定, 消息在背景上滚动(类微信)
        root.background = when (type) {
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
        chatBox.removeAllViews()
        currentSaved = true
        currentSessionId = null
        currentSessionTitle = null
        summary?.let { appendSys("已加载历史对话摘要，可继续之前的上下文。") }
        appendSys("agent 本地引擎已启动。SSH 目标请先到「SSH 配置」添加。")
        refreshSessionList()
        closeDrawer()
    }

    internal fun openSession(id: Long, locateSeq: Int? = null) {
        // AI 正在输出时切会话: 先取消引擎, 防止其把未完成的回复写进新会话历史
        if (aiBusy) LocalEngine.requestCancel()
        maybeSaveCurrent()
        val msgs = db.loadSessionMessages(id)
        if (msgs.isEmpty()) {
            Toast.makeText(this, "该会话没有消息", Toast.LENGTH_SHORT).show()
            return
        }
        messages.clear()
        messages.addAll(msgs)
        currentSaved = true
        currentSessionId = id
        currentSessionTitle = db.sessionTitleOf(id)
        chatBox.removeAllViews()
        msgs.forEachIndexed { i, m ->
            val v = when {
                m.role == "user" -> bubble(m.content, true)
                m.thinking.isNotBlank() -> aiBubbleWithThinking(m.thinking, m.content, m.tools)
                else -> bubble(m.content, false)
            }
            // 按 seq 打 tag, 供搜索结果点击后精准定位滚动
            v.tag = i
            chatBox.addView(v)
        }
        if (locateSeq != null) {
            // 定位到命中消息, 短暂高亮提示
            scroll.post {
                val target = chatBox.getChildAt(locateSeq)
                if (target != null) {
                    val top = target.top - scroll.height / 3
                    scroll.smoothScrollTo(0, top.coerceAtLeast(0))
                    val orig = target.alpha
                    target.animate().alpha(0.25f).setDuration(180).withEndAction {
                        target.animate().alpha(orig).setDuration(500).start()
                    }.start()
                }
            }
        } else {
            scrollToBottom()
        }
        refreshSessionList()
        closeDrawer()
    }

    private fun maybeSaveCurrent() {
        if (!currentSaved && messages.isNotEmpty()) {
            val title = messages.firstOrNull { it.role == "user" }?.content
                ?.replace("\n", " ")?.take(20) ?: "未命名会话"
            val sid = currentSessionId
            if (sid != null) {
                db.updateSession(sid, title, messages)
            } else {
                currentSessionId = db.saveSession(title, messages)
            }
            currentSessionTitle = title
            currentSaved = true
        }
    }

    internal fun refreshSessionList() {
        sessionList.removeAllViews()
        val list = db.listSessions(20)
        if (list.isEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = "暂无历史会话\n新对话会自动保存到这里"
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
                    text = (if (s.pinned) "已置顶 · " else "") + fmtTime(s.updatedAt)
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

    /** 刷新语音切换按钮显隐: 仅当当前模型支持语音时展示; 不支持时隐藏并强制退回文字输入 */
    internal fun refreshVoiceButton() {
        if (!currentModelSupportsVoice()) {
            if (voiceMode) {
                voiceMode = false
                input.visibility = View.VISIBLE
                speakBar.visibility = View.GONE
                micBtn.background = micIconBg(false, density = resources.displayMetrics.density)
                sendBtn.visibility = if (input.text.isNotBlank()) View.VISIBLE else View.GONE
            }
            micBtn.visibility = View.GONE
        } else {
            micBtn.visibility = if (input.text.isNotBlank()) View.GONE else View.VISIBLE
        }
    }

    /** 底栏交互: 输入框有文字→隐藏麦克风显示发送按钮; 空→恢复麦克风. AI输出/语音模式期间不切换 */
    private fun updateInputMode() {
        if (aiBusy || voiceMode) return
        val hasText = input.text.isNotBlank()
        // 麦克风按钮仅在当前模型支持语音时展示
        micBtn.visibility = if (hasText || !currentModelSupportsVoice()) View.GONE else View.VISIBLE
        sendBtn.visibility = if (hasText) View.VISIBLE else View.GONE
        // 附件按钮(+): 输入文字时向左移 10px(远离发送按钮), 空输入恢复默认间距
        val am = attachWrap.layoutParams as LinearLayout.LayoutParams
        am.marginStart = if (hasText) dp(8) - 10 else dp(8)
        attachWrap.layoutParams = am
    }

    private fun doSend(attachments: List<LocalEngine.Attachment>) {
        val text = input.text.toString().trim()
        android.util.Log.i("agent", "onSend text=[$text] aiBusy=$aiBusy attachments=${attachments.size}")
        if (text.isEmpty() && attachments.isEmpty()) return
        if (aiBusy) {
            Toast.makeText(this, "AI 正在输出，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        // 会话维度 Token 统计: 引擎 record 时读取
        TokenStore.currentSessionId = currentSessionId
        // 发起新请求前清掉可能残留的取消标记(如切会话时 requestCancel 但引擎未在跑)
        LocalEngine.cancelRequested = false
        LogStore.i(LogStore.MAIN, "发送消息 len=${text.length} 附件=${attachments.size} 会话=$currentSessionId")
        // 显示文本: 附件落盘持久化(私有目录)后转可点击占位标记, 无文本时仅显示附件标记
        // 显示气泡: 附件与文字拆成两条独立气泡(图片/附件一条, 说明文字一条), 无文字时仅一条附件气泡
        val dispList = if (attachments.isEmpty()) listOf(text) else {
            // PDF 扫描件页图(pdfSourceName 非空)仅作发送附件, 不参与气泡显示, 避免多图网格"一通到底"
            val showAtts = attachments.filter { it.pdfSourceName == null }
            val marks = showAtts.map { a ->
                val fileName = try {
                    AttachmentStore.save(this, a.name, a.mime, Base64.decode(a.base64, Base64.NO_WRAP))
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
        input.setText("")
        // 发送完成: 清空附件预览条与待发列表
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
        for (d in dispList) {
            appendUser(d)
            messages.add(MemoryDb.SessionMsg("user", d, "", ""))
            MemoryKeeper.push("user", d)
        }
        currentSaved = false
        // 即时落库: 不依赖 onStop 兜底, 防止发送后进程被杀(force-stop/划掉后台)导致最后一条消息丢失
        maybeSaveCurrent()

        // 文档类附件本地解析文本: 全部注入 history(而非仅第一个), 模型据此理解文档内容
        val docTexts = attachments.filter { !it.text.isNullOrBlank() }
            .joinToString("\n") { "[附件 ${it.name} 本地解析文本内容]\n${it.text}" }
        val history = if (docTexts.isBlank()) buildHistory() else buildHistory() + "\n$docTexts\n"
        aiBusy = true
        replySessionId = currentSessionId  // 快照: 回调回来时若已切会话, 拒绝写入
        sendBtn.visibility = View.GONE
        stopBtn.visibility = View.VISIBLE
        stopBtn.isEnabled = true
        stopBtn.text = "停止"
        startStopSpin()
        // 前台服务+通知保活: 任务期间防止进程被回收; Android 13+ 先请求通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        TaskService.start(this)
        executor.execute {
            val holder = AiBubbleHolder()
            runOnUiThread {
                holder.attach(chatBox)
                holder.showStatus("正在思考...")
                scrollToBottom()
            }
            LocalEngine.chat(this@MainActivity, history, object : LocalEngine.Callback {
                override fun onThinkingStart() {
                    LogStore.i(LogStore.MAIN, "开始思考")
                    runOnUiThread { holder.showThinking("思考中: ") }
                }
                override fun onThinkingDelta(text: String) {
                    runOnUiThread { holder.appendThinking(text); scrollToBottom() }
                }
                override fun onThinkingEnd() {
                    runOnUiThread { holder.collapseThinking() }
                }
                override fun onTool(name: String, arg: String) {
                    LogStore.i(LogStore.MAIN, "调用工具: $name")
                    runOnUiThread {
                        holder.showTool(name, arg)
                        // 进入工具调用即表示本段思考已结束: 折叠思考区, 避免一直停在"思考中"
                        holder.collapseThinking()
                        scrollToBottom()
                    }
                }
                override fun onToolResult(name: String, result: String) {
                    LogStore.i(LogStore.MAIN, "工具结果: $name")
                    runOnUiThread { holder.setToolResult(name, result) }
                }
                override fun onDelta(text: String) {
                    android.util.Log.i("agent", "onDelta=[$text]")
                    runOnUiThread { holder.appendContent(text); scrollToBottom() }
                }
                override fun onDone(reply: String) {
                    android.util.Log.i("agent", "onDone len=${reply.length}")
                    if (LocalEngine.cancelRequested) {
                        LogStore.w(LogStore.MAIN, "用户停止输出")
                    } else {
                        LogStore.i(LogStore.MAIN, "回复完成 len=${reply.length} 会话=$replySessionId")
                    }
                    runOnUiThread {
                        if (LocalEngine.cancelRequested) {
                            // 用户主动停止: 不写入对话/记忆
                            holder.appendContent("\n(已停止)")
                            LocalEngine.cancelRequested = false
                        } else {
                            // 思考内容随回复一起持久化, 切回会话可恢复思考区
                            // 竞态防护: 回调排队期间用户可能已切会话, 不能把回复写进新会话历史
                            if (replySessionId == currentSessionId) {
                                messages.add(MemoryDb.SessionMsg("assistant", reply, holder.thinkingSnapshot(), holder.toolsSnapshot()))
                                MemoryKeeper.push("assistant", reply)
                                currentSaved = false
                                // 回复完成即时落库, 防止进程被杀丢失最后一条回复
                                maybeSaveCurrent()
                            }
                        }
                            // 兜底: 引擎重试后仍无正文时给出明确提示, 避免"思考了但没输出"静默空白
                            if (reply.isBlank()) appendSys("模型未返回内容，请再发一次")
                        holder.finishContent()
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                        stopSpinAnim?.cancel()
                        scrollToBottom()
                    }
                }
                override fun onError(msg: String) {
                    LogStore.e(LogStore.MAIN, "错误: $msg")
                    runOnUiThread {
                        holder.showError("出错了: $msg")
                        LocalEngine.cancelRequested = false
                        aiBusy = false
                        TaskService.stop(this@MainActivity)
                        updateInputMode()
                        stopBtn.visibility = View.GONE
                        stopSpinAnim?.cancel()
                        scrollToBottom()
                    }
                }
            }, attachments)
        }
    }

    /** 中期摘要(每次实时读库, 辅助AI后台可能已更新) + 短期最近 KEEP 条 */
    private fun buildHistory(): String {
        summary = db.loadSummary() ?: summary
        val sb = StringBuilder()
        summary?.let { sb.append("[历史摘要]\n$it\n\n") }
        val recent = messages.takeLast(KEEP)
        sb.append(recent.joinToString("\n") { (r, c) -> "$r: $c" })
        // 注意: 不再原地截断 messages —— 否则 onStop 保存时会把被截断的列表写回数据库, 造成旧消息永久丢失。
        // 历史裁剪交给 buildHistory 的 takeLast 窗口即可, 完整历史始终保留在 messages/数据库里。
        return sb.toString()
    }

    // ===================== 气泡渲染 =====================

    /** 新消息气泡入场动画: 上移 + 淡入(全局动画 A 范围: 气泡入场) */
    private fun enterBubble(v: View) {
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
        val v = bubble(content, isUser = true)
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
        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
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
            } else markwon.setMarkdown(this, content.trim())
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
            maxWidth = maxW
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 用户气泡>60字符固定宽, 短文本自适应; 文件卡片始终自适应(由 maxWidth 负责超宽省略);
                // AI 气泡始终自适应(与流式路径一致, 短内容收窄, 长内容由 maxWidth 封顶)
                // 纯图片/纯视频气泡(edgeImage): 必须 WRAP_CONTENT —— 宽度由图片 span 决定,
                // 否则长文件名(content>60)触发 maxW 固定宽, 图片 span 在 TextView 内默认左对齐,
                // 整个图片气泡会被推到屏幕左侧(右对齐被固定宽度架空)
                width = when {
                    isUser && content.length > 60 && !isFileCard && !edgeImage -> maxW
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
                    decodeVideoThumbnail(f, resources.displayMetrics.density) else null
                android.util.Log.i("agent", "renderAtt file=$file mime=$mime exists=${f != null} bmp=${bmp != null} vtb=${vtb != null}")
                if (bmp != null) {
                    // 图片: 圆角化贴合气泡贴边, 替换为缩略图, 同时保留点击打开原图
                    val rb = roundedBitmap(bmp, dp(14))
                    val d = BitmapDrawable(resources, rb)
                    d.setBounds(0, 0, rb.width, rb.height)
                    sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (vtb != null) {
                    // 视频: 首帧缩略图(带播放三角) 像图片一样内嵌气泡, 点击进入 App 内视频预览
                    val rb = roundedBitmap(vtb, dp(14))
                    val d = BitmapDrawable(resources, rb)
                    d.setBounds(0, 0, rb.width, rb.height)
                    sb.setSpan(BubbleImageSpan(d), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
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
                        override fun onClick(widget: View) { openAttachmentPreview(allFiles, allFiles.indexOf(file)) }
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

    // 气泡 span 类 (BubbleImageSpan/BadgeSpan/WaveSpan/PlayPauseIconSpan) 已抽离 BubbleSpans.kt

    /** 附件打开统一入口: 图片/视频/PDF/文本/音频 App 内弹窗预览, 其他(Office等)走系统打开/分享降级 */
    private fun openAttachmentPreview(files: List<String>, startIndex: Int) {
        val idx = if (startIndex in files.indices) startIndex else 0
        val file = files[idx]
        val f = AttachmentStore.fileOf(this, file)
        if (f == null) {
            Toast.makeText(this, "附件文件已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = AttachmentStore.mimeOf(file)
        when {
            mime.startsWith("image/") || mime.startsWith("video/") -> {
                // 同一条消息的图片/视频: 全屏左右滑动切换浏览
                val media = files.filter { fn ->
                    val ff = AttachmentStore.fileOf(this, fn)
                    ff != null && AttachmentStore.mimeOf(fn).let { it.startsWith("image/") || it.startsWith("video/") }
                }
                val mi = media.indexOf(file).coerceAtLeast(0)
                showMediaPreviewDialog(media, mi)
            }
            mime == "application/pdf" -> showPdfPreviewDialog(file)
            mime.startsWith("text/") -> showTextPreviewDialog(file)
            mime.startsWith("audio/") -> showAudioPreviewDialog(file)
            else -> openAttachmentExternal(this, file)
        }
    }

    /** 全屏媒体预览: 同消息多图/视频左右滑动切换; 图片单击关闭/双击缩放, 视频自动播放当前页 */
    private fun showMediaPreviewDialog(media: List<String>, startIndex: Int) {
        if (media.isEmpty()) return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // 外层留边距, 露出圆角: 整卡黑底圆角, 顶部标题栏白底仅顶部圆角
        val outer = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        outer.addView(createMediaPreviewContent(media, startIndex, d))
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.show()
    }

    private fun createMediaPreviewContent(media: List<String>, startIndex: Int, d: Dialog): View {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        // 内容区宽度: 外层留边距后实际可用宽度
        val contentW = screenW - dp(20)
        lateinit var indicator: TextView
        val root = FrameLayout(this).apply {
            background = rounded(dp(20), Color.BLACK)
            // 内容裁剪到圆角范围内, 视频/图片铺满底部时底角仍保持圆角
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        // 垂直容器: 顶部标题栏占一行, 媒体内容在其下方填充剩余空间, 不被标题遮挡
        val vStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val videoViews = arrayOfNulls<VideoView>(media.size)
        media.forEachIndexed { i, file ->
            val page = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val mime = AttachmentStore.mimeOf(file)
            if (mime.startsWith("image/")) {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        bottomMargin = dp(8)   // 底部留白收窄, 配合 root 圆角裁剪, 不遮圆角
                    }
                    setBackgroundColor(Color.BLACK)
                }
                val f = AttachmentStore.fileOf(this, file)
                // 大图解码移到后台线程, 避免大图在主线程解码卡顿
                if (f != null) {
                    executor.execute {
                        val bmp = decodeFullBitmap(f)
                        runOnUiThread { iv.setImageBitmap(bmp) }
                    }
                }
                // 缩放平移状态: 双击在 1x/2x 间切换; 放大后可单指拖动, 边界钳制不拖出
                var scale = 1f
                var tx = 0f
                var ty = 0f
                fun clampAndApply() {
                    if (scale <= 1.01f) {
                        tx = 0f; ty = 0f
                    } else {
                        val maxX = iv.width * (scale - 1f) / 2f
                        val maxY = iv.height * (scale - 1f) / 2f
                        tx = tx.coerceIn(-maxX, maxX)
                        ty = ty.coerceIn(-maxY, maxY)
                    }
                    iv.scaleX = scale
                    iv.scaleY = scale
                    iv.translationX = tx
                    iv.translationY = ty
                }
                val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                        if (scale > 1.01f) {
                            tx -= distanceX
                            ty -= distanceY
                            clampAndApply()
                            return true
                        }
                        return false
                    }
                })
                gd.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean { d.dismiss(); return true }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        scale = if (scale > 1.01f) 1f else 2f
                        tx = 0f; ty = 0f
                        iv.animate().scaleX(scale).scaleY(scale)
                            .translationX(0f).translationY(0f).setDuration(200).start()
                        return true
                    }
                    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
                })
                iv.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
                page.addView(iv)
            } else {
                val vv = VideoView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
                        bottomMargin = dp(8)   // 底部留白收窄, 播放控件悬浮于视频画面内, 不遮底部圆角
                    }
                }
                val f = AttachmentStore.fileOf(this, file)
                if (f != null) vv.setVideoPath(f.absolutePath)
                // MediaController 锚定视频底边, 通过底部内边距把控制条上推, 悬浮在视频画面内不遮圆角
                val mc = MediaController(this).apply {
                    setPadding(0, 0, 0, dp(40))
                }
                vv.setMediaController(mc)
                videoViews[i] = vv
                page.addView(vv)
            }
            strip.addView(page)
        }
        hsv.addView(strip)

        // 顶部标题栏(白底融合整体 UI): 文件名 + 页码 + 关闭; 仅顶部两角圆角(与整卡黑底圆角衔接)
        val topBarBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                0f, 0f, 0f, 0f)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topBarBg
            setPadding(dp(10), dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        topBar.addView(TextView(this).apply {
            text = media.mapNotNull { AttachmentStore.fileOf(this@MainActivity, it)?.name }.getOrNull(startIndex) ?: "预览"
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A1A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topBar.addView(TextView(this).apply {
            text = "${startIndex + 1}/${media.size}"
            textSize = 14f
            setTextColor(Color.parseColor("#0B93F6"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }.also { indicator = it })
        topBar.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(14), dp(2), dp(12), dp(2))
            setOnClickListener { d.dismiss() }
        })
        vStack.addView(topBar)
        vStack.addView(hsv)
        root.addView(vStack)

        fun currentPage(): Int =
            if (media.isEmpty()) 0 else (hsv.scrollX.toFloat() / contentW).let { Math.round(it).coerceIn(0, media.size - 1) }

        fun onPageChanged(page: Int) {
            indicator.text = "${page + 1}/${media.size}"
            media.forEachIndexed { i, fn ->
                val vv = videoViews[i] ?: return@forEachIndexed
                if (i == page) {
                    if (!vv.isPlaying) { try { vv.start() } catch (_: Exception) {} }
                } else {
                    if (vv.isPlaying) vv.stopPlayback()
                }
            }
        }

        // 抬手后(含惯性滑动)重新定位当前页: 滚动停止时吸附到最近整页并同步页码, 避免停在中缝/页码错位
        hsv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    hsv.postDelayed({
                        val target = currentPage()
                        hsv.smoothScrollTo(target * contentW, 0)
                        onPageChanged(target)
                    }, 60)
            }
            false
        }
        d.setOnDismissListener { media.forEachIndexed { i, _ -> videoViews[i]?.stopPlayback() } }

        hsv.post {
            hsv.scrollTo(startIndex * contentW, 0)
            onPageChanged(startIndex)
        }
        return root
    }

    /** 全屏 PDF 预览: 分页式, 一页一屏, 上下翻页; 内存恒定一页(几百页不 OOM, 不再一通到底) */
    private fun showPdfPreviewDialog(fileName: String) {
        val f = AttachmentStore.fileOf(this, fileName) ?: return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val screenW = resources.displayMetrics.widthPixels
        val contentW = screenW - dp(24)
        // 外层留边距露出圆角: 整卡浅底圆角, 标题栏白底仅顶部圆角
        val outer = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(dp(20), Color.parseColor("#F7F7F8"))
        }
        val topBarBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                0f, 0f, 0f, 0f)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topBarBg
            setPadding(dp(16), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "📄 ${f.name}"
                textSize = 15f
                setTextColor(Color.parseColor("#1A1A1A"))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "✕"
                textSize = 22f
                setTextColor(Color.parseColor("#1A1A1A"))
                setPadding(dp(14), dp(2), dp(12), dp(2))
                setOnClickListener { d.dismiss() }
            })
        })
        // 中间: 单页展示区(权重1), 页码悬浮底中
        val pageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val pageIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        pageFrame.addView(pageIv)
        val pageNo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#66000000"))
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) }
        }
        pageFrame.addView(pageNo)
        root.addView(pageFrame)
        // 底部导航: 上一页 / 页码 / 下一页
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val prevBtn = TextView(this).apply {
            text = "‹ 上一页"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#00000000"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val nextBtn = TextView(this).apply {
            text = "下一页 ›"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#00000000"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        nav.addView(prevBtn)
        nav.addView(TextView(this).apply {
            text = "· · ·"
            textSize = 14f
            setTextColor(Color.parseColor("#BBBBBB"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(8) }
        })
        nav.addView(nextBtn)
        root.addView(nav)
        outer.addView(root)
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // 分页渲染状态
        var renderer: PdfRenderer? = null
        var cur = 0
        var total = 0
        var lastPage: PdfRenderer.Page? = null
        var renderSeq = 0   // 渲染序号: 翻页递增, 过期渲染结果直接丢弃, 避免快速翻页时旧页覆盖新页
        fun renderPage(i: Int) {
            val r = renderer ?: return
            val seq = ++renderSeq
            // 渲染移到后台线程, 大 PDF 单页渲染不再阻塞主线程
            executor.execute {
                try {
                    lastPage?.let { try { it.close() } catch (e: Exception) { } }
                    lastPage = null
                    val pg = try { r.openPage(i) } catch (e: Exception) { null } ?: return@execute
                    lastPage = pg
                    val targetH = (pg.height.toFloat() / pg.width * contentW).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(contentW, targetH, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    c.drawColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    runOnUiThread {
                        if (seq != renderSeq) { bmp.recycle(); return@runOnUiThread }
                        pageIv.setImageBitmap(bmp)
                        pageNo.text = "${i + 1} / $total"
                        prevBtn.isEnabled = i > 0
                        nextBtn.isEnabled = i < total - 1
                        prevBtn.setTextColor(if (i > 0) Color.parseColor("#1A1A1A") else Color.parseColor("#BBBBBB"))
                        nextBtn.setTextColor(if (i < total - 1) Color.parseColor("#1A1A1A") else Color.parseColor("#BBBBBB"))
                    }
                } catch (e: Exception) {
                    // 渲染失败静默, 保持上一页画面
                }
            }
        }
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            total = renderer!!.pageCount
            renderPage(0)
        } catch (e: Exception) {
            Toast.makeText(this, "PDF 解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
            d.dismiss()
        }
        prevBtn.setOnClickListener { if (cur > 0) { cur--; renderPage(cur) } }
        nextBtn.setOnClickListener { if (cur < total - 1) { cur++; renderPage(cur) } }
        // 左右滑动翻页(保留上下页按钮), 左滑下一页 / 右滑上一页
        val pdfSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f && kotlin.math.abs(dx) > dp(60)) {
                    if (dx < 0 && cur < total - 1) { cur++; renderPage(cur) }
                    else if (dx > 0 && cur > 0) { cur--; renderPage(cur) }
                    return true
                }
                return false
            }
        })
        pageFrame.setOnTouchListener { _, ev -> pdfSwipe.onTouchEvent(ev); true }
        d.setOnDismissListener {
            lastPage?.let { try { it.close() } catch (e: Exception) { } }
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
        }
        d.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) { d.dismiss(); true } else false
        }
        d.show()
    }

    /** 全屏文本预览 */
    private fun showTextPreviewDialog(fileName: String) {
        val f = AttachmentStore.fileOf(this, fileName) ?: return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(dp(20), Color.parseColor("#F7F7F8"))
        }
        val content = try {
            f.readText()
        } catch (e: Exception) {
            "无法读取文本: ${e.message}"
        }
        val topBarBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                0f, 0f, 0f, 0f)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topBarBg
            setPadding(dp(16), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "📄 ${f.name}"
                textSize = 15f
                setTextColor(Color.parseColor("#1A1A1A"))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "✕"
                textSize = 22f
                setTextColor(Color.parseColor("#1A1A1A"))
                setPadding(dp(14), dp(2), dp(12), dp(2))
                setOnClickListener { d.dismiss() }
            })
        })
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(TextView(this).apply {
            text = content
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            // 自由复制: 长按出现选择手柄, 可拖选任意片段复制(系统自带全选/复制菜单)
            setTextIsSelectable(true)
        })
        root.addView(scroll)
        root.addView(TextView(this).apply {
            text = "点击 ✕ 关闭"
            textSize = 13f
            setTextColor(Color.parseColor("#8A8A8A"))
            setBackgroundColor(Color.parseColor("#F7F7F8"))
            setPadding(dp(16), dp(10), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        outer.addView(root)
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.setOnClickListener { d.dismiss() }
        d.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) { d.dismiss(); true } else false
        }
        d.show()
    }

    /** 音频预览弹窗: 播放/暂停 + 进度条 */
    private fun showAudioPreviewDialog(fileName: String) {
        val f = AttachmentStore.fileOf(this, fileName) ?: return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val player = MediaPlayer()
        player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        try {
            player.setDataSource(f.absolutePath)
            player.prepare()
        } catch (e: Exception) {
            Toast.makeText(this, "音频加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(dp(20), Color.WHITE)
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "🎵 ${f.name}"
            textSize = 16f
            setTextColor(Color.parseColor("#1A1A1A"))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
        })
        val playBtn = Button(this).apply {
            text = "播放"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B93F6"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(16) }
        }
        val seek = SeekBar(this).apply {
            max = player.duration.coerceAtLeast(1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val timeTv = TextView(this).apply {
            text = "00:00 / ${fmtDuration(player.duration.toLong())}"
            textSize = 13f
            setTextColor(Color.parseColor("#8A8A8A"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
        }
        root.addView(playBtn); root.addView(seek); root.addView(timeTv)
        root.addView(Button(this).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#F1F2F4"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            setOnClickListener { d.dismiss() }
        })
        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                if (player.isPlaying) {
                    seek.progress = player.currentPosition
                    timeTv.text = "${fmtDuration(player.currentPosition.toLong())} / ${fmtDuration(player.duration.toLong())}"
                }
                handler.postDelayed(this, 500)
            }
        }
        playBtn.setOnClickListener {
            if (player.isPlaying) { player.pause(); playBtn.text = "播放" }
            else { player.start(); playBtn.text = "暂停" }
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { player.seekTo(progress); timeTv.text = "${fmtDuration(progress.toLong())} / ${fmtDuration(player.duration.toLong())}" }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        player.setOnCompletionListener { runOnUiThread { playBtn.text = "播放"; seek.progress = seek.max } }
        d.setOnDismissListener { handler.removeCallbacks(ticker); try { player.release() } catch (_: Exception) {} }
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
        handler.post(ticker)
    }

    /** 加载原图(限制到屏幕2倍, 避免大图 OOM) */
    private fun decodeFullBitmap(f: File, maxSide: Int = resources.displayMetrics.widthPixels * 2): Bitmap? {
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, b)
            if (b.outWidth <= 0 || b.outHeight <= 0) return null
            var sample = 1
            while (maxOf(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (e: Exception) { null }
    }

    /** 从会话历史恢复的 AI 气泡: 思考折叠区(可点开) + 工具行(可点开) + 正文 */
    private fun aiBubbleWithThinking(thinking: String, content: String, toolsJson: String = ""): LinearLayout {
        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        var expanded = false
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(dp(14), BUBBLE_AI)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 自适应: 与流式输出路径一致, 内容短则收窄, 最长由子 view maxWidth 限制(屏幕宽*0.78)
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
            addView(TextView(this@MainActivity).apply {
                val count = thinking.codePointCount(0, thinking.length)
                text = "💭 已思考${count}字，点按展开"
                textSize = 14f
                setTextColor(THINK_TEXT)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(dp(10), THINK_BG)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
                setOnClickListener {
                    expanded = !expanded
                    text = if (expanded) "💭 $thinking" else "💭 已思考${count}字，点按展开"
                }
                makeCopyable(this) { thinking }
            })
            // 工具调用序列恢复: 每条折叠为一行"🔧 工具：name", 点击展开参数与结果(与流式 ToolBlock 一致)
            parseTools(toolsJson).forEach { (name, arg, result) ->
                val toolTv = TextView(this@MainActivity).apply {
                    text = "🔧 工具：$name"
                    textSize = 14f
                    setTextColor(THINK_TEXT)
                    setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = rounded(dp(10), THINK_BG)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    }
                    isClickable = true
                    var toolExpanded = false
                    setOnClickListener {
                        toolExpanded = !toolExpanded
                        text = buildString {
                            append("🔧 工具：$name")
                            if (toolExpanded) {
                                if (arg.isNotBlank()) append("\n参数：$arg")
                                if (result.isNotBlank()) append("\n结果：$result")
                            }
                        }
                    }
                }
                makeCopyable(toolTv) { buildString {
                    append("🔧 工具：$name")
                    if (arg.isNotBlank()) append("\n参数：$arg")
                    if (result.isNotBlank()) append("\n结果：$result")
                } }
                addView(toolTv)
            }
            addView(TextView(this@MainActivity).apply {
                // 先设基础字号再渲染 Markdown, 保证 HeadingSpan 的倍率基于正确 textSize 生效
                textSize = 15f
                markwon.setMarkdown(this, content)
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(BUBBLE_AI_TEXT)
                setPadding(0, dp(4), 0, dp(2))
                maxWidth = maxW
                makeCopyable(this) { content }
            })
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

    private fun scrollToBottom() {
        // 不能用 fullScroll(FOCUS_DOWN): 它会把焦点交给滚动方向上第一个可聚焦子 view(气泡 setTextIsSelectable 后可聚焦),
        // 导致发送后焦点被气泡抢走、输入框失焦无法继续打字。改用 scrollTo 纯滚动不碰焦点。
        scroll.post { scroll.scrollTo(0, scroll.getChildAt(0)?.bottom ?: 0) }
    }

    /** 气泡文本支持自由选择复制: 长按出现选择手柄, 可拖选部分文本; 顶部菜单含 全选/复制 */
    private fun makeCopyable(tv: TextView, textProvider: () -> String = { tv.text.toString() }) {
        tv.setTextIsSelectable(true)
        // 不拦截长按: 交给系统进入文本选择模式, 支持拖动手柄自由复制任意片段
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
                    Toast.makeText(this, "请先授权麦克风, 授权后重新按住说话", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, "已达 60 秒上限, 自动发送", Toast.LENGTH_SHORT).show()
                        finishSpeakAndSend()
                    } else handler.postDelayed(this, 200)
                }
            }
            handler.postDelayed(recTimer!!, 200)
        } catch (e: Exception) {
            Toast.makeText(this, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "说话时间太短, 请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val (bytes, name, durationMs) = res
        if (aiBusy) {
            Toast.makeText(this, "AI 正在输出, 请稍后再发", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "语音发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
        speakBar.text = "按住 说话"
        speakBar.background = rounded(dp(22), Color.parseColor("#9AA0A6"))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "麦克风已授权, 请重新按住说话", Toast.LENGTH_SHORT).show()
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
                runOnUiThread { stopWaveAnim(); animateVoiceBubble(fileName, false); refreshAudioBubbles() }
            }
            p.prepare()
            p.start()
            audioPlayer = p
            playingFileName = fileName
            startWaveAnim()
            animateVoiceBubble(fileName, true)
            refreshAudioBubbles()
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "无法打开选择器: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        // 支持一次多选, 但每次携带上限 6 个: 超出部分丢弃并提示
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        }
        if (uris.isEmpty()) return
        val MAX = 6
        if (uris.size > MAX) {
            Toast.makeText(this, "一次最多上传 $MAX 个文件, 已保留前 $MAX 个", Toast.LENGTH_SHORT).show()
        }
        uris.take(MAX).forEach { sendAttachmentFromUri(it) }
    }

    /**
     * 读取附件并发送:
     * - 图片: 压缩后 Base64
     * - 视频: 限 50MB, 超限本地转码压缩后再发, 压缩后仍超限拒绝
     * - 其余(含 PDF/txt/md/Word/Excel/PPT/压缩包): 本地解析提取文本
     *   (text 随 history 注入模型; 支持 PDF/MD/TXT/DOCX/XLSX/PPTX/ZIP/TAR/TGZ 等,
     *   提取不到或纯二进制文件则退回直发普通文件)
     */
    private fun sendAttachmentFromUri(uri: Uri) {
        executor.execute {
            try {
                val cr = contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                val name = queryDisplayName(uri) ?: "attachment"
                val isPdf = mime == "application/pdf" || name.lowercase().endsWith(".pdf")
                val isVideo = mime.startsWith("video/")
                val atts: List<LocalEngine.Attachment> = when {
                    mime.startsWith("image/") -> {
                        val bytes = compressImage(uri)
                        listOf(LocalEngine.Attachment(mime, Base64.encodeToString(bytes, Base64.NO_WRAP), name))
                    }
                    isVideo -> {
                        val raw = readAll(uri)
                        if (raw.size <= MAX_VIDEO_BYTES) {
                            listOf(LocalEngine.Attachment(mime, Base64.encodeToString(raw, Base64.NO_WRAP), name))
                        } else {
                            runOnUiThread { Toast.makeText(this@MainActivity, "视频超过50MB，正在本地压缩...", Toast.LENGTH_SHORT).show() }
                            val out = File(cacheDir, "comp_${System.currentTimeMillis()}.mp4")
                            try {
                                VideoCompressor.compress(this@MainActivity, uri, out)
                            } catch (e: Exception) {
                                runOnUiThread { Toast.makeText(this@MainActivity, "视频压缩失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                                return@execute
                            }
                            val cb = out.readBytes()
                            out.delete()
                            if (cb.size > MAX_VIDEO_BYTES) {
                                runOnUiThread { Toast.makeText(this@MainActivity, "视频压缩后仍超过50MB，暂不支持发送", Toast.LENGTH_SHORT).show() }
                                return@execute
                            }
                            listOf(LocalEngine.Attachment("video/mp4", Base64.encodeToString(cb, Base64.NO_WRAP), name))
                        }
                    }
                    else -> {
                        val raw = readAll(uri)
                        if (raw.size > MAX_FILE_BYTES) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity,
                                    (if (isPdf) "PDF 过大(>20MB)${if (name.lowercase().endsWith(".pdf")) "" else " 或格式异常"}" else "文件过大(>20MB)") + "，暂不支持发送",
                                    Toast.LENGTH_SHORT).show()
                            }
                            return@execute
                        }
                        // m4a: 部分设备/APP 生成 isom/mp42 容器, MiMo 仅接受 ftyp M4A; 修正 major_brand 避免 400
                        val finalRaw = if ((mime.startsWith("audio/") || name.lowercase().endsWith(".m4a")) &&
                            raw.size >= 16 && raw[4].toInt().toChar() == 'f' && raw[5].toInt().toChar() == 't' &&
                            raw[6].toInt().toChar() == 'y' && raw[7].toInt().toChar() == 'p') {
                            val brand = String(raw, 8, 4)
                            if (brand != "M4A " && brand != "M4A\u0000") {
                                val out = raw.clone()
                                out[8] = 'M'.code.toByte(); out[9] = '4'.code.toByte()
                                out[10] = 'A'.code.toByte(); out[11] = ' '.code.toByte()
                                out
                            } else raw
                        } else raw
                        val txt = DocTextExtractor.extract(name, finalRaw)
                        // 无文本层 PDF(扫描件)且模型支持图像: 渲染为图片走 image_url, 避免 MiMo 对 input_file 500
                        if (txt.isNullOrBlank() && isPdf &&
                            ApiConfig.modelHasCap(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.CAP_IMAGE)) {
                            val imgs = pdfToImageAttachments(uri, name)
                            if (imgs.isEmpty()) {
                                runOnUiThread { Toast.makeText(this@MainActivity, "无法解析该 PDF(无文本层且渲染失败), 暂不支持发送", Toast.LENGTH_SHORT).show() }
                                return@execute
                            }
                            // 页图标记 pdfSourceName: 显示层隐藏(不铺图片网格), 仅作为 image_url 发给模型看图;
                            // 追加 PDF 卡片附件: 气泡以文件卡片展示(点击进 PDF 全屏预览), 不再"一通到底"
                            imgs.map { it.copy(pdfSourceName = name) } + listOf(
                                LocalEngine.Attachment("application/pdf", Base64.encodeToString(finalRaw, Base64.NO_WRAP), name,
                                    text = "（PDF 扫描件，已渲染为图片供查看）"))
                        } else if (txt.isNullOrBlank()) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "无法解析该文档文本内容, 暂不支持发送", Toast.LENGTH_SHORT).show() }
                            return@execute
                        } else {
                            listOf(LocalEngine.Attachment(mime, Base64.encodeToString(finalRaw, Base64.NO_WRAP), name, text = txt))
                        }
                    }
                }
                runOnUiThread {
                    // 预览条方案: 附件先进输入框上方预览, 补文字后由 onSend 一并发送, 不再直接发出
                    // 总量上限 6: 无论单次还是多次累积, 超出部分拒绝加入预览条(不占发送队列)
                    val MAX_ATT = 6
                    // PDF 扫描件页图(pdfSourceName 非空)是同一个 PDF 的内部展开, 不占用户文件计数
                    var userAtt = pendingAttachments.count { it.pdfSourceName == null }
                    for (att in atts) {
                        val isPageImg = att.pdfSourceName != null
                        if (!isPageImg && userAtt >= MAX_ATT) {
                            Toast.makeText(this@MainActivity, "一次最多上传 $MAX_ATT 个文件, 已丢弃多余附件", Toast.LENGTH_SHORT).show()
                            break
                        }
                        pendingAttachments.add(att)
                        if (!isPageImg) {
                            userAtt += 1
                            addAttachPreview(att)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("agent", "读取附件失败", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "读取附件失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 附件预览条加一项: 图片显缩略图, 其他显格式角标; 右上角 × 删除该项 */
    private fun addAttachPreview(att: LocalEngine.Attachment) {
        attachPreviewWrap.visibility = View.VISIBLE
        val cell = FrameLayout(this)
        val thumb: View = if (att.mime.startsWith("image/")) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(dp(8), Color.parseColor("#EFEFF1"))
                try {
                    val arr = Base64.decode(att.base64, Base64.NO_WRAP)
                    val raw = BitmapFactory.decodeByteArray(arr, 0, arr.size)
                    if (raw != null) {
                        val s = minOf(raw.width, raw.height)
                        val crop = Bitmap.createBitmap(raw, (raw.width - s) / 2, (raw.height - s) / 2, s, s)
                        val thumbBmp = Bitmap.createScaledBitmap(crop, dp(48), dp(48), true)
                        if (thumbBmp != crop) crop.recycle()
                        raw.recycle()
                        setImageBitmap(thumbBmp)
                    }
                } catch (e: Exception) {
                    setImageBitmap(null)
                }
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            }
        } else {
            TextView(this).apply {
                text = badgeOf(att.mime, att.name)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(dp(8), Color.parseColor("#8A8F9C"))
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            }
        }
        val del = Button(this).apply {
            text = "×"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            background = rounded(dp(9), Color.parseColor("#E5484D"))
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END)
            setOnClickListener {
                attachPreviewRow.removeView(cell)
                pendingAttachments.remove(att)
                if (pendingAttachments.isEmpty()) attachPreviewWrap.visibility = View.GONE
            }
        }
        cell.addView(thumb)
        cell.addView(del)
        attachPreviewRow.addView(cell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
    }

    /** 附件格式角标文案 (已抽离 UiKit.badgeOf) */

    /** 无文本层 PDF(扫描件): 用系统 PdfRenderer 渲染前几页为 JPEG, 走 image_url 让多模态模型看图。
     *  返回空列表表示渲染失败。 */
    private fun pdfToImageAttachments(uri: Uri, name: String): List<LocalEngine.Attachment> {
        val list = mutableListOf<LocalEngine.Attachment>()
        val pfd: ParcelFileDescriptor = try {
            contentResolver.openFileDescriptor(uri, "r") ?: return list
        } catch (e: Exception) { return list }
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            val maxPages = minOf(renderer.pageCount, 10)
            for (i in 0 until maxPages) {
                val page = renderer.openPage(i)
                try {
                    val w = page.width
                    val h = page.height
                    val scale = if (maxOf(w, h) > MAX_IMAGE_SIDE) MAX_IMAGE_SIDE.toFloat() / maxOf(w, h) else 1f
                    val bmp = Bitmap.createBitmap(
                        (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    if (out.size() > 3 * 1024 * 1024) {
                        out.reset()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 68, out)
                    }
                    val bytes = out.toByteArray()
                    bmp.recycle()
                    list.add(LocalEngine.Attachment("image/jpeg",
                        Base64.encodeToString(bytes, Base64.NO_WRAP), "${name.removeSuffix(".pdf")}_p${i + 1}.jpg"))
                } finally {
                    try { page.close() } catch (e: Exception) { /* 单页失败跳过 */ }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("agent", "PDF 渲染失败", e)
        } finally {
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
        return list
    }

    /** 图片压缩: 最长边限制 MAX_IMAGE_SIDE, JPEG 质量 85; 超 3MB 再降至 70 */
    private fun compressImage(uri: Uri): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > MAX_IMAGE_SIDE || opts.outHeight / sample > MAX_IMAGE_SIDE) sample *= 2
        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dec) }
            ?: throw IllegalStateException("无法解码图片")
        val scaled = if (bmp.width > MAX_IMAGE_SIDE || bmp.height > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true).also { if (it != bmp) bmp.recycle() }
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (out.size() > 3 * 1024 * 1024) {
            out.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        val bytes = out.toByteArray()
        scaled.recycle()
        return bytes
    }

    private fun readAll(uri: Uri): ByteArray {
        val ins: InputStream = contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法打开文件")
        return ins.use { i ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = i.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }



    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
    private inner class AiBubbleHolder : TypewriterTickable {
        private var bubbleBox: LinearLayout? = null
        private var statusView: TextView? = null
        // 思考块列表: 每段思考独立一行, 按真实顺序竖向排列成时间线
        private val thinkingBlocks = ArrayList<ThinkingBlock>()
        private var activeThinking: ThinkingBlock? = null
        // 工具块列表: 每个工具调用独立一行, 折叠点击展开
        private val toolBlocks = ArrayList<ToolBlock>()
        private var contentView: TextView? = null
        private var contentText = StringBuilder()
        private var done = false
        private var loadingRow: LinearLayout? = null   // 思考完成等待正文时的加载指示行
        private var maxW = 0
        // 打字机(游标+帧驱动): contentText 只累积不删除, shownLen 控制显示进度
        private var shownLen = 0
        private var lastFrameNs = 0L
        private var typeActive = false
        private var typeFinishedRender = false

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
                scrollToBottom()
            }
        }

        /** 思考块内多段只显示最后一段, 避免 "思考中: 思考一思考二..." 眼花 */
        private fun tailThinking(b: ThinkingBlock): String {
            val s = b.text.toString()
            val idx = s.lastIndexOf("\n\n")
            return if (idx >= 0) s.substring(idx + 2).trim() else s
        }

        fun attach(parent: LinearLayout) {
            maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(dp(14), BUBBLE_AI)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.START
                    topMargin = dp(6)
                    // 整体自适应: 内容短则收窄, 最长由子 view maxWidth 封顶(屏幕宽*0.78)
                }
            }
            parent.addView(box)
            bubbleBox = box
            enterBubble(box)
        }

        fun showStatus(s: String) {
            statusView = addLine(s, THINK_TEXT, italic = true)
        }

        /** 思考完成、正文未开始前显示加载指示, 避免误以为卡住 */
        private fun startLoading() {
            if (loadingRow != null || contentView != null) return
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall).apply {
                val lp = LinearLayout.LayoutParams(dp(14), dp(14))
                lp.rightMargin = dp(6)
                layoutParams = lp
            })
            row.addView(TextView(this@MainActivity).apply {
                text = "生成中…"
                textSize = 12f
                setTextColor(THINK_TEXT)
            })
            bubbleBox?.addView(row)
            loadingRow = row
            scrollToBottom()   // 加载行可能加在屏幕外, 必须滚到底才可见
            android.util.Log.i("agent", "startLoading shown len=" + contentText.length)
        }

        private fun stopLoading() {
            loadingRow?.let { bubbleBox?.removeView(it) }
            loadingRow = null
        }

        /** 思考段开始: 若上一段已折叠则新建一块(多轮思考->工具->正文按真实顺序竖向排列), 否则沿用当前块 */
        fun showThinking(prefix: String) {
            removeStatus()
            stopLoading()
            var b = activeThinking
            if (b == null || b.collapsed) {
                // 新建思考块: 独立一行, 深色背景块
                b = ThinkingBlock()
                thinkingBlocks.add(b)
                activeThinking = b
                // 气泡变形修复: 真正出现思考段才设最小宽度(=折叠行渲染宽), 无思考段(自动模式直出正文)不设, 自由自适应
                if (bubbleBox?.minimumWidth == 0) {
                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
                    }
                    // 折叠行自身 padding(左右各10dp) + 气泡 padding(左右各14dp)
                    bubbleBox?.minimumWidth = (tp.measureText("💭 已思考 1000 字，点按展开") + dp(48)).toInt()
                }
                val tv = TextView(this@MainActivity).apply {
                    text = prefix
                    textSize = 14f
                    setTextColor(THINK_TEXT)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = rounded(dp(10), THINK_BG)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    }
                }
                bubbleBox?.addView(tv)
                makeCopyable(tv) { b.text.toString() }
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
            if (contentView == null) startLoading()   // 思考完但正文未开始: 提示生成中
        }

        private fun toggleThinking(b: ThinkingBlock) {
            b.expanded = !b.expanded
            b.view?.text = if (b.expanded) "💭 " + b.text else b.summary()
        }

        /** 工具调用开始: 折叠为一行"🔧 工具：名称", 点击展开参数与结果 */
        fun showTool(name: String, arg: String) {
            removeStatus()
            stopLoading()
            val b = ToolBlock(name, arg)
            toolBlocks.add(b)
            val tv = addLine(b.collapsedText(), THINK_TEXT, italic = true)
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
            b.view?.text = if (b.expanded) b.expandedText() else b.collapsedText()
            scrollToBottom()
        }

        fun appendContent(text: String) {
            removeStatus()
            stopLoading()
            if (contentView == null) {
                contentView = addLine("", BUBBLE_AI_TEXT, italic = false)
            }
            contentText.append(text)
            if (!typeActive) {
                typeActive = true
                typeFinishedRender = false
                lastFrameNs = 0L
                TypewriterCenter.register(this)
            }
        }

        /** 帧回调(Choreographer): 按经过时间折算本帧应打字符数, 游标推进后渲染纯文本 */
        override fun tickFrame(frameNs: Long): Boolean {
            if (!typeActive) return false
            val total = contentText.length
            if (shownLen >= total) {
                lastFrameNs = frameNs
                if (done) {
                    finishTypeRender()
                    return false
                }
                return true   // 空转等新 delta, 不渲染
            }
            val elapsed = if (lastFrameNs == 0L) 1.0 / 60.0 else (frameNs - lastFrameNs) / 1_000_000_000.0
            lastFrameNs = frameNs
            var budget = (typeSpeed(total) * elapsed).toInt() + 1   // 本帧字符预算, 至少 1
            var idx = shownLen
            while (budget > 0 && idx < total) {
                val n = Character.charCount(contentText.codePointAt(idx))  // 不拆散 emoji/代理对
                idx += n
                budget -= n
            }
            if (idx > shownLen) {
                shownLen = idx
                contentView?.let { tv ->
                    // 打字期间也按已输出部分实时渲染 MD, 避免输出完才一次性排版(符号原样可见)
                    markwon.setMarkdown(tv, contentText.substring(0, shownLen))
                    scrollToBottom()
                }
            }
            if (shownLen >= total && done) {
                finishTypeRender()
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

        /** 收尾: 一次性 Markdown 排版, 只执行一次 */
        private fun finishTypeRender() {
            if (typeFinishedRender) return
            typeFinishedRender = true
            typeActive = false
            TypewriterCenter.unregister(this)
            contentView?.let { markwon.setMarkdown(it, contentText.toString()) }
            scrollToBottom()
        }

        fun finishContent() {
            done = true
            stopLoading()
            if (contentText.isBlank() && toolBlocks.isEmpty() && thinkingBlocks.isEmpty()) {
                contentView = addLine("(无内容)", BUBBLE_AI_TEXT, italic = false)
                return
            }
            // 打字机没在跑(从未收到正文或已打完): 直接排版; 在跑则由帧回调追上后自动收尾
            if (!typeActive) {
                if (contentText.isNotEmpty()) {
                    contentView?.let { markwon.setMarkdown(it, contentText.toString()) }
                    scrollToBottom()
                }
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
            typeActive = false        // 出错即停打
            TypewriterCenter.unregister(this)
            addLine(msg, Color.parseColor("#D93025"), italic = false)
            done = true
        }

        private fun removeStatus() {
            statusView?.let { bubbleBox?.removeView(it) }
            statusView = null
        }

        private fun addLine(text: String, color: Int, italic: Boolean): TextView {
            val tv = TextView(this@MainActivity).apply {
                this.text = text
                textSize = 15f
                setTextColor(color)
                if (italic) setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC))
                setPadding(0, dp(2), 0, dp(2))
                // 自适应上限: 与用户气泡/恢复路径同一宽度基准(屏幕宽*0.78)
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            }
            bubbleBox?.addView(tv)
            makeCopyable(tv)
            return tv
        }
    }
}

/** 带缺口的旋转加载环: 画 270° 圆弧, 剩 90° 缺口, 旋转时有明显转动感 */
class ArcRingDrawable(private val size: Int, stroke: Int, color: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke.toFloat()
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private var angle = 0f

    override fun draw(canvas: Canvas) {
        val half = size / 2f
        val r = half - paint.strokeWidth / 2f - 1f
        canvas.save()
        canvas.rotate(angle, half, half)
        canvas.drawArc(half - r, half - r, half + r, half + r, 0f, 270f, false, paint)
        canvas.restore()
    }

    fun setAngle(a: Int) {
        angle = a.toFloat()
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
}


/** 固定背景: 按位图宽高比固定绘制高度, 输入法弹起窗口变矮时只裁切不拉伸不平铺(类微信) */
private class FixedBgDrawable(private val bmp: android.graphics.Bitmap) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas: android.graphics.Canvas) {
        // centerCrop: 按比例放大至完全覆盖窗口, 多余裁掉, 居中 -> 任意尺寸图片都不留空白(同微信)
        val vw = bounds.width().coerceAtLeast(1)
        val vh = bounds.height().coerceAtLeast(1)
        val bw = bmp.width.coerceAtLeast(1)
        val bh = bmp.height.coerceAtLeast(1)
        val scale = Math.max(vw.toFloat() / bw, vh.toFloat() / bh)
        val dw = (bw * scale).toInt()
        val dh = (bh * scale).toInt()
        val left = (vw - dw) / 2
        val top = (vh - dh) / 2
        canvas.drawBitmap(bmp, null, android.graphics.Rect(left, top, left + dw, top + dh), paint)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
