package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 界面模式配置：聊天模式 / Agent 模式
 * - 聊天模式(chatMode=true)：带头像(用户/AI 并排)，正文纯文本不渲染 Markdown，AI 侧禁止输出 MD 格式
 * - Agent 模式(chatMode=false)：维持原样(不展示头像，保留 Markdown 渲染)
 * 会话按模式隔离(listSessions 只列当前模式会话)，长期记忆库两模式互通。
 */
object ModeConfig {
    private const val PREFS = "mode_config"
    private const val KEY_CHAT_MODE = "chat_mode"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 当前是否聊天模式 */
    fun chatMode(): Boolean = prefs.getBoolean(KEY_CHAT_MODE, false)

    /** 切换聊天/Agent 模式 */
    fun setChatMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_CHAT_MODE, on).apply()
    }

    /** 会话隔离用的模式值：0=Agent 模式，1=聊天模式 */
    fun modeValue(): Int = if (chatMode()) 1 else 0
}
