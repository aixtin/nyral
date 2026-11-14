package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 定时岗位列表页(2026-11-14 三行卡片改版)。
 *
 * 卡片只回答三件事: 叫什么/什么时候跑、跑过没有、授了什么权。
 *  - 点卡片进详情页(状态 / 目标全文 / 台账 / 执行与删除)
 *  - 长按卡片 = 立即执行(高频动作, 不进二级页)
 *  - 右下角 FAB = 新建, 与 BlogPad 同一套 56dp 主色圆 + 矢量图标
 * 右上角"报告"由文字改为柱状图图标: 标题栏右侧是图标位, 文字会被误读成按钮名。
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

        val page = Ui.pageRoot(this)

        page.addView(Ui.titleBar(this, "定时岗位") { bar ->
            bar.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_report)
                setColorFilter(Ui.TEXT)
                setPadding(dp(10), dp(6), dp(6), dp(6))
                contentDescription = "运行报告"
                isClickable = true
                Ui.press(this)
                setOnClickListener {
                    startActivity(Intent(this@TimerTaskActivity, TimerHealthActivity::class.java))
                }
            })
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            // 底部留白 92dp: 避免右下角 FAB(56dp)+边距遮住最后一张任务卡
            setPadding(dp(12), dp(8), dp(12), dp(92))
        }
        scroll.addView(listBox)
        page.addView(scroll)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(page, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val fab = ImageButton(this).apply {
            setImageResource(R.drawable.ic_fab_add)
            setColorFilter(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.PRIMARY)
            }
            elevation = dp(6).toFloat()
            contentDescription = "新建岗位"
            Ui.press(this)
            setOnClickListener { openEditor(-1L) }
        }
        val fabLp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(20), dp(20))
        }
        root.addView(fab, fabLp)
        if (Build.VERSION.SDK_INT >= 35) {
            fab.setOnApplyWindowInsetsListener { v, insets ->
                // pageRoot 已把导航栏 inset 吃成自身 padding; FAB 在 FrameLayout 层需自行抬高避让
                val navBottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                fabLp.setMargins(0, 0, dp(20), dp(20) + navBottom)
                v.layoutParams = fabLp
                insets
            }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 保活三件套: 进页面兜底同步常驻 FGS(有启用任务则拉起, 全停则停)
        TimerKeepAliveService.ensure(this)
        render()
    }

    private fun openEditor(taskId: Long) {
        startActivity(Intent(this, TimerTaskEditActivity::class.java).putExtra("task_id", taskId))
    }

    private fun render() {
        listBox.removeAllViews()
        val tasks = db.all()
        if (tasks.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "还没有定时岗位\n点右下角「＋」创建第一个"
                textSize = 14f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(48), 0, dp(48))
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

    /** 三行卡片: 名称+下次时间 / 周期+授权数 / 上次结果 */
    private fun taskCard(t: TimerTaskDb.Task): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // 圆角收小: 12dp 在列表里显得过于"胶囊化", 与运行报告的卡片观感不统一
            background = rounded(dp(8), Ui.SURFACE)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@TimerTaskActivity, TimerTaskDetailActivity::class.java)
                    .putExtra("task_id", t.id))
            }
            // 长按 = 立即执行: 高频动作, 不强制进详情页; 先给一次触感反馈, 避免"长按没反应"的错觉
            setOnLongClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                TimerTaskUi.runNow(this@TimerTaskActivity, t)
                true
            }
        }
        // 第一行: 名称 + 下次运行时刻(停用显示灰色状态)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@TimerTaskActivity).apply {
                text = t.name
                textSize = 16f
                setTextColor(if (t.enabled) Ui.TEXT else Ui.SUB)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@TimerTaskActivity).apply {
                if (t.enabled) {
                    text = "下次 " + TimerTaskUi.nextHhmm(t)
                    setTextColor(Ui.PRIMARY)
                    typeface = Typeface.DEFAULT_BOLD
                } else {
                    text = "已停用"
                    setTextColor(Ui.SUB)
                }
                textSize = 12f
                setPadding(dp(8), 0, 0, 0)
            })
        })
        // 第二行: 执行周期 + 授权项数
        val allowCount = TimerTaskUi.allowKeys(t.allowTools).size
        card.addView(TextView(this).apply {
            text = t.cronDesc + " · " + if (allowCount > 0) "授权 $allowCount 项" else "未授权能力"
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, dp(7), 0, 0)
        })
        // 第三行: 上次结果(成功绿 / 失败红, 一眼可扫)
        card.addView(TextView(this).apply {
            if (t.lastRunTs > 0L) {
                val ok = TimerTaskUi.lastOk(t)
                text = "上次 " + TimerTaskUi.fmt(t.lastRunTs) + " · " + if (ok) "成功" else "失败"
                setTextColor(if (ok) Ui.OK else Ui.DANGER)
            } else {
                text = "还没执行过"
                setTextColor(Ui.SUB)
            }
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(r: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(r).toFloat()
            setColor(color)
        }
}
