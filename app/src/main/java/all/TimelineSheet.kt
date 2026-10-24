package io.github.aixtin.nyral

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Agent 单轨折叠流底部抽屉(2026-10-25):
 * 点击思考/工具胶囊后从底部滑入, 展示完整时间线(思考全文 / 工具参数+结果 JSON),
 * 自动滚动定位到被点击事件的 index 并高亮闪烁。
 * 快照渲染: 打开时全量重建, 流式期间再次打开即见最新内容(不做实时增量刷新, 降耦合)。
 * 复用 buildStatusTimeline(与原地展开同源渲染, 含 12000 字安全截断)。
 */
internal class TimelineSheet(private val host: MainActivity) {

    private var dialog: Dialog? = null

    fun show(events: List<StatusEvent>, focusIndex: Int) {
        dismiss()
        val d = Dialog(host, android.R.style.Theme_Translucent_NoTitleBar)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val root = FrameLayout(host).apply {
            setBackgroundColor(0x66000000.toInt())   // 半透明遮罩, 点击空白关闭
            setOnClickListener { dismiss() }
        }
        // 底部面板: 深色圆角顶, 高取屏幕 85%
        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(host.dp(18), THINK_BG)
            elevation = host.dp(12).toFloat()
        }
        val panelH = (host.resources.displayMetrics.heightPixels * 0.85f).toInt()
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, panelH).apply {
            gravity = Gravity.BOTTOM
        })
        // 头部: 标题 + 事件数 + 关闭
        val header = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(16), host.dp(12), host.dp(8), host.dp(8))
        }
        header.addView(TextView(host).apply {
            text = "时间线"
            textSize = 16f
            setTextColor(THINK_TEXT)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(host).apply {
            text = "${events.size} 个事件"
            textSize = 12f
            setTextColor(THINK_TEXT)
            setPadding(0, 0, host.dp(8), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(ImageView(host).apply {
            setImageDrawable(Ui.lucideX(host, THINK_TEXT, 18))
            contentDescription = "关闭"
            setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(host.dp(40), host.dp(40)))
        panel.addView(header)
        // 时间线主体: ScrollView + buildStatusTimeline(复用原地展开渲染)
        val scroll = ScrollView(host).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(4), 0, host.dp(4), host.dp(24))
        }
        val rows = arrayOfNulls<TextView>(events.size)
        val tl = buildStatusTimeline(host, events) { i, tv -> rows[i] = tv }
        content.addView(tl)
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 入场: 面板从屏幕底部滑入(面板已在底部位置, 用自身高度做偏移)
        panel.post {
            val anim = TranslateAnimation(0f, 0f, panel.height.toFloat(), 0f)
            anim.duration = 280L
            anim.interpolator = DecelerateInterpolator()
            panel.startAnimation(anim)
            val target = rows.getOrNull(focusIndex)
            if (target != null) {
                scroll.scrollTo(0, (target.top - host.dp(72)).coerceAtLeast(0))
                flashRow(target)
            }
        }
        d.setContentView(root)
        d.show()
        dialog = d
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** 定位行高亮闪烁: 背景在 高亮色/原色 间切换(REVERSE 两轮) */
    private fun flashRow(tv: TextView) {
        val base = THINK_BG
        val hi = (THINK_TEXT and 0x00FFFFFF) or (0x99 shl 24)   // 高亮: 文字色 60% 透明度
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            repeatCount = 2
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { v ->
                val blink = v.animatedFraction < 0.5f
                tv.background = rounded(host.dp(10), if (blink) hi else base)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    tv.background = rounded(host.dp(10), base)
                }
            })
        }
        anim.start()
    }
}
