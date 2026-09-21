package io.github.aixtin.nyral

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 动图(GIF) -> 无声 MP4 转换器, 零第三方依赖。
 * 原理: 自解析 GIF87a/89a(LZW 逐帧取位图, 处理 GCE/透明/Disposal)
 *       -> MediaCodec 硬编 H.264(buffer 输入, PTS 精确可控)
 *       -> MediaMuxer 封装 MP4。
 * 对齐微信"大动图转无声循环视频"策略; 数据不出本机。
 */
object GifToMp4 {

    private const val MAX_SIDE = 960          // 动图分辨率上限(超过等比缩小)
    private const val BITRATE = 900_000       // 码率
    private const val DEFAULT_FRAME_MS = 100  // GIF delay 为 0 的兜底帧时长
    private const val MAX_FRAMES = 240        // 采样后的编码帧数上限(超帧数时均匀抽帧, 不再回退静态图)
    /** 表情转码时长上限(ms): 超过截断, 只保留前段并保持窗口内原始帧率。
     *  与 EmojiFrameAnimator.MAX_DURATION_MS 对齐: 截断产物必然是短表情, 走帧动画流畅播放,
     *  杜绝"298s/240帧 超长低帧率 MP4"导致 0.8fps 幻灯片与渲染端黑屏。 */
    const val MAX_DURATION_MS = 3000L

    private class Gce(val delayMs: Int, val transparent: Int, val disposal: Int)
    private class Lzw(private val data: ByteArray) {
        var pos = 0
        private var acc = 0
        private var nbits = 0

