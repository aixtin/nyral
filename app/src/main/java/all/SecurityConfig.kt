package io.github.aixtin.nyral

import android.content.Context

/**
 * 安全管理开关 v3(2026-10-04 三档门禁):
 * - danger_mode: strict(严格: 每次确认, 禁用窗口期) / auto(自动: 高危确认+窗口期复用, 默认) / off(放行: 关闭门禁)
 * - 兼容旧布尔 danger_confirm: true→auto, false→off
 * - 风险分级 RiskLevel 预留: 自动档内先两级(HIGH/CRITICAL 确认, MEDIUM/LOW 自动放行)
 * - 票据窗口期: auto 档保留(5 分钟同参数复用); strict 档禁用(每次确认)
 */
object SecurityConfig {
    private const val PREFS = "nyral_security"
    private const val KEY_DANGER = "danger_confirm"
    private const val KEY_DANGER_MODE = "danger_mode"
    private const val KEY_ROOT_GRANT = "root_auto_grant"

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    fun dangerMode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DANGER_MODE, "auto") ?: "auto"

    fun setDangerMode(ctx: Context, mode: String) {
        val m = when (mode.trim()) {
            "strict", "auto", "off" -> mode.trim()
            else -> "auto"
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DANGER_MODE, m).apply()
    }

    /** 兼容旧布尔: true→auto(开启确认), false→off(关闭) */
    fun dangerConfirm(ctx: Context): Boolean = dangerMode(ctx) != "off"

    fun setDangerConfirm(ctx: Context, on: Boolean) {
        setDangerMode(ctx, if (on) "auto" else "off")
    }

    fun rootAutoGrant(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROOT_GRANT, false)

    fun setRootAutoGrant(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROOT_GRANT, on).apply()
    }

    // ===== 风险分级(自动档内两级, 预留多级扩展) =====
    private val CRITICAL_TOOLS = setOf("security_set")
    private val HIGH_TOOLS = setOf(
        "ssh_run", "sh_run", "js_run", "browser_eval", "web_download",
        "file:write", "file:upload", "workdir:write",
        "browser:click", "browser:type", "browser:upload", "browser:clear_cache", "browser:save_cookies",
        "app:click", "app:text", "app:tap", "app:launch"
    )

    fun riskOf(name: String): RiskLevel = when {
        name in CRITICAL_TOOLS -> RiskLevel.CRITICAL
        name.startsWith("mcp:") -> RiskLevel.HIGH
        name in HIGH_TOOLS -> RiskLevel.HIGH
        else -> RiskLevel.MEDIUM
    }

    /** 门禁决策: 是否需要用户确认(off→不; strict→总是; auto→无票则高危确认, 低危自动放行) */
    fun needsConfirm(ctx: Context, name: String, arg: String): Boolean {
        return when (dangerMode(ctx)) {
            "off" -> false
            "strict" -> true
            else -> {
                if (hasTicket(ctx, name, arg)) return false
                val r = riskOf(name)
                r == RiskLevel.HIGH || r == RiskLevel.CRITICAL
            }
        }
    }

    fun modeText(m: String): String = when (m) {
        "strict" -> "严格(每次确认)"
        "auto" -> "自动(高危确认+窗口期复用)"
        "off" -> "放行(关闭门禁)"
        else -> "未知"
    }

    /** security_set 工具入口: JSON {danger_mode?, danger_confirm?, root_auto_grant?, ssh_trust?} */
    fun set(context: Context, argRaw: String): String {
        val j = try { org.json.JSONObject(argRaw.trim()) } catch (e: Exception) { return "参数格式错误(需 JSON)" }
        val sb = StringBuilder()
        if (j.has("danger_mode")) {
            val m = j.optString("danger_mode").trim()
            setDangerMode(context, m)
            sb.append("危险门禁模式: ").append(modeText(dangerMode(context))).append("\n")
        }
        if (j.has("danger_confirm")) {
            val v = j.optBoolean("danger_confirm")
            setDangerConfirm(context, v)
            sb.append("危险操作确认门禁: ").append(if (v) "已开启(auto)" else "已关闭(off, 不推荐)").append("\n")
        }
        if (j.has("root_auto_grant")) {
            val v = j.optBoolean("root_auto_grant")
            setRootAutoGrant(context, v)
            sb.append("root静默自动补权: ").append(if (v) "已开启(注意: 有root即静默补全特殊权限)" else "已关闭(默认, 走手动授权)").append("\n")
        }
        if (j.has("ssh_trust")) {
            val host = j.optString("ssh_trust").trim()
            if (host.isEmpty()) sb.append("ssh_trust 需指定连接名/主机标识\n")
            else sb.append("SSH主机信任: ").append(SshTools.trustHost(context, host)).append("\n")
        }
        return sb.toString().ifEmpty { "security_set 需指定至少一项: danger_mode / danger_confirm / root_auto_grant / ssh_trust" }
    }

    // ===== 票据 + 审计(v2 保留) =====
    private val tickets = java.util.concurrent.ConcurrentHashMap<String, Long>() // key -> expireAtMs
    private const val TICKET_TTL_MS = 5 * 60 * 1000L
    private const val AUDIT_FILE = "nyral_security_audit.log"

    private fun ticketKey(name: String, arg: String): String = "$name|${sha256(name, arg)}"

    private fun sha256(name: String, arg: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val d = md.digest("$name\n$arg".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 用户允许后签发窗口期票据(5分钟有效, 绑定工具+参数) */
    fun grantTicket(ctx: Context, name: String, arg: String) {
        tickets[ticketKey(name, arg)] = System.currentTimeMillis() + TICKET_TTL_MS
    }

    /** 门禁放行前检查票据(窗口期复用, 不消耗); strict 档禁用(每次确认) */
    fun hasTicket(ctx: Context, name: String, arg: String): Boolean {
        if (dangerMode(ctx) == "strict") return false
        val exp = tickets[ticketKey(name, arg)] ?: return false
        return exp > System.currentTimeMillis()
    }

    /** 窗口期复用校验(v2 兼容): 存在且未过期即放行, 不删除票据; strict 档禁用 */
    fun validateTicket(ctx: Context, name: String, arg: String): Boolean {
        if (dangerMode(ctx) == "strict") return false
        val exp = tickets[ticketKey(name, arg)] ?: return false
        return exp > System.currentTimeMillis()
    }

    /** 审计日志: 追加到 filesDir/nyral_security_audit.log (JSON 行) */
    fun audit(ctx: Context, name: String, arg: String, approvedBy: String, channel: String = "bubble") {
        try {
            val f = java.io.File(ctx.filesDir, AUDIT_FILE)
            val line = org.json.JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("tool", name)
                .put("argHash", sha256(name, arg).take(12))
                .put("arg", arg.take(200))
                .put("approvedBy", approvedBy)
                .put("channel", channel)
                .toString() + "\n"
            f.appendText(line)
        } catch (e: Exception) { /* 审计失败不阻塞主流程 */ }
    }

    /** 审计文件路径(供审计页/导出查看) */
    fun auditFile(ctx: Context): java.io.File = java.io.File(ctx.filesDir, AUDIT_FILE)
}
