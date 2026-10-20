package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 崩溃上报总开关配置(2026-10-21)。
 * - 开启(默认): 冷启动时将未导出的崩溃/ANR/异常退出日志经 HTTPS 匿名上传至作者服务器(atin.asia/crash)
 * - 关闭: 跳过上传与导出, 崩溃日志仅留在应用私有目录, 不上传任何数据
 * 开关位置: 外观设置 → 快捷开关 → 崩溃上报
 */
object CrashReportConfig {
    private const val PREFS = "crash_report_config"
    private const val KEY_ENABLED = "enabled"

    /** 是否开启崩溃上报(默认开启) */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** 设置崩溃上报开关 */
    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
