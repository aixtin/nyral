package io.github.aixtin.nyral

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast

/**
 * 岗位编辑页(2026-11-14, 新建/编辑共用)。
 *
 * 为什么从弹窗改成全屏页: 目标是一段多行提示词, 在 0.8 屏弹窗里写会被键盘和滚动条反复挤压。
 * 时间输入也彻底去掉手填 cron —— 用户只需要选一个时刻, 剩下的"每天 HH:mm"由页面拼,
 * 格式错误从"用户要学语法"变成"不可能输错"。
 */
class TimerTaskEditActivity : Activity() {

    private var taskId: Long = -1L
    private lateinit var db: TimerTaskDb
    private var existing: TimerTaskDb.Task? = null

    private lateinit var nameEt: EditText
    private lateinit var goalEt: EditText
    private lateinit var timeValue: TextView
    private lateinit var allowValue: TextView
    private lateinit var saveBtn: TextView
    private val chips = mutableListOf<Pair<TextView, Int>>() // (chip, 分钟数)

    private var hh = 9
    private var mm = 0
    private var allowTools = ""

    // 进入页面时的快照: 返回时用它判断有没有未保存的修改
    private var initName = ""
    private var initGoal = ""
    private var initHh = 9
    private var initMm = 0
    private var initAllow = ""

