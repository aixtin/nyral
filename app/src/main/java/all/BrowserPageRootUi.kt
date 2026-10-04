package io.github.aixtin.nyral

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * BrowserPage 根容器构建域(第一刀拆分, BP7):
 * - root 圆角卡片容器 + takeover 接管按钮 + 汉堡面板/遮罩
 * - 主类 init 第二步调用(依赖 initWebViews 先初始化 web/highlight)
 */
internal fun BrowserPage.initRootUi() {
    root = FrameLayout(act).apply {
        // 2026-09-20 浏览器页倒角: 白色圆角卡片 + clipToOutline 裁剪(与顶底栏/控制条 16dp 圆角体系一致)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = act.dp(16).toFloat()
        }
        clipToOutline = true
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
        // 悬浮模式始终隐藏: 接管已迁移到输入框上方控制条, 构建即 GONE 防重启后首次打开闪现
        takeover.visibility = View.GONE
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
            act.dp(300), (act.resources.displayMetrics.heightPixels * 78 / 100),
            Gravity.CENTER))
        // 初始右外(不可见): 显式设置 View 属性, 不依赖 LayoutParams.translationX(容器可能不应用)
        hamburgerPanel.translationX = burgerHideX
    }
}
