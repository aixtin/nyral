package io.github.aixtin.nyral

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 状态探测(2026-09-14)。
 * 需求: 设置页权限管理 / 首启引导页补充 Root 权限项, 但授权框架多(KernelSU/Magisk/APatch…),
 * 不内置各家包名、不做跳转, 只需知道"设备是否具备 root 能力"与"本应用是否已被授权"。
 * 通用探测不依赖具体框架, 兼容 KernelSU/Magisk/APatch/SuperSU 等.
 */
object RootCheck {

    /** 设备是否具备 root 能力(仅探测 su 二进制/框架痕迹, 不执行 su、不触发授权弹窗) */
    fun deviceRooted(): Boolean {
        val traces = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/system/etc/init.d/99SuperSUDaemon",
            "/data/adb/ksu",          // KernelSU
            "/data/adb/magisk",        // Magisk
            "/data/adb/ap"             // APatch
        )
        return traces.any { File(it).exists() } || suInPath()
    }

    /** PATH 中是否存在 su(仅查找不执行, 无副作用) */
    private fun suInPath(): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start()
        p.waitFor(2000, TimeUnit.MILLISECONDS) &&
            p.inputStream.readBytes().toString(Charsets.UTF_8).isNotBlank()
    } catch (e: Exception) {
        false
    }

    /**
     * 本应用当前是否获得 root 授权(实时探测 su -c id, 无进程级缓存)。
     * 已授权: su 静默放行; 未授权: 首次可能触发系统授权弹窗(KernelSU/Magisk 等会弹),
     * 用户拒绝且勾选"记住"后默认拒绝不再弹.
     * 耗时最多 timeoutMs, 调用方须放后台线程.
     */
    fun isGranted(timeoutMs: Long = 3000): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            false
        } else {
            p.inputStream.readBytes().toString(Charsets.UTF_8).contains("uid=0")
        }
    } catch (e: Exception) {
        false
    }

    /**
     * root 自动补齐自身权限(尽力而为, 2026-09-14)。
     * 已获得 root 后调用: 运行时权限用 pm grant, 特殊权限用 appops set,
     * 一次 root 授权即可静默补齐通知/麦克风/悬浮窗/所有文件访问/安装未知应用。
     * 逐项容错: 单个失败不影响其他, 已授权项幂等无副作用; 全程静默不抛错。
     * 调用方须放后台线程(每项一次 su 子进程).
     */
    fun grantSelf(context: Context): Boolean {
        // H4 修复(2026-10-03): root 静默自动补权需显式开关(默认关), 关闭时拒绝静默补权, 走手动授权
        if (!SecurityConfig.rootAutoGrant(context)) return false
        val pkg = context.packageName
        // 运行时权限(dangerous): 需在 Manifest 声明, pm grant 直接写权限库
        val runtimePerms = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECORD_AUDIO"
        )
        // 特殊权限(appop 类型): 悬浮窗 / 所有文件访问 / 安装未知应用
        val appOps = listOf(
            "SYSTEM_ALERT_WINDOW",
            "MANAGE_EXTERNAL_STORAGE",
            "REQUEST_INSTALL_PACKAGES"
        )
        runtimePerms.forEach { perm ->
            runCmd("su", "-c", "pm grant $pkg $perm")
        }
        appOps.forEach { op ->
            runCmd("su", "-c", "appops set $pkg $op allow")
        }
        return true
    }

    /** 静默执行一条 root 命令, 单项失败忽略, 最长等待 timeoutMs */
    private fun runCmd(vararg cmd: String, timeoutMs: Long = 4000) = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.inputStream.readBytes()
        } else {
            p.destroyForcibly()
        }
    } catch (e: Exception) {
        null
    }
}
