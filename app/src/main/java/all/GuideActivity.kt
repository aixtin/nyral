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
                background = Ui.rounded(0xFFFFFFFF.toInt(), 14, this@GuideActivity)
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
        fun linkColor(read: Boolean): Int = if (read) 0xFF0B93F6.toInt() else 0xFFE53935.toInt()
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
            showAgreementDialog(getString(R.string.guide_disclaimer), DISCLAIMER_TEXT) { markRead(true) }
        }
        agreeRow.addView(TextView(this).apply {
            text = "、"
            textSize = 13f
            setTextColor(Ui.SUB)
        })
        pLink = link(getString(R.string.guide_privacy), false) {
            showAgreementDialog(getString(R.string.guide_privacy), PRIVACY_TEXT) { markRead(false) }
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
            background = Ui.rounded(0xFFFFFFFF.toInt(), 16, this@GuideActivity)
        }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            setPadding(dpf(20), dpf(20), dpf(20), dpf(12))
        })
        val sv = ScrollView(this).apply { isFillViewport = true }
        sv.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF333333.toInt())
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
            setTextColor(0xFF888888.toInt())
            background = Ui.rounded(0xFFF0F0F0.toInt(), 18, this@GuideActivity)
            setPadding(dpf(6), dpf(13), dpf(6), dpf(13))
            setOnClickListener { dlg.dismiss() }
        }
        val agreeBtn = TextView(this).apply {
            text = getString(R.string.guide_dlg_read_go)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = Ui.rounded(0xFF0B93F6.toInt(), 18, this@GuideActivity)
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
        /** 免责声明（与仓库 DISCLAIMER.md 同口径的 App 内简版） */
        private val DISCLAIMER_TEXT = """本应用为个人开发者提供的安卓智能体工具，仅用于学习、研究、技术交流，不构成任何正式产品承诺。

一、无担保声明
本应用按"现状"提供，作者不作任何明示或默示担保，包括但不限于适销性、特定用途适用性、不侵犯第三方权利、运行稳定、数据完整无损。因使用本应用产生的任何直接或间接损失，作者不承担责任。

二、数据与 API 披露（重要）
1. 本应用不集成任何本地模型，所有对话均通过你自行配置的第三方 AI 服务商 API（如 OpenAI 兼容接口）实现。
2. 你在对话中提交的内容、附带的上下文（如记忆检索结果、工具调用参数、文件内容片段）将通过网络请求发送至你所配置的模型服务商服务器处理，数据并不只停留在本地设备。
3. 上述数据的处理、存储与合规遵循该服务商自身的服务条款与隐私政策，与作者无关；请在配置 API 前阅读并确认服务商相关协议。
4. 涉及文件内容、SSH 远程信息、个人隐私、账号凭据等敏感数据，请在发送前充分知悉其必然经过你指定的模型通道，谨慎选择模型与配置。

三、远程与系统操作风险
本应用可能包含 SSH 远程连接、文件读写、系统调试（root/adb）等能力，此类操作可能对设备、远程服务器或其上数据造成不可预期的后果（包括但不限于数据丢失、服务中断、设备异常）。所有远程与系统级操作后果由操作者自行承担，请自行评估风险并做好备份。

四、责任边界
作者不对第三方服务商的可用性、安全性、合规性作任何担保，不对网络、模型输出内容、第三方服务故障引发的损失负责；用户违反法律法规、服务商条款或他人权利导致的后果，由用户自行承担。

五、变更
作者保留随时修改本声明的权利，修改后自更新之日起适用；继续使用本应用即视为接受最新版本。"""

        /** 隐私政策（与仓库 PRIVACY.md 同口径的 App 内简版） */
        private val PRIVACY_TEXT = """本应用遵循"最小收集"原则：应用本身不收集、不存储、不分析、不共享任何与你个人身份直接相关的数据，你的使用记录默认仅保存在你自己设备上。

一、数据收集
1. 作者不收集任何个人信息，不采集设备标识、IMEI、位置、通讯录、电话、短信等。
2. 不接入任何统计、广告、埋点 SDK。
3. 应用内登录态、配置、记忆等内容均由你自行管理与操作。

二、数据去向（重要）
1. 你配置并启用的模型 API：对话内容、附带上下文（含记忆检索结果、工具调用参数、文件内容片段）会经网络发送至你所配置的第三方模型服务商处理，以该服务商自身的隐私政策为准。
2. 除你主动配置的上述通道外，本应用不向任何第三方传输你的数据。

三、本地存储内容
以下内容仅保存在设备本地，用于保证连续对话与基本功能：
1. 聊天记录、记忆库内容。
2. 模型、SSH 等连接配置（含你自行填写的地址、令牌、密钥等）。
3. 应用运行日志。
请妥善保管含敏感信息的配置数据；卸载应用或清除应用数据即可删除上述本地内容。

四、权限使用说明
1. 网络权限：用于访问你配置的模型 API、SSH 等连接目标。
2. 存储权限：用于读写你允许访问的文件。
3. 系统调试相关：仅在启用远程调试、root 能力时按你的操作生效。
权限均在功能需要时请求，你可随时在系统设置中关闭。

五、未成年人
本应用不面向未成年人提供服务；若未成年人在监护人指导下使用，请监护人知悉上述 API 数据通道内容。

六、政策变更与联系
我们可能更新本政策，更新后自公布之日起生效，重大变更将以显著方式提示。如有疑问可提交至仓库 Issue。"""
    }
}
