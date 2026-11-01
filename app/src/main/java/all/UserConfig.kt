package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户档案配置（设置 → 外观 → AI 个性化 → 我的资料）。
 * - 用户名字：对话对象的名字，留空时界面显示"我"
 * - 用户爱好：注入系统提示词"用户档案"段，帮助 AI 了解对话对象
 */
object UserConfig {
    private const val PREF = "user_config"
    private const val K_NAME = "user_name"
    private const val K_HOBBY = "user_hobby"
    const val DEFAULT_NAME = ""
    const val DEFAULT_HOBBY = ""

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun userName(): String {
        val v = prefs()?.getString(K_NAME, DEFAULT_NAME)
        return if (v.isNullOrBlank()) DEFAULT_NAME else v.trim()
    }

    fun userHobby(): String {
        val v = prefs()?.getString(K_HOBBY, DEFAULT_HOBBY)
        return if (v.isNullOrBlank()) DEFAULT_HOBBY else v.trim()
    }

    fun setUserName(v: String) {
        prefs()?.edit()?.putString(K_NAME, v.trim())?.apply()
    }

    fun setUserHobby(v: String) {
        prefs()?.edit()?.putString(K_HOBBY, v.trim())?.apply()
    }
}
