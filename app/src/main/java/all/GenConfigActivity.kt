package io.github.aixtin.nyral

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * AI 生成设置(2026-11-04 v2 契约版): 多厂商配置列表。
 * - 每个厂商一条契约配置(端点/鉴权/模式/取图路径), Key 独立加密存本机
 * - "添加厂商"进入编辑页: 粘贴官方示例自动解析 + 测试连接
 * - 2026-11-04 UI 优化: 图标徽章行 + 主题化开关 + 圆角操作按钮 + 额度进度条, 对齐设置页风格
 */
class GenConfigActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private lateinit var listContainer: LinearLayout
    private lateinit var etQuota: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "AI 生成设置"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        content.addView(hintBlock())

        // 厂商列表容器
        content.addView(Ui.groupLabel(this, "厂商列表"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        content.addView(addVendorBtn())

        // 月度限额: 输入框 + 行内保存按钮
        content.addView(Ui.groupLabel(this, "月度限额"))
        val cardQuota = Ui.card(this)
        val quotaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etQuota = Ui.input(this, "每月最多生成张数(默认 100)").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
        }
        etQuota.setText(GenConfig.quotaMax(this).toString())
        quotaRow.addView(etQuota, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(12), dp(6), dp(8), dp(6)) })
        quotaRow.addView(Ui.lightBtn(this, "保存") { saveQuota() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(10) })
        cardQuota.addView(quotaRow)
        content.addView(cardQuota)

        // 使用统计
        content.addView(Ui.groupLabel(this, "本月已用"))
        val used = GenConfig.monthUsed(this)
        val max = GenConfig.quotaMax(this).coerceAtLeast(1)
        val pct = (used.toFloat() / max).coerceIn(0f, 1f)
        val cardStat = Ui.card(this)
        cardStat.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(Ui.itemTitle(this@GenConfigActivity, "已生成 $used 张"))
            addView(usageBar(pct))
            addView(Ui.hint(this@GenConfigActivity, "剩余额度 ${max - used} 张 · 按自然月滚动"))
        })
        content.addView(cardStat)

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    /** 顶部说明: 精简为一行主句 + 两条要点, 去掉大段灰字 */
    private fun hintBlock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, dp(2), dp(8))
        addView(Ui.hint(this@GenConfigActivity, "多个厂商可并存使用, 粘贴官方示例即可自动识别协议。"))
        addView(Ui.hint(this@GenConfigActivity, "· 调用前弹出成本确认, 限额耗尽自动拒绝, 防 AI 失控花钱"))
        addView(Ui.hint(this@GenConfigActivity, "· Key 仅加密保存在本机(AndroidKeyStore), 不随对话上传"))
    }

    /** 添加厂商: 与卡片同宽的浅色圆角按钮, 替代裸文本 */
    private fun addVendorBtn(): View = TextView(this).apply {
        text = "+  添加厂商"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Ui.PRIMARY)
        background = Ui.rounded(Ui.PRIMARY_LIGHT, 14, this@GenConfigActivity)
        setPadding(0, dp(12), 0, dp(12))
        Ui.press(this)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
        setOnClickListener {
            startActivity(Intent(this@GenConfigActivity, GenConfigEditActivity::class.java)
                .putExtra("entry_id", ""))
        }
    }

    /** 使用进度条: 圆角轨道 + 主色填充 */
    private fun usageBar(pct: Float): View = object : View(this@GenConfigActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            p.color = Ui.INPUT_BG
            canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, p)
            if (pct > 0f) {
                p.color = Ui.PRIMARY
                val fw = (w * pct).coerceAtLeast(h)
                canvas.drawRoundRect(0f, 0f, fw, h, h / 2f, h / 2f, p)
            }
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) }
    }

    /** 行内保存月度限额 */
    private fun saveQuota() {
        val q = etQuota.text.toString().trim().toIntOrNull() ?: 100
        GenConfig.setQuotaMax(this, q)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val list = GenConfig.configs(this)
        if (list.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "还没有厂商配置, 点下方「+ 添加厂商」粘贴官方示例开始。"
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(dp(2), dp(6), dp(2), dp(6))
            })
            return
        }
        list.forEach { e -> listContainer.addView(buildEntryCard(e)) }
    }

    private fun buildEntryCard(e: GenConfig.Entry): View {
        val card = Ui.card(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        // 行1: 图标徽章 + 名称/默认标记 + 元信息 + 启用开关
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(Ui.iconBadgeRes(this, R.drawable.ic_settings_model, 0, 40))
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(this).apply {
            text = e.displayName.ifBlank { "(未命名厂商)" }
            textSize = 15f
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(6)
        })
        if (e.isDefault) {
            nameRow.addView(TextView(this).apply {
                text = "默认"
                textSize = 10f
                setTextColor(Ui.PRIMARY)
                background = Ui.rounded(Ui.PRIMARY_LIGHT, 8, this@GenConfigActivity)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
        nameCol.addView(nameRow)
        nameCol.addView(Ui.itemSub(this,
            "${e.contract.modelId.ifBlank { "未填模型" }} · ${e.contract.provider.ifBlank { "未知平台" }} · Key ${if (GenConfig.keyOf(this, e).isBlank()) "未填" else "已填"}"))
        row1.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
            rightMargin = dp(8)
        })
        val sw = Ui.switch(this).apply { isChecked = e.enabled }
        sw.setOnCheckedChangeListener { on ->
            GenConfig.addOrUpdate(this@GenConfigActivity, e.copy(enabled = on))
        }
        row1.addView(sw)
        col.addView(row1)
        // 行2: 操作按钮
        col.addView(Ui.divider(this).let { dv ->
            ((dv.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                .topMargin = dp(10)
            dv
        })
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row3.addView(smallBtn("编辑", false) {
            startActivity(Intent(this@GenConfigActivity, GenConfigEditActivity::class.java)
                .putExtra("entry_id", e.id))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row3.addView(smallBtn("测试", false) {
            val k = GenConfig.keyOf(this@GenConfigActivity, e)
            if (k.isBlank()) {
                Toast.makeText(this@GenConfigActivity, "请先填写 Key 再测试", Toast.LENGTH_SHORT).show()
                return@smallBtn
            }
            Toast.makeText(this@GenConfigActivity, "测试中...", Toast.LENGTH_SHORT).show()
            Thread {
                val r = GenTools.testConnection(this@GenConfigActivity, e, k)
                runOnUiThread {
                    Toast.makeText(this@GenConfigActivity, r.take(160), Toast.LENGTH_LONG).show()
                }
            }.start()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row3.addView(smallBtn("删除", true) { confirmDelete(e) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row3)
        card.addView(col)
        return card
    }

    /** 删除确认: 防误触 */
    private fun confirmDelete(e: GenConfig.Entry) {
        val (dlg, box) = Ui.dialog(this, "删除厂商")
        box.addView(Ui.dialogText(this,
            "确定删除「${e.displayName.ifBlank { "未命名厂商" }}」吗?\n删除后需要重新配置才能恢复。"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        row.addView(Ui.dialogCancelBtn(this, "取消", { dlg.dismiss() }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(TextView(this).apply {
            text = "删除"
            textSize = 15f
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.rounded(Ui.DANGER, 14, this@GenConfigActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            Ui.press(this)
            setOnClickListener {
                dlg.dismiss()
                GenConfig.delete(this@GenConfigActivity, e.id)
                refreshList()
                Toast.makeText(this@GenConfigActivity, "已删除", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        box.addView(row)
        dlg.show()
    }

    /** 圆角行内操作按钮: 编辑/测试 浅蓝底, 删除 浅红底 */
    private fun smallBtn(text: String, danger: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        isClickable = true
        gravity = Gravity.CENTER
        setTextColor(if (danger) Ui.DANGER else Ui.PRIMARY)
        background = Ui.rounded(if (danger) Ui.DANGER_LIGHT else Ui.PRIMARY_LIGHT, 12, this@GenConfigActivity)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        Ui.press(this)
        setOnClickListener { onClick() }
    }
}
