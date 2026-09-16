package io.github.aixtin.nyral

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * 本地视频压缩(零第三方依赖, 系统 MediaCodec 硬编):
 * 视频轨 Surface-to-Surface 重编码(h264, 降低分辨率/码率/帧率), 音频轨 aac 直通(保留声音)。
 * 用于超大视频本地压缩后再发送, 数据不出本机。
 */
object VideoCompressor {

    private const val TARGET_BITRATE = 1_500_000   // 1.5 Mbps
    private const val TARGET_MAX_SIDE = 720         // 最长边
    private const val TARGET_FPS = 24
    private const val IFRAME_INTERVAL = 1           // 秒
    private const val AUDIO_BITRATE = 96_000

    /** 同步压缩: 把 uri 视频转码到 outFile; 失败抛异常 */
    fun compress(context: Context, uri: Uri, outFile: File) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开视频")
        pfd.use {
            val extractor = MediaExtractor()
            extractor.setDataSource(it.fileDescriptor)
            val vIdx = findTrack(extractor, MediaFormat.MIMETYPE_VIDEO_AVC) { mime -> mime.startsWith("video/") }
            if (vIdx < 0) throw IllegalStateException("视频无画面轨")
            extractor.selectTrack(vIdx)
            val srcFormat = extractor.getTrackFormat(vIdx)
            val width = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (srcFormat.containsKey(MediaFormat.KEY_ROTATION)) srcFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            val (outW, outH) = scaleKeep(width, height, TARGET_MAX_SIDE)

            // ---- 编码器 (Surface 输入) ----
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // ---- 解码器 (Surface 输出到编码器输入面) ----
            val decFormat = extractor.getTrackFormat(vIdx)
            val decoder = MediaCodec.createDecoderByType(decFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(decFormat, inputSurface, null, 0)
            decoder.start()

            // ---- 音频直通(仅 aac) ----
            var audioExtractor: MediaExtractor? = null
            val aIdx = findTrack(extractor, MediaFormat.MIMETYPE_AUDIO_AAC) { mime -> mime.startsWith("audio/") }

            // ---- Muxer ----
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val videoMuxIdx = muxer.addTrack(encoder.outputFormat)
            var audioMuxIdx = -1
            if (aIdx >= 0) {
                val aFmt = extractor.getTrackFormat(aIdx)
                val aMime = aFmt.getString(MediaFormat.KEY_MIME)
                if (aMime != null && aMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    audioExtractor = MediaExtractor()
                    audioExtractor.setDataSource(it.fileDescriptor)
                    audioExtractor.selectTrack(aIdx)
                    audioMuxIdx = muxer.addTrack(aFmt)
                }
            }
            muxer.start()

            try {
                transcodeLoop(extractor, decoder, encoder, muxer, videoMuxIdx, audioExtractor, audioMuxIdx)
            } finally {
                try { decoder.stop() } catch (_: Exception) {}
                try { decoder.release() } catch (_: Exception) {}
                try { encoder.stop() } catch (_: Exception) {}
                try { encoder.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
                audioExtractor?.let { ae -> try { ae.release() } catch (_: Exception) {} }
                try { muxer.stop() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
            }
        }
    }

    private fun transcodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        videoMuxIdx: Int,
        audioExtractor: MediaExtractor?,
        audioMuxIdx: Int
    ) {
        val dInfo = MediaCodec.BufferInfo()
        val eInfo = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        var guard = 0

        while (!outputEOS) {
            // 1. 解码器输入(读源视频样本)
            if (!inputEOS) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)
                    val sz = buf?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sz < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOS = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // 2. 解码器输出(渲染到 Surface 喂给编码器)
            var dDone = false
            while (!dDone) {
                when (val idx = decoder.dequeueOutputBuffer(dInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> dDone = true
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 无需处理: 输出即渲染面 */ }
                    else -> if (idx >= 0) {
                        decoder.releaseOutputBuffer(idx, true)
                        if (dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) dDone = true
                    } else dDone = true
                }
            }
            // 3. 编码器输出(写 muxer)
            var eDone = false
            while (!eDone) {
                when (val idx = encoder.dequeueOutputBuffer(eInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> eDone = true
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 格式已在 addTrack 时获取 */ }
                    else -> if (idx >= 0) {
                        if (eInfo.size > 0 && eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val ob = encoder.getOutputBuffer(idx)
                            if (ob != null) {
                                ob.position(eInfo.offset)
                                ob.limit(eInfo.offset + eInfo.size)
                                muxer.writeSampleData(videoMuxIdx, ob, eInfo)
                                writePendingAudio(audioExtractor, audioMuxIdx, muxer, eInfo.presentationTimeUs)
                            }
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eDone = true
                            outputEOS = true
                        }
                    } else eDone = true
                }
            }
            // 安全阀: 防止极端情况下无限循环
            if (++guard > 200_000) break
        }
        // 收尾: 写掉剩余音频
        writePendingAudio(audioExtractor, audioMuxIdx, muxer, Long.MAX_VALUE)
    }

    /** 把音频轨时间戳 <= uptoUs 的样本写入 muxer(视频/音频按时间交错) */
    private fun writePendingAudio(ae: MediaExtractor?, idx: Int, muxer: MediaMuxer, uptoUs: Long) {
        if (ae == null || idx < 0) return
        while (true) {
            val sampleSize = ae.sampleSize.toInt()
            if (sampleSize < 0) break
            val t = ae.sampleTime
            if (t > uptoUs) break
            val buf = ByteBuffer.allocate(sampleSize)
            ae.readSampleData(buf, 0)
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = t
            info.flags = ae.sampleFlags
            muxer.writeSampleData(idx, buf, info)
            ae.advance()
        }
    }

    /** 查找指定类型的 track 索引 */
    private fun findTrack(extractor: MediaExtractor, primaryMime: String, isMatch: (String) -> Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (isMatch(mime)) return i
        }
        return -1
    }

    private fun scaleKeep(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val max = maxOf(w, h)
        if (max <= maxSide) return w to h
        val ratio = maxSide.toFloat() / max
        return (w * ratio).toInt().let { ow ->
            ow to (h * ratio).toInt()
        }
    }
}