        fun read(codeSize: Int): Int {
            while (nbits < codeSize) {
                if (pos >= data.size) throw IllegalStateException("LZW 数据不足")
                acc = acc or ((data[pos].toInt() and 0xFF) shl nbits)
                pos++
                nbits += 8
            }
            val v = acc and ((1 shl codeSize) - 1)
            acc = acc ushr codeSize
            nbits -= codeSize
            return v
        }
    }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** 判断是否为动画 GIF(含 2 个及以上图像帧); 非 GIF/损坏返回 false */
    fun isAnimated(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != '8'.code.toByte()) return false
        return try { countFrames(bytes) >= 2 } catch (_: Exception) { false }
    }

    /** 统计图像帧数(0x2C 图像描述符), 用于 isAnimated */
    private fun countFrames(bytes: ByteArray): Int {
        if (bytes.size < 13) return 0
        var pos = 13
        val packed = bytes[10].toInt() and 0xFF
        if (packed and 0x80 != 0) pos += (1 shl ((packed and 0x07) + 1)) * 3
        var n = 0
        while (pos < bytes.size) {
            when (bytes[pos].toInt() and 0xFF) {
                0x3B -> break
                0x21 -> { // extension: 跳过 label 与所有 sub-block
                    pos += 2
                    while (pos < bytes.size) {
                        val len = bytes[pos].toInt() and 0xFF
                        pos += 1 + len
                        if (len == 0) break
                    }
                }
                0x2C -> {
                    n++
                    pos += 9
                    val p2 = bytes[pos].toInt() and 0xFF
                    pos++
                    if (p2 and 0x80 != 0) pos += (1 shl ((p2 and 0x07) + 1)) * 3
                    pos++ // LZW 最小码长
                    while (pos < bytes.size) {
                        val len = bytes[pos].toInt() and 0xFF
                        pos += 1 + len
                        if (len == 0) break
                    }
                }
                else -> return n
            }
        }
        return n
    }

    /** 预扫描"时长截断窗口"内图像帧数与累计时长: 用于把采样步长限定在窗口内, 保证截断段不丢帧率。
     *  (帧数, 窗口内累计时长 ms); maxDurMs 有限值(截断启用)时才调用。 */
    private fun countFramesInWindow(bytes: ByteArray, maxDurMs: Long): Pair<Int, Long> {
        if (bytes.size < 13) return 0 to 0L
        var pos = 13
        val packed = bytes[10].toInt() and 0xFF
        if (packed and 0x80 != 0) pos += (1 shl ((packed and 0x07) + 1)) * 3
        var n = 0
        var elapsedMs = 0L
        var pendingDelay = DEFAULT_FRAME_MS
        while (pos < bytes.size) {
            when (bytes[pos].toInt() and 0xFF) {
                0x3B -> return n to elapsedMs
                0x21 -> {
                    pos++
                    val label = if (pos < bytes.size) bytes[pos].toInt() and 0xFF else -1
                    pos++
                    var payload = ByteArray(0)
                    var p = pos
                    val bos = java.io.ByteArrayOutputStream()
                    while (p < bytes.size) {
                        val len = bytes[p].toInt() and 0xFF
                        p++
                        if (len == 0) break
                        if (p + len > bytes.size) throw IllegalStateException("子块越界")
                        bos.write(bytes, p, len)
                        p += len
                    }
                    payload = bos.toByteArray()
                    pos = p
                    if (label == 0xF9 && payload.size >= 4) {
                        val delayMs = le16(payload, 1) * 10
                        pendingDelay = if (delayMs > 0) delayMs else DEFAULT_FRAME_MS
                    }
                }
                0x2C -> {
                    n++
                    elapsedMs += pendingDelay
                    pendingDelay = DEFAULT_FRAME_MS
                    pos += 9
                    val p2 = bytes[pos].toInt() and 0xFF
                    pos++
                    if (p2 and 0x80 != 0) pos += (1 shl ((p2 and 0x07) + 1)) * 3
                    pos++ // LZW 最小码长
                    while (pos < bytes.size) {
                        val len = bytes[pos].toInt() and 0xFF
                        pos += 1 + len
                        if (len == 0) break
                    }
                    if (elapsedMs >= maxDurMs) return n to elapsedMs
                }
                else -> return n to elapsedMs
            }
        }
        return n to elapsedMs
    }

    /**
     * 把 GIF 转成无声 mp4。成功返回编码帧数(>=1); 非动图/损坏/超出限制返回 -1。
     * 任何异常均向上抛出, 由调用方兜底回退静态图。
     * @param maxDurationMs 可选时长截断上限(ms): 表情库传 GifToMp4.MAX_DURATION_MS 截断为短循环,
     *                      普通发送链路默认不截断(保持原行为)。
     */
    fun convert(bytes: ByteArray, outFile: File, maxDurationMs: Long = Long.MAX_VALUE): Int {
        // ---- 头部与全局色表 ----
        if (bytes.size < 13 || bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != '8'.code.toByte()) return -1
        val w = le16(bytes, 6)
        val h = le16(bytes, 8)
        if (w <= 0 || h <= 0) return -1
        val packed = bytes[10].toInt() and 0xFF
        val bgIndex = bytes[11].toInt() and 0xFF
        var pos = 13
        var globalPalette: IntArray? = null
        if (packed and 0x80 != 0) {
            val gctSize = 1 shl ((packed and 0x07) + 1)
            globalPalette = readPalette(bytes, pos, gctSize)
            pos += gctSize * 3
        }
        val bgColor: Int = if (globalPalette != null && bgIndex < globalPalette.size) {
            // 0xFF000000 超过 Int.MAX, 必须 .toInt() 否则字面量被推断为 Long
            0xFF000000.toInt() or globalPalette[bgIndex]
        } else 0xFF000000.toInt()

        // ---- 输出尺寸(偶数对齐) ----
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        var outW = (w * scale).toInt()
        var outH = (h * scale).toInt()
        outW += outW and 1
        outH += outH and 1
        if (outW < 2) outW = 2
        if (outH < 2) outH = 2

        // ---- 编码器 ----
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 16)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            // BT.601 limited range, 与 toNV12 的色度公式一致, 避免解码端偏色
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackIdxHolder = IntArray(1) { -1 }
        val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }

        // 超长帧序列: 预扫描帧数(截断启用时只统计窗口内), 计算均匀采样步长, 把编码帧数压到 MAX_FRAMES 内
        val useWindow = maxDurationMs != Long.MAX_VALUE
        val (windowFrames, _) = if (useWindow) countFramesInWindow(bytes, maxDurationMs) else (countFrames(bytes) to 0L)
        val totalFrames = windowFrames
        val step = if (totalFrames > MAX_FRAMES) (totalFrames + MAX_FRAMES - 1) / MAX_FRAMES else 1

        try {
            var cur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var cvs = Canvas(cur)
            cvs.drawColor(bgColor)
            var snapshot: Bitmap? = null
            var pendingGce: Gce? = null
            var frames = 0
            var frameIndex = 0
            var ptsUs = 0L
            var elapsedMs = 0L
            var breakOut = false

            while (pos < bytes.size && !breakOut) {
                when (val b = bytes[pos].toInt() and 0xFF) {
                    0x3B -> break
                    0x21 -> {
                        pos++
                        val label = if (pos < bytes.size) bytes[pos].toInt() and 0xFF else -1
                        pos++
                        val payload = readSubBlocks(bytes, pos).also { pos = it.second }
                        if (label == 0xF9 && payload.first.size >= 4) {
                            val gPacked = payload.first[0].toInt() and 0xFF
                            val delayMs = (le16(payload.first, 1)) * 10
                            val transparent = if (gPacked and 0x01 != 0) payload.first[3].toInt() and 0xFF else -1
                            pendingGce = Gce(
                                if (delayMs > 0) delayMs else DEFAULT_FRAME_MS,
                                transparent,
                                (gPacked shr 2) and 0x07
                            )
                        }
                    }
                    0x2C -> {
                        val left = le16(bytes, pos + 1)
                        val top = le16(bytes, pos + 3)
                        val rw = le16(bytes, pos + 5)
                        val rh = le16(bytes, pos + 7)
                        val p2 = bytes[pos + 9].toInt() and 0xFF
                        pos += 10
                        var pal = globalPalette
                        if (p2 and 0x80 != 0) {
                            val lctSize = 1 shl ((p2 and 0x07) + 1)
                            pal = readPalette(bytes, pos, lctSize)
                            pos += lctSize * 3
                        }
                        if (rw <= 0 || rh <= 0 || pos >= bytes.size) throw IllegalStateException("非法图像块")
                        val minCode = bytes[pos].toInt() and 0xFF
                        pos++
                        val payload = readSubBlocks(bytes, pos).also { pos = it.second }
                        val gce = pendingGce
                        pendingGce = null
                        val transparent = gce?.transparent ?: -1
                        val delayMs = gce?.delayMs ?: DEFAULT_FRAME_MS
                        val disposal = gce?.disposal ?: 0

                        val sample = step == 1 || frameIndex % step == 0
                        frameIndex++
                        // disposal=3: 记录绘制前快照(之后恢复)
                        if (disposal == 3) {
                            snapshot?.recycle()
                            snapshot = Bitmap.createBitmap(cur)
                        }
                        // 解码当前图像块
                        val indices = decodeLzw(minCode, payload.first, rw * rh)
                        val region = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                        val palArray = pal
                        for (iy in 0 until rh) {
                            for (ix in 0 until rw) {
                                val ii = iy * rw + ix
                                val idx = if (ii < indices.size) indices[ii] else -1
                                val color = if (idx >= 0 && idx != transparent && palArray != null && idx < palArray.size) {
                                    0xFF000000.toInt() or palArray[idx]
                                } else 0
                                region.setPixel(ix, iy, color)
                            }
                        }
                        cvs.drawBitmap(region, left.toFloat(), top.toFloat(), paint)
                        region.recycle()

                        if (sample) {
                            // 编码本帧(组合画面), 仅采样帧输出
                            val scaled = if (w != outW || h != outH) Bitmap.createScaledBitmap(cur, outW, outH, true) else cur
                            encodeFrame(encoder, muxer, trackIdxHolder, scaled, outW, outH, ptsUs)
                            if (scaled !== cur) scaled.recycle()
                            frames++
                        }
                        // 时长保真: 所有帧(含跳过的)累计显示时长, 保持动画节奏与原 GIF 一致
                        ptsUs += delayMs * 1000L
                        // 时长截断: 超过上限停止解析后续帧(窗口内帧已全部处理, 采样节奏不受影响)
                        if (useWindow) {
                            elapsedMs += delayMs
                            if (elapsedMs >= maxDurationMs) breakOut = true
                        }

                        // Disposal 处理(影响后续帧的合成基线)
                        when (disposal) {
                            2 -> { // 恢复到背景色
                                val clear = Paint().apply { color = bgColor }
                                cvs.drawRect(left.toFloat(), top.toFloat(), (left + rw).toFloat(), (top + rh).toFloat(), clear)
                            }
                            3 -> { // 恢复绘制前快照
                                if (snapshot != null) {
                                    cur.recycle()
                                    cur = snapshot!!
                                    cvs = Canvas(cur)
                                    snapshot = null
                                }
                            }
                        }
                    }
                    else -> break
                }
            }

            if (frames <= 0) return -1
            // 结束流
            val eosIdx = encoder.dequeueInputBuffer(10_000)
            if (eosIdx >= 0) encoder.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(encoder, muxer, trackIdxHolder, true)
            cur.recycle()
            return frames
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun readPalette(b: ByteArray, start: Int, count: Int): IntArray {
        val arr = IntArray(count)
        for (i in 0 until count) {
            val o = start + i * 3
            arr[i] = (b[o].toInt() and 0xFF) shl 16 or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
        }
        return arr
    }

    /** 读取 0x00 结束的子块序列, 返回 (payload, 结束后的游标) */
    private fun readSubBlocks(b: ByteArray, start: Int): Pair<ByteArray, Int> {
        val bos = ByteArrayOutputStream()
        var p = start
        while (p < b.size) {
            val len = b[p].toInt() and 0xFF
            p++
            if (len == 0) break
            if (p + len > b.size) throw IllegalStateException("子块越界")
            bos.write(b, p, len)
            p += len
        }
        return bos.toByteArray() to p
    }

    /** GIF LZW 解码, 输出 ≤expect 个像素索引 */
    private fun decodeLzw(minCode: Int, data: ByteArray, expect: Int): IntArray {
        val clear = 1 shl minCode
        val end = clear + 1
        var codeSize = minCode + 1
        val dict = arrayOfNulls<IntArray>(4096)
        for (i in 0 until clear) dict[i] = intArrayOf(i)
        var next = end + 1
        var prev = -1
        val out = IntArray(expect)
        var oi = 0
        val r = Lzw(data)
        while (oi < expect) {
            val code = try { r.read(codeSize) } catch (e: Exception) { break }
            if (code == clear) {
                for (i in 0 until clear) dict[i] = intArrayOf(i)
                for (i in clear until 4096) dict[i] = null
                next = end + 1
                codeSize = minCode + 1
                prev = -1
                continue
            }
            if (code == end || code < 0) break
            val entry: IntArray = when {
                code < next && dict[code] != null -> dict[code]!!
                code == next && prev >= 0 -> { // KWK 特例: 新字典项尚未定义即被引用
                    val pv = dict[prev]!!
                    concat(pv, pv[0])
                }
                else -> break
            }
            for (v in entry) { if (oi >= expect) break; out[oi++] = v }
            if (prev >= 0 && next < 4096) {
                val pv = dict[prev]!!
                dict[next] = concat(pv, entry[0])
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = code
        }
        return out
    }

    private fun concat(a: IntArray, tail: Int): IntArray {
        val r = IntArray(a.size + 1)
        System.arraycopy(a, 0, r, 0, a.size)
        r[a.size] = tail
        return r
    }

    /** 把一帧 bitmap 转 NV12 YUV 并送入编码器; 随后排空编码输出到 muxer */
    private fun encodeFrame(
        encoder: MediaCodec, muxer: MediaMuxer, trackIdx: IntArray,
        bmp: Bitmap, outW: Int, outH: Int, ptsUs: Long
    ) {
        val nv12 = toNV12(bmp, outW, outH)
        val inIdx = encoder.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf = encoder.getInputBuffer(inIdx) ?: throw IllegalStateException("无输入缓冲")
            buf.clear()
            buf.put(nv12, 0, nv12.size)
            encoder.queueInputBuffer(inIdx, 0, nv12.size, ptsUs, 0)
        }
        drain(encoder, muxer, trackIdx, false)
    }

    /** ARGB bitmap -> NV12(UV 交错) YUV。性能对小动图足够。 */
    private fun toNV12(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        val uvStride = w
        var yOff = 0
        var uvOff = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yy = (66 * r + 129 * g + 25 * b + 128) shr 8
                yuv[yOff + x] = (yy + 16).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128) shr 8
                    val v = (112 * r - 94 * g - 18 * b + 128) shr 8
                    val uvIdx = uvOff + (y / 2) * uvStride + x
                    yuv[uvIdx] = (u + 128).toByte()     // 偶数位存 U(Cb)
                    yuv[uvIdx + 1] = (v + 128).toByte() // 奇数位存 V(Cr)
                }
            }
            yOff += w
        }
        return yuv
    }

    /** 排空编码输出到 muxer; all=true 时一直排到 EOS。 */
    private fun drain(encoder: MediaCodec, muxer: MediaMuxer, trackIdx: IntArray, all: Boolean) {
        val info = MediaCodec.BufferInfo()
        var guard = 0
        while (guard++ < 200_000) {
            val idx = encoder.dequeueOutputBuffer(info, 0)
            when (idx) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!all) return else { continue }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIdx[0] < 0) {
                        trackIdx[0] = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                }
                else -> {
                    if (idx >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && trackIdx[0] >= 0) {
                            val ob = encoder.getOutputBuffer(idx) ?: continue
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIdx[0], ob, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}
