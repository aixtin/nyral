package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/**
 * 岗位运行报告(2026-11-14 第二批改版)。
 *
 * 只回答一个问题 —— "这些岗位到底跑了没有":
 *  1. 近 7 天成功 / 失败 / 补跑三个数
 *  2. 按岗位分组的 7 天圆点: 绿=成功, 红=失败, 灰=无记录
 *  3. 启用中却 7 天无记录 = 可能漏跑, 直接标出来
 *  4. 失败行可展开看最近失败原因
 *
 * 权限判定不在本页维护, 入口副标题只显示 PermCatalog 算出的"还缺几项"。
 * 唯一可写项是保活开关, 其余全部只读。
 */
class TimerHealthActivity : Activity() {

    private lateinit var db: TimerTaskDb
    private var runBox: LinearLayout? = null
    private var permSub: TextView? = null
    private var okNum: TextView? = null
    private var failNum: TextView? = null
    private var catchNum: TextView? = null
    /** 展开失败原因的岗位 id */
    private val expanded = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        db = TimerTaskDb(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, "运行报告"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }

        // 三数概况: 成功 / 失败 / 补跑
        content.addView(Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
            okNum = bigStat("成功", Ui.OK)
            addView(statDivider())
            failNum = bigStat("失败", Ui.DANGER)
            addView(statDivider())
            catchNum = bigStat("补跑", Ui.SUB)
        })

        // 权限入口: 副标题动态显示还缺几项
        content.addView(Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                startActivity(Intent(this@TimerHealthActivity, PermissionsActivity::class.java))
            }
            addView(LinearLayout(this@TimerHealthActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.itemTitle(this@TimerHealthActivity, "权限与授权检查"))
                permSub = TextView(this@TimerHealthActivity).apply {
                    text = "正在检查…"
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                }
                addView(permSub)
            })
            addView(Ui.arrow(this@TimerHealthActivity))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // 与上方"成功/失败/补跑"卡留出间距, 两张卡不要贴成一块
            topMargin = dp(10)
        })

        // 各岗位 7 天圆点
        content.addView(Ui.groupLabel(this, "各岗位近 7 天"))
        runBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(runBox)

        // 保活开关
        content.addView(Ui.groupLabel(this, "常驻保活"))
        content.addView(Ui.card(this).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(keepAliveRow())
        })
        content.addView(Ui.hint(this, "关闭后仍按系统闹钟唤醒，只是少了常驻前台服务兜底；每日 1~2 次的岗位建议关闭，高频岗位建议开启。"))

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ===== 三数概况 =====
    /** 一列"大数字 + 小标签", 返回数字 TextView 供外部填充 */
    private fun LinearLayout.bigStat(label: String, color: Int): TextView {
        val num = TextView(this@TimerHealthActivity).apply {
            text = "0"
            textSize = 24f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val lb = TextView(this@TimerHealthActivity).apply {
            text = label
            textSize = 11f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        val col = LinearLayout(this@TimerHealthActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(num)
            addView(lb)
        }
        addView(col)
        return num
    }

    private fun statDivider(): View = View(this).apply {
        setBackgroundColor(Ui.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    // ===== 保活开关 =====
    private fun keepAliveRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@TimerHealthActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(Ui.itemTitle(this@TimerHealthActivity, "常驻保活服务"))
            addView(TextView(this@TimerHealthActivity).apply {
                text = "开启后常驻前台通知，被系统清理后更快回位"
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(0, dp(3), 0, 0)
            })
        })
        addView(Ui.switch(this@TimerHealthActivity).apply {
            isChecked = TimerKeepAliveService.keepAliveEnabled(this@TimerHealthActivity)
            setOnCheckedChangeListener { v ->
                TimerKeepAliveService.setKeepAlive(this@TimerHealthActivity, v)
            }
        })
    }

    // ===== 渲染 =====
    private fun refresh() {
        val box = runBox ?: return
        box.removeAllViews()

        val runs = runCatching { db.recentRuns(7) }.getOrDefault(emptyList())
        val tasks = runCatching { db.all() }.getOrDefault(emptyList())

        val ok = runs.count { it.ok }
        val fail = runs.count { !it.ok }
        val catchUp = runs.count { it.catchUp }
        okNum?.text = ok.toString()
        failNum?.text = fail.toString()
        catchNum?.text = catchUp.toString()
        okNum?.setTextColor(if (ok > 0) Ui.OK else Ui.SUB)
        failNum?.setTextColor(if (fail > 0) Ui.DANGER else Ui.SUB)

        refreshPermSubtitle()

        if (tasks.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "还没有定时岗位"
                textSize = 13f
                setTextColor(Ui.SUB)
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
            box.addView(Ui.lightBtn(this, "去创建岗位") {
                startActivity(Intent(this@TimerHealthActivity, TimerTaskEditActivity::class.java)
                    .putExtra("task_id", -1L))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            return
        }

        val byTask = runs.groupBy { it.taskId }
        val known = tasks.associateBy { it.id }

        tasks.forEach { t ->
            box.addView(taskCard(t.id, t.name, byTask[t.id].orEmpty(), t.enabled))
            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            })
        }
        // 已删除岗位的台账: task_id 不做外键级联, 记录仍在, 也要看得见
        byTask.keys.filter { it !in known }.forEach { id ->
            box.addView(taskCard(id, "已删除的岗位", byTask[id].orEmpty(), false))
            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            })
        }
        if (runs.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "近 7 天还没有执行记录。到点跑过之后，这里会按天留下成败。"
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }
    }

    private fun refreshPermSubtitle() {
        // RootCheck.deviceRooted() 会起 su 子进程探测, 放后台避免卡首帧
        Thread {
            val text = runCatching {
                val rooted = RootCheck.cachedRooted() ?: RootCheck.deviceRooted()
                val rootGranted = if (RootCheck.cachedGranted() == true) 1 else 0
                val specs = PermCatalog.build(this, rootGranted, rooted)
                val miss = specs.count { it.required() && !it.manual && !it.granted() }
                val manual = specs.count { it.manual }
                if (miss == 0) "已就绪 · $manual 项需手动确认"
                else "还缺 $miss 项 · $manual 项需手动确认"
            }.getOrDefault("通知 · 精确闹钟 · 电池优化 · 自启动")
            runOnUiThread { permSub?.text = text }
        }.start()
    }

    /**
     * 岗位圆点卡。
     * @param enabled 启用中且 7 天无记录 -> 标「可能漏跑」
     */
    private fun taskCard(taskId: Long, name: String, runs: List<TimerTaskDb.RunRecord>, enabled: Boolean): LinearLayout {
        val buckets = IntArray(7) // 0=无, 1=成功, 2=失败
        runs.forEach { r ->
            val idx = dayIndex(r.startTs)
            if (idx in 0..6) {
                if (!r.ok) buckets[idx] = 2 else if (buckets[idx] == 0) buckets[idx] = 1
            }
        }
        val okCount = runs.count { it.ok }
        val failCount = runs.count { !it.ok }
        val mayMiss = enabled && runs.isEmpty()
        val failures = runs.filter { !it.ok }.take(3)
        val isExpanded = taskId in expanded

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Ui.SURFACE)
            }
            if (failures.isNotEmpty()) {
                isClickable = true
                Ui.press(this)
                setOnClickListener {
                    if (!expanded.add(taskId)) expanded.remove(taskId)
                    refresh()
                }
            }
            addView(LinearLayout(this@TimerHealthActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@TimerHealthActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@TimerHealthActivity).apply {
                        text = name
                        textSize = 15f
                        setTextColor(Ui.TEXT)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@TimerHealthActivity).apply {
                        text = if (runs.isEmpty()) "近 7 天无记录"
                        else "近 7 天 成功 $okCount · 失败 $failCount"
                        textSize = 12f
                        setTextColor(Ui.SUB)
                        setPadding(0, dp(3), 0, 0)
                    })
                })
                addView(dots(buckets))
            })
            if (mayMiss) addView(TextView(this@TimerHealthActivity).apply {
                text = "可能漏跑"
                textSize = 11f
                setTextColor(0xFFFF8A00.toInt())
                background = TimerTaskUi.capsule(this@TimerHealthActivity, TimerTaskUi.tint(0xFFFF8A00.toInt()), 9)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            if (isExpanded && failures.isNotEmpty()) {
                addView(View(this@TimerHealthActivity).apply {
                    setBackgroundColor(Ui.DIVIDER)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(10)
                })
                failures.forEach { r ->
                    addView(TextView(this@TimerHealthActivity).apply {
                        text = TimerTaskUi.fmt(r.startTs) + " · " + (r.result ?: "失败(无摘要)")
                        textSize = 12f
                        setTextColor(Ui.DANGER)
                        setPadding(0, dp(8), 0, 0)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            }
        }
    }

    /** 7 个圆点: 左旧右新 */
    private fun dots(buckets: IntArray): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        buckets.forEach { s ->
            val color = when (s) {
                1 -> Ui.OK
                2 -> Ui.DANGER
                else -> TimerTaskUi.tint(Ui.SUB)
            }
            addView(View(this@TimerHealthActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    leftMargin = dp(3)
                }
            })
        }
    }

    /** 时间戳落在 7 天窗口的哪一格: 0=最早, 6=今天; 窗口外返回 -1 */
    private fun dayIndex(ts: Long): Int {
        val startToday = dayStart(System.currentTimeMillis())
        val startTs = dayStart(ts)
        val daysAgo = ((startToday - startTs) / 86_400_000L).toInt()
        return 6 - daysAgo
    }

    private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
