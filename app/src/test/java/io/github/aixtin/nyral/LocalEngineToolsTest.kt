package io.github.aixtin.nyral

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.lang.reflect.Method

/**
 * LocalEngine 高风险纯逻辑区 JVM 测试(工具注册表/参数归一/输出截断/降级判定)。
 * 全部反射调用真实实现, 不拷贝复刻; 不触碰 Android API。
 */
class LocalEngineToolsTest {

    private fun method(name: String, vararg paramTypes: Class<*>): Method =
        LocalEngine::class.java.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }

    private fun field(name: String): Any? =
        LocalEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(LocalEngine)

    // ================= 输出截断 capOut =================

    @Test
    fun capOutKeepsShortOutput() {
        val m = method("capOut", String::class.java)
        val short = "正常回答"
        check(m.invoke(LocalEngine, short) as String == short) { "短输出不应被修改" }
        val boundary = "x".repeat(60000)
        check((m.invoke(LocalEngine, boundary) as String).length == 60000) { "恰好 60000 字符不应截断" }
    }

    @Test
    fun capOutTruncatesOversize() {
        val m = method("capOut", String::class.java)
        val r = m.invoke(LocalEngine, "y".repeat(70000)) as String
        check(r.length > 60000) { "截断后应保留 60000 前缀, 实际 ${r.length}" }
        check(r.startsWith("y".repeat(60000))) { "截断后应以原文前 60000 字符开头" }
        check(r.endsWith("[输出过长已截断]")) { "应追加截断提示, 实际尾部: ${r.takeLast(30)}" }
    }

    // ================= 工具名归一 normalizeToolName =================

    @Test
    fun normalizeToolNameTrimsUnderscores() {
        val m = method("normalizeToolName", String::class.java)
        check(m.invoke(LocalEngine, "_web_search") as String == "web_search")
        check(m.invoke(LocalEngine, "web_search_") as String == "web_search")
        check(m.invoke(LocalEngine, "___web_search___") as String == "web_search")
        check(m.invoke(LocalEngine, "web_search") as String == "web_search")
    }

    // ================= 参数归一 normalizeArgs =================

    @Test
    fun normalizeArgsExtractsCalcExpr() {
        val m = method("normalizeArgs", String::class.java, String::class.java)
        val r = m.invoke(LocalEngine, "calc", """{"expr":"17*23"}""") as String
        check(r == "17*23") { "calc 应抽取 expr 为裸字符串, 实际 $r" }
    }

    @Test
    fun normalizeArgsExtractsMemorySearchQuery() {
        val m = method("normalizeArgs", String::class.java, String::class.java)
        val r = m.invoke(LocalEngine, "memory_search", """{"query":"表格方案"}""") as String
        check(r == "表格方案") { "memory_search 应抽取 query, 实际 $r" }
    }

    @Test
    fun normalizeArgsMergesSshConnCmd() {
        val m = method("normalizeArgs", String::class.java, String::class.java)
        val r = m.invoke(LocalEngine, "ssh_run", """{"conn":"vps","cmd":"ls /"}""") as String
        check(r == "vps:ls /") { "ssh_run conn+cmd 应合并为 连接名:命令, 实际 $r" }
    }

    @Test
    fun normalizeArgsKeepsBareString() {
        val m = method("normalizeArgs", String::class.java, String::class.java)
        val r = m.invoke(LocalEngine, "ssh_run", "vps:ls /") as String
        check(r == "vps:ls /") { "裸字符串应原样透传, 实际 $r" }
    }

    @Test
    fun normalizeArgsFallbackOnBlankExpr() {
        val m = method("normalizeArgs", String::class.java, String::class.java)
        val r = m.invoke(LocalEngine, "calc", """{"expr":""}""") as String
        check(r == """{"expr":""}""") { "空 expr 应回退原始 JSON, 实际 $r" }
    }

    // ================= 工具注册表完整性 =================

    @Suppress("UNCHECKED_CAST")
    private fun registry(): List<LocalEngine.ToolSpec> = field("toolRegistry") as List<LocalEngine.ToolSpec>

    @Test
    fun toolRegistryNamesUniqueAndNonBlank() {
        val specs = registry()
        check(specs.isNotEmpty()) { "工具注册表不应为空" }
        val names = specs.map { it.name }
        check(names.size == names.toSet().size) { "工具名重复: ${names.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        check(names.none { it.isBlank() }) { "工具名不应为空" }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun legacyMappingsPointToExistingToolsAndValidActions() {
        val legacy = field("LEGACY_TOOL_ACTION") as Map<String, Pair<String, String>>
        val specs = registry().associateBy { it.name }
        val schemaMethod = method("builtinSchema", String::class.java)
        check(legacy.isNotEmpty()) { "旧工具映射表不应为空" }
        for ((legacyName, mapping) in legacy) {
            val (tool, action) = mapping
            check(specs.containsKey(tool)) { "旧工具 $legacyName 映射到不存在的工具 $tool" }
            check(legacyName != tool || legacyName == "open_browser") { "旧工具名不应与目标同名: $legacyName" }
            // 若目标工具 schema 定义了 action 枚举, 则映射的 action 必须是合法枚举值
            val schema = schemaMethod.invoke(LocalEngine, tool) as JSONObject
            val props = schema.optJSONObject("properties")
            val actionProp = props?.optJSONObject("action")
            val enum = actionProp?.optJSONArray("enum")
            if (enum != null) {
                val values = (0 until enum.length()).map { enum.getString(it) }
                check(action in values) { "旧工具 $legacyName 的 action=$action 不在 $tool 合法枚举 $values 中" }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun whitelistToolsAllExistInRegistry() {
        val whitelist = field("TOOL_WHITELIST") as Set<String>
        val names = registry().map { it.name }.toSet()
        val missing = whitelist - names
        check(missing.isEmpty()) { "白名单引用不存在的工具: $missing" }
    }

    // ================= builtinSchema 合法性 =================

    @Test
    fun builtinSchemaValidForAllRegistryTools() {
        val schemaMethod = method("builtinSchema", String::class.java)
        for (spec in registry()) {
            val schema = schemaMethod.invoke(LocalEngine, spec.name) as JSONObject
            check(schema.optString("type") == "object") { "${spec.name} schema type 应为 object, 实际 ${schema.optString("type")}" }
            val props = schema.optJSONObject("properties")
            check(props != null) { "${spec.name} schema 应有 properties" }
            val required = schema.optJSONArray("required")
            if (required != null) {
                for (i in 0 until required.length()) {
                    val k = required.getString(i)
                    check(props.has(k)) { "${spec.name} required 字段 $k 不在 properties 中" }
                }
            }
        }
    }

    @Test
    fun requiredActionsEnumsCoverExecutablePaths() {
        // 保证 executeTool 中 when(action) 的每个分支都有对应枚举值(防模型无法走到分支)
        val schemaMethod = method("builtinSchema", String::class.java)
        val expected = mapOf(
            "browser" to listOf("open", "scan", "text", "scroll", "click", "type", "upload", "clear_cache", "save_cookies"),
            "app" to listOf("scan", "click", "text", "back", "home", "launch", "installed", "tap", "screenshot"),
            "workdir" to listOf("list", "read", "write", "grep", "head", "stats"),
            "file" to listOf("list", "read", "info", "write", "upload", "download", "ls"),
            "site_auth" to listOf("list", "set", "del")
        )
        for ((tool, actions) in expected) {
            val schema = schemaMethod.invoke(LocalEngine, tool) as JSONObject
            val actionProp = schema.optJSONObject("properties")?.optJSONObject("action")
            check(actionProp != null) { "$tool 应定义 action 属性" }
            val enumArr = actionProp.optJSONArray("enum") ?: JSONArray()
            val enumValues = (0 until enumArr.length()).map { enumArr.getString(it) }
            check(enumValues.containsAll(actions)) { "$tool action 枚举缺失: ${actions - enumValues.toSet()}" }
        }
    }

    // ================= tools 降级判定 =================

    @Test
    fun looksLikeToolsUnsupportedRecognizesCommonErrors() {
        val m = method("looksLikeToolsUnsupported", String::class.java)
        check(m.invoke(LocalEngine, "The model does not support tools") as Boolean) { "does not support 应识别" }
        check(m.invoke(LocalEngine, "tools unsupported") as Boolean) { "unsupported 应识别" }
        check(m.invoke(LocalEngine, "unknown parameter: tools") as Boolean) { "unknown parameter 应识别" }
        check(m.invoke(LocalEngine, "tools: additional properties are not allowed") as Boolean) { "additional properties 应识别" }
        check(m.invoke(LocalEngine, "tools request failed: HTTP 400 Bad Request") as Boolean) { "400 应识别" }
    }

    @Test
    fun looksLikeToolsUnsupportedExcludesToolCallIdError() {
        val m = method("looksLikeToolsUnsupported", String::class.java)
        // 2026-09-18 实测默认厂商该报错含 invalid+400 曾被误判为 tools 不支持, 必须排除
        val err = "HTTP 400: invalid tool_call_id, Duplicate tool_call_id"
        check(!(m.invoke(LocalEngine, err) as Boolean)) { "tool_call_id 相关错误不应降级(会放大 DSML 泄漏)" }
    }

    @Test
    fun looksLikeToolsUnsupportedRejectsIrrelevantErrors() {
        val m = method("looksLikeToolsUnsupported", String::class.java)
        check(!(m.invoke(LocalEngine, "401 Unauthorized") as Boolean)) { "401 不应识别为 tools 不支持" }
        check(!(m.invoke(LocalEngine, "context length exceeded") as Boolean)) { "超长错误不应误判" }
    }
    // ================= assistant tool_calls 回填配对(2026-10-05 400 修复) =================

    private fun streamResultClass(): Class<*> =
        LocalEngine::class.java.declaredClasses.first { it.simpleName == "StreamResult" }

    private fun newStreamResult(calls: List<Pair<String, String>>, ids: List<String>): Any {
        val cls = streamResultClass()
        val ctor = cls.declaredConstructors.first { it.parameterCount == 6 }
        ctor.isAccessible = true
        return ctor.newInstance("", calls, null, ids, null, false)
    }

    private fun buildAssistantToolMessage(res: Any, calls: List<Pair<String, String>>): JSONObject {
        val m = LocalEngine::class.java.getDeclaredMethod(
            "buildAssistantToolMessage", streamResultClass(), List::class.java
        )
        m.isAccessible = true
        return m.invoke(LocalEngine, res, calls) as JSONObject
    }

    @Test
    fun assistantToolCallsMatchesAllExecuted() {
        // 无截断: 回填数量 = 实际执行数量, id 与 StreamResult.toolCallIds 一一对应
        val calls = listOf("scan" to "{}", "click" to "{\"index\":0}", "text" to "{}")
        val ids = listOf("call_scan_1", "call_click_2", "call_text_3")
        val msg = buildAssistantToolMessage(newStreamResult(calls, ids), calls)
        val arr = msg.getJSONArray("tool_calls")
        check(arr.length() == 3) { "无截断时回填 3 条, 实际 " + arr.length() }
        for (i in 0 until 3) {
            val tc = arr.getJSONObject(i)
            check(tc.getString("id") == ids[i]) { "第 " + i + " 条 id 应 " + ids[i] + ", 实际 " + tc.getString("id") }
            check(tc.getJSONObject("function").getString("name") == calls[i].first) { "第 " + i + " 条函数名不符" }
        }
    }

    @Test
    fun assistantToolCallsTruncatedToExecutedSubset() {
        // 截断场景(400 bug 回归): 模型请求 5 个调用, 实际只执行前 2 个(额度耗尽),
        // 回填必须只带 2 条 tool_calls, 不能回填全集 5 条(否则 tool 结果消息不足被拒)
        val full = (0 until 5).map { "tool_" + it to "{}" }
        val executed = full.take(2)
        val ids = (0 until 5).map { "call_real_" + it }
        val msg = buildAssistantToolMessage(newStreamResult(full, ids), executed)
        val arr = msg.getJSONArray("tool_calls")
        check(arr.length() == 2) { "截断后回填应只 2 条(实际执行数), 实际 " + arr.length() + "; 回填全集 5 条即 400 根因" }
        check(arr.getJSONObject(0).getString("id") == "call_real_0") { "首条 id 应对应实际执行的第 0 个" }
        check(arr.getJSONObject(1).getString("id") == "call_real_1") { "次条 id 应对应实际执行的第 1 个" }
    }

    @Test
    fun assistantToolCallsEmptyWhenNoneExecuted() {
        val msg = buildAssistantToolMessage(newStreamResult(emptyList(), emptyList()), emptyList())
        check(msg.getJSONArray("tool_calls").length() == 0) { "无调用时 tool_calls 应为空数组" }
    }
}
