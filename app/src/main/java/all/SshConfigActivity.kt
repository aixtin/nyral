package io.github.aixtin.droidagent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
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
 * SSH 连接配置页 — 与主页统一视觉（灰底 + 白色圆角卡片 + 圆角输入框/按钮）
 * 点击条目编辑；密码/私钥二选一，均留空则不填。
 */
class SshConfigActivity : Activity() {

    private companion object {
        const val REQ_IMPORT_KEY = 1001
    }

    /** 私钥字段折叠状态: content=真实密钥内容, fileName=导入文件名, folded=是否折叠 */
    private class KeyFieldState(
        var content: String,
        var fileName: String?,
        var folded: Boolean,
        var toggleBtn: TextView? = null,
        var delBtn: TextView? = null,
        var suppress: Boolean = false
    )

    private lateinit var configList: LinearLayout
    private var configs: MutableList<SshConfigStore.SshConfig> = mutableListOf()
    private var pendingKeyField: EditText? = null
    private val keyStates = mutableMapOf<EditText, KeyFieldState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        configs = SshConfigStore.load(this).toMutableList()

        val root = Ui.pageRoot(this)

        // ---- 自绘标题栏 ----
        root.addView(Ui.titleBar(this, "SSH 连接配置"))

        // ---- 内容区 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        configList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(configList)
        refreshList()

