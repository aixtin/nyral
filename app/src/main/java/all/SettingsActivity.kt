package io.github.aixtin.droidagent

import io.github.aixtin.droidagent.R

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import java.io.FileOutputStream

/**
 * 设置页（入口列表）— 与主页统一视觉（灰底 + 白色圆角卡片 + 自绘标题栏）
 * - SSH 配置 → SshConfigActivity
 * - 长期记忆 → MemoryListActivity
 * - 模型 API → ModelConfigActivity（每个模型单独配置）
 */
class SettingsActivity : Activity() {

    private lateinit var modelSubtitle: TextView
    private lateinit var memorySubtitle: TextView
    private lateinit var summarySubtitle: TextView
    private lateinit var mcpSubtitleView: TextView
    private lateinit var memModelSubtitle: TextView
    private lateinit var tokenSubtitle: TextView
    private lateinit var debugSubtitle: TextView
    private lateinit var debugBox: LinearLayout
    private lateinit var uploadSizeSub: TextView
    private lateinit var permSubtitleView: TextView

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
        root.addView(Ui.titleBar(this, getString(R.string.settings_title)))

        ModeConfig.init(this)
        // ---- 内容区 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // 分组1：连接
        content.addView(Ui.groupLabel(this, getString(R.string.settings_group_conn)))
        val cardConn = Ui.card(this)
        cardConn.addView(settingsItem(getString(R.string.settings_ssh_config), getString(R.string.settings_ssh_config_sub), "🔐", 0, {
            startActivity(Intent(this@SettingsActivity, SshConfigActivity::class.java))
        }))
        cardConn.addView(Ui.divider(this))
        cardConn.addView(settingsItem(getString(R.string.settings_mcp), mcpStatus(), "🧩", 0, {
            startActivity(Intent(this@SettingsActivity, McpConfigActivity::class.java))
        }) { mcpSubtitleView = it })
        cardConn.addView(Ui.divider(this))
        cardConn.addView(settingsItem(getString(R.string.settings_memory), getString(R.string.settings_memory_count, MemoryDb(this).count()), "🧠", 1, {
            startActivity(Intent(this@SettingsActivity, MemoryListActivity::class.java))
        }) { memorySubtitle = it })
        cardConn.addView(Ui.divider(this))
        cardConn.addView(settingsItem(getString(R.string.settings_memory_index), summaryStatus(), "索", 2, {
            startActivity(Intent(this@SettingsActivity, MemorySummaryActivity::class.java))
        }) { summarySubtitle = it })
        content.addView(cardConn)

