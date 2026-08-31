package io.github.aixtin.nyral

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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 记忆辅助模型配置页 — 与模型配置同视觉，仅配置 MemoryKeeper 生成索引用的辅助 API。
 * - 未启用 / 未填全时自动跟随主对话 API（保底机制），本页仅为可选独立配置。
 */
class MemModelConfigActivity : Activity() {

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

        // ---- 自绘标题栏 + 右上角"保存" ----
        root.addView(Ui.titleBar(this, "记忆辅助模型", right = { bar ->
            bar.addView(TextView(this).apply {
                text = "保存"
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
        card.addView(Ui.fieldLabel(this, "独立配置"))
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
            text = "使用独立的辅助模型 API"
            textSize = 15f
            setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        enableBox = Ui.check(this, enabled)
        enableRow.addView(enableBox)
        card.addView(enableRow)
        card.addView(Ui.hint(this, "关闭时自动跟随主对话 API（默认保底，行为与未配置前一致）"))

        // 名称（可选，仅展示用）
        card.addView(Ui.fieldLabel(this, "名称（可选）"))
        labelInput = Ui.input(this, "如 记忆索引模型").apply {
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
        card.addView(Ui.fieldLabel(this, "模型名"))
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modelInput = Ui.input(this, "如 deepseek-chat").apply {
            setText(cfg.model)
        }
        modelRow.addView(modelInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modelRow.addView(Ui.lightBtn(this, "拉取") { fetchModels() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        card.addView(modelRow)
        card.addView(Ui.hint(this, "建议选择轻量模型（如 deepseek-chat / glm-4-flash），索引生成更快"))

        content.addView(card)

        // 底部说明
        content.addView(Ui.hint(this, "说明：辅助模型只负责把归档对话提炼为 summary 主题索引；原文记忆的向量化与检索始终在本地 BGE 完成，不受本配置影响。"))

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
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                }))
                put("max_tokens", 1)
                put("stream", false)
            }
            conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) TestResult(true, "")
            else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val lower = err.lowercase()
                val insufficient = code == 402 || lower.contains("insufficient") || lower.contains("balance") ||
                    lower.contains("余额") || lower.contains("欠费")
                if (insufficient) TestResult(true, "配置正确但账户余额不足，已保存，充值后即可使用")
                else TestResult(false, "HTTP $code: ${err.take(150)}")
            }
        } catch (e: Exception) {
            TestResult(true, "网络异常未能验证连通性，已保存（${e.message ?: "网络错误"}）")
        } finally {
            conn?.disconnect()
        }
    }

    private fun save() {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        val label = labelInput.text.toString().trim()

        if (enabled && (base.isEmpty() || key.isEmpty() || model.isEmpty())) {
            Toast.makeText(this, "启用独立配置需填全 Base URL / API Key / 模型名", Toast.LENGTH_SHORT).show()
            return
        }

        if (!enabled) {
            // 未启用：直接保存（字段可留空，不生效）
            MemoryApiConfig.save(MemoryApiConfig.Config(false, label, base, key, model))
            Toast.makeText(this, "已保存（跟随主对话 API）", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "正在测试连通性...", Toast.LENGTH_SHORT).show()
        Thread {
            val res = testConnectivity(base, key, model)
            runOnUiThread {
                if (!res.canSave) {
                    Toast.makeText(this, "连通性测试失败，未保存：${res.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                MemoryApiConfig.save(MemoryApiConfig.Config(true, label, base, key, model))
                val tip = if (res.message.isNotEmpty()) "已启用独立辅助模型。${res.message}" else "已启用独立辅助模型"
                Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    /** 点击「拉取」：子线程请求 {baseUrl}/models，成功后弹列表选择 */
    private fun fetchModels() {
        val base = baseInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (base.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "请先填写 Base URL 和 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在拉取模型列表...", Toast.LENGTH_SHORT).show()
        Thread {
            val models = fetchModelsFromNetwork(base, key)
            runOnUiThread {
                when {
                    models == null -> Toast.makeText(this, "拉取失败：网络错误或接口不兼容", Toast.LENGTH_SHORT).show()
                    models.isEmpty() -> Toast.makeText(this, "接口返回空列表", Toast.LENGTH_SHORT).show()
                    else -> showModelPicker(models)
                }
            }
        }.start()
    }

    private fun fetchModelsFromNetwork(base: String, key: String): List<String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$base/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
                if (id.isNotEmpty()) list.add(id)
            }
            list
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun showModelPicker(models: List<String>) {
        val (dlg, box) = Ui.dialog(this, "选择模型")
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

        box.addView(Ui.primaryBtn(this, "确定") {
            dlg.dismiss()
            val picked = models.filterIndexed { i, _ -> checked[i] }
            if (picked.isNotEmpty()) modelInput.setText(picked.first())
            Toast.makeText(this, "已选择 ${picked.size} 个模型", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
