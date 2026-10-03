package io.github.aixtin.nyral

import android.content.Context

/**
 * 安全管理开关(H3/H4 修复, 2026-10-03):
 * - danger_confirm(默认开): 危险工具(ssh_run/sh_run/js_run/file.write/browser点击上传等)执行前需用户确认(指纹二次放行)
 * - root_auto_grant(默认关): root 静默自动补权(悬浮窗/文件访问/安装未知应用/录音/通知)开关;
 *   关闭后 grantSelf 不执行, 走手动授权
 * 开关入口: 对话内调用 security_set 工具(设置页 UI 后续可加)
 */
object SecurityConfig {
    private const val PREFS = "nyral_security"
    private const val KEY_DANGER = "danger_confirm"
    private const val KEY_ROOT_GRANT = "root_auto_grant"

    fun dangerConfirm(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DANGER, true)

    fun setDangerConfirm(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DANGER, on).apply()
    }

    fun rootAutoGrant(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROOT_GRANT, false)

    fun setRootAutoGrant(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROOT_GRANT, on).apply()
    }

    /** security_set 工具入口: JSON {danger_confirm?, root_auto_grant?, ssh_trust?} */
    fun set(context: Context, argRaw: String): String {
        val j = try { org.json.JSONObject(argRaw.trim()) } catch (e: Exception) { return "参数格式错误(需 JSON)" }
        val sb = StringBuilder()
        if (j.has("danger_confirm")) {
            val v = j.optBoolean("danger_confirm")
            setDangerConfirm(context, v)
            sb.append("危险操作确认门禁: ").append(if (v) "已开启(默认)" else "已关闭(不推荐)").append("\n")
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
        return sb.toString().ifEmpty { "security_set 需指定至少一项: danger_confirm / root_auto_grant / ssh_trust" }
    }
}
