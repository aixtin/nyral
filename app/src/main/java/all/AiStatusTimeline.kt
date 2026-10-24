package io.github.aixtin.nyral

import android.util.Log
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView

// ==== AI 状态脉络时间线(自 AiBubbleHolder.kt 外移, 2026-10-25, 低耦纯函数层) ====

/** 全局取色: 文字类气泡背景色统一带全局 alpha(0-255) */
internal fun floatBubbleColor(base: Int): Int =
    (base and 0x00FFFFFF) or (msgBubbleAlpha shl 24)

internal fun applyBubbleTheme(t: AppTheme) {
    BUBBLE_AI = t.bubbleAi
    BUBBLE_AI_TEXT = t.bubbleAiText
    THINK_TEXT = t.thinkText
    THINK_BG = t.thinkBg
}

/** 脉络时间线事件(流式 AiBubbleHolder 与历史 AiRich 重建共用的展示数据, 09-24 重构):
 *  type = "think"(text=全文) | "tool"(name/arg/result) */
internal class StatusEvent(val type: String, val text: String, val name: String, val arg: String, val result: String)

/** 单行状态行外壳(流式与历史同形态): 深色圆角胶囊行, 内容由调用方填充 */
internal fun makeStatusShell(host: MainActivity): LinearLayout = LinearLayout(host).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
    background = rounded(host.dp(10), floatBubbleColor(THINK_BG))
    elevation = host.dp(2).toFloat()   // 状态块阴影(2dp 次级)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = host.dp(6)
        bottomMargin = host.dp(4)
    }
}

/** 收尾/历史摘要文案: "已思考X字 · N个工具"(缺项自动省略) */
internal fun statusSummaryText(thinkChars: Int, toolCount: Int): String = buildString {
    if (thinkChars > 0) append("已思考").append(thinkChars).append("字")
    if (toolCount > 0) {
        if (isNotEmpty()) append(" · ")
        append(toolCount).append("个工具")
    }
}

/** 脉络时间线(原地展开形态): 左侧竖线贯穿 + 段首 9dp 圆点衔接(手串意象),
 *  思考段=💭头深色块全文 / 工具段=🔧头深色块(名称/参数/结果), 事件间以圆点分段;
 *  onBody(事件序号, 内容块): 流式侧借此建立 block->视图映射实现展开态实时刷新 */
/** 时间线全文安全截断(2026-10-20 v2): 思考与工具全文统一, 防超长 selectable 文本 DynamicLayout reflow 打爆主线程 */
private fun safeTimelineText(raw: String, max: Int = 12000): String {
    val text = raw.ifBlank { "…" }
    val cnt = text.codePointCount(0, text.length)
    return if (cnt <= max) text
    else text.substring(0, text.offsetByCodePoints(0, max)) + "\n\n[内容过长, 已截断显示]"
}

internal fun buildStatusTimeline(
    host: MainActivity, events: List<StatusEvent>,
    onBody: ((Int, TextView) -> Unit)? = null
): View {
    val lineColor = (THINK_TEXT and 0x00FFFFFF) or (0x55 shl 24)   // 竖线: 文字色 1/3 透明度
    val box = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
            clipChildren = false   // 气泡阴影不被父容器裁剪
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(4)
        }
    }
    events.forEachIndexed { i, e ->
        // 左列脉络: 珠子行顶, 竖线从珠子下方留白处起(线不穿珠), 行间空隙处连续
        val spine = FrameLayout(host)
        spine.clipChildren = false   // 中间珠子负边距上移出界可见
        val iconH = host.dp(18)
        val beadGap = host.dp(4)   // 珠子下方留白: 线不贴珠子(给表情留一段)
        val beadTop = 0   // 珠子行顶: 行顶=上一行底, 中间珠子自然嵌在行间空隙(无负边距, 不被裁剪)
        // 竖线: 从珠子底边下方留白处起(跟随珠子); 非末行下端避开下一行上移的珠子
        spine.addView(View(host).apply { setBackgroundColor(lineColor) },
            FrameLayout.LayoutParams(host.dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = beadTop + iconH + beadGap
                bottomMargin = beadGap   // 每行下端留白, 行间珠子处断开(不穿珠)
            })
        val bead = ImageView(host).apply {
            val ic = if (e.type == "think") Ui.lucideBrain(host, THINK_TEXT, 14)
            else Ui.lucideWrench(host, THINK_TEXT, 14)
            setImageDrawable(ic)
            contentDescription = if (e.type == "think") "思考" else "工具"
        }
        spine.addView(bead, FrameLayout.LayoutParams(iconH, iconH).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = beadTop
        })
        // 内容块: 与原思考/工具深色气泡同款
        val body = TextView(host).apply {
            textSize = 14f
            setTextColor(THINK_TEXT)
            maxWidth = host.chatMaxW()
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            // 展开态思考/工具全文块: 不跟随全局透明度(透明会看不清全文), 打标记防就地刷新改色
            tag = NO_FLOAT_TAG
            background = rounded(host.dp(10), THINK_BG)
            elevation = host.dp(2).toFloat()   // 状态块阴影(2dp 次级)
            text = if (e.type == "think") {
                if (e.text.isBlank()) "…" else safeTimelineText(e.text)
            } else {
                safeTimelineText(buildString {
                    append("工具：").append(e.name)
                    if (e.arg.isNotBlank()) append("\n参数：").append(e.arg)
                    if (e.result.isNotBlank()) append("\n结果：").append(e.result)
                })
            }
            setTextIsSelectable(true)
            // 2026-10-20 卡死修复 v2: 09:15 真机复现 LineBreaker ANR(3002ms) 证明 v1 未挡住; 策略移后防 selectable 重置
            breakStrategy = 0   // TextView.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = 0   // TextView.HYPHENATION_FREQUENCY_NONE
        }
        onBody?.invoke(i, body)
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false   // 中间珠子负边距上移出界可见
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(spine, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = host.dp(4)
            topMargin = if (i == 0) host.dp(10) else host.dp(20)   // 首行内容在珠子下10dp; 中间行内容在珠子下20dp(珠子18+2留白)
            bottomMargin = 0   // 行间空隙由下一行 body topMargin 承担, 此处不留行底空隙
        })
        box.addView(row)
    }
    // 轨道末尾: route 结尾标记(流程终点), 与珠子同列对齐(行顶)(09-25)
    val endRow = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 0   // route 紧贴末行收尾, 底部不留空白行
        }
    }
    val left = FrameLayout(host)
    endRow.addView(left, LinearLayout.LayoutParams(host.dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
    val ic = ImageView(host).apply {
        setImageDrawable(Ui.lucideRoute(host, THINK_TEXT, 16))
        contentDescription = "完成"
    }
    left.addView(ic, FrameLayout.LayoutParams(host.dp(18), host.dp(18)).apply {
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
    })
    box.addView(endRow)
    return box
}
