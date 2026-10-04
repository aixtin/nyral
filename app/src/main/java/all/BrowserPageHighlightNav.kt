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

/** 高亮下一个元素（模拟 AI 逐步操作, 后续对接真 Agent） */
internal fun BrowserPage.nextElement() {
    if (elements.isEmpty()) { paintStatus(act.getString(R.string.br_no_elements)); return }
    val i = (elemPos + 1) % elements.size
    elemPos = i
    val e = elements[i]
    highlightElement(e.x, e.y, e.w, e.h, e.label)
    setThink(act.getString(R.string.br_candidate, i + 1, elements.size, e.label, e.x, e.y, e.w, e.h))
}

/** 高亮指定索引候选元素(0-based, 供调试接口按 index 定位); 越界/列表空则忽略 */
internal fun BrowserPage.highlightIndex(n: Int) {
    if (elements.isEmpty()) { paintStatus(act.getString(R.string.br_no_elements)); return }
    if (n < 0 || n >= elements.size) { paintStatus(act.getString(R.string.br_idx_oob, n, elements.size)); return }
    elemPos = n
    val e = elements[n]
    highlightElement(e.x, e.y, e.w, e.h, e.label)
    setThink(act.getString(R.string.br_candidate, n + 1, elements.size, e.label, e.x, e.y, e.w, e.h))
}

/** 按坐标(doc 绝对)命中最近候选元素并高亮(供 /v1/browser/highlight/xy), 返回命中的元素描述 */
internal fun BrowserPage.highlightNear(x: Int, y: Int): String {
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
