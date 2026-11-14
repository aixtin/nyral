package io.github.aixtin.nyral

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * 单个权限项的统一定义。
 *
 * 历史教训(2026-11-14): 引导页(GuideActivity) 与权限管理页(PermissionsActivity) 曾各自维护
 * 一份 items(), 权限页从 6 项扩到 12 项时引导页没跟上, 用户首次启动看到的仍是旧 6 项。
 * 现在清单只保留这一份, 以后再增删权限只改 PermCatalog.build()。
 */
class PermSpec(
    val iconRes: Int,
    val name: String,
    val desc: String,
    /** 展示分组: 基础 / 定时任务保障 / 高级与扩展 */
    val group: Int,
    /** 当前系统是否需要该权限(false 表示本机无需, 展示 perm_17) */
    val required: () -> Boolean,
    /** 是否已授权 */
    val granted: () -> Boolean,
    /** 发起授权(运行时弹窗或跳系统设置页) */
    val request: (Activity) -> Unit,
    /** 是否为 Root 权限项(特殊三态渲染 + 后台探测, 不跳转任何授权软件) */
    val isRoot: Boolean = false,
    /** 厂商私有开关(自启动 / 后台运行 / 锁屏 / 弹窗): 无任何标准 API 能读到真实状态,
     *  故不计入"已授权"统计, 只作为可点击的跳转入口, 状态固定显示"手动确认" */
    val manual: Boolean = false
) {
    companion object {
        const val GROUP_BASE = 0
        const val GROUP_TIMER = 1
        const val GROUP_ADV = 2
    }
}

/**
 * 全量权限清单(12 项) —— 引导页与权限管理页共用。
 *
 * 分组原则:
 *   基础权限   —— 任何功能都要用(通知 / 麦克风 / 悬浮窗)
 *   定时任务保障 —— 真正决定定时任务能否按时跑起来(精确闹钟 / 电池优化 / 自启动 / 后台运行 / 锁屏显示 / 后台弹窗)
 *   高级与扩展 —— 按需开启(所有文件访问 / 安装未知应用 / Root)
 */
object PermCatalog {
    const val REQ_RUNTIME = 2001

