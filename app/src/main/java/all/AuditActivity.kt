package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
 * 安全审计历史页(2026-10-04):
 * 读 filesDir/nyral_security_audit.log, 按时间倒序渲染决策记录卡片。
 * 第一版: 简单 ScrollView 卡片列表; 后续可扩展筛选/搜索/导出。
 * 2026-10-27 整页接入 Ui.* 主题规范(语义色随 7 套主题)。
 */
class AuditActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)

        // 顶栏: 返回(lucide 箭头) + 标题
        root.addView(Ui.titleBar(this, "安全审计"))

        // 统计条
        val summary = loadAudit()
        root.addView(TextView(this).apply {
            text = "共 ${summary.size} 条记录"
            textSize = 12f
            setTextColor(Ui.SUB)
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
                setTextColor(Ui.SUB)
                gravity = android.view.Gravity.CENTER
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
            background = rounded(dp(12), Ui.SURFACE)
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(2)
            }
        }
        // 头行: 时间 + 结果徽标
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = fmt.format(Date(e.ts))
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badgeText = when {
            allowed -> "已允许"
            e.approvedBy == "timeout" -> "超时拒绝"
            e.approvedBy == "rejected" -> "已拒绝"
            else -> e.approvedBy
        }
        // 结果徽标: 同色系半透明底 + 同色文字, 浅色/暗色主题下都可读
        val badgeFg = if (allowed) 0xFF2E8B57.toInt() else Ui.DANGER
        val badgeBg = (badgeFg and 0x00FFFFFF) or 0x1A000000.toInt()
        head.addView(TextView(this).apply {
            text = badgeText
            textSize = 12f
            setTextColor(badgeFg)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(dp(8), badgeBg)
        })
        card.addView(head)

        // 工具名 + 通道
        val toolLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        toolLine.addView(TextView(this).apply {
            text = e.tool
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolLine.addView(TextView(this).apply {
            text = if (e.channel == "notification") "通知" else "气泡"
            textSize = 11f
            setTextColor(Ui.SUB)
        })
        card.addView(toolLine)

        // 参数摘要
        card.addView(TextView(this).apply {
            text = e.arg.ifBlank { "(无参数摘要)" }
            textSize = 12f
            setTextColor(Ui.SUB)
            maxLines = 3
        })
        return card
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun rounded(radius: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
        }
}