        // 分组2：模型 API
        content.addView(Ui.groupLabel(this, getString(R.string.settings_group_model)))
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
                addView(Ui.itemTitle(this@SettingsActivity, getString(R.string.settings_model_config)))
                modelSubtitle = Ui.itemSub(this@SettingsActivity, getString(R.string.settings_model_current, ApiConfig.providerLabel(ApiConfig.providerId()), ApiConfig.model()))
                addView(modelSubtitle)
            })
            addView(Ui.arrow(this@SettingsActivity))
        })
        cardModel.addView(Ui.divider(this))
        cardModel.addView(settingsItem(getString(R.string.settings_mem_model), getString(R.string.settings_mem_model_sub, MemoryApiConfig.statusText()), "辅", 6, {
            startActivity(Intent(this@SettingsActivity, MemModelConfigActivity::class.java))
        }) { memModelSubtitle = it })
        content.addView(cardModel)

        // 分组3：外观
        content.addView(Ui.groupLabel(this, getString(R.string.settings_group_look)))
        val cardLook = Ui.card(this)
        cardLook.addView(settingsItem(getString(R.string.settings_look), getString(R.string.settings_look_sub), "🎨", 8, {
            startActivity(Intent(this@SettingsActivity, AppearanceActivity::class.java))
        }))
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem(getString(R.string.settings_ai_persona), getString(R.string.settings_ai_persona_sub), "🧩", 18, {
            startActivity(Intent(this@SettingsActivity, PersonalityActivity::class.java))
        }))
        content.addView(cardLook)

        // 分组4：其他
        content.addView(Ui.groupLabel(this, getString(R.string.settings_group_other)))
        val cardAbout = Ui.card(this)
        cardAbout.addView(settingsItem(getString(R.string.settings_log), getString(R.string.settings_log_sub), "📋", 5, {
            startActivity(Intent(this@SettingsActivity, LogActivity::class.java))
        }))
        cardAbout.addView(Ui.divider(this))
        cardAbout.addView(settingsItem(getString(R.string.settings_upload_size), getString(R.string.settings_upload_size_sub, UploadConfig.maxMb()), "⬆", 14, {
            showUploadSizeEdit()
        }) { uploadSizeSub = it })
        cardAbout.addView(Ui.divider(this))
        cardAbout.addView(settingsItem(getString(R.string.settings_token), getString(R.string.settings_token_sub), "∑", 7, {
            showTokenStats()
        }) { tokenSubtitle = it })
        cardAbout.addView(Ui.divider(this))
        debugBox = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsItem(getString(R.string.settings_debug), DebugServer.statusText(this@SettingsActivity), "🔧", 11, {
                showDebugDialog()
            }) { debugSubtitle = it })
            addView(Ui.divider(this@SettingsActivity))
        }
        debugBox.visibility = if (DebugServer.unlocked(this)) View.VISIBLE else View.GONE
        cardAbout.addView(debugBox)
        cardAbout.addView(Ui.divider(this))
        // 权限管理: 查看/补开全部依赖权限(部分手机长时间不用会自动收回)
        cardAbout.addView(Ui.divider(this))
        cardAbout.addView(settingsItem(getString(R.string.settings_perm), getString(R.string.settings_perm_sub), "🔏", 9, {
            startActivity(Intent(this@SettingsActivity, PermissionsActivity::class.java))
        }) { permSubtitleView = it })
        cardAbout.addView(settingsItem(getString(R.string.settings_about), getString(R.string.settings_about_sub), "ℹ", 3, {
            startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
        }))
        content.addView(cardAbout)

        // 底部说明
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_about_motto)
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
            modelSubtitle.text = getString(R.string.settings_model_current, ApiConfig.providerLabel(ApiConfig.providerId()), ApiConfig.model())
        }
        if (::memModelSubtitle.isInitialized) {
            memModelSubtitle.text = getString(R.string.settings_mem_model_sub, MemoryApiConfig.statusText())
        }
        if (::memorySubtitle.isInitialized) {
            memorySubtitle.text = getString(R.string.settings_memory_count, MemoryDb(this).count())
        }
        if (::summarySubtitle.isInitialized) {
            summarySubtitle.text = summaryStatus()
        }
        if (::mcpSubtitleView.isInitialized) {
            mcpSubtitleView.text = mcpStatus()
        }
        if (::tokenSubtitle.isInitialized) {
            val s = TokenStore.stats(this)
            val a = TokenStore.auxStats(this)
            tokenSubtitle.text = getString(R.string.settings_token_total, s.total, a.total)
        }
        if (::debugBox.isInitialized) {
            debugBox.visibility = if (DebugServer.unlocked(this)) View.VISIBLE else View.GONE
        }
        if (::permSubtitleView.isInitialized) {
            permSubtitleView.text = permStatus()
        }
    }

    /** 调试服务弹窗: 启用开关 / 端口 / Token 展示与重置 / 局域网访问 */
    private fun showDebugDialog() {
        val (dlg, box) = Ui.dialog(this, getString(R.string.settings_debug), maxHeightRatio = 0.8, jellyOvershoot = 1.4f)
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
                addView(Ui.itemTitle(ctx, getString(R.string.settings_debug_enable)))
                addView(Ui.hint(ctx, if (debuggable) getString(R.string.settings_debug_enable_hint) else getString(R.string.settings_debug_release_hint)).apply {
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
                addView(Ui.itemTitle(ctx, getString(R.string.settings_debug_lan)))
                addView(Ui.hint(ctx, getString(R.string.settings_debug_lan_hint)).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(swLan)
        })

        // 端口
        val portEdit = Ui.input(ctx, getString(R.string.settings_debug_port))
        portEdit.setText(DebugServer.port(ctx).toString())
        portEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(Ui.itemTitle(ctx, getString(R.string.settings_debug_port)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            addView(Ui.itemTitle(ctx, getString(R.string.settings_debug_token)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tokenText, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, dp(4), dp(8), 0) })
                addView(Ui.dangerBtn(ctx, getString(R.string.settings_debug_reset), {
                    DebugServer.resetToken(ctx)
                    tokenText.text = DebugServer.token(ctx)
                    Toast.makeText(ctx, getString(R.string.settings_toast_token_reset), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(ctx, getString(R.string.settings_toast_debug_enabled), Toast.LENGTH_SHORT).show()
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
            val cParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val hideBtn = Ui.dangerBtn(ctx, getString(R.string.settings_debug_hide), {
                DebugServer.hideDebug(this@SettingsActivity)
                if (::debugBox.isInitialized) debugBox.visibility = View.GONE
                dlg.dismiss()
                android.widget.Toast.makeText(
                    ctx, getString(R.string.settings_toast_debug_hidden), android.widget.Toast.LENGTH_SHORT
                ).show()
            })
            hideBtn.layoutParams = cParams
            addView(hideBtn)
            addView(LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val closeBtn = Ui.dialogCancelBtn(ctx, getString(R.string.settings_btn_close), { dlg.dismiss() })
            closeBtn.layoutParams = cParams
            addView(closeBtn)
        })

        syncState()
        dlg.show()
    }

    /** Token 统计弹窗: 顶部 主 AI / 辅助 AI tab 切换(同运行日志), 各自累计+今日, 支持清空(二次确认) */
    private fun showTokenStats() {
        val s = TokenStore.stats(this)
        val a = TokenStore.auxStats(this)
        val (dlg, box) = Ui.dialog(this, getString(R.string.settings_token), maxHeightRatio = 0.8, jellyOvershoot = 1.4f)
        var isMain = true   // 当前 tab: true=主 AI, false=辅助 AI

        // ---- tab 行 ----
        val tabMain = TextView(this).apply {
            text = getString(R.string.settings_tab_main_ai)
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            Ui.press(this)
        }
        val tabMem = TextView(this).apply {
            text = getString(R.string.settings_tab_mem_ai)
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
                append(getString(R.string.settings_stats_input, tp)).append("\n")
                append(getString(R.string.settings_stats_output, tc)).append("\n")
                append(getString(R.string.settings_stats_total, tp + tc)).append("\n")
                append(getString(R.string.settings_stats_count, cnt)).append("\n\n")
                append(getString(R.string.settings_stats_today_input, dyp)).append("\n")
                append(getString(R.string.settings_stats_today_output, dyc)).append("\n")
                append(getString(R.string.settings_stats_today_total, dyp + dyc))
            }
        }
        tabMain.setOnClickListener { isMain = true; syncTab(); render() }
        tabMem.setOnClickListener { isMain = false; syncTab(); render() }

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(Ui.dangerBtn(this@SettingsActivity, getString(R.string.settings_btn_clear), {
                val (dlg2, box2) = Ui.dialog(this@SettingsActivity, getString(R.string.settings_dialog_clear_token), jellyOvershoot = 1.4f)
                box2.addView(Ui.hint(this@SettingsActivity, getString(R.string.settings_clear_token_hint)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                box2.addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, 0)
                    val confirmBtn = Ui.dangerBtn(this@SettingsActivity, getString(R.string.settings_btn_confirm_clear), {
                        TokenStore.clear(this@SettingsActivity)
                        dlg2.dismiss()
                        dlg.dismiss()
                        if (::tokenSubtitle.isInitialized) tokenSubtitle.text = getString(R.string.settings_token_zero)
                    })
                    addView(confirmBtn)
                    val cancelBtn = Ui.dialogCancelBtn(this@SettingsActivity, getString(R.string.dialog_cancel), { dlg2.dismiss() })
                    cancelBtn.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10) }
                    addView(cancelBtn)
                })
                dlg2.show()
            }))
            val closeBtn = Ui.dialogCancelBtn(this@SettingsActivity, getString(R.string.settings_btn_close), { dlg.dismiss() })
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

    private fun showUploadSizeEdit() {
        val (dlg, box) = Ui.dialog(this, getString(R.string.settings_dialog_upload_title), jellyOvershoot = 1.4f, animate = false)
        box.addView(Ui.hint(this, getString(R.string.settings_upload_hint)))
        val input = Ui.input(this, getString(R.string.settings_hint_upload_example)).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(UploadConfig.maxMb().toString())
            setSelection(text.length)
        }
        box.addView(input)
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(Ui.dialogCancelBtn(this@SettingsActivity, getString(R.string.settings_btn_save), {
                val mb = input.text.toString().trim().toIntOrNull()
                if (mb == null || mb <= 0) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_toast_upload_invalid), Toast.LENGTH_SHORT).show()
                    return@dialogCancelBtn
                }
                UploadConfig.setMaxMb(mb)
                if (::uploadSizeSub.isInitialized) uploadSizeSub.text = getString(R.string.settings_upload_size_sub, UploadConfig.maxMb())
                dlg.dismiss()
            }))
            val cancelBtn = Ui.dialogCancelBtn(this@SettingsActivity, getString(R.string.dialog_cancel), { dlg.dismiss() })
            cancelBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(cancelBtn)
        })
        dlg.show()
    }

    private fun mcpStatus(): String {
        val servers = McpConfigStore.load(this)
        return if (servers.isEmpty()) getString(R.string.settings_mcp_none)
        else getString(R.string.settings_mcp_count, servers.size, servers.joinToString(getString(R.string.comma_sep)) { it.name })
    }

    /** 整理记忆索引副标题：AI 整理的主题索引摘要条数 */
    private fun summaryStatus(): String {
        val s = MemoryDb(this).loadSummary()?.trim()
        return if (s.isNullOrEmpty()) getString(R.string.settings_summary_none) else getString(R.string.settings_summary_count, s.count { it == '\n' } + 1)
    }

    /** 权限管理入口副标题: 汇总当前未授权项(与本机适配后按需统计) */
    private fun permStatus(): String {
        var missing = 0
        fun checked(cond: Boolean) { if (cond) missing++ }
        checked(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        checked(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        checked(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))
        checked(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
        checked(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls())
        return if (missing == 0) getString(R.string.settings_perm_all) else getString(R.string.settings_perm_missing, missing)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
