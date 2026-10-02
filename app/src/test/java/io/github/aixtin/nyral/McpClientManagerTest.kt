package io.github.aixtin.nyral

import org.junit.Test
import java.lang.reflect.Method

/**
 * McpClientManager 服务名消毒逻辑 JVM 测试。
 * sanitizeName 决定 MCP 工具名前缀: 前缀错误会导致模型无法回溯工具归属服务、撞名等。
 */
class McpClientManagerTest {

    private val sanitizeMethod: Method by lazy {
        McpClientManager::class.java.getDeclaredMethod("sanitizeName", String::class.java)
            .apply { isAccessible = true }
    }

    private fun sanitize(s: String): String =
        sanitizeMethod.invoke(McpClientManager, s) as String

    @Test
    fun keepsAlnumUnderscoreAndChinese() {
        check(sanitize("mt_apk_mcp") == "mt_apk_mcp") { "字母数字下划线应原样保留" }
        check(sanitize("APK分析") == "APK分析") { "中文应保留" }
        check(sanitize("Server_01") == "Server_01") { "混合名应保留" }
    }

    @Test
    fun convertsOtherCharsToUnderscore() {
        check(sanitize("mt-apk mcp") == "mt_apk_mcp") { "空格/连字符应转下划线, 实际 ${sanitize("mt-apk mcp")}" }
        check(sanitize("a.b/c") == "a_b_c") { "点/斜杠应转下划线, 实际 ${sanitize("a.b/c")}" }
    }

    @Test
    fun trimsLeadingTrailingUnderscores() {
        check(sanitize("_mt_apk_") == "mt_apk") { "首尾下划线应去除, 实际 ${sanitize("_mt_apk_")}" }
        check(sanitize(" -mt- ") == "mt") { "全转下划线后应去除首尾, 实际 ${sanitize(" -mt- ")}" }
    }

    @Test
    fun emptyAndBlankYieldEmpty() {
        check(sanitize("") == "") { "空串应返回空" }
        check(sanitize("   ") == "") { "空白串应返回空" }
        check(sanitize("!!!") == "") { "纯特殊字符应返回空" }
    }

    @Test
    fun distinctNamesStayDistinct() {
        // 两个不同服务名消毒后不应撞名(撞名在 reload 中靠 _2 后缀兜底, 但前缀应尽量保持区分度)
        val a = sanitize("mt apk")
        val b = sanitize("mt-apk")
        check(a == b) { "测试前提: 两种写法应消毒为同一前缀(验证兜底必要性)" }
        check(a.isNotBlank()) { "消毒结果不应为空" }
    }
}