        content.addView(Ui.primaryBtn(this, "添加连接") { showEditDialog(-1) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        content.addView(TextView(this).apply {
            text = "提示: 点击条目编辑; 密码/私钥二选一, 均留空则不填。\n数据经 Android Keystore 加密存储。"
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_KEY && resultCode == Activity.RESULT_OK && data != null) {
            val uri: Uri? = data.data
            val field = pendingKeyField
            if (uri != null && field != null) {
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (content.isNullOrBlank()) {
                        Toast.makeText(this, "文件为空", Toast.LENGTH_SHORT).show()
                    } else {
                        val st = keyStates[field]
                        if (st != null) {
                            st.content = content.trim()
                            st.fileName = queryDisplayName(uri) ?: "私钥文件"
                            st.folded = true
                            updateKeyField(field, st.toggleBtn)
                        } else {
                            field.setText(content.trim())
                        }
                        Toast.makeText(this, "私钥已导入", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            pendingKeyField = null
        }
    }

    private fun refreshList() {
        configList.removeAllViews()
        if (configs.isEmpty()) {
            configList.addView(TextView(this).apply {
                text = "暂无 SSH 连接\n点击下方「添加连接」创建"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }
        val card = Ui.card(this)
        configs.forEachIndexed { i, cfg ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener { showEditDialog(i) }
                Ui.press(this)
                addView(Ui.iconBadge(this@SshConfigActivity, cfg.name, cfg.host.hashCode()))
                addView(LinearLayout(this@SshConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(12), 0, dp(8), 0)
                    }
                    addView(Ui.itemTitle(this@SshConfigActivity, cfg.name))
                    addView(Ui.itemSub(this@SshConfigActivity, buildDesc(cfg)))
                })
                addView(Ui.arrow(this@SshConfigActivity))
            }
            card.addView(item)
            if (i < configs.size - 1) card.addView(Ui.divider(this))
        }
        configList.addView(card)
    }

    private fun buildDesc(cfg: SshConfigStore.SshConfig): String {
        val sb = StringBuilder("${cfg.user}@${cfg.host}:${cfg.port}")
        if (cfg.password != null) sb.append(" [密码]")
        if (cfg.privateKey != null) sb.append(" [密钥]")
        if (cfg.hasProxy) sb.append(" [跳板:${cfg.proxyUser ?: ""}@${cfg.proxyHost}]")
        return sb.toString()
    }

    private fun showEditDialog(index: Int) {
        keyStates.clear()
        val isEdit = index >= 0
        val cfg = if (isEdit) configs[index] else SshConfigStore.SshConfig("", "", 22, "")

        fun addField(container: LinearLayout, editTexts: MutableList<EditText>, label: String, value: String, onMore: ((EditText) -> Unit)? = null) {
            val et = Ui.input(this, label).apply {
                setText(value)
                if (label.contains("密码") || label.contains("passphrase")) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                if (label.contains("私钥")) {
                    minLines = 4
                }
            }
            if (onMore != null) {
                container.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@SshConfigActivity).apply {
                        text = label
                        textSize = 13f
                        setTextColor(0xFF888888.toInt())
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@SshConfigActivity).apply {
                        text = "⋯"
                        textSize = 18f
                        setTextColor(0xFF666666.toInt())
                        setPadding(dp(8), 0, 0, 0)
                        setOnClickListener { onMore(et) }
                        Ui.press(this)
                    })
                })
            } else {
                container.addView(Ui.fieldLabel(this, label))
            }
            container.addView(et)
            editTexts.add(et)
        }

        /** 私钥字段: 标题行 = 标签 + 折叠/展开按钮 + ⋯导入; 折叠时显示文件名, 展开显示密钥内容 */
        fun addKeyField(container: LinearLayout, editTexts: MutableList<EditText>, label: String, content: String, onMore: (EditText) -> Unit) {
            val et = Ui.input(this, label).apply {
                setText(content)
                gravity = Gravity.TOP
                setMinLines(1)
                setMaxLines(Integer.MAX_VALUE)
                setSingleLine(false)
            }
            val st = KeyFieldState(content = content, fileName = null, folded = false).also { keyStates[et] = it }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!st.suppress) st.content = s?.toString() ?: ""
                }
            })
            val toggleBtn = TextView(this).apply {
                text = "﹀"
                textSize = 16f
                setTextColor(0xFF0B93F6.toInt())
                setPadding(dp(10), 0, dp(6), 0)
                gravity = Gravity.CENTER
                minWidth = dp(22)
                visibility = View.INVISIBLE
            }
            toggleBtn.setOnClickListener {
                val s = keyStates[et] ?: return@setOnClickListener
                if (s.fileName == null) return@setOnClickListener
                s.folded = !s.folded
                updateKeyField(et, toggleBtn)
            }
            Ui.press(toggleBtn)
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SshConfigActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(0xFF888888.toInt())
                })
                addView(toggleBtn)
                addView(TextView(this@SshConfigActivity).apply {
                    text = "导入"
                    textSize = 13f
                    setTextColor(0xFF0B93F6.toInt())
                    setPadding(dp(8), 0, 0, 0)
                    setOnClickListener { onMore(et) }
                        Ui.press(this)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(48) })
            })
            editTexts.add(et)
            st.toggleBtn = toggleBtn

            val delBtn = TextView(this).apply {
                text = "删除"
                textSize = 13f
                setTextColor(0xFFE53935.toInt())
                setPadding(dp(8), 0, 0, 0)
                visibility = View.GONE
            }
            delBtn.setOnClickListener {
                val s = keyStates[et] ?: return@setOnClickListener
                s.content = ""
                s.fileName = null
                s.folded = false
                et.isEnabled = true
                et.setSingleLine(false)
                st.suppress = true
                et.setText("")
                st.suppress = false
                updateKeyField(et, toggleBtn)
                et.requestFocus()
            }
            st.delBtn = delBtn
            Ui.press(delBtn)
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(delBtn)
            })
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val editTexts = mutableListOf<EditText>()
        addField(container, editTexts, "名称(如 vps/dev188)", cfg.name)
        addField(container, editTexts, "主机 IP", cfg.host)
        addField(container, editTexts, "端口", cfg.port.toString())
        addField(container, editTexts, "用户名", cfg.user)
        addField(container, editTexts, "密码(留空不填)", cfg.password ?: "")
        addKeyField(container, editTexts, "私钥内容(粘贴 PEM, 留空不填)", cfg.privateKey ?: "") { field ->
            openKeyFilePicker(field)
        }
        addField(container, editTexts, "密钥口令 passphrase", cfg.passphrase ?: "")
        container.addView(TextView(this).apply {
            text = "━━ 跳板机(留空=直连) ━━"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(12), 0, dp(4))
        })
        addField(container, editTexts, "跳板机主机 IP", cfg.proxyHost ?: "")
        addField(container, editTexts, "跳板机端口", cfg.proxyPort.toString())
        addField(container, editTexts, "跳板机用户名", cfg.proxyUser ?: "")
        addField(container, editTexts, "跳板机密码(留空不填)", cfg.proxyPassword ?: "")
        addKeyField(container, editTexts, "跳板机私钥内容(粘贴 PEM)", cfg.proxyPrivateKey ?: "") { field ->
            openKeyFilePicker(field)
        }
        addField(container, editTexts, "跳板机密钥口令", cfg.proxyPassphrase ?: "")

        // 改用项目统一 Ui.dialog 圆角弹窗(系统 AlertDialog 是直角, 与全局视觉不符)
        // maxHeightRatio=0.72: 13 个输入框较长, 超出屏幕高度时内部滚动
        val (dlg, box) = Ui.dialog(this, if (isEdit) "编辑连接" else "添加连接", maxHeightRatio = 0.72)
        box.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Ui.primaryBtn(this, "保存") {
            val port = editTexts[2].text.toString().toIntOrNull() ?: 22
            val keyContent = keyStates[editTexts[5]]?.content ?: editTexts[5].text.toString()
            val proxyKeyContent = keyStates[editTexts[11]]?.content ?: editTexts[11].text.toString()
            val newCfg = SshConfigStore.SshConfig(
                name = editTexts[0].text.toString().trim(),
                host = editTexts[1].text.toString().trim(),
                port = port,
                user = editTexts[3].text.toString().trim(),
                password = editTexts[4].text.toString().ifEmpty { null },
                privateKey = keyContent.trim().ifEmpty { null },
                passphrase = editTexts[6].text.toString().ifEmpty { null },
                proxyHost = editTexts[7].text.toString().trim().ifEmpty { null },
                proxyPort = editTexts[8].text.toString().toIntOrNull() ?: 22,
                proxyUser = editTexts[9].text.toString().trim().ifEmpty { null },
                proxyPassword = editTexts[10].text.toString().ifEmpty { null },
                proxyPrivateKey = proxyKeyContent.trim().ifEmpty { null },
                proxyPassphrase = editTexts[12].text.toString().ifEmpty { null }
            )
            if (newCfg.name.isEmpty() || newCfg.host.isEmpty() || newCfg.user.isEmpty()) {
                Toast.makeText(this, "名称/主机/用户名必填", Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            if (isEdit) configs[index] = newCfg else configs.add(newCfg)
            SshConfigStore.save(this, configs)
            refreshList()
            dlg.dismiss()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        if (isEdit) {
            btnRow.addView(Ui.dangerBtn(this, "删除") {
                configs.removeAt(index)
                SshConfigStore.save(this, configs)
                refreshList()
                dlg.dismiss()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
        }
        box.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun openKeyFilePicker(field: EditText) {
        pendingKeyField = field
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT_KEY)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }

    /** 按折叠状态刷新私钥输入框: 折叠=只读单行省略号显示文件名+删除按钮, 展开=可编辑显示密钥内容 */
    private fun updateKeyField(et: EditText, btn: TextView?) {
        val st = keyStates[et] ?: return
        if (st.fileName == null) {
            btn?.visibility = View.INVISIBLE
            st.delBtn?.visibility = View.GONE
            return
        }
        btn?.visibility = View.VISIBLE
        st.suppress = true
        if (st.folded) {
            et.isEnabled = false
            et.setSingleLine(true)
            et.ellipsize = TextUtils.TruncateAt.END
            et.setText(st.fileName ?: "")
            st.delBtn?.visibility = View.VISIBLE
            btn?.text = "﹀"
        } else {
            et.isEnabled = true
            et.setSingleLine(false)
            et.ellipsize = null
            et.setMinLines(1)
            et.setMaxLines(Integer.MAX_VALUE)
            et.setText(st.content)
            st.delBtn?.visibility = View.GONE
            btn?.text = "︿"
        }
        st.suppress = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
