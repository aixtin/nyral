package io.github.aixtin.droidagent

import android.content.Context
import android.content.SharedPreferences

/**
 * 界面标题自定义配置（设置页-外观）。
 * - 主页标题：聊天主页标题栏文字
 * - 侧栏标题：汉堡页（左侧抽屉）头部文字
 * 两者独立存储，默认均为 "DroidAgent"；留空保存时回退默认值。
 */
object TitleConfig {
    private const val PREF = "title_config"
    private const val K_MAIN = "main_title"
    private const val K_DRAWER = "drawer_title"
    private const val K_DRAWER_NOTE = "drawer_note"
    const val DEFAULT = "DroidAgent"
    const val DEFAULT_NOTE = "你有对象吗？"

    @Volatile private var app: Context? = null

    /** 由每个 Activity.onCreate 调用一次注入 context（重复调用无害） */
    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun mainTitle(): String {
        val v = prefs()?.getString(K_MAIN, DEFAULT)
        return if (v.isNullOrBlank()) DEFAULT else v
    }

    fun drawerTitle(): String {
        val v = prefs()?.getString(K_DRAWER, DEFAULT)
        return if (v.isNullOrBlank()) DEFAULT else v
    }

    /** 侧栏便签：汉堡页头部副标题，默认 "本地智能体 · v1.0" */
    fun drawerNote(): String {
        val v = prefs()?.getString(K_DRAWER_NOTE, DEFAULT_NOTE)
        return if (v.isNullOrBlank()) DEFAULT_NOTE else v
    }

    fun setMainTitle(v: String) {
        prefs()?.edit()?.putString(K_MAIN, v.trim())?.apply()
    }

    fun setDrawerTitle(v: String) {
        prefs()?.edit()?.putString(K_DRAWER, v.trim())?.apply()
    }

    fun setDrawerNote(v: String) {
        prefs()?.edit()?.putString(K_DRAWER_NOTE, v.trim())?.apply()
    }
}
