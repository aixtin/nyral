package io.github.aixtin.droidagent

import android.content.Context
import android.content.SharedPreferences

/**
 * 记忆辅助模型配置（独立于主对话 API）。
 * - 供 MemoryKeeper 生成 summary 主题索引的辅助调用。
 * - 未启用 / 配置不完整时自动回退主对话 ApiConfig（保底机制，行为与旧版完全一致）。
 */
object MemoryApiConfig {
    private const val PREF = "mem_api_config"
    private const val K_ENABLED = "enabled"
    private const val K_LABEL = "label"
    private const val K_BASE = "base"
    private const val K_KEY = "key"
    private const val K_MODEL = "model"

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class Config(
        val enabled: Boolean,
        val label: String,
        val base: String,
        val key: String,
        val model: String
    )

    fun load(): Config {
        val p = prefs() ?: return Config(false, "", "", "", "")
        return Config(
            p.getBoolean(K_ENABLED, false),
            p.getString(K_LABEL, "") ?: "",
            p.getString(K_BASE, "") ?: "",
            p.getString(K_KEY, "") ?: "",
            p.getString(K_MODEL, "") ?: ""
        )
    }

    fun save(c: Config) {
        prefs()?.edit()?.apply {
            putBoolean(K_ENABLED, c.enabled)
            putString(K_LABEL, c.label)
            putString(K_BASE, c.base)
            putString(K_KEY, c.key)
            putString(K_MODEL, c.model)
        }?.apply()
    }

    /** 独立配置是否真正可用（启用 + base/key/model 齐全） */
    fun isActive(): Boolean {
        val c = load()
        return c.enabled && c.base.isNotBlank() && c.key.isNotBlank() && c.model.isNotBlank()
    }

    /** 设置页副标题用：当前生效的辅助配置描述 */
    fun statusText(): String {
        val c = load()
        return if (isActive()) {
            if (c.label.isNotBlank()) "已启用 ${c.label} · ${c.model.trim()}"
            else "已启用 ${c.model.trim()}"
        } else {
            "跟随主对话 · ${ApiConfig.model()}"
        }
    }

    // ---- 供 MemoryKeeper 使用：优先独立配置，否则回退主对话配置（保底=原机制） ----

    fun model(): String {
        val c = load()
        return if (c.enabled && c.model.isNotBlank()) c.model.trim() else ApiConfig.model()
    }

    fun chatUrl(): String {
        val c = load()
        return if (c.enabled && c.base.isNotBlank()) {
            c.base.trim().trimEnd('/') + "/chat/completions"
        } else ApiConfig.chatUrl()
    }

    fun apiKey(): String {
        val c = load()
        return if (c.enabled && c.key.isNotBlank()) c.key.trim() else ApiConfig.apiKey()
    }
}
