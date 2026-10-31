package io.github.aixtin.nyral

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 设备面板(2026-10-30 老板定调)。
 *
 * 不做消费级"体检打分", 做只读设备面板: CPU 占用与最高核频率、内存、SoC 温度、电池、负载、数据分区余量,
 * 带 5 分钟曲线与近 5 分钟峰值。**只看不改**: 不写任何系统参数。
 *
 * 「问 AI」不新开会话: 固定绑定普通会话「设备分析」, 点击后跳到该会话并带上本次快照提问。
 */
class DiagnosticsActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    private var askedShizuku = false

    private lateinit var profileText: TextView
    private lateinit var statusText: TextView
    private lateinit var verdictText: TextView
    private lateinit var cpuVal: TextView
    private lateinit var cpuSub: TextView
    private lateinit var memVal: TextView
    private lateinit var memSub: TextView
    private lateinit var tempVal: TextView
    private lateinit var tempSub: TextView
    private lateinit var battVal: TextView
    private lateinit var battSub: TextView
    private lateinit var loadVal: TextView
    private lateinit var loadSub: TextView
    private lateinit var diskVal: TextView
    private lateinit var diskSub: TextView
    private lateinit var spark: SparkView
    private lateinit var coreBars: CoreBarsView
    private lateinit var coreHint: TextView
    private lateinit var netVal: TextView
    private lateinit var netSub: TextView
    private lateinit var netSpark: NetView
    private lateinit var mountList: LinearLayout
    private lateinit var taskList: LinearLayout
    private lateinit var taskHint: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            refresh()
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.resetPrivilegeCache()
        setContentView(buildRoot())
        darkSystemBars()
        loadProfile()
    }

    override fun onResume() {
        super.onResume()
        alive = true
        syncPrivilege()
        ui.post(tick)
    }

    /** 回到面板时重新探测特权通道: Shizuku 刚授完权要能立刻解锁读取, 未授权则弹一次授权请求 */
    private fun syncPrivilege() {
        Diagnostics.resetPrivilegeCache()
        val st = try { Diagnostics.shizukuState() } catch (e: Throwable) { 0 }
        if (st == 1 && !askedShizuku) {
            askedShizuku = true
            try { Diagnostics.requestShizukuPermission() } catch (_: Throwable) {}
        }
        loadProfile()
    }

    override fun onPause() {
        alive = false
        ui.removeCallbacks(tick)
        super.onPause()
    }

    /** 页面底色是深色, 状态栏/导航栏图标必须是亮色, 否则暗底暗字看不见 */
    private fun darkSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
    }

    // ===================== UI =====================

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        // targetSdk 36 强制 edge-to-edge: 根布局消费状态栏/导航栏 inset, 顶栏不再压到系统状态栏上
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val top = insets.getInsets(WindowInsets.Type.statusBars()).top
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                v.setPadding(0, top, 0, bottom)
                insets
            }
        }
        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), dpi(12), dpi(10), dpi(8))
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(TXT)
            setPadding(dpi(6), 0, dpi(10), 0)
            isClickable = true
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "设备面板"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "复制快照"
            textSize = 12.5f
            setTextColor(ACCENT)
            setPadding(dpi(10), dpi(6), dpi(10), dpi(6))
            background = roundBg(ACCENT_BG, 999)
            isClickable = true
            setOnClickListener { copySnapshot() }
        })
        root.addView(bar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14), 0, dpi(14), dpi(20))
        }
        scroll.addView(col)
        root.addView(scroll)

        // 设备画像
        val profileCard = card()
        profileCard.addView(sectionTitle("设备"))
        profileText = TextView(this).apply {
            text = "读取中…"
            textSize = 12.5f
            setTextColor(SUB)
            setLineSpacing(dpi(4).toFloat(), 1f)
        }
        profileCard.addView(profileText)
        col.addView(profileCard)

        // 指标网格
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(10)
            }
        }
        fun cell(title: String): Triple<LinearLayout, TextView, TextView> {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(CARD2, 14)
                setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            }
            box.addView(TextView(this).apply {
                text = title
                textSize = 11.5f
                setTextColor(SUB)
            })
            val v = TextView(this).apply {
                text = "--"
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(TXT)
                setPadding(0, dpi(2), 0, 0)
            }
            box.addView(v)
            val sub = TextView(this).apply {
                text = ""
                textSize = 10.5f
                setTextColor(SUB)
                maxLines = 2
                setEllipsize(android.text.TextUtils.TruncateAt.END)
            }
            box.addView(sub)
            return Triple(box, v, sub)
        }
        fun row(vararg cells: Triple<LinearLayout, TextView, TextView>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cells.forEachIndexed { i, c ->
                r.addView(c.first, LinearLayout.LayoutParams(0, dpi(94), 1f).apply {
                    if (i > 0) marginStart = dpi(8)
                })
            }
            return r
        }
        val cpuCell = cell("CPU 占用")
        cpuVal = cpuCell.second; cpuSub = cpuCell.third
        val memCell = cell("内存")
        memVal = memCell.second; memSub = memCell.third
        val tempCell = cell("SoC 温度")
        tempVal = tempCell.second; tempSub = tempCell.third
        val battCell = cell("电池")
        battVal = battCell.second; battSub = battCell.third
        val loadCell = cell("负载")
        loadVal = loadCell.second; loadSub = loadCell.third
        val diskCell = cell("数据分区")
        diskVal = diskCell.second; diskSub = diskCell.third

        grid.addView(row(cpuCell, memCell, tempCell))
        grid.addView(row(battCell, loadCell, diskCell).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(8)
            }
        })
        col.addView(grid)

        // 每核占用(Ubuntu 资源页观感: 一核一根, 核数按 availableProcessors 动态排, 4/6/8/10 核都能放)
        val coreCard = card()
        coreCard.addView(sectionTitle("每核占用"))
        coreBars = CoreBarsView(this)
        coreCard.addView(coreBars, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(84)).apply { topMargin = dpi(6) })
        coreHint = TextView(this).apply {
            text = ""
            textSize = 10.5f
            setTextColor(SUB)
            setPadding(0, dpi(4), 0, 0)
        }
        coreCard.addView(coreHint)
        col.addView(coreCard)

        // 网络: 上下行速率 + 5 分钟曲线
        val netCard = card()
        val netHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        netHead.addView(sectionTitle("网络").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        netHead.addView(TextView(this).apply {
            text = legend("下行 ", ACCENT, "上行 ", WARN)
            textSize = 10.5f
            setTextColor(SUB)
        })
        netCard.addView(netHead)
        netVal = TextView(this).apply {
            text = "--"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TXT)
            setPadding(0, dpi(2), 0, 0)
        }
        netCard.addView(netVal)
        netSub = TextView(this).apply {
            text = ""
            textSize = 10.5f
            setTextColor(SUB)
        }
        netCard.addView(netSub)
        netSpark = NetView(this)
        netCard.addView(netSpark, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(96)).apply { topMargin = dpi(6) })
        col.addView(netCard)

        // 存储: 多挂载点(对应 Ubuntu 文件系统页)
        val fsCard = card()
        fsCard.addView(sectionTitle("存储"))
        mountList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fsCard.addView(mountList)
        col.addView(fsCard)

        // 曲线
        val sparkCard = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(sectionTitle("近 1 分钟").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = legend("CPU ", ACCENT, "温度 ", WARN)
            textSize = 10.5f
            setTextColor(SUB)
        })
        sparkCard.addView(head)
        spark = SparkView(this)
        sparkCard.addView(spark, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(104)).apply { topMargin = dpi(8) })
        statusText = TextView(this).apply {
            text = ""
            textSize = 10.5f
            setTextColor(SUB)
            setPadding(0, dpi(6), 0, 0)
        }
        sparkCard.addView(statusText)
        col.addView(sparkCard)

        // 判定
        val verdictCard = card()
        verdictCard.addView(sectionTitle("当前判定"))
        verdictText = TextView(this).apply {
            text = "采样中…"
            textSize = 12.5f
            setTextColor(TXT)
            setLineSpacing(dpi(4).toFloat(), 1f)
        }
        verdictCard.addView(verdictText)
        col.addView(verdictCard)

        // 后台任务(P3): 运行中的脚本进程。停止按钮之外的第二道人工闸门 —— 点停止没刹住或没有对话在跑时, 这里能直接收口
        val taskCard = card()
        taskCard.addView(sectionTitle("后台任务"))
        taskHint = TextView(this).apply {
            text = "读取中…"
            textSize = 11.5f
            setTextColor(SUB)
            setLineSpacing(dpi(3).toFloat(), 1f)
        }
        taskCard.addView(taskHint)
        taskList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        taskCard.addView(taskList)
        col.addView(taskCard)

        // 按钮
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(12)
            }
        }
        actions.addView(TextView(this).apply {
            text = "问 AI"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBg(ACCENT, 14)
            setPadding(0, dpi(12), 0, dpi(12))
            isClickable = true
            setOnClickListener { DeviceAssist.ask(this@DiagnosticsActivity) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(TextView(this).apply {
            text = "复制快照"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(TXT)
            background = roundBg(CARD2, 14)
            setPadding(0, dpi(12), 0, dpi(12))
            isClickable = true
            setOnClickListener { copySnapshot() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpi(8)
        })
        col.addView(actions)

        col.addView(TextView(this).apply {
            text = "只读面板: 不修改任何系统参数; root / Shizuku 仅用于读取受限路径。\n" +
                    "动态读数不进对话上下文 —— AI 需要时按需调 device_snapshot / device_probe 取。"
            textSize = 10.5f
            setTextColor(SUB)
            setLineSpacing(dpi(3).toFloat(), 1f)
            setPadding(0, dpi(12), 0, 0)
        })
        return root
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(CARD, 16)
        setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(10)
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TXT)
        setPadding(0, 0, 0, dpi(6))
    }

    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** 图例: 两个圆点分别取各自曲线的颜色, 与线一一对应 */
    private fun legend(l1: String, c1: Int, l2: String, c2: Int): CharSequence {
        val s = SpannableString(l1 + "●   " + l2 + "●")
        val a = s.indexOf('●')
        s.setSpan(ForegroundColorSpan(c1), a, a + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val b = s.lastIndexOf('●')
        s.setSpan(ForegroundColorSpan(c2), b, b + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun roundBg(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpi(radius).toFloat()
    }

    // ===================== 数据 =====================

    private fun loadProfile() {
        Thread {
            val p = try { DeviceMetrics.profile(this) } catch (e: Throwable) { JSONObject() }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                profileText.text = listOf(
                    p.optString("model") + " (" + p.optString("device") + ")",
                    "Android " + p.optString("android") + " · API " + p.optInt("api"),
                    "SoC " + p.optString("soc") + " · " + p.optInt("cores") + " 核 · " + p.optString("abi"),
                    "Kernel " + p.optString("kernel"),
                    "root: " + p.optString("root") + " · Shizuku: " + p.optString("shizuku")
                ).filter { it.isNotBlank() }.joinToString("\n")
            }
        }.start()
    }

    private fun refresh() {
        Thread {
            val s = try { DeviceMetrics.sample(this) } catch (e: Throwable) { JSONObject() }
            val (allCpu, allTemp) = DeviceMetrics.series()
            val cpuSeries = allCpu.takeLast(PLOT_POINTS)
            val tempSeries = allTemp.takeLast(PLOT_POINTS)
            val peaks = Pair(cpuSeries.maxOrNull() ?: 0f, tempSeries.maxOrNull() ?: 0f)
            val verdicts = try { DeviceMetrics.verdicts(s) } catch (e: Throwable) { emptyList<String>() }
            ui.post {
                if (isFinishing || isDestroyed || !alive) return@post
                bindValues(s)
                spark.setData(cpuSeries, tempSeries)
                val (dnSeries, upSeries) = DeviceMetrics.netHistory()
                netSpark.setData(dnSeries.takeLast(PLOT_POINTS), upSeries.takeLast(PLOT_POINTS))
                statusText.text = "峰值 CPU " + fmt(peaks.first.toDouble()) + "%" +
                        (if (peaks.second > 0f) " · 温度 " + fmt(peaks.second.toDouble()) + "℃" else "") +
                        " · " + cpuSeries.size + " 点 · " + timeNow()
                verdictText.text = if (verdicts.isEmpty()) "各项读数正常区间内(只陈述事实, 不做评分)" else verdicts.joinToString("\n") { "· " + it }
                refreshTaskPanel()
            }
        }.start()
    }

    /** 后台任务卡片: 每秒随面板一起刷新(进程结束/被终止后自动消失) */
    private fun refreshTaskPanel() {
        if (!::taskList.isInitialized) return
        val items = try { ProcGuard.tasks() } catch (e: Throwable) { emptyList<ProcGuard.TaskView>() }
        taskList.removeAllViews()
        if (items.isEmpty()) {
            taskHint.text = "没有运行中的脚本进程"
            return
        }
        taskHint.text = "运行中 " + items.size + " 个 · 终止时按进程组整组收口(setsid 独立会话)"
        items.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(6), 0, dpi(6))
            }
            val secs = ((System.currentTimeMillis() - t.ts) / 1000L).coerceAtLeast(0L)
            row.addView(TextView(this).apply {
                text = t.id + " · pid " + t.pid + (if (t.root) " · root" else "") + " · 已运行 " + secs + "s"
                textSize = 12f
                setTextColor(SUB)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "终止"
                textSize = 12f
                setTextColor(ACCENT)
                setPadding(dpi(10), dpi(5), dpi(10), dpi(5))
                background = roundBg(ACCENT_BG, 999)
                isClickable = true
                setOnClickListener {
                    ProcGuard.killOne(t.id)
                    Toast.makeText(this@DiagnosticsActivity, "已发送终止: " + t.id, Toast.LENGTH_SHORT).show()
                    ui.postDelayed({ refreshTaskPanel() }, 500L)
                }
            })
            taskList.addView(row)
        }
    }

    private fun bindValues(s: JSONObject) {
        val cpu = s.optJSONObject("cpu") ?: JSONObject()
        // 机型差异兜底: Android 10+ 对普通应用隐藏 /proc/stat, 读不到就如实显示 "--" + 缺哪个通道,
        // 不伪造 0.0%(老板出身 root 圈, 假数字比空值更误导)
        val hasPct = cpu.has("pct")
        cpuVal.text = if (hasPct) fmt(cpu.optDouble("pct")) + "%" else "--"
        cpuSub.text = buildString {
            if (cpu.has("max_mhz")) append("最高核 ").append(fmt(cpu.optDouble("max_mhz"))).append("MHz")
            if (cpu.has("avg_mhz")) {
                if (isNotEmpty()) append(" · ")
                append("均 ").append(fmt(cpu.optDouble("avg_mhz")))
            }
            if (!hasPct) {
                if (isNotEmpty()) append(" · ")
                append("占用率需 root/Shizuku")
            }
            if (isEmpty()) append(cpu.optInt("cores")).append(" 核")
        }
        cpuVal.setTextColor(if (hasPct) heatColor(cpu.optDouble("pct"), 60.0, 85.0) else TXT)

        val mem = s.optJSONObject("mem") ?: JSONObject()
        memVal.text = fmt(mem.optDouble("used_pct", 0.0)) + "%"
        memSub.text = buildString {
            append(fmt(mem.optDouble("used_mb"))).append(" / ").append(fmt(mem.optDouble("total_mb"))).append("MB")
            if (mem.has("swap_used_mb")) append(" · swap ").append(fmt(mem.optDouble("swap_used_mb"))).append("MB")
        }
        memVal.setTextColor(heatColor(mem.optDouble("used_pct", 0.0), 80.0, 90.0))

        val temp = s.optJSONObject("temp") ?: JSONObject()
        val soc = temp.optDouble("soc_c", -1.0)
        val socSuspect = temp.optInt("soc_suspect", 0) == 1
        tempVal.text = when {
            soc <= 0 -> "--"
            socSuspect -> fmt(soc) + "℃?"
            else -> fmt(soc) + "℃"
        }
        tempSub.text = buildString {
            if (temp.has("batt_c")) append("电池 ").append(fmt(temp.optDouble("batt_c"))).append("℃")
            else append("读不到温度节点")
            when (temp.optString("soc_src")) {
                "sysfs" -> append(" · 节点直读")
                "sysfs-su" -> append(" · 节点读(root)")
                "hal" -> append(" · HAL 上报")
            }
            if (socSuspect) append(" · 读数可疑")
        }
        tempVal.setTextColor(if (soc > 0) heatColor(soc, 45.0, 58.0) else SUB)

        val b = s.optJSONObject("batt") ?: JSONObject()
        battVal.text = b.optInt("level").toString() + "%"
        battSub.text = buildString {
            append(b.optString("status"))
            if (b.has("current_ma")) append(" · ").append(fmt(b.optDouble("current_ma"))).append("mA")
        }

        val load = s.optJSONObject("load") ?: JSONObject()
        val hasLoad = load.has("loadavg")
        loadVal.text = if (hasLoad) load.optString("load1", "--") else "--"
        loadSub.text = if (hasLoad) {
            "5/15m " + load.optString("loadavg", "-").substringAfter(" / ", "-") + " · " + s.optString("uptime")
        } else {
            "/proc/loadavg 需 root/Shizuku · " + s.optString("uptime")
        }

        val d = s.optJSONObject("disk") ?: JSONObject()
        diskVal.text = fmt(d.optDouble("free_gb")) + "G"
        diskSub.text = "剩 " + fmt(d.optDouble("free_pct", 0.0)) + "% / 共 " + fmt(d.optDouble("total_gb")) + "GB"
        diskVal.setTextColor(if (d.optDouble("free_gb", 999.0) < 5.0) RED else TXT)

        // 分核占用
        val coresArr = cpu.optJSONArray("per_core_pct")
        if (coresArr != null && coresArr.length() > 0) {
            val vals = ArrayList<Float>()
            for (i in 0 until coresArr.length()) vals.add(coresArr.optDouble(i, -1.0).toFloat())
            coreBars.setData(vals)
            coreHint.text = vals.size.toString() + " 核 · 单核占比, 与整机占用同源 /proc/stat"
        } else {
            coreBars.setData(emptyList())
            coreHint.text = "分核读数需 root/Shizuku"
        }

        // 网络
        val net = s.optJSONObject("net") ?: JSONObject()
        if (net.has("down_kbps")) {
            netVal.text = "↓ " + speed(net.optDouble("down_kbps")) + "   ↑ " + speed(net.optDouble("up_kbps"))
            netSub.text = "累计 收 " + fmt(net.optDouble("rx_gb")) + "G · 发 " + fmt(net.optDouble("tx_gb")) + "G"
        } else {
            netVal.text = "--"
            netSub.text = "/proc/net/dev 需 root/Shizuku 授权"
        }

        // 存储挂载点
        mountList.removeAllViews()
        val mounts = d.optJSONArray("mounts")
        if (mounts != null && mounts.length() > 0) {
            for (i in 0 until mounts.length()) {
                val m = mounts.optJSONObject(i) ?: continue
                mountList.addView(TextView(this).apply {
                    // 两行: 挂载点一行、容量一行。合并同源挂载后路径会变长, 单行必截断, 截掉的正是"剩多少"这种关键信息
                    text = buildString {
                        append(m.optString("label"))
                        append("\n")
                        append(fmt(m.optDouble("free_gb"))).append("G 可用 / ")
                        append(fmt(m.optDouble("total_gb"))).append("G · 剩 ")
                        append(fmt(m.optDouble("free_pct"))).append("%")
                    }
                    textSize = 12.5f
                    setTextColor(if (m.optDouble("free_pct", 100.0) < 10.0) RED else TXT)
                    setPadding(0, dpi(4), 0, dpi(2))
                    setLineSpacing(dpi(2).toFloat(), 1f)
                })
            }
        } else {
            mountList.addView(TextView(this).apply {
                text = "读不到挂载点"
                textSize = 12.5f
                setTextColor(SUB)
            })
        }
    }

    private fun speed(kbps: Double): String =
        if (kbps >= 1024.0) fmt(kbps / 1024.0) + " MB/s" else fmt(kbps) + " KB/s"

    private fun heatColor(v: Double, warm: Double, hot: Double): Int = when {
        v >= hot -> RED
        v >= warm -> WARN
        else -> TXT
    }

    /** 量程向上取整到 1/2/2.5/5/10 档, 让纵轴刻度是整齐数字而不是 0.37 这类怪值 */
    private fun niceCeil(v: Float): Float {
        if (v <= 0f) return 1f
        val exp = floor(log10(v.toDouble())).toInt()
        val base = 10.0.pow(exp)
        val m = v / base
        val nm = when {
            m <= 1.0 -> 1.0
            m <= 2.0 -> 2.0
            m <= 2.5 -> 2.5
            m <= 5.0 -> 5.0
            else -> 10.0
        }
        return (nm * base).toFloat()
    }

    /** 纵轴刻度文字: 单位随量程走(MB/s 或 KB/s), 保持 3~4 字符宽度 */
    private fun axisNum(kbps: Float, top: Float): String {
        if (kbps <= 0.001f) return "0"
        return if (top >= 1024f) fmt((kbps / 1024f).toDouble()) + "M"
        else fmt(kbps.toDouble()) + "K"
    }

    private fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "-"
        return if (v >= 100) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()
    }

    private fun timeNow(): String {
        val d = java.util.Date()
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(d)
    }

    private fun copySnapshot() {
        Thread {
            val text = try { DeviceMetrics.dumpText(this) } catch (e: Throwable) { "" }
            ui.post {
                if (text.isBlank()) {
                    Toast.makeText(this, "快照读取失败", Toast.LENGTH_SHORT).show()
                    return@post
                }
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("nyral-device", text))
                    Toast.makeText(this, "快照已复制", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "复制失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 每核占用: 一核一根竖条, 核数动态(4/6/8/10 核), 对应 Ubuntu 资源页的按核视图 */
    private inner class CoreBarsView(c: Context) : View(c) {
        private var vals: List<Float> = emptyList()
        private val pBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CARD2
        }
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * resources.displayMetrics.density
            color = SUB
            textAlign = Paint.Align.CENTER
        }
        private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = GRID
        }
        private val pPct = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * resources.displayMetrics.density
            color = SUB
            textAlign = Paint.Align.CENTER
        }

        fun setData(v: List<Float>) {
            vals = v
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val d = resources.displayMetrics.density
            val labelH = 12f * d
            val headH = 12f * d
            val barTop = headH
            val barH = h - labelH - headH
            if (barH <= 0f) return
            for (i in 1..3) {
                val y = barTop + barH * i / 4f
                canvas.drawLine(0f, y, w, y, pGrid)
            }
            if (vals.isEmpty()) {
                canvas.drawText("无分核读数", w / 2f, barTop + barH / 2f, pText)
                return
            }
            val n = vals.size
            val slot = w / n
            val barW = minOf(slot * 0.62f, 26f * resources.displayMetrics.density)
            vals.forEachIndexed { i, v ->
                val x = slot * i + slot / 2f
                if (v >= 0f) {
                    canvas.drawText(v.roundToInt().toString() + "%", x, headH - 2.5f * d, pPct)
                }
                val track = RectF(x - barW / 2f, barTop, x + barW / 2f, barTop + barH)
                canvas.drawRoundRect(track, barW / 4f, barW / 4f, pTrack)
                if (v >= 0f) {
                    val top = barTop + barH - barH * (v.coerceIn(0f, 100f) / 100f)
                    pBar.color = when {
                        v >= 85f -> RED
                        v >= 60f -> WARN
                        else -> ACCENT
                    }
                    canvas.drawRoundRect(RectF(x - barW / 2f, top, x + barW / 2f, barTop + barH), barW / 4f, barW / 4f, pBar)
                }
                canvas.drawText(i.toString(), x, h - 1f, pText)
            }
        }
    }

    /** 网络曲线: 上下行双线, 纵向按当前峰值自适应, 低速也不会被压成直线 */
    /**
     * Catmull-Rom 转三次贝塞尔: 曲线仍穿过每个采样点, 峰顶带自然弧度(乌班图观感)。
     * 控制点 y 夹在绘图区内, 避免冲高越过 0/100 框线误导读数。
     */
    private fun smoothPath(data: List<Float>, x0: Float, step: Float,
                           yOf: (Float) -> Float, h: Float): Path {
        val p = Path()
        val n = data.size
        if (n == 0) return p
        val xs = FloatArray(n) { x0 + it * step }
        val ys = FloatArray(n) { yOf(data[it]).coerceIn(0f, h) }
        p.moveTo(xs[0], ys[0])
        if (n == 1) return p
        for (i in 0 until n - 1) {
            val y0 = ys[i]
            val y1 = ys[i + 1]
            val yPrev = ys[(i - 1).coerceAtLeast(0)]
            val yNext = ys[(i + 2).coerceAtMost(n - 1)]
            val dx = xs[i + 1] - xs[i]
            val c1y = (y0 + (y1 - yPrev) / 6f).coerceIn(0f, h)
            val c2y = (y1 - (yNext - y0) / 6f).coerceIn(0f, h)
            p.cubicTo(xs[i] + dx / 3f, c1y, xs[i + 1] - dx / 3f, c2y, xs[i + 1], y1)
        }
        return p
    }

    /** 横轴刻度: 右端为现在(0s), 向左依次 10s/20s/30s/40s/50s/1分钟 */
    private fun drawTimeAxis(c: Canvas, w: Float, step: Float, plotH: Float, viewH: Float, p: Paint,
                             startX: Float = 0f) {
        val density = resources.displayMetrics.density
        p.color = GRID
        c.drawLine(startX, plotH, w, plotH, p)
        p.color = SUB
        for (k in 1..PLOT_POINTS / 10) {
            val x = (w - k * 10 * step).coerceIn(startX, w - 1f)
            c.drawLine(x, plotH, x, plotH + 3f * density, p)
            val label = if (k == PLOT_POINTS / 10) "1分钟" else (k * 10).toString() + "s"
            c.drawText(label, x.coerceIn(12f * density, w - 2f), viewH - 2f * density, p)
        }
    }

    /** 网络曲线: 上下行双线, 纵向按当前峰值自适应; 横轴 1 分钟 */
    private inner class NetView(c: Context) : View(c) {
        private var down: List<Float> = emptyList()
        private var up: List<Float> = emptyList()
        private var anchor = 0L
        private var delta = 1000f
        private val pDown = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(1).toFloat().coerceAtLeast(1.2f)
            color = ACCENT
        }
        private val pUp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(1).toFloat().coerceAtLeast(1.2f)
            color = WARN
        }
        private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = GRID
        }
        private val pPeak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f * resources.displayMetrics.density
            color = SUB
            textAlign = Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * resources.displayMetrics.density
            color = SUB
            textAlign = Paint.Align.RIGHT
        }
        private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * resources.displayMetrics.density
            color = SUB
            textAlign = Paint.Align.CENTER
        }
        private val animRun = object : Runnable {
            override fun run() {
                invalidate()
                if (phaseNow() < 1f && isAttachedToWindow) postDelayed(this, 33L)
            }
        }

        private fun phaseNow(): Float {
            if (anchor == 0L) return 1f
            return ((SystemClock.elapsedRealtime() - anchor).toFloat() / delta).coerceIn(0f, 1f)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            removeCallbacks(animRun)
            post(animRun)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(animRun)
        }

        fun setData(d: List<Float>, u: List<Float>) {
            val now = SystemClock.elapsedRealtime()
            if (anchor != 0L) delta = (now - anchor).toFloat().coerceIn(600f, 3000f)
            anchor = now
            down = d
            up = u
            removeCallbacks(animRun)
            post(animRun)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val density = resources.displayMetrics.density
            val axisH = 13f * density
            val headH = 13f * density
            val h = height.toFloat() - axisH - headH
            if (w <= 0f || h <= 0f) return
            val gutter = 30f * density
            val plotW = (w - gutter).coerceAtLeast(1f)
            // 量程 = 窗口峰值向上取整到 1/2/2.5/5/10 档, 刻度按量程四等分, 读数有单位可依
            val realPeak = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f, 0.1f)
            val top = niceCeil(maxOf(realPeak, 1f))
            canvas.drawText("峰 " + speed(realPeak.toDouble()), w, headH - 3f * density, pPeak)
            for (i in 1..4) {
                val y = headH + h * i / 4f
                canvas.drawLine(gutter, y, w, y, pGrid)
                if (i < 4) {
                    canvas.drawText(axisNum(top * (4 - i) / 4f, top),
                        gutter - 3f * density, y - 2.5f * density, pTick)
                }
            }
            val step = plotW / (PLOT_POINTS - 1)
            if (maxOf(down.size, up.size) >= 2) {
                val lead = (1f - phaseNow()) * step
                fun line(data: List<Float>, p: Paint) {
                    if (data.size < 2) return
                    val x0 = w + lead - (data.size - 1) * step
                    val yOf = { v: Float -> headH + h - h * (v.coerceIn(0f, top) / top) }
                    canvas.drawPath(smoothPath(data, x0, step, yOf, h), p)
                }
                line(down, pDown)
                line(up, pUp)
            }
            drawTimeAxis(canvas, w, step, headH + h, height.toFloat(), pAxis, gutter)
        }
    }


    /** 双线迷你图: CPU 百分比 + SoC 温度 */
    /** 双线迷你图: CPU 百分比 + SoC 温度, 横轴 1 分钟(右 0s -> 左 1 分钟) */
    private inner class SparkView(c: Context) : View(c) {
        private var cpu: List<Float> = emptyList()
        private var temp: List<Float> = emptyList()
        private var anchor = 0L
        private var delta = 1000f
        private var gradH = -1
        private val pCpu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(1).toFloat().coerceAtLeast(1.2f)
            color = ACCENT
        }
        private val pTemp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(1).toFloat().coerceAtLeast(1.2f)
            color = WARN
        }
        private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = GRID
        }
        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ACCENT_FILL
        }
        private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * resources.displayMetrics.density
            color = SUB
            textAlign = Paint.Align.CENTER
        }
        private val animRun = object : Runnable {
            override fun run() {
                invalidate()
                if (phaseNow() < 1f && isAttachedToWindow) postDelayed(this, 33L)
            }
        }

        private fun phaseNow(): Float {
            if (anchor == 0L) return 1f
            return ((SystemClock.elapsedRealtime() - anchor).toFloat() / delta).coerceIn(0f, 1f)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            removeCallbacks(animRun)
            post(animRun)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(animRun)
        }

        fun setData(c: List<Float>, t: List<Float>) {
            val now = SystemClock.elapsedRealtime()
            if (anchor != 0L) delta = (now - anchor).toFloat().coerceIn(600f, 3000f)
            anchor = now
            cpu = c
            temp = t
            removeCallbacks(animRun)
            post(animRun)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val density = resources.displayMetrics.density
            val axisH = 13f * density
            val h = height.toFloat() - axisH
            if (w <= 0f || h <= 0f) return
            for (i in 1..3) {
                val y = h * i / 4f
                canvas.drawLine(0f, y, w, y, pGrid)
            }
            val step = w / (PLOT_POINTS - 1)
            if (cpu.size < 2) {
                drawTimeAxis(canvas, w, step, h, height.toFloat(), pAxis)
                return
            }
            val phase = phaseNow()
            // 最新一点先画在右边界外一格(视口外), 随 phase 滑进可视区: 新段是"滑入"而非在边缘突变
            val lead = (1f - phase) * step
            val x0 = w + lead - (cpu.size - 1) * step
            if (gradH != height) {
                gradH = height
                pFill.shader = LinearGradient(0f, 0f, 0f, h, ACCENT_FILL, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP)
            }
            val yOf = { v: Float -> h - h * (v.coerceIn(0f, 100f) / 100f) }
            val line = smoothPath(cpu, x0, step, yOf, h)
            val xLast = x0 + (cpu.size - 1) * step
            val fill = Path(line)
            fill.lineTo(xLast, h)
            fill.lineTo(x0, h)
            fill.close()
            canvas.drawPath(fill, pFill)
            canvas.drawPath(line, pCpu)
            if (temp.size >= 2) {
                val tX0 = w + lead - (temp.size - 1) * step
                val tYOf = { v: Float -> h - h * (((v - 30f) / 45f).coerceIn(0f, 1f)) }
                val t = smoothPath(temp, tX0, step, tYOf, h)
                canvas.drawPath(t, pTemp)
            }
            drawTimeAxis(canvas, w, step, h, height.toFloat(), pAxis)
        }
    }


    companion object {
        /** 曲线窗口: 60 点 x 1s = 1 分钟(手机屏宽下点距约 16px, 波动不挤) */
        const val PLOT_POINTS = 60
        private val BG = Color.parseColor("#0E1014")
        private val CARD = Color.parseColor("#171A20")
        private val CARD2 = Color.parseColor("#1E222A")
        private val TXT = Color.parseColor("#E8EBF0")
        private val SUB = Color.parseColor("#8B93A1")
        private val ACCENT = Color.parseColor("#5B8DEF")
        private val ACCENT_BG = Color.parseColor("#20304F")
        private val ACCENT_FILL = Color.parseColor("#335B8DEF")
        private val WARN = Color.parseColor("#E8A33D")
        private val RED = Color.parseColor("#E8685D")
        private val GRID = Color.parseColor("#262B34")
    }
}
