@file:Suppress("InvalidPackageDeclaration") // all/ 目录沿用 io.github.aixtin.nyral 包名，与同目录既有文件一致

package io.github.aixtin.nyral

import org.json.JSONArray

/**
 * 批次 D-2 请求侧工具白名单。
 *
 * 下发的 tools 数组同时承担两个职责:
 * 1) 告诉模型"本轮只能调用这些工具";
 * 2) 作为 StreamSanitizer 识别泄漏壳的名字白名单(extraShell)的唯一来源。
 * 两者同源, 避免正文里出现"恰好同名但本轮并未下发"的普通文字被误吞。
 */
fun issuedToolNamesOf(tools: JSONArray?): Set<String> {
    if (tools == null || tools.length() == 0) return emptySet()
    val out = LinkedHashSet<String>()
    for (i in 0 until tools.length()) {
        val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
        val name = fn.optString("name", "").trim()
        if (name.isNotEmpty()) out.add(name)
    }
    return out
}

/**
 * 正文工具壳禁令(批次D-2, 三道防线里管"源头不产生"的一道)。
 * 刻意穷举常见壳形态: 与 StreamSanitizer 的 SHELL_TAGS 对应, 改壳表时需同步本段。
 */
const val SHELL_BAN_PROMPT: String =
    "工具调用通道规约(强制): 调用工具只能通过原生 function calling 的 tool_calls 字段发起。严禁在正文(content)中输出任何形式的工具调用壳或标记: 不得写 <tool_call>/<tool_calls>/<function=名字>/<function_calls>/<invoke>/<parameter>/<arg_key>/<arg_value>/<dsml>/<tool_use> 等 XML 标签(含其闭合标签), 不得写 [tool_call]/[function_call] 之类的方括号壳, 不得写 {\"name\":..,\"arguments\":{..}} 这类 JSON 调用壳, 也不要为了\"示范\"把工具名和参数抄进正文。正文只写给人读的自然语言; 需要调用就直接发 tool_calls, 参数全部走结构化字段。工具不存在或参数不足时用自然语言说明, 不得伪造调用。\n"

fun shellBanPrompt(): String = SHELL_BAN_PROMPT
