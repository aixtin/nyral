package io.github.aixtin.droidagent

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Uri/MediaStore 纯工具集合(从 MainActivity 拆分):
 * 全部为无 Activity 依赖的纯函数, 通过 ContentResolver 操作, 便于独立测试与复用。
 */
object MediaFileUtils {
    /** 读取 Uri 全部字节; 无法打开抛 IllegalStateException(由调用方决策) */
    fun readAll(cr: ContentResolver, uri: Uri): ByteArray {
        val ins: InputStream = cr.openInputStream(uri) ?: throw IllegalStateException("无法打开文件")
        return ins.use { i ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = i.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    }

    /** 读取文件头 n 字节用于格式嗅探(失败返回空, 由上层按原逻辑兜底) */
    fun readHead(cr: ContentResolver, uri: Uri, n: Int): ByteArray {
        return try {
            cr.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(n)
                val len = ins.read(buf, 0, n)
                if (len < 0) ByteArray(0) else if (len == n) buf else buf.copyOf(len)
            } ?: ByteArray(0)
        } catch (e: Exception) { ByteArray(0) }
    }

    /** 查询 Uri 显示名(失败返回 null) */
    fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
        return try {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    /**
     * 实况照片关联的 motion 视频 Uri(仅 Android 10+)。
     * 匹配顺序: ①同 _id ②related_owner_id(API30+) ③同 DATA 路径前缀 + 同名 .mp4。
     */
    fun motionVideoUriOf(cr: ContentResolver, imgUri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            var imgId = -1L
            var imgData: String? = null
            cr.query(imgUri, arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA
            ), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    imgId = c.getLong(0)
                    imgData = if (c.getColumnIndex(MediaStore.Images.Media.DATA) >= 0) c.getString(1) else null
                }
            }
            if (imgId < 0) return null
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            // ①同 _id
            var vid = -1L
            cr.query(videoUri, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media._ID} = ?", arrayOf(imgId.toString()), null)?.use { c ->
                if (c.moveToFirst()) vid = c.getLong(0)
            }
            // ②RELATED_OWNER_ID (API 30+; 常量在 MediaColumns, minSdk 编译不可引用, 用字符串列名)
            if (vid < 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cr.query(videoUri, arrayOf(MediaStore.Video.Media._ID),
                    "related_owner_id = ?",
                    arrayOf(imgId.toString()), null)?.use { c ->
                    if (c.moveToFirst()) vid = c.getLong(0)
                }
            }
            // ③同 DATA 路径名.mp4 (xxx.heic + xxx.mp4 成对); 局部拷贝避免闭包 smart-cast 报错
            val imgDataPath = imgData
            if (vid < 0 && imgDataPath != null && imgDataPath.isNotEmpty()) {
                val base = imgDataPath.substringBeforeLast('.')
                if (base.isNotEmpty()) {
                    val esc = base.replace("'", "''")
                    cr.query(videoUri, arrayOf(MediaStore.Video.Media._ID),
                        "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.MIME_TYPE} LIKE ?",
                        arrayOf("$esc%.mp4", "video/%"), null)?.use { c ->
                        if (c.moveToFirst()) vid = c.getLong(0)
                    }
                }
            }
            if (vid < 0) null else ContentUris.withAppendedId(videoUri, vid)
        } catch (e: Exception) {
            Log.w("DroidAgent", "motionVideoUriOf 查询失败", e)
            null
        }
    }

    /** 图片按最长边 limit 等比采样解码并压缩为 JPEG(≤3MB 用 85 质量, 超过降 70) */
    fun compressImage(cr: ContentResolver, uri: Uri, maxSide: Int): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > maxSide || opts.outHeight / sample > maxSide) sample *= 2
        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dec) }
            ?: throw IllegalStateException("无法解码图片")
        val scaled = if (bmp.width > maxSide || bmp.height > maxSide) {
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true).also { if (it != bmp) bmp.recycle() }
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (out.size() > 3 * 1024 * 1024) {
            out.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        val bytes = out.toByteArray()
        scaled.recycle()
        return bytes
    }
}
