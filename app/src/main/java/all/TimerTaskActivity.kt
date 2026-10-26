package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 定时岗位列表页(2026-10-11): MVP 简单列表。
 * - 顶部: 返回 + 标题 + "＋ 新建任务"
 * - 列表: 任务名 / 触发时间 / 开关 / 上次执行结果
 * - 每项点击: 操作菜单(立即执行 / 停用-启用 / 删除)
 * 全部代码式 UI, 2026-10-27 整页接入 Ui.* 主题规范(语义色随 7 套主题 + 自绘圆角弹窗)。
 */
class TimerTaskActivity : Activity() {

    private lateinit var db: TimerTaskDb
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        ModeConfig.init(this)
        db = TimerTaskDb(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = Ui.pageRoot(this)

        // 顶栏: 返回(lucide 箭头) + 标题 + 右侧"＋ 新建"
        root.addView(Ui.titleBar(this, "任务") { bar ->
            bar.addView(TextView(this).apply {
                text = "＋ 新建"
                textSize = 15f
                setTextColor(Ui.PRIMARY)
                isClickable = true
                setPadding(dp(10), dp(6), dp(4), dp(6))
                Ui.press(this)
                setOnClickListener { showCreateDialog() }
            })
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        scroll.addView(listBox)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 保活三件套: 进页面兜底同步常驻 FGS(有启用任务则拉起, 全停则停)
        TimerKeepAliveService.ensure(this)
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val tasks = db.all()
        if (tasks.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "暂无任务\n点击右上角「＋ 新建」创建"
                textSize = 14f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            })
            return
        }
        for (t in tasks) {
            listBox.addView(taskCard(t))
            listBox.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            })
        }
    }

    private fun taskCard(t: TimerTaskDb.Task): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(dp(12), Ui.SURFACE)
            isClickable = true
            setOnClickListener { showActionDialog(t) }
        }
        // 第一行: 任务名 + 状态徽标
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@TimerTaskActivity).apply {
                text = t.name
                textSize = 16f
                setTextColor(Ui.TEXT)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@TimerTaskActivity).apply {
                text = if (t.enabled) "启用中" else "已停用"
                textSize = 12f
                // 状态徽标: 同色系半透明底 + 同色文字, 浅色/暗色主题下都可读
                setTextColor(if (t.enabled) 0xFF34A853.toInt() else Ui.SUB)
                background = rounded(dp(10), if (t.enabled) 0x1A34A853.toInt() else (Ui.SUB and 0x00FFFFFF or 0x1A000000.toInt()))
                setPadding(dp(10), dp(3), dp(10), dp(3))
            })
        })
        // 第二行: 触发时间
        card.addView(TextView(this).apply {
            text = "触发: ${t.cronDesc}"
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, dp(6), 0, 0)
        })
        // 第二行半: 能力授权状态(2026-10-13)
        card.addView(TextView(this).apply {
            text = "授权: " + allowLabel(t.allowTools)
            textSize = 12f
            setTextColor(Ui.SUB)
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        })
        // 第三行: 目标(单行截断)
        card.addView(TextView(this).apply {
            text = "目标: ${t.goal}"
            textSize = 13f
            setTextColor(Ui.SUB)
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        })
        // 第四行: 上次执行结果
        if (t.lastRunTs > 0) {
            card.addView(TextView(this).apply {
                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(t.lastRunTs))
                text = "上次(${time}): ${t.lastResult ?: "执行中/无结果"}"
                textSize = 12f
                setTextColor(Ui.SUB)
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            })
        }
        return card
    }

    private fun showActionDialog(t: TimerTaskDb.Task) {
        val (dlg, box) = Ui.dialog(this, t.name)
        fun row(label: String, danger: Boolean = false, onClick: () -> Unit) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                setTextColor(if (danger) Ui.DANGER else Ui.PRIMARY)
                background = Ui.rounded(if (danger) Ui.DANGER_LIGHT else Ui.PRIMARY_LIGHT, 12, this@TimerTaskActivity)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                Ui.press(this)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (danger) dp(14) else dp(10)
            })
        }
        row("立即执行") { dlg.dismiss(); runNow(t) }
        row(if (t.enabled) "停用" else "启用") {
            dlg.dismiss()
            if (t.enabled) disable(t) else enable(t)
        }
        row("能力授权") {
            dlg.dismiss()
            val cur = t.allowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            showAllowToolsDialog("能力授权(${t.name})", cur) { s ->
                db.updateAllowTools(t.id, s)
                SecurityConfig.audit(this, "timer_task_allow", "${t.name}(id=${t.id}) -> $s", "ui_user", "timer_task")
                render()
            }
        }
        row("删除", danger = true) { dlg.dismiss(); delete(t) }
        dlg.show()
    }

    private fun runNow(t: TimerTaskDb.Task) {
        val svc = Intent(this, TaskService::class.java)
            .setAction(TaskService.ACTION_TIMER_TASK)
            .putExtra("task_id", t.id)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        Toast.makeText(this, "已触发「${t.name}」静默执行", Toast.LENGTH_SHORT).show()
    }

    private fun disable(t: TimerTaskDb.Task) {
        val auth = t.allowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val msg = buildString {
            append("停用「${t.name}」后不再触发, 预授权能力暂停使用(重新启用后恢复)。")
            if (auth.isNotEmpty()) {
                append("\n\n当前能力授权(${auth.size} 项):\n")
                append(auth.mapNotNull { SecurityConfig.ALLOW_CATALOG[it] }.joinToString("、"))
            }
        }
        val (dlg, box) = Ui.dialog(this, "停用任务")
        box.addView(Ui.dialogText(this, msg))
        box.addView(Ui.dangerBtn(this, "停用") {
            dlg.dismiss()
            db.setEnabled(t.id, false)
            TimerScheduler.cancel(this, t.id)
            SecurityConfig.audit(this, "timer_task_disable", "${t.name}(id=${t.id})", "ui_user", "timer_task")
            TimerKeepAliveService.ensure(this)
            render()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun enable(t: TimerTaskDb.Task) {
        db.setEnabled(t.id, true)
        TimerScheduler.schedule(this, t.id, t.cronDesc)
        SecurityConfig.audit(this, "timer_task_enable", "${t.name}(id=${t.id})", "ui_user", "timer_task")
        TimerKeepAliveService.ensure(this)
        render()
    }

    private fun delete(t: TimerTaskDb.Task) {
        val auth = t.allowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val msg = buildString {
            append("删除「${t.name}」后不再触发, 确定?\n")
            if (auth.isNotEmpty()) {
                append("\n将同步撤回以下能力授权(${auth.size} 项):\n")
                append(auth.mapNotNull { SecurityConfig.ALLOW_CATALOG[it] }.joinToString("、"))
                append("\n\n任务专属会话一并清理, 授权范围=任务生命周期。")
            }
        }
        val (dlg, box) = Ui.dialog(this, "删除任务")
        box.addView(Ui.dialogText(this, msg))
        box.addView(Ui.dangerBtn(this, "删除") {
            dlg.dismiss()
            db.delete(t.id)
            TimerScheduler.cancel(this, t.id)
            SecurityConfig.audit(this, "timer_task_delete", "${t.name}(id=${t.id})", "ui_user", "timer_task")
            if (t.sessionId > 0L) {
                // 级联清理任务专属会话(2026-10-16): 删除任务时同步清理 MemoryDb 绑定的 session 与其消息, 避免孤儿会话
                MemoryDb(this).deleteSession(t.sessionId)
                SecurityConfig.audit(
                    this, "timer_task_session_cleanup",
                    "${t.name}(session=${t.sessionId})", "ui_user", "timer_task"
                )
            }
            TimerKeepAliveService.ensure(this)
            render()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun showCreateDialog() {
        val (dialog, box) = Ui.dialog(this, "新建任务", maxHeightRatio = 0.8)
        val nameEt = Ui.input(this, "任务名(如: 早报整理)").apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val goalEt = Ui.input(this, "目标(要 AI 做什么)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        val cronEt = Ui.input(this, "触发时间(每天 HH:mm, 如 09:00)").apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        // 能力预授权(2026-10-13): 勾选危险工具白名单, 静默执行时直接放行
        var allowTools = ""
        val allowTv = TextView(this).apply {
            text = "能力授权: 未授权(点击配置)"
            textSize = 13f
            setTextColor(Ui.PRIMARY)
            isClickable = true
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                showAllowToolsDialog("能力授权(危险工具)", emptySet()) { s ->
                    allowTools = s
                    text = "能力授权: " + allowLabel(s)
                }
            }
        }
        fun field(v: View, top: Int) {
            box.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(top)
            })
        }
        field(nameEt, 4)
        field(goalEt, 10)
        field(cronEt, 10)
        field(allowTv, 4)
        box.addView(Ui.primaryBtn(this, "创建") {
            val name = nameEt.text.toString().trim()
            val goal = goalEt.text.toString().trim()
            val cron = cronEt.text.toString().trim()
            if (name.isEmpty() || goal.isEmpty() || cron.isEmpty()) {
                Toast.makeText(this, "三项都要填写", Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            val fullCron = if (cron.startsWith("每天")) cron else "每天 $cron"
            if (TimerScheduler.parseCron(fullCron) == null) {
                Toast.makeText(this, "触发时间格式: 每天 HH:mm(如 09:00)", Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            // 每开启一个任务即开启一个新会话绑定它(2026-10-13): 会话按创建时模式归属, 聊天/Agent 两模式互不串
            val mode = ModeConfig.modeValue()
            val id = db.insert(name, goal, fullCron, mode, allowTools)
            val sessionId = MemoryDb(this).saveSession(name, emptyList(), mode)
            db.bindSession(id, sessionId)
            TimerScheduler.schedule(this, id, fullCron)
            SecurityConfig.audit(this, "timer_task_register", "$name / $goal", "ui_user", "timer_task")
            TimerKeepAliveService.ensure(this)
            dialog.dismiss()
            render()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dialog.show()
    }

    /** 能力授权摘要文案(2026-10-13) */
    private fun allowLabel(keys: String): String {
        val set = keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (set.isEmpty()) return "未授权任何危险工具"
        val names = SecurityConfig.ALLOW_CATALOG.filterKeys { it in set }.values
        return if (names.size == set.size) "已授权 ${set.size} 项: " + names.joinToString("、")
        else "已授权 ${set.size} 项"
    }

    /** 能力授权多选对话框(2026-10-13): 勾选危险工具白名单, 保存为逗号分隔 key */
    private fun showAllowToolsDialog(title: String, current: Set<String>, onSave: (String) -> Unit) {
        val keys = SecurityConfig.ALLOW_CATALOG.keys.toList()
        val checked = keys.map { it in current }.toBooleanArray()
        val (dlg, box) = Ui.dialog(this, title, maxHeightRatio = 0.75)
        box.addView(TextView(this).apply {
            text = "勾选后, 任务静默执行时可直接使用这些危险工具, 不再逐次确认; 未勾选的仍会自动拒绝。"
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, 0, 0, dp(4))
        })
        keys.forEachIndexed { i, k ->
            box.addView(CheckBox(this).apply {
                text = SecurityConfig.ALLOW_CATALOG[k] + "  ($k)"
                textSize = 14f
                setTextColor(Ui.TEXT)
                isChecked = checked[i]
                setOnCheckedChangeListener { _, b -> checked[i] = b }
            })
        }
        box.addView(Ui.primaryBtn(this, "保存") {
            dlg.dismiss()
            val picked = keys.filterIndexed { i, _ -> checked[i] }
            onSave(picked.joinToString(","))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(r: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(r).toFloat()
            setColor(color)
        }
}
