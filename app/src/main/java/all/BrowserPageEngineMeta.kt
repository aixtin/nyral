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

internal fun BrowserPage.engineKeyOf(n: String): String = when (n) {
    "百度", "baidu" -> "baidu"
    "必应", "bing" -> "bing"
    "谷歌", "google" -> "google"
    "搜狗", "sogou" -> "sogou"
    "神马", "sm" -> "sm"
    "知乎", "zhihu" -> "zhihu"
    else -> ""
}

/** 预设 key -> 当前 locale 显示名（自定义引擎原样返回） */
internal fun BrowserPage.engineLabelOf(n: String, k: String): String {
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

internal fun BrowserPage.engineLabel(e: Engine): String = engineLabelOf(e.name, e.key)
