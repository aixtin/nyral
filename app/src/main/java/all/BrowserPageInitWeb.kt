package io.github.aixtin.nyral

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * BrowserPage 初始化域(第一刀拆分, BP7):
 * - WebView 配置 + WebViewClient/WebChromeClient + JS 桥注册(daBridge) + 滚动高亮跟随
 * - 主类 init 第一步调用, 初始化 highlight/web 两个 lateinit 字段
 */
internal fun BrowserPage.initWebViews() {
    val page = this
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
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
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
        addJavascriptInterface(BrowserPageJsBridge(page), "daBridge")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setOnScrollChangeListener { _, sx, sy, _, _ ->
                highlight.updateScroll(sx, sy, contentHeight, height, scale.toFloat())
            }
        }
    }
}
