package io.github.aixtin.nyral

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 附件发送链路（从 MainActivity 抽离，持有 host 访问其内部成员）：
 * - 图片: 压缩后 Base64
 * - 视频: 限 50MB, 超限本地转码压缩后再发, 压缩后仍超限拒绝
 * - 其余(含 PDF/txt/md/Word/Excel/PPT/压缩包): 本地解析提取文本
 *   (text 随 history 注入模型; 支持 PDF/MD/TXT/DOCX/XLSX/PPTX/ZIP/TAR/TGZ 等,
 *   提取不到或纯二进制文件则退回直发普通文件)
 */
internal class AttachmentSender(private val host: MainActivity) {

    private val MAX_IMAGE_SIDE = 2048
    private val MAX_VIDEO_BYTES = 37 * 1024 * 1024 // MiMo 视频 base64 ≤50MB(原始约 ≤37MB)
    private val SMALL_VIDEO_BYTES = 10 * 1024 * 1024 // 阈值分流: 小视频≤10MB 直传(阶段E实测校准)
    private val SMALL_VIDEO_SEC = 30 * 1000L          // 阈值分流: 小视频≤30秒 直传(阶段E实测校准)
    private val MAX_VIDEO_SEC = 3 * 60 * 1000L // 方案A: 视频时长上限 3 分钟
    private val MAX_VIDEO_MB = 50 * 1024 * 1024 // 方案A: 视频大小硬上限 50MB
    private val MAX_AUDIO_SEC = 10 * 60 * 1000L // 方案A: 音频时长上限 10 分钟
    private val GIF_ANIM_MAX_BYTES = 20 * 1024 * 1024 // 动图转视频的源 GIF 上限(超出回退静态图), 防超大内存占用

    private fun dp(v: Int) = host.dp(v)

