package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 手机体检 — 只读诊断结果页。
 *
 * 说明:
 *  - 全部采集由 Diagnostics 完成, 本页只负责展示, 不做任何写操作。
 *  - 逐项异步采集、逐项回填, 避免一项卡住拖住整页。
 *  - 等级不足的项不隐藏, 明确显示"缺什么通道、怎么解锁", 避免误以为系统无此能力。
 */
class DiagnosticsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var levelTitle: TextView
    private lateinit var levelSub: TextView
    private lateinit var startBtn: TextView
    private val cards = LinkedHashMap<String, LinearLayout>()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "手机体检"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // 特权环境卡
        content.addView(Ui.card(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@DiagnosticsActivity).apply {
                text = "当前采集体检等级"
                textSize = 12f
                setTextColor(Ui.SUB)
            })
            addView(TextView(this@DiagnosticsActivity).apply {
                text = "检测中…"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.TEXT)
                setPadding(0, dp(4), 0, 0)
                levelTitle = this
            })
            addView(TextView(this@DiagnosticsActivity).apply {
                text = ""
                textSize = 11.5f
                setTextColor(Ui.SUB)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(6), 0, 0)
                levelSub = this
            })
        })

        // 说明
        content.addView(TextView(this).apply {
            text = "体检全程只读：不修改设置、不写文件、不执行任何写命令。采集通道与 Agent 的 shell 执行完全隔离。"
            textSize = 11f
            setTextColor(Ui.SUB)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        })

        startBtn = Ui.primaryBtn(this, "开始体检") { startCheck() }
        content.addView(startBtn)

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(listBox)

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚去装完 Shizuku / 授完权, 回来即刷新等级
        refreshLevel()
    }

    private fun refreshLevel() {
        Thread {
            Diagnostics.resetPrivilegeCache()
            val lv = Diagnostics.currentLevel()
            runOnUiThread {
                levelTitle.text = Diagnostics.levelName(lv)
                levelSub.text = when (lv) {
                    2 -> "已具备 root 通道, 可读取全部体检项(含模块与 SELinux 状态)。"
                    1 -> "Shizuku 通道可用, 可读取系统级数据(thermal zone 等)。root 档仍需 su。"
                    else -> "仅有公开 API 权限。安装 Shizuku 并授权后可解锁系统级诊断(无需 root)。"
                }
                if (running) startBtn.text = "重新体检"
            }
        }.start()
    }

    private fun startCheck() {
        if (running) return
        running = true
        startBtn.text = "体检中…"
        startBtn.alpha = 0.6f
        cards.clear()
        listBox.removeAllViews()

        // 先铺占位卡
        for (it in Diagnostics.ITEMS) {
            val card = buildCard(it)
            cards[it.id] = card
            listBox.addView(card)
        }

        Thread {
            val lv = Diagnostics.currentLevel()
            runOnUiThread {
                levelTitle.text = Diagnostics.levelName(lv)
                levelSub.text = when (lv) {
                    2 -> "已具备 root 通道, 可读取全部体检项(含模块与 SELinux 状态)。"
                    1 -> "Shizuku 通道可用, 可读取系统级数据(thermal zone 等)。root 档仍需 su。"
                    else -> "仅有公开 API 权限。安装 Shizuku 并授权后可解锁系统级诊断(无需 root)。"
                }
            }
            for (it in Diagnostics.ITEMS) {
                val r = Diagnostics.run(this, it.id)
                runOnUiThread { fillCard(cards[it.id], r) }
            }
            runOnUiThread {
                running = false
                startBtn.text = "重新体检"
                startBtn.alpha = 1f
            }
        }.start()
    }

    private fun buildCard(it: Diagnostics.Item): LinearLayout = Ui.card(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = dp(10)
        layoutParams = params
        addView(TextView(this@DiagnosticsActivity).apply {
            text = it.title + "   " + levelTag(it.level)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
        })
        addView(TextView(this@DiagnosticsActivity).apply {
            text = "检测中…"
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(0, dp(6), 0, 0)
            tag = "body"
        })
    }

    private fun levelTag(lv: Int): String = when (lv) {
        2 -> "[L2]"
        1 -> "[L1]"
        else -> "[L0]"
    }

    private fun fillCard(card: LinearLayout?, r: Diagnostics.Result) {
        if (card == null) return
        val body = card.findViewWithTag<TextView>("body") ?: return
        if (!r.ok) {
            body.text = "跳过: " + (if (r.error.isNotBlank()) r.error else r.verdict) +
                    (if (r.hint.isNotBlank()) "\n" + r.hint else "")
            body.setTextColor(Color.parseColor("#B07800"))
            return
        }
        val sb = StringBuilder()
        sb.append(r.verdict)
        for (l in r.lines) sb.append('\n').append(l)
        if (r.hint.isNotBlank()) sb.append("\n\n").append(r.hint)
        body.text = sb.toString()
        val calm = r.verdict.contains("正常") || r.verdict.contains("充足") ||
                r.verdict.contains("无异常") || r.verdict.contains("仅有")
        body.setTextColor(if (calm) Ui.TEXT else Color.parseColor("#B07800"))
        body.setLineSpacing(0f, 1.3f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
