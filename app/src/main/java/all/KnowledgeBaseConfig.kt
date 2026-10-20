package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 知识库自动沉淀开关(2026-10-19): 独立旁路功能, 与现有记忆系统零交集。
 * 默认关闭: 不打开的用户零成本; 打开的用户消耗自己的 token。
 */
object KnowledgeBaseConfig {
    private const val PREFS = "knowledge_base"
    private const val K_ENABLED = "kb_enabled"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(K_ENABLED, on).apply()
    }
}
