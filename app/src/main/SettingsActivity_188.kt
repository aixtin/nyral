package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 设置页（入口列表）— 与主页统一视觉（灰底 + 白色圆角卡片 + 自绘标题栏）
 * - SSH 配置 → SshConfigActivity
 * - 长期记忆 → MemoryListActivity
 * - 模型 API → ModelConfigActivity（每个模型单独配置）
 */
class SettingsActivity : Activity() {

    private lateinit var modelSubtitle: TextView
    private lateinit var memorySubtitle: TextView
    private lateinit var memModelSubtitle: TextView
    private lateinit var tokenSubtitle: TextView
    private lateinit var debugSubtitle: TextView
    private lateinit var debugBox: LinearLayout
    private lateinit var mainTitleSub: TextView
    private lateinit var drawerTitleSub: TextView
    private lateinit var drawerNoteSub: TextView
    private lateinit var aiNameSub: TextView
    private lateinit var aiPersonaSub: TextView
    private lateinit var uploadSizeSub: TextView
    private lateinit var avatarUserSub: TextView
    private lateinit var avatarAiSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        TitleConfig.init(this)
        UploadConfig.init(this)
        PersonaConfig.init(this)
        AvatarConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)

        // ---- 自绘标题栏 ----
        root.addView(Ui.titleBar(this, "设置"))

        ModeConfig.init(this)
        // ---- 内容区 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // 分组1：连接
        content.addView(Ui.groupLabel(this, "连接"))
        val cardConn = Ui.card(this)
        cardConn.addView(settingsItem("SSH 配置", "连接目标 / 私钥管理", "🔐", 0, {
            startActivity(Intent(this@SettingsActivity, SshConfigActivity::class.java))
        }))
        cardConn.addView(Ui.divider(this))
        cardConn.addView(settingsItem("长期记忆", "已存储 " + MemoryDb(this).count() + " 条记忆", "🧠", 1, {
            startActivity(Intent(this@SettingsActivity, MemoryListActivity::class.java))
        }) { memorySubtitle = it })
        content.addView(cardConn)

        // 分组2：模型 API
        content.addView(Ui.groupLabel(this, "模型 API"))
        val cardModel = Ui.card(this)
        cardModel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, ModelConfigActivity::class.java))
            }
            Ui.press(this)
            addView(Ui.iconBadge(this@SettingsActivity, "模", 2))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@SettingsActivity, "模型配置"))
                modelSubtitle = Ui.itemSub(this@SettingsActivity, "当前: ${ApiConfig.providerLabel(ApiConfig.providerId())} · ${ApiConfig.model()}")
                addView(modelSubtitle)
            })
            addView(Ui.arrow(this@SettingsActivity))
        })
        cardModel.addView(Ui.divider(this))
        cardModel.addView(settingsItem("记忆辅助模型", "摘要索引用 API · " + MemoryApiConfig.statusText(), "辅", 6, {
            startActivity(Intent(this@SettingsActivity, MemModelConfigActivity::class.java))
        }) { memModelSubtitle = it })
        content.addView(cardModel)

        // 分组3：外观
        content.addView(Ui.groupLabel(this, "外观"))
        val cardLook = Ui.card(this)
        cardLook.addView(settingsItem("主页标题", "当前: " + TitleConfig.mainTitle(), "🏠", 8, {
            showTitleEdit("主页标题", TitleConfig.mainTitle(), onSave = { v ->
                TitleConfig.setMainTitle(v)
                if (::mainTitleSub.isInitialized) mainTitleSub.text = "当前: " + TitleConfig.mainTitle()
            })
        }) { mainTitleSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("侧栏标题", "当前: " + TitleConfig.drawerTitle(), "📂", 9, {
            showTitleEdit("侧栏标题", TitleConfig.drawerTitle(), onSave = { v ->
                TitleConfig.setDrawerTitle(v)
                if (::drawerTitleSub.isInitialized) drawerTitleSub.text = "当前: " + TitleConfig.drawerTitle()
            })
        }) { drawerTitleSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("侧栏便签", "当前: " + TitleConfig.drawerNote(), "📝", 10, {
            showTitleEdit("侧栏便签", TitleConfig.drawerNote(), onSave = { v ->
                TitleConfig.setDrawerNote(v)
                if (::drawerNoteSub.isInitialized) drawerNoteSub.text = "当前: " + TitleConfig.drawerNote()
            }, hint = "请输入便签内容（留空恢复默认）", multiline = true, maxLength = 18)
        }) { drawerNoteSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("AI 名字", "当前: " + PersonaConfig.aiName(), "🤖", 12, {
            showTitleEdit("AI 名字", PersonaConfig.aiName(), onSave = { v ->
                PersonaConfig.setAiName(v)
                if (::aiNameSub.isInitialized) aiNameSub.text = "当前: " + PersonaConfig.aiName()
            }, hint = "请输入 AI 名字（留空恢复默认 agent）", maxLength = 20)
        }) { aiNameSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("AI 人设", "system 提示词中的身份描述", "🧬", 13, {
            showTitleEdit("AI 人设", PersonaConfig.aiPersona(), onSave = { v ->
                PersonaConfig.setAiPersona(v)
                if (::aiPersonaSub.isInitialized) aiPersonaSub.text = if (v.isEmpty()) "system 提示词中的身份描述" else "当前: " + v
            }, hint = "请输入 AI 人设描述（留空使用内置默认）", multiline = true, maxLength = 200)
        }) { aiPersonaSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("用户头像", "默认文字头像（我）", "👤", 16, {
            showAvatarDialog(false)
        }) { avatarUserSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("AI 头像", "默认文字头像（AI）", "🤖", 17, {
            showAvatarDialog(true)
        }) { avatarAiSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsSwitch("聊天模式", "并排头像 · 纯文本正文", "💬", 15, ModeConfig.chatMode()) { on ->
            ModeConfig.setChatMode(on)
        })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("聊天背景", "渐变预设 / 自定义图片", "🎨", 4, {
            startActivity(Intent(this@SettingsActivity, ChatBackgroundActivity::class.java))
        }))
        content.addView(cardLook)

        // 分组4：其他
        content.addView(Ui.groupLabel(this, "其他"))
        val cardAbout = Ui.card(this)
        cardAbout.addView(settingsItem("运行日志", "主日志 / 辅助 AI 日志", "📋", 5, {
            startActivity(Intent(this@SettingsActivity, LogActivity::class.java))
        }))
        cardAbout.addView(Ui.divider(this))
        cardAbout.addView(settingsItem("上传大小上限", "附件上传限制 · 当前 " + UploadConfig.maxMb() + " MB", "⬆", 14, {
            showUploadSizeEdit()
        }) { uploadSizeSub = it })
        cardAbout.addView(Ui.divider(this))
        cardAbout.addView(settingsItem("Token 统计", "累计 token 用量 / 请求次数", "∑", 7, {
            showTokenStats()
        }) { tokenSubtitle = it })
        cardAbout.addView(Ui.divider(this))
        debugBox = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsItem("调试服务", DebugServer.statusText(this@SettingsActivity), "🔧", 11, {
                showDebugDialog()
            }) { debugSubtitle = it })
            addView(Ui.divider(this@SettingsActivity))
        }
        debugBox.visibility = if (DebugServer.unlocked(this)) View.VISIBLE else View.GONE
        cardAbout.addView(debugBox)
        cardAbout.addView(settingsItem("关于", "agent v1.0 · 本地引擎", "ℹ", 3, {
            startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
        }))
        content.addView(cardAbout)

        // 底部说明
        content.addView(TextView(this).apply {
            text = "本机运行的爱，全部来自我家那位"
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

    override fun onResume() {
        super.onResume()
        // 从子页返回后仅刷新文本；禁止 recreate（会导致窗口无限重建闪屏黑屏）
        if (::modelSubtitle.isInitialized) {
            modelSubtitle.text = "当前: ${ApiConfig.providerLabel(ApiConfig.providerId())} · ${ApiConfig.model()}"
        }
        if (::memModelSubtitle.isInitialized) {
            memModelSubtitle.text = "摘要索引用 API · " + MemoryApiConfig.statusText()
        }
        if (::memorySubtitle.isInitialized) {
            memorySubtitle.text = "已存储 " + MemoryDb(this).count() + " 条记忆"
        }
        if (::tokenSubtitle.isInitialized) {
            val s = TokenStore.stats(this)
            val a = TokenStore.auxStats(this)
            tokenSubtitle.text = "主 AI ${s.total} · 辅助 AI ${a.total} tokens"
        }
        if (::debugBox.isInitialized) {
            debugBox.visibility = if (DebugServer.unlocked(this)) View.VISIBLE else View.GONE
        }
    }

    /** 调试服务弹窗: 启用开关 / 端口 / Token 展示与重置 / 局域网访问 */
    private fun showDebugDialog() {
        val (dlg, box) = Ui.dialog(this, "调试服务", maxHeightRatio = 0.8, jellyOvershoot = 1.4f)
        val ctx = this@SettingsActivity
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // 启用开关
        val swEnable = Switch(ctx)
        swEnable.isChecked = DebugServer.isEnabled(ctx)
        swEnable.isEnabled = debuggable
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.itemTitle(ctx, "启用调试服务"))
                addView(Ui.hint(ctx, if (debuggable) "本机 HTTP 接口, 需 Token 鉴权" else "仅 debug 构建可用").apply {
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(swEnable)
        })

        // 局域网访问开关
        val swLan = Switch(ctx)
        swLan.isChecked = DebugServer.lanEnabled(ctx)
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.itemTitle(ctx, "允许局域网访问"))
                addView(Ui.hint(ctx, "默认仅本机(adb forward 可访问)").apply {
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(swLan)
        })

        // 端口
        val portEdit = Ui.input(ctx, "端口")
        portEdit.setText(DebugServer.port(ctx).toString())
        portEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(Ui.itemTitle(ctx, "端口"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(portEdit, LinearLayout.LayoutParams(dp(110), dp(42)))
        })

        // Token 展示 + 重置
        val tokenText = TextView(ctx).apply {
            text = DebugServer.token(ctx)
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(4), dp(3), dp(4), dp(3))
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(Ui.itemTitle(ctx, "访问 Token"))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tokenText, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, dp(4), dp(8), 0) })
                addView(Ui.dangerBtn(ctx, "重置", {
                    DebugServer.resetToken(ctx)
                    tokenText.text = DebugServer.token(ctx)
                    Toast.makeText(ctx, "Token 已重置", Toast.LENGTH_SHORT).show()
                }))
            })
        })

        fun syncState() {
            if (debuggable) {
                if (DebugServer.isEnabled(ctx) && swLan.isChecked != DebugServer.lanEnabled(ctx)) {
                    // 仅显示刷新, 不在此处重启
                }
            }
        }

        swEnable.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            DebugServer.setEnabled(ctx, on)
            if (on) {
                DebugServer.stop()
                // 端口/局域网变化需重启: 简单处理为关后重开才生效, 提示用户
                if (::debugSubtitle.isInitialized) debugSubtitle.text = DebugServer.statusText(ctx)
                Toast.makeText(ctx, "已启用（重启应用后生效）", Toast.LENGTH_SHORT).show()
            } else {
                DebugServer.stop()
                if (::debugSubtitle.isInitialized) debugSubtitle.text = DebugServer.statusText(ctx)
            }
        }
        swLan.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            DebugServer.setLan(ctx, on)
            if (DebugServer.isEnabled(ctx) && DebugServer.running()) {
                // 局域网开关变更需重启服务
                DebugServer.stop()
                DebugServer.restart(ctx)
            }
            if (::debugSubtitle.isInitialized) debugSubtitle.text = DebugServer.statusText(ctx)
        }
        portEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val p = s.toString().trim().toIntOrNull()
                if (p != null && p in 1024..65535) DebugServer.setPort(ctx, p)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            val closeBtn = Ui.dialogCancelBtn(ctx, "关闭", { dlg.dismiss() })
            closeBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(closeBtn)
        })

        syncState()
        dlg.show()
    }

    /** Token 统计弹窗: 顶部 主 AI / 辅助 AI tab 切换(同运行日志), 各自累计+今日, 支持清空(二次确认) */
    private fun showTokenStats() {
        val s = TokenStore.stats(this)
        val a = TokenStore.auxStats(this)
        val (dlg, box) = Ui.dialog(this, "Token 统计", maxHeightRatio = 0.8, jellyOvershoot = 1.4f)
        var isMain = true   // 当前 tab: true=主 AI, false=辅助 AI

        // ---- tab 行 ----
        val tabMain = TextView(this).apply {
            text = "主 AI"
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            Ui.press(this)
        }
        val tabMem = TextView(this).apply {
            text = "辅助 AI"
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            Ui.press(this)
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabRow.addView(tabMain, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(0, 0, dp(6), 0)
        })
        tabRow.addView(tabMem, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        })
        box.addView(tabRow)

        // ---- 内容区: 随 tab 切换 ----
        val stats = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        box.addView(stats)

        fun syncTab() {
            updateTokenTab(tabMain, isMain)
            updateTokenTab(tabMem, !isMain)
        }
        fun render() {
            val tp: Long; val tc: Long; val cnt: Long; val dyp: Long; val dyc: Long
            if (isMain) {
                tp = s.totalPrompt; tc = s.totalCompletion; cnt = s.count
                dyp = s.dayPrompt; dyc = s.dayCompletion
            } else {
                tp = a.totalPrompt; tc = a.totalCompletion; cnt = a.count
                dyp = a.dayPrompt; dyc = a.dayCompletion
            }
            stats.text = buildString {
                append("累计输入  ").append(tp).append("\n")
                append("累计输出  ").append(tc).append("\n")
                append("累计总计  ").append(tp + tc).append("\n")
                append("请求次数  ").append(cnt).append(" 次\n\n")
                append("今日输入  ").append(dyp).append("\n")
                append("今日输出  ").append(dyc).append("\n")
                append("今日总计  ").append(dyp + dyc).append(" tokens")
            }
        }
        tabMain.setOnClickListener { isMain = true; syncTab(); render() }
        tabMem.setOnClickListener { isMain = false; syncTab(); render() }

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(Ui.dangerBtn(this@SettingsActivity, "清空", {
                val (dlg2, box2) = Ui.dialog(this@SettingsActivity, "确认清空 Token 统计？", jellyOvershoot = 1.4f)
                box2.addView(Ui.hint(this@SettingsActivity, "清空后无法恢复，主 AI 与辅助 AI 的累计和今日数据都会归零。").apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                box2.addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, 0)
                    val confirmBtn = Ui.dangerBtn(this@SettingsActivity, "确认清空", {
                        TokenStore.clear(this@SettingsActivity)
                        dlg2.dismiss()
                        dlg.dismiss()
                        if (::tokenSubtitle.isInitialized) tokenSubtitle.text = "主 AI 0 · 辅助 AI 0 tokens"
                    })
                    addView(confirmBtn)
                    val cancelBtn = Ui.dialogCancelBtn(this@SettingsActivity, "取消", { dlg2.dismiss() })
                    cancelBtn.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10) }
                    addView(cancelBtn)
                })
                dlg2.show()
            }))
            val closeBtn = Ui.dialogCancelBtn(this@SettingsActivity, "关闭", { dlg.dismiss() })
            closeBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(closeBtn)
        })

        syncTab()
        render()
        dlg.show()
    }

    /** Token 统计 tab 按钮样式: 选中蓝底白字, 未选中白底灰字(同运行日志) */
    private fun updateTokenTab(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(0)
        tv.background = Ui.rounded(if (selected) 0xFF3B82F6.toInt() else Color.WHITE, 12, this)
        tv.setTextColor(if (selected) Color.WHITE else 0xFF666666.toInt())
    }

    /** 上传大小上限编辑弹窗: 输入正整数(单位 MB), 非法或留空回退默认 20 */
    private fun showUploadSizeEdit() {
        val (dlg, box) = Ui.dialog(this, "修改上传大小上限", jellyOvershoot = 1.4f, animate = false)
        box.addView(Ui.hint(this, "聊天附件上传的大小限制（单位 MB），范围 1~500，默认 20"))
        val input = Ui.input(this, "如 50").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(UploadConfig.maxMb().toString())
            setSelection(text.length)
        }
        box.addView(input)
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(Ui.dialogCancelBtn(this@SettingsActivity, "保存", {
                val mb = input.text.toString().trim().toIntOrNull()
                if (mb == null || mb <= 0) {
                    Toast.makeText(this@SettingsActivity, "请输入正整数（MB）", Toast.LENGTH_SHORT).show()
                    return@dialogCancelBtn
                }
                UploadConfig.setMaxMb(mb)
                if (::uploadSizeSub.isInitialized) uploadSizeSub.text = "附件上传限制 · 当前 " + UploadConfig.maxMb() + " MB"
                dlg.dismiss()
            }))
            val cancelBtn = Ui.dialogCancelBtn(this@SettingsActivity, "取消", { dlg.dismiss() })
            cancelBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(cancelBtn)
        })
        dlg.show()
    }

    /** 标题/便签自定义弹窗: 输入新内容, 留空保存回退默认值; multiline 时支持多行便签; maxLength>0 时显示实时字数提示并到上限自动停止 */
    private fun showTitleEdit(title: String, current: String, onSave: (String) -> Unit, hint: String = "请输入$title（留空恢复默认）", multiline: Boolean = false, maxLength: Int = 0) {
        // animate=false: 标题编辑弹窗不做缩放动画(硬件加速下缩放会把圆角拉伸成直角), 即开即显
        val (dlg, box) = Ui.dialog(this, "修改$title", jellyOvershoot = 1.4f, animate = false)
        val input = Ui.input(this, hint).apply {
            setText(current)
            setSelection(text.length)
            if (multiline) {
                setSingleLine(false)
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
        }
        box.addView(input)
        // 字数提示: 实时显示 x/上限, 到上限自动停住不再增加(不静默吞字, 用户能看到原因)
        var counter: TextView? = null
        if (maxLength > 0) {
            counter = TextView(this).apply {
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.END
                setPadding(0, dp(4), dp(2), 0)
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val len = s?.length ?: 0
                    if (len > maxLength) {
                        val keep = s!!.substring(0, maxLength)
                        input.setText(keep)
                        input.setSelection(keep.length)
                    }
                    counter?.text = "${input.text.length}/$maxLength"
                }
            })
            counter.text = "${input.text.length}/$maxLength"
            box.addView(counter)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(Ui.dialogCancelBtn(this@SettingsActivity, "保存", {
                onSave(input.text.toString().trim())
                dlg.dismiss()
            }))
            val cancelBtn = Ui.dialogCancelBtn(this@SettingsActivity, "取消", { dlg.dismiss() })
            cancelBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(cancelBtn)
        })
        dlg.show()
    }

    private var pendingAvatarIsAi = false
    private val REQ_AVATAR_USER = 3001
    private val REQ_AVATAR_AI = 3002

    private fun refreshAvatarSubs() {
        if (::avatarUserSub.isInitialized)
            avatarUserSub.text = if (AvatarConfig.hasUserAvatar()) "已设置自定义头像" else "默认文字头像（我）"
        if (::avatarAiSub.isInitialized)
            avatarAiSub.text = if (AvatarConfig.hasAiAvatar()) "已设置自定义头像" else "默认文字头像（AI）"
    }

    private fun showAvatarDialog(isAi: Boolean) {
        val has = if (isAi) AvatarConfig.hasAiAvatar() else AvatarConfig.hasUserAvatar()
        val (dlg, box) = Ui.dialog(this, if (isAi) "更换 AI 头像" else "更换用户头像", jellyOvershoot = 1.4f, animate = false)
        box.addView(Ui.hint(this, if (has) "当前为自定义头像" else "当前为默认文字头像"))
        val vstack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        vstack.addView(Ui.dialogCancelBtn(this@SettingsActivity, "从相册选择图片", {
            dlg.dismiss()
            pendingAvatarIsAi = isAi
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            try {
                startActivityForResult(i, if (isAi) REQ_AVATAR_AI else REQ_AVATAR_USER)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "无法打开相册: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }))
        if (has) {
            val restoreBtn = Ui.dangerBtn(this@SettingsActivity, "恢复默认", {
                if (isAi) AvatarConfig.clearAiAvatar() else AvatarConfig.clearUserAvatar()
                dlg.dismiss()
                refreshAvatarSubs()
                Toast.makeText(this@SettingsActivity, "已恢复默认头像", Toast.LENGTH_SHORT).show()
            })
            restoreBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            vstack.addView(restoreBtn)
        }
        box.addView(vstack)
        dlg.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        // 裁剪页返回：拿到 1:1 正方形临时文件并落盘
        if (requestCode == AvatarCropActivity.REQ_CROP) {
            val path = data.getStringExtra(AvatarCropActivity.EXTRA_RESULT_PATH)
            if (path == null) return
            val file = File(path)
            val ok = if (pendingAvatarIsAi) AvatarConfig.setAiAvatarFromSquare(file)
            else AvatarConfig.setUserAvatarFromSquare(file)
            Toast.makeText(this, if (ok) "头像已更新" else "头像设置失败", Toast.LENGTH_SHORT).show()
            refreshAvatarSubs()
            file.delete()
            return
        }

        if (data.data == null) return
        // 相册选图返回：跳转 1:1 自由裁剪页
        val uri = data.data!!
        try {
            val i = Intent(this, AvatarCropActivity::class.java).apply {
                putExtra(AvatarCropActivity.EXTRA_URI, uri)
                putExtra(AvatarCropActivity.EXTRA_IS_AI, pendingAvatarIsAi)
            }
            startActivityForResult(i, AvatarCropActivity.REQ_CROP)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开裁剪: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun settingsItem(title: String, subtitle: String, icon: String, seed: Int, onClick: () -> Unit, subRef: ((TextView) -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            Ui.press(this)
            addView(Ui.iconBadge(this@SettingsActivity, icon, seed))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@SettingsActivity, title))
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    subRef?.invoke(this)
                })
            })
            addView(Ui.arrow(this@SettingsActivity))
        }
    }

    private fun settingsSwitch(title: String, subtitle: String, icon: String, seed: Int, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(Ui.iconBadge(this@SettingsActivity, icon, seed))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@SettingsActivity, title))
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(Switch(this@SettingsActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
