package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * 安全中心(2026-10-04):
 * - 危险操作门禁三档切换(严格/自动/放行), 仅用户 UI 可改(AI 的 security_set 工具已摘除, 方案 A)
 * - 切档动作写审计 approvedBy=ui_user / channel=security_center
 * - 审计历史入口 → AuditActivity
 */
class SecurityCenterActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var rbStrict: RadioButton
    private lateinit var rbAuto: RadioButton
    private lateinit var rbOff: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        TitleConfig.init(this)
        PersonaConfig.init(this)
        AvatarConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "安全中心"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // 分组1: 危险操作门禁
        content.addView(Ui.groupLabel(this, "危险操作门禁"))
        val card = Ui.card(this)
        var mode = SecurityConfig.dangerMode(this)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        rbStrict = RadioButton(this).apply {
            id = View.generateViewId()
            text = "严格（每次确认）"
            textSize = 15f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        rbAuto = RadioButton(this).apply {
            id = View.generateViewId()
            text = "自动（高危确认 + 窗口期复用）"
            textSize = 15f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        rbOff = RadioButton(this).apply {
            id = View.generateViewId()
            text = "放行（关闭门禁，不推荐）"
            textSize = 15f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        group.addView(rbStrict)
        group.addView(rbAuto)
        group.addView(rbOff)

        // 档位说明
        val note = TextView(this).apply {
            text = "说明：\n严格 — 每次危险操作都需确认，禁用窗口期复用。\n自动 — 高危工具（sh/ssh/写文件等）需确认，5 分钟内同参数不再重复弹。\n放行 — 关闭门禁，AI 可自由执行危险操作，仅建议临时使用。"
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(dp(12), dp(4), dp(12), dp(14))
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        fun currentId(): Int = when (SecurityConfig.dangerMode(this@SecurityCenterActivity)) {
            "strict" -> rbStrict.id
            "off" -> rbOff.id
            else -> rbAuto.id
        }
        group.check(currentId())

        // 2026-10-04 修复: 改用 RadioButton 点击监听, 避免 setOnCheckedChangeListener + 取消回退 group.check()
        // 触发重入二次弹窗(编程 setChecked 不会触发 setOnClickListener)
        fun trySwitch(newMode: String) {
            if (newMode == mode) { group.check(currentId()); return }
            if (newMode == "off") {
                // 放行档二次确认
                SecurityUi.confirmDisableGate(this@SecurityCenterActivity,
                    onConfirm = {
                        SecurityConfig.setDangerMode(this@SecurityCenterActivity, "off")
                        SecurityConfig.audit(this@SecurityCenterActivity, "security_mode", "off", "ui_user", "security_center")
                        mode = "off"
                        modeChanged("off")
                    },
                    onCancel = { group.check(currentId()) })
            } else {
                SecurityConfig.setDangerMode(this@SecurityCenterActivity, newMode)
                SecurityConfig.audit(this@SecurityCenterActivity, "security_mode", newMode, "ui_user", "security_center")
                mode = newMode
                modeChanged(newMode)
            }
        }
        rbStrict.setOnClickListener { trySwitch("strict") }
        rbAuto.setOnClickListener { trySwitch("auto") }
        rbOff.setOnClickListener { trySwitch("off") }

        card.addView(group)
        card.addView(note)
        content.addView(card)

        // 分组2: 审计
        content.addView(Ui.groupLabel(this, "审计"))
        val cardAudit = Ui.card(this)
        cardAudit.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@SecurityCenterActivity, AuditActivity::class.java))
            }
            Ui.press(this)
            addView(Ui.iconBadgeRes(this@SecurityCenterActivity, R.drawable.ic_settings_log, 5))
            addView(LinearLayout(this@SecurityCenterActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@SecurityCenterActivity, "审计历史"))
                addView(TextView(this@SecurityCenterActivity).apply {
                    text = "查看危险工具确认 / 拒绝 / 超时记录"
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(Ui.arrow(this@SecurityCenterActivity))
        })
        content.addView(cardAudit)

        root.addView(content)
        setContentView(root)
    }

    private fun modeChanged(m: String) {
        // 更新说明区/后续扩展; 目前切档即生效并已审计
    }
}
