package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 整理记忆索引页 — 展示辅助模型归档时 AI 整理生成的 summary（主题索引）
 * 与设置页统一视觉（灰底 + 白色圆角卡片 + 自绘标题栏）
 * 内容可长按选择复制；底部提供getString(R.string.msum_01)按钮
 */
class MemorySummaryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.msum_02)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val summary = MemoryDb(this).loadSummary()?.trim()

        if (summary.isNullOrEmpty()) {
            // ---- 空态 ----
            content.addView(TextView(this).apply {
                text = getString(R.string.msum_03)
                setTextColor(Ui.SUB)
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL
                setLineSpacing(0f, 1.6f)
                setPadding(dp(20), dp(70), dp(20), dp(20))
            })
        } else {
            // ---- 内容卡片：可长按选择复制 ----
            content.addView(Ui.card(this).apply {
                addView(TextView(this@MemorySummaryActivity).apply {
                    text = summary
                    textSize = 14f
                    setTextColor(0xFF3A3A3A.toInt())
                    setLineSpacing(dp(6).toFloat(), 1.0f)
                    setTextIsSelectable(true)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                })
            })

            // ---- 复制全部 ----
            content.addView(TextView(this).apply {
                text = getString(R.string.msum_01)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.WHITE)
                background = Ui.rounded(0xFF2B5DDA.toInt(), 12, this@MemorySummaryActivity)
                setPadding(0, dp(11), 0, dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                isClickable = true
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("summary", summary))
                    Toast.makeText(this@MemorySummaryActivity, getString(R.string.msum_04), Toast.LENGTH_SHORT).show()
                }
                Ui.press(this)
            })
        }

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
