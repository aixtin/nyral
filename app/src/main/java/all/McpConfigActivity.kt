package io.github.aixtin.droidagent

import io.github.aixtin.droidagent.R

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


/**
 * MCP 服务配置页 — 与 SshConfigActivity 同视觉风格。
 * 字段: 名称 + 地址 URL(+可选 Token)。支持「测试」实时验证。
 * 保存后清空 McpClientManager 缓存, 下次会话生效。
 */
class McpConfigActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var configList: LinearLayout
    private var servers: MutableList<McpConfigStore.McpServer> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        servers = McpConfigStore.load(this).toMutableList()

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.mcp_01)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        configList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(configList)
        refreshList()

        content.addView(Ui.primaryBtn(this, getString(R.string.mcp_02)) { showEditDialog(-1) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.mcp_03) +
                "示例地址: 本机 http://127.0.0.1:8787/mcp · 局域网 http://192.168.x.x:8787/mcp\n" +
                getString(R.string.mcp_04)
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun refreshList() {
        configList.removeAllViews()
        if (servers.isEmpty()) {
            configList.addView(TextView(this).apply {
                text = getString(R.string.mcp_05)
                textSize = 13f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }
        val card = Ui.card(this)
        servers.forEachIndexed { i, s ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener { showEditDialog(i) }
                Ui.press(this)
                addView(Ui.iconBadge(this@McpConfigActivity, s.name, s.url.hashCode()))
                addView(LinearLayout(this@McpConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(12), 0, dp(8), 0)
                    }
                    addView(Ui.itemTitle(this@McpConfigActivity, s.name))
                    addView(Ui.itemSub(this@McpConfigActivity,
                        s.url + if (!s.token.isNullOrBlank()) getString(R.string.mcp_06) else ""))
                })
                addView(Ui.arrow(this@McpConfigActivity))
            }
            card.addView(item)
            if (i < servers.size - 1) card.addView(Ui.divider(this))
        }
        configList.addView(card)
    }

    private fun showEditDialog(index: Int) {
        val isEdit = index >= 0
        val cfg = if (isEdit) servers[index] else McpConfigStore.McpServer("", "", null)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val editTexts = mutableListOf<EditText>()
        // 名称
        container.addView(Ui.fieldLabel(this, getString(R.string.mcp_07)))
        val etName = Ui.input(this, getString(R.string.mcp_08)).apply { setText(cfg.name) }
        container.addView(etName)
        editTexts.add(etName)
        // URL
        container.addView(Ui.fieldLabel(this, getString(R.string.mcp_09)))
        val etUrl = Ui.input(this, "http://127.0.0.1:8787/mcp").apply { setText(cfg.url) }
        container.addView(etUrl)
        editTexts.add(etUrl)
        // Token
        container.addView(Ui.fieldLabel(this, getString(R.string.mcp_10)))
        val etToken = Ui.input(this, getString(R.string.mcp_11)).apply { setText(cfg.token ?: "") }
        container.addView(etToken)
        editTexts.add(etToken)

        val (dlg, content, bottom) = Ui.dialogFixed(this, if (isEdit) getString(R.string.mcp_12) else getString(R.string.mcp_02), 0.55)
        content.addView(container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 测试(异步, UI 层起线程): 与「保存」按钮同高同字号(覆盖 lightBtn 的 13f/(10,8)/12 小号样式)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Ui.lightBtn(this, getString(R.string.mcp_13)) {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) { Toast.makeText(this, getString(R.string.mcp_14), Toast.LENGTH_SHORT).show(); return@lightBtn }
            val token = etToken.text.toString().trim().ifEmpty { null }
            Toast.makeText(this, getString(R.string.mcp_15), Toast.LENGTH_SHORT).show()
            Thread {
                var res = getString(R.string.mcp_16)
                try { res = McpClientManager.testConnection(url, token) }
                catch (e: Exception) { res = getString(R.string.mcp_31, e.message) }
                uiScope.launch { Toast.makeText(this@McpConfigActivity, res, Toast.LENGTH_LONG).show() }
            }.start()
        }.apply { textSize = 15f; setPadding(dp(16), dp(12), dp(16), dp(12)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        btnRow.addView(Ui.primaryBtn(this, getString(R.string.mcp_17)) {
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, getString(R.string.mcp_18), Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            val token = etToken.text.toString().trim().ifEmpty { null }
            val newCfg = McpConfigStore.McpServer(name, url, token)
            if (isEdit) servers[index] = newCfg else servers.add(newCfg)
            McpConfigStore.save(this, servers)
            McpClientManager.clearCache()
            refreshList()
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.mcp_19), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        bottom.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        bottom.addView(Ui.dialogCancelBtn(this, getString(R.string.mcp_20)) { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

}
