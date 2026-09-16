package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


/**
 * 记忆辅助模型配置页 — 与模型配置同视觉，仅配置 MemoryKeeper 生成索引用的辅助 API。
 * - 未启用 / 未填全时自动跟随主对话 API（保底机制），本页仅为可选独立配置。
 */
class MemModelConfigActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var labelInput: EditText
    private lateinit var baseInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var enableBox: TextView
    private var enabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        Ui.statusBar(this)

        val cfg = MemoryApiConfig.load()
        enabled = cfg.enabled

        val root = Ui.pageRoot(this)

        // ---- 自绘标题栏 + 右上角getString(R.string.mmcfg_02) ----
        root.addView(Ui.titleBar(this, getString(R.string.mmcfg_01), right = { bar ->
            bar.addView(TextView(this).apply {
                text = getString(R.string.mmcfg_02)
                textSize = 15f
                setTextColor(Ui.PRIMARY)
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener { save() }
            })
        }))

        // ---- 内容区：白色圆角卡片包裹表单 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val card = Ui.card(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // 启用开关行
        card.addView(Ui.fieldLabel(this, getString(R.string.mmcfg_03)))
        val enableRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
            isClickable = true
            setOnClickListener {
                enabled = !enabled
                renderEnable()
            }
            Ui.press(this)
        }
        enableRow.addView(TextView(this).apply {
            text = getString(R.string.mmcfg_04)
            textSize = 15f
            setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        enableBox = Ui.check(this, enabled)
        enableRow.addView(enableBox)
        card.addView(enableRow)
        card.addView(Ui.hint(this, getString(R.string.mmcfg_05)))

        // 名称（可选，仅展示用）
        card.addView(Ui.fieldLabel(this, getString(R.string.mmcfg_06)))
        labelInput = Ui.input(this, getString(R.string.mmcfg_07)).apply {
            setText(cfg.label)
        }
        card.addView(labelInput)

        // Base URL
        card.addView(Ui.fieldLabel(this, "Base URL"))
        baseInput = Ui.input(this, "https://api.example.com/v1").apply {
            setText(cfg.base)
        }
        card.addView(baseInput)

        // API Key
        card.addView(Ui.fieldLabel(this, "API Key"))
        keyInput = Ui.input(this, "sk-...").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(cfg.key)
        }
        card.addView(keyInput)

        // 模型名 + 拉取
        card.addView(Ui.fieldLabel(this, getString(R.string.mmcfg_08)))
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modelInput = Ui.input(this, getString(R.string.mmcfg_09)).apply {
            setText(cfg.model)
        }
        modelRow.addView(modelInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modelRow.addView(Ui.lightBtn(this, getString(R.string.mmcfg_10)) { fetchModels() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        card.addView(modelRow)
        card.addView(Ui.hint(this, getString(R.string.mmcfg_11)))

        content.addView(card)

        // 底部说明
        content.addView(Ui.hint(this, getString(R.string.mmcfg_12)))

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun renderEnable() {
        enableBox.text = if (enabled) "✓" else ""
        enableBox.background = Ui.rounded(if (enabled) Ui.PRIMARY else Ui.INPUT_BG, 6, this)
    }

    /** 连通性测试结果: canSave=true 表示可保存(通过或放行), message 为提示/警告 */
    private class TestResult(val canSave: Boolean, val message: String)

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
            headers = ApiClient.bearer(key),
            connectMs = 10_000, readMs = 20_000,
        )
        return when {
            result.isSuccess -> TestResult(true, "")
            else -> {
                val e = result.exceptionOrNull()
                val h = e as? ApiClient.HttpError
                val lower = (h?.body ?: "").lowercase()
                val insufficient = h != null && (h.code == 402 || lower.contains("insufficient") ||
                    lower.contains("balance") || lower.contains(getString(R.string.mmcfg_13)) || lower.contains(getString(R.string.mmcfg_14)))
                when {
                    insufficient -> TestResult(true, getString(R.string.mmcfg_15))
                    h != null -> TestResult(false, "HTTP ${h.code}: ${h.body.take(150)}")
                    else -> TestResult(true, getString(R.string.mmcfg_31, e?.message ?: getString(R.string.mmcfg_32)))
                }
            }
        }
    }

    private fun save() {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        val label = labelInput.text.toString().trim()

        if (enabled && (base.isEmpty() || key.isEmpty() || model.isEmpty())) {
            Toast.makeText(this, getString(R.string.mmcfg_16), Toast.LENGTH_SHORT).show()
            return
        }

        if (!enabled) {
            // 未启用：直接保存（字段可留空，不生效）
            MemoryApiConfig.save(MemoryApiConfig.Config(false, label, base, key, model))
            Toast.makeText(this, getString(R.string.mmcfg_17), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, getString(R.string.mmcfg_18), Toast.LENGTH_SHORT).show()
        Thread {
            val res = testConnectivity(base, key, model)
            uiScope.launch {
                if (!res.canSave) {
                    Toast.makeText(this@MemModelConfigActivity, getString(R.string.mmcfg_27, res.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                MemoryApiConfig.save(MemoryApiConfig.Config(true, label, base, key, model))
                val tip = if (res.message.isNotEmpty()) getString(R.string.mmcfg_28, res.message) else getString(R.string.mmcfg_19)
                Toast.makeText(this@MemModelConfigActivity, tip, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    /** 点击「拉取」：子线程请求 {baseUrl}/models，成功后弹列表选择 */
    private fun fetchModels() {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (base.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, getString(R.string.mmcfg_20), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.mmcfg_21), Toast.LENGTH_SHORT).show()
        Thread {
            val models = fetchModelsFromNetwork(base, key)
            uiScope.launch {
                when {
                    models == null -> Toast.makeText(this@MemModelConfigActivity, getString(R.string.mmcfg_22), Toast.LENGTH_SHORT).show()
                    models.isEmpty() -> Toast.makeText(this@MemModelConfigActivity, getString(R.string.mmcfg_23), Toast.LENGTH_SHORT).show()
                    else -> showModelPicker(models)
                }
            }
        }.start()
    }

    private fun fetchModelsFromNetwork(base: String, key: String): List<String>? {
        val jb = ApiClient.getJson(
            "$base/models",
            headers = ApiClient.bearer(key),
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

    private fun showModelPicker(models: List<String>) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.mmcfg_24))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checked = BooleanArray(models.size)
        models.forEachIndexed { i, m ->
            val cb = Ui.check(this, checked[i])
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(2), dp(10), dp(2), dp(10))
                setOnClickListener {
                    checked[i] = !checked[i]
                    cb.text = if (checked[i]) "✓" else ""
                    cb.background = Ui.rounded(if (checked[i]) Ui.PRIMARY else Ui.INPUT_BG, 6, this@MemModelConfigActivity)
                }
                Ui.press(this)
            }
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
        }
        box.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        box.addView(Ui.primaryBtn(this, getString(R.string.mmcfg_25)) {
            dlg.dismiss()
            val picked = models.filterIndexed { i, _ -> checked[i] }
            if (picked.isNotEmpty()) modelInput.setText(picked.first())
            Toast.makeText(this, getString(R.string.mmcfg_30, picked.size), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.mmcfg_26)) { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

}
