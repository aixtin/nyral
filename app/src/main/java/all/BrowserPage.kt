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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.net.Uri
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
internal class BrowserPage(private val act: MainActivity) {

    internal lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var thinkBody: TextView
    private lateinit var thinkWrap: LinearLayout
    private lateinit var highlight: BrowserHighlightView
    private lateinit var takeover: TextView
    internal var taken = false      // 用户是否接管中
    private var loaded = false     // 网页是否已加载(首个页面)
    private var lastUrl = ""       // 最近一次 open 指定的地址(WebView.getUrl 加载完成前为空, 用于调试状态回显)

    // 扫描到的高亮候选元素(doc 绝对坐标)
    private val elements = mutableListOf<Element>()
    private var textResult: String = ""
    private var textLatch: CountDownLatch? = null
    private var elemPos = -1       // 当前高亮的是第几个

    // JS 桥回传结果(click/type 的执行回执), 供调试/日志查看
    @Volatile private var actionResult = ""

    // AI 上传: browser_upload 预设的工作目录文件 content uri, onShowFileChooser 触发时直接注入
    private var pendingUpload: Array<Uri>? = null

    private data class Element(val x: Int, val y: Int, val w: Int, val h: Int, val label: String)

    /** 搜索引擎: name 显示名, search 搜索模板(含 {q}), home 首页, key 预设标识(空=自定义) */
    private data class Engine(val name: String, val search: String, val home: String, val key: String = "")

    /** 旧数据中文名 -> 预设 key 迁移 */
    private fun engineKeyOf(n: String): String = when (n) {
        "百度", "baidu" -> "baidu"
        "必应", "bing" -> "bing"
        "谷歌", "google" -> "google"
        "搜狗", "sogou" -> "sogou"
        "神马", "sm" -> "sm"
        "知乎", "zhihu" -> "zhihu"
        else -> ""
    }

    /** 预设 key -> 当前 locale 显示名（自定义引擎原样返回） */
    private fun engineLabelOf(n: String, k: String): String {
        val key = if (k.isNotEmpty()) k else engineKeyOf(n)
        val r = when (key) {
            "baidu" -> R.string.br_engine_baidu
            "bing" -> R.string.br_engine_bing
            "google" -> R.string.br_engine_google
            "sogou" -> R.string.br_engine_sogou
            "sm" -> R.string.br_engine_sm
            "zhihu" -> R.string.br_engine_zhihu
            else -> 0
        }
        return if (r != 0) act.getString(r) else n
    }

    private fun engineLabel(e: Engine): String = engineLabelOf(e.name, e.key)

    /* ===== 底部抽屉(状态条+设置面板) / 引擎管理 ===== */
    private lateinit var urlView: TextView
    private lateinit var drawerStatus: TextView
    private lateinit var drawerEngineTag: TextView
    internal lateinit var hamburgerMask: View
    internal lateinit var hamburgerPanel: LinearLayout
    internal var hamburgerOpen = false
    private lateinit var tabEngine: TextView
    private lateinit var tabData: TextView
    private lateinit var engineScroll: ScrollView
    private lateinit var engineList: LinearLayout
    private lateinit var dataScroll: ScrollView
    private lateinit var dataBox: LinearLayout
    private val engines = mutableListOf<Engine>()
    private var engineIdx = 0
    @Volatile private var lastKeyword = ""   // 最近一次搜索关键词, 供聚合引擎复用
    private val ENGINE_PREFS = "browser_engines"
    private val blue = Ui.PRIMARY
    private val gray = Color.parseColor("#9AA0A6")

    internal var open = false

    // ===== 供 DebugServer(/v1/browser) 调试接口读取的内部状态 =====
    /** 当前 WebView 地址(未初始化/无页面时返回空串); 加载完成前回显 lastUrl */
    internal val currentUrl: String
        get() {
            val u = runCatching { web.url }.getOrNull()
            return if (!u.isNullOrBlank()) u else lastUrl
        }
    internal val elementCount: Int get() = elements.size
    internal val highlightedIndex: Int get() = elemPos

    /**
     * 同步扫描页面元素: 注入 JS 扫描器并阻塞等待 onElements 回填完成(超时兜底)。
     * 由非 UI 线程调用(DebugServer 工作线程 / LocalEngine 工具线程), 回填在 UI 线程 onElements 完成。
     * 返回识别到的元素数; 页面无地址立即返回当前数。
     */
    internal fun scanSync(timeoutMs: Long): Int {
        val latch = CountDownLatch(1)
        act.runOnUiThread {
            scanLatch = latch
            val u = runCatching { web.url }.getOrNull()
            if (u.isNullOrBlank()) { latch.countDown(); return@runOnUiThread }
            injectScanner()
        }
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
        return elements.size
    }

    /** 提取整页可见文本(截断 3000 字), 供 browser_text 工具: 元素扫不到时让 AI 至少能读到页面内容 */
    internal fun fetchTextSync(timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        act.runOnUiThread {
            textLatch = latch
            val u = runCatching { web.url }.getOrNull()
            if (u.isNullOrBlank()) { latch.countDown(); return@runOnUiThread }
            web.evaluateJavascript("(function(){var t=document.body?document.body.innerText:'';daBridge.onPageText((t||'').slice(0,3000));})();", null)
        }
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
        return textResult.ifBlank { "页面暂无可见文本" }
    }

    /** 滚动浏览器页(正数向下, 负数向上), 供 browser_scroll 工具 */
    internal fun scrollBySync(delta: Int): String {
        act.runOnUiThread { web.evaluateJavascript("window.scrollBy(0,$delta);", null) }
        return "已滚动: $delta"
    }

    /** 当前已识别元素快照(doc 绝对坐标), 供调试接口返回 JSON */
    internal fun elementsSnapshot(): List<Map<String, Any>> =
        elements.mapIndexed { i, e -> mapOf("index" to i, "x" to e.x, "y" to e.y, "w" to e.w, "h" to e.h, "label" to e.label) }

    /** AI 可读元素清单: "共N个: [i]「label」(x,y wxh)", 供 browser_scan 工具返回 */
    internal fun snapshotText(): String {
        if (elements.isEmpty()) return "页面暂无识别到可操作元素(可能动态加载中)。建议: 1)稍等1秒再调 browser_scan 重扫; 2)用 browser_text 读取整页文字; 3)用 browser_scroll 滚动页面后再 browser_scan; 仍无则页面可能纯展示, 可考虑 web_search 补充"
        val sb = StringBuilder("页面共识别 ${elements.size} 个可操作元素:\n")
        for ((i, e) in elements.withIndex()) {
            sb.append("[$i]「${e.label}」 坐标(${e.x},${e.y}) 尺寸${e.w}x${e.h}\n")
        }
        return sb.toString().trimEnd()
    }

    /** 点击浏览器页第 N 个已识别元素: 物理坐标注入(dispatchTouchEvent 模拟真实触摸), 绕开站点 isTrusted 反自动化拦截; 入参元素为中心文档坐标 */
    internal fun clickIndex(i: Int): String {
        val e = elements.getOrNull(i) ?: return "索引越界(共 ${elements.size} 个)"
        val cx = e.x + e.w / 2
        val cy = e.y + e.h / 2
        web.post { physicalTap(cx, cy) }
        return "已发起点击元素[$i]「${e.label}」"
    }

