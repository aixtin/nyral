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

/** 思考摘要折叠容器（默认隐藏） */
internal fun BrowserPage.thinkCollapsed(): LinearLayout {
    thinkWrap = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.VISIBLE
        setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(16))
        // 小区: 当前步骤(输入框式, 中等高度)
        thinkBody = TextView(act).apply {
            text = act.getString(R.string.br_no_think)
            textSize = 12f
            setTextColor(Ui.SUB)
            minHeight = act.dp(90)
            maxHeight = act.dp(160)
            // 只描边不填充
            background = GradientDrawable().apply {
                setStroke(act.dp(1), Ui.STROKE)
                cornerRadius = act.dp(12).toFloat()
            }
            setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
        }
        addView(thinkBody)
        // 大区: 所有步骤(终端式, 大高度)
        thinkAll = TextView(act).apply {
            text = act.getString(R.string.br_no_think)
            textSize = 12f
            setTextColor(Ui.SUB)
            minHeight = act.dp(240)
            maxHeight = act.dp(440)
            // 只描边不填充
            background = GradientDrawable().apply {
                setStroke(act.dp(1), Ui.STROKE)
                cornerRadius = act.dp(12).toFloat()
            }
            setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
        }
        addView(thinkAll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = act.dp(36)
        })
    }
    return thinkWrap
}

/** 页签描边背景: 不填充, 选中=蓝色描边, 未选中=灰色描边 */
internal fun BrowserPage.tabOutline(active: Boolean): GradientDrawable = GradientDrawable().apply {
    setStroke(act.dp(1), if (active) blue else Ui.STROKE)
    cornerRadius = act.dp(10).toFloat()
}

/** 默认页: 只显示思考摘要, 隐藏引擎/登录数据, 同步页签高亮 */
internal fun BrowserPage.showThinkTab() {
    thinkScrollBox.visibility = View.VISIBLE
    engineTitle.visibility = View.GONE
    engineScroll.visibility = View.GONE
    dataScroll.visibility = View.GONE
    tabSpacer.visibility = View.GONE
    addTab.visibility = View.GONE
    tabEngine.setTextColor(gray); tabEngine.background = tabOutline(false)
    tabData.setTextColor(gray); tabData.background = tabOutline(false)
    thinkTab.setTextColor(blue); thinkTab.background = tabOutline(true)
}

internal fun BrowserPage.toggleThink() {
    if (thinkScrollBox.visibility == View.VISIBLE) {
        thinkScrollBox.visibility = View.GONE
        engineTitle.visibility = View.VISIBLE
        engineScroll.visibility = View.VISIBLE
        tabSpacer.visibility = View.GONE
        addTab.visibility = View.VISIBLE
        tabEngine.setTextColor(blue); tabEngine.background = tabOutline(true)
        tabData.setTextColor(gray); tabData.background = tabOutline(false)
        thinkTab.setTextColor(gray); thinkTab.background = tabOutline(false)
    } else {
        thinkScrollBox.visibility = View.VISIBLE
        engineTitle.visibility = View.GONE
        engineScroll.visibility = View.GONE
        dataScroll.visibility = View.GONE
        tabSpacer.visibility = View.GONE
        addTab.visibility = View.GONE
        tabEngine.setTextColor(gray); tabEngine.background = tabOutline(false)
        tabData.setTextColor(gray); tabData.background = tabOutline(false)
        thinkTab.setTextColor(blue); thinkTab.background = tabOutline(true)
    }
}
