package io.github.aixtin.nyral

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import io.github.aixtin.nyral.R

/**
 * 首启引导页：全新安装首次进入先展示功能/权限说明，由用户选择"开始授权"后才进入主界面并触发授权流程，
 * 不再一进 APP 就直弹系统权限框。老用户（已看过引导）直接透传进主界面。
 */
class GuideActivity : Activity() {

    private fun prefs() = getSharedPreferences("app_prefs", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 已看过引导（老用户/升级安装）直接进主界面，不再展示
        if (prefs().getBoolean("guide_done", false)) {
            goMain(finishSelf = true)
            return
        }
        // 冷启动直达引导页时全局 token 尚未应用, 先按当前主题刷新（与 MainActivity 一致）
        val theme = ThemeManager.current(this)
        Ui.applyTheme(theme)
        applyBubbleTheme(theme)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(40), dp(22), dp(24))
        }

        // 顶部：App 图标徽标 + 名称 + 欢迎语
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@GuideActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                // APP 图标作头像: 圆角裁剪成头像样式
                background = Ui.rounded(Ui.SURFACE, 14, this@GuideActivity)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            })
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(14), 0, 0, 0)
                }
                addView(TextView(this@GuideActivity).apply {
                    text = getString(R.string.guide_app_name)
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ui.TEXT)
                })
                addView(TextView(this@GuideActivity).apply {
                    text = getString(R.string.guide_slogan)
                    textSize = 13f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(4), 0, 0)
                })
            })
        })

        // 功能亮点卡（外层 LinearLayout 控制上边距）
        val featCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(18), 0, 0)
            }
            addView(Ui.card(this@GuideActivity).apply {
                addView(guideItem("🤖", getString(R.string.guide_f1_t), getString(R.string.guide_f1_d)))
                addView(Ui.divider(this@GuideActivity))
                addView(guideItem("🔍", getString(R.string.guide_f2_t), getString(R.string.guide_f2_d)))
                addView(Ui.divider(this@GuideActivity))
                addView(guideItem("🗂️", getString(R.string.guide_f3_t), getString(R.string.guide_f3_d)))
                addView(Ui.divider(this@GuideActivity))
                addView(guideItem("🖥️", getString(R.string.guide_f4_t), getString(R.string.guide_f4_d)))
            })
        }
        content.addView(featCard)

        // 权限说明标语
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_perm_note)
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(dp(4), dp(20), dp(4), dp(10))
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        // ===== 协议同意区（与权限说明同级）：勾选"已阅读并同意"后才可开始授权/稍后再说 =====
        // 已读状态跟踪：任一协议"滑到底点同意"即置位对应标记；链接色 蓝=已读 红=未读
        var agreed = prefs().getBoolean("privacy_accepted", false)
        var disclaimerRead = prefs().getBoolean("disclaimer_read", false)
        var privacyRead = prefs().getBoolean("privacy_read", false)
        val cb = CheckBox(this).apply {
            isChecked = agreed
        }
        val agreeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }
        val agreeTip = TextView(this).apply {
            text = getString(R.string.guide_agree_pre)
            textSize = 13f
            setTextColor(Ui.SUB)
        }
        // 链接颜色：未读红 / 已读蓝
        fun linkColor(read: Boolean): Int = if (read) Ui.PRIMARY else 0xFFE53935.toInt()
        var dLink: TextView? = null
        var pLink: TextView? = null
        fun refreshLinks() {
            dLink?.setTextColor(linkColor(disclaimerRead))
            pLink?.setTextColor(linkColor(privacyRead))
        }
        fun markRead(isDisclaimer: Boolean) {
            if (isDisclaimer) {
                disclaimerRead = true
                prefs().edit().putBoolean("disclaimer_read", true).apply()
            } else {
                privacyRead = true
                prefs().edit().putBoolean("privacy_read", true).apply()
            }
            refreshLinks()
            // 两篇都读完 -> 自动勾选
            if (disclaimerRead && privacyRead) {
                cb.isChecked = true
                agreed = true
            }
        }
        fun link(tag: String, isDisclaimer: Boolean, open: () -> Unit) = TextView(this).apply {
            text = tag
            textSize = 13f
            setTextColor(linkColor(if (isDisclaimer) disclaimerRead else privacyRead))
            setPadding(dp(2), dp(4), dp(2), dp(4))
            isClickable = true
            setOnClickListener { open() }
        }.also {
            if (isDisclaimer) dLink = it else pLink = it
            agreeRow.addView(it)
        }
        // 勾选校验（用户在复选框上主动点击触发）：
        // - 两篇都没读：直接放过，视为同意，字体回蓝
        // - 读过至少一篇但没全读：完全阻止，Toast 提示未读的那篇
        cb.setOnClickListener {
            if (cb.isChecked) {
                if (!disclaimerRead && !privacyRead) {
                    // 一篇没读直接同意 -> 放行，两篇均视为已知悉并回蓝
                    agreed = true
                    disclaimerRead = true
                    privacyRead = true
                    prefs().edit()
                        .putBoolean("disclaimer_read", true)
                        .putBoolean("privacy_read", true)
                        .apply()
                    refreshLinks()
                } else if (!(disclaimerRead && privacyRead)) {
                    // 主动读过其中一篇，但另一篇未读 -> 完全阻止
                    val unread = if (!disclaimerRead) getString(R.string.guide_disclaimer) else getString(R.string.guide_privacy)
                    Toast.makeText(this@GuideActivity, getString(R.string.guide_agree_unread, unread), Toast.LENGTH_SHORT).show()
                    cb.isChecked = false
                    agreed = false
                } else {
                    agreed = true
                }
            } else {
                agreed = false
            }
        }
        agreeRow.addView(cb, LinearLayout.LayoutParams(dp(30), dp(30)))
        agreeRow.addView(agreeTip)
        dLink = link(getString(R.string.guide_disclaimer), true) {
            showAgreementDialog(getString(R.string.guide_disclaimer), Agreements.disclaimerText(this)) { markRead(true) }
        }
        agreeRow.addView(TextView(this).apply {
            text = "、"
            textSize = 13f
            setTextColor(Ui.SUB)
        })
        pLink = link(getString(R.string.guide_privacy), false) {
            showAgreementDialog(getString(R.string.guide_privacy), Agreements.privacyText(this)) { markRead(false) }
        }
        refreshLinks()
        content.addView(agreeRow)

        // 主按钮：开始授权
        val startBtn = Ui.primaryBtn(this, getString(R.string.guide_start)) {
            if (!cb.isChecked) {
                Toast.makeText(this, getString(R.string.guide_agree_hint), Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            prefs().edit()
                .putBoolean("privacy_accepted", true)
                .putBoolean("guide_done", true)
                .apply()
            startActivity(Intent(this@GuideActivity, FirstRunSetupActivity::class.java))
        }
        startBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        content.addView(startBtn)

        // 次按钮：稍后再说（跳过授权，之后可在 设置→权限管理 补开）
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_later)
            textSize = 14f
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            setPadding(0, dp(14), 0, 0)
            Ui.press(this)
            setOnClickListener {
                if (!cb.isChecked) {
                    Toast.makeText(this@GuideActivity, getString(R.string.guide_agree_hint), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs().edit()
                    .putBoolean("privacy_accepted", true)
                    .putBoolean("guide_done", true)
                    // 标记为已处理授权，避免主界面再次弹出；可在 设置→权限管理 手动补开
                    .putBoolean("first_run_perms_done", true)
                    .apply()
                startActivity(Intent(this@GuideActivity, FirstRunSetupActivity::class.java))
            }
        })

        val scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
        root.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun guideItem(icon: String, title: String, desc: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(Ui.iconBadge(this@GuideActivity, icon.take(1), sizeDp = 42))
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, 0, 0)
                }
                addView(TextView(this@GuideActivity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ui.TEXT)
                })
                addView(TextView(this@GuideActivity).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }

    /** 协议全文阅读弹窗：可滚动，滑到底部后"我已阅读并同意"才可点击 */
    private fun showAgreementDialog(title: String, body: String, onAgree: () -> Unit) {
        val dlg = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.setCancelable(true) // 未同意前允许关闭
        dlg.setCanceledOnTouchOutside(false)
        val d = resources.displayMetrics.density
        fun dpf(v: Int) = (v * d).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, 16, this@GuideActivity)
        }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            setPadding(dpf(20), dpf(20), dpf(20), dpf(12))
        })
        val sv = ScrollView(this).apply { isFillViewport = true }
        sv.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.TEXT)
            setLineSpacing(dpf(3).toFloat(), 1f)
            text = body
            setPadding(dpf(20), dpf(2), dpf(20), dpf(14))
        })
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var scrolledEnd = false
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpf(16), dpf(6), dpf(16), dpf(18))
        }
        val cancelBtn = TextView(this).apply {
            text = getString(R.string.guide_dlg_cancel)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            background = Ui.rounded(Ui.INPUT_BG, 18, this@GuideActivity)
            setPadding(dpf(6), dpf(13), dpf(6), dpf(13))
            setOnClickListener { dlg.dismiss() }
        }
        val agreeBtn = TextView(this).apply {
            text = getString(R.string.guide_dlg_read_go)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = Ui.rounded(Ui.PRIMARY, 18, this@GuideActivity)
            setPadding(dpf(6), dpf(13), dpf(6), dpf(13))
            isEnabled = false
            alpha = 0.5f
        }
        fun checkReady() {
            agreeBtn.isEnabled = scrolledEnd
            agreeBtn.alpha = if (scrolledEnd) 1f else 0.5f
        }
        sv.viewTreeObserver.addOnScrollChangedListener {
            val child = sv.getChildAt(0) ?: return@addOnScrollChangedListener
            if (!scrolledEnd && sv.scrollY + sv.height >= child.height - dpf(6)) {
                scrolledEnd = true
                agreeBtn.text = getString(R.string.guide_dlg_read)
                checkReady()
            }
        }
        agreeBtn.setOnClickListener {
            if (scrolledEnd) {
                dlg.dismiss()
                onAgree()
            }
        }
        bottomRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottomRow.addView(cancelBtn, LinearLayout.LayoutParams(dpf(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomRow.addView(Space(this), LinearLayout.LayoutParams(dpf(16), 1))
        bottomRow.addView(agreeBtn, LinearLayout.LayoutParams(dpf(156), ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        panel.addView(bottomRow)
        dlg.setContentView(panel)
        dlg.window?.let { w ->
            val lp = w.attributes
            lp.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = (resources.displayMetrics.heightPixels * 0.8).toInt()
            lp.gravity = Gravity.CENTER
            w.attributes = lp
            w.setBackgroundDrawable(ColorDrawable())
        }
        dlg.show()
    }

    private fun goMain(finishSelf: Boolean) {
        startActivity(Intent(this, MainActivity::class.java))
        if (finishSelf) finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {

    }
}
