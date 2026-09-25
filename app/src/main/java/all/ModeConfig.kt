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
    private const val KEY_ACTION_TRACK = "action_track"
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

    /** 行动轨道开关(方案A 原型): AI 流式输出以时间轴行动轨道+无框文本轨道呈现, 替代气泡形态 */
    fun actionTrack(): Boolean = prefs.getBoolean(KEY_ACTION_TRACK, false)

    /** 切换行动轨道 */
    fun setActionTrack(on: Boolean) {
        prefs.edit().putBoolean(KEY_ACTION_TRACK, on).apply()
    }

    /** 表情气泡开关: 仅聊天模式可用(有头像场景); Agent 模式(无头像)两端均禁发表情气泡 */
    fun emojiEnabled(): Boolean = chatMode()

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

    /** 聊天模式纯文本渲染: 剥 Markdown 语法(代码围栏/粗斜体/删除线/链接图片/标题/引用/列表/表格), 留可读纯文本;
     *  先走 stripChatProtocolPrefix 剥协议前缀(幂等), 所有渲染路径统一调用: 流式收尾/历史恢复/正文气泡。
     *  历史: 2026-09-18 前 MD 剥离只挂在流式 AiBubbleHolder 分支, 切会话/重启走的历史渲染路径(MainActivity
     *  静态 Ai 行/AiRich contentRow)纯文本分支只剥前缀不剥 MD → 重启后满屏 ## ** | ` 原始符号("纯文本效果不好"
     *  根因之一), 本函数提升至 ModeConfig 后四处统一 */
    fun stripMarkdownForChat(s: String): String {
        if (s.isBlank()) return s
        var t = stripChatProtocolPrefix(s)
        // 代码块: 去掉围栏行, 保留内容
        t = t.replace(Regex("(?s)```[^\\r\\n]*\\r?\\n?(.*?)```"), "$1")
        // 行内代码: `x` -> x
        t = t.replace(Regex("`([^`]+)`"), "$1")
        // 粗斜体/加粗/斜体: ***x*** -> x, **x** -> x, *x* -> x
        t = t.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
        // 删除线: ~~x~~ -> x
        t = t.replace(Regex("~~(.+?)~~"), "$1")
        // 图片/链接: ![alt](url) -> alt, [text](url) -> text
        t = t.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // 标题: 行首 # -> 去掉
        t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
        // 引用: 行首 > -> 去掉
        t = t.replace(Regex("(?m)^\\s*>+\\s*"), "")
        // 无序/有序列表: 行首 - * + 或 1. -> 去掉标记
        t = t.replace(Regex("(?m)^\\s*(?:[-*+]|\\d{1,3}\\.)\\s+"), "")
        // 表格分隔行(|---|---|)整行去掉; 竖线 -> 空格, 并清理行尾多余空格
        t = t.replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$"), "")
        t = t.replace("|", " ")
        t = t.replace(Regex("(?m)[ \\t]+$"), "")
        // 压缩多余空行, 保留段落分隔
        return t.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** 会话隔离用的模式值：0=Agent 模式，1=聊天模式 */
    fun modeValue(): Int = if (chatMode()) 1 else 0
}
