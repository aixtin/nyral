package io.github.aixtin.nyral

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机只读体检(诊断采集层)。
 *
 * 硬边界:
 *  1. 只读: 只用系统查询接口 + 固定只读命令常量, 不接受外部输入拼接命令, 不写任何文件, 不改任何设置。
 *  2. 隔离: 不依赖 ScriptEngine, 不进入 sh_run 门禁链路; 执行通道仅本文件内的私有 Sh 实现。
 *  3. 特权分级: L0 公开 API / L1 Shizuku(adb 级) / L2 root。等级不足时明确降级, 不静默失败。
 *  4. 脱敏: 不输出 SSID / 内网 IP / 序列号 / IMEI / 账号线索; DNS 只报数量不报地址。
 */
object Diagnostics {

    /** 单个体检项定义。level 为该项所需的最低特权等级。 */
    data class Item(val id: String, val title: String, val desc: String, val level: Int)

    /** 单项采集结果。ok=false 时 error 说明原因(权限不足 / 系统不支持)。 */
    data class Result(
        val id: String,
        val title: String,
        val level: Int,
        val ok: Boolean,
        val verdict: String,
        val lines: List<String> = emptyList(),
        val hint: String = "",
        val error: String = ""
    )

    val ITEMS = listOf(
        Item("battery", "电池与充电", "电量 · 温度 · 电压 · 充电状态 · 健康度", 0),
        Item("storage", "存储占用", "数据分区容量 · 可用空间 · 本应用占用", 0),
        Item("crash", "崩溃与异常退出", "本应用近期崩溃 / ANR 记录", 0),
        Item("net", "网络状态", "链路类型 · 计费属性 · DNS 配置项数", 0),
        Item("appcrash", "其他应用崩溃", "全机应用近期崩溃 / ANR / 被杀记录", 1),
        Item("partitions", "分区与挂载", "各挂载点容量 · 剩余 · 文件系统", 1),
        Item("thermal", "温度与降频", "各 thermal zone 实时读数", 1),
        Item("rootenv", "Root 环境", "su 可用性 · 模块数量 · SELinux 模式", 2)
    )

    fun item(id: String): Item? = ITEMS.firstOrNull { it.id == id }

    // ---------------- 特权等级 ----------------

    /** 当前可用等级: 0=L0 公开 API, 1=L1 Shizuku, 2=L2 root。 */
    fun currentLevel(): Int = when {
        Sh.hasRoot() -> 2
        Sh.hasShizuku() -> 1
        else -> 0
    }

    fun levelName(lv: Int): String = when (lv) {
        2 -> "L2 深度(root)"
        1 -> "L1 系统级(Shizuku)"
        else -> "L0 基础(公开 API)"
    }

    /** 重新探测特权环境(用户刚装完 Shizuku / 授完权时调用)。 */
    fun resetPrivilegeCache() {
        Sh.rootOk = -1
        Sh.shizukuOk = -1
    }

    /** Shizuku 状态: 0=未安装/未运行, 1=已运行未授权本应用, 2=已授权。 */
    fun shizukuState(): Int = try {
        when {
            !Shizuku.pingBinder() -> 0
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 2
            else -> 1
        }
    } catch (t: Throwable) {
        0
    }