    private val presets = listOf(7 * 60, 9 * 60, 12 * 60, 21 * 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        ApiConfig.init(this)
        MemoryApiConfig.init(this)
        ModeConfig.init(this)
        db = TimerTaskDb(this)
        taskId = intent.getLongExtra("task_id", -1L)
        existing = if (taskId > 0L) db.get(taskId) else null
        existing?.let {
            allowTools = it.allowTools
            TimerScheduler.hhmmOf(it.cronDesc)?.let { hm -> hh = hm.first; mm = hm.second }
        }
        initName = existing?.name.orEmpty()
        initGoal = existing?.goal.orEmpty()
        initHh = hh
        initMm = mm
        initAllow = allowTools

        val page = Ui.pageRoot(this)
        // 尾随 lambda 会绑定到最后一个参数 right, 返回动作必须用命名参数
        page.addView(Ui.titleBar(this, if (existing == null) "新建岗位" else "编辑岗位", onBack = { confirmExit() }))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // 名称
        content.addView(Ui.fieldLabel(this, "岗位名称"))
        nameEt = Ui.input(this, "如：早报整理").apply {
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        content.addView(nameEt)

        // 目标
        content.addView(Ui.fieldLabel(this, "想让 AI 做什么"))
        goalEt = Ui.input(this, "写清楚要它每天做什么、产出什么，越具体越稳").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            setText(existing?.goal.orEmpty())
        }
        content.addView(goalEt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 执行时间: 整行点击 TimePicker + 常用时刻胶囊
        content.addView(Ui.fieldLabel(this, "执行时间"))
        content.addView(Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            Ui.press(this)
            setOnClickListener { pickTime() }
            addView(LinearLayout(this@TimerTaskEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.itemTitle(this@TimerTaskEditActivity, "每天").apply { textSize = 13f })
                timeValue = TextView(this@TimerTaskEditActivity).apply {
                    textSize = 22f
                    setTextColor(Ui.PRIMARY)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(2), 0, 0)
                }
                addView(timeValue)
                addView(Ui.hint(this@TimerTaskEditActivity, "点这一行改时间"))
            })
            addView(Ui.arrow(this@TimerTaskEditActivity))
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            presets.forEach { minutes ->
                val chip = TextView(this@TimerTaskEditActivity).apply {
                    text = String.format("%02d:%02d", minutes / 60, minutes % 60)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    isClickable = true
                    Ui.press(this)
                    setOnClickListener {
                        hh = minutes / 60
                        mm = minutes % 60
                        updateTimeUi()
                    }
                }
                chips.add(chip to minutes)
                addView(chip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                })
            }
        })
        content.addView(Ui.hint(this, "每天固定时刻执行一次，到点由系统闹钟唤醒。"))

        // 能力授权
        content.addView(Ui.fieldLabel(this, "能力授权"))
        content.addView(Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                TimerTaskUi.allowDialog(this@TimerTaskEditActivity, "能力授权", TimerTaskUi.allowKeys(allowTools)) { s ->
                    allowTools = s
                    allowValue.text = TimerTaskUi.allowLabel(s)
                }
            }
            addView(LinearLayout(this@TimerTaskEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.itemTitle(this@TimerTaskEditActivity, "预授权能力").apply { textSize = 14f })
                allowValue = TextView(this@TimerTaskEditActivity).apply {
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(allowValue)
            })
            addView(Ui.arrow(this@TimerTaskEditActivity))
        })

        page.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部主按钮: 名称/目标为空时置灰不可点
        saveBtn = Ui.primaryBtn(this, if (existing == null) "创建岗位" else "保存") { save() }
        page.addView(LinearLayout(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(Ui.SURFACE)
            addView(saveBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        setContentView(page)

        updateTimeUi()
        allowValue.text = TimerTaskUi.allowLabel(allowTools)
        listOf(nameEt, goalEt).forEach { et ->
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSaveState()
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        updateSaveState()
    }

    private fun updateSaveState() {
        val ready = nameEt.text.toString().trim().isNotEmpty() && goalEt.text.toString().trim().isNotEmpty()
        saveBtn.isEnabled = ready
        saveBtn.alpha = if (ready) 1f else 0.45f
    }

    /** 有没有未保存的修改(名称/目标按去空格后比较) */
    private fun hasChanges(): Boolean =
        nameEt.text.toString().trim() != initName ||
            goalEt.text.toString().trim() != initGoal ||
            hh != initHh || mm != initMm || allowTools != initAllow

    /** 返回前拦截: 有改动才确认, 没改动直接退出(别让用户多点一次) */
    private fun confirmExit() {
        if (!hasChanges()) {
            finish()
            return
        }
        val (dlg, box) = Ui.dialog(this, "放弃修改?")
        box.addView(Ui.dialogText(this, "当前内容还没保存，返回后会丢失。"))
        box.addView(Ui.primaryBtn(this, "继续编辑") { dlg.dismiss() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dangerBtn(this, "放弃修改") {
            dlg.dismiss()
            finish()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        dlg.show()
    }

    @Deprecated("Activity 已无 OnBackPressedDispatcher, 编辑页是纯 Activity 且无 fragment")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun updateTimeUi() {
        timeValue.text = String.format("%02d:%02d", hh, mm)
        val cur = hh * 60 + mm
        chips.forEach { (tv, minutes) -> Ui.chip(tv, minutes == cur, this) }
    }

    private fun pickTime() {
        val (dlg, box) = Ui.dialog(this, "选择执行时间")
        val tp = TimePicker(this).apply {
            setIs24HourView(true)
            hour = hh
            minute = mm
        }
        box.addView(tp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.primaryBtn(this, "确定") {
            hh = tp.hour
            mm = tp.minute
            dlg.dismiss()
            updateTimeUi()
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

    private fun save() {
        val name = nameEt.text.toString().trim()
        val goal = goalEt.text.toString().trim()
        if (name.isEmpty() || goal.isEmpty()) return
        val cron = String.format("每天 %02d:%02d", hh, mm)
        val old = existing
        if (old == null) {
            // 每个岗位创建即绑定一个专属会话(聊天/Agent 两模式互不串)
            val mode = ModeConfig.modeValue()
            val id = db.insert(name, goal, cron, mode, allowTools)
            val sessionId = MemoryDb(this).saveSession(name, emptyList(), mode, MemoryDb.KIND_TIMER)
            db.bindSession(id, sessionId)
            TimerScheduler.schedule(this, id, cron)
            SecurityConfig.audit(this, "timer_task_register", "$name / $goal", "ui_user", "timer_task")
            Toast.makeText(this, "已创建「$name」", Toast.LENGTH_SHORT).show()
        } else {
            db.update(old.id, name, goal, cron, allowTools)
            // 停用的岗位只改配置, 不重排闹钟; 启用中的按新时刻重排
            if (old.enabled) TimerScheduler.schedule(this, old.id, cron)
            SecurityConfig.audit(this, "timer_task_update", "${old.name}(id=${old.id}) -> $name / $cron", "ui_user", "timer_task")
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        TimerKeepAliveService.ensure(this)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