    /** 物理注入一次触摸(DOWN+UP)到 WebView 视口内坐标; 入参为文档坐标, 若不可见先滚动再点 */
    private fun physicalTap(docX: Int, docY: Int) {
        val vw = web.width; val vh = web.height
        if (vw <= 0 || vh <= 0) { paintStatus(act.getString(R.string.br_click_view_not_ready)); return }
        val s = web.scale.toFloat()
        val vx = ((docX - web.scrollX) * s).roundToInt()
        val vy = ((docY - web.scrollY) * s).roundToInt()
        fun fire() {
            val sx = (((docX - web.scrollX) * s).roundToInt()).coerceIn(0, vw - 1)
            val sy = (((docY - web.scrollY) * s).roundToInt()).coerceIn(0, vh - 1)
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx.toFloat(), sy.toFloat(), 0)
            val up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, sx.toFloat(), sy.toFloat(), 0)
            web.onTouchEvent(down)
            web.onTouchEvent(up)
            down.recycle(); up.recycle()
            paintStatus(act.getString(R.string.br_tapped, sx, sy))
        }
        if (vx in 0 until vw && vy in 0 until vh) { fire() }
        else {
            web.scrollTo((docX - vw / 2).coerceAtLeast(0), (docY - vh / 2).coerceAtLeast(0))
            web.postDelayed({ fire() }, 260)
        }
    }

    /** 向浏览器页第 N 个已识别元素(输入框)输入文本(React/Vue 受控组件兼容); data-scan 标记优先定位; 异步发起, 返回指令结果 */
    internal fun typeIndex(i: Int, text: String): String {
        val e = elements.getOrNull(i) ?: return "索引越界(共 ${elements.size} 个)"
        actionResult = ""
        val x = e.x; val y = e.y
        val safe = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        web.evaluateJavascript(
            "(function(){var i=$i,x=$x,y=$y,s=\"$safe\";" +
            "function findScan(i,doc){" +
            "  var el=doc.querySelector('[data-scan=\"'+i+'\"]');" +
            "  if(el) return el;" +
            "  var fs=doc.querySelectorAll('iframe');" +
            "  for(var j=0;j<fs.length;j++){" +
            "    try{var inner=findScan(i,fs[j].contentDocument); if(inner) return inner;}catch(e){}" +
            "  }" +
            "  return null;" +
            "}" +
            "var el=findScan(i,document);" +
            "if(!el){window.scrollTo(0,Math.max(0,y-Math.floor(window.innerHeight/3)));el=document.elementFromPoint(x-window.scrollX,y-window.scrollY);}" +
            "if(!el){daBridge.onActionResult('no-element');return;}" +
            "var isArea=el.tagName==='TEXTAREA';el.focus();" +
            "var setter=(isArea?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype);" +
            "var d=Object.getOwnPropertyDescriptor(setter,'value');" +
            "if(d&&d.set){d.set.call(el,s);}else{el.value=s;}" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "daBridge.onActionResult('typed:'+(el.tagName));})();", null)
        return "已向元素[$i]「${e.label}」发起输入"
    }

    /** 供调试/兜底: 执行任意 JS 表达式并把结果经 onActionResult 回传(EVAL: 前缀), 调用线程非主线程可同步等结果 */
    internal fun evalScript(script: String, done: CountDownLatch) {
        actionResult = ""
        actionLatch = done
        val js = "try{(function(){var __r=(function(){return (" + script + ");})();" +
                 "daBridge.onActionResult('EVAL:'+String(JSON.stringify(__r)));})();}" +
                 "catch(e){daBridge.onActionResult('EVAL:ERR:'+e.message);}"
        web.evaluateJavascript(js, null)
        Thread { try { Thread.sleep(1500); done.countDown() } catch (e: Exception) {} }.start()
    }

    /** 取最近一次 JS 执行结果(去掉 EVAL: 前缀) */
    internal fun lastActionResult(): String {
        val r = actionResult
        return if (r.startsWith("EVAL:")) r.substring(5) else r
    }

    @Volatile
    private var actionLatch: CountDownLatch? = null
    /** scan 同步等待: DebugServer / AI 工具触发重扫时阻塞等 onElements 回填, 防止异步竞态读到旧/空元素 */
    @Volatile private var scanLatch: CountDownLatch? = null
    /** open 页面就绪等待: 记录最近一次 open 触发的加载, onPageFinished 时置完成 */
    @Volatile private var loadDoneLatch: CountDownLatch? = null

    /** 供 MainActivity/未来 AI 引擎调用的公开能力; url 为关键词时自动转百度搜索 */
    internal fun open(url: String? = null) {
        if (url != null) {
            lastUrl = url; loaded = true; paintStatus(act.getString(R.string.br_opening, url))
            loadDoneLatch = CountDownLatch(1)
            web.loadUrl(toLoadableUrl(url))
        } else ensureLoad()
        slideIn()
    }

    /**
     * 等待最近一次 open 的页面加载完成(onPageFinished), 由非 UI 线程调用。
     * 无进行中加载立即返回 true; 超时返回 false。
     */
    internal fun waitLoaded(timeoutMs: Long): Boolean {
        val l = loadDoneLatch ?: return true
        return try { l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }
    }

    /** AI 清除浏览器缓存: full=true 连登录 Cookie 一起清(会退出所有站点登录); 清完强制刷新当前页(主线程执行) */
    internal fun clearCacheForAi(full: Boolean): String {
        val latch = CountDownLatch(1)
        val ret = arrayOfNulls<String>(1)
        act.runOnUiThread {
            try {
                val cm = android.webkit.CookieManager.getInstance()
                if (full) cm.removeAllCookies(null)
                web.clearCache(true)
                if (full) cm.flush()
                web.reload()
                ret[0] = if (full) "已清除全部缓存与登录Cookie, 当前页已刷新" else "已清除页面缓存, 当前页已刷新"
            } catch (e: Exception) {
                ret[0] = "清缓存失败: ${e.message}"
            }
            latch.countDown()
        }
        try { latch.await(4, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { }
        return ret[0] ?: "清缓存操作超时"
    }

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
    private val hostCookieCache = HashMap<String, String>()

    /** 登录态判定已抽至 SiteAuthDetector(纯函数可单测), 见 all/SiteAuthDetector.kt */
    /** 检测当前域 Cookie: 非空、含真实登录态且相对上次有变化则回调 autoSaveCookie */
    private fun tryAutoSaveCookie(url: String?) {
        val host = runCatching { java.net.URI(url ?: "").host }.getOrNull() ?: return
        if (host.isBlank()) return
        val cm = CookieManager.getInstance()
        val ck = listOf("https://$host", "http://$host")
            .mapNotNull { u -> runCatching { cm.getCookie(u) }.getOrNull()?.takeIf { it.isNotBlank() } }
            .firstOrNull().orEmpty()
        if (ck.isNotBlank() && SiteAuthDetector.hasLoginCookies(ck) && ck != hostCookieCache[host]) {
            hostCookieCache[host] = ck
            autoSaveCookie?.invoke(host, ck)
        }
    }
    /** AI 提取登录态 Cookie 存 site_auth: domain 非空按该域取(返回 cookie 原文), 为空取浏览器当前页域名(返回 "域名\tcookie") */
    internal fun cookieStringFor(domain: String?): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        val out = arrayOfNulls<String>(1)
        act.runOnUiThread {
            try {
                val curUrl = runCatching { web.url }.getOrNull() ?: lastUrl
                val host = domain?.trim()?.removePrefix("http://")?.removePrefix("https://")?.substringBefore('/')
                    ?: runCatching { java.net.URI(curUrl).host }.getOrNull()
                if (host.isNullOrBlank()) { out[0] = ""; return@runOnUiThread }
                val cm = android.webkit.CookieManager.getInstance()
                val ck = listOf("https://$host", "http://$host")
                    .mapNotNull { u -> runCatching { cm.getCookie(u) }.getOrNull()?.takeIf { it.isNotBlank() } }
                    .firstOrNull().orEmpty()
                out[0] = if (domain.isNullOrBlank()) "$host\t$ck" else ck
            } catch (e: Exception) { out[0] = "" }
            latch.countDown()
        }
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { }
        return out[0] ?: ""
    }

    /** 非 http(s) 开头的文本视为搜索词, 拼百度搜索; 已是网址原样用 */
    private fun toLoadableUrl(u: String): String {
        val t = u.trim()
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
        if (t.isEmpty()) return engines.getOrNull(engineIdx)?.home ?: "https://www.baidu.com"
        lastKeyword = t
        val e = engines.getOrNull(engineIdx) ?: engines.firstOrNull()
            ?: return "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(t, "UTF-8")
        return e.search.replace("{q}", java.net.URLEncoder.encode(t, "UTF-8"))
    }
    internal fun close() { slideOut() }
    /** 网页后退一步; 无历史可退时返回 false(由调用方决定是否收起浏览器页) */
    /** browser_upload 工具: 把工作目录(Download/Nyral_work)文件注入页面第 N 个 file input;
     *  优先用 browser_scan 的元素索引定位(若该元素是 file input), 否则按页面第 N 个 input[type=file] 定位(默认0) */
    internal fun uploadIndex(i: Int, localName: String): String {
        val ctx: Context = act
        if (!WorkDir.exists(ctx, localName)) return "工作目录不存在该文件: $localName (可先用 workdir_list 查看)"
        val uri = Uri.parse("content://${ctx.packageName}.files/work/${Uri.encode(localName)}")
        pendingUpload = arrayOf(uri)
        val js = "(function(){var idx=$i;" +
            "var target=null;" +
            "var marked=document.querySelector('[data-scan=\"'+idx+'\"]');" +
            "if(marked&&marked.tagName==='INPUT'&&marked.getAttribute('type')==='file'){target=marked;}" +
            "if(!target){var all=document.querySelectorAll('input[type=file]');" +
            "if(all.length===0){daBridge.onActionResult('no-file-input');return;}" +
            "target=all[(idx>=0&&idx<all.length)?idx:0];}" +
            "target.click();" +
            "daBridge.onActionResult('file-input-clicked:'+(target.name||'?'));})();"
        web.evaluateJavascript(js, null)
        return "已发起上传 $localName 到文件选择框[$i](观察页面是否出现文件)"
    }

    internal fun goBack(): Boolean {
        if (web.canGoBack()) { web.goBack(); return true }
        return false
    }
    internal fun setStatus(s: String) { paintStatus(s) }
    internal fun setThink(s: String) {
        act.runOnUiThread {
            thinkBody.text = s
            thinkWrap.visibility = View.VISIBLE
        }
    }
    /** 高亮某元素(doc 绝对坐标), 并滚动使其可见 */
    internal fun highlightElement(x: Int, y: Int, w: Int, h: Int, label: String) {
        highlight.setTarget(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()))
        highlight.updateScroll(web.scrollX, web.scrollY, web.contentHeight, web.height, web.scale.toFloat())
        // 滚动到该元素附近, 验证滚动跟随
        val targetY = (y * web.scale - web.height / 3f).coerceAtLeast(0f).toInt()
        web.post { web.scrollTo(0, targetY) }
        paintStatus(act.getString(R.string.br_highlight_fmt, label.ifEmpty { act.getString(R.string.br_element) }))
    }
    internal fun destroy() { web.destroy() }
    internal fun hideHighlight() { highlight.clearTarget() }

    init {
        highlight = BrowserHighlightView(act).apply { isClickable = false }
        web = WebView(act).apply {
            setBackgroundColor(Color.WHITE)
            // 未接管时网页内容不可点击/不可滚动(用户触摸全拦截), 接管后放行; AI 物理点击走 onTouchEvent 直通不受影响
            setOnTouchListener { _, _ -> if (!taken) true else false }
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")) return false
                    return true // 拦截 intent:// / baiduboxapp:// 等非 http(s) scheme 拉起, 保持 H5 闭环
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // 实时回显当前访问地址(欢迎页/本地页不刷)
                    if (url != null && !url.startsWith("file:///android_asset/home.html")) refreshDrawerUrl(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.startsWith("file:///android_asset/home.html") == true) {
                        paintStatus(act.getString(R.string.br_welcome_page))
                        loadDoneLatch?.countDown(); loadDoneLatch = null
                        return
                    }
                    loadDoneLatch?.countDown(); loadDoneLatch = null
                    tryAutoSaveCookie(url)
                    injectScanner()
                    refreshDrawerUrl(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                /** 文件上传: browser_upload 先设 pendingUpload(工作目录文件 content uri), 点击 file input 触发本回调时直接注入, 不弹系统文件选择器 */
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    val pu = pendingUpload
                    if (pu != null && pu.isNotEmpty() && filePathCallback != null) {
                        pendingUpload = null
                        filePathCallback.onReceiveValue(pu)
                        return true
                    }
                    return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
                }
            }
            addJavascriptInterface(JsBridge(), "daBridge")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, sx, sy, _, _ ->
                    highlight.updateScroll(sx, sy, contentHeight, height, scale.toFloat())
                }
            }
        }

        root = FrameLayout(act).apply {
            setBackgroundColor(Color.WHITE)
            // 初始位于屏幕右外, 右缘左滑整页推入
            translationX = act.resources.displayMetrics.widthPixels.toFloat()
            // 浏览器作为底层内容层(悬浮聊天模式): 不再置顶, 层级由 MainActivity addView 顺序决定(main 在其上)
            elevation = 0f
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(highlight, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))


            // 底部中央"接管"按钮: 交还用户直接点验证码
            takeover = TextView(act).apply {
                text = act.getString(R.string.br_takeover)
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Ui.PRIMARY)
                    cornerRadius = act.dp(20).toFloat()
                }
                elevation = act.dp(4).toFloat()
                setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9))
                setOnClickListener { toggleTakeover() }
                Ui.press(this)
            }
            addView(takeover, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = act.dp(66) })

            // 右侧汉堡面板: 收起=隐藏(初始右外), 右缘左滑展开= 设置页(引擎管理/登录数据/URL/关页)
            hamburgerMask = View(act).apply {
                setBackgroundColor(Color.parseColor("#66000000"))
                visibility = View.GONE
                setOnClickListener { collapseHamburger() }
            }
            addView(hamburgerMask, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            hamburgerPanel = buildHamburger()
            addView(hamburgerPanel, FrameLayout.LayoutParams(
                act.dp(300), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END))
            // 初始右外(不可见): 显式设置 View 属性, 不依赖 LayoutParams.translationX(容器可能不应用)
            hamburgerPanel.translationX = act.dp(300).toFloat()
        }
        loadEngines()
    }

    /** 思考摘要折叠容器（默认隐藏） */
    private fun thinkCollapsed(): LinearLayout {
        thinkWrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(2))
            thinkBody = TextView(act).apply {
                text = act.getString(R.string.br_no_think)
                textSize = 12f
                setTextColor(Color.parseColor("#666666"))
                maxHeight = act.dp(110)
            }
            addView(thinkBody)
        }
        return thinkWrap
    }

    private fun toggleThink() {
        thinkWrap.visibility = if (thinkWrap.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 窗口化模式: 浏览器窗口收缩到聊天内容区(titleBar 下 ~ inputBar 上), 接管/悬浮均保持该尺寸 */
    internal fun setWindowRect(top: Int, height: Int) {
        val lp = root.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = top
        lp.height = height
        root.layoutParams = lp
    }

    /** 恢复全屏铺底(浏览器关闭时复位) */
    internal fun resetWindowRect() {
        val lp = root.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = 0
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        root.layoutParams = lp
    }

    /** 悬浮聊天模式(输入框上方控制条接管)时隐藏浏览器页底部按钮, 接管全幅/汉堡时显示 */
    internal fun setBottomTakeoverVisible(v: Boolean) {
        takeover.visibility = if (v) View.VISIBLE else View.GONE
    }

    internal fun toggleTakeover() {
        taken = !taken
        onTakeoverChange?.invoke(taken)
        if (taken) {
            takeover.text = act.getString(R.string.br_return_ai)
            highlight.clearTarget()
            paintStatus(act.getString(R.string.br_returned_hint))
            Toast.makeText(act, act.getString(R.string.br_taken_hint), Toast.LENGTH_SHORT).show()
        } else {
            takeover.text = act.getString(R.string.br_takeover)
            paintStatus(act.getString(R.string.br_ai_resume))
            Toast.makeText(act, act.getString(R.string.br_returned_ai), Toast.LENGTH_SHORT).show()
        }
    }

    private fun paintStatus(s: String) {
        act.runOnUiThread {
            if (::drawerStatus.isInitialized) drawerStatus.text = s
        }
    }

    /** 首次呼出自动加载默认页(手势路径不经过 open(url) 时兜底) */
    private fun ensureLoad() {
        if (!loaded) {
            loaded = true
            paintStatus(act.getString(R.string.br_welcome_ready))
            loadDoneLatch = CountDownLatch(1)
            web.loadUrl("file:///android_asset/home.html")
        }
    }

    /** 打开/关闭 整页平移动画 */
    private fun slideIn() {
        open = true
        // 动画开始前先让宿主收缩窗口(标题栏下~输入框上), 滑入的即中间尺寸, 避免先全屏再跳变
        onPreOpen?.invoke()
        root.post {
            root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            root.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction {
                    root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                    onOpenChange?.invoke(true)
                }.start()
            root.animate().alpha(1f).setDuration(280).start()
        }
    }
    private fun slideOut() {
        open = false
        if (hamburgerOpen) collapseHamburger()
        highlight.clearTarget()
        root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        root.animate().translationX(act.resources.displayMetrics.widthPixels.toFloat())
            .setDuration(280).setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            }.start()
        onOpenChange?.invoke(false)
    }

    /** WebView 加载完成后注入扫描器: 收集可见可交互元素(doc 绝对坐标) */
    private fun injectScanner() {
        web.evaluateJavascript(
            """(function(){
              var out=[];
              var MAX=150;
              function cleanTxt(t){ t=(t||'').trim(); return t.length>40?t.slice(0,40):t; }
              function push(el,ox,oy){
                if(out.length>=MAX) return;
                try{
                  var r=el.getBoundingClientRect();
                  if(r.width<24||r.height<24) return;
                  var st=el.ownerDocument.defaultView.getComputedStyle(el);
                  if(st.visibility==='hidden'||st.display==='none'||st.opacity==='0') return;
                  var tag=el.tagName;
                  var txt='';
                  if(tag==='INPUT'||tag==='TEXTAREA'){
                    txt=((el.placeholder||'')+(el.name?('('+el.name+')'):'')+(el.value?('当前:'+el.value):''));
                    if(!txt) txt=tag;
                  } else {
                    txt=(el.innerText||el.value||el.getAttribute('aria-label')||el.title||el.alt||'').trim();
                    if(!txt&&tag==='IMG') txt='[图片]';
                    if(!txt&&(tag==='DIV'||tag==='SPAN'||tag==='LI')) {
                      var c=el.querySelector('a,button,img,[role=button]');
                      if(c) txt=(c.innerText||c.getAttribute('aria-label')||c.title||c.alt||'').trim();
                    }
                  }
                  txt=cleanTxt(txt);
                  if(!txt) return;
                  el.setAttribute('data-scan',String(out.length));
                  out.push({x:Math.round(r.left+ox),y:Math.round(r.top+oy),
                            w:Math.round(r.width),h:Math.round(r.height),t:txt});
                }catch(e){}
              }
              function pushFrame(fr,ox,oy){
                if(out.length>=MAX) return;
                try{
                  var r=fr.getBoundingClientRect();
                  if(r.width<24||r.height<24) return;
                  var src=fr.getAttribute('src')||'';
                  var host='';
                  try{ host=src.match(/^https?:\/\/([^\/?#]+)/)[1]||''; }catch(e){}
                  var txt=host?(host+' 内嵌页面'):'内嵌页面';
                  if(src) txt=txt+' ['+src.slice(0,70)+']';
                  fr.setAttribute('data-scan',String(out.length));
                  out.push({x:Math.round(r.left+ox),y:Math.round(r.top+oy),
                            w:Math.round(r.width),h:Math.round(r.height),t:txt});
                }catch(e){}
              }
              function scanDoc(doc,win,ox,oy){
                if(!doc||out.length>=MAX) return;
                var sel='a,button,input,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[contenteditable],[tabindex],[onclick],[data-action],[data-testid],[data-clickable],li';
                try{ doc.querySelectorAll(sel).forEach(function(el){push(el,ox,oy);}); }catch(e){}
                if(out.length<MAX){
                  try{
                    doc.querySelectorAll('div,span').forEach(function(el){
                      if(out.length>=MAX) return;
                      if(el.children.length>3) return;
                      var st=el.ownerDocument.defaultView.getComputedStyle(el);
                      if(st.cursor!=='pointer') return;
                      push(el,ox,oy);
                    });
                  }catch(e){}
                }
                // iframe 穿透
                try{
                  doc.querySelectorAll('iframe').forEach(function(fr){
                    if(out.length>=MAX) return;
                    var frr=fr.getBoundingClientRect();
                    var fxo=ox+frr.left+(win?win.scrollX:0);
                    var fyo=oy+frr.top+(win?win.scrollY:0);
                    var inner=null;
                    try{ inner=fr.contentDocument; }catch(e){ inner=null; }
                    if(inner&&inner!==doc){
                      pushFrame(fr,fxo,fyo);
                      scanDoc(inner,fr.contentWindow,fxo,fyo);
                    } else {
                      pushFrame(fr,fxo,fyo);
                    }
                  });
                }catch(e){}
                // shadow DOM 穿透
                try{
                  doc.querySelectorAll('*').forEach(function(el){
                    if(out.length>=MAX) return;
                    if(el.shadowRoot){
                      var sr=el.getBoundingClientRect();
                      scanDoc(el.shadowRoot,win,ox+sr.left+(win?win.scrollX:0),oy+sr.top+(win?win.scrollY:0));
                    }
                  });
                }catch(e){}
              }
              function doScan(){
                out=[];
                scanDoc(document,window,0,0);
                window.scrollTo(0,0);
                daBridge.onElements(JSON.stringify(out));
              }
              var steps=0;
              function warm(){
                steps++;
                window.scrollTo(0, document.body.scrollHeight);
                if(steps<3){ setTimeout(warm, 200); }
                else { window.scrollTo(0,0); setTimeout(doScan, 200); }
              }
              setTimeout(warm, 150);
            })();""", null)
    }

    /** JS 桥: 收集页面元素 -> 高亮第一个候选并播报 */
    private inner class JsBridge {
        /** 来源白名单: 仅允许 http/https 页面回调 daBridge, 阻断 data:/file:/javascript: 注入面
         *  注意: JavascriptInterface 回调在后台线程, WebView.getUrl() 必须在主线程读取,
         *  否则非主线程访问返回 null 导致所有回调被误丢弃(scan/text/eval 全空)。 */
        private fun sourceOk(): Boolean {
            val holder = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            val latch = CountDownLatch(1)
            act.runOnUiThread {
                try {
                    val u = web.url
                    holder.set(u != null && (u.startsWith("http://") || u.startsWith("https://")))
                } catch (e: Exception) { holder.set(false) }
                latch.countDown()
            }
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
            return holder.get() == true
        }

        @android.webkit.JavascriptInterface
        fun onActionResult(json: String) { if (!sourceOk()) return; actionResult = json; actionLatch?.countDown() }

        @android.webkit.JavascriptInterface
        fun onPageText(t: String) {
            if (!sourceOk()) return
            act.runOnUiThread { textResult = t; textLatch?.countDown(); textLatch = null }
        }

        @android.webkit.JavascriptInterface
        fun onElements(json: String) {
            if (!sourceOk()) return
            act.runOnUiThread {
                elements.clear(); elemPos = -1
                try {
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        elements.add(Element(o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"), o.optString("t")))
                    }
                } catch (e: Exception) {}
                paintStatus(act.getString(R.string.br_scanned, elements.size))
                nextElement()
                scanLatch?.countDown()
                scanLatch = null
            }
        }

        @android.webkit.JavascriptInterface
        fun homeSearch(q: String) {
            if (!sourceOk()) return
            act.runOnUiThread { open(q) }
        }

        @android.webkit.JavascriptInterface
        fun homeOpen(url: String) {
            if (!sourceOk()) return
            act.runOnUiThread { web.loadUrl(url); paintStatus(act.getString(R.string.br_opened, url)) }
        }
    }

    /** 高亮下一个元素（模拟 AI 逐步操作, 后续对接真 Agent） */
    internal fun nextElement() {
        if (elements.isEmpty()) { paintStatus(act.getString(R.string.br_no_elements)); return }
        val i = (elemPos + 1) % elements.size
        elemPos = i
        val e = elements[i]
        highlightElement(e.x, e.y, e.w, e.h, e.label)
        setThink(act.getString(R.string.br_candidate, i + 1, elements.size, e.label, e.x, e.y, e.w, e.h))
    }

    /** 高亮指定索引候选元素(0-based, 供调试接口按 index 定位); 越界/列表空则忽略 */
    internal fun highlightIndex(n: Int) {
        if (elements.isEmpty()) { paintStatus(act.getString(R.string.br_no_elements)); return }
        if (n < 0 || n >= elements.size) { paintStatus(act.getString(R.string.br_idx_oob, n, elements.size)); return }
        elemPos = n
        val e = elements[n]
        highlightElement(e.x, e.y, e.w, e.h, e.label)
        setThink(act.getString(R.string.br_candidate, n + 1, elements.size, e.label, e.x, e.y, e.w, e.h))
    }

    /** 按坐标(doc 绝对)命中最近候选元素并高亮(供 /v1/browser/highlight/xy), 返回命中的元素描述 */
    internal fun highlightNear(x: Int, y: Int): String {
        if (elements.isEmpty()) return "页面暂无可用元素"
        var best = -1; var bestDist = Int.MAX_VALUE
        for ((i, e) in elements.withIndex()) {
            val inside = x >= e.x && x <= e.x + e.w && y >= e.y && y <= e.y + e.h
            val cx = e.x + e.w / 2; val cy = e.y + e.h / 2
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (inside || d < bestDist) { bestDist = d; best = i }
            if (inside) break
        }
        if (best < 0) return "坐标($x,$y) 未命中任何元素"
        highlightIndex(best)
        return "已高亮 $best"
    }

    /* ===================== 底部导航栏 / 引擎管理 / 本地数据 ===================== */

    /** 右侧汉堡面板: 初始右外隐藏, 右缘左滑展开= URL + 设置页(引擎管理/登录数据) */
    private fun buildHamburger(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = act.dp(18).toFloat()
        }
        elevation = act.dp(8).toFloat()

        // —— 头部: 状态 + 默认引擎 + 收起 ——
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(12), act.dp(9), act.dp(8), act.dp(9))
            addView(TextView(act).apply {
                text = "●"; textSize = 9f; setTextColor(Color.parseColor("#22C55E"))
                setPadding(0, 0, act.dp(6), 0)
            })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_idle); textSize = 12f; maxLines = 1
                setTextColor(Color.parseColor("#333333"))
                setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { drawerStatus = it })
            addView(TextView(act).apply {
                text = (engines.getOrNull(engineIdx)?.let { engineLabel(it) } ?: "") + " ▾"; textSize = 12f; setTextColor(blue)
                setPadding(act.dp(8), act.dp(3), act.dp(4), act.dp(3))
            }.also { drawerEngineTag = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_collapse); textSize = 12f; setTextColor(gray)
                setPadding(act.dp(8), act.dp(4), act.dp(2), act.dp(4))
                setOnClickListener { collapseHamburger() }
                Ui.press(this)
            })
        }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        // —— 当前访问地址(实时回显, 只读) + 一键复制 ——
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(act).apply {
                text = "—"
                textSize = 12f; maxLines = 1
                setTextColor(Ui.TEXT)
                setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2F4F7")); cornerRadius = act.dp(12).toFloat()
                }
                setPadding(act.dp(10), act.dp(4), act.dp(10), act.dp(4))
                layoutParams = LinearLayout.LayoutParams(0, act.dp(34), 1f)
            }.also { urlView = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_copy); textSize = 12f; setTextColor(blue)
                setPadding(act.dp(6), act.dp(4), act.dp(2), act.dp(4))
                setOnClickListener { copyCurrentUrl() }
                Ui.press(this)
            })
        }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        // 页签: 引擎管理 / 登录数据 / ✕ 关页
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(act).apply {
                text = act.getString(R.string.br_engine_mgr); textSize = 12f; setTextColor(blue)
                setOnClickListener { showEngineTab() }
                setPadding(act.dp(8), act.dp(5), act.dp(8), act.dp(5))
                Ui.press(this)
            }.also { tabEngine = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_login_data); textSize = 12f; setTextColor(gray)
                setOnClickListener { showDataTab() }
                setPadding(act.dp(8), act.dp(5), act.dp(8), act.dp(5))
                Ui.press(this)
            }.also { tabData = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_think); textSize = 12f; setTextColor(blue)
                setOnClickListener { toggleThink() }
                setPadding(act.dp(8), act.dp(5), act.dp(8), act.dp(5))
                Ui.press(this)
            })
            addView(TextView(act).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_close_page); textSize = 12f; setTextColor(Ui.DANGER)
                setPadding(act.dp(6), act.dp(4), act.dp(2), act.dp(4))
                setOnClickListener { slideOut() }
                Ui.press(this)
            })
        }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        // 思考摘要容器(默认隐藏, 点"思考▾"展开)
        addView(thinkCollapsed())

        // 引擎管理面板
        engineScroll = ScrollView(act).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        engineList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        engineScroll.addView(engineList)
        addView(engineScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(196)))

        // 登录数据面板(默认隐藏)
        dataScroll = ScrollView(act).apply { isFillViewport = false; isVerticalScrollBarEnabled = false; visibility = View.GONE }
        dataBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        dataScroll.addView(dataBox)
        addView(dataScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(196)))
        refreshEngineList()
        initDataView()
    }


    /** 右缘左滑展开汉堡面板: 显示遮罩+面板平移到 0 */
    internal fun expandHamburger() {
        if (hamburgerOpen) return
        hamburgerOpen = true
        hamburgerMask.alpha = 0f
        hamburgerMask.visibility = View.VISIBLE
        onHamburgerChange?.invoke(true)
        hamburgerMask.animate().alpha(1f).setDuration(180).start()
        hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        hamburgerPanel.animate().translationX(0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            }.start()
        refreshDataView()
    }

    /** 收起汉堡面板: 隐藏遮罩+面板平移回右外 */
    internal fun collapseHamburger() {
        if (!hamburgerOpen) return
        hamburgerOpen = false
        onHamburgerChange?.invoke(false)
        hamburgerMask.animate().alpha(0f).setDuration(160).withEndAction {
            hamburgerMask.visibility = View.GONE
        }.start()
        hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        hamburgerPanel.animate().translationX(act.dp(300).toFloat()).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            }.start()
    }
    private fun showEngineTab() {
        engineScroll.visibility = View.VISIBLE; dataScroll.visibility = View.GONE
        tabEngine.setTextColor(blue); tabData.setTextColor(gray)
    }
    private fun showDataTab() {
        engineScroll.visibility = View.GONE; dataScroll.visibility = View.VISIBLE
        tabEngine.setTextColor(gray); tabData.setTextColor(blue)
        refreshDataView()
    }


    /** 引擎管理列表: ★=当前默认, 点击设为默认; 每行可改/删 */
    private fun refreshEngineList() {
        if (!::engineList.isInitialized) return
        engineList.removeAllViews()

        engineList.addView(TextView(act).apply {
            text = act.getString(R.string.br_add_engine_btn)
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
            background = GradientDrawable().apply { setColor(blue); cornerRadius = act.dp(10).toFloat() }
            setOnClickListener { showEditEngineDialog(-1) }
            Ui.press(this)
        })

        for ((i, e) in engines.withIndex()) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(2), act.dp(3), act.dp(2), act.dp(3))
            }
            row.addView(TextView(act).apply {
                text = (if (i == engineIdx) "★ " else "  ") + engineLabel(e)
                textSize = 13f
                setTextColor(if (i == engineIdx) blue else Color.parseColor("#333333"))
                setTypeface(typeface, if (i == engineIdx) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (i != engineIdx) {
                        engineIdx = i; saveEngines(); refreshEngineLabel(); refreshEngineList()
                        paintStatus(act.getString(R.string.br_default_engine, e.name))
                    }
                }
            })
            row.addView(TextView(act).apply {
                text = act.getString(R.string.br_edit); textSize = 12f; setTextColor(gray)
                setPadding(act.dp(8), act.dp(2), act.dp(4), act.dp(2))
                setOnClickListener { showEditEngineDialog(i) }
                Ui.press(this)
            })
            row.addView(TextView(act).apply {
                text = act.getString(R.string.br_delete); textSize = 12f; setTextColor(Ui.DANGER)
                setPadding(act.dp(8), act.dp(2), act.dp(4), act.dp(2))
                setOnClickListener {
                    if (engines.size <= 1) { Toast.makeText(act, act.getString(R.string.br_keep_one), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    engines.removeAt(i)
                    if (engineIdx >= engines.size) engineIdx = engines.size - 1
                    saveEngines(); refreshEngineLabel(); refreshEngineList()
                }
                Ui.press(this)
            })
            engineList.addView(row)
            engineList.addView(View(act).apply {
                background = GradientDrawable().apply { setColor(Color.parseColor("#E8EAED")); setSize(1, 1) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }    }

    /** 添加(idx=-1)/编辑(idx>=0) 引擎: 名称 + 搜索模板(必须含 {q}) + 首页(可空) */
    private fun showEditEngineDialog(idx: Int) {
        val isEdit = idx in engines.indices
        val src = if (isEdit) engines[idx] else null
        Dialog(act).apply {
            setTitle(act.getString(if (isEdit) R.string.br_edit_engine else R.string.br_add_engine))
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(12))
            }
            val nameIn = EditText(act).apply { hint = act.getString(R.string.br_name_hint); textSize = 14f }
            val tmplIn = EditText(act).apply { hint = act.getString(R.string.br_tmpl_hint); textSize = 14f }
            val homeIn = EditText(act).apply { hint = act.getString(R.string.br_home_hint); textSize = 14f }
            if (src != null) { nameIn.setText(engineLabel(src)); tmplIn.setText(src.search); homeIn.setText(src.home) }
            box.addView(nameIn)
            box.addView(tmplIn)
            box.addView(homeIn)
            LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(act).apply {
                    text = act.getString(R.string.br_cancel); textSize = 14f; setTextColor(gray); gravity = Gravity.CENTER
                    setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { dismiss() }
                    Ui.press(this)
                })
                addView(TextView(act).apply {
                    text = act.getString(R.string.br_save); textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
                    background = GradientDrawable().apply { setColor(blue); cornerRadius = act.dp(12).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val n = nameIn.text.toString().trim()
                        var s = tmplIn.text.toString().trim()
                        val h0 = homeIn.text.toString().trim()
                        if (n.isEmpty() || s.isEmpty()) { Toast.makeText(act, act.getString(R.string.br_name_tmpl_empty), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                        if (!s.contains("{q}")) { Toast.makeText(act, act.getString(R.string.br_tmpl_need_q), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s
                        val h = if (h0.isEmpty()) s.substringBefore("{q}").trimEnd('&', '?') else
                            if (h0.startsWith("http://") || h0.startsWith("https://")) h0 else "https://" + h0
                        if (isEdit) engines[idx] = Engine(n, s, h, src?.let { if (n == engineLabel(it)) it.key else "" } ?: "") else { engines.add(Engine(n, s, h, "")); engineIdx = engines.size - 1 }
                        saveEngines(); refreshEngineLabel(); refreshEngineList()
                        Toast.makeText(act, act.getString(R.string.br_saved), Toast.LENGTH_SHORT).show(); dismiss()
                    }
                    Ui.press(this)
                })
            }.also { box.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
            setContentView(box)
            show()
        }
    }

    /** 登录数据面板: site_auth 登录站点逐站列出/清除 + 一键清除全部登录态与缓存 */
    private fun initDataView() { refreshDataView() }
    private fun refreshDataView() {
        if (!::dataBox.isInitialized) return
        dataBox.removeAllViews()
        dataBox.addView(TextView(act).apply {
            text = act.getString(R.string.br_data_intro)
            textSize = 12f; setTextColor(Color.parseColor("#555555"))
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F2F4F7")); cornerRadius = act.dp(10).toFloat() }
        })
        val auth = parseSiteAuth()
        if (auth == null || auth.length() == 0) {
            dataBox.addView(TextView(act).apply {
                text = act.getString(R.string.br_no_sites)
                textSize = 13f; setTextColor(gray); gravity = Gravity.CENTER
                setPadding(act.dp(10), act.dp(14), act.dp(10), act.dp(14))
            })
        } else {
            val keys = ArrayList<String>()
            val it = auth.keys()
            while (it.hasNext()) keys.add(it.next() as String)
            val metaTs = auth.optLong("__updated_at", 0L)
            if (metaTs > 0) {
                val ts = try {
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(metaTs))
                } catch (e: Exception) { metaTs.toString() }
                dataBox.addView(TextView(act).apply {
                    text = act.getString(R.string.br_recent_saved, ts)
                    textSize = 11f; setTextColor(gray)
                    setPadding(act.dp(10), act.dp(2), act.dp(10), act.dp(2))
                })
            }
            for (site in keys.sorted()) {
                if (site.startsWith("__")) continue
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(4), act.dp(3), act.dp(4), act.dp(3))
                }
                row.addView(TextView(act).apply {
                    text = site
                    textSize = 13f; setTextColor(Ui.TEXT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(act).apply {
                    text = maskCookie(auth.optString(site))
                    textSize = 11f; setTextColor(gray)
                    setPadding(act.dp(4), act.dp(2), act.dp(4), act.dp(2))
                })
                row.addView(TextView(act).apply {
                    text = act.getString(R.string.br_clear); textSize = 12f; setTextColor(Ui.DANGER)
                    setPadding(act.dp(8), act.dp(2), act.dp(4), act.dp(2))
                    setOnClickListener {
                        val r = WebTools.siteAuth(act, "{\"action\":\"del\",\"site\":\"$site\"}")
                        if (r.contains("已删除")) {
                            expiresDomainCookie(site)
                            Toast.makeText(act, act.getString(R.string.br_site_cleared, site), Toast.LENGTH_SHORT).show()
                            refreshDataView()
                        } else Toast.makeText(act, r, Toast.LENGTH_SHORT).show()
                    }
                    Ui.press(this)
                })
                dataBox.addView(row)
                dataBox.addView(View(act).apply {
                    background = GradientDrawable().apply { setColor(Color.parseColor("#E8EAED")); setSize(1, 1) }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }
        dataBox.addView(TextView(act).apply {
            text = act.getString(R.string.br_clear_all)
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
            background = GradientDrawable().apply { setColor(Ui.DANGER); cornerRadius = act.dp(12).toFloat() }
            setOnClickListener {
                runCatching { WorkDir.write(act, "site_auth.json", "{}".toByteArray(Charsets.UTF_8)) }
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                web.clearCache(true)
                Toast.makeText(act, act.getString(R.string.br_cleared_all), Toast.LENGTH_SHORT).show()
                refreshDataView()
            }
            Ui.press(this)
        })
    }

    /** 读取 site_auth.json 全部站点登录态; 缺失/损坏返回 null */
    private fun parseSiteAuth(): org.json.JSONObject? {
        val bytes = WorkDir.read(act, "site_auth.json") ?: return null
        return runCatching { org.json.JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
    }

    private fun maskCookie(c: String): String {
        val t = c.trim()
        return if (t.length <= 12) "***" else t.take(8) + "…" + t.takeLast(6)
    }

    /** 把指定站点在 WebView 里的 Cookie 逐条置过期(等效清除该站 Cookie) */
    private fun expiresDomainCookie(site: String) {
        val cm = android.webkit.CookieManager.getInstance()
        for (scheme in listOf("https://$site", "http://$site")) {
            val ck = runCatching { cm.getCookie(scheme) }.getOrNull() ?: continue
            if (ck.isBlank()) continue
            for (pair in ck.split(";")) {
                val k = pair.trim().substringBefore("=").trim()
                if (k.isNotBlank()) cm.setCookie(scheme, "$k=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/", null)
            }
        }
    }

    /** 复制当前访问地址到剪贴板 */
    private fun copyCurrentUrl() {
        val u = urlView.text.toString().trim()
        if (u.isEmpty() || u == "—") { Toast.makeText(act, act.getString(R.string.br_nothing_copy), Toast.LENGTH_SHORT).show(); return }
        val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("browser_url", u))
        Toast.makeText(act, act.getString(R.string.br_copied, u), Toast.LENGTH_SHORT).show()
    }

    /** 底部状态条实时回显当前访问站点 */
    private fun refreshDrawerUrl(url: String?) {
        val s = url ?: return
        val host = runCatching { java.net.URI(s).host }.getOrNull()
        act.runOnUiThread {
            if (::drawerStatus.isInitialized)
                drawerStatus.text = if (host != null) act.getString(R.string.br_visiting, host) else s
            if (::urlView.isInitialized) urlView.text = s
        }
    }

    private fun refreshEngineLabel() {
        val e = engines.getOrNull(engineIdx)?.let { engineLabel(it) } ?: "──"
        if (::drawerEngineTag.isInitialized) drawerEngineTag.text = "$e ▾"
    }

    /** 切到另一引擎, 复用当前关键词(空则取搜索框文本, 仍空则开其首页) */
    private fun gotoEngine(i: Int) {
        if (i !in engines.indices) return
        val kw = lastKeyword
        val e = engines[i]
        val url = if (kw.isEmpty()) e.home else e.search.replace("{q}", java.net.URLEncoder.encode(kw, "UTF-8"))
        web.loadUrl(url)
        engineIdx = i
        if (kw.isNotEmpty()) lastKeyword = kw
        saveEngines(); refreshEngineLabel()
        paintStatus(act.getString(R.string.br_search_fmt, e.name, kw.ifEmpty { act.getString(R.string.br_home) }))
    }

    /** 本地数据管理入口: v1 说明展示 + 一键清除 WebView 登录会话 */
    private fun showDataDialog() {
        val e = engines.getOrNull(engineIdx)
        val body = StringBuilder()
        body.append(act.getString(R.string.br_dialog_body1))
        body.append(act.getString(R.string.br_dialog_body2))
        body.append(act.getString(R.string.br_dialog_body3, e?.name ?: act.getString(R.string.br_none)))
        body.append(act.getString(R.string.br_dialog_body4))
        Dialog(act).apply {
            setTitle(act.getString(R.string.br_local_data))
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(act.dp(18), act.dp(10), act.dp(18), act.dp(14))
            }
            box.addView(TextView(act).apply {
                text = body.toString()
                textSize = 13f
                setTextColor(Color.parseColor("#555555"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2F4F7"))
                    cornerRadius = act.dp(10).toFloat()
                }
                setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
            })
            box.addView(TextView(act).apply {
                text = act.getString(R.string.br_clear_all_sessions)
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
                background = GradientDrawable().apply {
                    setColor(Ui.DANGER)
                    cornerRadius = act.dp(12).toFloat()
                }
                setOnClickListener {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    web.clearCache(true)
                    Toast.makeText(act, act.getString(R.string.br_cleared_all), Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                Ui.press(this)
            })
            setContentView(box)
            show()
        }
    }

    /* ===================== 引擎配置持久化(SharedPreferences) ===================== */

    /** 内置预设引擎: 百度/必应/谷歌/搜狗/神马/知乎 */
    private fun presetEngines(): String =
        """[
          {"n":"百度","k":"baidu","s":"https://www.baidu.com/s?wd={q}","h":"https://www.baidu.com"},
          {"n":"必应","k":"bing","s":"https://www.bing.com/search?q={q}","h":"https://www.bing.com"},
          {"n":"谷歌","k":"google","s":"https://www.google.com/search?q={q}","h":"https://www.google.com"},
          {"n":"搜狗","k":"sogou","s":"https://www.sogou.com/web?query={q}","h":"https://www.sogou.com"},
          {"n":"神马","k":"sm","s":"https://m.sm.cn/s?q={q}","h":"https://m.sm.cn"},
          {"n":"知乎","k":"zhihu","s":"https://www.zhihu.com/search?type=content&q={q}","h":"https://www.zhihu.com"}]"""

    private fun loadEngines() {
        val sp = act.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE)
        var saved = sp.getString("engines", null)
        if (saved.isNullOrBlank()) saved = presetEngines()
        try {
            engines.clear()
            val arr = org.json.JSONArray(saved)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val n = o.getString("n")
                val k = o.optString("k", "")
                engines.add(Engine(engineLabelOf(n, k), o.getString("s"), o.optString("h", o.getString("s")), if (k.isNotEmpty()) k else engineKeyOf(n)))
            }
        } catch (e: Exception) {
            engines.clear()
            engines.add(Engine(act.getString(R.string.br_engine_baidu), "https://www.baidu.com/s?wd={q}", "https://www.baidu.com", "baidu"))
        }
        engineIdx = sp.getInt("idx", 0).coerceIn(0, (engines.size - 1).coerceAtLeast(0))
        refreshEngineLabel()
        refreshEngineList()
    }

    private fun saveEngines() {
        val arr = org.json.JSONArray()
        for (e in engines) arr.put(org.json.JSONObject().put("n", e.name).put("k", e.key).put("s", e.search).put("h", e.home))
        act.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("engines", arr.toString()).putInt("idx", engineIdx).apply()
    }
}

/** 高亮圈层: 全屏透明覆盖, 在目标矩形外画半透明遮罩 + 圆角描边高亮, 跟随滚动 */
internal class BrowserHighlightView(context: Context) : View(context) {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#40000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#FF7043")
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        color = Color.parseColor("#FFAB40")
    }
    private var target: RectF? = null
    private var scrollX = 0
    private var scrollY = 0
    private var docH = 0
    private var viewH = 0
    private var scale = 1f

    fun setTarget(r: RectF) { target = r; invalidate() }
    fun updateScroll(sx: Int, sy: Int, docHeight: Int, viewHeight: Int, s: Float) {
        scrollX = sx; scrollY = sy; docH = docHeight; viewH = viewHeight; scale = s; invalidate()
    }
    fun clearTarget() { target = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val t = target ?: return
        // doc 坐标 -> 视口坐标（减滚动偏移, 实现滚动跟随）
        val s = scale
        val top = t.top * s - scrollY
        val bottom = t.bottom * s - scrollY
        val left = t.left * s - scrollX
        val right = t.right * s - scrollX
        // 超出视口的元素裁剪掉
        if (bottom < 0 || top > viewH) return
        val r = RectF(left, top, right, bottom)
        // 目标外遮罩(四块)
        c.drawRect(0f, 0f, width.toFloat(), r.top.coerceAtLeast(0f), maskPaint)
        c.drawRect(0f, r.bottom.coerceAtMost(height.toFloat()), width.toFloat(), height.toFloat(), maskPaint)
        c.drawRect(0f, r.top.coerceAtLeast(0f), r.left.coerceAtLeast(0f), r.bottom.coerceAtMost(height.toFloat()), maskPaint)
        c.drawRect(r.right.coerceAtMost(width.toFloat()), r.top.coerceAtLeast(0f), width.toFloat(), r.bottom.coerceAtMost(height.toFloat()), maskPaint)
        // 圆角高亮框 + 四角加粗
        val rr = RectF(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f)
        c.drawRoundRect(rr, 12f, 12f, borderPaint)
        val L = 26f
        c.drawLine(rr.left, rr.top + L, rr.left, rr.top, cornerPaint)
        c.drawLine(rr.left, rr.top, rr.left + L, rr.top, cornerPaint)
        c.drawLine(rr.right - L, rr.top, rr.right, rr.top, cornerPaint)
        c.drawLine(rr.right, rr.top, rr.right, rr.top + L, cornerPaint)
        c.drawLine(rr.right, rr.bottom - L, rr.right, rr.bottom, cornerPaint)
        c.drawLine(rr.right, rr.bottom, rr.right - L, rr.bottom, cornerPaint)
        c.drawLine(rr.left + L, rr.bottom, rr.left, rr.bottom, cornerPaint)
        c.drawLine(rr.left, rr.bottom, rr.left, rr.bottom - L, cornerPaint)
        invalidate()
    }
}

/**
 * 浏览器页右侧跟手滑出手势控制器（镜像 DrawerDragController）：
 * - 关闭态: 右缘 EDGE_DP 内按下左滑 -> 整页从右往左推入（translationX: +screenW -> 0）
 * - 打开态: 页面内左缘 EDGE_DP 内按下右滑 -> 整页往右推回（0 -> +screenW）
 * 与左抽屉(左缘右滑)区域/方向互补, 互不冲突
 */
internal class BrowserSlideController(private val act: MainActivity) {
    private companion object {
        const val EDGE_DP = 48          // 触发区宽度
        const val FLING_VX = 500f       // 吸附速度阈值(px/s)
        const val SNAP_FRAC = 0.5f      // 吸附位置阈值
    }
    private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
    private var tracker: android.view.VelocityTracker? = null
    private var mode = 0               // 0=无 1=待开(关闭态右缘按下) 2=待关(打开态左缘按下) 3=汉堡跟手(打开态右缘按下) 4=收汉堡(汉堡开且左缘按下)
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startTrans = 0f
    private val w get() = act.browserPage.root.translationX.coerceAtLeast(0f)

    private fun panel() = act.browserPage.root

    fun isDragging() = dragging

    fun onIntercept(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = android.view.VelocityTracker.obtain()
                tracker?.addMovement(ev)
                dragging = false
                downX = ev.rawX
                downY = ev.rawY
                val openNow = act.browserPage.open
                val sw = act.resources.displayMetrics.widthPixels
                // 三分区触发: 左份=左1/3(收/关), 右份=右1/3(开/汉堡面板), 中间1/3公共区不拦截
                val leftThird = sw / 3f
                val rightThird = sw * 2f / 3f
                mode = when {
                    act.tokenMask.visibility == View.VISIBLE -> 0
                    // 主页汉堡页(抽屉)打开时浏览器手势让位: 右缘左滑归抽屉关闭, 避免误开浏览器
                    act.drawerOpen -> 0
                    act.browserPage.hamburgerOpen && ev.rawX <= leftThird -> 4
                    act.browserPage.hamburgerOpen && ev.rawX >= rightThird -> 3
                    openNow && ev.rawX <= leftThird -> 2
                    openNow && ev.rawX >= rightThird -> 3
                    !openNow && ev.rawX >= rightThird -> 1
                    else -> 0
                }
                startTrans = if (mode == 3 || mode == 4) act.browserPage.hamburgerPanel.translationX
                              else panel().translationX
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode != 0 && !dragging) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val wantLeft = when (mode) {
                        1 -> true
                        2, 4 -> false
                        3 -> !act.browserPage.hamburgerOpen
                        else -> false
                    }
                    val dirOk = if (wantLeft) dx < 0 else dx > 0
                    if (abs(dx) > slop && abs(dx) > abs(dy) && dirOk) {
                        dragging = true
                        // 跟手平移期间开硬件层: WebView 以纹理平移, 避免露白边闪白
                        act.browserPage.root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        tracker?.addMovement(ev)
                        return true
                    }
                }
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> reset()
        }
        return false
    }

    fun onTouch(ev: MotionEvent): Boolean {
        if (!dragging) return false
        val isBurger = mode == 3 || mode == 4
        val burgerW = act.dp(300).toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (isBurger) {
                    // 汉堡面板: 未开(300)左滑=向左展开; 已开(0)左滑=向右推回收起
                    val dx = ev.rawX - downX
                    val trans = (startTrans + dx).coerceIn(0f, burgerW)
                    act.browserPage.hamburgerPanel.translationX = trans
                } else {
                    // 整页: 关闭态从 +screenW 向左推进; 打开态从 0 向右推出
                    val sw = act.resources.displayMetrics.widthPixels
                    val trans = (startTrans + (ev.rawX - downX)).coerceIn(0f, sw.toFloat())
                    panel().translationX = trans
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                if (isBurger) {
                    val frac = (burgerW - act.browserPage.hamburgerPanel.translationX) / burgerW // 展开比例
                    val open = when {
                        vx > FLING_VX -> false
                        vx < -FLING_VX -> !act.browserPage.hamburgerOpen  // 已开时左滑 fling=关闭
                        frac > SNAP_FRAC -> true
                        else -> false
                    }
                    snapBurger(open)
                } else {
                    val sw = act.resources.displayMetrics.widthPixels
                    val frac = (sw - panel().translationX) / sw   // 显现比例
                    val open = when {
                        vx > FLING_VX -> false
                        vx < -FLING_VX -> true
                        frac > SNAP_FRAC -> true
                        else -> false
                    }
                    snap(open)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isBurger) snapBurger(act.browserPage.hamburgerOpen)
                else snap(act.browserPage.open)
                reset()
            }
        }
        return true
    }

    /** 汉堡面板吸附: 展开动画到 0 / 收起动画回右外 */
    private fun snapBurger(open: Boolean) {
        if (open) act.browserPage.expandHamburger()
        else act.browserPage.collapseHamburger()
    }

    /** 抬手吸附到开/关: 打开时走 browserPage.open()(加载默认页+整页动画), 关闭时走 browserPage.close() */
    private fun snap(open: Boolean) {
        val sw = act.resources.displayMetrics.widthPixels
        if (open) {
            act.browserPage.open()
            return
        }
        val target = sw.toFloat()
        val cur = panel().translationX
        val dist = abs(target - cur)
        val dur = (170 + 130 * (dist / sw)).toLong().coerceIn(150, 300)
        val dec = DecelerateInterpolator(1.3f)
        act.browserPage.open = false
        act.browserPage.onOpenChange?.invoke(false)
        panel().animate().translationX(sw.toFloat()).setDuration(dur).setInterpolator(dec)
            .withEndAction {
                act.browserPage.root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                act.browserPage.hideHighlight()
            }.start()
    }

    /** 返回键/✕ 关闭: 走 BrowserPage.close() */
    internal fun close() { act.browserPage.close() }

    private fun reset() {
        mode = 0
        dragging = false
        tracker?.recycle()
        tracker = null
    }
}
