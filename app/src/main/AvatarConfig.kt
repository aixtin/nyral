package io.github.aixtin.nyral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 用户 / AI 头像配置（设置页-外观）。
 * - 自定义头像存 filesDir/avatar_user.png 与 avatar_ai.png（圆形透明 PNG）
 * - 无自定义时渲染层回退默认文字圆
 */
object AvatarConfig {
    private const val PREF = "avatar_config"
    private const val K_USER = "user_avatar"
    private const val K_AI = "ai_avatar"
    private const val USER_FILE = "avatar_user.png"
    private const val AI_FILE = "avatar_ai.png"
    private const val STORE_SIZE = 256

    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun prefs(): android.content.SharedPreferences? = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun avatarFile(name: String): File? {
        val dir = app?.filesDir ?: return null
        return File(dir, name)
    }

    fun userAvatarFile(): File? = avatarFile(USER_FILE)
    fun aiAvatarFile(): File? = avatarFile(AI_FILE)

    fun hasUserAvatar(): Boolean = userAvatarFile()?.exists() == true
    fun hasAiAvatar(): Boolean = aiAvatarFile()?.exists() == true

    /** 从相册 uri 保存用户头像（圆形裁剪落盘） */
    fun setUserAvatar(uri: Uri): Boolean = saveAvatar(uri, USER_FILE)
    /** 从相册 uri 保存 AI 头像（圆形裁剪落盘） */
    fun setAiAvatar(uri: Uri): Boolean = saveAvatar(uri, AI_FILE)

    /** 从 1:1 正方形裁剪结果文件保存用户头像（圆形化落盘） */
    fun setUserAvatarFromSquare(file: File): Boolean = saveSquare(file, USER_FILE)
    /** 从 1:1 正方形裁剪结果文件保存 AI 头像（圆形化落盘） */
    fun setAiAvatarFromSquare(file: File): Boolean = saveSquare(file, AI_FILE)

    fun clearUserAvatar(): Boolean = clearAvatar(USER_FILE)
    fun clearAiAvatar(): Boolean = clearAvatar(AI_FILE)

    private fun saveAvatar(uri: Uri, fileName: String): Boolean {
        val ctx = app ?: return false
        return try {
            val src = decodeUri(ctx, uri) ?: return false
            val circle = circleCropCopy(src, STORE_SIZE)
            src.recycle()
            val file = avatarFile(fileName) ?: return false
            FileOutputStream(file).use { fos ->
                circle.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            circle.recycle()
            prefs()?.edit()?.putBoolean(if (fileName == USER_FILE) K_USER else K_AI, true)?.apply()
            true
        } catch (e: Exception) { false }
    }

    private fun clearAvatar(fileName: String): Boolean {
        avatarFile(fileName)?.delete()
        prefs()?.edit()?.remove(if (fileName == USER_FILE) K_USER else K_AI)?.apply()
        return true
    }

    /** 从 1:1 正方形裁剪文件保存头像（圆形化落盘） */
    private fun saveSquare(file: File, fileName: String): Boolean {
        return try {
            if (!file.exists()) return false
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: return false
            val circle = circleCropCopy(src, STORE_SIZE)
            src.recycle()
            val target = avatarFile(fileName) ?: return false
            FileOutputStream(target).use { fos ->
                circle.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            circle.recycle()
            prefs()?.edit()?.putBoolean(if (fileName == USER_FILE) K_USER else K_AI, true)?.apply()
            true
        } catch (e: Exception) { false }
    }

    /** 渲染：返回圆形头像 BitmapDrawable，无自定义头像返回 null */
    fun avatarDrawable(file: File?, px: Int): BitmapDrawable? {
        if (file == null || !file.exists()) return null
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val circle = circleCropCopy(src, px)
        src.recycle()
        return BitmapDrawable(app?.resources, circle)
    }

    /** 解码 uri 图片，采样缩放避免 OOM */
    private fun decodeUri(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > STORE_SIZE * 2) sample *= 2
            val dec = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dec) }
        } catch (e: Exception) { null }
    }

    /** 居中裁正方形 + 圆形透明裁剪 + 缩放，输出全新 bitmap */
    private fun circleCropCopy(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val square = if (src.width == side && src.height == side) src
        else Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        c.drawBitmap(scaled, 0f, 0f, p)
        if (scaled !== square) scaled.recycle()
        if (square !== src) square.recycle()
        return out
    }
}
