package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

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

        // ---- 分组：快捷开关（九宫格） ----
        content.addView(Ui.groupLabel(this, getString(R.string.appr_32)))
        val cardGrid = Ui.card(this)
        cardGrid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            // 行1：聊天模式 / 聊天背景 / 主题
            addView(LinearLayout(this@AppearanceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(gridSwitch(R.drawable.ic_appr_chat_mode, 15, getString(R.string.appr_15), {
                    if (ModeConfig.chatMode()) getString(R.string.appr_33) else getString(R.string.appr_34)
                }) { ModeConfig.setChatMode(!ModeConfig.chatMode()) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                addView(gridAction(R.drawable.ic_appr_bg, 4, getString(R.string.appr_17), {
                    when (ChatBackgroundActivity.loadType(this@AppearanceActivity)) {
                        "preset" -> getString(R.string.appr_37)
                        "custom" -> getString(R.string.appr_38)
                        else -> getString(R.string.appr_36)
                    }
                }) { startActivity(Intent(this@AppearanceActivity, ChatBackgroundActivity::class.java)) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                addView(gridAction(R.drawable.ic_appr_theme, 9, getString(R.string.appr_29), {
                    getString(ThemeManager.current(this@AppearanceActivity).nameRes)
                }) { showThemePicker() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            })
            // 行2：显示 MD / AI 悬浮终端 / 输入澄清（设置页移入）
            addView(LinearLayout(this@AppearanceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                addView(gridSwitch(R.drawable.ic_appr_md, 16, getString(R.string.appr_26), {
                    if (ModeConfig.chatMarkdown()) getString(R.string.appr_33) else getString(R.string.appr_34)
                }) { ModeConfig.setChatMarkdown(!ModeConfig.chatMarkdown()) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                addView(gridSwitch(R.drawable.ic_appr_terminal, 21, getString(R.string.appr_25), {
                    terminalStatusText()
                }) { toggleTerminal() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                addView(gridSwitch(R.drawable.ic_appr_ask, 22, getString(R.string.settings_ask_user), {
                    if (AskUserConfig.enabled(this@AppearanceActivity)) getString(R.string.appr_33) else getString(R.string.appr_34)
                }) { AskUserConfig.setEnabled(this@AppearanceActivity, !AskUserConfig.enabled(this@AppearanceActivity)) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            })
        })
        content.addView(cardGrid)

        setContentView(root)
    }

    /** 跳转卡状态刷新器（onResume 统一刷新，返回后反映最新背景/主题） */
    private val gridRefreshers = mutableListOf<() -> Unit>()

    override fun onResume() {
        super.onResume()
        gridRefreshers.forEach { it() }
    }

    /** 分组小节标题（加粗） */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Ui.TEXT)
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
            background = Ui.rounded(Ui.INPUT_BG, 12, this@AppearanceActivity)
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
            setTextColor(Ui.SUB)
        })
    }

    /** 提示 + 保存按钮行（合并为标题区唯一保存按钮） */
    private fun saveRow(hint: String, btnText: String, onSave: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        addView(TextView(this@AppearanceActivity).apply {
            text = hint
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@AppearanceActivity).apply {
            text = btnText
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.PRIMARY, 18, this@AppearanceActivity)
            setPadding(dp(18), dp(7), dp(18), dp(7))
            isClickable = true
            Ui.press(this)
            setOnClickListener { onSave() }
        })
    }

    /** 九宫格开关卡：图标 + 名称 + 状态，点击切换后自动刷新状态文案 */
    private fun gridSwitch(iconRes: Int, seed: Int, title: String, status: () -> String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = Ui.rounded(Ui.INPUT_BG, 14, this@AppearanceActivity)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            val statusTv = TextView(this@AppearanceActivity).apply {
                textSize = 11f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            fun refresh() { statusTv.text = status() }
            refresh()
            addView(Ui.iconBadgeRes(this@AppearanceActivity, iconRes, seed, sizeDp = 38))
            addView(TextView(this@AppearanceActivity).apply {
                text = title
                textSize = 13f
                setTextColor(Ui.TEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(statusTv)
            Ui.press(this)
            setOnClickListener {
                onClick()
                refresh()
            }
        }
    }

    /** 九宫格跳转卡：图标 + 名称 + 当前值，点击跳转（聊天背景/主题），onResume 自动刷新状态 */
    private fun gridAction(iconRes: Int, seed: Int, title: String, status: () -> String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = Ui.rounded(Ui.INPUT_BG, 14, this@AppearanceActivity)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            val statusTv = TextView(this@AppearanceActivity).apply {
                textSize = 11f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            fun refresh() { statusTv.text = status() }
            refresh()
            gridRefreshers.add { refresh() }
            addView(Ui.iconBadgeRes(this@AppearanceActivity, iconRes, seed, sizeDp = 38))
            addView(TextView(this@AppearanceActivity).apply {
                text = title
                textSize = 13f
                setTextColor(Ui.TEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(statusTv)
            Ui.press(this)
            setOnClickListener { onClick() }
        }
    }

    /** 悬浮终端宫格状态文案：开启→已开启；关闭且有权限→已关闭；关闭且无权限→需授权 */
    private fun terminalStatusText(): String {
        if (!AITerminal.isEnabled(this)) {
            return if (android.provider.Settings.canDrawOverlays(this)) getString(R.string.appr_34)
            else getString(R.string.appr_35)
        }
        return getString(R.string.appr_33)
    }

    /** 悬浮终端宫格点击：开启时关闭；关闭时先校验悬浮窗权限（无权限跳授权页），通过后启动服务 */
    private fun toggleTerminal() {
        if (AITerminal.isEnabled(this)) {
            AITerminal.setEnabled(this, false)
            try { AITerminalService.stop(this) } catch (e: Exception) { }
            Toast.makeText(this, getString(R.string.appr_24), Toast.LENGTH_SHORT).show()
        } else {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, getString(R.string.appr_22), Toast.LENGTH_SHORT).show()
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            } else {
                AITerminal.setEnabled(this, true)
                try { AITerminalService.start(this) } catch (e: Exception) {
                    LogStore.e(LogStore.MAIN, "悬浮终端服务启动失败: ${e.message}")
                }
                Toast.makeText(this, getString(R.string.appr_23), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 主题选择弹窗：单选，选中即保存并全量即时生效 */
    private fun showThemePicker() {
        val (dlg, box) = Ui.dialog(this, getString(R.string.appr_31))
        val cur = ThemeManager.current(this).id
        ThemeManager.themes.forEachIndexed { idx, t ->
            if (idx > 0) box.addView(Ui.divider(this@AppearanceActivity))
            val selected = t.id == cur
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(15), dp(14), dp(15))
                isClickable = true
                Ui.press(this)
                setOnClickListener {
                    ThemeManager.setTheme(this@AppearanceActivity, t.id)
                    // 即时生效：刷新全局语义色与气泡色，并重建本页 + 主页（若存活）
                    Ui.applyTheme(t)
                    applyBubbleTheme(t)
                    dlg.dismiss()
                    Toast.makeText(this@AppearanceActivity,
                        getString(R.string.appr_30, getString(t.nameRes)), Toast.LENGTH_SHORT).show()
                    MainActivity.instance?.recreate()
                    recreate()
                }
                addView(TextView(this@AppearanceActivity).apply {
                    text = if (selected) "●" else "○"
                    textSize = 18f
                    setTextColor(if (selected) t.primary else Ui.SUB)
                })
                addView(TextView(this@AppearanceActivity).apply {
                    text = getString(t.nameRes)
                    textSize = 15f
                    setTextColor(Ui.TEXT)
                    setPadding(dp(10), 0, 0, 0)
                })
            })
        }
        dlg.show()
    }
}