    /**
     * @param rootGranted -1=检测中, 0=未授权, 1=已授权
     * @param rooted 设备是否具备 root 环境。必须由调用方在后台探测后传入 ——
     *   这里若直接调 RootCheck.deviceRooted() 会起 su 子进程, 卡住列表首帧,
     *   且前后两次探测结果不一致时会让汇总分母在 7/8 之间跳动。
     *   默认值只读进程级缓存, 尚未探测时按 false 处理。
     */
    fun build(act: Activity, rootGranted: Int, rooted: Boolean = RootCheck.cachedRooted() ?: false): List<PermSpec> = listOf(
        // ===================== 基础权限 =====================
        PermSpec(
            R.drawable.ic_perm_notification, act.getString(R.string.perm_05), act.getString(R.string.perm_06),
            PermSpec.GROUP_BASE,
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
            { act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            { a -> a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_RUNTIME) }
        ),
        PermSpec(
            R.drawable.ic_perm_mic, act.getString(R.string.perm_07), act.getString(R.string.perm_08),
            PermSpec.GROUP_BASE,
            { true },
            { act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            { a -> a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RUNTIME) }
        ),
        PermSpec(
            R.drawable.ic_perm_overlay, act.getString(R.string.perm_09), act.getString(R.string.perm_10),
            PermSpec.GROUP_BASE,
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M },
            { Settings.canDrawOverlays(act) },
            { a -> PermActions.openOverlay(a) }
        ),
        // ===================== 定时任务运行保障 =====================
        PermSpec(
            R.drawable.ic_perm_alarm, act.getString(R.string.perm_30), act.getString(R.string.perm_31),
            PermSpec.GROUP_TIMER,
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
            { PermActions.exactAlarmOk(act) },
            { a -> PermActions.openExactAlarm(a) }
        ),
        PermSpec(
            R.drawable.ic_perm_battery, act.getString(R.string.perm_28), act.getString(R.string.perm_29),
            PermSpec.GROUP_TIMER,
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M },
            { PermActions.batteryOk(act) },
            { a -> PermActions.openBattery(a) }
        ),
        PermSpec(
            R.drawable.ic_perm_package, act.getString(R.string.perm_32), act.getString(R.string.perm_33),
            PermSpec.GROUP_TIMER,
            { true },
            { false },
            { a -> PermActions.openAutoStart(a) },
            manual = true
        ),
        PermSpec(
            R.drawable.ic_perm_background, act.getString(R.string.perm_34), act.getString(R.string.perm_35),
            PermSpec.GROUP_TIMER,
            { true },
            { false },
            { a -> PermActions.openBackground(a) },
            manual = true
        ),
        PermSpec(
            R.drawable.ic_perm_lock, act.getString(R.string.perm_36), act.getString(R.string.perm_37),
            PermSpec.GROUP_TIMER,
            { true },
            { false },
            { a -> PermActions.openLockScreen(a) },
            manual = true
        ),
        PermSpec(
            R.drawable.ic_perm_popup, act.getString(R.string.perm_38), act.getString(R.string.perm_39),
            PermSpec.GROUP_TIMER,
            { true },
            { false },
            { a -> PermActions.openPopup(a) },
            manual = true
        ),
        // ===================== 高级与扩展 =====================
        PermSpec(
            R.drawable.ic_perm_folder, act.getString(R.string.perm_11), act.getString(R.string.perm_12),
            PermSpec.GROUP_ADV,
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R },
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() },
            { a -> PermActions.openAllFiles(a) }
        ),
        PermSpec(
            R.drawable.ic_perm_package, act.getString(R.string.perm_13), act.getString(R.string.perm_14),
            PermSpec.GROUP_ADV,
            { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O },
            { act.packageManager.canRequestPackageInstalls() },
            { a -> PermActions.openUnknownSources(a) }
        ),
        PermSpec(
            R.drawable.ic_perm_root, act.getString(R.string.perm_24), act.getString(R.string.perm_25),
            PermSpec.GROUP_ADV,
            { rooted },
            { rootGranted == 1 },
            { a -> PermActions.rootHint(a) },
            isRoot = true
        )
    )

    /** 分组标题文案 */
    fun groupTitle(act: Activity, group: Int): String = act.getString(
        when (group) {
            PermSpec.GROUP_TIMER -> R.string.perm_48
            PermSpec.GROUP_ADV -> R.string.perm_49
            else -> R.string.perm_47
        }
    )
}

/**
 * 权限跳转动作 —— 全部为无状态工具方法, 直接接收 Activity, 供引导页与权限页共用。
 */
object PermActions {

    /** Root 未授权提示: 不内置各家授权框架包名, 不跳转, 引导用户去系统授权工具手动授权 */
    fun rootHint(act: Activity) {
        Toast.makeText(act, act.getString(R.string.perm_27), Toast.LENGTH_LONG).show()
    }

    /** 精确闹钟是否就绪。
     *  注(2026-11-14 Redmi 真机实证): USE_EXACT_ALARM 已 granted, canScheduleExactAlarms() 仍返 false,
     *  只看 API 会误报"未授权", 故两条件取或。 */
    fun exactAlarmOk(act: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = act.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val byApi = runCatching { am?.canScheduleExactAlarms() == true }.getOrDefault(false)
        val byPerm = act.checkSelfPermission(Manifest.permission.USE_EXACT_ALARM) == PackageManager.PERMISSION_GRANTED
        return byApi || byPerm
    }

