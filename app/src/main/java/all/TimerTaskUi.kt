package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 定时岗位三页(列表 / 详情 / 编辑)共用的交互与文案(2026-11-14)。
 *
 * 抽出来的原因: "立即执行 / 启停 / 删除 / 能力授权"原本只有列表页一份私有实现,
 * 新加的详情页和编辑页都要用 —— 复制一份就会出现"改了列表忘了详情"的双份逻辑,
 * 而删除任务的级联清理(会话 + 闹钟 + 保活)漏一处就会留下孤儿数据。
 */
object TimerTaskUi {

    fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    /** allow_tools 逗号分隔串 -> 规范化 key 集合 */
    fun allowKeys(raw: String): Set<String> =
        raw.split(',').map { SecurityConfig.normalizeAllowKey(it.trim()) }
            .filter { it.isNotEmpty() }.toSet()

    /** 能力授权摘要文案 */
    fun allowLabel(raw: String): String {
        val set = allowKeys(raw)
        if (set.isEmpty()) return "未授权任何危险工具"
        val names = SecurityConfig.ALLOW_CATALOG.filterKeys { it in set }.values
        return if (names.size == set.size) "已授权 ${set.size} 项: " + names.joinToString("、")
        else "已授权 ${set.size} 项"
    }

    /** 能力授权多选弹窗: 勾选危险工具白名单, 保存为逗号分隔 key */
    fun allowDialog(a: Activity, title: String, current: Set<String>, onSave: (String) -> Unit) {
        val keys = SecurityConfig.ALLOW_CATALOG.keys.toList()
        val checked = keys.map { it in current }.toBooleanArray()
        val (dlg, box) = Ui.dialog(a, title, maxHeightRatio = 0.75)
        box.addView(TextView(a).apply {
            text = "勾选后, 任务静默执行时可直接使用这些危险工具, 不再逐次确认; 未勾选的仍会自动拒绝。"
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, 0, 0, dp(a, 4))
        })
        keys.forEachIndexed { i, k ->
            box.addView(CheckBox(a).apply {
                text = SecurityConfig.ALLOW_CATALOG[k] + "  ($k)"
                textSize = 14f
                setTextColor(Ui.TEXT)
                isChecked = checked[i]
                setOnCheckedChangeListener { _, b -> checked[i] = b }
            })
        }
        box.addView(Ui.primaryBtn(a, "保存") {
            dlg.dismiss()
            onSave(keys.filterIndexed { i, _ -> checked[i] }.joinToString(","))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(a, 12)
        })
        box.addView(Ui.dialogCancelBtn(a, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(a, 10)
        })
        dlg.show()
    }

    /** 立即执行: 与服务端/闹钟触发走同一条静默链路 */
    fun runNow(a: Activity, t: TimerTaskDb.Task) {
        val svc = Intent(a, TaskService::class.java)
            .setAction(TaskService.ACTION_TIMER_TASK)
            .putExtra("task_id", t.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) a.startForegroundService(svc) else a.startService(svc)
        Toast.makeText(a, "已触发「${t.name}」静默执行", Toast.LENGTH_SHORT).show()
    }

    /** 启停: 数据库 + 闹钟 + 审计 + 保活四件事必须一起做 */
    fun setEnabled(a: Activity, db: TimerTaskDb, t: TimerTaskDb.Task, enabled: Boolean) {
        db.setEnabled(t.id, enabled)
        if (enabled) TimerScheduler.schedule(a, t.id, t.cronDesc) else TimerScheduler.cancel(a, t.id)
        SecurityConfig.audit(
            a, if (enabled) "timer_task_enable" else "timer_task_disable",
            "${t.name}(id=${t.id})", "ui_user", "timer_task"
        )
        TimerKeepAliveService.ensure(a)
    }

    /** 删除: 确认弹窗 -> 任务行 + 闹钟 + 专属会话 + 保活, 缺一项就是脏数据 */
    fun deleteTask(a: Activity, db: TimerTaskDb, t: TimerTaskDb.Task, onDone: () -> Unit) {
        val auth = allowKeys(t.allowTools)
        val msg = buildString {
            append("删除「${t.name}」后不再触发, 确定?\n")
            if (auth.isNotEmpty()) {
                append("\n将同步撤回以下能力授权(${auth.size} 项):\n")
                append(auth.mapNotNull { SecurityConfig.ALLOW_CATALOG[it] }.joinToString("、"))
                append("\n\n任务专属会话一并清理, 授权范围=任务生命周期。")
            }
        }
        val (dlg, box) = Ui.dialog(a, "删除岗位")
        box.addView(Ui.dialogText(a, msg))
        box.addView(Ui.dangerBtn(a, "删除") {
            dlg.dismiss()
            db.delete(t.id)
            TimerScheduler.cancel(a, t.id)
            SecurityConfig.audit(a, "timer_task_delete", "${t.name}(id=${t.id})", "ui_user", "timer_task")
            if (t.sessionId > 0L) {
                MemoryDb(a).deleteSession(t.sessionId)
                SecurityConfig.audit(
                    a, "timer_task_session_cleanup",
                    "${t.name}(session=${t.sessionId})", "ui_user", "timer_task"
                )
            }
            TimerKeepAliveService.ensure(a)
            onDone()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(a, 12)
        })
        box.addView(Ui.dialogCancelBtn(a, "取消") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(a, 10)
        })
        dlg.show()
    }

    /** 上次执行是否成功: 失败摘要由 TaskService 以 "失败: " 开头写入 */
    fun lastOk(t: TimerTaskDb.Task): Boolean =
        t.lastRunTs > 0L && t.lastResult?.startsWith("失败") != true

    fun fmt(ts: Long, pattern: String = "MM-dd HH:mm"): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date(ts))

    /** 下次运行的可读时间: 今天/明天用相对词, 更远的日期带 MM-dd(卡片一行放得下)。
     *  只写 HH:mm 会让"每天 09:00"的任务在 10:00 时仍显示 09:00, 用户误以为马上要跑。 */
    fun nextHhmm(t: TimerTaskDb.Task): String {
        val at = TimerScheduler.nextRunAt(t.cronDesc)
        if (at <= 0L) return "—"
        val now = java.util.Calendar.getInstance()
        val next = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val hm = fmt(at, "HH:mm")
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay(now, next)) return "今天 $hm"
        val tomorrow = (now.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        if (sameDay(tomorrow, next)) return "明天 $hm"
        return fmt(at, "MM-dd HH:mm")
    }

    /** 圆角胶囊底 */
    fun capsule(a: Activity, color: Int, radiusDp: Int = 11): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(a, radiusDp).toFloat()
            setColor(color)
        }

    /** 半透明同色底(浅色/暗色主题都可读) */
    fun tint(color: Int): Int = (color and 0x00FFFFFF) or 0x1A000000
}
