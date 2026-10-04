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


/* ===================== 底部导航栏 / 引擎管理 / 本地数据 ===================== */

/** 右侧汉堡面板: 初始右外隐藏, 右缘左滑展开= 顶栏(状态+页签) + 设置区 + 底栏(URL+复制) */
internal fun BrowserPage.buildHamburger(): LinearLayout = LinearLayout(act).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        setColor(Ui.SURFACE); cornerRadius = act.dp(18).toFloat()
    }
    clipToOutline = true
    elevation = act.dp(8).toFloat()

    // —— 顶栏: 状态行 + 页签行 ——
    LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Ui.INPUT_BG); cornerRadius = act.dp(14).toFloat(); setStroke(act.dp(1), Ui.STROKE) }
        setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))

        // 头部: 状态 + 默认引擎 + 收起
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(6), act.dp(2), act.dp(2), act.dp(2))
            addView(TextView(act).apply {
                text = "●"; textSize = 9f; setTextColor(Color.parseColor("#22C55E"))
                setPadding(0, 0, act.dp(6), 0)
            })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_idle); textSize = 12f; maxLines = 1
                setTextColor(Ui.TEXT)
                setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { drawerStatus = it })
            addView(TextView(act).apply {
                text = (engines.getOrNull(engineIdx)?.let { engineLabel(it) } ?: "") + " ▾"; textSize = 13f; setTextColor(blue)
                setPadding(act.dp(10), act.dp(5), act.dp(6), act.dp(5))
            }.also { drawerEngineTag = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_collapse); textSize = 13f; setTextColor(gray)
                setPadding(act.dp(10), act.dp(6), act.dp(2), act.dp(6))
                setOnClickListener { collapseHamburger() }
                Ui.press(this)
            })
        }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        // 页签: 引擎管理 / 登录数据 / 思考 / 关页 (胶囊选中态)
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4))
            addView(TextView(act).apply {
                text = act.getString(R.string.br_think); textSize = 11.5f; setTextColor(blue)
                gravity = Gravity.CENTER
                background = tabOutline(true)
                setOnClickListener { toggleThink() }
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(35)).apply { rightMargin = act.dp(10) }
                Ui.press(this)
            }.also { thinkTab = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_login_data); textSize = 11.5f; setTextColor(gray)
                gravity = Gravity.CENTER
                background = tabOutline(false)
                setOnClickListener { showDataTab() }
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(35)).apply { rightMargin = act.dp(10) }
                Ui.press(this)
            }.also { tabData = it })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_engine_mgr); textSize = 10.5f; setTextColor(gray)
                gravity = Gravity.CENTER
                background = tabOutline(false)
                setOnClickListener { showEngineTab() }
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(35)).apply { rightMargin = act.dp(10) }
                Ui.press(this)
            }.also { tabEngine = it })
            addView(View(act).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_add_tab); textSize = 11.5f; setTextColor(blue)
                gravity = Gravity.CENTER
                background = tabOutline(false)
                setOnClickListener { showEditEngineDialog(-1) }
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(35))
                Ui.press(this)
            }.also { addTab = it })
        }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
    }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

    // 思考摘要容器(默认隐藏, 点"思考"展开): 包 ScrollView 占剩余空间, 内容超高可滚动, 底栏(URL卡片)始终贴底不被挤出裁剪
    thinkScrollBox = android.widget.ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
    }
    thinkScrollBox.addView(thinkCollapsed(), FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    addView(thinkScrollBox, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    // 弹性占位: 思考展开时把底栏推到底部(引擎/数据模式由各自 ScrollView 的 weight 承担)
    tabSpacer = View(act).apply { visibility = View.GONE }
    addView(tabSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

    // 引擎管理面板
    engineTitle = TextView(act).apply {
        text = act.getString(R.string.br_engine_mgr); textSize = 12f; setTextColor(gray)
        setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(14))
    }
    addView(engineTitle)
    engineScroll = ScrollView(act).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
    engineList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    engineScroll.addView(engineList)
    addView(engineScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

    // 登录数据面板(默认隐藏)
    dataScroll = ScrollView(act).apply { isFillViewport = false; isVerticalScrollBarEnabled = false; visibility = View.GONE }
    dataBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    dataScroll.addView(dataBox)
    addView(dataScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = act.dp(12) })

    // —— 底栏: 当前访问地址(实时回显, 只读) + 一键复制 ——
    LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Ui.INPUT_BG); cornerRadius = act.dp(14).toFloat(); setStroke(act.dp(1), Ui.STROKE) }
        setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(14))
        addView(TextView(act).apply {
            text = "—"
            textSize = 13f; maxLines = 1
            setTextColor(Ui.TEXT)
            setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE)
            background = GradientDrawable().apply {
                setColor(Ui.INPUT_BG); cornerRadius = act.dp(12).toFloat()
                setStroke(act.dp(1), Ui.STROKE)
            }
            setPadding(act.dp(12), act.dp(3), act.dp(12), act.dp(3))
            layoutParams = LinearLayout.LayoutParams(0, act.dp(36), 1f)
        }.also { urlView = it })
        addView(TextView(act).apply {
            text = act.getString(R.string.br_copy); textSize = 14f; setTextColor(blue)
            setPadding(act.dp(10), act.dp(8), act.dp(6), act.dp(8))
            setOnClickListener { copyCurrentUrl() }
            Ui.press(this)
        })
    }.also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
    refreshEngineList()
    initDataView()
}