    fun batteryOk(act: Activity): Boolean {
        val pm = act.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(act.packageName) }.getOrDefault(false)
    }

    fun openExactAlarm(act: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (runCatching {
                act.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${act.packageName}")))
            }.isSuccess) return
        if (runCatching { act.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }.isSuccess) return
        openAppDetail(act)
    }

    /** 未豁免时直接弹系统"允许应用一直在后台运行"对话框;
     *  该 action 需要 Manifest 里的 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, 缺了会被系统静默忽略 → 退化成列表页 */
    fun openBattery(act: Activity) {
        if (!batteryOk(act)) {
            if (runCatching {
                    act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}")))
                }.isSuccess) return
        }
        if (runCatching { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isSuccess) return
        openAppDetail(act)
    }

    /** 后台运行(厂商"允许后台活动/耗电管理"开关)。
     *  与"电池优化"不是一回事: 那是 Doze 白名单, 有 isIgnoringBatteryOptimizations 可读;
     *  这个纯属厂商私有策略, 无 API、无独立入口, 只能落到应用详情页让用户自己点。 */
    fun openBackground(act: Activity) {
        Toast.makeText(act, act.getString(R.string.perm_44), Toast.LENGTH_LONG).show()
        openAppDetail(act)
    }

    /** 锁屏显示 / 后台弹窗都落在本应用的通知设置页 */
    fun openNotifSettings(act: Activity) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
        if (runCatching { act.startActivity(i) }.isSuccess) return
        openAppDetail(act)
    }

    /** 锁屏显示: 与「后台弹窗」不是一回事(锁屏面 vs 横幅/浮层), 同一设置页但要点不同开关 */
    fun openLockScreen(act: Activity) {
        Toast.makeText(act, act.getString(R.string.perm_45), Toast.LENGTH_LONG).show()
        openNotifSettings(act)
    }

    /** 后台弹窗: 通知横幅/浮层显示, 仍在通知设置页, 但指引的是「横幅通知」开关 */
    fun openPopup(act: Activity) {
        Toast.makeText(act, act.getString(R.string.perm_46), Toast.LENGTH_LONG).show()
        openNotifSettings(act)
    }

    /** 自启动: 各厂商私有页无标准 Intent, 只能逐个试候选。
     *  注(2026-11-14 一加 ColorOS 16 实证): oplus 的 StartupAppListActivity 声明了
     *  oplus.permission.OPLUS_COMPONENT_SAFE, 第三方跳转必被 Permission Denial(返回 -91),
     *  所以 OPPO 系直接走应用详情页 + 文字指引, 不做无效尝试。 */
    fun openAutoStart(act: Activity) {
        if (isOplus()) {
            Toast.makeText(act, act.getString(R.string.perm_42), Toast.LENGTH_LONG).show()
            openAppDetail(act)
            return
        }
        val candidates = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/.safeguard.PurviewTabActivity",
            "com.samsung.android.lool/.activity.BatterySettingsActivity",
            "com.meizu.safe/.permission.SmartBGActivity",
            "com.transsion.phonemaster/.autostart.AutoStartActivity"
        )
        for (cn in candidates) {
            val pkg = cn.substringBefore('/')
            var cls = cn.substringAfter('/')
            if (cls.startsWith(".")) cls = pkg + cls
            val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { act.startActivity(i) }.isSuccess) return
        }
        Toast.makeText(act, act.getString(R.string.perm_43), Toast.LENGTH_LONG).show()
        openAppDetail(act)
    }

    fun isOplus(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("oppo") || m.contains("oneplus") || m.contains("realme")
    }

    fun openAppDetail(act: Activity) {
        runCatching {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}")))
        }
    }

    fun openOverlay(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
        } catch (e: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            catch (e2: Exception) { Toast.makeText(act, act.getString(R.string.perm_21), Toast.LENGTH_LONG).show() }
        }
    }

    fun openAllFiles(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${act.packageName}")))
        } catch (e: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (e2: Exception) { Toast.makeText(act, act.getString(R.string.perm_22), Toast.LENGTH_LONG).show() }
        }
    }

    fun openUnknownSources(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
        } catch (e: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
            catch (e2: Exception) { Toast.makeText(act, act.getString(R.string.perm_23), Toast.LENGTH_LONG).show() }
        }
    }
}
