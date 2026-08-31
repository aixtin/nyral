package io.github.aixtin.droidagent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.TextPaint
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File

/** 纯工具函数集: 从 MainActivity 拆出, 无 Activity/this 依赖 */

/** 时间戳格式化 */
fun fmtTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

/** 时长格式化: ms -> "mm:ss"(微信语音样式) */
fun fmtDuration(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "%d:%02d".format(m, s) else "%d″".format(s)
}

/** 音频文件时长(ms), 失败返回 0 */
fun audioDurationMs(file: File): Long {
    return try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        ms
    } catch (e: Exception) { 0L }
}

/** PCM16 单声道 → WAV(RIFF little-endian) 封装
 *  注意: 不能用 writeInt(Integer.reverseBytes(x)) 组合, 写 16 位时 reverseBytes 结果被截断成 0,
 *  导致 format/blockAlign 等字段损坏, 服务端解码崩溃返回 500; 这里手工按 little-endian 逐字节写 */
fun toWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val byteRate = sampleRate * 2
    val baos = ByteArrayOutputStream()
    val d = java.io.DataOutputStream(baos)
    fun i32(v: Int) { d.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())) }
    fun i16(v: Int) { d.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) }
    d.writeBytes("RIFF"); i32(pcm.size + 36); d.writeBytes("WAVE")
    d.writeBytes("fmt "); i32(16)
    i16(1); i16(1)
    i32(sampleRate); i32(byteRate)
    i16(2); i16(16)
    d.writeBytes("data"); i32(pcm.size)
    d.write(pcm)
    d.flush()
    return baos.toByteArray()
}

/** 文件卡片类型角标文案: 按 mime 与文件名扩展名判定 */
fun badgeOf(mime: String, fileName: String): String {
    val n = fileName.lowercase()
    return when {
        mime.startsWith("image/") -> "IMG"
        mime.startsWith("video/") -> "VID"
        mime.startsWith("audio/") -> "AUD"
        n.endsWith(".pdf") -> "PDF"
        n.endsWith(".doc") || n.endsWith(".docx") -> "DOC"
        n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv") -> "XLS"
        n.endsWith(".ppt") || n.endsWith(".pptx") -> "PPT"
        n.endsWith(".md") || n.endsWith(".markdown") -> "MD"
        n.endsWith(".txt") || n.endsWith(".log") -> "TXT"
        n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
        || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".tgz") -> "ZIP"
        else -> "FILE"
    }
}

/** 将位图裁剪为圆角 (图片气泡贴边后四角不露出直角), 输入位图会被回收 */
fun roundedBitmap(src: Bitmap, radius: Int): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawRoundRect(
        RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
        radius.toFloat(), radius.toFloat(), p)
    p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    c.drawBitmap(src, 0f, 0f, p)
    if (src !== out) src.recycle()
    return out
}

/** 圆角背景 Drawable */
fun rounded(radius: Int, color: Int): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }
}

/** 文件卡片文件名省略参考宽度(px): 首条参照文件名(连接卡_记忆库读写.md) 的渲染宽度。
 *  短文件名(小于该宽)直接自适应气泡; 超长文件名在渲染时手动中间省略到该宽度(保留开头与后缀)。 */
fun fileCardNameMaxWidth(scaledDensity: Float): Int {
    val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * scaledDensity
    }
    return fp.measureText("连接卡_记忆库读写.md").toInt()
}

/** 附件图片采样解码为气泡缩略图(最长边 ~200dp), 失败返回 null */
fun decodeAttachmentBitmap(f: File, density: Float): Bitmap? {
    return try {
        val req = dp(density, 200)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var sample = 1
        while (b.outWidth / sample > req || b.outHeight / sample > req) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (e: Exception) { null }
}

/** 视频首帧缩略图(最长边 ~200dp) + 居中半透明播放三角, 作为视频气泡; 取帧/解码失败返回 null */
fun decodeVideoThumbnail(f: File, density: Float): Bitmap? {
    return try {
        val req = dp(density, 200)
        var mmr: MediaMetadataRetriever? = null
        var frame: Bitmap? = null
        try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            mmr?.release()
        }
        val src = frame ?: return null
        // 从帧直接缩放而非采样: 帧是已解码的完整位图
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) { if (src !== frame) src.recycle(); return null }
        val scale = minOf(1f, req.toFloat() / maxOf(w, h))
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, tw, th, true) else src
        if (scaled !== src) src.recycle()
        // 叠加播放三角: 半透明黑圆底 + 白色右三角
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        if (scaled !== out) scaled.recycle()
        val cx = out.width / 2f
        val cy = out.height / 2f
        val r = minOf(out.width, out.height) * 0.18f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        c.drawCircle(cx, cy, r, bg)
        val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val s = r * 0.55f
        val path = Path().apply {
            moveTo(cx - s * 0.4f, cy - s)
            lineTo(cx - s * 0.4f, cy + s)
            lineTo(cx + s * 0.9f, cy)
            close()
        }
        c.drawPath(path, tri)
        out
    } catch (e: Exception) { null }
}

