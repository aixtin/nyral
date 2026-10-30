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
    private lateinit var authBtn: TextView
    private val cards = LinkedHashMap<String, LinearLayout>()
    private var running = false
    /** 本次体检绑定的会话(2026-10-30): 连续体检复用同一条, 结果只追加摘要卡 */
    private var sessionId = 0L
    private var checkMode = 0

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

        // Shizuku 已运行但本应用未授权时, 提供一键触发授权弹窗(免 root)
        authBtn = Ui.lightBtn(this, "授权 Shizuku（免 root, adb 级）") {
            val asked = Diagnostics.requestShizukuPermission()
            Toast.makeText(
                this@DiagnosticsActivity,
                if (asked) "已具备 Shizuku 权限" else "请在弹窗中选择「允许」",
                Toast.LENGTH_SHORT
            ).show()
        }.apply {
            visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(authBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

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
        // Shizuku 的 binder 查询放主线程: 后台线程首次绑定可能拿不到 binder
        val sz = Diagnostics.shizukuState()
        Thread {
            Diagnostics.resetPrivilegeCache()
            val lv = Diagnostics.currentLevel()
            runOnUiThread { applyPrivilege(lv, sz) }
        }.start()
    }

    /** 统一渲染特权等级与 Shizuku 状态(等级卡 + 授权按钮) */
    private fun applyPrivilege(lv: Int, sz: Int) {
        levelTitle.text = Diagnostics.levelName(lv)
        val base = when (lv) {
            2 -> "已具备 root 通道, 可读取全部体检项(含模块与 SELinux 状态)。"
            1 -> "Shizuku 通道可用, 可读取系统级数据(thermal zone 等)。root 档仍需 su。"
            else -> "仅有公开 API 权限。安装 Shizuku 并授权后可解锁系统级诊断(无需 root)。"
        }
        levelSub.text = if (lv == 2) base else base + "\n" + when (sz) {
            2 -> "Shizuku: 已授权"
            1 -> "Shizuku: 已运行, 本应用未授权"
            else -> "Shizuku: 未安装或未运行"
        }
        authBtn.visibility = if (sz == 1) View.VISIBLE else View.GONE
        if (running) startBtn.text = "重新体检"
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

        val sz = Diagnostics.shizukuState()
        // 体检会话(2026-10-30): 进页面即绑定/新建 kind=2 会话, 报告只写进这条会话
        checkMode = ModeConfig.modeValue()
        sessionId = DiagSession.openOrResume(this, checkMode)
        Thread {
            val lv = Diagnostics.currentLevel()
            runOnUiThread { applyPrivilege(lv, sz) }
            val results = LinkedHashMap<String, Diagnostics.Result>()
            for (it in Diagnostics.ITEMS) {
                val r = Diagnostics.run(this, it.id)
                results[it.id] = r
                runOnUiThread { fillCard(cards[it.id], r) }
            }
            // 全部采集完成再落档案, 避免半途中断留下残缺报告
            saveArchive(results, lv)
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


    /** 体检完成: 落档案 + 往体检会话追加摘要卡(2026-10-30 P2) */
    private fun saveArchive(results: Map<String, Diagnostics.Result>, level: Int) {
        if (results.isEmpty()) return
        try {
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val ok = results.values.count { it.ok && calm(it.verdict) }
            val fail = results.size - ok
            val abn = results.values.filter { !(it.ok && calm(it.verdict)) }

            val digest = StringBuilder()
            digest.append("体检时间: ").append(time)
            digest.append("\n采集等级: ").append(Diagnostics.levelName(level))
            digest.append("\n结果: 正常 ").append(ok).append(" 项, 需关注 ").append(fail).append(" 项")
            if (abn.isEmpty()) {
                digest.append("\n异常项: 无")
            } else {
                digest.append("\n需关注项:")
                abn.forEach { r ->
                    digest.append("\n- ").append(r.title).append(": ")
                    digest.append(
                        when {
                            !r.ok -> if (r.error.isNotBlank()) "无法采集(" + r.error + ")" else "跳过"
                            else -> r.verdict.ifBlank { "异常" }
                        }
                    )
                    if (r.hint.isNotBlank()) digest.append(" / 处理: ").append(r.hint.replace("\n", " "))
                }
            }

            val detail = StringBuilder()
            results.values.forEach { r ->
                detail.append("[").append(r.title).append("]")
                detail.append(if (r.ok) "\n结论: " + r.verdict else "\n结论: 跳过 " + r.error)
                if (r.ok) r.lines.forEach { detail.append("\n  ").append(it) }
                if (r.hint.isNotBlank()) detail.append("\n提示: ").append(r.hint)
                detail.append("\n\n")
            }

            val db = MemoryDb(this)
            val id = db.saveDiagArchive(level, ok, fail, digest.toString(), detail.toString(), sessionId)
            val card = "【手机体检报告 #" + id + "】\n" + digest + "\n\n完整明细已存入体检档案 #" + id +
                    ", 需要回查历史报告时引用该编号即可; 报告正文不占用会话上下文。"
            DiagSession.appendReport(this, sessionId, checkMode, card)
        } catch (e: Exception) {
            android.util.Log.w("Diagnostics", "档案落库失败: " + e.message)
        }
    }

    /** 与 fillCard 一致的"平静"判据: 结论不含告警词才算正常项 */
    private fun calm(verdict: String): Boolean =
        verdict.contains("正常") || verdict.contains("充足") ||
            verdict.contains("无异常") || verdict.contains("仅有")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
