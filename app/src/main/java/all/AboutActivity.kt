package io.github.aixtin.droidagent

import io.github.aixtin.droidagent.R

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

    private var versionTap = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.about_01)))

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
            text = "DroidAgent"
            textSize = 28f
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        brand.addView(TextView(this).apply {
            text = getString(R.string.about_02)
            textSize = 14f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(brand)

        // ---- 项目信息卡片 ----
        val card = Ui.card(this)
        card.addView(infoRow(getString(R.string.about_04), getString(R.string.about_05)))
        card.addView(Ui.divider(this))
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        card.addView(infoRow(getString(R.string.about_06), "v$versionName").apply {
            isClickable = true
            setOnClickListener { onVersionTap() }
            Ui.press(this)
        })
        card.addView(Ui.divider(this))
        card.addView(infoRow(getString(R.string.about_07), getString(R.string.about_08)))
        card.addView(Ui.divider(this))
        card.addView(infoRow(getString(R.string.about_09), getString(R.string.about_10)))
        card.addView(Ui.divider(this))
        card.addView(infoRow(getString(R.string.about_11), getString(R.string.about_12)).apply {
            isClickable = true
            setOnClickListener { UpdateChecker.check(this@AboutActivity, true) }
            Ui.press(this)
        })
        content.addView(card)

        // ---- 链接卡片（博客 / 仓库 / 免责声明 / 隐私政策）----
        val linkCard = Ui.card(this)
        linkCard.addView(infoRow(getString(R.string.about_13), "atin.asia").apply {
            isClickable = true
            setOnClickListener { openUrl("https://atin.asia") }
            Ui.press(this)
        })
        linkCard.addView(Ui.divider(this))
        linkCard.addView(infoRow(getString(R.string.about_22), getString(R.string.about_23)).apply {
            isClickable = true
            setOnClickListener { Agreements.showReadonly(this@AboutActivity, getString(R.string.about_22), Agreements.DISCLAIMER_TEXT) }
            Ui.press(this)
        })
        linkCard.addView(Ui.divider(this))
        linkCard.addView(infoRow(getString(R.string.about_24), getString(R.string.about_23)).apply {
            isClickable = true
            setOnClickListener { Agreements.showReadonly(this@AboutActivity, getString(R.string.about_24), Agreements.PRIVACY_TEXT) }
            Ui.press(this)
        })
        linkCard.addView(Ui.divider(this))
        linkCard.addView(infoRow(getString(R.string.about_25), getString(R.string.about_26)).apply {
            isClickable = true
            setOnClickListener { openUrl(getString(R.string.about_27)) }
            Ui.press(this)
        })
        content.addView(linkCard)

        // ---- 底部说明 ----
        content.addView(TextView(this).apply {
            text = getString(R.string.about_14)
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

    /** 连续点击版本号 7 次解锁调试服务入口（借鉴安卓开发者模式） */
    private fun onVersionTap() {
        if (DebugServer.unlocked(this)) {
            android.widget.Toast.makeText(this, getString(R.string.about_15), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        versionTap++
        if (versionTap >= 7) {
            versionTap = 0
            DebugServer.setUnlocked(this, true)
            android.widget.Toast.makeText(this, getString(R.string.about_16), android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, getString(R.string.about_21, 7 - versionTap), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, getString(R.string.about_17), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