    /** Shizuku 已安装但未授权时, 触发一次授权请求(仅 UI 主动调用)。 */
    fun requestShizukuPermission(): Boolean = try {
        if (!Shizuku.pingBinder()) false
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) true
        else {
            Shizuku.requestPermission(1)
            false
        }
    } catch (t: Throwable) {
        false
    }

    // ---------------- 统一入口 ----------------

    /** 采集单项。level 不足或异常一律返回 ok=false, 由调用方展示原因。 */
    fun run(c: Context, id: String): Result {
        val it = item(id) ?: return Result(id, id, 0, false, "未知体检项", error = "未知体检项: $id")
        if (currentLevel() < it.level) {
            val lv = currentLevel()
            val why = if (it.level >= 2) Sh.lastError?.let { e -> "(" + e + ")" } ?: "" else ""
            return Result(
                id = it.id, title = it.title, level = it.level, ok = false,
                verdict = "未采集",
                error = "需要 " + levelName(it.level) + " 通道, 当前仅有 " + levelName(lv) + why,
                hint = when (it.level) {
                    1 -> "安装 Shizuku 并在本页授权后即可解锁(无需 root)。"
                    else -> "su 存在但本应用未被授权时, 需在超级用户(KernelSU/Magisk)中把本应用加入授权列表。"
                }
            )
        }
        return try {
            when (id) {
                "battery" -> battery(c)
                "storage" -> storage(c)
                "crash" -> crash(c)
                "net" -> net(c)
                "appcrash" -> appCrash(c)
                "partitions" -> partitions(c)
                "thermal" -> thermal(c)
                else -> rootEnv(c)
            }
        } catch (t: Throwable) {
            Result(it.id, it.title, it.level, false, "采集失败", error = (t.message ?: t.javaClass.simpleName))
        }
    }

    fun runAll(c: Context): List<Result> = ITEMS.map { run(c, it.id) }

    // ---------------- L0: 电池 ----------------

    private fun battery(c: Context): Result {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Result("battery", "电池与充电", 0, false, "读取失败", error = "系统未返回电池广播")
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0 && lvl >= 0) lvl * 100 / scale else -1
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
            else -> "未连接"
        }
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "失效"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "异常"
            else -> "未知"
        }
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // 瞬时电流(微安), 部分机型需系统级权限, 取不到时返回 Integer.MIN_VALUE
        val curUa = try { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE } catch (t: Throwable) { Int.MIN_VALUE }

        val lines = mutableListOf<String>()
        if (pct >= 0) lines += "电量: $pct%"
        lines += "状态: $status" + (if (plug != "未连接") " ($plug)" else "")
        if (temp > 0f) lines += "温度: $temp ℃"
        if (volt > 0) lines += "电压: $volt mV"
        lines += "健康: $health"
        if (curUa != Int.MIN_VALUE && curUa != 0) {
            lines += "瞬时电流: " + (curUa / 1000) + " mA"
        } else {
            lines += "瞬时电流: 本机不对外提供"
        }

        val issues = mutableListOf<String>()
        if (temp >= 45f) issues += "电池温度 $temp ℃ 已进入高温区, 可能触发充电限流或降频"
        else if (temp >= 40f) issues += "电池温度 $temp ℃ 偏热"
        if (pct in 0..15 && status == "放电中") issues += "电量仅 $pct%, 建议充电"
        if (health != "良好" && health != "未知") issues += "健康状态异常: $health"
        if (status == "放电中" && curUa != Int.MIN_VALUE && curUa > 0) issues += "放电中电流为正, 读数可能不准"

        return Result(
            id = "battery", title = "电池与充电", level = 0, ok = true,
            verdict = if (issues.isEmpty()) "正常" else issues.joinToString("；"),
            lines = lines,
            hint = if (issues.isEmpty()) "" else "持续高温或健康异常建议做一次满充满放校准; 若为硬件衰减需送修检测。"
        )
    }

    // ---------------- L0: 存储 ----------------

    private fun storage(c: Context): Result {
        val lines = mutableListOf<String>()
        val dataDir = Environment.getDataDirectory()
        val st = StatFs(dataDir.absolutePath)
        val total = st.blockCountLong * st.blockSizeLong
        val avail = st.availableBlocksLong * st.blockSizeLong
        val used = total - avail
        val usedPct = if (total > 0) (used * 100 / total).toInt() else 0
        lines += "数据分区: 共 " + gb(total) + " GB / 已用 " + gb(used) + " GB / 可用 " + gb(avail) + " GB (" + usedPct + "%)"

        val ext = try {
            val s2 = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val t2 = s2.blockCountLong * s2.blockSizeLong
            val a2 = s2.availableBlocksLong * s2.blockSizeLong
            Triple(t2, t2 - a2, a2)
        } catch (t: Throwable) { null }
        // 内部存储与数据分区常见为同一分区, 重复展示只增噪音
        if (ext != null && ext.first > 0 && ext.first != total) {
            lines += "内部存储: 共 " + gb(ext.first) + " GB / 可用 " + gb(ext.third) + " GB"
        }

        try {
            val ssm = c.getSystemService(StorageStatsManager::class.java)
            val stats = ssm?.queryStatsForUid(StorageManager.UUID_DEFAULT, android.os.Process.myUid())
            if (stats != null) {
                lines += "本应用: 安装 " + mb(stats.appBytes) + " / 数据 " + mb(stats.dataBytes) + " / 缓存 " + mb(stats.cacheBytes)
            }
        } catch (t: Throwable) {
            lines += "本应用占用: 读取受限(" + (t.javaClass.simpleName) + ")"
        }

        val issues = mutableListOf<String>()
        if (total > 0 && avail * 100 / total < 10) issues += "可用空间不足 10%, 系统可能开始拒绝写入"
        else if (total > 0 && avail * 100 / total < 20) issues += "可用空间低于 20%, 建议清理"

        return Result(
            id = "storage", title = "存储占用", level = 0, ok = true,
            verdict = if (issues.isEmpty()) "空间充足" else issues.joinToString("；"),
            lines = lines
        )
    }

    private fun gb(b: Long): String = String.format(Locale.US, "%.1f", b / 1073741824.0)
    private fun mb(b: Long): String = String.format(Locale.US, "%.1f MB", b / 1048576.0)

    // ---------------- L0: 崩溃与异常退出 ----------------

    private fun crash(c: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result("crash", "崩溃与异常退出", 0, false, "未采集",
                error = "系统版本低于 Android 11, 无对应查询接口")
        }
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Result("crash", "崩溃与异常退出", 0, false, "读取失败", error = "无法获取 ActivityManager")
        val list = try {
            am.getHistoricalProcessExitReasons(c.packageName, 0, 10)
        } catch (t: Throwable) {
            return Result("crash", "崩溃与异常退出", 0, false, "读取失败", error = t.message ?: "查询异常")
        }
        if (list.isEmpty()) {
            return Result("crash", "崩溃与异常退出", 0, true, "近期无异常退出记录",
                hint = "该接口仅能查询本应用自身的退出记录；其他应用崩溃需 L1/L2 通道。")
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val lines = mutableListOf<String>()
        var bad = 0
        var recent24h = 0
        val now = System.currentTimeMillis()
        for (r in list.take(8)) {
            val name = reasonName(r.reason)
            val ts = fmt.format(Date(r.timestamp))
            lines += ts + " " + name + " (importance " + r.importance + ")"
            if (name == "崩溃" || name == "ANR" || name == "Native 崩溃") {
                bad++
                if (now - r.timestamp < 24L * 3600 * 1000) recent24h++
            }
        }
        val verdict = when {
            recent24h > 0 -> "近 24 小时异常退出 " + recent24h + " 次, 需排查"
            bad > 0 -> "存在历史崩溃记录, 但近 24 小时未复发"
            else -> "仅有正常退出记录"
        }
        return Result(
            id = "crash", title = "崩溃与异常退出", level = 0, ok = true,
            verdict = verdict, lines = lines,
            hint = if (recent24h > 0) "崩溃日志可导出分析; 若需看 native tombstone 需 root。" else ""
        )
    }

    private fun reasonName(r: Int): String = when (r) {
        0 -> "未知原因"
        1 -> "正常退出(自身)"
        ApplicationExitInfo.REASON_CRASH -> "崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号终止"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_OTHER -> "其他"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更导致重启"
        14 -> "被系统冻结"
        15 -> "包状态变更"
        16 -> "应用被更新(进程重启)"
        17 -> "应用被卸载/重装"
        else -> "原因码 $r"
    }

    // ---------------- L0: 网络 ----------------

    private fun net(c: Context): Result {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Result("net", "网络状态", 0, false, "读取失败", error = "无法获取 ConnectivityManager")
        val n = cm.activeNetwork
        if (n == null) {
            return Result("net", "网络状态", 0, false, "当前无网络连接", error = "无活动网络", hint = "检查 Wi-Fi / 移动数据 / VPN 是否关闭。")
        }
        val cap = cm.getNetworkCapabilities(n)
        val lines = mutableListOf<String>()
        val types = mutableListOf<String>()
        if (cap != null) {
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types += "Wi-Fi"
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types += "移动数据"
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types += "以太网"
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types += "VPN"
            if (types.isEmpty()) types += "其他"
            lines += "链路: " + types.joinToString(" + ")
            lines += "已联网: " + (if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "是" else "否")
            lines += "已验证通外网: " + (if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "是" else "否")
            lines += "下行带宽: " + (cap.linkDownstreamBandwidthKbps / 1000) + " Mbps / 上行 " + (cap.linkUpstreamBandwidthKbps / 1000) + " Mbps"
        } else {
            lines += "链路: 未知(能力信息不可用)"
        }
        val metered = cm.isActiveNetworkMetered
        lines += "计费网络: " + (if (metered) "是" else "否")
        val lp = try { cm.getLinkProperties(n) } catch (t: Throwable) { null }
        val dnsCount = lp?.dnsServers?.size ?: 0
        lines += "DNS 服务器: " + dnsCount + " 个"
        if (lp?.privateDnsServerName != null) lines += "私有 DNS: 已启用"

        val issues = mutableListOf<String>()
        if (cap != null && !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) issues += "网络未通过连通性验证, 可能无法上网"
        if (types.contains("VPN")) issues += "当前走 VPN 链路"

        return Result(
            id = "net", title = "网络状态", level = 0, ok = true,
            verdict = if (issues.isEmpty()) "连接正常" else issues.joinToString("；"),
            lines = lines,
            hint = "为避免泄露隐私, 本项不输出 SSID、内网 IP 与 DNS 地址。"
        )
    }

    // ---------------- L1: 分区与挂载 ----------------

    /** 挂载点容量只读快照(df -h), 用于观察 /data、/storage/emulated 等分区余量。 */
    private fun partitions(c: Context): Result {
        val raw = Sh.exec("df -h 2>&1", 15000L)
            ?: return Result("partitions", "分区与挂载", 1, false, "未采集",
                error = Sh.lastError ?: "系统级通道调用失败", hint = "确认 Shizuku 已运行并已授权本应用。")
        val want = listOf("/data", "/storage/emulated", "/cache", "/metadata", "/")
        val lines = mutableListOf<String>()
        for (ln in raw.lines()) {
            val t = ln.trim()
            if (t.isEmpty() || !t.startsWith("/")) continue
            val col = t.split(Regex("\\s+"))
            if (col.size < 6) continue
            // Filesystem Size Used Avail Use% Mounted
            val mounted = col[5]
            val hit = want.any { w ->
                if (w == "/") mounted == "/" else mounted == w || mounted.startsWith("$w/")
            }
            if (!hit) continue
            lines += mounted + " | 总 " + col[1] + " | 已用 " + col[2] + " | 可用 " + col[3] + " | " + col[4]
        }
        if (lines.isEmpty()) {
            return Result("partitions", "分区与挂载", 1, false, "未采集",
                error = "df 输出中没有可识别的挂载点",
                hint = "部分机型 df 输出格式不同。")
        }
        return Result(
            id = "partitions", title = "分区与挂载", level = 1,
            ok = true, verdict = lines.size.toString() + " 个主要分区可读",
            lines = lines.take(8),
            hint = "/data 使用率超过 90% 时系统可能变慢或无法安装应用。"
        )
    }

    // ---------------- L1: 其他应用崩溃 ----------------

    /**
     * 全机应用异常退出记录。数据源为 ActivityManager 的 exit-info(需 shell 级),
     * 只读聚合, 不输出包名以外的用户数据。
     */
    private fun appCrash(c: Context): Result {
        val raw = Sh.exec(
            "dumpsys activity exit-info 2>&1 | grep -E '^  package:|timestamp=|reason='",
            20000L
        ) ?: return Result("appcrash", "其他应用崩溃", 1, false, "未采集",
                error = Sh.lastError ?: "系统级通道调用失败", hint = "确认 Shizuku 已运行并已授权本应用。")

        var pkg = ""
        var ts = ""
        var scanned = 0
        val abnormal = mutableListOf<Pair<String, String>>()
        for (ln in raw.lines()) {
            val t = ln.trim()
            if (t.startsWith("package:")) {
                pkg = t.substringAfter("package:").trim()
                continue
            }
            if (t.startsWith("timestamp=")) {
                ts = t.removePrefix("timestamp=").trim().take(16)
                continue
            }
            val ri = t.indexOf("reason=")
            if (ri < 0) continue
            scanned++
            val reason = t.substring(ri + 7).trim().substringBefore(" ")
                .toIntOrNull() ?: -1
            val proc = t.substringAfter("process=", "").substringBefore(" ").trim()
            val bad = reason == 4 || reason == 5 || reason == 6 || reason == 9
            if (!bad) continue
            val name = when (reason) {
                4 -> "崩溃"
                5 -> "Native 崩溃"
                6 -> "ANR 无响应"
                9 -> "资源占用过高被杀"
                else -> "异常($reason)"
            }
            abnormal += ts to (pkg + (if (proc.isNotBlank() && proc != pkg) " [$proc]" else "") + "  " + name)
        }

        if (scanned == 0) {
            val peek = raw.lines().map { it.trim() }.filter { it.isNotBlank() }.take(2).joinToString(" / ")
            return Result("appcrash", "其他应用崩溃", 1, false, "未采集",
                error = "exit-info 无可解析记录: " + (peek.ifBlank { "空输出" }).take(160),
                hint = "部分机型默认关闭进程退出信息持久化。")
        }

        val lines = mutableListOf<String>()
        lines += "扫描退出记录: " + scanned + " 条"
        lines += "异常退出: " + abnormal.size + " 条"
        val sorted = abnormal.sortedByDescending { it.first }
        if (sorted.isEmpty()) {
            lines += "最近没有应用崩溃或 ANR"
        } else {
            sorted.take(6).forEach { lines += it.first + "  " + it.second }
            if (sorted.size > 6) lines += "其余 " + (sorted.size - 6) + " 条略"
        }
        return Result(
            id = "appcrash", title = "其他应用崩溃", level = 1,
            ok = true,
            verdict = if (abnormal.isEmpty()) "其他应用无异常退出" else abnormal.size.toString() + " 次应用异常退出",

            lines = lines,
            hint = if (abnormal.isEmpty()) "这是系统保留的近期退出记录; 更早的记录可能已被清理。"
            else "频繁崩溃多为应用自身问题; 若集中在同一应用, 可尝试清数据或更新版本。"
        )
    }

    // ---------------- L1: 温度与降频 ----------------

    private fun thermal(c: Context): Result {
        // MTK 等平台 /sys/class/thermal 下无 thermal_zone*, 改读框架聚合的 thermalservice
        val raw = Sh.exec(
            "dumpsys thermalservice 2>&1 | grep -E 'Thermal Status:|Temperature\\{|HAL Ready'",
            15000L
        ) ?: return Result("thermal", "温度与降频", 1, false, "未采集",
                error = Sh.lastError ?: "系统级通道调用失败", hint = "确认 Shizuku 已运行并已授权本应用。")

        val tempRe = Regex("mValue=([0-9.]+),\\s*mType=[0-9]+,\\s*mName=([A-Za-z_ ]+)")
        var status = -1
        var halReady = false
        var hottest = -1.0
        var hottestName = ""
        val named = LinkedHashMap<String, Double>()
        for (ln in raw.lines()) {
            val t = ln.trim()
            if (t.startsWith("Thermal Status:")) {
                status = t.substringAfter(":").trim().toIntOrNull() ?: -1
                continue
            }
            if (t.startsWith("HAL Ready:")) {
                halReady = t.substringAfter(":").trim().equals("true", true)
                continue
            }
            val m = tempRe.find(t) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            val nm = m.groupValues[2].trim().uppercase()
            if (v <= 0 || v > 150) continue
            named[nm] = v
            if (v > hottest) { hottest = v; hottestName = nm }
        }

        if (named.isEmpty() && status < 0) {
            return Result("thermal", "温度与降频", 1, false, "未采集",
                error = "thermalservice 无温度数据", hint = "设备未上报温度节点, 该机型可能不通过标准 thermal HAL 暴露。")
        }

        val statusText = when (status) {
            0 -> "NONE(正常)"
            1 -> "LIGHT(轻度限频)"
            2 -> "MODERATE(中度限频)"
            3 -> "SEVERE(重度限频)"
            4 -> "CRITICAL(危急)"
            5 -> "EMERGENCY(紧急)"
            6 -> "SHUTDOWN(关机保护)"
            else -> "未知"
        }
        val lines = mutableListOf<String>()
        lines += "系统热状态: " + statusText
        val order = listOf("CPU", "GPU", "NPU", "SKIN", "BATTERY", "POWER_AMPLIFIER")
        for (k in order) named[k]?.let { lines += k + ": " + String.format(Locale.US, "%.1f", it) + " ℃" }
        for ((k, v) in named) if (!order.contains(k)) lines += k + ": " + String.format(Locale.US, "%.1f", v) + " ℃"
        lines += "温度探针数: " + named.size + (if (halReady) " | thermal HAL 已连接" else "")

        val issues = mutableListOf<String>()
        if (status >= 2) issues += "系统已进入限频状态(" + statusText + "), 表现为帧率下降/充电变慢"
        if (hottest >= 75) issues += "最高 " + hottestName + " " + String.format(Locale.US, "%.1f", hottest) + " ℃, 接近降频阈值"
        else if (hottest >= 65) issues += "最高 " + hottestName + " " + String.format(Locale.US, "%.1f", hottest) + " ℃, 偏热"
        if (!halReady) issues += "thermal HAL 未连接, 温度可能不是实时值"

        return Result(
            id = "thermal", title = "温度与降频", level = 1,
            ok = true,
            verdict = if (issues.isEmpty()) "温度正常" else issues.joinToString("；"),
            lines = lines,
            hint = if (issues.isEmpty()) "热状态 NONE 表示未触发任何限频。" else "长时间高负载会触发系统限频; 可检查后台常驻应用与充电时是否使用。"
        )
    }

    // ---------------- L2: Root 环境 ----------------

    private fun rootEnv(c: Context): Result {
        val raw = Sh.exec("id -u; echo ---; getenforce; echo ---; ls /data/adb/modules 2>/dev/null")
            ?: return Result("rootenv", "Root 环境", 2, false, "未采集", error = Sh.lastError ?: "su 调用失败")
        val parts = raw.split("---")
        val uid = parts.getOrNull(0)?.trim()?.lines()?.firstOrNull() ?: ""
        val se = parts.getOrNull(1)?.trim()?.lines()?.firstOrNull() ?: "未知"
        val mods = parts.getOrNull(2)?.trim()?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        val lines = mutableListOf<String>()
        lines += "shell uid: " + (if (uid.isBlank()) "未知" else uid)
        lines += "SELinux: " + se
        lines += "模块数: " + mods.size
        if (mods.isNotEmpty()) {
            val show = mods.take(8)
            lines += "模块: " + show.joinToString(", ") + (if (mods.size > show.size) " 等" else "")
        }
        val issues = mutableListOf<String>()
        if (se.equals("Permissive", true)) issues += "SELinux 为宽容模式, 安全性下降"
        return Result(
            id = "rootenv", title = "Root 环境", level = 2, ok = true,
            verdict = if (issues.isEmpty()) "环境正常" else issues.joinToString("；"),
            lines = lines,
            hint = "仅展示只读信息, 不修改任何模块或 SELinux 状态。"
        )
    }

    // ---------------- 私有执行通道(与 sh_run 完全隔离) ----------------

    /** 只读通道入口(2026-10-30): 供设备面板数据层复用同一套 Shizuku/su 隔离通道读受限路径 */
    internal fun execRead(cmd: String, timeoutMs: Long = 4000L): String? = Sh.exec(cmd, timeoutMs)

    /** 强制走 su(root uid) 的只读入口: OEM 对 shell uid 屏蔽了 thermal_zone, 只有 root 读得到真实节点 */
    internal fun execRoot(cmd: String, timeoutMs: Long = 4000L): String? =
        Sh.exec(cmd, timeoutMs, forceRoot = true)

    /**
     * 只读命令执行器。仅接受本文件内写死的常量命令, 不接受任何外部输入。
     * 通道优先级: Shizuku(adb 级) > su > 不可用。
     */
    internal object Sh {
        @Volatile var rootOk: Int = -1
        @Volatile var shizukuOk: Int = -1
        /** 最近一次通道失败原因, 供上层如实展示(不吞异常) */
        @Volatile var lastError: String? = null
        /** 探测成功的 su 路径(?进程 PATH 不含 /system/bin 时相对名会 ENOENT) */
        @Volatile var suPath: String? = null

        /** su 候选路径: 绝对路径优先, 相对名兜底。 */
        private val SU_CANDIDATES = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "su"
        )

        fun hasRoot(): Boolean {
            if (rootOk >= 0) return rootOk == 1
            val detail = StringBuilder()
            var hit: String? = null
            for (cand in SU_CANDIDATES) {
                val out = probeSu(cand)
                if (out.contains("uid=0")) {
                    hit = cand
                    break
                }
                detail.append(cand).append(" -> ").append(out.replace("\n", " ").trim()).append("; ")
            }
            suPath = hit
            rootOk = if (hit != null) 1 else 0
            if (hit == null) lastError = "su 探测失败: " + detail.toString().trim()
            return hit != null
        }

        /** 单次 `su -c id` 探测, 返回输出(含失败原因文本), 不抛异常。 */
        private fun probeSu(cand: String): String {
            val p = try {
                ProcessBuilder(cand, "-c", "id").redirectErrorStream(true).start()
            } catch (t: Throwable) {
                return "启动异常 " + (t.message ?: t.javaClass.simpleName)
            }
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(sb) { sb.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            try {
                reader.join(3000)
            } catch (_: Throwable) {
            }
            if (reader.isAlive) {
                try { p.destroy() } catch (_: Throwable) {
                }
                try { reader.join(300) } catch (_: Throwable) {
                }
                return "超时(3000ms)"
            }
            val text = sb.toString().trim()
            if (text.isNotEmpty()) return text
            val rc = try { p.exitValue() } catch (_: Throwable) { -1 }
            return "无输出(exit=" + rc + ")"
        }

        fun hasShizuku(): Boolean {
            if (shizukuOk >= 0) return shizukuOk == 1
            val probe = {
                try {
                    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (t: Throwable) {
                    false
                }
            }
            // Shizuku 的 binder 只在主线程 attach, 后台线程直接查询会误判为未授权
            val ok = if (Looper.myLooper() == Looper.getMainLooper()) {
                probe()
            } else {
                val latch = CountDownLatch(1)
                var r = false
                val posted = Handler(Looper.getMainLooper()).post {
                    try { r = probe() } finally { latch.countDown() }
                }
                if (posted && latch.await(3, TimeUnit.SECONDS)) r else false
            }
            shizukuOk = if (ok) 1 else 0
            return ok
        }

        /**
         * Shizuku 的 newProcess 在 13.x 中为 private, 通过反射调用(同库自有方法, 非系统 hidden API)。
         * 拿不到则降级为不可用, 绝不静默改成 su 或 shell。
         */
        private val newProcessMethod: java.lang.reflect.Method? by lazy {
            try {
                val cls = Class.forName("rikka.shizuku.Shizuku")
                val strArr = java.lang.reflect.Array.newInstance(String::class.java, 0).javaClass
                cls.getDeclaredMethod("newProcess", strArr, strArr, String::class.java)
                    .also { it.isAccessible = true }
            } catch (t: Throwable) {
                // 反射失败时把候选方法签名记下来, 便于定位 API 版本差异
                lastError = "newProcess 反射失败: " + (t.message ?: t.javaClass.simpleName) + " / 候选: " + probeMethods()
                null
            }
        }

        /** 列出 Shizuku 类里所有 *rocess* 方法签名(仅用于失败时自证原因) */
        private fun probeMethods(): String = try {
            Class.forName("rikka.shizuku.Shizuku").declaredMethods
                .filter { it.name.contains("rocess", true) }
                .joinToString("; ") { m ->
                    m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
                }
        } catch (t: Throwable) {
            "探测失败: " + (t.message ?: t.javaClass.simpleName)
        }

        private fun newProcess(cmd: Array<String>): Process? {
            val m = newProcessMethod ?: return null
            return try {
                val r = m.invoke(null, cmd, null, null) as? Process
                if (r == null) lastError = "newProcess 返回空(Shizuku 服务未运行?)"
                r
            } catch (t: Throwable) {
                val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                lastError = "newProcess 调用失败: " + (real.message ?: real.javaClass.name)
                null
            }
        }

        fun exec(cmd: String, timeoutMs: Long = 5000L, forceRoot: Boolean = false): String? {
            val p: Process = try {
                when {
                    // stderr 合并进 stdout: 失败时也能拿到真实报错
                    !forceRoot && hasShizuku() -> newProcess(arrayOf("sh", "-c", cmd + " 2>&1")) ?: return null
                    hasRoot() -> ProcessBuilder(suPath ?: "su", "-c", cmd).redirectErrorStream(true).start()
                    else -> {
                        lastError = "无可用特权通道(Shizuku 未授权且无 su)"
                        return null
                    }
                }
            } catch (t: Throwable) {
                lastError = "启动通道失败: " + (t.message ?: t.javaClass.simpleName)
                return null
            }
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(sb) { sb.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            // 以 EOF(读线程结束)作为完成判据: ShizukuRemoteProcess.waitFor 在跨版本时可能挂起
            try { reader.join(timeoutMs) } catch (_: Throwable) {
            }
            if (reader.isAlive) {
                lastError = "通道执行超时(" + timeoutMs + "ms)"
                try { p.destroy() } catch (_: Throwable) {
                }
                try { reader.join(300) } catch (_: Throwable) {
                }
                return null
            }
            return sb.toString()
        }
    }
}
