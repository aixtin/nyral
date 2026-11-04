package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除审计页(2026-11-04):
 * 只读展示 FileAuditStore 记录的附件删除快照; 无任何删除/编辑入口, 用户不可删。
 * 入口: 文件管理页标题栏右侧「日志」。
 */
class FileAuditActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "删除日志"))

        val entries = FileAuditStore.read(this)
        root.addView(TextView(this).apply {
            text = "共 ${entries.size} 条删除记录 · 仅展示不可删"
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "暂无删除记录"
                textSize = 14f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, dp(48))
            })
        } else {
            entries.forEach { e -> list.addView(card(e)) }
        }

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun card(e: JSONObject): View {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val disp = e.optString("displayName", e.optString("fileName", "?"))
        val isAI = e.optBoolean("isArtifact", false)
        val sourceText = when (e.optString("source", "user")) {
            "user_batch" -> "批量删除"
            "ai" -> "AI 删除"
            else -> "用户删除"
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(dp(12), Ui.SURFACE)
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(2)
            }
        }
        // 头行: 时间 + 来源徽标
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = fmt.format(Date(e.optLong("ts", 0)))
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badgeFg = if (isAI) Color.parseColor("#8B5CF6") else Ui.DANGER
        val badgeBg = (badgeFg and 0x00FFFFFF) or 0x1A000000.toInt()
        head.addView(TextView(this).apply {
            text = if (isAI) "AI产物" else sourceText
            textSize = 12f
            setTextColor(badgeFg)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(dp(8), badgeBg)
        })
        card.addView(head)

        // 显示名(粗体)
        card.addView(TextView(this).apply {
            text = disp
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        // 明细: 大小 / mime / 落库名
        card.addView(TextView(this).apply {
            text = buildString {
                append(humanSize(e.optLong("size", 0)))
                val m = e.optString("mime", "?")
                if (m.isNotBlank() && m != "null") append("  ·  $m")
                append("\n落库名: " + e.optString("fileName", "?"))
            }
            textSize = 12f
            setTextColor(Ui.SUB)
        })
        return card
    }

    private fun humanSize(size: Long): String = when {
        size >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", size / 1024.0 / 1024.0)
        size >= 1024 -> String.format(Locale.US, "%.1fKB", size / 1024.0)
        else -> "${size}B"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(radius: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
        }
}
