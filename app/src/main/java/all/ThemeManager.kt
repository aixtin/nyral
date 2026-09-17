package io.github.aixtin.nyral

import android.content.Context

/**
 * 主题管理器：读取/切换当前主题，持久化到 app_prefs（与工程现有偏好一致）。
 * 当前主题全局唯一，切换后各页面从 ThemeManager.current() 取色。
 */
object ThemeManager {
    const val PREFS = "app_prefs"
    const val KEY_THEME_ID = "theme_id"

    /** 全部可用主题（有序，顺序即设置页展示顺序） */
    val themes: List<AppTheme> = listOf(
        DefaultTheme,
        FlatTheme,
        KawaiiTheme,
        KiwiDarkTheme
    )

    /** 当前主题（无记录默认主题 0） */
    fun current(ctx: Context): AppTheme {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_ID, DefaultTheme.id)
        return themes.firstOrNull { it.id == id } ?: DefaultTheme
    }

    /** 切换并持久化主题 */
    fun setTheme(ctx: Context, id: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_ID, id)
            .apply()
    }
}
