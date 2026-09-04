package io.github.aixtin.droidagent

import android.content.Context
import android.content.SharedPreferences

/**
 * AI 人设与名字配置（设置页-外观）。
 * - AI 名字：system 提示词中的自称，默认 "DroidAgent"
 * - AI 人设：追加在 system 提示词开头的身份描述，留空用内置默认
 */
object PersonaConfig {
    private const val PREF = "persona_config"
    private const val K_NAME = "ai_name"
    private const val K_PERSONA = "ai_persona"
    const val DEFAULT_NAME = "DroidAgent"
    const val DEFAULT_PERSONA = ""

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun aiName(): String {
        val v = prefs()?.getString(K_NAME, DEFAULT_NAME)
        return if (v.isNullOrBlank()) DEFAULT_NAME else v.trim()
    }

    fun aiPersona(): String {
        val v = prefs()?.getString(K_PERSONA, DEFAULT_PERSONA)
        return if (v.isNullOrBlank()) DEFAULT_PERSONA else v.trim()
    }

    fun setAiName(v: String) {
        prefs()?.edit()?.putString(K_NAME, v.trim())?.apply()
    }

    fun setAiPersona(v: String) {
        prefs()?.edit()?.putString(K_PERSONA, v.trim())?.apply()
    }
}
