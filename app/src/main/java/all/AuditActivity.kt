package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全审计历史页(2026-10-04):
 * 读 filesDir/nyral_security_audit.log, 按时间倒序渲染决策记录卡片。
 * 第一版: 简单 ScrollView 卡片列表; 后续可扩展筛选/搜索/导出。
 */
class AuditActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F3F7.toInt())
        }

        // 顶栏: 返回 + 标题
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(16), dp(10))
            background = rounded(dp(0), 0xFFFFFFFF.toInt())
        }
        titleBar.addView(TextView(this).apply {
            text = "‹ 返回"
            textSize = 16f
            setTextColor(0xFF3D7FFF.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        })
        titleBar.addView(TextView(this).apply {
            text = "安全审计"
            textSize = 17f
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(1))
        })
        root.addView(titleBar)

        // 统计条
        val summary = loadAudit()
        root.addView(TextView(this).apply {
            text = "共 ${summary.size} 条记录"
            textSize = 12f
            setTextColor(0xFF8A8A8A.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(4))
        })

        // 记录列表
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        if (summary.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "暂无审计记录"
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, dp(48))
            })
        } else {
            summary.forEach { e ->
                list.addView(auditCard(e))
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private data class AuditEntry(
        val ts: Long, val tool: String, val approvedBy: String,
        val channel: String, val arg: String, val argHash: String
    )

    private fun loadAudit(): List<AuditEntry> {
        val f = SecurityConfig.auditFile(this)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { line ->
                try {
                    val j = JSONObject(line)
                    AuditEntry(
                        ts = j.optLong("ts", 0),
                        tool = j.optString("tool", "?"),
                        approvedBy = j.optString("approvedBy", "?"),
                        channel = j.optString("channel", "bubble"),
                        arg = j.optString("arg", ""),
                        argHash = j.optString("argHash", "")
                    )
                } catch (e: Exception) { null }
            }.sortedByDescending { it.ts }
        } catch (e: Exception) { emptyList() }
    }

    private fun auditCard(e: AuditEntry): View {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val allowed = e.approvedBy == "user"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(dp(12), 0xFFFFFFFF.toInt())
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(2)
            }
        }
        // 头行: 时间 + 结果徽标
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = fmt.format(Date(e.ts))
            textSize = 12f
            setTextColor(0xFF8A8A8A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badgeText = when {
            allowed -> "已允许"
            e.approvedBy == "timeout" -> "超时拒绝"
            e.approvedBy == "rejected" -> "已拒绝"
            else -> e.approvedBy
        }
        val badgeColor = when {
            allowed -> 0xFF2E8B57.toInt()
            else -> 0xFFD9534F.toInt()
        }
        head.addView(TextView(this).apply {
            text = badgeText
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(dp(8), badgeColor)
        })
        card.addView(head)

        // 工具名 + 通道
        val toolLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolLine.addView(TextView(this).apply {
            text = e.tool
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolLine.addView(TextView(this).apply {
            text = if (e.channel == "notification") "通知" else "气泡"
            textSize = 11f
            setTextColor(0xFF8A8A8A.toInt())
        })
        card.addView(toolLine)

        // 参数摘要
        card.addView(TextView(this).apply {
            text = e.arg.ifBlank { "(无参数摘要)" }
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            maxLines = 3
        })
        return card
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(radius: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
        }
}
