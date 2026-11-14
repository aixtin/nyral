package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 岗位详情页(2026-11-14)。
 *
 * 列表卡片只放得下三行, "目标到底写了什么 / 最近几次跑得怎样"必须有个地方摊开。
 * 页面顺序按用户真实问题排: 现在会不会跑(状态) -> 它要干什么(目标) -> 它能动什么(授权) -> 之前跑了没(台账)。
 * 底部只有一颗实心主按钮(立即执行), 删除降级为文字: 一屏只能有一个主 CTA。
 */
class TimerTaskDetailActivity : Activity() {

    private var taskId: Long = -1L
    private lateinit var db: TimerTaskDb
    private lateinit var contentBox: LinearLayout
    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        db = TimerTaskDb(this)
        taskId = intent.getLongExtra("task_id", -1L)

        val page = Ui.pageRoot(this)
        val bar = Ui.titleBar(this, "", onBack = { finish() })
        // 顶栏右侧"编辑": 文字入口, 与返回箭头同一条视觉基线
        bar.addView(TextView(this).apply {
            text = "编辑"
            textSize = 15f
            setTextColor(Ui.PRIMARY)
            setPadding(dp(10), dp(6), dp(6), dp(6))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                startActivity(Intent(this@TimerTaskDetailActivity, TimerTaskEditActivity::class.java)
                    .putExtra("task_id", taskId))
            }
        })
        page.addView(bar)
        // titleBar 会自己加一个占满剩余宽度的标题 TextView, 这里取出来用于刷新名称
        titleView = (bar.getChildAt(1) as TextView).apply { text = "岗位详情" }

        contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        page.addView(ScrollView(this).apply {
            addView(contentBox)
            isFillViewport = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部: 唯一实心主按钮 + 文字删除
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(Ui.SURFACE)
            addView(Ui.primaryBtn(this@TimerTaskDetailActivity, "立即执行") {
                db.get(taskId)?.let { TimerTaskUi.runNow(this@TimerTaskDetailActivity, it) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@TimerTaskDetailActivity).apply {
                text = "删除岗位"
                textSize = 13f
                setTextColor(Ui.DANGER)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(2))
                isClickable = true
                Ui.press(this)
                setOnClickListener {
                    val t = db.get(taskId) ?: return@setOnClickListener
                    TimerTaskUi.deleteTask(this@TimerTaskDetailActivity, db, t) { finish() }
                }
            })
        })

        setContentView(page)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val t = db.get(taskId)
        if (t == null) {
            finish()
            return
        }
        titleView.text = t.name
        contentBox.removeAllViews()

        // ===== 状态卡 =====
        contentBox.addView(Ui.card(this).apply {
            addView(LinearLayout(this@TimerTaskDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(LinearLayout(this@TimerTaskDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(Ui.itemTitle(this@TimerTaskDetailActivity, if (t.enabled) "启用中" else "已停用"))
                    addView(Ui.itemSub(this@TimerTaskDetailActivity, "停用后不再自动触发，授权保留"))
                })
                addView(Ui.switch(this@TimerTaskDetailActivity).apply {
                    isChecked = t.enabled
                    setOnCheckedChangeListener { on ->
                        if (on) {
                            TimerTaskUi.setEnabled(this@TimerTaskDetailActivity, db, t, true)
                            render()
                        } else {
                            confirmDisable(t) { render() }
                        }
                    }
                })
            })
            addView(Ui.divider(this@TimerTaskDetailActivity, 16))
            statusRow("下次运行", if (t.enabled) nextRunText(t) else "已停用",
                if (t.enabled) Ui.TEXT else Ui.SUB)
            addView(Ui.divider(this@TimerTaskDetailActivity, 16))
            val ok = TimerTaskUi.lastOk(t)
            statusRow(
                "上次结果",
                if (t.lastRunTs > 0L) TimerTaskUi.fmt(t.lastRunTs) + " · " + if (ok) "成功" else "失败" else "还没执行过",
                if (t.lastRunTs > 0L) (if (ok) Ui.OK else Ui.DANGER) else Ui.SUB
            )
        })

        // ===== 目标全文 =====
        contentBox.addView(Ui.groupLabel(this, "目标"))
        contentBox.addView(Ui.card(this).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@TimerTaskDetailActivity).apply {
                text = t.goal
                textSize = 14f
                setTextColor(Ui.TEXT)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        })

        // ===== 能力授权 =====
        contentBox.addView(Ui.groupLabel(this, "能力授权"))
        contentBox.addView(Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                TimerTaskUi.allowDialog(
                    this@TimerTaskDetailActivity,
                    "能力授权(${t.name})",
                    TimerTaskUi.allowKeys(t.allowTools)
                ) { s ->
                    db.updateAllowTools(t.id, s)
                    SecurityConfig.audit(
                        this@TimerTaskDetailActivity, "timer_task_allow",
                        "${t.name}(id=${t.id}) -> $s", "ui_user", "timer_task"
                    )
                    render()
                }
            }
            addView(LinearLayout(this@TimerTaskDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.itemTitle(this@TimerTaskDetailActivity, "预授权能力"))
                addView(TextView(this@TimerTaskDetailActivity).apply {
                    text = TimerTaskUi.allowLabel(t.allowTools)
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(Ui.arrow(this@TimerTaskDetailActivity))
        })

        // ===== 最近执行 =====
        contentBox.addView(Ui.groupLabel(this, "最近执行"))
        val runs = runCatching { db.recentRuns(30).filter { it.taskId == t.id }.take(5) }
            .getOrDefault(emptyList())
        contentBox.addView(Ui.card(this).apply {
            if (runs.isEmpty()) {
                addView(TextView(this@TimerTaskDetailActivity).apply {
                    text = "还没有执行记录"
                    textSize = 13f
                    setTextColor(Ui.SUB)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                })
            } else {
                runs.forEachIndexed { i, r ->
                    if (i > 0) addView(Ui.divider(this@TimerTaskDetailActivity, 16))
                    addView(runRow(r))
                }
            }
        })
    }

    private fun statusRow(label: String, value: String, color: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            addView(Ui.itemTitle(this@TimerTaskDetailActivity, label).apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@TimerTaskDetailActivity).apply {
                text = value
                textSize = 14f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
            })
        }

    /** 台账行: 成败图标 + 时间 + 摘要(失败才显示原因) */
    private fun runRow(r: TimerTaskDb.RunRecord): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(this@TimerTaskDetailActivity).apply {
                text = if (r.ok) "✓" else "!"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (r.ok) Ui.OK else Ui.DANGER)
                gravity = Gravity.CENTER
                width = dp(24)
            })
            addView(LinearLayout(this@TimerTaskDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), 0, 0, 0)
                addView(TextView(this@TimerTaskDetailActivity).apply {
                    text = TimerTaskUi.fmt(r.startTs)
                    textSize = 13f
                    setTextColor(Ui.TEXT)
                })
                val reason = r.result?.takeIf { !it.isNullOrBlank() }
                if (reason != null) addView(TextView(this@TimerTaskDetailActivity).apply {
                    text = reason
                    textSize = 12f
                    setTextColor(if (r.ok) Ui.SUB else Ui.DANGER)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            if (r.catchUp) addView(TextView(this@TimerTaskDetailActivity).apply {
                text = "补跑"
                textSize = 11f
                setTextColor(Ui.SUB)
                background = TimerTaskUi.capsule(this@TimerTaskDetailActivity, TimerTaskUi.tint(Ui.SUB), 9)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            })
        }

    private fun nextRunText(t: TimerTaskDb.Task): String {
        val at = TimerScheduler.nextRunAt(t.cronDesc)
        if (at <= 0L) return "—"
        val isToday = TimerTaskUi.fmt(at, "yyyyMMdd") == TimerTaskUi.fmt(System.currentTimeMillis(), "yyyyMMdd")
        return (if (isToday) "今天 " else "明天 ") + TimerTaskUi.fmt(at, "HH:mm")
    }

    /** 停用确认: 授权会被暂停, 需要让用户知道代价; 取消时把开关拨回原位 */
    private fun confirmDisable(t: TimerTaskDb.Task, onDismiss: () -> Unit) {
        val auth = TimerTaskUi.allowKeys(t.allowTools)
        val msg = buildString {
            append("停用「${t.name}」后不再触发, 预授权能力暂停使用(重新启用后恢复)。")
            if (auth.isNotEmpty()) {
                append("\n\n当前能力授权(${auth.size} 项):\n")
                append(auth.mapNotNull { SecurityConfig.ALLOW_CATALOG[it] }.joinToString("、"))
            }
        }
        val (dlg, box) = Ui.dialog(this, "停用岗位")
        box.addView(Ui.dialogText(this, msg))
        box.addView(Ui.dangerBtn(this, "停用") {
            dlg.dismiss()
            TimerTaskUi.setEnabled(this, db, t, false)
            onDismiss()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") {
            dlg.dismiss()
            onDismiss()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
