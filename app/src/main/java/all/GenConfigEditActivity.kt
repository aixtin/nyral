package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/**
 * 厂商配置编辑页(2026-11-04 v2 契约版):
 * - 粘贴官方示例 → 自动解析(Key/model/URL) → 指纹表识别协议生成契约 → 回显可改
 * - 填 Key 后"测试连接"发真实最小请求验证; 通过才保存
 */
class GenConfigEditActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var editingId: String = ""
    private lateinit var etExample: EditText
    private lateinit var etName: EditText
    private lateinit var etEndpoint: EditText
    private lateinit var etSubmit: EditText
    private lateinit var etPoll: EditText
    private lateinit var etModel: EditText
    private lateinit var etAuth: EditText
    private lateinit var etImagePath: EditText
    private lateinit var etSep: EditText
    private lateinit var etKey: EditText
    private lateinit var swEnable: SwitchView
    private lateinit var swDefault: SwitchView
    private lateinit var tvTestResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        editingId = intent.getStringExtra("entry_id") ?: ""
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, if (editingId.isEmpty()) "添加厂商" else "编辑厂商"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // 粘贴示例
        content.addView(Ui.groupLabel(this, "粘贴官方示例"))
        content.addView(Ui.card(this).apply {
            addView(EditText(this@GenConfigEditActivity).apply {
                etExample = this
                hint = "从平台官网复制调用示例(curl/代码)粘贴到这里, 点自动解析识别协议"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 6
                textSize = 12f
                gravity = Gravity.TOP or Gravity.START
                setBackgroundColor(Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)).apply {
                setMargins(dp(12), dp(8), dp(12), dp(8))
            })
        })
        content.addView(Ui.primaryBtn(this, "自动解析") { parseExample() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })

        // 配置字段
        content.addView(Ui.groupLabel(this, "协议配置(自动解析后核对)"))
        content.addView(Ui.card(this).apply {
            val col = LinearLayout(this@GenConfigEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            col.addView(field("显示名", "如 腾讯混元") { etName = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("请求端点", "POST 完整 URL") { etEndpoint = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("异步提交端点", "异步模式才填; 同步留空") { etSubmit = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("轮询端点", "含 {task_id} 占位; 同步留空") { etPoll = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("模型名", "如 wanx2.1-t2i-turbo / hy-image-v3") { etModel = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("鉴权方式", "bearer / header / none") { etAuth = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("取图路径", "data[0].url / choices[0].delta.image.url") { etImagePath = it })
            col.addView(Ui.divider(this@GenConfigEditActivity))
            col.addView(field("尺寸分隔符", "* 或 x") { etSep = it })
            addView(col)
        })

        // 模型名输入时实时修正协议(v3.5/v35 → 混元 v3.5 官方协议回填)
        etModel.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                val m = s?.toString()?.trim().orEmpty()
                if (m.lowercase().contains("v3.5") || m.lowercase().contains("v35")) {
                    val refined = GenConfig.refineContractByModel(
                        GenConfig.GenContract(
                            provider = "hunyuan", displayName = "腾讯混元",
                            endpoint = etEndpoint.text.toString().trim(),
                            submitEndpoint = etSubmit.text.toString().trim(),
                            pollEndpoint = etPoll.text.toString().trim(),
                            modelId = m,
                            authType = etAuth.text.toString().trim().ifBlank { "bearer" },
                            imageUrlPath = etImagePath.text.toString().trim().ifBlank { "data[0].url" },
                            sizeSeparator = etSep.text.toString().trim().ifBlank { "x" },
                            mode = if (etSubmit.text.toString().trim().isNotEmpty()) "async" else "sync"
                        ), m
                    )
                    etEndpoint.setText(refined.endpoint)
                    etSubmit.setText(refined.submitEndpoint)
                    etPoll.setText(refined.pollEndpoint)
                    etAuth.setText(refined.authType)
                    etImagePath.setText(refined.imageUrlPath)
                    etSep.setText(refined.sizeSeparator)
                }
            }
        })

        // Key
        content.addView(Ui.groupLabel(this, "API Key"))
        content.addView(Ui.card(this).apply {
            addView(EditText(this@GenConfigEditActivity).apply {
                etKey = this
                hint = "用户自己填 Key, 加密存本机, 不出设备"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                textSize = 14f
                setBackgroundColor(Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(8), dp(12), dp(8))
            })
        })

        // 开关行: 启用 / 设为默认(卡片化 + 主题化开关)
        content.addView(Ui.groupLabel(this, "选项"))
        content.addView(Ui.card(this).apply {
            fun rowOf(label: String, bind: (SwitchView) -> Unit): View {
                val row = LinearLayout(this@GenConfigEditActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                row.addView(TextView(this@GenConfigEditActivity).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.BLACK)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val sw = Ui.switch(this@GenConfigEditActivity)
                bind(sw)
                row.addView(sw)
                return row
            }
            addView(rowOf("启用该厂商") { swEnable = it })
            addView(Ui.divider(this@GenConfigEditActivity))
            addView(rowOf("设为默认厂商") { swDefault = it })
        })

        // 测试结果区
        tvTestResult = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        content.addView(tvTestResult)

        // 测试连接
        content.addView(Ui.lightBtn(this, "测试连接(会发一次最小请求)") { testConnection() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })

        // 保存
        content.addView(Ui.primaryBtn(this, "保存") { save() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // 编辑已有配置: 回显
        if (editingId.isNotEmpty()) {
            GenConfig.entryById(this, editingId)?.let { e ->
                etName.setText(e.displayName)
                etEndpoint.setText(e.contract.endpoint)
                etSubmit.setText(e.contract.submitEndpoint)
                etPoll.setText(e.contract.pollEndpoint)
                etModel.setText(e.contract.modelId)
                etAuth.setText(e.contract.authType)
                etImagePath.setText(e.contract.imageUrlPath)
                etSep.setText(e.contract.sizeSeparator)
                etKey.setText(GenConfig.keyOf(this, e))
                swEnable.isChecked = e.enabled
                swDefault.isChecked = e.isDefault
            }
        }
    }

    /** 字段编辑行: label + EditText, 通过 bind 回调把 EditText 交给调用方 */
    private fun field(label: String, hint: String, secret: Boolean = false, bind: (EditText) -> Unit = {}): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Ui.SUB)
        })
        val et = Ui.input(this, hint).apply {
            textSize = 14f
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        bind(et)
        row.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** 自动解析: 正则抓 Key/model/URL, 指纹表识别协议, 回显 */
    private fun parseExample() {
        val example = etExample.text.toString()
        if (example.isBlank()) {
            Toast.makeText(this, "请先粘贴官方示例", Toast.LENGTH_SHORT).show()
            return
        }
        val (_, model, url) = GenConfig.parseExample(example)
        if (url.isBlank() && model.isBlank()) {
            Toast.makeText(this, "没认出有效内容, 请确认粘贴的是完整调用示例(含请求地址)", Toast.LENGTH_SHORT).show()
            return
        }
        val contract = GenConfig.inferContractFromUrl(url) ?: GenConfig.GenContract(
            provider = "openai", displayName = "OpenAI 兼容",
            endpoint = url,
            authType = "bearer", authHeaderName = "Authorization", authPrefix = "Bearer ",
            mode = "sync", template = "prompt", sizeSeparator = "x",
            imageUrlPath = "data[0].url"
        ).let { if (url.isBlank()) it.copy(endpoint = "") else it }
        // 模型名回填 + 按模型修正协议(v3.5 等新协议自动切换契约)
        val c2 = if (model.isNotBlank()) {
            val refined = GenConfig.refineContractByModel(contract, model)
            if (refined.modelId.isBlank()) refined.copy(modelId = model) else refined
        } else contract
        etName.setText(c2.displayName)
        etEndpoint.setText(c2.endpoint)
        etSubmit.setText(c2.submitEndpoint)
        etPoll.setText(c2.pollEndpoint)
        etModel.setText(c2.modelId)
        etAuth.setText(c2.authType)
        etImagePath.setText(c2.imageUrlPath)
        etSep.setText(c2.sizeSeparator)
        tvTestResult.text = "已识别: ${c2.displayName} / ${c2.provider} / ${c2.template} / ${c2.mode}" +
            "\nKey 请自行填写(示例中 Key 多为占位符, 不做自动抓取)"
    }

    /** 测试连接: 构造临时 Entry 发最小请求 */
    private fun testConnection() {
        val entry = buildEntry()
        if (entry.contract.endpoint.isBlank() && entry.contract.submitEndpoint.isBlank()) {
            Toast.makeText(this, "端点为空, 先解析示例", Toast.LENGTH_SHORT).show()
            return
        }
        val keyText = etKey.text.toString().trim()
        if (keyText.isBlank()) {
            Toast.makeText(this, "先填 Key 才能测试", Toast.LENGTH_SHORT).show()
            return
        }
        tvTestResult.text = "测试中, 请稍候(最长 30 秒)..."
        Thread {
            val r = GenTools.testConnection(this, entry, keyText)
            runOnUiThread { tvTestResult.text = r }
        }.start()
    }

    private fun buildEntry(): GenConfig.Entry {
        val raw = GenConfig.GenContract(
            provider = "",  // 由保存时按 URL 推断或保留已有
            displayName = etName.text.toString().trim().ifBlank { "未命名厂商" },
            endpoint = etEndpoint.text.toString().trim(),
            submitEndpoint = etSubmit.text.toString().trim(),
            pollEndpoint = etPoll.text.toString().trim(),
            modelId = etModel.text.toString().trim(),
            authType = etAuth.text.toString().trim().ifBlank { "bearer" },
            imageUrlPath = etImagePath.text.toString().trim().ifBlank { "data[0].url" },
            sizeSeparator = etSep.text.toString().trim().ifBlank { "x" },
            mode = if (etSubmit.text.toString().trim().isNotEmpty()) "async" else "sync"
        )
        // 模型名含 v3.5/v35: 强制对齐混元 v3.5 官方协议(端点/chat模板/取图路径/同步), 防止手改字段后协议漂移
        val contract = if (raw.modelId.isNotBlank()) {
            val refined = GenConfig.refineContractByModel(raw, raw.modelId)
            if (refined === raw) raw else refined
        } else raw
        // 保留旧条目的 provider 与成本信息
        val contract2 = contract.let { c ->
            val old = GenConfig.entryById(this, editingId)?.contract
            c.copy(
                provider = if (c.provider.isBlank()) (old?.provider ?: "custom") else c.provider,
                pricePerImage = old?.pricePerImage ?: c.pricePerImage,
                extraHeaders = old?.extraHeaders ?: emptyMap()
            )
        }
        val id = if (editingId.isEmpty()) UUID.randomUUID().toString() else editingId
        val base = GenConfig.Entry(
            id = id,
            displayName = contract2.displayName,
            enabled = swEnable.isChecked,
            isDefault = swDefault.isChecked,
            contract = contract2
        )
        return GenConfig.entryWithKey(this, base, etKey.text.toString().trim())
    }

    private fun save() {
        val entry = buildEntry()
        if (entry.contract.endpoint.isBlank() && entry.contract.submitEndpoint.isBlank()) {
            Toast.makeText(this, "端点不能为空, 请先解析示例或手填", Toast.LENGTH_SHORT).show()
            return
        }
        GenConfig.addOrUpdate(this, entry)
        Toast.makeText(this, "已保存 ${entry.displayName}", Toast.LENGTH_SHORT).show()
        finish()
    }
}
