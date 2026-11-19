@file:Suppress("InvalidPackageDeclaration") // all/ 目录沿用 io.github.aixtin.nyral 包名, 与同目录既有文件一致

package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray

/**
 * 表情库白名单(2026-11-19): 提示词注入 / 流式掩码 / 收尾拆分 三处同源读取。
 *
 * 背景: 之前三处各自解析 lib_items, 且"白名单为空"时行为不一致 —— 提示词仍把
 * [表情:名] 语法告诉模型, 掩码照样替换成〔表情〕, 拆分却因空表不拆, 结果模型自造的
 * 名字既上不了气泡、又在正文留下占位。统一到这里后, 库空即视为"不支持表情标记"。
 */
object EmojiLib {
    private const val PREF = "emoji_drawer"
    private const val KEY = "lib_items"
    private const val TTL_MS = 3000L

    @Volatile private var cacheAt = 0L
    @Volatile private var cacheMap: Map<String, String> = emptyMap()

    /** 名字 -> 文件路径; 3 秒内存缓存, 流式期间每帧读 prefs 太贵 */
    fun nameToFile(ctx: Context): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - cacheAt < TTL_MS) return cacheMap
        val m = try {
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name")
                val f = o.optString("file")
                if (n.isNotBlank() && f.isNotBlank()) n to f else null
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
        cacheMap = m
        cacheAt = now
        return m
    }

    fun names(ctx: Context): List<String> = nameToFile(ctx).keys.toList()

    /**
     * 剥除白名单外的 [表情:名] 标记(纯函数, 便于 JVM 单测)。
     * 库为空 / 名字不在库中 -> 整段删除, 不留占位也不留裸标记; 命中则原样保留给拆分器。
     */
    fun stripUnknownMarks(text: String, known: Set<String>): String =
        Regex("\\[表情:([^\\]]*)\\]").replace(text) { m ->
            if (m.groupValues[1].trim() in known) m.value else ""
        }

    /** 流式用: 命中 -> 〔表情〕占位, 未命中 -> 删除 */
    fun maskMarks(text: String, known: Set<String>): String =
        Regex("\\[表情:([^\\]]*)\\]").replace(text) { m ->
            if (m.groupValues[1].trim() in known) "〔表情〕" else ""
        }
}
