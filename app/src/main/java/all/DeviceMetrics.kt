package io.github.aixtin.nyral

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * 设备面板数据层(2026-10-30 老板定调: 做只读设备面板, 不做消费级体检)。
 *
 * 定位: 给调试模块/内核的极客看事实读数 —— CPU 占用、最高核频率、内存、SoC 温度、电池、负载、
 * 数据分区余量, 以及最近 5 分钟趋势峰值。**只读不写**: 不修改任何系统参数(与 Scene 的改参数正相反)。
 *
 * 上下文策略: 动态指标不进对话上下文; 模型要看时按需调 device_snapshot / device_probe 取。
 */
object DeviceMetrics {

    /** 环形缓冲上限: 1s 一点, 300 点 = 5 分钟 */
    const val MAX_POINTS = 300
    /** 采样节流: 面板 2s 刷一次, 模型连调也不重复压系统 */
    private const val MIN_INTERVAL_MS = 900L

    private val cpuSeries = ArrayDeque<Float>()
    private val tempSeries = ArrayDeque<Float>()
    private val lock = Any()

    @Volatile private var lastSampleAt = 0L
    @Volatile private var lastSnapshot: JSONObject? = null

    private var lastTotal = 0L
    private var lastIdle = 0L

    // 分核基线(核数动态: 4/6/8/10 核都能算)
    private val coreTotals = HashMap<Int, Long>()
    private val coreIdles = HashMap<Int, Long>()

    // 网络速率基线(/proc/net/dev 累计字节)
    private var lastNetRx = -1L
    private var lastNetTx = -1L
    private var lastNetAt = 0L
    private val netDownSeries = ArrayDeque<Float>()
    private val netUpSeries = ArrayDeque<Float>()
    private val memSeriesQ = ArrayDeque<Float>()

    // ===================== 对外 =====================

