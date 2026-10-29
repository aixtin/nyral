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
import android.os.StatFs
import android.os.storage.StorageManager
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            return Result(
                id = it.id, title = it.title, level = it.level, ok = false,
                verdict = "未采集",
                error = "需要 " + levelName(it.level) + " 通道, 当前仅有 " + levelName(currentLevel()),
                hint = when (it.level) {
                    1 -> "安装 Shizuku 并在本页授权后即可解锁(无需 root)。"
                    else -> "该档需 root 环境, 当前设备无可用 su。"
                }
            )
        }
        return try {
            when (id) {
                "battery" -> battery(c)
                "storage" -> storage(c)
                "crash" -> crash(c)
                "net" -> net(c)
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

    // ---------------- L1: 温度与降频 ----------------

    private fun thermal(c: Context): Result {
        val raw = Sh.exec("cat /sys/class/thermal/thermal_zone*/type; echo ---; cat /sys/class/thermal/thermal_zone*/temp")
            ?: return Result("thermal", "温度与降频", 1, false, "未采集",
                error = "系统级通道调用失败", hint = "确认 Shizuku 已运行并已授权本应用。")
        val parts = raw.split("---")
        if (parts.size < 2) return Result("thermal", "温度与降频", 1, false, "未采集", error = "thermal zone 输出格式异常")
        val types = parts[0].trim().lines().filter { it.isNotBlank() }
        val temps = parts[1].trim().lines().filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var hottest = -1.0
        var hottestName = ""
        var cpuCount = 0
        var cpuSum = 0.0
        for (idx in types.indices) {
            val t = temps.getOrNull(idx)?.trim()?.toDoubleOrNull() ?: continue
            val celsius = when {
                t > 10000 -> t / 1000.0
                t > 1000 -> t / 1000.0
                t > 200 -> t / 10.0
                else -> t
            }
            if (celsius <= 0 || celsius > 150) continue
            val name = types[idx].trim()
            if (name.contains("cpu", true) || name.contains("gpu", true) || name.contains("battery", true) ||
                name.contains("ap", true) || name.contains("skin", true) || name.contains("quiet", true)) {
                lines += name + ": " + String.format(Locale.US, "%.1f", celsius) + " ℃"
            }
            if (name.contains("cpu", true)) { cpuCount++; cpuSum += celsius }
            if (celsius > hottest) { hottest = celsius; hottestName = name }
        }
        if (lines.isEmpty()) {
            types.take(8).forEachIndexed { i, nm ->
                val t = temps.getOrNull(i)?.trim()?.toDoubleOrNull() ?: return@forEachIndexed
                val celsius = if (t > 1000) t / 1000.0 else if (t > 200) t / 10.0 else t
                if (celsius in 1.0..150.0) lines += nm.trim() + ": " + String.format(Locale.US, "%.1f", celsius) + " ℃"
            }
        }
        lines += "thermal zone 总数: " + types.size
        if (cpuCount > 0) {
            lines += "CPU 平均: " + String.format(Locale.US, "%.1f", cpuSum / cpuCount) + " ℃ (" + cpuCount + " 个探针)"
        }
        val issues = mutableListOf<String>()
        if (hottest >= 75) issues += "最高 " + String.format(Locale.US, "%.1f", hottest) + " ℃ (" + hottestName + "), 已接近/进入降频区间"
        else if (hottest >= 65) issues += "最高 " + String.format(Locale.US, "%.1f", hottest) + " ℃, 偏热"
        return Result(
            id = "thermal", title = "温度与降频", level = 1, ok = true,
            verdict = if (issues.isEmpty()) "温度正常" else issues.joinToString("；"),
            lines = lines,
            hint = if (issues.isEmpty()) "" else "高负载下温度过高会触发 CPU/充电降频; 可检查后台是否有个别应用常驻占用。"
        )
    }

    // ---------------- L2: Root 环境 ----------------

    private fun rootEnv(c: Context): Result {
        val raw = Sh.exec("id -u; echo ---; getenforce; echo ---; ls /data/adb/modules 2>/dev/null")
            ?: return Result("rootenv", "Root 环境", 2, false, "未采集", error = "su 调用失败")
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

    /**
     * 只读命令执行器。仅接受本文件内写死的常量命令, 不接受任何外部输入。
     * 通道优先级: Shizuku(adb 级) > su > 不可用。
     */
    private object Sh {
        @Volatile var rootOk: Int = -1
        @Volatile var shizukuOk: Int = -1

        fun hasRoot(): Boolean {
            if (rootOk >= 0) return rootOk == 1
            val ok = try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(3, TimeUnit.SECONDS)
                out.contains("uid=0")
            } catch (t: Throwable) {
                false
            }
            rootOk = if (ok) 1 else 0
            return ok
        }

        fun hasShizuku(): Boolean {
            if (shizukuOk >= 0) return shizukuOk == 1
            val ok = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (t: Throwable) {
                false
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
                null
            }
        }

        private fun newProcess(cmd: Array<String>): Process? {
            val m = newProcessMethod ?: return null
            return try {
                m.invoke(null, cmd, null, null) as? Process
            } catch (t: Throwable) {
                null
            }
        }

        fun exec(cmd: String, timeoutMs: Long = 5000L): String? {
            val p: Process = try {
                when {
                    hasShizuku() -> newProcess(arrayOf("sh", "-c", cmd)) ?: return null
                    hasRoot() -> ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                    else -> return null
                }
            } catch (t: Throwable) {
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
            val finished = try {
                p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                false
            }
            if (!finished) {
                try { p.destroy() } catch (_: Throwable) {
                }
            }
            try { reader.join(400) } catch (_: Throwable) {
            }
            return if (finished) sb.toString() else null
        }
    }
}
