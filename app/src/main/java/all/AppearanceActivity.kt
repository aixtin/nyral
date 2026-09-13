package io.github.aixtin.droidagent

import io.github.aixtin.droidagent.R

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 外观设置（设置 → 外观 → 外观设置）— 资料卡式三级页面，仿 AI 个性化。
 * - 标题设置分组：主页标题 / 侧栏标题 / 侧栏便签 扁平化输入框（内嵌编辑，无需弹窗），保存按钮合并为唯一
 * - 聊天分组：聊天模式开关、聊天背景入口（跳 ChatBackgroundActivity）
 */
class AppearanceActivity : Activity() {

    private lateinit var mainTitleInput: EditText
    private lateinit var drawerTitleInput: EditText
    private lateinit var drawerNoteInput: EditText
    private var mainTitleReady = false
    private var drawerTitleReady = false
    private var drawerNoteReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TitleConfig.init(this)
        ModeConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.appr_01)))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 分组：标题设置 ----
        content.addView(Ui.groupLabel(this, getString(R.string.appr_02)))
        val cardTitle = Ui.card(this)
        // 主页标题
        cardTitle.addView(sectionTitle(getString(R.string.appr_03)))
        cardTitle.addView(flatInput(
            hint = getString(R.string.appr_04),
            current = TitleConfig.mainTitle(),
            maxLen = 20,
            ref = { et -> mainTitleInput = et }
        ))
        cardTitle.addView(hintRow(getString(R.string.appr_05)))
        // 侧栏标题
        cardTitle.addView(Ui.divider(this))
        cardTitle.addView(sectionTitle(getString(R.string.appr_06)))
        cardTitle.addView(flatInput(
            hint = getString(R.string.appr_07),
            current = TitleConfig.drawerTitle(),
            maxLen = 20,
            ref = { et -> drawerTitleInput = et }
        ))
        cardTitle.addView(hintRow(getString(R.string.appr_08)))
        // 侧栏便签
        cardTitle.addView(Ui.divider(this))
        cardTitle.addView(sectionTitle(getString(R.string.appr_09)))
        cardTitle.addView(flatInput(
            hint = getString(R.string.appr_10),
            current = TitleConfig.drawerNote(),
            maxLen = 18,
            ref = { et -> drawerNoteInput = et },
            multiline = true
        ))
        cardTitle.addView(saveRow(getString(R.string.appr_11), getString(R.string.appr_12), {
            if (mainTitleReady && drawerTitleReady && drawerNoteReady) {
                TitleConfig.setMainTitle(mainTitleInput.text.toString().trim())
                TitleConfig.setDrawerTitle(drawerTitleInput.text.toString().trim())
                TitleConfig.setDrawerNote(drawerNoteInput.text.toString().trim())
                Toast.makeText(this, getString(R.string.appr_13), Toast.LENGTH_SHORT).show()
            }
        }))
        content.addView(cardTitle)

        // ---- 分组：聊天 ----
        content.addView(Ui.groupLabel(this, getString(R.string.appr_14)))
        val cardChat = Ui.card(this)
        // 聊天模式开关
        cardChat.addView(settingsSwitch(
            getString(R.string.appr_15),
            getString(R.string.appr_16),
            "💬", 15,
            ModeConfig.chatMode()
        ) { on -> ModeConfig.setChatMode(on) })
        // 聊天模式显示 Markdown 开关
        cardChat.addView(Ui.divider(this))
        cardChat.addView(settingsSwitch(
            getString(R.string.appr_26),
            getString(R.string.appr_27),
            "📝", 16,
            ModeConfig.chatMarkdown()
        ) { on -> ModeConfig.setChatMarkdown(on) })
        // 聊天背景
        cardChat.addView(Ui.divider(this))
        cardChat.addView(settingsItem(getString(R.string.appr_17), getString(R.string.appr_18), "🎨", 4, {
            startActivity(Intent(this@AppearanceActivity, ChatBackgroundActivity::class.java))
        }))
        content.addView(cardChat)

        // ---- 分组：悬浮终端 ----
        content.addView(Ui.groupLabel(this, getString(R.string.appr_19)))
        val cardTerm = Ui.card(this)
        cardTerm.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            val swTerm = Switch(this@AppearanceActivity)
            swTerm.isChecked = AITerminal.isEnabled(this@AppearanceActivity)
            // 副标题随授权态联动
            val subTitle = TextView(this@AppearanceActivity).apply {
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            fun refreshSub() {
                subTitle.text = if (android.provider.Settings.canDrawOverlays(this@AppearanceActivity))
                    getString(R.string.appr_20)
                else
                    getString(R.string.appr_21)
            }
            refreshSub()
            swTerm.setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (!android.provider.Settings.canDrawOverlays(this@AppearanceActivity)) {
                        swTerm.isChecked = false
                        Toast.makeText(this@AppearanceActivity, getString(R.string.appr_22), Toast.LENGTH_SHORT).show()
                        try {
                            startActivity(Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                        } catch (e: Exception) {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        }
                    } else {
                        AITerminal.setEnabled(this@AppearanceActivity, true)
                        try { AITerminalService.start(this@AppearanceActivity) } catch (e: Exception) {
                            LogStore.e(LogStore.MAIN, "悬浮终端服务启动失败: ${e.message}")
                        }
                        Toast.makeText(this@AppearanceActivity, getString(R.string.appr_23), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AITerminal.setEnabled(this@AppearanceActivity, false)
                    try { AITerminalService.stop(this@AppearanceActivity) } catch (e: Exception) { }
                    Toast.makeText(this@AppearanceActivity, getString(R.string.appr_24), Toast.LENGTH_SHORT).show()
                }
                refreshSub()
            }
            addView(Ui.iconBadge(this@AppearanceActivity, "▶", 21))
            addView(LinearLayout(this@AppearanceActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@AppearanceActivity, getString(R.string.appr_25)))
                addView(subTitle)
            })
            addView(swTerm)
        })
        content.addView(cardTerm)

        setContentView(root)
    }

    /** 分组小节标题（加粗） */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF222222.toInt())
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(12), dp(12), 0)
    }

    /** 扁平化输入框：浅灰圆角底、无边框，内嵌编辑；maxLen>0 时超长自动截断 */
    private fun flatInput(hint: String, current: String, maxLen: Int, ref: (EditText) -> Unit, multiline: Boolean = false): EditText {
        val et = Ui.input(this, hint).apply {
            setText(if (current.isEmpty()) "" else current)
            setSelection(text.length)
            if (multiline) {
                setSingleLine(false)
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            } else {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            background = Ui.rounded(0xFFF4F5F7.toInt(), 12, this@AppearanceActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(8), dp(12), 0)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length ?: 0 > maxLen) {
                        val keep = s!!.substring(0, maxLen)
                        setText(keep)
                        setSelection(keep.length)
                    }
                }
            })
        }
        ref(et)
        return et
    }

    /** 提示行 */
    private fun hintRow(text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), dp(14))
        addView(TextView(this@AppearanceActivity).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF999999.toInt())
        })
    }

    /** 提示 + 保存按钮行（合并为标题区唯一保存按钮） */
    private fun saveRow(hint: String, btnText: String, onSave: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), dp(14))
        addView(TextView(this@AppearanceActivity).apply {
            text = hint
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@AppearanceActivity).apply {
            text = btnText
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.rounded(Color.parseColor("#4A90D9"), 18, this@AppearanceActivity)
            setPadding(dp(18), dp(7), dp(18), dp(7))
            isClickable = true
            Ui.press(this)
            setOnClickListener { onSave() }
        })
    }

    /** 列表项（跳转类） */
    private fun settingsItem(title: String, subtitle: String, icon: String, seed: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            Ui.press(this)
            addView(Ui.iconBadge(this@AppearanceActivity, icon, seed))
            addView(LinearLayout(this@AppearanceActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@AppearanceActivity, title))
                addView(TextView(this@AppearanceActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(Ui.arrow(this@AppearanceActivity))
        }
    }

    /** 开关项（聊天模式） */
    private fun settingsSwitch(title: String, subtitle: String, icon: String, seed: Int, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(Ui.iconBadge(this@AppearanceActivity, icon, seed))
            addView(LinearLayout(this@AppearanceActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@AppearanceActivity, title))
                addView(TextView(this@AppearanceActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(Switch(this@AppearanceActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
