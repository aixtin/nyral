package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences

/**
 * 上传文件大小上限配置（设置页-其他）。
 * 单位为 MB，默认 20MB；留空或非法值回退默认。影响聊天附件发送的文件大小限制。
 */
object UploadConfig {
    private const val PREF = "upload_config"
    private const val K_MAX_MB = "max_mb"
    const val DEFAULT_MB = 20
    const val MAX_MB = 500

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun maxMb(): Int {
        val v = prefs()?.getInt(K_MAX_MB, DEFAULT_MB) ?: DEFAULT_MB
        return v.coerceIn(1, MAX_MB)
    }

    fun setMaxMb(mb: Int) {
        prefs()?.edit()?.putInt(K_MAX_MB, mb.coerceIn(1, MAX_MB))?.apply()
    }
}
