package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 文档页(2026-10-11 占位, 2026-10-20 填入模型幻觉警示):
 * - 抽屉"文档"入口, 与"设置"对称补齐右下角
 * - 首发内容: 提醒用户勿过度依赖模型, 重要判断以自己核实为准
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

        val sv = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // ---- 模型幻觉警示 ----
        content.addView(Ui.groupLabel(this, "重要提示"))
        val warnCard = Ui.card(this)
        warnCard.addView(TextView(this).apply {
            text = "AI 模型也可能“看走眼”"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        warnCard.addView(TextView(this).apply {
            text = "模型不是万能的：有时会编造细节、只看局部就下结论，或给出模糊、自相矛盾的回答。"
            textSize = 14f
            setTextColor(Ui.TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(8), dp(0), dp(8), dp(2))
        })
        warnCard.addView(Ui.hint(this, "涉及重要判断时，请先自己核实，相信自己的判断，不要盲目信任模型的输出。"))
        content.addView(warnCard)

        // ---- 使用说明（占位，后续补充）----
        content.addView(Ui.groupLabel(this, "使用说明"))
        val card = Ui.card(this)
        card.addView(TextView(this).apply {
            text = "文档整理中…（占位）"
            textSize = 15f
            setTextColor(Ui.TEXT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        content.addView(card)

        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