    private fun toast(resId: Int) {
        host.uiScope.launch { Toast.makeText(host, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun toast(resId: Int, vararg args: Any?) {
        host.uiScope.launch { Toast.makeText(host, host.getString(resId, *args.map { it ?: "" }.toTypedArray()), Toast.LENGTH_SHORT).show() }
    }

    /** 视频时长(ms), 失败返回 0(不阻断发送) */
    private fun videoDurationMs(uri: Uri): Long {
        return try {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(host, uri) } catch (t: Throwable) { return 0L }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release(); ms
        } catch (e: Exception) { 0L }
    }

    /** 音频时长(ms), 失败返回 0(不阻断发送) */
    private fun audioDurationMs(uri: Uri): Long {
        return try {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(host, uri) } catch (t: Throwable) { return 0L }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release(); ms
        } catch (e: Exception) { 0L }
    }

    /** 视频元信息 JSON(时长/分辨率/大小), 失败返回 null; 供直传写工作目录/落库写附件库复用 */
    private fun videoMetaJson(uri: Uri, name: String, size: Long, mime: String, durMs: Long): JSONObject? {
        return try {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(host, uri) } catch (t: Throwable) { return null }
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE).orEmpty()
            r.release()
            JSONObject()
                .put("name", name).put("mime", mime).put("size", size)
                .put("durationMs", durMs).put("durationSec", (durMs + 500) / 1000)
                .put("width", w).put("height", h).put("fps", fps)
                .put("createdAt", System.currentTimeMillis())
        } catch (e: Exception) { null }
    }

    /** 视频元信息 → 工作目录 .meta.json(供 AI 后续按需观看/抽帧; 失败不阻断发送) */
    private fun writeVideoMeta(uri: Uri, name: String, size: Long) {
        try {
            val dur = videoDurationMs(uri)
            val meta = videoMetaJson(uri, name, size, "video/mp4", dur) ?: return
            WorkDir.write(host, name + ".meta.json", meta.toString().toByteArray())
        } catch (e: Exception) { /* meta 失败不阻断发送 */ }
    }

    private fun buildVideoAttachment(uri: Uri, mime: String, name: String, failHintRes: Int): LocalEngine.Attachment? {
        // 方案A 双维度限制: 时长 >3min 直接拒(不转码防 token 爆炸); 大小 >50MB 直接拒
        val durMs = videoDurationMs(uri)
        if (durMs > MAX_VIDEO_SEC) {
            toast(io.github.aixtin.nyral.R.string.toast_video_too_long, host.getString(failHintRes))
            return null
        }
        val raw = MediaFileUtils.readAll(host.contentResolver, uri)
        if (raw.size > MAX_VIDEO_MB) {
            toast(io.github.aixtin.nyral.R.string.toast_video_too_big, host.getString(failHintRes))
            return null
        }
        if (raw.size <= SMALL_VIDEO_BYTES && durMs <= SMALL_VIDEO_SEC) {
            // 小视频直传时顺手生成 meta 存工作目录(供 AI 按需观看/抽帧参考)
            writeVideoMeta(uri, name, raw.size.toLong())
            return LocalEngine.Attachment(mime, Base64.encodeToString(raw, Base64.NO_WRAP), name)
        }
        // 大视频(>10MB 或 >30秒, 阈值待阶段E实测校准): 落私有附件库(附件+meta 同生共死), 消息只放索引卡, 不传 base64
        val meta = videoMetaJson(uri, name, raw.size.toLong(), mime, durMs)
        val key = try {
            AttachmentStore.save(host, name, mime, raw, meta?.toString())
        } catch (e: Exception) {
            toast(io.github.aixtin.nyral.R.string.toast_video_store_fail, host.getString(failHintRes), e.message)
            return null
        }
        toast(io.github.aixtin.nyral.R.string.toast_video_stored_lib)
        return LocalEngine.Attachment(mime, "", key, stored = true)
    }

    /** 读取附件并加入预览条(补文字后由 onSend 一并发送, 不再直接发出)。 */
    fun sendAttachmentFromUri(uri: Uri) {
        host.executor.execute {
            try {
                val cr = host.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                val name = MediaFileUtils.queryDisplayName(host.contentResolver, uri) ?: "attachment"
                val lowName = name.lowercase()
                val isPdf = mime == "application/pdf" || lowName.endsWith(".pdf")
                val isVideo = mime.startsWith("video/")
                // 魔数嗅探真实格式(解决"扩展名≠真实格式"): 仅读头部, 不动文件本体
                val head = MediaFileUtils.readHead(host.contentResolver, uri, 64)
                val real = FormatSniffer.sniff(head, lowName)
                val realType = real.substringBefore('/') // image/audio/video/text 或 empty/unknown
                // 伪装/异常文件直接拒绝并提示真实情况
                if (real == FormatSniffer.EMPTY) {
                    toast(io.github.aixtin.nyral.R.string.toast_att_empty)
                    return@execute
                }
                val fakeMedia = real == FormatSniffer.TEXT &&
                    (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/"))
                if (fakeMedia) {
                    host.uiScope.launch {
                        val fakeTypeRes = when { mime.startsWith("image/") -> io.github.aixtin.nyral.R.string.att_label_image; mime.startsWith("video/") -> io.github.aixtin.nyral.R.string.att_label_video; else -> io.github.aixtin.nyral.R.string.att_label_audio }
                        Toast.makeText(host, host.getString(io.github.aixtin.nyral.R.string.toast_att_fake_media, host.getString(fakeTypeRes)), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                if (real == FormatSniffer.UNKNOWN &&
                    (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/"))) {
                    toast(io.github.aixtin.nyral.R.string.toast_att_unk_fmt)
                    return@execute
                }
                val atts: List<LocalEngine.Attachment> = when {
                    mime.startsWith("image/") || realType == "image" -> {
                        // 实况图(LIVE photo): HEIC 查 MediaStore 关联 motion 视频, 命中即以视频发送(保留动态);
                        // 未命中回退静态压缩; 压缩失败回退静态 JPEG, 绝不阻断发送
                        if (real == FormatSniffer.IMAGE_HEIC) {
                            val mv = MediaFileUtils.motionVideoUriOf(host.contentResolver, uri)
                            if (mv != null) {
                                val vAtt = buildVideoAttachment(mv, "video/mp4",
                                    name.replace(Regex("\\.heic$", RegexOption.IGNORE_CASE), ".mp4"),
                                    io.github.aixtin.nyral.R.string.att_label_live_video)
                                if (vAtt != null) {
                                    listOf(vAtt)
                                } else {
                                    val bytes = MediaFileUtils.compressImage(host.contentResolver, uri, MAX_IMAGE_SIDE)
                                    listOf(LocalEngine.Attachment("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), name))
                                }
                            } else {
                                val bytes = MediaFileUtils.compressImage(host.contentResolver, uri, MAX_IMAGE_SIDE)
                                listOf(LocalEngine.Attachment("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), name))
                            }
                        } else if (real == FormatSniffer.IMAGE_GIF) {
                            val mp4Att = try {
                                val gifBytes = MediaFileUtils.readAll(host.contentResolver, uri)
                                if (gifBytes.size in 6..GIF_ANIM_MAX_BYTES && GifToMp4.isAnimated(gifBytes)) {
                                    val f = File(host.cacheDir, "anim_${System.currentTimeMillis()}.mp4")
                                    try {
                                        val frames = GifToMp4.convert(gifBytes, f)
                                        if (frames > 0 && f.length() in 1..MAX_VIDEO_BYTES.toLong()) {
                                            val mp4 = f.readBytes()
                                            f.delete()
                                            listOf(LocalEngine.Attachment(
                                                "video/mp4", Base64.encodeToString(mp4, Base64.NO_WRAP),
                                                name.replace(Regex("\\.gif$", RegexOption.IGNORE_CASE), ".mp4")))
                                        } else { f.delete(); null }
                                    } catch (e: Exception) {
                                        try { f.delete() } catch (_: Exception) {}
                                        null
                                    }
                                } else null
                            } catch (e: Exception) { null }
                            if (mp4Att != null) mp4Att else {
                                val bytes = MediaFileUtils.compressImage(host.contentResolver, uri, MAX_IMAGE_SIDE)
                                // 压缩产物恒为 JPEG, mime 必须同步标 image/jpeg, 修复字节/mime 错配(如 .png 实为 JPEG/HEIC)
                                listOf(LocalEngine.Attachment("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), name))
                            }
                        } else {
                            val bytes = MediaFileUtils.compressImage(host.contentResolver, uri, MAX_IMAGE_SIDE)
                            // 压缩产物恒为 JPEG, mime 必须同步标 image/jpeg, 修复字节/mime 错配(如 .png 实为 JPEG/HEIC)
                            listOf(LocalEngine.Attachment("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), name))
                        }
                    }
                    isVideo || realType == "video" -> {
                        val vAtt = buildVideoAttachment(uri,
                            if (realType == "video") real else mime, name, io.github.aixtin.nyral.R.string.att_label_video)
                        if (vAtt == null) {
                            return@execute
                        }
                        listOf(vAtt)
                    }
                    else -> {
                        // 音频以魔数真实 mime 为准(扩展名可能说谎, 如 .aac 实为 MP3): 归一到模型认识的格式
                        val effMime = if (realType == "audio" && real != FormatSniffer.UNKNOWN) real else mime
                        val raw = MediaFileUtils.readAll(host.contentResolver, uri)
                        // 方案A 音频双维度: 时长 >10min 拒(大小沿用 maxFileBytes 上限)
                        if (effMime.startsWith("audio/") || realType == "audio") {
                            val aDur = audioDurationMs(uri)
                            if (aDur > MAX_AUDIO_SEC) {
                                host.uiScope.launch {
                                    Toast.makeText(host, host.getString(io.github.aixtin.nyral.R.string.toast_audio_too_long), Toast.LENGTH_SHORT).show()
                                }
                                return@execute
                            }
                        }
                        if (raw.size > host.maxFileBytes) {
                            host.uiScope.launch {
                                val maxMb = UploadConfig.maxMb()
                                val msg = when {
                                    isPdf && !name.lowercase().endsWith(".pdf") ->
                                        host.getString(io.github.aixtin.nyral.R.string.toast_att_pdf_abnormal, maxMb)
                                    isPdf -> host.getString(io.github.aixtin.nyral.R.string.toast_att_pdf_large, maxMb)
                                    else -> host.getString(io.github.aixtin.nyral.R.string.toast_att_file_large, maxMb)
                                }
                                Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                            }
                            return@execute
                        }
                        // m4a: 部分设备/APP 生成 isom/mp42 容器, MiMo 仅接受 ftyp M4A; 修正 major_brand 避免 400
                        val finalRaw = if ((effMime.startsWith("audio/") || lowName.endsWith(".m4a")) &&
                            raw.size >= 16 && raw[4].toInt().toChar() == 'f' && raw[5].toInt().toChar() == 't' &&
                            raw[6].toInt().toChar() == 'y' && raw[7].toInt().toChar() == 'p') {
                            val brand = String(raw, 8, 4)
                            if (brand != "M4A " && brand != "M4A\u0000") {
                                val out = raw.clone()
                                out[8] = 'M'.code.toByte(); out[9] = '4'.code.toByte()
                                out[10] = 'A'.code.toByte(); out[11] = ' '.code.toByte()
                                out
                            } else raw
                        } else raw
                        val txt = DocTextExtractor.extract(name, finalRaw)
                        // 无文本层 PDF(扫描件)且模型支持图像: 渲染为图片走 image_url, 避免 MiMo 对 input_file 500
                        if (txt.isNullOrBlank() && isPdf &&
                            ApiConfig.modelHasCap(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.CAP_IMAGE)) {
                            val imgs = pdfToImageAttachments(uri, name)
                            if (imgs.isEmpty()) {
                                toast(io.github.aixtin.nyral.R.string.toast_pdf_unparsable)
                                return@execute
                            }
                            // 页图标记 pdfSourceName: 显示层隐藏(不铺图片网格), 仅作为 image_url 发给模型看图;
                            // 追加 PDF 卡片附件: 气泡以文件卡片展示(点击进 PDF 全屏预览), 不再"一通到底"
                            imgs.map { it.copy(pdfSourceName = name) } + listOf(
                                LocalEngine.Attachment("application/pdf", Base64.encodeToString(finalRaw, Base64.NO_WRAP), name,
                                    text = "（PDF 扫描件，已渲染为图片供查看）"))
                        } else if (txt.isNullOrBlank()) {
                            toast(io.github.aixtin.nyral.R.string.toast_doc_unparsable)
                            return@execute
                        } else {
                            listOf(LocalEngine.Attachment(effMime, Base64.encodeToString(finalRaw, Base64.NO_WRAP), name, text = txt))
                        }
                    }
                }
                host.uiScope.launch {
                    // 预览条方案: 附件先进输入框上方预览, 补文字后由 onSend 一并发送, 不再直接发出
                    // 总量上限 6: 无论单次还是多次累积, 超出部分拒绝加入预览条(不占发送队列)
                    val MAX_ATT = 5
                    // PDF 扫描件页图(pdfSourceName 非空)是同一个 PDF 的内部展开, 不占用户文件计数
                    var userAtt = host.pendingAttachments.count { it.pdfSourceName == null }
                    for (att in atts) {
                        val isPageImg = att.pdfSourceName != null
                        if (!isPageImg && userAtt >= MAX_ATT) {
                            Toast.makeText(host, host.getString(io.github.aixtin.nyral.R.string.toast_att_max_drop, MAX_ATT), Toast.LENGTH_SHORT).show()
                            break
                        }
                        host.pendingAttachments.add(att)
                        userAtt += 1
                        addAttachPreview(att)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Nyral", "读取附件失败", e)
                toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, e.message)
            }
        }
    }

    /** 附件预览条加一项: 图片显缩略图, 其他显格式角标; 右上角 × 删除该项 */
    private fun addAttachPreview(att: LocalEngine.Attachment) {
        host.attachPreviewWrap.visibility = View.VISIBLE
        val cell = FrameLayout(host)
        val thumb: View = if (att.mime.startsWith("image/")) {
            ImageView(host).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(dp(8), Ui.INPUT_BG)
                try {
                    val arr = Base64.decode(att.base64, Base64.NO_WRAP)
                    val raw = BitmapFactory.decodeByteArray(arr, 0, arr.size)
                    if (raw != null) {
                        val s = minOf(raw.width, raw.height)
                        val crop = Bitmap.createBitmap(raw, (raw.width - s) / 2, (raw.height - s) / 2, s, s)
                        val thumbBmp = Bitmap.createScaledBitmap(crop, dp(48), dp(48), true)
                        if (thumbBmp != crop) crop.recycle()
                        raw.recycle()
                        setImageBitmap(thumbBmp)
                    }
                } catch (e: Exception) {
                    setImageBitmap(null)
                }
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            }
        } else {
            TextView(host).apply {
                text = badgeOf(att.mime, att.name)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(dp(8), Color.parseColor("#8A8F9C"))
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            }
        }
        val del = Button(host).apply {
            text = "×"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            background = rounded(dp(9), Ui.DANGER)
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END)
            setOnClickListener {
                host.attachPreviewRow.removeView(cell)
                host.pendingAttachments.remove(att)
                if (host.pendingAttachments.isEmpty()) host.attachPreviewWrap.visibility = View.GONE
            }
        }
        cell.addView(thumb)
        cell.addView(del)
        host.attachPreviewRow.addView(cell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
    }

    /** 无文本层 PDF(扫描件): 用系统 PdfRenderer 渲染前几页为 JPEG, 走 image_url 让多模态模型看图。
     *  返回空列表表示渲染失败。 */
    private fun pdfToImageAttachments(uri: Uri, name: String): List<LocalEngine.Attachment> {
        val list = mutableListOf<LocalEngine.Attachment>()
        val pfd: ParcelFileDescriptor = try {
            host.contentResolver.openFileDescriptor(uri, "r") ?: return list
        } catch (e: Exception) { return list }
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            val maxPages = minOf(renderer.pageCount, 10)
            for (i in 0 until maxPages) {
                val page = renderer.openPage(i)
                try {
                    val w = page.width
                    val h = page.height
                    val scale = if (maxOf(w, h) > MAX_IMAGE_SIDE) MAX_IMAGE_SIDE.toFloat() / maxOf(w, h) else 1f
                    val bmp = Bitmap.createBitmap(
                        (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    if (out.size() > 3 * 1024 * 1024) {
                        out.reset()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 68, out)
                    }
                    val bytes = out.toByteArray()
                    bmp.recycle()
                    list.add(LocalEngine.Attachment("image/jpeg",
                        Base64.encodeToString(bytes, Base64.NO_WRAP), "${name.removeSuffix(".pdf")}_p${i + 1}.jpg"))
                } finally {
                    try { page.close() } catch (e: Exception) { /* 单页失败跳过 */ }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Nyral", "PDF 渲染失败", e)
        } finally {
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
        return list
    }
}
