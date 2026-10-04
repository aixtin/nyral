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

internal data class Element(val x: Int, val y: Int, val w: Int, val h: Int, val label: String)

/**
 * 同步扫描页面元素: 注入 JS 扫描器并阻塞等待 onElements 回填完成(超时兜底)。
 * 由非 UI 线程调用(DebugServer 工作线程 / LocalEngine 工具线程), 回填在 UI 线程 onElements 完成。
 * 返回识别到的元素数; 页面无地址立即返回当前数。
 */
internal fun BrowserPage.scanSync(timeoutMs: Long): Int {
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
internal fun BrowserPage.fetchTextSync(timeoutMs: Long): String {
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
internal fun BrowserPage.scrollBySync(delta: Int): String {
    act.runOnUiThread { web.evaluateJavascript("window.scrollBy(0,$delta);", null) }
    return "已滚动: $delta"
}

/** 当前已识别元素快照(doc 绝对坐标), 供调试接口返回 JSON */
internal fun BrowserPage.elementsSnapshot(): List<Map<String, Any>> =
    elements.mapIndexed { i, e -> mapOf("index" to i, "x" to e.x, "y" to e.y, "w" to e.w, "h" to e.h, "label" to e.label) }

/** AI 可读元素清单: "共N个: [i]「label」(x,y wxh)", 供 browser_scan 工具返回 */
internal fun BrowserPage.snapshotText(): String {
    if (elements.isEmpty()) return "页面暂无识别到可操作元素(可能动态加载中)。建议: 1)稍等1秒再调 browser_scan 重扫; 2)用 browser_text 读取整页文字; 3)用 browser_scroll 滚动页面后再 browser_scan; 仍无则页面可能纯展示, 可考虑 web_search 补充"
    val sb = StringBuilder("页面共识别 ${elements.size} 个可操作元素:\n")
    for ((i, e) in elements.withIndex()) {
        sb.append("[$i]「${e.label}」 坐标(${e.x},${e.y}) 尺寸${e.w}x${e.h}\n")
    }
    return sb.toString().trimEnd()
}

/** 点击浏览器页第 N 个已识别元素: 物理坐标注入(dispatchTouchEvent 模拟真实触摸), 绕开站点 isTrusted 反自动化拦截; 入参元素为中心文档坐标 */
internal fun BrowserPage.clickIndex(i: Int): String {
    val e = elements.getOrNull(i) ?: return "索引越界(共 ${elements.size} 个)"
    val cx = e.x + e.w / 2
    val cy = e.y + e.h / 2
    web.post { physicalTap(cx, cy) }
    return "已发起点击元素[$i]「${e.label}」"
}

/** 物理注入一次触摸(DOWN+UP)到 WebView 视口内坐标; 入参为文档坐标, 若不可见先滚动再点 */
internal fun BrowserPage.physicalTap(docX: Int, docY: Int) {
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
internal fun BrowserPage.typeIndex(i: Int, text: String): String {
    val e = elements.getOrNull(i) ?: return "索引越界(共 ${elements.size} 个)"
    actionResult = ""
    val x = e.x; val y = e.y
    val safe = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    act.runOnUiThread { web.evaluateJavascript(
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
        "daBridge.onActionResult('typed:'+(el.tagName));})();", null) }
    return "已向元素[$i]「${e.label}」发起输入"
}

/** 供调试/兜底: 执行任意 JS 表达式并把结果经 onActionResult 回传(EVAL: 前缀), 调用线程非主线程可同步等结果 */
internal fun BrowserPage.evalScript(script: String, done: CountDownLatch) {
    actionResult = ""
    actionLatch = done
    val js = "try{(function(){var __r=(function(){return (" + script + ");})();" +
             "daBridge.onActionResult('EVAL:'+String(JSON.stringify(__r)));})();}" +
             "catch(e){daBridge.onActionResult('EVAL:ERR:'+e.message);}"
    act.runOnUiThread { web.evaluateJavascript(js, null) }
    Thread { try { Thread.sleep(1500); done.countDown() } catch (e: Exception) {} }.start()
}

/** 取最近一次 JS 执行结果(去掉 EVAL: 前缀) */
internal fun BrowserPage.lastActionResult(): String {
    val r = actionResult
    return if (r.startsWith("EVAL:")) r.substring(5) else r
}

/** scan 同步等待: DebugServer / AI 工具触发重扫时阻塞等 onElements 回填, 防止异步竞态读到旧/空元素 */
/** open 页面就绪等待: 记录最近一次 open 触发的加载, onPageFinished 时置完成 */

/** 供 MainActivity/未来 AI 引擎调用的公开能力; url 为关键词时自动转百度搜索 */
internal fun BrowserPage.open(url: String? = null) {
    if (url != null) {
        lastUrl = url; loaded = true; paintStatus(act.getString(R.string.br_opening, url))
        loadDoneLatch = CountDownLatch(1)
        act.runOnUiThread { web.loadUrl(toLoadableUrl(url)) }
    } else ensureLoad()
    slideIn()
}

/**
 * 等待最近一次 open 的页面加载完成(onPageFinished), 由非 UI 线程调用。
 * 无进行中加载立即返回 true; 超时返回 false。
 */
internal fun BrowserPage.waitLoaded(timeoutMs: Long): Boolean {
    val l = loadDoneLatch ?: return true
    return try { l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }
}

/** AI 清除浏览器缓存: full=true 连登录 Cookie 一起清(会退出所有站点登录); 清完强制刷新当前页(主线程执行) */
internal fun BrowserPage.clearCacheForAi(full: Boolean): String {
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

/** 登录态判定已抽至 SiteAuthDetector(纯函数可单测), 见 all/SiteAuthDetector.kt */
/** 检测当前域 Cookie: 非空、含真实登录态且相对上次有变化则回调 autoSaveCookie */
internal fun BrowserPage.tryAutoSaveCookie(url: String?) {
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
internal fun BrowserPage.cookieStringFor(domain: String?): String {
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
internal fun BrowserPage.toLoadableUrl(u: String): String {
    val t = u.trim()
    if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
    if (t.isEmpty()) return engines.getOrNull(engineIdx)?.home ?: "https://www.baidu.com"
    lastKeyword = t
    val e = engines.getOrNull(engineIdx) ?: engines.firstOrNull()
        ?: return "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(t, "UTF-8")
    return e.search.replace("{q}", java.net.URLEncoder.encode(t, "UTF-8"))
}
internal fun BrowserPage.close() { slideOut() }
/** 网页后退一步; 无历史可退时返回 false(由调用方决定是否收起浏览器页) */
/** browser_upload 工具: 把工作目录(Download/Nyral_work)文件注入页面第 N 个 file input;
 *  优先用 browser_scan 的元素索引定位(若该元素是 file input), 否则按页面第 N 个 input[type=file] 定位(默认0) */
internal fun BrowserPage.uploadIndex(i: Int, localName: String): String {
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
    act.runOnUiThread { web.evaluateJavascript(js, null) }
    return "已发起上传 $localName 到文件选择框[$i](观察页面是否出现文件)"
}

internal fun BrowserPage.goBack(): Boolean {
    if (web.canGoBack()) { web.goBack(); return true }
    return false
}
internal fun BrowserPage.setStatus(s: String) { paintStatus(s) }
internal fun BrowserPage.setThink(s: String) {
    act.runOnUiThread {
        thinkBody.text = s
        thinkLog = if (thinkLog.isEmpty()) s else thinkLog + "\n" + s
        thinkAll.text = thinkLog
        thinkScrollBox.visibility = View.VISIBLE
    }
}
/** 高亮某元素(doc 绝对坐标), 并滚动使其可见 */
internal fun BrowserPage.highlightElement(x: Int, y: Int, w: Int, h: Int, label: String) {
    highlight.setTarget(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()))
    highlight.updateScroll(web.scrollX, web.scrollY, web.contentHeight, web.height, web.scale.toFloat())
    // 滚动到该元素附近, 验证滚动跟随
    val targetY = (y * web.scale - web.height / 3f).coerceAtLeast(0f).toInt()
    web.post { web.scrollTo(0, targetY) }
    paintStatus(act.getString(R.string.br_highlight_fmt, label.ifEmpty { act.getString(R.string.br_element) }))
}
