package io.github.aixtin.nyral

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import io.github.aixtin.nyral.R
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 首启引导页（配置向导版）：
 * Step0 亮点介绍 + 聊天/Agent 模式双卡 + 协议同意区；
 * Step1 选择模型服务商（独立一屏，顶部标题栏返回；复用 ApiConfig 预设三家 + 自定义入口）；
 * Step2 填写 API Key（? 说明首次默认展开、官网跳转、测试连接），完成或稍后配置后进入权限页。
 * 老用户（已看过引导）直接透传进主界面。
 */
class GuideActivity : Activity() {

    private fun prefs() = getSharedPreferences("app_prefs", MODE_PRIVATE)

    private var rootContent: LinearLayout? = null
    private var bottomBar: LinearLayout? = null
    private var step = 0
    private var selectedProvider: ApiConfig.Provider? = null

    // Step1 服务商列表的选中态刷新引用
    private val providerList = mutableListOf<ApiConfig.Provider>()
    private val providerCardViews = mutableListOf<LinearLayout>()
    private val providerTitleViews = mutableListOf<TextView>()
    private val providerSubViews = mutableListOf<TextView>()

    // Step2 引用
    private var keyInput: EditText? = null        // Key 内联输入框：输入即写入 pendingKeys，回车拉取模型
    private val pendingKeys = mutableMapOf<String, String>()          // 各服务商已确认密钥（providerId -> key）
    private val fetchedModelsMap = mutableMapOf<String, List<String>>()  // 各服务商最近拉取到的实时模型列表
    private val selectedModels = mutableMapOf<String, String>()       // 各服务商最终选中的模型
    private var helpCard: LinearLayout? = null
    private var connTv: TextView? = null
    private var scrollView: ScrollView? = null
    private var modelInput: TextView? = null      // 模型行：只读显示当前选中模型
    private val selectedGuideModels = mutableListOf<String>()
    // 顶部 Tab 栏引用
    private val tabViews = mutableListOf<TextView>()
    private val tabBars = mutableListOf<View>()