/** dp -> px (UiKit 内部图标/解码函数用), 避免依赖 Activity.dp() */
private fun dp(density: Float, v: Int): Int = (v * density).toInt()

/** 模型按钮背景: 浅蓝圆角底 + 居中双向箭头(⇄), 图标随 background 绘制天然居中 */
fun modelIconBg(density: Float): Drawable = object : Drawable() {
    private fun dp(v: Int) = (v * density).toInt()
    private val bg = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor("#E8F3FE"))
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B93F6")
        style = Paint.Style.STROKE
        strokeWidth = density * 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        bg.setBounds(bounds)
        bg.draw(canvas)
        // 图标 18dp, 相对按钮 bounds 居中
        val d = density
        val ic = dp(18).toFloat()
        val left = (bounds.width() - ic) / 2f
        val top = (bounds.height() - ic) / 2f
        canvas.save()
        canvas.translate(left, top)
        // 上: 右向箭头
        val y1 = ic * 0.30f
        canvas.drawLine(d * 2f, y1, ic - d * 5f, y1, p)
        canvas.drawPath(Path().apply {
            moveTo(ic - d * 2f, y1 - d * 3f)
            lineTo(ic - d * 5f, y1)
            lineTo(ic - d * 2f, y1 + d * 3f)
        }, p)
        // 下: 左向箭头
        val y2 = ic * 0.70f
        canvas.drawLine(d * 5f, y2, ic - d * 2f, y2, p)
        canvas.drawPath(Path().apply {
            moveTo(d * 2f, y2 - d * 3f)
            lineTo(d * 5f, y2)
            lineTo(d * 2f, y2 + d * 3f)
        }, p)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 附件按钮背景: 浅蓝圆角底 + 居中加号, 与模型按钮同风格 */
fun attachIconBg(density: Float): Drawable = object : Drawable() {
    private fun dp(v: Int) = (v * density).toInt()
    private val bg = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor("#E8F3FE"))
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B93F6")
        style = Paint.Style.STROKE
        strokeWidth = density * 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        bg.setBounds(bounds)
        bg.draw(canvas)
        val d = density
        val ic = dp(16).toFloat()
        val left = (bounds.width() - ic) / 2f
        val top = (bounds.height() - ic) / 2f
        canvas.save()
        canvas.translate(left, top)
        val cx = ic / 2f
        val half = ic * 0.36f
        canvas.drawLine(cx - half, cx, cx + half, cx, p)
        canvas.drawLine(cx, cx - half, cx, cx + half, p)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 极简放大镜图标: 细线圆环 + 手柄斜线, 无背景, 仅描边灰色, 用于搜索框折叠态 */