/** 右缘左滑展开汉堡面板: 显示遮罩+面板平移到 0 */
internal fun BrowserPage.expandHamburger() {
    if (hamburgerOpen) return
    hamburgerOpen = true
    // 每次打开抽屉默认展示思考页(与用户上次停留的页签无关)
    showThinkTab()
    // 跟手拖动中 mask 已实时显示(alpha=frac), 不再重置为 0 再淡入(否则抬手瞬间遮罩闪没又淡入=灯光闪烁)
    if (hamburgerMask.visibility != View.VISIBLE) {
        hamburgerMask.alpha = 0f
        hamburgerMask.visibility = View.VISIBLE
    }
    // 汉堡展开时屏蔽整屏系统返回手势: 右缘左滑不再被当返回收起面板
    act.setHamburgerGestureExclusion(true)
    onHamburgerChange?.invoke(true)
    act.animateHamburgerSink(true, false)
    hamburgerMask.animate().alpha(1f).setDuration(180).start()
    hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    hamburgerPanel.animate().translationX(0f).setDuration(240)
        .setInterpolator(DecelerateInterpolator(1.2f))
        .withEndAction {
            hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            act.hamburgerDragEnd()
            // 数据刷新延迟到下一帧: 避免与动画结束帧的硬件层释放挤在同一帧(卡顿)
            root.post { refreshDataView() }
        }.start()
}

/** 汉堡面板完全收出屏幕右侧所需的位移: 面板居中挂载(宽300dp), 需 (屏宽+300dp)/2 才能完全移出 */
internal val BrowserPage.burgerHideX: Float
    get() = (act.resources.displayMetrics.widthPixels + act.dp(300)) / 2f

/** 收起汉堡面板: 隐藏遮罩+面板平移回右外 */
internal fun BrowserPage.collapseHamburger() {
    val wasOpen = hamburgerOpen
    if (wasOpen) {
        hamburgerOpen = false
        // 汉堡收起后恢复默认系统手势排除区
        act.setHamburgerGestureExclusion(false)
        onHamburgerChange?.invoke(false)
        act.animateHamburgerSink(false)
        hamburgerMask.animate().alpha(0f).setDuration(160).withEndAction {
            hamburgerMask.visibility = View.GONE
        }.start()
    }
    // 无论是否已标记展开都复位面板: 修复未开态跟手展开不足阈值松手导致卡半开
    if (hamburgerPanel.translationX != burgerHideX) {
        hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        hamburgerPanel.animate().translationX(burgerHideX).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                act.hamburgerDragEnd()
            }.start()
    }
}