    // Step3 完成与设置: 权限列表 + 模式选择引用
    private var listBox: LinearLayout? = null
    /** 折叠的分组(默认收起"高级与扩展": 12 项平铺太长, 常用项不该被淹没) */
    private val collapsedGroups = mutableSetOf(PermSpec.GROUP_ADV)
    /** root 授权状态: -1=检测中/未知, 0=未授权, 1=已授权(首帧取进程缓存, 后台重探) */
    private var rootGranted = when (RootCheck.cachedGranted()) {
        true -> 1
        false -> 0
        else -> -1
    }
    /** 设备是否具备 root 环境: 仅后台探测, 主线程读缓存(避免列表首帧卡在 su 子进程) */
    private var deviceRooted = RootCheck.cachedRooted() ?: false
    /** 本次进程内是否已用 root 自动补齐过自身权限(仅一次, 幂等) */
    @Volatile private var rootAutoGranted = false
    /** 进入 Step3(完成与设置) 前所在的步骤: 返回按钮按来源回跳 */
    private var step3SourceStep = 2


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        ModeConfig.init(this)
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
        root.setBackgroundColor(Ui.BG)
        // edge-to-edge（targetSdk 36，API 35+ 强制）统一消费系统栏 inset：状态栏顶 + 导航栏底，
        // 否则矮屏/手势条/三键机型底部内容被导航栏遮挡（表现为“引导页超出屏幕底部”）
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        rootContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(20))
        }
        scrollView = ScrollView(this).apply {
            addView(rootContent)
            isFillViewport = true
            clipToPadding = false
        }
        // 底部固定操作栏：每步主按钮（开始授权/下一步/完成）始终可见，不随内容滚动，
        // 矮屏机型无需滑动即可操作，实现不同屏幕高度自适应
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(24), dp(10), dp(24), dp(10))
        }
        root.addView(buildTabBar(), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        if (step == 3) {
            refreshPerms()
            probeRoot()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (step != 3) return
        refreshPerms()
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, getString(R.string.perm_04), Toast.LENGTH_LONG).show()
        }
    }

    private fun showStep(n: Int) {
        step = n
        updateTabs(n)
        val c = rootContent ?: return
        val bb = bottomBar ?: return
        c.removeAllViews()
        bb.removeAllViews()
        when (n) {
            0 -> buildStep0(c, bb)
            1 -> buildStep1(c, bb)
            2 -> buildStep2(c, bb)
            3 -> buildStep3(c, bb)
        }
    }

    // ===================== 顶部 Tab 栏：三步进度指示 =====================
    private fun buildTabBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Ui.SURFACE)
        val titles = arrayOf(
            getString(R.string.guide_tab_intro),
            getString(R.string.guide_tab_provider),
            getString(R.string.guide_tab_key),
            getString(R.string.guide_tab_done)
        )
        titles.forEachIndexed { i, t ->
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@GuideActivity).apply {
                    text = t
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(if (i == 0) Ui.PRIMARY else Ui.SUB)
                    typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding(0, dp(12), 0, dp(8))
                }.also { tabViews.add(it) })
                addView(View(this@GuideActivity).apply {
                    setBackgroundColor(if (i == 0) Ui.PRIMARY else Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
                }.also { tabBars.add(it) })
            })
        }
    }

    private fun updateTabs(n: Int) {
        tabViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == n) Ui.PRIMARY else Ui.SUB)
            tv.typeface = if (i == n) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        tabBars.forEachIndexed { i, v ->
            v.setBackgroundColor(if (i == n) Ui.PRIMARY else Color.TRANSPARENT)
        }
    }

    // ===================== Step0：亮点 + 模式双卡 + 协议 =====================
    private fun buildStep0(c: LinearLayout, bottom: LinearLayout) {
        // 顶部品牌区：图标 + 品牌名 + 标语，整体居中（顶部留白收紧，矮屏免滚动）
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(16), 0, 0)
            }
            addView(ImageView(this@GuideActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                background = Ui.rounded(Ui.SURFACE, 24, this@GuideActivity)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
            })
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_app_name)
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setTextColor(Ui.TEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            })
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_slogan)
                textSize = 13f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        })


        // 模式双卡横排：聊天 / Agent —— 这是引导流程里唯一的选择入口, 点卡片即选中
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(40)
            }
        }
        fun fillModeRow() {
            modeRow.removeAllViews()
            modeRow.addView(modeCard(
                R.drawable.ic_guide_chat,
                getString(R.string.guide_mode_chat_t),
                getString(R.string.guide_mode_chat_d),
                listOf(
                    Triple(R.drawable.ic_guide_chat, getString(R.string.guide_f1_t), getString(R.string.guide_point_chat_d)),
                    Triple(R.drawable.ic_guide_folder, getString(R.string.guide_f3_t), getString(R.string.guide_point_file_d))
                ),
                checked = ModeConfig.chatMode(),
                onPick = { ModeConfig.setChatMode(true); fillModeRow() }
            ))
            modeRow.addView(Space(this@GuideActivity), LinearLayout.LayoutParams(dp(10), 1))
            modeRow.addView(modeCard(
                R.drawable.ic_guide_tool,
                getString(R.string.guide_mode_agent_t),
                getString(R.string.guide_mode_agent_d),
                listOf(
                    Triple(R.drawable.ic_guide_tool, getString(R.string.guide_f2_t), getString(R.string.guide_f2_d)),
                    Triple(R.drawable.ic_guide_server, getString(R.string.guide_f4_t), getString(R.string.guide_f4_d))
                ),
                checked = !ModeConfig.chatMode(),
                onPick = { ModeConfig.setChatMode(false); fillModeRow() }
            ))
        }
        fillModeRow()
        c.addView(modeRow)
        c.addView(TextView(this).apply {
            text = getString(R.string.guide_mode_pick_tip)
            textSize = 11f
            setTextColor(Ui.SUB)
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        // ===== 协议勾选栏：方形勾选框 + 同意文字 + 品牌色链接 =====
        var agreed = prefs().getBoolean("privacy_accepted", false)
        var disclaimerRead = prefs().getBoolean("disclaimer_read", false)
        var privacyRead = prefs().getBoolean("privacy_read", false)
        val boxChecked = booleanArrayOf(agreed)
        var dLink: TextView? = null
        var pLink: TextView? = null
        var cb: ImageView? = null
        fun refreshLinks() {
            dLink?.setTextColor(Ui.PRIMARY)
            pLink?.setTextColor(Ui.PRIMARY)
        }
        fun refreshBox() {
            val v = cb ?: return
            if (boxChecked[0]) {
                v.setImageDrawable(Ui.lucideBadgeCheck(this@GuideActivity, Color.WHITE, 12))
                v.background = Ui.rounded(0xFF0B93F6.toInt(), 6, this@GuideActivity)
            } else {
                v.setImageDrawable(null)
                v.background = Ui.roundedBorder(0xFFD9D9DE.toInt(), 6, 1, this@GuideActivity)
            }
        }
        cb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(3), dp(3), dp(3), dp(3))
            isClickable = true
            setOnClickListener {
                boxChecked[0] = !boxChecked[0]
                if (boxChecked[0]) {
                    if (!disclaimerRead && !privacyRead) {
                        agreed = true
                        disclaimerRead = true
                        privacyRead = true
                        prefs().edit()
                            .putBoolean("disclaimer_read", true)
                            .putBoolean("privacy_read", true)
                            .apply()
                    } else if (!(disclaimerRead && privacyRead)) {
                        val unread = if (!disclaimerRead) getString(R.string.guide_disclaimer) else getString(R.string.guide_privacy)
                        Toast.makeText(this@GuideActivity, getString(R.string.guide_agree_unread, unread), Toast.LENGTH_SHORT).show()
                        boxChecked[0] = false
                        agreed = false
                    } else {
                        agreed = true
                    }
                } else {
                    agreed = false
                }
                refreshBox()
            }
        }
        refreshBox()
        val agreeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(24), 0, 0)
            }
        }
        agreeRow.addView(cb, LinearLayout.LayoutParams(dp(22), dp(22)))
        agreeRow.addView(TextView(this).apply {
            text = getString(R.string.guide_agree_pre)
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(dp(8), 0, 0, 0)
        })
        fun markRead(isDisclaimer: Boolean) {
            if (isDisclaimer) {
                disclaimerRead = true
                prefs().edit().putBoolean("disclaimer_read", true).apply()
            } else {
                privacyRead = true
                prefs().edit().putBoolean("privacy_read", true).apply()
            }
            refreshLinks()
            if (disclaimerRead && privacyRead) {
                boxChecked[0] = true
                agreed = true
                refreshBox()
            }
        }
        fun link(tag: String, isDisclaimer: Boolean, open: () -> Unit) = TextView(this).apply {
            text = tag
            textSize = 13f
            setTextColor(Ui.PRIMARY)
            setPadding(dp(8), 0, 0, 0)
            isClickable = true
            setOnClickListener { open() }
        }.also {
            if (isDisclaimer) dLink = it else pLink = it
            agreeRow.addView(it)
        }
        dLink = link(getString(R.string.guide_disclaimer), true) {
            showAgreementDialog(getString(R.string.guide_disclaimer), Agreements.disclaimerText(this)) { markRead(true) }
        }
        pLink = link(getString(R.string.guide_privacy), false) {
            showAgreementDialog(getString(R.string.guide_privacy), Agreements.privacyText(this)) { markRead(false) }
        }
        refreshLinks()
        c.addView(agreeRow)

        // 主按钮：开始授权（深色通栏圆角大按钮）
        val startBtn = TextView(this).apply {
            text = getString(R.string.guide_start)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.rounded(0xFF1A1A1A.toInt(), 16, this@GuideActivity)
            Ui.press(this)
            setOnClickListener {
                if (!boxChecked[0]) {
                    Toast.makeText(this@GuideActivity, getString(R.string.guide_agree_hint), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs().edit().putBoolean("privacy_accepted", true).apply()
                showStep(1)
            }
        }
        startBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        bottom.addView(startBtn)

        // 次按钮：稍后再说（跳过配置与授权，之后可在 设置 中补齐）
        bottom.addView(TextView(this).apply {
            text = getString(R.string.guide_later)
            textSize = 14f
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            setPadding(0, dp(12), 0, 0)
            Ui.press(this)
            setOnClickListener {
                if (!boxChecked[0]) {
                    Toast.makeText(this@GuideActivity, getString(R.string.guide_agree_hint), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                finishGuide()
            }
        })

    }

    // ===================== Step1：选择模型服务商（独立一屏 + 标题栏） =====================
    private fun buildStep1(c: LinearLayout, bottom: LinearLayout) {
        c.addView(TextView(this).apply {
            text = getString(R.string.guide_step_provider_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(0, dp(8), 0, dp(4))
        })
        c.addView(TextView(this).apply {
            text = getString(R.string.guide_step_provider_sub)
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(0, dp(6), 0, dp(6))
        })

        providerList.clear()
        providerCardViews.clear()
        providerTitleViews.clear()
        providerSubViews.clear()
        providerList.addAll(ApiConfig.providers().filter { it.isPreset })
        providerList.forEach { p ->
            c.addView(providerCard(p))
        }

        // 更多服务商入口：弹说明后跳过配置，直达完成与设置
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.rounded(Ui.SURFACE, 12, this@GuideActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, 0)
            }
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                val (dlg, box) = Ui.dialog(this@GuideActivity, getString(R.string.guide_more_provider_desc))
                box.addView(Ui.primaryBtn(this@GuideActivity, getString(R.string.dialog_ok)) {
                    dlg.dismiss()
                    finishGuide()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                })
                box.addView(Ui.dialogCancelBtn(this@GuideActivity, getString(R.string.dialog_cancel)) { dlg.dismiss() }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
                dlg.show()
            }
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_more_provider)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.PRIMARY)
            })
            addView(Space(this@GuideActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(this@GuideActivity).apply {
                text = "›"
                textSize = 18f
                setTextColor(Ui.SUB)
            })
        })

        bottom.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val prevBtn = Ui.lightBtn(this@GuideActivity, getString(R.string.guide_back)) { showStep(0) }
            prevBtn.textSize = 15f
            prevBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            addView(prevBtn)
            addView(Space(this@GuideActivity), LinearLayout.LayoutParams(dp(10), 1))
            val nextBtn = Ui.primaryBtn(this@GuideActivity, getString(R.string.guide_next)) {
                if (selectedProvider == null) {
                    Toast.makeText(this@GuideActivity, getString(R.string.guide_provider_pick_hint), Toast.LENGTH_SHORT).show()
                    return@primaryBtn
                }
                showStep(2)
            }
            nextBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            addView(nextBtn)
        })
    }

    private fun providerCard(p: ApiConfig.Provider): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.rounded(Ui.SURFACE, 12, this@GuideActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, 0)
            }
            val tvTitle = TextView(this@GuideActivity).apply {
                text = p.label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.TEXT)
            }
            val tvSub = TextView(this@GuideActivity).apply {
                text = p.defaultBase
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(0, dp(4), 0, 0)
            }
            addView(tvTitle)
            addView(tvSub)
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                selectedProvider = p
                refreshProviderSel()
            }
            providerCardViews.add(this)
            providerTitleViews.add(tvTitle)
            providerSubViews.add(tvSub)
        }

    private fun refreshProviderSel() {
        providerCardViews.forEachIndexed { i, card ->
            val sel = providerList.getOrNull(i)?.id == selectedProvider?.id
            card.background = Ui.rounded(if (sel) Ui.PRIMARY else Ui.SURFACE, 12, this@GuideActivity)
            providerTitleViews.getOrNull(i)?.setTextColor(if (sel) 0xFFFFFFFF.toInt() else Ui.TEXT)
            providerSubViews.getOrNull(i)?.setTextColor(if (sel) 0xCCFFFFFF.toInt() else Ui.SUB)
        }
    }

    // ===================== Step2：填写 API Key =====================
    private fun buildStep2(c: LinearLayout, bottom: LinearLayout) {
        val p = selectedProvider
        if (p == null) {
            showStep(1)
            return
        }
        c.addView(TextView(this).apply {
            text = getString(R.string.guide_step_key_title, p.label)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(0, dp(8), 0, dp(4))
        })

        keyInput = EditText(this).apply {
            hint = getString(R.string.guide_key_inline_hint)
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.SUB)
            typeface = Typeface.MONOSPACE
            // Key 是单行串: 多行输入会把回车吃掉变成换行, IME 的"完成"就到不了我们手里
            setSingleLine(true)
            background = Ui.rounded(Ui.INPUT_BG, 12, this@GuideActivity)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // 显示明文: Key 需要用户自己核对是否粘全, 打码反而制造"不知道错在哪"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setText(pendingKeys[p.id].orEmpty())
            setSelection(text.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val k = s?.toString()?.trim().orEmpty()
                    if (k == pendingKeys[p.id].orEmpty()) return
                    pendingKeys[p.id] = k
                    // Key 变了, 之前拉到的模型列表就不再可信, 重置模型行等用户重新拉取
                    fetchedModelsMap.remove(p.id)
                    selectedModels.remove(p.id)
                    modelInput?.text = getString(R.string.guide_model_placeholder)
                    modelInput?.setTextColor(Ui.SUB)
                    connTv?.text = ""
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(windowToken, 0)
                    if (pendingKeys[p.id].orEmpty().isNotEmpty()) startFetchModels(p)
                    true
                } else false
            }
        }
        c.addView(keyInput)

        // 模型行：只读显示当前选中模型（默认打分最优）+ 替换按钮（弹单选列表换模型）
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            modelInput = TextView(this@GuideActivity).apply {
                text = selectedModels[p.id] ?: getString(R.string.guide_model_placeholder)
                textSize = 14f
                setTextColor(if (selectedModels.containsKey(p.id)) Ui.TEXT else Ui.SUB)
                background = Ui.rounded(Ui.INPUT_BG, 12, this@GuideActivity)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(modelInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Space(this@GuideActivity), LinearLayout.LayoutParams(dp(8), 1))
            addView(Ui.lightBtn(this@GuideActivity, getString(R.string.guide_replace_model)) { openReplaceModel(p) })
        })

        // ? 说明入口（整行可点）：首次默认展开，之后收起
        c.addView(TextView(this).apply {
            text = getString(R.string.guide_key_hint)
            textSize = 12f
            setTextColor(Ui.PRIMARY)
            setPadding(dp(2), dp(10), dp(2), dp(4))
            isClickable = true
            setOnClickListener { toggleHelp() }
        })
        helpCard = Ui.card(this).apply {
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_key_help_title)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.TEXT)
                setPadding(dp(2), dp(4), dp(2), dp(4))
            })
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_key_help_body)
                textSize = 13f
                setTextColor(Ui.SUB)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            })
        }
        val helpShown = prefs().getBoolean("guide_key_help_shown", false)
        if (!helpShown) {
            prefs().edit().putBoolean("guide_key_help_shown", true).apply()
        } else {
            helpCard?.visibility = View.GONE
        }
        c.addView(helpCard)

        // 官网获取 Key + 测试连接（横排次级按钮）
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_get_key)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Ui.TEXT)
                background = Ui.rounded(Ui.INPUT_BG, 18, this@GuideActivity)
                setPadding(dp(6), dp(11), dp(6), dp(11))
                isClickable = true
                Ui.press(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val url = keyUrlOf(p)
                    if (url.isNotEmpty()) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Toast.makeText(this@GuideActivity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
            addView(Space(this@GuideActivity), LinearLayout.LayoutParams(dp(10), 1))
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_test_conn)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Ui.PRIMARY)
                background = Ui.rounded(Ui.INPUT_BG, 18, this@GuideActivity)
                setPadding(dp(6), dp(11), dp(6), dp(11))
                isClickable = true
                Ui.press(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { testConnection(p) }
            })
        })

        connTv = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(dp(4), dp(8), dp(4), dp(2))
        }
        c.addView(connTv)

        // 底部操作：左「上一步」回选服务商，右「下一步」自动校验连接并保存（同 Step1 布局）
        bottom.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val prevBtn = Ui.lightBtn(this@GuideActivity, getString(R.string.guide_back)) { showStep(1) }
            prevBtn.textSize = 15f
            prevBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            addView(prevBtn)
            addView(Space(this@GuideActivity), LinearLayout.LayoutParams(dp(10), 1))
            val nextBtn = Ui.primaryBtn(this@GuideActivity, getString(R.string.guide_next)) {
                val key = pendingKeys[p.id].orEmpty()
                if (key.isEmpty()) {
                    Toast.makeText(this@GuideActivity, getString(R.string.guide_key_empty_hint), Toast.LENGTH_SHORT).show()
                    return@primaryBtn
                }
                doneWithCheck(p, key)
            }
            nextBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            addView(nextBtn)
        })

        // 稍后配置：跳过 Key，直接进权限页
        bottom.addView(TextView(this).apply {
            text = getString(R.string.guide_skip_config)
            textSize = 14f
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            setPadding(0, dp(12), 0, 0)
            Ui.press(this)
            setOnClickListener { finishGuide() }
        })
    }

    private fun toggleHelp() {
        val hc = helpCard ?: return
        hc.visibility = if (hc.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 各家服务商官网的 Key 获取地址 */
    private fun keyUrlOf(p: ApiConfig.Provider): String = when (p.id) {
        "deepseek" -> "https://platform.deepseek.com/api_keys"
        "zhipu" -> "https://open.bigmodel.cn/usercenter/apikeys"
        "mimo" -> "https://mimo.xiaomi.com/"
        else -> ""
    }

    /** 测试连接：GET {base}/models，5s 超时 */
    private fun testConnection(p: ApiConfig.Provider) {
        val key = pendingKeys[p.id].orEmpty()
        if (key.isEmpty()) {
            Toast.makeText(this, getString(R.string.guide_key_empty_hint), Toast.LENGTH_SHORT).show()
            return
        }
        connTv?.setTextColor(Ui.SUB)
        connTv?.text = getString(R.string.guide_testing)
        Thread {
            var ok = false
            var msg = ""
            try {
                val url = URL(p.defaultBase.trimEnd('/') + "/models")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                when (p.authType) {
                    "x-api-key" -> conn.setRequestProperty("x-api-key", key)
                    "header" -> conn.setRequestProperty(p.authHeader.ifBlank { "Authorization" }, key)
                    else -> conn.setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                ok = code in 200..299
                if (!ok) {
                    val err = conn.errorStream?.bufferedReader()?.readText()?.take(200)
                        ?: "HTTP $code"
                    msg = err
                }
                conn.disconnect()
            } catch (e: Exception) {
                msg = e.message ?: e.toString()
            }
            runOnUiThread {
                if (ok) {
                    connTv?.setTextColor(Ui.OK)
                    connTv?.text = getString(R.string.guide_test_ok)
                } else {
                    connTv?.setTextColor(Ui.DANGER)
                    connTv?.text = getString(R.string.guide_test_fail) + " " + msg
                }
            }
        }.start()
    }

    /** 完成：先自动校验连接；成功=写入拉取到的模型列表并完成；失败=弹「连接异常，是否继续？」 */
    private fun doneWithCheck(p: ApiConfig.Provider, key: String) {
        connTv?.setTextColor(Ui.SUB)
        connTv?.text = getString(R.string.guide_testing)
        Thread {
            val models = fetchModelsFromNetwork(p, key)
            runOnUiThread {
                if (models != null && models.isNotEmpty()) {
                    connTv?.setTextColor(Ui.OK)
                    connTv?.text = getString(R.string.guide_test_ok)
                    saveAndFinish(p, key, models)
                } else {
                    showContinueDialog(p, key)
                }
            }
        }.start()
    }

    /** 连接成功：只落库当前选中模型（打分最优或用户替换），不带多余模型 */
    private fun saveAndFinish(p: ApiConfig.Provider, key: String, models: List<String>) {
        val model = selectedModels[p.id] ?: models.firstOrNull() ?: p.defaultModel
        ApiConfig.saveProvider(p.id, p.defaultBase, key, model, true, models = listOf(model))
        finishGuide()
    }

    /** 连接失败：弹窗确认是否继续；继续则按当前输入强行保存（models 兜底默认模型） */
    private fun showContinueDialog(p: ApiConfig.Provider, key: String) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.guide_conn_error_confirm))
        box.addView(Ui.primaryBtn(this, getString(R.string.dialog_ok)) {
            dlg.dismiss()
            val model = selectedModels[p.id] ?: p.defaultModel
            ApiConfig.saveProvider(p.id, p.defaultBase, key, model, true, models = listOf(model))
            finishGuide()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    /** 确认 Key 后自动拉取模型列表并打分选最优，更新模型行显示 */
    private fun startFetchModels(p: ApiConfig.Provider) {
        val key = pendingKeys[p.id].orEmpty()
        if (key.isEmpty()) {
            Toast.makeText(this, getString(R.string.guide_key_empty_hint), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.toast_fetching_models), Toast.LENGTH_SHORT).show()
        Thread {
            val models = fetchModelsFromNetwork(p, key)
            runOnUiThread {
                if (models == null || models.isEmpty()) {
                    connTv?.setTextColor(Ui.DANGER)
                    connTv?.text = getString(R.string.toast_fetch_fail)
                    Toast.makeText(this, getString(R.string.toast_fetch_fail), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                fetchedModelsMap[p.id] = models
                val best = pickBestModel(models, p.defaultModel)
                selectedModels[p.id] = best
                modelInput?.text = best
                modelInput?.setTextColor(Ui.TEXT)
                connTv?.setTextColor(Ui.OK)
                connTv?.text = getString(R.string.guide_test_ok) + "，已自动选择最优模型"
            }
        }.start()
    }

    /** 替换模型：有实时列表直接弹单选；没有先拉取 */
    private fun openReplaceModel(p: ApiConfig.Provider) {
        if (pendingKeys[p.id].orEmpty().isEmpty()) {
            Toast.makeText(this, getString(R.string.guide_key_empty_hint), Toast.LENGTH_SHORT).show()
            return
        }
        val cached = fetchedModelsMap[p.id]
        if (!cached.isNullOrEmpty()) {
            showGuideModelPicker(cached)
            return
        }
        Toast.makeText(this, getString(R.string.toast_fetching_models), Toast.LENGTH_SHORT).show()
        Thread {
            val models = fetchModelsFromNetwork(p, pendingKeys[p.id].orEmpty())
            runOnUiThread {
                if (models == null || models.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_fetch_fail), Toast.LENGTH_SHORT).show()
                } else {
                    fetchedModelsMap[p.id] = models
                    showGuideModelPicker(models)
                }
            }
        }.start()
    }

    /** 打分选最优：后缀词加权，pro/max 旗舰优先、flash/lite 垫底；同分优先内置默认 */
    private fun pickBestModel(models: List<String>, preferred: String): String {
        fun score(m: String): Int {
            val s = m.lowercase()
            var sc = 0
            // 供应商特例(2026-10-18): 该供应商新版 flash 已全面超越 pro 且把 pro 路由到 flash, 引导页须默认选 flash
            if (s.contains("deepseek") && s.contains("flash")) return 10
            if (listOf("pro", "max", "plus", "ultra", "premium", "reasoner", "thinking", "think", "r1", "o1").any { s.contains(it) }) sc += 3
            if (listOf("vision", "vl", "omni", "multimodal").any { s.contains(it) }) sc += 1
            if (Regex("v?[0-9]+").containsMatchIn(s)) sc += 1
            if (listOf("flash", "lite", "mini", "small", "base", "chat", "instruct", "fast").any { s.contains(it) }) sc -= 1
            return sc
        }
        var best = models.firstOrNull() ?: preferred
        var bestScore = Int.MIN_VALUE
        for (m in models) {
            val sc = score(m)
            if (sc > bestScore) {
                bestScore = sc
                best = m
            }
        }
        if (preferred in models && score(preferred) >= bestScore) best = preferred
        return best
    }

    /** OpenAI 兼容 GET /models，解析 data[].id；失败返回 null */
    private fun fetchModelsFromNetwork(p: ApiConfig.Provider, key: String): List<String>? {
        return try {
        val url = URL(p.defaultBase.trimEnd('/') + "/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        when (p.authType) {
            "x-api-key" -> conn.setRequestProperty("x-api-key", key)
            "header" -> conn.setRequestProperty(p.authHeader.ifBlank { "Authorization" }, key)
            else -> conn.setRequestProperty("Authorization", "Bearer $key")
        }
        val code = conn.responseCode
        val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
        conn.disconnect()
        if (body == null) return null
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
            if (id.isNotEmpty()) list.add(id)
        }
            list
        } catch (e: Exception) {
            null
        }
    }

    /** 模型替换单选弹窗：点某行立即选中替换；取消则保持原选择 */
    private fun showGuideModelPicker(models: List<String>) {
        val current = selectedModels[selectedProvider?.id] ?: ""
        val (dlg, box) = Ui.dialog(this, getString(R.string.guide_pick_one_model))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        models.forEach { m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                background = Ui.roundedBorder(if (m == current) Ui.PRIMARY else Ui.DIVIDER, 12, 1, this@GuideActivity)
                setOnClickListener {
                    dlg.dismiss()
                    selectedModels[selectedProvider?.id.orEmpty()] = m
                    modelInput?.text = m
                    modelInput?.setTextColor(Ui.TEXT)
                    Toast.makeText(this@GuideActivity, "已选择 $m", Toast.LENGTH_SHORT).show()
                }
            }
            Ui.press(row)
            row.addView(TextView(this).apply {
                text = m
                textSize = 14f
                setTextColor(Ui.TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            listBox.addView(row)
        }
        box.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    /** 结束配置：标记已完成，进入第4步「完成与设置」（授权 + 模式选择） */
    private fun finishGuide() {
        step3SourceStep = step
        prefs().edit()
            .putBoolean("privacy_accepted", true)
            .putBoolean("guide_done", true)
            .apply()
        showStep(3)
    }

    // ===================== Step3：完成与设置（权限 + 模式二选一） =====================
    private fun buildStep3(c: LinearLayout, bottom: LinearLayout) {
        // 引导语
        c.addView(TextView(this).apply {
            text = getString(R.string.guide_perm_intro)
            textSize = 13f
            setTextColor(Ui.SUB)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), dp(4), dp(4), dp(12))
        })
        // 12 项里有 4 项是厂商私有开关, 没有可读状态, 先说清楚"手动确认"是什么意思, 免得起疑
        c.addView(TextView(this).apply {
            text = getString(R.string.perm_50)
            textSize = 11f
            setTextColor(Ui.SUB)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        // 权限列表卡
        val permCard = Ui.card(this)
        permCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }.also { box -> listBox = box })
        c.addView(permCard)

        // 模式已在 Step0 选定, 这里不再重复一套选择器, 只提示去哪改
        c.addView(TextView(this).apply {
            text = if (ModeConfig.chatMode()) getString(R.string.guide_mode_chat_t) else getString(R.string.guide_mode_agent_t)
            textSize = 11f
            setTextColor(Ui.SUB)
            setPadding(dp(4), dp(14), dp(4), dp(4))
        })

        // 底部一分为二：返回(小比率, 回 Step2 填 Key) + 进入 APP(大比率)
        bottom.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val backBtn = Ui.lightBtn(this@GuideActivity, getString(R.string.guide_back)) { showStep(step3SourceStep) }
            backBtn.textSize = 15f
            backBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            addView(backBtn)
            addView(Space(this@GuideActivity), LinearLayout.LayoutParams(dp(10), 1))
            val enterBtn = Ui.primaryBtn(this@GuideActivity, getString(R.string.guide_perm_enter)) {
                enterApp(skip = false)
            }
            enterBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 2f)
            addView(enterBtn)
        })

        // 跳过：暂不授权，之后在 设置→权限管理 补开
        bottom.addView(TextView(this).apply {
            text = getString(R.string.guide_perm_skip)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            Ui.press(this)
            setOnClickListener { enterApp(skip = true) }
        })

        refreshPerms()
    }

    /** 后台实时探测 root 授权(不依赖进程级缓存, 用户去授权工具回来后能即时刷新);
     *  首次探测到已授权时用 root 静默补齐自身权限(尽力而为, 失败保持手动入口) */
    private fun probeRoot() {
        Thread {
            // 先探测是否存在 root 环境, 再执行 su -c id: 未 root 的设备不必白等一次超时
            val rooted = RootCheck.deviceRooted()
            val g = rooted && RootCheck.isGranted()
            if (g && !rootAutoGranted) {
                rootAutoGranted = true
                RootCheck.grantSelf(this)
            }
            val act = this@GuideActivity
            act.runOnUiThread {
                deviceRooted = rooted
                rootGranted = if (g) 1 else 0
                refreshPerms()
            }
        }.start()
    }

    private fun enterApp(skip: Boolean) {
        // 无论是否跳过都标记 first_run_perms_done, 避免进主界面后 MainActivity 旧强制引导再轰炸权限;
        // 未授权的权限(通知/麦克风/悬浮窗/文件/未知来源)改走功能按需请求。
        prefs().edit().putBoolean("first_run_perms_done", true).apply()
        goMain(finishSelf = true)
    }

    private fun items(): List<PermSpec> = PermCatalog.build(this, rootGranted, deviceRooted)

    private fun refreshPerms() {
        val box = listBox ?: return
        box.removeAllViews()
        val all = items()
        var firstGroup = true
        listOf(PermSpec.GROUP_BASE, PermSpec.GROUP_TIMER, PermSpec.GROUP_ADV).forEach { g ->
            val group = all.filter { it.group == g }
            if (group.isEmpty()) return@forEach
            if (!firstGroup) box.addView(Ui.divider(this))
            firstGroup = false
            // 小计只统计"有 API 可读状态"的必需项, manual 项永远不计入分母
            val groupTotal = group.count { it.required() && !it.manual }
            val groupDone = group.count {
                it.required() && !it.manual && it.granted()
            }
            val collapsed = g in collapsedGroups
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(8))
                isClickable = true
                Ui.press(this)
                setOnClickListener {
                    if (!collapsedGroups.add(g)) collapsedGroups.remove(g)
                    refreshPerms()
                }
                addView(TextView(this@GuideActivity).apply {
                    text = PermCatalog.groupTitle(this@GuideActivity, g) +
                        if (groupTotal > 0) " · $groupDone/$groupTotal" else ""
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@GuideActivity).apply {
                    text = if (collapsed) "▸" else "▾"
                    textSize = 13f
                    setTextColor(Ui.SUB)
                    setPadding(dp(8), 0, dp(2), 0)
                })
            })
            if (collapsed) return@forEach
            group.forEachIndexed { i, item ->
                if (i > 0) box.addView(Ui.divider(this, 12))
                box.addView(buildPermRow(item))
            }
        }
    }

    private fun buildPermRow(item: PermSpec): LinearLayout {
        val required = item.required()
        val granted = item.granted()
        val detecting = item.isRoot && rootGranted == -1
        // manual 项(自启动/后台运行/锁屏显示/后台弹窗)没有可读状态, 它本身就是跳转入口, 恒可点
        val clickable = item.manual || (required && !detecting && !granted)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            if (clickable) {
                isClickable = true
                setOnClickListener { item.request(this@GuideActivity) }
                Ui.press(this)
            }
            addView(ImageView(this@GuideActivity).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(if (granted) Ui.OK else Ui.TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(10), 0, 0, 0)
                addView(TextView(this@GuideActivity).apply {
                    text = item.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ui.TEXT)
                })
                addView(TextView(this@GuideActivity).apply {
                    text = item.desc
                    textSize = 11f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            // 右侧状态/按钮：手动项=中性描边胶囊；不需要=灰字；已授权=绿色"已完成"；未授权=主色"去授权"
            addView(TextView(this@GuideActivity).apply {
                textSize = 13f
                when {
                    item.manual -> {
                        text = getString(R.string.perm_40)
                        setTextColor(Ui.SUB)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), Ui.SUB)
                        }
                    }
                    !required -> {
                        text = getString(R.string.perm_17)
                        setTextColor(Ui.SUB)
                    }
                    detecting -> {
                        text = getString(R.string.perm_26)
                        setTextColor(Ui.SUB)
                    }
                    granted -> {
                        text = getString(R.string.guide_perm_done)
                        setTextColor(Ui.OK)
                    }
                    else -> {
                        text = getString(R.string.guide_perm_go)
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(Ui.PRIMARY)
                        }
                    }
                }
            })
        }
    }

    /** Root 未授权提示: 不内置各家授权框架包名, 不跳转, 引导用户去系统授权工具手动授权 */

    private fun backTextRow(label: String, onBack: () -> Unit): TextView =
        TextView(this).apply {
            text = "‹ " + label
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, dp(2), 0, dp(2))
            isClickable = true
            Ui.press(this)
            setOnClickListener { onBack() }
        }

    private fun modeCard(
        iconRes: Int,
        title: String,
        desc: String,
        points: List<Triple<Int, String, String>>,
        checked: Boolean = false,
        onPick: (() -> Unit)? = null
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = dp(16).toFloat()
                if (checked) setStroke(dp(2), Ui.PRIMARY) else setStroke(dp(1), Ui.STROKE)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(14), dp(20), dp(14), dp(20))
            if (onPick != null) {
                isClickable = true
                contentDescription = if (checked) "已选择 $title" else "选择 $title"
                Ui.press(this)
                setOnClickListener { onPick() }
            }
            addView(ImageView(this@GuideActivity).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(if (checked) Ui.PRIMARY else Ui.TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            addView(TextView(this@GuideActivity).apply {
                text = if (checked) "✓ $title" else title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (checked) Ui.PRIMARY else Ui.TEXT)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@GuideActivity).apply {
                text = desc
                textSize = 11f
                maxLines = 1
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, dp(4), 0, 0)
            })
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(16), 0, 0)
                }
                points.forEach { (pIcon, ptTitle, ptDesc) ->
                    addView(LinearLayout(this@GuideActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                        setPadding(0, 0, 0, 0)
                        addView(ImageView(this@GuideActivity).apply {
                            setImageResource(pIcon)
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setColorFilter(Ui.SUB)
                            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        })
                        addView(LinearLayout(this@GuideActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                setMargins(dp(7), 0, 0, 0)
                            }
                            addView(TextView(this@GuideActivity).apply {
                                text = ptTitle
                                textSize = 12f
                                maxLines = 1
                                setTextColor(Ui.SUB)
                            })
                            addView(TextView(this@GuideActivity).apply {
                                text = ptDesc
                                textSize = 10f
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setTextColor(0xFFB0B0B6.toInt())
                            })
                        })
                    })
                }
            })
        }

    /** 协议全文阅读弹窗：可滚动，滑到底部后"我已阅读并同意"才可点击 */
    private fun showAgreementDialog(title: String, body: String, onAgree: () -> Unit) {
        val dlg = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.setCancelable(true)
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
        Ui.blurBehind(this, dlg)
        dlg.show()
    }

    private fun goMain(finishSelf: Boolean) {
        startActivity(Intent(this, MainActivity::class.java))
        if (finishSelf) finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val REQ_RUNTIME = 2001
    }
}
