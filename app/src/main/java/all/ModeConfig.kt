package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 界面模式配置：聊天模式 / Agent 模式
 * - 聊天模式(chatMode=true)：带头像(用户/AI 并排)；正文默认渲染 Markdown(chatMarkdown=true)，可关闭回退纯文本
 * - Agent 模式(chatMode=false)：维持原样(不展示头像，保留 Markdown 渲染)
 * 会话按模式隔离(listSessions 只列当前模式会话)，长期记忆库两模式互通。
 */
object ModeConfig {
    private const val PREFS = "mode_config"
    private const val KEY_CHAT_MODE = "chat_mode"
    private const val KEY_CHAT_MD = "chat_md"
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

    /** 聊天模式是否渲染 Markdown(默认开)；关闭则正文走纯文本剥离 */
    fun chatMarkdown(): Boolean = prefs.getBoolean(KEY_CHAT_MD, true)

    /** 开关聊天模式的 Markdown 渲染 */
    fun setChatMarkdown(on: Boolean) {
        prefs.edit().putBoolean(KEY_CHAT_MD, on).apply()
    }

    /** 渲染层统一判断：是否应按纯文本处理正文（聊天模式且关闭 MD 时才剥；Agent 模式/开 MD 均按 Markdown 渲染） */
    fun chatPlainText(): Boolean = chatMode() && !chatMarkdown()

    /** 聊天模式正文协议前缀硬兜底(与 MD 开关无关, 所有渲染路径统一先剥):
     * 思考:/TOOL: 行整体剔除(思考已由 thinking 区承载, TOOL 走原生 function calling), 答案: 仅去前缀保留正文。全半角兼容。
     * 根因: 本地模型按 fmtRules(thinkingOff=false) 仍输出 思考:/答案: 前缀, 聊天模式原生 tools 分支不做行级剥离;
     * 而 stripMarkdownForChat 此前仅 chatPlainText()(聊天模式且关 MD)时执行, MD 开关默认开 -> 兜底失效, 前缀漏入正文。
     */
    fun stripChatProtocolPrefix(s: String): String {
        if (s.isBlank()) return s
        return s.replace(Regex("(?m)^\\s*思考\\s*[:：][^\\r\\n]*\\r?\\n?"), "")
            .replace(Regex("(?m)^\\s*TOOL\\s*[:：][^\\r\\n]*\\r?\\n?"), "")
            .replace(Regex("(?m)^\\s*答案\\s*[:：]\\s*"), "")
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim()
    }

    /** 会话隔离用的模式值：0=Agent 模式，1=聊天模式 */
    fun modeValue(): Int = if (chatMode()) 1 else 0
}
