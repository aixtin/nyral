package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

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

        // 预置服务: 点条目即预填名称与地址, 免手写
        content.addView(TextView(this).apply {
            text = getString(R.string.mcp_32)
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })
        val presetCard = Ui.card(this)
        val presets = McpConfigStore.presets()
        presets.forEachIndexed { i, p ->
            presetCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener { showEditDialog(-1, p) }
                Ui.press(this)
                addView(Ui.iconBadge(this@McpConfigActivity, p.name, p.url.hashCode()))
                addView(LinearLayout(this@McpConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(12), 0, dp(8), 0)
                    }
                    addView(Ui.itemTitle(this@McpConfigActivity, p.name))
                    addView(Ui.itemSub(this@McpConfigActivity, p.desc))
                })
                addView(Ui.arrow(this@McpConfigActivity))
            })
            if (i < presets.size - 1) presetCard.addView(Ui.divider(this))
        }
        content.addView(presetCard)

        content.addView(TextView(this).apply {
            text = getString(R.string.mcp_03) + getString(R.string.mcp_04) + "\n\n" +
                getString(R.string.mcp_33) + "\n" + getString(R.string.mcp_34)
            textSize = 12f
            setTextColor(Ui.SUB)
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
                setTextColor(Ui.SUB)
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

    /** preset 非空时用预置模板预填名称/地址(点预置项进来的路径) */
    private fun showEditDialog(index: Int, preset: McpConfigStore.McpPreset? = null) {
        val isEdit = index >= 0
        val cfg = when {
            isEdit -> servers[index]
            preset != null -> McpConfigStore.McpServer(preset.name, preset.url, null)
            else -> McpConfigStore.McpServer("", "", null)
        }

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
        // 编辑态才给删除入口; 删除会移除该服务的全部工具, 需二次确认
        if (isEdit) {
            bottom.addView(Ui.dangerBtn(this, getString(R.string.mcp_35)) { confirmDelete(index, dlg) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
        }
        bottom.addView(Ui.dialogCancelBtn(this, getString(R.string.mcp_20)) { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    /** 删除服务: 二次确认后落盘并清缓存(不可撤销) */
    private fun confirmDelete(index: Int, parent: android.app.Dialog) {
        val name = servers.getOrNull(index)?.name ?: return
        val (dlg, content, bottom) = Ui.dialogFixed(this, getString(R.string.mcp_35), 0.5)
        content.addView(Ui.dialogText(this, getString(R.string.mcp_37, name)))
        bottom.addView(Ui.dangerBtn(this, getString(R.string.mcp_38)) {
            servers.removeAt(index)
            McpConfigStore.save(this, servers)
            McpClientManager.clearCache()
            refreshList()
            dlg.dismiss()
            parent.dismiss()
            Toast.makeText(this, getString(R.string.mcp_39), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
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
