package io.github.aixtin.nyral

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
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

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            refresh()
            ui.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.resetPrivilegeCache()
        setContentView(buildRoot())
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

    // ===================== UI =====================

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
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
            }
            box.addView(sub)
            return Triple(box, v, sub)
        }
        fun row(vararg cells: Triple<LinearLayout, TextView, TextView>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cells.forEachIndexed { i, c ->
                r.addView(c.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(72)).apply { topMargin = dpi(6) })
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
        netCard.addView(sectionTitle("网络"))
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
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(72)).apply { topMargin = dpi(6) })
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
        head.addView(sectionTitle("近 5 分钟").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = "CPU ● 温度 ●"
            textSize = 10.5f
            setTextColor(SUB)
        })
        sparkCard.addView(head)
        spark = SparkView(this)
        sparkCard.addView(spark, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(92)).apply { topMargin = dpi(8) })
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
            val (cpuSeries, tempSeries) = DeviceMetrics.series()
            val peaks = DeviceMetrics.peaks()
            val verdicts = try { DeviceMetrics.verdicts(s) } catch (e: Throwable) { emptyList<String>() }
            ui.post {
                if (isFinishing || isDestroyed || !alive) return@post
                bindValues(s)
                spark.setData(cpuSeries, tempSeries)
                val (dnSeries, upSeries) = DeviceMetrics.netHistory()
                netSpark.setData(dnSeries, upSeries)
                statusText.text = "峰值 CPU " + fmt(peaks.first.toDouble()) + "%" +
                        (if (peaks.second > 0f) " · 温度 " + fmt(peaks.second.toDouble()) + "℃" else "") +
                        " · " + cpuSeries.size + " 点 · " + timeNow()
                verdictText.text = if (verdicts.isEmpty()) "各项读数正常区间内(只陈述事实, 不做评分)" else verdicts.joinToString("\n") { "· " + it }
            }
        }.start()
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
        tempVal.text = if (soc > 0) fmt(soc) + "℃" else "--"
        tempSub.text = if (temp.has("batt_c")) "电池 " + fmt(temp.optDouble("batt_c")) + "℃" else "读不到温度节点"
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
                    text = m.optString("label") + "   " + fmt(m.optDouble("free_gb")) + "G 可用 / " +
                            fmt(m.optDouble("total_gb")) + "G · 剩 " + fmt(m.optDouble("free_pct")) + "%"
                    textSize = 12.5f
                    setTextColor(if (m.optDouble("free_pct", 100.0) < 10.0) RED else TXT)
                    setPadding(0, dpi(3), 0, 0)
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

        fun setData(v: List<Float>) {
            vals = v
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val labelH = 12f * resources.displayMetrics.density
            val barH = h - labelH
            canvas.drawLine(0f, barH * 0.5f, w, barH * 0.5f, pGrid)
            if (vals.isEmpty()) {
                canvas.drawText("无分核读数", w / 2f, barH / 2f, pText)
                return
            }
            val n = vals.size
            val slot = w / n
            val barW = minOf(slot * 0.62f, 26f * resources.displayMetrics.density)
            vals.forEachIndexed { i, v ->
                val x = slot * i + slot / 2f
                val track = RectF(x - barW / 2f, 0f, x + barW / 2f, barH)
                canvas.drawRoundRect(track, barW / 4f, barW / 4f, pTrack)
                if (v >= 0f) {
                    val top = barH - barH * (v.coerceIn(0f, 100f) / 100f)
                    pBar.color = when {
                        v >= 85f -> RED
                        v >= 60f -> WARN
                        else -> ACCENT
                    }
                    canvas.drawRoundRect(RectF(x - barW / 2f, top, x + barW / 2f, barH), barW / 4f, barW / 4f, pBar)
                }
                canvas.drawText(i.toString(), x, h - 1f, pText)
            }
        }
    }

    /** 网络曲线: 上下行双线, 纵向按当前峰值自适应, 低速也不会被压成直线 */
    private inner class NetView(c: Context) : View(c) {
        private var down: List<Float> = emptyList()
        private var up: List<Float> = emptyList()
        private val pDown = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(2).toFloat()
            color = ACCENT
        }
        private val pUp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(2).toFloat()
            color = WARN
        }
        private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = GRID
        }
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * resources.displayMetrics.density
            color = SUB
        }

        fun setData(d: List<Float>, u: List<Float>) {
            down = d
            up = u
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            for (i in 1..3) {
                val y = h * i / 4f
                canvas.drawLine(0f, y, w, y, pGrid)
            }
            val peak = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f, 1f)
            canvas.drawText("峰 " + speed(peak.toDouble()), 2f * resources.displayMetrics.density,
                10f * resources.displayMetrics.density, pText)
            fun line(data: List<Float>, p: Paint) {
                if (data.size < 2) return
                val step = w / (data.size - 1).coerceAtLeast(1)
                val path = Path()
                data.forEachIndexed { i, v ->
                    val y = h - h * (v.coerceIn(0f, peak) / peak)
                    if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
                }
                canvas.drawPath(path, p)
            }
            line(down, pDown)
            line(up, pUp)
        }
    }

    /** 双线迷你图: CPU 百分比 + SoC 温度 */
    private inner class SparkView(c: Context) : View(c) {
        private var cpu: List<Float> = emptyList()
        private var temp: List<Float> = emptyList()
        private val pCpu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(2).toFloat()
            color = ACCENT
        }
        private val pTemp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(2).toFloat()
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

        fun setData(c: List<Float>, t: List<Float>) {
            cpu = c
            temp = t
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // 三档横网格(25/50/75%)
            for (i in 1..3) {
                val y = h * i / 4f
                canvas.drawLine(0f, y, w, y, pGrid)
            }
            if (cpu.size < 2) return
            val step = w / (cpu.size - 1).coerceAtLeast(1)
            // CPU 面积
            val fill = Path()
            fill.moveTo(0f, h)
            cpu.forEachIndexed { i, v -> fill.lineTo(i * step, h - h * (v.coerceIn(0f, 100f) / 100f)) }
            fill.lineTo((cpu.size - 1) * step, h)
            fill.close()
            canvas.drawPath(fill, pFill)
            // CPU 线
            val line = Path()
            cpu.forEachIndexed { i, v ->
                val y = h - h * (v.coerceIn(0f, 100f) / 100f)
                if (i == 0) line.moveTo(0f, y) else line.lineTo(i * step, y)
            }
            canvas.drawPath(line, pCpu)
            // 温度线(30~75℃ 归一)
            if (temp.size >= 2) {
                val tStep = w / (temp.size - 1).coerceAtLeast(1)
                val t = Path()
                temp.forEachIndexed { i, v ->
                    val n = ((v - 30f) / 45f).coerceIn(0f, 1f)
                    val y = h - h * n
                    if (i == 0) t.moveTo(0f, y) else t.lineTo(i * tStep, y)
                }
                canvas.drawPath(t, pTemp)
            }
        }
    }

    companion object {
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
