package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 关于页 — 与设置系页面统一视觉（灰底 + 白色圆角卡片 + 自绘标题栏）
 * 展示项目定位 / 版本 / 数据边界（自托管、不接第三方、开源、不 Root）
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "关于"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // ---- 品牌区（居中大标题，无首字徽标）----
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(32), 0, dp(26))
        }
        brand.addView(TextView(this).apply {
            text = "agent"
            textSize = 28f
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        brand.addView(TextView(this).apply {
            text = "本地智能体 · V1.0"
            textSize = 14f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        })
        brand.addView(TextView(this).apply {
            text = "atin"
            textSize = 16f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        brand.addView(TextView(this).apply {
            text = "喜欢散狗粮"
            textSize = 13f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(brand)

        // ---- 项目信息卡片 ----
        val card = Ui.card(this)
        card.addView(infoRow("定位", "安卓 Agent"))
        card.addView(Ui.divider(this))
        card.addView(infoRow("版本", "v1.0"))
        card.addView(Ui.divider(this))
        card.addView(infoRow("数据", "记忆自托管 · 对话经模型 API"))
        card.addView(Ui.divider(this))
        card.addView(infoRow("权限", "普通应用权限"))
        card.addView(Ui.divider(this))
        card.addView(infoRow("更新", "点击检查新版本").apply {
            isClickable = true
            setOnClickListener { UpdateChecker.check(this@AboutActivity, true) }
            Ui.press(this)
        })
        content.addView(card)

        // ---- 链接卡片（博客 / 仓库）----
        val linkCard = Ui.card(this)
        linkCard.addView(infoRow("个人博客", "atin.asia").apply {
            isClickable = true
            setOnClickListener { openUrl("https://atin.asia") }
            Ui.press(this)
        })
        content.addView(linkCard)

        // ---- 底部说明 ----
        content.addView(TextView(this).apply {
            text = "agent — 你的安卓智能体管家"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun infoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@AboutActivity).apply {
                text = label
                textSize = 14f
                setTextColor(Ui.SUB)
                layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@AboutActivity).apply {
                text = value
                textSize = 14f
                setTextColor(Ui.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "未找到可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
