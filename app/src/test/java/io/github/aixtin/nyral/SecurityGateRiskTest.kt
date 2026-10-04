package io.github.aixtin.nyral

import org.junit.Test

/**
 * 安全门禁 R3-1 防回归测试(2026-10-04):
 * 门禁判定键必须是复合键 name:action, 高危名单(DANGER_CONFIRM_TOOLS)中每个条目
 * 都必须被 SecurityConfig.riskOf 判为 HIGH/CRITICAL —— 这样 auto 档无票时才会弹确认;
 * 裸工具名(browser/file/app/workdir)必须保持 MEDIUM, 锁定"裸名不会误触发高危确认"的语义。
 * 全部为纯逻辑, 不触碰 Android API/Context。
 */
class SecurityGateRiskTest {

    private val high = SecurityConfig.RiskLevel.HIGH
    private val critical = SecurityConfig.RiskLevel.CRITICAL

    @Test
    fun dangerListEntriesAllRankHighOrCritical() {
        val f = ToolExecutor::class.java.getDeclaredField("DANGER_CONFIRM_TOOLS").apply { isAccessible = true }
        val list = (f.get(ToolExecutor) as Set<*>).map { it.toString() }
        check(list.isNotEmpty()) { "高危名单不应为空" }
        for (key in list) {
            val r = SecurityConfig.riskOf(key)
            check(r == high || r == critical) { "高危名单条目 $key 的 riskOf 应为 HIGH/CRITICAL, 实际 $r —— 门禁 auto 档将漏确认" }
        }
    }

    @Test
    fun compositeDangerKeysRankHigh() {
        // 报告 R3-1 原文: browser:click/type/upload、app:click/text/tap/launch、file:write/upload、workdir:write
        val composite = listOf(
            "browser:click", "browser:type", "browser:upload", "browser:clear_cache", "browser:save_cookies",
            "app:click", "app:text", "app:tap", "app:launch",
            "file:write", "file:upload", "workdir:write"
        )
        for (key in composite) {
            val r = SecurityConfig.riskOf(key)
            check(r == high) { "复合键 $key 应判 HIGH, 实际 $r" }
        }
    }

    @Test
    fun bareToolNamesRemainMedium() {
        // 裸工具名不应命中复合键名单: auto 档对纯只读动作(browser 打开等)不弹确认是设计预期
        val bare = listOf("browser", "app", "file", "workdir")
        for (n in bare) {
            val r = SecurityConfig.riskOf(n)
            check(r == SecurityConfig.RiskLevel.MEDIUM) { "裸工具名 $n 应判 MEDIUM, 实际 $r" }
        }
    }

    @Test
    fun legacyStandaloneDangerToolsRankHigh() {
        val keys = listOf("ssh_run", "sh_run", "js_run", "web_download")
        for (k in keys) {
            val r = SecurityConfig.riskOf(k)
            check(r == high) { "独立高危工具 $k 应判 HIGH, 实际 $r" }
        }
        check(SecurityConfig.riskOf("security_set") == critical) { "security_set 应为 CRITICAL" }
        check(SecurityConfig.riskOf("mcp:anything") == high) { "mcp: 前缀工具应判 HIGH" }
    }
}
