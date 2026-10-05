package io.github.aixtin.nyral

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 右侧整屏浏览器操作页（自研 Agent 浏览器第一版雏形）：
 * - 右缘左滑整页推入 WebView（全屏铺满，非抽屉叠加），左缘右滑/✕/返回键推回主界面
 * - AI 浮层：顶部 AI 状态条（步骤播报 + 可折叠思考摘要）、页面高亮圈（坐标画框 + 滚动跟随）
 * - 接管模式：一键交还用户直接点验证码，再点"交还 AI"切回 AI
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
internal class BrowserPage(internal val act: MainActivity) {

    internal lateinit var root: FrameLayout
    internal lateinit var web: WebView
    internal lateinit var thinkBody: TextView
    internal lateinit var thinkAll: TextView
    internal var thinkLog = ""
    internal lateinit var thinkWrap: LinearLayout
    internal lateinit var thinkScrollBox: android.widget.ScrollView
    internal lateinit var highlight: BrowserHighlightView
    internal lateinit var takeover: TextView
    internal var taken = false      // 用户是否接管中
    internal var loaded = false     // 网页是否已加载(首个页面)
    internal var lastUrl = ""       // 最近一次 open 指定的地址(WebView.getUrl 加载完成前为空, 用于调试状态回显)

    // 扫描到的高亮候选元素(视口坐标)
    internal val elements = mutableListOf<Element>()
    internal var textResult: String = ""
    internal var textLatch: CountDownLatch? = null
    internal var elemPos = -1       // 当前高亮的是第几个

    // JS 桥回传结果(click/type 的执行回执), 供调试/日志查看
    @Volatile internal var actionResult = ""

    // AI 上传: browser_upload 预设的工作目录文件 content uri, onShowFileChooser 触发时直接注入
    internal var pendingUpload: Array<Uri>? = null

    /** 旧数据中文名 -> 预设 key 迁移 */

    /* ===== 底部抽屉(状态条+设置面板) / 引擎管理 ===== */
    internal lateinit var urlView: TextView
    internal lateinit var drawerStatus: TextView
    internal lateinit var drawerEngineTag: TextView
    internal lateinit var hamburgerMask: View
    internal lateinit var hamburgerPanel: LinearLayout
    internal var hamburgerOpen = false
    internal lateinit var tabEngine: TextView
    internal lateinit var tabData: TextView
    internal lateinit var thinkTab: TextView
    internal lateinit var engineTitle: TextView
    internal lateinit var addTab: TextView
    internal lateinit var tabSpacer: View
    internal lateinit var engineScroll: ScrollView
    internal lateinit var engineList: LinearLayout
    internal lateinit var dataScroll: ScrollView
    internal lateinit var dataBox: LinearLayout
    internal val engines = mutableListOf<Engine>()
    internal var engineIdx = 0
    @Volatile internal var lastKeyword = ""   // 最近一次搜索关键词, 供聚合引擎复用
    internal val ENGINE_PREFS = "browser_engines"
    internal val blue = Ui.PRIMARY
    internal val gray = Ui.SUB

    internal var open = false
    internal val engineListInitialized: Boolean get() = ::engineList.isInitialized
    internal val dataBoxInitialized: Boolean get() = ::dataBox.isInitialized
    internal val drawerStatusInitialized: Boolean get() = ::drawerStatus.isInitialized
    internal val urlViewInitialized: Boolean get() = ::urlView.isInitialized
    internal val drawerEngineTagInitialized: Boolean get() = ::drawerEngineTag.isInitialized

    // ===== 供 DebugServer(/v1/browser) 调试接口读取的内部状态 =====
    /** 当前 WebView 地址(未初始化/无页面时返回空串); 加载完成前回显 lastUrl */
    internal val currentUrl: String
        get() {
            val u = runCatching { web.url }.getOrNull()
            return if (!u.isNullOrBlank()) u else lastUrl
        }
    internal val elementCount: Int get() = elements.size
    internal val highlightedIndex: Int get() = elemPos

    @Volatile
    internal var actionLatch: CountDownLatch? = null

    @Volatile internal var scanLatch: CountDownLatch? = null

    @Volatile internal var loadDoneLatch: CountDownLatch? = null

    /** 自动落盘回调: 页面加载后检测到当前域 Cookie 变化时回调宿主写入 site_auth.json */
    var autoSaveCookie: ((host: String, cookie: String) -> Unit)? = null

    /** 浏览器即将打开(窗口化收缩前回调): 宿主在此先锁定窗口尺寸, 避免动画期间"全屏->中间"跳变 */
    var onPreOpen: (() -> Unit)? = null

    /** 浏览器打开/关闭回调(悬浮聊天模式切换): true=已打开 slideIn, false=已关闭 slideOut */
    var onOpenChange: ((Boolean) -> Unit)? = null

    /** 接管状态变化回调: true=用户接管(taken), false=交还 AI */
    var onTakeoverChange: ((Boolean) -> Unit)? = null

    /** 汉堡面板展开/收起回调: true=展开(聊天层让位), false=收起 */
    var onHamburgerChange: ((Boolean) -> Unit)? = null
    internal val hostCookieCache = HashMap<String, String>()

    internal fun destroy() { web.destroy() }
    internal fun hideHighlight() { highlight.clearTarget() }

    init {
        initWebViews()
        initRootUi()
        loadEngines()
    }

    /** 窗口化模式: 浏览器窗口收缩到聊天内容区(titleBar 下 ~ inputBar 上), 接管/悬浮均保持该尺寸 */



}
