package io.github.aixtin.nyral

import java.util.concurrent.CountDownLatch

/**
 * BrowserPage JS 桥(第二刀拆分, BP8):
 * - 从 BrowserPage 内部类独立为顶层类, 持有 page 引用
 * - 收集页面元素 -> 高亮第一个候选并播报; 本地欢迎页跳转/搜索
 * - 由 initWebViews 注册: addJavascriptInterface(BrowserPageJsBridge(this), "daBridge")
 */
internal class BrowserPageJsBridge(private val page: BrowserPage) {
    /** 来源白名单: 仅允许 http/https 页面回调 daBridge, 阻断 data:/file:/javascript: 注入面
     *  注意: JavascriptInterface 回调在后台线程, WebView.getUrl() 必须在主线程读取,
     *  否则非主线程访问返回 null 导致所有回调被误丢弃(scan/text/eval 全空)。 */
    private fun sourceOk(): Boolean {
        val holder = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        page.act.runOnUiThread {
            try {
                val u = page.web.url
                holder.set(u != null && (u.startsWith("http://") || u.startsWith("https://")))
            } catch (e: Exception) { holder.set(false) }
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
        return holder.get() == true
    }

    @android.webkit.JavascriptInterface
    fun onActionResult(json: String) { if (!sourceOk()) return; page.actionResult = json; page.actionLatch?.countDown() }

    @android.webkit.JavascriptInterface
    fun onPageText(t: String) {
        if (!sourceOk()) return
        page.act.runOnUiThread { page.textResult = t; page.textLatch?.countDown(); page.textLatch = null }
    }

    @android.webkit.JavascriptInterface
    fun onElements(json: String) {
        if (!sourceOk()) return
        page.act.runOnUiThread {
            page.elements.clear(); page.elemPos = -1
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    page.elements.add(Element(o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"), o.optString("t")))
                }
            } catch (e: Exception) {}
            page.paintStatus(page.act.getString(R.string.br_scanned, page.elements.size))
            page.nextElement()
            page.scanLatch?.countDown()
            page.scanLatch = null
        }
    }

    /** 本地欢迎页专用: 仅允许 file:///android_asset/ 本地页调用跳转能力, 阻断任意网页经桥强制跳转 */
    private fun localPageOnly(): Boolean {
        val holder = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        page.act.runOnUiThread {
            try {
                val u = page.web.url
                holder.set(u?.startsWith("file:///android_asset/") == true)
            } catch (e: Exception) { holder.set(false) }
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
        return holder.get() == true
    }

    @android.webkit.JavascriptInterface
    fun homeSearch(q: String) {
        if (!localPageOnly()) return
        page.act.runOnUiThread { page.open(q) }
    }

    @android.webkit.JavascriptInterface
    fun homeOpen(url: String) {
        if (!localPageOnly()) return
        page.act.runOnUiThread { page.web.loadUrl(url); page.paintStatus(page.act.getString(R.string.br_opened, url)) }
    }
}