fun searchIconBg(density: Float): Drawable = object : Drawable() {
    private fun dp(v: Int) = (v * density).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        style = Paint.Style.STROKE
        strokeWidth = density * 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        val d = density
        val ic = dp(17).toFloat()
        val left = (bounds.width() - ic) / 2f
        val top = (bounds.height() - ic) / 2f
        canvas.save()
        canvas.translate(left, top)
        val cx = ic * 0.40f
        val cy = ic * 0.40f
        val r = ic * 0.30f
        // 圆环
        canvas.drawCircle(cx, cy, r, p)
        // 手柄: 圆环右下角向外延伸
        canvas.drawLine(
            cx + r * 0.72f, cy + r * 0.72f,
            ic * 0.90f, ic * 0.90f, p
        )
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 底栏图标按钮背景: 浅蓝圆角底 + 居中图标; recording=true 红底白麦; voiceMode=true 画键盘(语音模式) */
fun micIconBg(recording: Boolean, voiceMode: Boolean = false, density: Float): Drawable = object : Drawable() {
    private fun dp(v: Int) = (v * density).toInt()
    private val bg = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (recording) Color.parseColor("#F0523D") else Color.parseColor("#E8F3FE"))
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (recording) Color.WHITE else Color.parseColor("#0B93F6")
        style = Paint.Style.STROKE
        strokeWidth = density * 1.7f
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        bg.setBounds(bounds)
        bg.draw(canvas)
        val d = density
        val ic = dp(17).toFloat()
        val left = (bounds.width() - ic) / 2f
        val top = (bounds.height() - ic) / 2f
        canvas.save()
        canvas.translate(left, top)
        val cx = ic / 2f
        if (voiceMode) {
            // 键盘图标: 圆角外框 + 双排按键横线 + 底部空格
            val kp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = p.color
                style = Paint.Style.STROKE
                strokeWidth = d * 1.6f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawRoundRect(RectF(ic * 0.10f, ic * 0.16f, ic * 0.90f, ic * 0.82f),
                ic * 0.10f, ic * 0.10f, kp)
            canvas.drawLine(ic * 0.20f, ic * 0.36f, ic * 0.80f, ic * 0.36f, kp)
            canvas.drawLine(ic * 0.20f, ic * 0.54f, ic * 0.80f, ic * 0.54f, kp)
            canvas.drawLine(ic * 0.20f, ic * 0.70f, ic * 0.38f, ic * 0.70f, kp)
            canvas.drawLine(ic * 0.52f, ic * 0.70f, ic * 0.72f, ic * 0.70f, kp)
            canvas.restore()
            return
        }
        val bodyW = ic * 0.36f
        val bodyTop = ic * 0.18f
        val bodyBottom = ic * 0.62f
        val bodyR = bodyW / 2f
        // 话筒圆角矩形体
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyBottom),
            bodyR, bodyR, p)
        // 话筒支架(竖线)
        canvas.drawLine(cx, bodyBottom, cx, ic * 0.78f, p)
        // 底部弧线
        val arcL = cx - ic * 0.26f
        val arcR = cx + ic * 0.26f
        val arcTop = ic * 0.62f
        val arcBot = ic * 0.82f
        canvas.drawArc(RectF(arcL, arcTop, arcR, arcBot), 180f, 180f, false, p)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 打开持久化附件: 通过本地 FileProvider(content://) 让系统查看器/分享 (Office 等 App 内无法预览的类型) */
fun openAttachmentExternal(context: Context, fileName: String) {
    try {
        val f = AttachmentStore.fileOf(context, fileName)
        if (f == null) {
            Toast.makeText(context, "附件文件已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = AttachmentStore.mimeOf(fileName)
        val uri = Uri.parse("content://${context.packageName}.files/${Uri.encode(f.name)}")
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(view)
        } catch (e: Exception) {
            // 无对应查看器: 退化为分享
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(share, "分享附件"))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开附件: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** Token 数量格式化: 亿/万/k 分级 */
fun formatTokens(v: Long): String =
    if (v >= 100_000_000L) String.format("%.1f", v / 100_000_000.0) + " 亿"
    else if (v >= 10000) String.format("%.1f", v / 10000.0) + " 万"
    else if (v >= 1000) String.format("%.1f", v / 1000.0) + "k"
    else v.toString()

/** 关键词高亮: 命中片段裁剪到关键词附近(前后各 ~60 字符), Spannable 黄色底标出全部命中 */
fun highlightKeyword(text: String, kw: String): SpannableString {
    val flat = text.replace("\n", " ").trim()
    val idx = flat.indexOf(kw, ignoreCase = true)
    if (idx < 0) return SpannableString(flat)
    val start = (idx - 60).coerceAtLeast(0)
    val end = (idx + kw.length + 60).coerceAtMost(flat.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    val body = prefix + flat.substring(start, end) + suffix
    val ss = SpannableString(body)
    val base = prefix.length + (idx - start)
    var from = base
    while (from >= base && from + kw.length <= body.length) {
        val find = body.indexOf(kw, from, ignoreCase = true)
        if (find < 0) break
        ss.setSpan(BackgroundColorSpan(Color.parseColor("#FFE9A8")),
            find, find + kw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        from = find + kw.length
    }
    return ss
}
