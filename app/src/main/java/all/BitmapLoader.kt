package io.github.aixtin.droidagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File

/**
 * 统一 Bitmap 解码入口：采样降内存 + 缩略图 LruCache。
 *
 * 目的：把各处散落的 BitmapFactory.decode* 收敛到这里，统一采样策略、
 * 避免漏采样(整图全量解码)导致 OOM、并为重复渲染(头像/缩略图)提供缓存复用。
 * 纯 framework 实现(android.util.LruCache + BitmapFactory)，无第三方依赖。
 */
object BitmapLoader {

    /**
     * 计算 inSampleSize：把最长边压到 <= maxSide*2，之后绘制/缩放交给 Canvas。
     * 单调 2 的幂，保证 BitmapFactory 走快速下采样路径。
     */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(w, h) / sample > maxSide * 2) sample *= 2
        return sample
    }

    /** 解码本地文件，最长边采样缩到 maxSide。读取失败/无尺寸返回 null。 */
    fun decodeSampledFile(file: File, maxSide: Int): Bitmap? {
        val path = file.absolutePath
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            return BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) { return null }
    }

    /** 解码 Uri 流：先只读尺寸(bounds 预读)，再按采样二次读流。失败返回 null。 */
    fun decodeSampledUri(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) { return null }
    }

    /** 解码 ByteArray，采样缩到 maxSide。失败返回 null。 */
    fun decodeSampledBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) { return null }
    }

    // 缩略图缓存：按 key 复用最近解码出的渲染位图，默认内存上限 24MB，超限自动淘汰最久未用。
    private val sThumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 带缓存渲染解码：同 key 直接命中缓存，避免重复解码。
     * 注意：返回的位图归缓存所有，调用方【禁止 recycle】，只用于显示/绘制。
     */
    fun cachedSampled(path: String, key: String, maxSide: Int): Bitmap? {
        sThumbCache.get(key)?.let { return it }
        return decodeSampledFile(File(path), maxSide)?.also { sThumbCache.put(key, it) }
    }

    /** 命中是否已在缓存（诊断用）。 */
    @Suppress("unused")
    fun isCached(key: String): Boolean = sThumbCache.get(key) != null
}