    /** 单次采样(节流): 读一次全部动态指标并进环形缓冲 */
    fun sample(c: Context): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val cached = lastSnapshot
        if (cached != null && now - lastSampleAt < MIN_INTERVAL_MS) return cached
        lastSampleAt = now
        val o = JSONObject()
        try {
            val cpu = cpuBlock()
            o.put("cpu", cpu)
            val mem = memBlock(c)
            o.put("mem", mem)
            val temp = tempBlock(c, cpu)
            o.put("temp", temp)
            o.put("batt", battBlock(c))
            o.put("load", loadBlock())
            o.put("disk", diskBlock())
            o.put("net", netBlock())
            o.put("uptime", uptimeText())
            o.put("ts", System.currentTimeMillis())
            val pct = cpu.optDouble("pct", 0.0).toFloat()
            val t = temp.optDouble("soc_c", -1.0).toFloat()
            val memPct = mem.optDouble("used_pct", -1.0).toFloat()
            val netObj = o.optJSONObject("net")
            val dn = netObj?.optDouble("down_kbps", -1.0) ?: -1.0
            val upK = netObj?.optDouble("up_kbps", -1.0) ?: -1.0
            synchronized(lock) {
                push(cpuSeries, pct)
                if (t > 0f) push(tempSeries, t)
                if (memPct >= 0f) push(memSeriesQ, memPct)
                if (dn >= 0.0) {
                    push(netDownSeries, dn.toFloat())
                    push(netUpSeries, upK.toFloat())
                }
            }
        } catch (e: Throwable) {
            o.put("error", e.message ?: e.javaClass.simpleName)
        }
        lastSnapshot = o
        return o
    }

    /** 面板曲线: Pair(CPU 百分比序列, SoC 温度序列) */
    fun series(): Pair<List<Float>, List<Float>> = synchronized(lock) {
        Pair(ArrayList(cpuSeries), ArrayList(tempSeries))
    }

    /** 网络曲线: Pair(下行 KB/s, 上行 KB/s) */
    fun netHistory(): Pair<List<Float>, List<Float>> = synchronized(lock) {
        Pair(ArrayList(netDownSeries), ArrayList(netUpSeries))
    }

    /** 内存占用曲线(%) */
    fun memHistory(): List<Float> = synchronized(lock) { ArrayList(memSeriesQ) }

    /** 近 5 分钟峰值 */
    fun peaks(): Pair<Float, Float> = synchronized(lock) {
        Pair(cpuSeries.maxOrNull() ?: 0f, tempSeries.maxOrNull() ?: 0f)
    }

    /** 静态画像: 只在进程内算一次, 主要给"问 AI"时随快照带上 */
    fun profile(c: Context): JSONObject {
        val o = JSONObject()
        try {
            o.put("brand", Build.BRAND)
            o.put("model", Build.MODEL)
            o.put("device", Build.DEVICE)
            o.put("android", Build.VERSION.RELEASE)
            o.put("api", Build.VERSION.SDK_INT)
            o.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            o.put("kernel", System.getProperty("os.version") ?: "")
            o.put("cores", Runtime.getRuntime().availableProcessors())
            o.put("soc", socName())
            o.put("root", if (Diagnostics.currentLevel() >= 2) "su" else "无")
            o.put("shizuku", when (Diagnostics.shizukuState()) {
                2 -> "已授权"; 1 -> "已安装未授权"; else -> "未运行"
            })
        } catch (e: Throwable) {
            o.put("error", e.message ?: e.javaClass.simpleName)
        }
        return o
    }

    /** 工具出口: 按 fields 裁剪(省略=全部) */
    fun snapshot(c: Context, fields: List<String>): JSONObject {
        val want = fields.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun has(k: String) = want.isEmpty() || want.contains(k)
        val o = JSONObject()
        if (has("profile")) o.put("profile", profile(c))
        val s = sample(c)
        if (has("cpu")) o.put("cpu", s.optJSONObject("cpu") ?: JSONObject())
        if (has("mem")) o.put("mem", s.optJSONObject("mem") ?: JSONObject())
        if (has("temp")) o.put("temp", s.optJSONObject("temp") ?: JSONObject())
        if (has("batt")) o.put("batt", s.optJSONObject("batt") ?: JSONObject())
        if (has("load")) o.put("load", s.optJSONObject("load") ?: JSONObject())
        if (has("disk")) o.put("disk", s.optJSONObject("disk") ?: JSONObject())
        if (has("series")) {
            val (cpu, temp) = series()
            o.put("series", JSONObject()
                .put("cpu_pct", JSONArray(cpu.map { round1(it.toDouble()) }))
                .put("soc_c", JSONArray(temp.map { round1(it.toDouble()) })))
        }
        val (pc, pt) = peaks()
        o.put("peak5m", JSONObject().put("cpu_pct", round1(pc.toDouble())).put("soc_c", round1(pt.toDouble())))
        o.put("uptime", s.optString("uptime", ""))
        o.put("ts", s.optLong("ts", System.currentTimeMillis()))
        return o
    }

    /**
     * 只读探针: 追单个包/进程的事实(pid、常驻内存、线程数、状态、root 模块是否命中该名)。
     * target 走白名单正则, 不接受任何 shell 元字符。
     */
    fun probe(c: Context, target: String): JSONObject {
        val t = target.trim()
        val o = JSONObject().put("target", t)
        if (t.isEmpty() || !TARGET_RE.matches(t)) {
            return o.put("error", "target 只允许 字母/数字/_/./- , 例如 com.tencent.mm 或 system_server")
        }
        val isPkg = t.contains('.')
        try {
            if (isPkg) {
                val pm = c.packageManager
                try {
                    val pi = pm.getPackageInfo(t, 0)
                    o.put("installed", true)
                    o.put("version", pi.versionName ?: "")
                    o.put("versionCode", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong())
                    o.put("targetSdk", pi.applicationInfo?.targetSdkVersion ?: 0)
                    o.put("systemApp", ((pi.applicationInfo?.flags ?: 0) and 1) != 0)
                    o.put("firstInstall", pi.firstInstallTime)
                    o.put("lastUpdate", pi.lastUpdateTime)
                    o.put("apkSizeMb", round1(File(pi.applicationInfo?.sourceDir ?: "").length() / 1048576.0))
                } catch (e: Throwable) {
                    o.put("installed", false)
                }
            }
            val pid = pidOf(c, t, isPkg)
            if (pid <= 0) {
                o.put("running", false)
                o.put("hint", if (isPkg) "进程当前不在运行(或通道不足看不到 pid)" else "进程不存在")
            } else {
                o.put("running", true)
                o.put("pid", pid)
                val st = readText("/proc/$pid/status", privileged = true)
                if (st == null) {
                    o.put("hint", "无特权通道, 读不到 /proc/$pid/status (需 Shizuku 或 root)")
                } else {
                    o.put("proc", parseStatus(st))
                }
                val cmd = readText("/proc/$pid/cmdline", privileged = true)
                if (cmd != null) o.put("cmdline", cmd)
            }
            // root 模块命中: 只在有特权通道时查, 结果为空也如实说明
            val mod = if (Diagnostics.currentLevel() >= 1) {
                Diagnostics.execRead("lsmod 2>/dev/null | grep -i -- " + shellSafe(t))
            } else null
            o.put("kernel_module", when {
                mod == null -> "未查(无特权通道)"
                mod.isBlank() -> "无同名内核模块"
                else -> mod.trim().lineSequence().take(3).joinToString(" | ")
            })
        } catch (e: Throwable) {
            o.put("error", e.message ?: e.javaClass.simpleName)
        }
        return o
    }

    /** 给「问 AI」用的纯文本快照: 只带这一次的读数 + 静态画像, 不带趋势原始序列 */
    fun dumpText(c: Context): String {
        val sb = StringBuilder()
        val p = profile(c)
        sb.append("[设备] ").append(p.optString("model")).append(" (").append(p.optString("device"))
            .append(") · Android ").append(p.optString("android"))
            .append(" (API ").append(p.optInt("api")).append(") · ")
            .append(p.optString("soc")).append(" · ").append(p.optInt("cores")).append(" 核\n")
        sb.append("[权限] root=").append(p.optString("root")).append(" · Shizuku=")
            .append(p.optString("shizuku")).append("\n")
        val s = sample(c)
        val cpu = s.optJSONObject("cpu") ?: JSONObject()
        // 读不到占用率就写 "不可读", 不写 0.0% —— 假数字会让 AI 误判成"机器完全空闲"
        sb.append("[CPU] ")
        if (cpu.has("pct")) sb.append(round1(cpu.optDouble("pct"))).append("%")
        else sb.append("不可读(本机 /proc/stat 对普通应用隐藏, 需 root/Shizuku 授权)")
        if (cpu.has("max_mhz")) sb.append(" · 最高核 ").append(round1(cpu.optDouble("max_mhz"))).append("MHz")
        if (cpu.has("avg_mhz")) sb.append(" · 平均 ").append(round1(cpu.optDouble("avg_mhz"))).append("MHz")
        sb.append("\n")
        val mem = s.optJSONObject("mem") ?: JSONObject()
        sb.append("[内存] 用 ").append(round1(mem.optDouble("used_mb"))).append(" / ")
            .append(round1(mem.optDouble("total_mb"))).append("MB (")
            .append(round1(mem.optDouble("used_pct"))).append("%) · 可用 ")
            .append(round1(mem.optDouble("avail_mb"))).append("MB")
        if (mem.has("swap_used_mb")) sb.append(" · swap ").append(round1(mem.optDouble("swap_used_mb"))).append("MB")
        sb.append("\n")
        val temp = s.optJSONObject("temp") ?: JSONObject()
        sb.append("[温度] SoC ").append(optTemp(temp.optDouble("soc_c", -1.0)))
        sb.append(" · 电池 ").append(optTemp(temp.optDouble("batt_c", -1.0))).append("\n")
        val b = s.optJSONObject("batt") ?: JSONObject()
        sb.append("[电池] ").append(b.optInt("level")).append("% · ").append(b.optString("status"))
        if (b.has("current_ma")) sb.append(" · ").append(round1(b.optDouble("current_ma"))).append("mA")
        if (b.has("volt_v")) sb.append(" · ").append(round1(b.optDouble("volt_v"))).append("V")
        sb.append("\n")
        val load = s.optJSONObject("load") ?: JSONObject()
        sb.append("[负载] ").append(load.optString("loadavg", "不可读(需 root/Shizuku)")).append(" · 运行 ")
            .append(s.optString("uptime")).append("\n")
        val d = s.optJSONObject("disk") ?: JSONObject()
        sb.append("[数据分区] 剩 ").append(round1(d.optDouble("free_gb"))).append("GB / 共 ")
            .append(round1(d.optDouble("total_gb"))).append("GB")
        if (d.optDouble("free_pct", 100.0) < 10.0) sb.append(" (余量偏低)")
        sb.append("\n")
        val (pc, pt) = peaks()
        sb.append("[近5分钟峰值] ")
        if (cpu.has("pct")) sb.append("CPU ").append(round1(pc.toDouble())).append("%") else sb.append("CPU 不可读")
        if (pt > 0f) sb.append(" · SoC ").append(round1(pt.toDouble())).append("℃")
        sb.append("\n")
        val verdicts = verdicts(s)
        if (verdicts.isNotEmpty()) {
            sb.append("[判定] ").append(verdicts.joinToString(" · ")).append("\n")
        }
        return sb.toString()
    }

    /** 面板上的短判定(纯事实口径, 不打分) */
    fun verdicts(s: JSONObject): List<String> {
        val out = ArrayList<String>()
        val cpuPct = s.optJSONObject("cpu")?.optDouble("pct", 0.0) ?: 0.0
        if (cpuPct >= 85.0) out.add("CPU 接近满载") else if (cpuPct >= 60.0) out.add("CPU 偏高")
        val memPct = s.optJSONObject("mem")?.optDouble("used_pct", 0.0) ?: 0.0
        if (memPct >= 88.0) out.add("内存吃紧")
        val tObj = s.optJSONObject("temp")
        val socC = tObj?.optDouble("soc_c", -1.0) ?: -1.0
        if (tObj != null && tObj.optInt("soc_suspect", 0) == 1) {
            out.add("SoC 温度读数异常(疑似钝化), 已跳过过热判定")
        } else if (socC >= 60.0) out.add("SoC 过热, 可能在降频") else if (socC >= 45.0) out.add("SoC 偏热")
        val freeGb = s.optJSONObject("disk")?.optDouble("free_gb", 999.0) ?: 999.0
        if (freeGb < 5.0) out.add("存储余量不足")
        val b = s.optJSONObject("batt")
        if (b != null && b.optString("status") == "放电" && b.optDouble("current_ma", 0.0) > 1500.0) {
            out.add("放电电流偏大")
        }
        return out
    }

    // ===================== 内部读取 =====================

    private val TARGET_RE = Regex("^[A-Za-z0-9_.:\\-]{1,80}$")

    private fun shellSafe(s: String) = "'" + s.replace("'", "") + "'"

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > MAX_POINTS) q.removeFirst()
    }

    private fun cpuBlock(): JSONObject {
        val o = JSONObject()
        val stat = readText("/proc/stat", true)
        if (stat != null) {
            val perCore = JSONArray()
            for (raw in stat.lineSequence()) {
                val line = raw.trim()
                if (!line.startsWith("cpu")) continue
                val head = line.substringBefore(' ')
                val idx = if (head == "cpu") -1 else head.removePrefix("cpu").toIntOrNull() ?: continue
                val nums = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (nums.size < 4) continue
                val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
                val total = nums.sum()
                if (idx < 0) {
                    val dTotal = total - lastTotal
                    val dIdle = idle - lastIdle
                    lastTotal = total
                    lastIdle = idle
                    val pct = if (dTotal > 0) (dTotal - dIdle) * 100.0 / dTotal else 0.0
                    o.put("pct", (pct * 10).roundToInt() / 10.0)
                } else {
                    val pTotal = coreTotals[idx] ?: -1L
                    val pIdle = coreIdles[idx] ?: -1L
                    coreTotals[idx] = total
                    coreIdles[idx] = idle
                    val dTotal = if (pTotal < 0) 0L else total - pTotal
                    val dIdle = if (pIdle < 0) 0L else idle - pIdle
                    val c = if (dTotal > 0) (dTotal - dIdle) * 100.0 / dTotal else -1.0
                    perCore.put(if (c < 0) -1.0 else (c * 10).roundToInt() / 10.0)
                }
            }
            if (perCore.length() > 0) o.put("per_core_pct", perCore)
        }
        val cores = Runtime.getRuntime().availableProcessors()
        o.put("cores", cores)
        var maxMhz = 0.0
        var sumMhz = 0.0
        var counted = 0
        for (i in 0 until cores) {
            val f = readText("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toDoubleOrNull() ?: continue
            val mhz = f / 1000.0
            if (mhz > maxMhz) maxMhz = mhz
            sumMhz += mhz
            counted++
        }
        if (counted > 0) {
            o.put("max_mhz", (maxMhz * 10).roundToInt() / 10.0)
            o.put("avg_mhz", ((sumMhz / counted) * 10).roundToInt() / 10.0)
        }
        val peakKhz = readText("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")?.toDoubleOrNull()
        if (peakKhz != null) o.put("cap_mhz", (peakKhz / 1000.0 * 10).roundToInt() / 10.0)
        return o
    }

    private fun memBlock(c: Context): JSONObject {
        val o = JSONObject()
        val mi = ActivityManager.MemoryInfo()
        try {
            (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            o.put("total_mb", round1(mi.totalMem / 1048576.0))
            o.put("avail_mb", round1(mi.availMem / 1048576.0))
            o.put("used_mb", round1((mi.totalMem - mi.availMem) / 1048576.0))
            o.put("used_pct", if (mi.totalMem > 0) round1((mi.totalMem - mi.availMem) * 100.0 / mi.totalMem) else 0.0)
            o.put("low_mem", mi.lowMemory)
            o.put("threshold_mb", round1(mi.threshold / 1048576.0))
        } catch (_: Throwable) {
        }
        val mi2 = readText("/proc/meminfo")
        if (mi2 != null) {
            var swapTotal = 0.0
            var swapFree = 0.0
            var cached = 0.0
            for (line in mi2.lineSequence()) {
                val kv = line.split(':')
                if (kv.size < 2) continue
                val kb = kv[1].trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: continue
                when (kv[0].trim()) {
                    "SwapTotal" -> swapTotal = kb
                    "SwapFree" -> swapFree = kb
                    "Cached" -> cached = kb
                }
            }
            if (swapTotal > 0) {
                o.put("swap_total_mb", round1(swapTotal / 1024.0))
                o.put("swap_used_mb", round1((swapTotal - swapFree) / 1024.0))
                o.put("swap_used_pct", round1((swapTotal - swapFree) * 100.0 / swapTotal))
            }
            if (cached > 0) o.put("cached_mb", round1(cached / 1024.0))
        }
        return o
    }

    private fun tempBlock(c: Context, cpu: JSONObject): JSONObject {
        val o = JSONObject()
        val soc = socTemp()
        if (soc != null) o.put("soc_c", round1(soc.toDouble()))
        var battC = -1.0
        try {
            val i = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val it0 = c.registerReceiver(null, i)
            val raw = it0?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (raw > 0) battC = raw / 10.0
        } catch (_: Throwable) {
        }
        if (battC > 0) o.put("batt_c", round1(battC))
        // 温差大时给一句事实描述: 常见于重载降频
        if (soc != null && battC > 0) o.put("delta_c", round1(soc - battC))
        if (socSrc.isNotEmpty()) o.put("soc_src", socSrc)
        // 可疑读数: 长期纹丝不动, 或空载下比电池高出 40℃ 以上(物理上不成立)
        val cpuPct = cpu.optDouble("pct", -1.0)
        var suspect = socSameCount >= 8
        if (!suspect && soc != null && battC > 0 &&
            soc - battC >= 40.0 && (cpuPct < 0.0 || cpuPct < 40.0)
        ) suspect = true
        if (suspect) o.put("soc_suspect", 1)
        dbg("temp 最终 soc=" + o.opt("soc_c") + " batt=" + o.opt("batt_c") +
                " src=" + socSrc + " suspect=" + suspect)
        return o
    }

    /**
     * SoC 温度: sysfs 优先; ColorOS 等对 shell(shell uid 的 Shizuku 通道)也屏蔽了
     * thermal_zone 目录下的 type 与 temp(读出来是空串), 于是兜底走 thermal HAL 的上报值
     * (dumpsys thermalservice), 该路径返回的就是 SoC/GPU 的真实温度。
     */
    private var socCacheAt = 0L
    private var socCacheV: Float? = null
    private var socSrc: String = ""
    private var socSameCount = 0
    private var socLastV: Float = -1f
    private var shSocCacheAt = 0L
    private var shSocCacheV: Float? = null

    /**
     * 取数顺序: 进程内 sysfs → 特权通道读 sysfs → thermal HAL 上报。
     * 部分 OEM(OxygenOS/ColorOS)的 HAL 会把 CPU 项钉成常数(实测一加恒定 80.0, 而电池仅 23℃),
     * 所以只要能读到 sysfs 真实节点就以节点为准, HAL 仅作最后兜底。
     */
    private fun socTemp(): Float? {
        sysfsSocTemp()?.let { track(it, "sysfs"); dbg("A 进程内直读 $it"); return it }
        shSocTemp()?.let { track(it, "sysfs-su"); dbg("B 特权读节点 $it"); return it }
        val now = SystemClock.elapsedRealtime()
        if (socCacheV != null && now - socCacheAt < 2000L) return socCacheV
        if (Diagnostics.currentLevel() < 1) { dbg("C 无特权通道, 放弃"); return null }
        val dump = Diagnostics.execRead("dumpsys thermalservice", 4000L) ?: return null
        val v = thermalHalSocTemp(dump)
        if (v != null) {
            socCacheV = v
            socCacheAt = now
            track(v, "hal")
            dbg("C HAL 兜底 $v")
        }
        return v
    }

    private fun dbg(msg: String) {
        try {
            android.util.Log.d("NyralTemp", msg + " | level=" + Diagnostics.currentLevel())
        } catch (_: Throwable) {
        }
    }

    /** 记录读数是否长期卡死: 钝化值不参与过热判定 */
    private fun track(v: Float, src: String) {
        socSrc = src
        if (v == socLastV) socSameCount++ else {
            socSameCount = 0
            socLastV = v
        }
    }

    /** 特权通道读 thermal_zone: 绕过 shell uid 的 SELinux 屏蔽 */
    private fun shSocTemp(): Float? {
        val now = SystemClock.elapsedRealtime()
        if (shSocCacheV != null && now - shSocCacheAt < 3000L) return shSocCacheV
        val cmd = "for z in /sys/class/thermal/thermal_zone*; do " +
                "t=\$(cat \$z/type 2>/dev/null); v=\$(cat \$z/temp 2>/dev/null); " +
                "[ -n \"\$v\" ] && echo \"\$t=\$v\"; done"
        // Shizuku/shell 通道常返回空串(cat 被拒但不报错), 必须在解析失败后才继续尝试 root
        val shellOut = Diagnostics.execRead(cmd, 4000L)
        var v = parseZoneDump(shellOut ?: "")
        dbg("B1 shell 通道 len=" + (shellOut?.length ?: -1) + " -> " + v)
        if (v == null) {
            val rootOut = Diagnostics.execRoot(cmd, 4000L)
            v = parseZoneDump(rootOut ?: "")
            dbg("B2 root 通道 len=" + (rootOut?.length ?: -1) + " -> " + v)
        }
        if (v == null) return null
        shSocCacheV = v
        shSocCacheAt = now
        return v
    }

    /** 解析 "type=temp" 列表: 只认 SoC/CPU/GPU 类节点, 取最高值(自动换算 千分之一℃) */
    private fun parseZoneDump(out: String): Float? {
        val got = ArrayList<Pair<String, Float>>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            val eq = line.lastIndexOf('=')
            if (eq <= 0) continue
            val type = line.substring(0, eq).lowercase()
            val rawV = line.substring(eq + 1).trim().toFloatOrNull() ?: continue
            got.add(type to rawV)
        }
        return pickZone(got)
    }

    /** 直读 /sys/class/thermal(不需要特权, 能读就最快; 读不到返回 null) */
    private fun sysfsSocTemp(): Float? {
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles() ?: return null
        val got = ArrayList<Pair<String, Float>>()
        for (z in zones) {
            if (!z.name.startsWith("thermal_zone")) continue
            val type = readText(z.path + "/type")?.lowercase() ?: continue
            val raw = readText(z.path + "/temp")?.trim()?.toFloatOrNull() ?: continue
            got.add(type to raw)
        }
        return pickZone(got)
    }

    /**
     * 节点分类: 0=CPU 类, 1=GPU/其他 die 类, -1=忽略。
     * OEM 会把非温度量塞进 thermal_zone —— 一加的 socd(qcom SoC droop)恒为 80,
     * 按 "soc" 子串匹配就会把它当成 SoC 温度(实测踩坑), 必须按节点名精确筛。
     */
    private fun zoneClass(type: String): Int {
        if (type.contains("socd") || type.contains("droop") || type.contains("bcl")) return -1
        if (type.contains("limit") || type.contains("trip") || type.contains("cool")) return -1
        if (type.contains("vbat") || type.contains("ibat") || type.contains("batt")) return -1
        if (Regex("^(cpu|cpuss)[-_0-9]").containsMatchIn(type)) return 0
        if (Regex("^(gpu|gpuss)[-_0-9]").containsMatchIn(type)) return 1
        if (type.contains("tsens") || type.contains("soc_therm") || type.contains("soc-therm") ||
            type.contains("silver") || type.contains("gold")
        ) return 1
        return -1
    }

    /** 优先取 CPU 类节点的最高温, 没有则退 GPU/其他 die 类 */
    private fun pickZone(list: List<Pair<String, Float>>): Float? {
        var cpu: Float? = null
        var alt: Float? = null
        for ((type, raw) in list) {
            val cls = zoneClass(type)
            if (cls < 0) continue
            val v = if (raw > 1000f) raw / 1000f else raw
            if (v !in 5f..120f) continue
            if (cls == 0) cpu = if (cpu == null) v else maxOf(cpu!!, v)
            else alt = if (alt == null) v else maxOf(alt!!, v)
        }
        return cpu ?: alt
    }

    /** 解析 dumpsys thermalservice 的 "Current temperatures from HAL" 段, 取 CPU 温度(退而取最高温) */
    private fun thermalHalSocTemp(dump: String): Float? {
        var inHal = false
        var cpu: Float? = null
        var gpu: Float? = null
        var maxV: Float? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Current temperatures from HAL")) { inHal = true; continue }
            if (inHal && line.startsWith("Current cooling devices")) break
            if (!inHal || !line.startsWith("Temperature{")) continue
            val v = Regex("mValue=([-0-9.]+)").find(line)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            if (v !in 1f..130f) continue
            val n = Regex("mName=([A-Za-z_0-9]+)").find(line)?.groupValues?.get(1) ?: ""
            // 只认 CPU/GPU 传感器项; socd/skin/vbat/ibat/nsp 等不是 die 温度(部分机型 socd 恒定 80)
            val isCpu = n.startsWith("CPU", true)
            val isGpu = n.startsWith("GPU", true)
            if (!isCpu && !isGpu) continue
            if (isCpu) cpu = if (cpu == null) v else maxOf(cpu!!, v)
            if (isGpu) gpu = if (gpu == null) v else maxOf(gpu!!, v)
            if (maxV == null || v > maxV!!) maxV = v
        }
        return cpu ?: gpu ?: maxV
    }

    private fun battBlock(c: Context): JSONObject {
        val o = JSONObject()
        try {
            val it0 = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return o
            val level = it0.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it0.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = it0.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            o.put("level", if (scale > 0) (level * 100 / scale) else level)
            o.put("status", when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电"
                BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            })
            val mv = it0.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (mv > 0) o.put("volt_v", round1(mv / 1000.0))
            try {
                val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (ua != 0L && ua != Long.MIN_VALUE) o.put("current_ma", round1(ua / 1000.0))
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
        return o
    }

    /**
     * 网络速率: /proc/net/dev 在 Android 10+ 对普通应用屏蔽, 走 Shizuku/root 只读通道;
     * 累计字节做差值算速率, 面板 2s 一刷即是 2s 平均。
     */
    private fun netBlock(): JSONObject {
        val o = JSONObject()
        val raw = readText("/proc/net/dev", true)
        if (raw == null) return o
        var rx = 0L
        var tx = 0L
        for (line in raw.lineSequence()) {
            val i = line.indexOf(':')
            if (i < 0) continue
            val name = line.substring(0, i).trim()
            if (name == "lo" || name.startsWith("ifb") || name.startsWith("dummy") || name.startsWith("rmnet_ipa")) continue
            val nums = line.substring(i + 1).trim().split(Regex("\\s+"))
            rx += nums.getOrNull(0)?.toLongOrNull() ?: 0L
            tx += nums.getOrNull(8)?.toLongOrNull() ?: 0L
        }
        val now = SystemClock.elapsedRealtime()
        if (lastNetRx >= 0 && now > lastNetAt) {
            val dt = (now - lastNetAt) / 1000.0
            o.put("down_kbps", round1(maxOf(0L, rx - lastNetRx) / 1024.0 / dt))
            o.put("up_kbps", round1(maxOf(0L, tx - lastNetTx) / 1024.0 / dt))
        }
        lastNetRx = rx
        lastNetTx = tx
        lastNetAt = now
        o.put("rx_gb", round1(rx / 1073741824.0))
        o.put("tx_gb", round1(tx / 1073741824.0))
        return o
    }

    private fun loadBlock(): JSONObject {
        val o = JSONObject()
        val la = readText("/proc/loadavg", true)
        if (la != null) {
            val parts = la.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                o.put("loadavg", parts.take(3).joinToString(" / "))
                parts.getOrNull(0)?.toDoubleOrNull()?.let { o.put("load1", round1(it)) }
            }
        }
        return o
    }

    private fun diskBlock(): JSONObject {
        val o = JSONObject()
        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val total = sf.blockCountLong * sf.blockSizeLong
            val free = sf.availableBlocksLong * sf.blockSizeLong
            o.put("total_gb", round1(total / 1073741824.0))
            o.put("free_gb", round1(free / 1073741824.0))
            o.put("free_pct", if (total > 0) round1(free * 100.0 / total) else 0.0)
        } catch (_: Throwable) {
        }
        // 多挂载点: /data、/storage、/system 各一, 按文件系统合并同源挂载
        try {
            val mounts = JSONArray()
            // 同一底层文件系统会被挂多次(sdcardfs: /storage/emulated 与 /data 同源)。
            // 这里按文件系统去重但不丢信息: 后出现的挂载点合并进同一行 label,
            // 既不会出现两块数字相同的容量条, 也不会让用户以为该分区不存在。
            val byFs = LinkedHashMap<String, JSONObject>()
            fun addMount(label: String, dir: File?) {
                if (dir == null) return
                try {
                    val sf = StatFs(dir.path)
                    val total = sf.blockCountLong * sf.blockSizeLong
                    val free = sf.availableBlocksLong * sf.blockSizeLong
                    if (total <= 0) return
                    val key = "" + (total / 1048576) + ":" + (free / 1048576)
                    val exist = byFs[key]
                    if (exist != null) {
                        val prev = exist.optString("label")
                        if (!prev.contains(label)) exist.put("label", prev + " · " + label)
                        return
                    }
                    val one = JSONObject()
                        .put("label", label)
                        .put("free_gb", round1(free / 1073741824.0))
                        .put("total_gb", round1(total / 1073741824.0))
                        .put("free_pct", round1(free * 100.0 / total))
                    byFs[key] = one
                    mounts.put(one)
                } catch (_: Throwable) {
                }
            }
            addMount("/data", Environment.getDataDirectory())
            addMount("/storage/emulated", Environment.getExternalStorageDirectory())
            addMount("/system", Environment.getRootDirectory())
            if (mounts.length() > 0) o.put("mounts", mounts)
        } catch (_: Throwable) {
        }
        return o
    }

    private fun uptimeText(): String {
        val ms = SystemClock.elapsedRealtime()
        val totalMin = ms / 60000
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        return if (d > 0) "${d}天${h}小时" else if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    private fun socName(): String {
        val hw = try {
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        } catch (_: Throwable) {
            ""
        }
        if (!hw.isNullOrBlank() && hw != "unknown") return hw
        return Build.HARDWARE ?: ""
    }

    private fun pidOf(c: Context, target: String, isPkg: Boolean): Int {
        try {
            if (isPkg) {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.firstOrNull { it.processName == target }?.let { return it.pid }
            } else {
                val files = File("/proc").listFiles()
                if (files != null) {
                    for (f in files) {
                        val pid = f.name.toIntOrNull() ?: continue
                        val cmd = readText("/proc/$pid/cmdline", privileged = true)?.replace("\u0000", "") ?: continue
                        if (cmd == target) return pid
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val sh = if (Diagnostics.currentLevel() >= 1) {
            val q = if (isPkg) "pidof " + shellSafe(target) else "pgrep -f " + shellSafe(target)
            Diagnostics.execRead("$q 2>/dev/null | tr ' ' '\\n' | head -1")
        } else null
        return sh?.trim()?.lineSequence()?.firstOrNull()?.toIntOrNull() ?: -1
    }

    private fun parseStatus(text: String): JSONObject {
        val o = JSONObject()
        for (line in text.lineSequence()) {
            val kv = line.split(':')
            if (kv.size < 2) continue
            val k = kv[0].trim()
            val v = kv[1].trim()
            when (k) {
                "Name" -> o.put("name", v)
                "State" -> o.put("state", v)
                "Threads" -> o.put("threads", v.toIntOrNull() ?: 0)
                "VmRSS" -> o.put("rss_mb", round1((v.split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: 0.0) / 1024.0))
                "VmSize" -> o.put("vm_mb", round1((v.split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: 0.0) / 1024.0))
            }
        }
        return o
    }

    /** 读文件: 直读失败且给了 privileged 时走 Shizuku/root 只读通道; 都拿不到返回 null */
    private fun readText(path: String, privileged: Boolean = false): String? {
        try {
            val f = File(path)
            if (f.canRead()) {
                val t = f.readText().trim()
                if (t.isNotEmpty()) return t
            }
        } catch (_: Throwable) {
        }
        if (!privileged || Diagnostics.currentLevel() < 1) return null
        return Diagnostics.execRead("cat " + shellSafe(path))
    }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    private fun optTemp(v: Double): String = if (v > 0) "${round1(v)}℃" else "读不到"

}
