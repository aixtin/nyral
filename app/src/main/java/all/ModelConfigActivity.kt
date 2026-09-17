package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 模型配置列表页 — 与主页统一视觉（灰底 + 白色圆角卡片）
 * - 每个 Provider 一行：图标 + 名称 + 副标题 + 状态标签 + 右箭头
 * - 右上角「＋ 添加」进入 ModelEditActivity 新增 API 模型
 */
class ModelConfigActivity : Activity() {

    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)

        // ---- 自绘标题栏 + 右上角getString(R.string.mcfg_02) ----
        root.addView(Ui.titleBar(this, getString(R.string.mcfg_01), right = { bar ->
            bar.addView(TextView(this).apply {
                text = getString(R.string.mcfg_02)
                textSize = 15f
                setTextColor(Ui.PRIMARY)
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    startActivity(Intent(this@ModelConfigActivity, ModelEditActivity::class.java)
                        .putExtra(ModelEditActivity.EXTRA_IS_NEW, true))
                }
                Ui.press(this)
            })
        }))

        // ---- 列表（卡片分组）----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        refresh(listContainer)

        root.addView(content)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 从编辑页返回后刷新
        refresh(listContainer)
    }

    private fun refresh(container: LinearLayout) {
        container.removeAllViews()
        val providers = ApiConfig.providers().filter { it.type != "local" }
        if (providers.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.mcfg_03)
                textSize = 13f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            })
            return
        }
        providers.forEach { p ->
            container.addView(cardGroup(listOf(p)))
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
            })
        }
    }

    private fun cardGroup(items: List<ApiConfig.Provider>): LinearLayout {
        val card = Ui.card(this)
        val currentId = ApiConfig.providerId()
        items.forEachIndexed { i, p ->
            card.addView(providerItem(p, p.id == currentId))
            if (i < items.size - 1) card.addView(Ui.divider(this))
        }
        return card
    }

    private fun providerItem(p: ApiConfig.Provider, isCurrent: Boolean): LinearLayout {
        // 已配置判定: API 模型必须有 Key(默认 base/model 不构成已配置); 本地模型按 URI
        val configured = if (p.type == "local") p.uri.isNotBlank() else p.key.isNotBlank()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@ModelConfigActivity, ModelEditActivity::class.java)
                    .putExtra(ModelEditActivity.EXTRA_PROVIDER_ID, p.id))
            }
            Ui.press(this)

            // 左侧图标（圆角方块 + 首字）
            addView(Ui.iconBadge(this@ModelConfigActivity, p.label, p.id.hashCode()))

            // 中间：名称 + 副标题
            addView(LinearLayout(this@ModelConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@ModelConfigActivity, p.label))
                addView(Ui.itemSub(this@ModelConfigActivity,
                    if (p.defaultModel.isNotBlank()) p.defaultModel else getString(R.string.mcfg_04)))
            })

            // 状态标签
            addView(TextView(this@ModelConfigActivity).apply {
                text = when {
                    isCurrent -> getString(R.string.mcfg_05)
                    configured -> getString(R.string.mcfg_06)
                    else -> getString(R.string.mcfg_07)
                }
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = roundedBg(when {
                    isCurrent -> Ui.PRIMARY
                    configured -> 0xFF34C759.toInt()
                    else -> Ui.SUB
                })
            })

            // 右箭头
            addView(Ui.arrow(this@ModelConfigActivity))
        }
    }

    private fun roundedBg(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
