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

internal fun BrowserPage.setWindowRect(top: Int, height: Int) {
    val lp = root.layoutParams as FrameLayout.LayoutParams
    lp.topMargin = top
    lp.height = height
    root.layoutParams = lp
}

/** 恢复全屏铺底(浏览器关闭时复位) */
internal fun BrowserPage.resetWindowRect() {
    val lp = root.layoutParams as FrameLayout.LayoutParams
    lp.topMargin = 0
    lp.height = FrameLayout.LayoutParams.MATCH_PARENT
    root.layoutParams = lp
}

/** 悬浮聊天模式(输入框上方控制条接管)时隐藏浏览器页底部按钮, 接管全幅/汉堡时显示 */
internal fun BrowserPage.setBottomTakeoverVisible(v: Boolean) {
    takeover.visibility = if (v) View.VISIBLE else View.GONE
}

internal fun BrowserPage.toggleTakeover() {
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

internal fun BrowserPage.paintStatus(s: String) {
    act.runOnUiThread {
        if (drawerStatusInitialized) drawerStatus.text = s
    }
}

/** 首次呼出自动加载默认页(手势路径不经过 open(url) 时兜底) */
internal fun BrowserPage.ensureLoad() {
    if (!loaded) {
        loaded = true
        paintStatus(act.getString(R.string.br_welcome_ready))
        loadDoneLatch = CountDownLatch(1)
        act.runOnUiThread { web.loadUrl("file:///android_asset/home.html") }
    }
}

/** 打开/关闭 整页平移动画 */
internal fun BrowserPage.slideIn() {
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
/** 关闭浏览器时复位接管态: 手势/动画/工具关闭统一入口, 防止重开后按钮文字/聊天区与 taken 不一致 */
internal fun BrowserPage.resetTakeoverState() {
    if (taken) {
        taken = false
        takeover.text = act.getString(R.string.br_takeover)
        onTakeoverChange?.invoke(false)
    }
}

internal fun BrowserPage.slideOut() {
    open = false
    resetTakeoverState()
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
