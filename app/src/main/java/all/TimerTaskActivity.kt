package io.github.aixtin.nyral

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
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
 * 全部代码式 UI, 与 AuditActivity/SecurityCenterActivity 风格一致。
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F3F7.toInt())
            // targetSdk 36 强制 edge-to-edge(同 Ui.pageRoot): 消费状态栏 inset, 顶栏避开系统状态栏
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                    v.setPadding(0, top, 0, 0)
                    insets
                }
            }
        }

        // 顶栏: 返回 + 标题 + 新建
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(16), dp(10))
            background = rounded(dp(0), 0xFFFFFFFF.toInt())
        }
        titleBar.addView(TextView(this).apply {
            text = "‹ 返回"
            textSize = 16f
            setTextColor(0xFF3D7FFF.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        })
        titleBar.addView(TextView(this).apply {
            text = "任务"
            textSize = 17f
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleBar.addView(TextView(this).apply {
            text = "＋ 新建"
            textSize = 16f
            setTextColor(0xFF3D7FFF.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showCreateDialog() }
        })
        root.addView(titleBar)

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
                setTextColor(0xFF9A9A9A.toInt())
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
            background = rounded(dp(12), 0xFFFFFFFF.toInt())
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
                setTextColor(0xFF1A1A1A.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@TimerTaskActivity).apply {
                text = if (t.enabled) "启用中" else "已停用"
                textSize = 12f
                setTextColor(if (t.enabled) 0xFF34A853.toInt() else 0xFF9A9A9A.toInt())
                background = rounded(dp(10), if (t.enabled) 0x1A34A853.toInt() else 0x1A9A9A9A.toInt())
                setPadding(dp(10), dp(3), dp(10), dp(3))
            })
        })
        // 第二行: 触发时间
        card.addView(TextView(this).apply {
            text = "触发: ${t.cronDesc}"
            textSize = 13f
            setTextColor(0xFF5A5A5A.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        // 第二行半: 能力授权状态(2026-10-13)
        card.addView(TextView(this).apply {
            text = "授权: " + allowLabel(t.allowTools)
            textSize = 12f
            setTextColor(0xFF8A8A8A.toInt())
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        })
        // 第三行: 目标(单行截断)
        card.addView(TextView(this).apply {
            text = "目标: ${t.goal}"
            textSize = 13f
            setTextColor(0xFF5A5A5A.toInt())
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
                setTextColor(0xFF9A9A9A.toInt())
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            })
        }
        return card
    }

    private fun showActionDialog(t: TimerTaskDb.Task) {
        val items = ArrayList<String>()
        items.add("立即执行")
        items.add(if (t.enabled) "停用" else "启用")
        items.add("能力授权")
        items.add("删除")
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> runNow(t)
                    1 -> {
                        if (t.enabled) disable(t) else enable(t)
                    }
                    2 -> {
                        val cur = t.allowTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        showAllowToolsDialog("能力授权(${t.name})", cur) { s ->
                            db.updateAllowTools(t.id, s)
                            SecurityConfig.audit(this, "timer_task_allow", "${t.name}(id=${t.id}) -> $s", "ui_user", "timer_task")
                            render()
                        }
                    }
                    3 -> delete(t)
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("停用任务")
            .setMessage(msg)
            .setPositiveButton("停用") { _, _ ->
                db.setEnabled(t.id, false)
                TimerScheduler.cancel(this, t.id)
                SecurityConfig.audit(this, "timer_task_disable", "${t.name}(id=${t.id})", "ui_user", "timer_task")
                TimerKeepAliveService.ensure(this)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ ->
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
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreateDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val nameEt = EditText(this).apply {
            hint = "任务名(如: 早报整理)"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(0xFF1A1A1A.toInt())
            setHintTextColor(0xFF9A9A9A.toInt())
        }
        val goalEt = EditText(this).apply {
            hint = "目标(要 AI 做什么)"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setTextColor(0xFF1A1A1A.toInt())
            setHintTextColor(0xFF9A9A9A.toInt())
        }
        val cronEt = EditText(this).apply {
            hint = "触发时间(每天 HH:mm, 如 09:00)"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setTextColor(0xFF1A1A1A.toInt())
            setHintTextColor(0xFF9A9A9A.toInt())
        }
        // 能力预授权(2026-10-13): 勾选危险工具白名单, 静默执行时直接放行
        var allowTools = ""
        val allowTv = TextView(this).apply {
            text = "能力授权: 未授权(点击配置)"
            textSize = 13f
            setTextColor(0xFF3D7FFF.toInt())
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                showAllowToolsDialog("能力授权(危险工具)", emptySet()) { s ->
                    allowTools = s
                    text = "能力授权: " + allowLabel(s)
                }
            }
        }
        box.addView(nameEt)
        box.addView(goalEt)
        box.addView(cronEt)
        box.addView(allowTv)
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建任务")
            .setView(box)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEt.text.toString().trim()
                val goal = goalEt.text.toString().trim()
                val cron = cronEt.text.toString().trim()
                if (name.isEmpty() || goal.isEmpty() || cron.isEmpty()) {
                    Toast.makeText(this, "三项都要填写", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val fullCron = if (cron.startsWith("每天")) cron else "每天 $cron"
                if (TimerScheduler.parseCron(fullCron) == null) {
                    Toast.makeText(this, "触发时间格式: 每天 HH:mm(如 09:00)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
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
            }
        }
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(TextView(context).apply {
                text = "勾选后, 任务静默执行时可直接使用这些危险工具, 不再逐次确认; 未勾选的仍会自动拒绝。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF888888.toInt())
            })
            keys.forEachIndexed { i, k ->
                addView(CheckBox(context).apply {
                    text = SecurityConfig.ALLOW_CATALOG[k] + "  ($k)"
                    isChecked = checked[i]
                    setOnCheckedChangeListener { _, b -> checked[i] = b }
                })
            }
        }
        val sc = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton("保存") { _, _ ->
                val picked = keys.filterIndexed { i, _ -> checked[i] }
                onSave(picked.joinToString(","))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(r: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(r).toFloat()
            setColor(color)
        }
}
