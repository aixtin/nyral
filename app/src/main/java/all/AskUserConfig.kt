package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 输入澄清(ask_user)总开关配置
 * - 开启(默认): AI 遇到模糊/多义/缺关键信息的指令时, 可调用 ask_user 弹原生选择框向用户当面确认
 * - 关闭: LocalEngine 直接丢弃 ask_user 调用, AI 基于已有信息自行判断继续, 不打扰用户
 * 开关位置: 设置页 → 其他 → 输入澄清
 */
object AskUserConfig {
    private const val PREFS = "ask_user_config"
    private const val KEY_ENABLED = "enabled"

    /** 是否开启输入澄清(默认开启) */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** 设置输入澄清开关 */
    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
