package io.github.aixtin.nyral

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** 批次D-2: 请求侧工具白名单与正文壳禁令 */
class ToolWhitelistTest {

    private fun toolsOf(vararg names: String?): JSONArray {
        val arr = JSONArray()
        for (n in names) {
            val fn = JSONObject()
            if (n != null) fn.put("name", n)
            arr.put(JSONObject().put("type", "function").put("function", fn))
        }
        return arr
    }

    @Test
    fun issuedNamesComeFromToolsArray() {
        val arr = toolsOf("web_search", "browser", "ssh_run")
        check(issuedToolNamesOf(arr) == setOf("web_search", "browser", "ssh_run")) {
            "应从 tools 数组取出全部函数名, 实际 ${issuedToolNamesOf(arr)}"
        }
    }

    @Test
    fun issuedNamesSkipBlankAndMalformed() {
        val arr = JSONArray()
        arr.put(JSONObject().put("type", "function"))
        arr.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "")))
        arr.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", " calc ")))
        val got = issuedToolNamesOf(arr)
        check(got == setOf("calc")) { "空名/缺 function 应跳过并 trim, 实际 $got" }
    }

    @Test
    fun issuedNamesEmptyOnNull() {
        check(issuedToolNamesOf(null).isEmpty())
        check(issuedToolNamesOf(JSONArray()).isEmpty())
    }

    @Test
    fun shellBanPromptCoversKnownShellForms() {
        val required = listOf(
            "tool_calls", "<tool_call>", "<function=", "<invoke>", "dsml",
            "[tool_call]", "arguments", "content", "[表情:"
        )
        for (k in required) check(SHELL_BAN_PROMPT.contains(k)) { "禁令应覆盖壳形态: $k" }
        check(SHELL_BAN_PROMPT.contains("严禁")) { "禁令必须显式禁止" }
    }
}
