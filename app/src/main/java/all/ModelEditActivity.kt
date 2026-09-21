package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import io.github.aixtin.nyral.R
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.ceil


/**
 * 单个模型 Provider 编辑页 — 与主页统一视觉（灰底 + 白色圆角卡片 + 圆角输入框）
 * - 编辑已有：预填当前配置，可改 base/key/model
 * - 新增：自定义 label + base/key/model，保存即加入列表并设为当前
 * - 自定义 Provider 支持删除
 */
class ModelEditActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_IS_NEW = "is_new"
    }

    private lateinit var labelInput: EditText
    private lateinit var baseInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText

    private var providerId: String? = null
    private var isNew = false
    /** 当前已选子模型集合（多选结果）；保存时写入该供应商 models */
    private val selectedModels = mutableListOf<String>()
    /** 思考强度档位（六档 THINK_*） */
    private var selectedEffort: String = ApiConfig.THINK_AUTO
    /** 模型能力勾选（CAP_*；预设只读） */
    private val selectedCaps = mutableSetOf<String>()
    /** 模型级能力：子模型名 → 能力集合（手动模型逐模型独立勾选；默认仅文本） */
    private val selectedModelCaps = mutableMapOf<String, MutableSet<String>>()
    /** 鉴权方式：bearer / x-api-key / header */
    private var selectedAuthType: String = "bearer"
    /** 自定义鉴权请求头名（authType=header 时生效） */
    private var selectedAuthHeader: String = "Authorization"
    private lateinit var authTypeContainer: LinearLayout
    private lateinit var authHeaderInput: EditText
    private lateinit var saveBtn: TextView
    private lateinit var modelsContainer: LinearLayout
    private lateinit var effortContainer: LinearLayout
    private lateinit var capsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        Ui.statusBar(this)

        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)
        val p = providerId?.let { ApiConfig.providerById(it) }
        if (p != null) selectedModels.addAll(p.models)
        selectedEffort = if (isNew) ApiConfig.THINK_AUTO else (p?.thinkingEffort ?: ApiConfig.THINK_AUTO)
        selectedAuthType = if (isNew) "bearer" else (p?.authType ?: "bearer")
        selectedAuthHeader = if (isNew) "Authorization" else (p?.authHeader ?: "Authorization")
        if (isNew) {
            // 新模型默认: 文本+工具（OpenAI 兼容接口大多支持工具）；图片/视频/音频按需勾选
            selectedCaps.addAll(setOf(ApiConfig.CAP_TEXT, ApiConfig.CAP_TOOL))
        } else if (p != null) {
            selectedCaps.addAll(if (p.capabilities.isEmpty()) ApiConfig.modelCapabilities(p.id, p.defaultModel) else p.capabilities)
        }
        // 模型级能力初始化：读取已存配置，缺失模型默认仅文本
        if (p != null) {
            p.modelCaps.forEach { (m, caps) -> selectedModelCaps[m] = caps.toMutableSet() }
            selectedModels.forEach { m ->
                selectedModelCaps.getOrPut(m) { mutableSetOf(ApiConfig.CAP_TEXT) }
            }
        }

        val root = Ui.pageRoot(this)

        // ---- 自绘标题栏 + 右上角"保存" ----
        root.addView(Ui.titleBar(this, if (isNew) getString(R.string.title_model_add) else (p?.label ?: getString(R.string.title_model_edit)), right = { bar ->
            bar.addView(TextView(this).apply {
                text = getString(R.string.menu_save)
                textSize = 15f
                setTextColor(Ui.PRIMARY)
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener { showSaveMenu() }
                Ui.press(this)
            }.also { saveBtn = it })
        }))

        // ---- 内容区：白色圆角卡片包裹表单 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val card = Ui.card(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // 名称（新建与自定义可编辑；仅预设只读）
        card.addView(Ui.fieldLabel(this, getString(R.string.label_name)))
        labelInput = Ui.input(this, getString(R.string.hint_name_example)).apply {
            isEnabled = isNew || p?.isPreset == false
            setText(if (isNew) "" else (p?.label ?: ""))
        }
        card.addView(labelInput)
        if (p?.isPreset == true && !isNew) {
            card.addView(Ui.hint(this, getString(R.string.hint_preset_name_locked)))
        }

        // Base URL
        card.addView(Ui.fieldLabel(this, "Base URL"))
        baseInput = Ui.input(this, "https://api.example.com/v1").apply {
            setText(if (isNew) "" else (p?.defaultBase ?: ""))
        }
        card.addView(baseInput)

        // API Key
        card.addView(Ui.fieldLabel(this, "API Key"))
        keyInput = Ui.input(this, "sk-...").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(if (isNew) "" else (p?.key ?: ""))
        }
        card.addView(keyInput)

        // 鉴权方式（Bearer / x-api-key / 自定义 Header）
        card.addView(Ui.fieldLabel(this, getString(R.string.label_auth_type)))
        authTypeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        card.addView(authTypeContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        renderAuthTypeSelector()
        authHeaderInput = Ui.input(this, getString(R.string.hint_auth_header_example)).apply {
            setText(selectedAuthHeader)
        }
        card.addView(authHeaderInput)
        card.addView(Ui.hint(this, getString(R.string.hint_auth_type_desc)))

        // 模型名 + 联网拉取列表
        card.addView(Ui.fieldLabel(this, getString(R.string.label_model_name)))
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        modelInput = Ui.input(this, getString(R.string.hint_model_input)).apply {
            setText("") // 不预填默认模型, 由用户显式选择
        }
        modelRow.addView(modelInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modelRow.addView(Ui.lightBtn(this, getString(R.string.btn_fetch)) { fetchModels() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        modelRow.addView(Ui.lightBtn(this, "＋") {
            val name = modelInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this@ModelEditActivity, getString(R.string.toast_fill_model_name), Toast.LENGTH_SHORT).show()
                return@lightBtn
            }
            if (selectedModels.contains(name)) {
                Toast.makeText(this@ModelEditActivity, getString(R.string.toast_model_already_exists), Toast.LENGTH_SHORT).show()
                return@lightBtn
            }
            selectedModels.add(name)
            renderSelectedModels()
            modelInput.setText("")
            Toast.makeText(this@ModelEditActivity, getString(R.string.toast_model_added, name), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        card.addView(modelRow)
        card.addView(Ui.hint(this, getString(R.string.hint_fetch_desc)))

        // 已选子模型列表（多选结果展示 + 可移除）
        card.addView(Ui.fieldLabel(this, getString(R.string.label_models)))
        modelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        card.addView(modelsContainer)
        renderSelectedModels()

        // 思考强度（六档 chips）
        card.addView(Ui.fieldLabel(this, getString(R.string.label_effort)))
        effortContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        card.addView(effortContainer)
        renderEffortSelector()
        card.addView(Ui.hint(this, getString(R.string.hint_effort_desc)))

        // 模型能力（预设只读展示；自定义可勾选）
        card.addView(Ui.fieldLabel(this, getString(R.string.label_caps)))
        capsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        card.addView(capsContainer)
        renderCapSelector()
        card.addView(Ui.hint(this, if (p?.isPreset == true && !isNew) getString(R.string.hint_caps_preset_locked) else getString(R.string.hint_caps_desc)))

        // 删除（仅自定义）
        if (!isNew && p?.isPreset == false) {
            card.addView(Ui.dangerBtn(this, getString(R.string.btn_delete_model)) { delete() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        content.addView(card)
        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    /** 连通性测试结果: canSave=true 表示可保存(通过或放行), message 为提示/警告 */
    private class TestResult(val canSave: Boolean, val message: String)

    /** 保存前连通性测试: 向 {base}/chat/completions 发最小请求.
     *  配置错误(401/403/404/400) -> canSave=false 阻止保存;
     *  余额不足(402/insufficient/balance) -> canSave=true 放行但提示;
     *  网络异常/超时 -> canSave=true 放行但提示无法验证. */
    private fun testConnectivity(base: String, key: String, model: String): TestResult {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "hi")
            }))
            put("max_tokens", 1)
            put("stream", false)
        }
        val result = ApiClient.postJson(
            "$base/chat/completions", body.toString(),
            headers = authHeaders(key),
            connectMs = 10_000, readMs = 20_000,
        )
        return when {
            result.isSuccess -> TestResult(true, "")
            else -> {
                val e = result.exceptionOrNull()
                val h = e as? ApiClient.HttpError
                val lower = (h?.body ?: "").lowercase()
                val insufficient = h != null && (h.code == 402 || lower.contains("insufficient") ||
                    lower.contains("balance") || lower.contains("余额") || lower.contains("欠费"))
                when {
                    insufficient -> TestResult(true, getString(R.string.toast_balance_insufficient))
                    h != null -> TestResult(false, "HTTP ${h.code}: ${h.body.take(150)}")
                    else -> TestResult(true, getString(R.string.toast_net_unverified, e?.message ?: getString(R.string.net_error)))
                }
            }
        }
    }

    /** 保存前先测试连通性, 通过才落库 */
    private fun saveWithTest(setAsCurrent: Boolean, successMsg: String) {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        // 模型名未填但已选子模型时, 用第一个已选子模型(用户显式配置, 非默认值)
        val model = modelInput.text.toString().trim().ifEmpty { selectedModels.firstOrNull() ?: "" }
        if (base.isEmpty() || model.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_fill_base_and_model), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.toast_testing), Toast.LENGTH_SHORT).show()
        Thread {
            val res = testConnectivity(base, key, model)
            uiScope.launch {
                if (!res.canSave) {
                    Toast.makeText(this@ModelEditActivity, getString(R.string.toast_test_fail, res.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val id = doSave(setAsCurrent) ?: return@launch
                val tip = if (res.message.isNotEmpty()) "$successMsg。${res.message}" else successMsg
                Toast.makeText(this@ModelEditActivity, tip, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun saveAndApply() {
        saveWithTest(true, getString(R.string.toast_saved_and_current))
    }

    /** 仅保存(不切换当前): 同样测试连通性, 避免存下不可用的配置 */
    private fun saveOnly() {
        saveWithTest(false, getString(R.string.toast_saved))
    }

    /** 右上角"保存"下拉弹窗（仿长期记忆 PopupWindow 样式，锚定按钮从右上下拉，圆角卡片） */
    private var savePopup: PopupWindow? = null

    /** 右上角"保存"下拉弹窗：PopupWindow 锚定保存按钮下方右对齐下拉（替代系统 PopupMenu 直角样式） */
    private fun showSaveMenu() {
        if (savePopup?.isShowing == true) {
            dismissSaveMenu()
            return
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = Ui.rounded(Ui.SURFACE, 14, this@ModelEditActivity)
            elevation = dp(10).toFloat()
        }
        fun item(text: String, onClick: () -> Unit) {
            col.addView(TextView(this@ModelEditActivity).apply {
                this.text = text
                textSize = 15f
                setTextColor(Ui.TEXT)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                isClickable = true
                setOnClickListener { dismissSaveMenu(); onClick() }
                Ui.press(this)
            })
        }
        val labelApply = getString(R.string.menu_save_apply)
        val labelOnly = if (isNew) getString(R.string.menu_add_only) else getString(R.string.menu_save_only)
        item(labelApply) { saveAndApply() }
        col.addView(Ui.divider(this))
        item(labelOnly) { saveOnly() }

        // 弹窗宽度：按文本实测宽度 + 左右 padding 贴合内容。
        // 不用 WRAP_CONTENT：本设备 PopupWindow 的 wrap 测量会把内容拉成全屏宽。
        val probe = TextView(this).apply { textSize = 15f }
        val textW = ceil(maxOf(probe.paint.measureText(labelApply), probe.paint.measureText(labelOnly))).toInt()
        val popW = textW + dp(52)
        val popH = dp(120)
        savePopup = PopupWindow(col, popW, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(10).toFloat()
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable())
            setTouchInterceptor { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    val p = IntArray(2)
                    saveBtn.getLocationOnScreen(p)
                    val inBtn = e.rawX >= p[0] && e.rawX <= p[0] + saveBtn.width &&
                            e.rawY >= p[1] && e.rawY <= p[1] + saveBtn.height
                    if (!inBtn) dismissSaveMenu()
                }
                false
            }
        }
        // 右对齐（xoff 使弹窗右缘对齐按钮右缘）；向下下拉，底部空间不足则自动上翻
        val xoff = saveBtn.width - popW
        val loc = IntArray(2)
        saveBtn.getLocationOnScreen(loc)
        val below = resources.displayMetrics.heightPixels - (loc[1] + saveBtn.height)
        val yoff = if (below < popH + dp(8)) -(popH + saveBtn.height + dp(6)) else dp(6)
        savePopup!!.showAsDropDown(saveBtn, xoff, yoff)
    }

    private fun dismissSaveMenu() {
        val p = savePopup ?: return
        p.dismiss()
        savePopup = null
    }

    /** 渲染已选子模型列表：每个模型 = 模型名 + ✕ 移除 + 能力勾选/标注
     *  手动模型: 每行下可独立勾选能力（文本固定，图片/视频/音频/工具调用独立开关）
     *  预设模型: 每行下只读标注能力（查内置表） */
    private fun renderSelectedModels() {
        modelsContainer.removeAllViews()
        if (selectedModels.isEmpty()) {
            modelsContainer.addView(TextView(this).apply {
                text = getString(R.string.hint_no_submodel)
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(dp(2), dp(6), 0, dp(6))
            })
            return
        }
        val p = providerId?.let { ApiConfig.providerById(it) }
        val manual = isNew || p?.isPreset != true
        selectedModels.forEach { m ->
            // 描边框卡片：模型名 + 能力 chips 整体包进卡片，✕ 放卡片右侧垂直居中
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = Ui.roundedBorder(Ui.DIVIDER, 12, 1, this@ModelEditActivity)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            }
            // 左侧内容容器（模型名行 + 能力 chips 行）
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            body.addView(TextView(this).apply {
                text = m
                textSize = 13f
                setTextColor(Ui.TEXT)
            })
            // 能力行：手动可勾选，预设只读标注
            val capsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(0), dp(4), dp(0), dp(0))
            }
            val modelCaps: Set<String> = if (manual) {
                selectedModelCaps.getOrPut(m) { mutableSetOf(ApiConfig.CAP_TEXT) }
            } else {
                ApiConfig.modelCapabilities(providerId ?: "", m)
            }
            val allCaps = listOf(ApiConfig.CAP_TEXT, ApiConfig.CAP_IMAGE, ApiConfig.CAP_VIDEO, ApiConfig.CAP_AUDIO, ApiConfig.CAP_TOOL)
            allCaps.forEach { cap ->
                val label = ApiConfig.capLabel(this, cap)
                val selected = cap in modelCaps
                val chip = TextView(this).apply {
                    text = label
                    textSize = 11f
                    setTextColor(if (selected) 0xFFFFFFFF.toInt() else Ui.PRIMARY)
                    background = Ui.rounded(if (selected) Ui.PRIMARY else Ui.INPUT_BG, 14, this@ModelEditActivity)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    isClickable = manual && cap != ApiConfig.CAP_TEXT
                    setOnClickListener {
                        if (!manual || cap == ApiConfig.CAP_TEXT) return@setOnClickListener
                        val caps = selectedModelCaps.getOrPut(m) { mutableSetOf(ApiConfig.CAP_TEXT) }
                        if (selected) caps.remove(cap) else caps.add(cap)
                        renderSelectedModels()
                    }
                    Ui.press(this)
                }
                capsRow.addView(chip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(6)
                })
            }
            body.addView(capsRow)
            card.addView(body)
            // 右侧 ✕：相对整卡垂直居中
            card.addView(android.widget.ImageView(this).apply {
                setImageDrawable(Ui.lucideTrash(this@ModelEditActivity, 0xFFE53935.toInt(), 16))
                setPadding(dp(8), dp(4), dp(16), dp(4))
                setOnClickListener {
                    selectedModels.remove(m)
                    selectedModelCaps.remove(m)
                    renderSelectedModels()
                }
                Ui.press(this)
            })
            modelsContainer.addView(card)
        }
    }

    /** 渲染思考强度档位 chips（手动模型=全量 7 档，不校验实际支持；预设模型=按能力动态出档） */
    private fun renderEffortSelector() {
        effortContainer.removeAllViews()
        val id = providerId ?: ""
        val model = modelInput.text.toString().trim().ifEmpty { selectedModels.firstOrNull() ?: "" }
        val p = providerId?.let { ApiConfig.providerById(it) }
        val manual = isNew || p?.isPreset != true
        // 手动模型：自动/关闭/开启/低/中/高/超高 全给，先不管实际有没有用
        val levels = if (manual) ApiConfig.THINK_LEVEL_KEYS else ApiConfig.thinkingLevelsOf(id, model)
        // 已保存档位不在当前可用列表时（如切换模型后）回退高亮"自动"，避免无选中
        val eff = if (levels.any { it == selectedEffort }) selectedEffort else ApiConfig.THINK_AUTO
        levels.forEach { key ->
            val selected = key == eff
            val chip = TextView(this).apply {
                text = ApiConfig.thinkLabel(context, key)
                textSize = 12f
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else Ui.PRIMARY)
                background = Ui.rounded(if (selected) Ui.PRIMARY else Ui.INPUT_BG, 16, this@ModelEditActivity)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isClickable = true
                setOnClickListener {
                    selectedEffort = key
                    renderEffortSelector()
                }
            }
            effortContainer.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
    }

    /** 渲染鉴权方式 chips：bearer / x-api-key / 自定义 Header */
    private fun renderAuthTypeSelector() {
        authTypeContainer.removeAllViews()
        val types = listOf(
            "bearer" to "Bearer",
            "x-api-key" to "x-api-key",
            "header" to getString(R.string.auth_header_custom)
        )
        types.forEach { (key, name) ->
            val selected = key == selectedAuthType
            val chip = TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else Ui.PRIMARY)
                background = Ui.rounded(if (selected) Ui.PRIMARY else Ui.INPUT_BG, 16, this@ModelEditActivity)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isClickable = true
                setOnClickListener {
                    selectedAuthType = key
                    renderAuthTypeSelector()
                }
                Ui.press(this)
            }
            authTypeContainer.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
    }

    /** 按当前认证类型构造鉴权头（供 ApiClient 统一请求使用） */
    private fun authHeaders(key: String): Map<String, String> = when (selectedAuthType) {
        "x-api-key" -> mapOf("x-api-key" to key)
        "header" -> mapOf(selectedAuthHeader.ifBlank { "Authorization" } to key)
        else -> mapOf("Authorization" to "Bearer $key")
    }

    /** 渲染模型能力 chips：预设=只读徽标；自定义=可勾选（文本必选固定） */
    private fun renderCapSelector() {
        capsContainer.removeAllViews()
        val p = providerId?.let { ApiConfig.providerById(it) }
        val presetLocked = !isNew && p?.isPreset == true
        if (!presetLocked) {
            // 手动模型：能力按每个模型独立勾选，见下方「模型列表」
            capsContainer.addView(TextView(this).apply {
                text = getString(R.string.hint_caps_per_model)
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            })
            return
        }
        val caps: List<String> = ApiConfig.modelCapabilities(providerId ?: "", modelInput.text.toString().trim()).toList()
        caps.forEach { cap ->
            val label = ApiConfig.capLabel(this, cap)
            val selected = cap in selectedCaps
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(
                    if (selected && !presetLocked) 0xFFFFFFFF.toInt()
                    else if (selected) Ui.TEXT
                    else Ui.PRIMARY
                )
                background = Ui.rounded(if (selected && !presetLocked) Ui.PRIMARY else Ui.INPUT_BG, 16, this@ModelEditActivity)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isClickable = !presetLocked
                setOnClickListener {
                    if (presetLocked || cap == ApiConfig.CAP_TEXT) return@setOnClickListener
                    if (selected) selectedCaps.remove(cap) else selectedCaps.add(cap)
                    renderCapSelector()
                }
                Ui.press(this)
            }
            capsContainer.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
    }

    /** 点击「拉取」：子线程请求 {baseUrl}/models，成功后弹列表选择 */
    private fun fetchModels() {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (base.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_fill_base_and_key), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.toast_fetching_models), Toast.LENGTH_SHORT).show()
        Thread {
            val models = fetchModelsFromNetwork(base, key)
            uiScope.launch {
                when {
                    models == null -> Toast.makeText(this@ModelEditActivity, getString(R.string.toast_fetch_fail), Toast.LENGTH_SHORT).show()
                    models.isEmpty() -> Toast.makeText(this@ModelEditActivity, getString(R.string.toast_empty_models), Toast.LENGTH_SHORT).show()
                    else -> showModelPicker(models)
                }
            }
        }.start()
    }

    /** OpenAI 兼容 GET /models，解析 data[].id；失败返回 null */
    private fun fetchModelsFromNetwork(base: String, key: String): List<String>? {
        val jb = ApiClient.getJson(
            "$base/models",
            headers = authHeaders(key),
            connectMs = 10_000, readMs = 15_000,
        ).getOrNull() ?: return null
        val arr = jb.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
            if (id.isNotEmpty()) list.add(id)
        }
        return list
    }

    /** 拉取模型多选弹窗: 每个模型 = 勾选 + 模型名 + 能力勾选（文本固定，其余独立开关）
     *  默认能力: 已有配置或按名称启发式猜测，用户可逐模型调整 */
    private fun showModelPicker(models: List<String>) {
        val existing = selectedModels.toSet()
        val checked = BooleanArray(models.size) { i -> models[i] in existing }
        // 弹窗内能力勾选：模型名 → 能力集合
        val pickCaps = mutableMapOf<String, MutableSet<String>>()
        models.forEach { m ->
            pickCaps[m] = (selectedModelCaps[m] ?: ApiConfig.guessModelCaps(m)).toMutableSet()
        }
        val (dlg, box) = Ui.dialog(this, getString(R.string.dialog_pick_models))

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        models.forEachIndexed { i, m ->
            val cb = Ui.check(this, checked[i]).apply { textSize = 11f }
            lateinit var row: LinearLayout
            fun styleRow() {
                row.background = Ui.roundedBorder(if (checked[i]) Ui.PRIMARY else Ui.DIVIDER, 12, 1, this@ModelEditActivity)
            }
            fun updateCb() {
                Ui.applyCheck(cb, checked[i], this@ModelEditActivity)
                styleRow()
            }
            // 模型名行（独立圆角描边框卡片，整卡可点切换选中）
            row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                setOnClickListener {
                    checked[i] = !checked[i]
                    updateCb()
                }
            }
            styleRow()
            Ui.press(row)
            row.addView(TextView(this).apply {
                text = m
                textSize = 14f
                setTextColor(Ui.TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(cb)
            listBox.addView(row)

            // 能力 chips 行（独立开关，局部刷新不重建弹窗）
            val capsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2), dp(2), dp(2), dp(8))
            }
            fun renderPickCapsRow() {
                capsRow.removeAllViews()
                val allCaps = listOf(ApiConfig.CAP_TEXT, ApiConfig.CAP_IMAGE, ApiConfig.CAP_VIDEO, ApiConfig.CAP_AUDIO, ApiConfig.CAP_TOOL)
                val caps = pickCaps.getOrPut(m) { mutableSetOf(ApiConfig.CAP_TEXT) }
                allCaps.forEach { cap ->
                    val label = ApiConfig.capLabel(this, cap)
                    val selected = cap in caps
                    val chip = TextView(this@ModelEditActivity).apply {
                        text = label
                        textSize = 11f
                        setTextColor(if (selected) 0xFFFFFFFF.toInt() else Ui.PRIMARY)
                        background = Ui.rounded(if (selected) Ui.PRIMARY else Ui.INPUT_BG, 14, this@ModelEditActivity)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        isClickable = cap != ApiConfig.CAP_TEXT
                        setOnClickListener {
                            if (cap == ApiConfig.CAP_TEXT) return@setOnClickListener
                            if (selected) caps.remove(cap) else caps.add(cap)
                            renderPickCapsRow()
                        }
                        Ui.press(this)
                    }
                    capsRow.addView(chip, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(6)
                    })
                }
            }
            renderPickCapsRow()
            listBox.addView(capsRow)
        }
        box.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        box.addView(Ui.primaryBtn(this, getString(R.string.dialog_ok)) {
            dlg.dismiss()
            selectedModels.clear()
            selectedModels.addAll(models.filterIndexed { i, _ -> checked[i] })
            // 同步能力：勾选的模型写入 selectedModelCaps，未勾选的移除
            selectedModelCaps.clear()
            selectedModels.forEach { m ->
                selectedModelCaps[m] = (pickCaps[m] ?: mutableSetOf(ApiConfig.CAP_TEXT)).toMutableSet()
            }
            if (modelInput.text.isNullOrBlank() && selectedModels.isNotEmpty()) modelInput.setText(selectedModels[0])
            renderSelectedModels()
            Toast.makeText(this, getString(R.string.toast_models_selected, selectedModels.size), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    /** 返回 provider id；失败返回 null */
    private fun doSave(setAsCurrent: Boolean): String? {
        val base = baseInput.text.toString().trim()
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim().ifEmpty { selectedModels.firstOrNull() ?: "" }
        if (base.isEmpty() || model.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_fill_base_and_model), Toast.LENGTH_SHORT).show()
            return null
        }
        if (isNew) {
            val modelCapsMap = buildMap<String, Set<String>> {
                selectedModelCaps.forEach { (m, caps) -> if (m in selectedModels) put(m, caps.toSet()) }
                if (model.trim().isNotEmpty() && !containsKey(model.trim())) put(model.trim(), setOf(ApiConfig.CAP_TEXT))
            }
            val id = ApiConfig.addCustomProvider(labelInput.text.toString().trim(), base, key, model, selectedModels, selectedEffort, selectedCaps, modelCapsMap, selectedAuthType, selectedAuthHeader.ifBlank { "Authorization" })
            if (setAsCurrent) ApiConfig.setCurrent(id)
            return id
        }
        val id = providerId ?: return null
        val caps = if (ApiConfig.providerById(id)?.isPreset == true) null else selectedCaps
        val modelCapsMap = if (ApiConfig.providerById(id)?.isPreset == true) null else buildMap<String, Set<String>> {
            selectedModelCaps.forEach { (m, caps) -> if (m in selectedModels) put(m, caps.toSet()) }
            if (model.trim().isNotEmpty() && !containsKey(model.trim())) put(model.trim(), setOf(ApiConfig.CAP_TEXT))
        }
        ApiConfig.saveProvider(id, base, key, model, setAsCurrent, selectedModels, selectedEffort, caps, modelCapsMap, selectedAuthType, selectedAuthHeader.ifBlank { "Authorization" })
        return id
    }

    private fun delete() {
        val id = providerId ?: return
        ApiConfig.deleteProvider(id)
        Toast.makeText(this, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

}
