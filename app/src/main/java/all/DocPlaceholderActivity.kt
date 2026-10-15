package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 文档占位页(2026-10-11):
 * - 抽屉"文档"入口占位, 对称补齐右下角 UI
 * - 实际文档内容后续再填充
 */
class DocPlaceholderActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        TitleConfig.init(this)
        PersonaConfig.init(this)
        AvatarConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "文档"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        content.addView(Ui.groupLabel(this, "使用说明"))
        val card = Ui.card(this)
        card.addView(TextView(this).apply {
            text = "文档整理中…（占位）"
            textSize = 15f
            setTextColor(Ui.TEXT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        content.addView(card)

        root.addView(content)
        setContentView(root)
    }
}
