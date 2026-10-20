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
 * 附件发送链路（从 MainActivity 抽离，持有 host 访问其内部成员）。
 * 分流原则: 不因类型/大小拒绝用户(仅 0 字节空文件拒绝);
 * 模型能直接识别的媒体(图片/小视频/小音频/小文本)直发,
 * 其余(大视频/大音频/文档/压缩包/未知格式/伪装文件)流式落私有附件库(att://)并发索引卡,
 * 由 AI 按需 attach_read / video_frame / file_export 处理。
 */
internal class AttachmentSender(private val host: MainActivity) {

    private val MAX_IMAGE_SIDE = 2048
    private val MAX_VIDEO_BYTES = 37 * 1024 * 1024 // MiMo 视频 base64 ≤50MB(原始约 ≤37MB); 用于 GIF 转 MP4 产物校验
    private val SMALL_VIDEO_BYTES = 10 * 1024 * 1024 // 阈值分流: 小视频≤10MB 直传(阶段E实测校准)
    private val SMALL_VIDEO_SEC = 30 * 1000L          // 阈值分流: 小视频≤30秒 直传(阶段E实测校准)
    private val MAX_AUDIO_SEC = 10 * 60 * 1000L // 音频直发时长阈值: >10min 不再直发, 改落库交 AI
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
        // 统一落库(附件+meta 同生共死)发索引卡: 不再直发视频字节, 由 AI 按需 video_frame 抽帧 / file_export
        val durMs = videoDurationMs(uri)
        // 先取大小(不整文件进内存, 修复大视频 OOM 闪退): openAssetFileDescriptor 拿 length 即可
        val size = try {
            host.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) { -1L }
        val meta = videoMetaJson(uri, name, size, mime, durMs)
        val key = try {
            host.contentResolver.openInputStream(uri)?.use { ins ->
                AttachmentStore.saveStream(host, name, mime, ins, meta?.toString())
            } ?: run {
                toast(io.github.aixtin.nyral.R.string.toast_video_store_fail, host.getString(failHintRes))
                return null
            }
        } catch (e: Exception) {
            toast(io.github.aixtin.nyral.R.string.toast_video_store_fail, host.getString(failHintRes), e.message)
            return null
        }
        toast(io.github.aixtin.nyral.R.string.toast_video_stored_lib, host.getString(failHintRes))
        return LocalEngine.Attachment(mime, "", key, stored = true)
    }

    /** 图片(真实类型)统一落库: HEIC 实况图关联 motion 视频则转视频落库; GIF 动画转 MP4 落库; 其余压缩 JPEG 落库 */
    private fun imageAttachments(uri: Uri, name: String, real: String): List<LocalEngine.Attachment> {
        // 实况图(LIVE photo): HEIC 查 MediaStore 关联 motion 视频, 命中即以视频落库(保留动态);
        // 未命中回退静态压缩; 压缩失败回退静态 JPEG, 绝不阻断发送
        if (real == FormatSniffer.IMAGE_HEIC) {
            val mv = MediaFileUtils.motionVideoUriOf(host.contentResolver, uri)
            if (mv != null) {
                val vAtt = buildVideoAttachment(mv, "video/mp4",
                    name.replace(Regex("\\.heic$", RegexOption.IGNORE_CASE), ".mp4"),
                    io.github.aixtin.nyral.R.string.att_label_live_video)
                if (vAtt != null) return listOf(vAtt)
            }
            val bytes = MediaFileUtils.compressImage(host.contentResolver, uri, MAX_IMAGE_SIDE)
            val meta = JSONObject().put("name", name).put("mime", "image/jpeg")
                .put("size", bytes.size.toLong()).put("createdAt", System.currentTimeMillis())
            val key = AttachmentStore.save(host, name, "image/jpeg", bytes, meta.toString())
            return listOf(LocalEngine.Attachment("image/jpeg", "", key, stored = true))
        }
        if (real == FormatSniffer.IMAGE_GIF) {
            val mp4Att: List<LocalEngine.Attachment>? = try {
                val gifBytes = MediaFileUtils.readAll(host.contentResolver, uri)
                if (gifBytes.size in 6..GIF_ANIM_MAX_BYTES && GifToMp4.isAnimated(gifBytes)) {
                    val f = File(host.cacheDir, "anim_${System.currentTimeMillis()}.mp4")
                    try {
                        val frames = GifToMp4.convert(gifBytes, f)
                        if (frames > 0 && f.length() in 1..MAX_VIDEO_BYTES.toLong()) {
                            val mp4Name = name.replace(Regex("\\.gif$", RegexOption.IGNORE_CASE), ".mp4")
                            val meta = videoMetaJson(uri, mp4Name, f.length(), "video/mp4", 0L)
                            val key = try {
                                f.inputStream().use { ins -> AttachmentStore.saveStream(host, mp4Name, "video/mp4", ins, meta?.toString()) }
                            } catch (e: Exception) { null }
                            f.delete()
                            if (key != null) return listOf(LocalEngine.Attachment("video/mp4", "", key, stored = true))
                            null
                        } else { f.delete(); null }
                    } catch (e: Exception) {
                        try { f.delete() } catch (_: Exception) {}
                        null
                    }
                } else null
            } catch (e: Exception) { null }
            if (mp4Att != null) return mp4Att
        }
        // 普通图片/压缩失败回退: 统一压缩 JPEG 落库
        val bytes = MediaFileUtils.compressImage(host.contentResolver, uri, MAX_IMAGE_SIDE)
        // 压缩产物恒为 JPEG, mime 必须同步标 image/jpeg, 修复字节/mime 错配(如 .png 实为 JPEG/HEIC)
        val meta = JSONObject().put("name", name).put("mime", "image/jpeg")
            .put("size", bytes.size.toLong()).put("createdAt", System.currentTimeMillis())
        val key = AttachmentStore.save(host, name, "image/jpeg", bytes, meta.toString())
        toast(io.github.aixtin.nyral.R.string.toast_video_stored_lib, host.getString(io.github.aixtin.nyral.R.string.att_label_image))
        return listOf(LocalEngine.Attachment("image/jpeg", "", key, stored = true))
    }

    /** 音频统一落库(魔数真实 mime 为准): 不再直发音频字节, 由 AI 按需 audio_read(支持音频输入的模型) / file_export */
    private fun audioOrStore(uri: Uri, name: String, realMime: String, claimedMime: String): List<LocalEngine.Attachment> {
        // 音频以魔数真实 mime 为准(扩展名可能说谎, 如 .aac 实为 MP3): 归一到模型认识的格式
        val effMime = if (realMime != FormatSniffer.UNKNOWN) realMime else claimedMime
        val size = try {
            host.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) { -1L }
        val dur = audioDurationMs(uri)
        // 统一流式落库(不整文件进内存) + meta(时长/大小), 消息只放索引卡
        val meta = videoMetaJson(uri, name, size, effMime, dur)
        val key = try {
            host.contentResolver.openInputStream(uri)?.use { ins ->
                AttachmentStore.saveStream(host, name, effMime, ins, meta?.toString())
            } ?: run {
                toast(io.github.aixtin.nyral.R.string.toast_video_store_fail, host.getString(io.github.aixtin.nyral.R.string.att_label_audio))
                return listOf()
            }
        } catch (e: Exception) {
            toast(io.github.aixtin.nyral.R.string.toast_video_store_fail, host.getString(io.github.aixtin.nyral.R.string.att_label_audio), e.message)
            return listOf()
        }
        toast(io.github.aixtin.nyral.R.string.toast_video_stored_lib, host.getString(io.github.aixtin.nyral.R.string.att_label_audio))
        return listOf(LocalEngine.Attachment(effMime, "", key, stored = true))
    }


    private fun fileOrStore(uri: Uri, name: String, claimedMime: String, real: String, isPdf: Boolean): List<LocalEngine.Attachment> {
        val size = try {
            host.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) { -1L }
        // PDF: 一律流式落私有附件库 + 首轮预览前 3 页图(pdfSourceName 显示层隐藏) + 索引卡(含总页数与 pdf_page 指引)
        if (isPdf) {
            val meta = JSONObject()
                .put("name", name).put("mime", claimedMime).put("size", size)
                .put("realType", real).put("createdAt", System.currentTimeMillis())
            val key = try {
                host.contentResolver.openInputStream(uri)?.use { ins ->
                    AttachmentStore.saveStream(host, name, claimedMime, ins, meta.toString())
                } ?: run {
                    toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, "open failed")
                    return listOf()
                }
            } catch (e: Exception) {
                toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, e.message)
                return listOf()
            }
            toast(io.github.aixtin.nyral.R.string.toast_video_stored_lib, host.getString(io.github.aixtin.nyral.R.string.att_label_file))
            val atts = mutableListOf<LocalEngine.Attachment>()
            if (ApiConfig.modelHasCap(ApiConfig.providerId(), ApiConfig.model(), ApiConfig.CAP_IMAGE)) {
                val f = AttachmentStore.fileOf(host, key)
                if (f != null) {
                    val (first, total) = renderPdfPageJpeg(f, 0)
                    if (first != null && total > 0) {
                        atts.add(LocalEngine.Attachment("image/jpeg", Base64.encodeToString(first, Base64.NO_WRAP),
                            "${name.removeSuffix(".pdf")}_p1.jpg").copy(pdfSourceName = name))
                        for (i in 1 until minOf(total, 3)) {
                            val (bytes, _) = renderPdfPageJpeg(f, i)
                            if (bytes == null) break
                            atts.add(LocalEngine.Attachment("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP),
                                "${name.removeSuffix(".pdf")}_p${i + 1}.jpg").copy(pdfSourceName = name))
                        }
                        atts.add(LocalEngine.Attachment("application/pdf", "", key, stored = true,
                            text = "（PDF 已落私有附件库，共 $total 页；需要看任意页请调 pdf_page(name=\"att://$key\", page=页码, mode=\"image\"或\"text\")，按自身识图能力选 mode）"))
                        return atts
                    }
                }
            }
            atts.add(LocalEngine.Attachment("application/pdf", "", key, stored = true,
                text = "（PDF 已落私有附件库；需要看任意页请调 pdf_page(name=\"att://$key\", page=页码, mode=\"image\"或\"text\")，按自身识图能力选 mode）"))
            return atts
        }
        // 预算内小文件(非PDF): 尝试提取文本直发(模型可直接读)
        if (size in 1..host.maxFileBytes) {
            val raw = MediaFileUtils.readAll(host.contentResolver, uri)
            val txt = DocTextExtractor.extract(name, raw)
            if (!txt.isNullOrBlank()) {
                return listOf(LocalEngine.Attachment(claimedMime, Base64.encodeToString(raw, Base64.NO_WRAP), name, text = txt))
            }
        }
        // 大文件或无法解析: 流式落库 + meta(真实类型/大小), 由 AI 按需读取/导出处理
        val meta = JSONObject()
            .put("name", name).put("mime", claimedMime).put("size", size)
            .put("realType", real).put("createdAt", System.currentTimeMillis())
        val key = try {
            host.contentResolver.openInputStream(uri)?.use { ins ->
                AttachmentStore.saveStream(host, name, claimedMime, ins, meta.toString())
            } ?: run {
                toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, "open failed")
                return listOf()
            }
        } catch (e: Exception) {
            toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, e.message)
            return listOf()
        }
        toast(io.github.aixtin.nyral.R.string.toast_video_stored_lib, host.getString(io.github.aixtin.nyral.R.string.att_label_file))
        return listOf(LocalEngine.Attachment(claimedMime, "", key, stored = true))
    }

    /** 表情库项直接发送: 读库内文件 bytes 作图片附件直接发出(不经预览条); 调用方需保证输入框/预览条为空 */
    fun sendEmojiLibItemNow(file: File, name: String) {
        host.executor.execute {
            try {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    toast(io.github.aixtin.nyral.R.string.toast_att_empty)
                    return@execute
                }
                // 表情库项含动图(落库前已转无声循环MP4): 按真实类型打 mime, 渲染端表情气泡小图循环播放
                val mime = if (file.name.lowercase().endsWith(".mp4")) "video/mp4" else "image/jpeg"
                val att = LocalEngine.Attachment(
                    mime, Base64.encodeToString(bytes, Base64.NO_WRAP), name, isEmoji = true)
                host.uiScope.launch { host.doSend(listOf(att)) }
            } catch (e: Exception) {
                android.util.Log.e("Nyral", "发送表情失败", e)
                toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, e.message)
            }
        }
    }

    /** 表情库项直接发送: 读库内文件 bytes 作图片附件加入预览条(名字即消息名, AI 可语义索引) */
    fun sendEmojiLibItem(file: File, name: String) {
        host.executor.execute {
            try {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    toast(io.github.aixtin.nyral.R.string.toast_att_empty)
                    return@execute
                }
                // 表情库项含动图(落库前已转无声循环MP4): 按真实类型打 mime, 渲染端表情气泡小图循环播放
                val mime = if (file.name.lowercase().endsWith(".mp4")) "video/mp4" else "image/jpeg"
                val att = LocalEngine.Attachment(
                    mime, Base64.encodeToString(bytes, Base64.NO_WRAP), name, isEmoji = true)
                host.uiScope.launch {
                    val MAX_ATT = 5
                    var userAtt = host.pendingAttachments.count { it.pdfSourceName == null }
                    if (userAtt >= MAX_ATT) {
                        Toast.makeText(host, host.getString(io.github.aixtin.nyral.R.string.toast_att_max_drop, MAX_ATT), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    host.pendingAttachments.add(att)
                    userAtt += 1
                    addAttachPreview(att)
                }
            } catch (e: Exception) {
                android.util.Log.e("Nyral", "发送表情失败", e)
                toast(io.github.aixtin.nyral.R.string.toast_att_read_fail, e.message)
            }
        }
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
                // 魔数嗅探真实格式(解决"扩展名≠真实格式"): 仅读头部, 不动文件本体
                val head = MediaFileUtils.readHead(host.contentResolver, uri, 64)
                val real = FormatSniffer.sniff(head, lowName)
                val realType = real.substringBefore('/') // image/audio/video/text 或 empty/unknown
                // 仅 0 字节空文件拒绝; 其余一律按真实类型分流, 伪装/未知不再拦截(落库交 AI 判断)
                if (real == FormatSniffer.EMPTY) {
                    toast(io.github.aixtin.nyral.R.string.toast_att_empty)
                    return@execute
                }
                val atts: List<LocalEngine.Attachment> = when (realType) {
                    "image" -> imageAttachments(uri, name, real)
                    "video" -> {
                        val vAtt = buildVideoAttachment(uri, real, name, io.github.aixtin.nyral.R.string.att_label_video)
                        if (vAtt == null) return@execute
                        listOf(vAtt)
                    }
                    "audio" -> audioOrStore(uri, name, real, mime)
                    else -> fileOrStore(uri, name, mime, real, isPdf)
                }
                host.uiScope.launch {
                    // 预览条方案: 附件先进输入框上方预览, 补文字后由 onSend 一并发送, 不再直接发出
                    // 总量上限 5: 无论单次还是多次累积, 超出部分拒绝加入预览条(不占发送队列)
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

    /** 附件显示名: 落库附件取 meta 中的原名, 否则原名 */
    /** 附件显示名: 落库附件取 meta 中的原名, 否则原名 */
    private fun displayNameOf(att: LocalEngine.Attachment): String {
        if (!att.stored) return att.name
        return try {
            AttachmentStore.metaOf(host, att.name)?.let { JSONObject(it).optString("name") }
                ?.takeIf { it.isNotBlank() } ?: att.name
        } catch (e: Exception) { att.name }
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
                    val raw = if (att.stored) {
                        AttachmentStore.fileOf(host, att.name)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    } else {
                        val arr = Base64.decode(att.base64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(arr, 0, arr.size)
                    }
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
                text = badgeOf(att.mime, displayNameOf(att))
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(dp(8), Color.parseColor("#8A8F9C"))
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            }
        }
        val del = ImageView(host).apply {
            setImageDrawable(Ui.lucideX(host, Color.WHITE, 10))
            scaleType = ImageView.ScaleType.CENTER
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

    /** PDF 渲染已迁移: 发送时落库 + 首轮预览前 3 页 + pdf_page 工具按需翻页, 不再整段渲染直发 */
}