package io.github.aixtin.nyral

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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.TextPaint
import android.widget.Toast
// Media3: 系统 MMR/extractor 拒绝的转发视频, 用 ExoPlayer(自带纯 Java mp4 解析)离屏 TextureView 渲染取帧生成气泡缩略图
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.ViewGroup
import android.app.Activity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

/** 圆角背景 Drawable：缓存 ConstantState 模板，每次返回独立 mutate 副本；
 *  根治多 View 共享同一实例 setBounds 互踩圆角、动画 setColor 污染同 key 缓存变色。 */
private val sRoundedCache = object : android.util.LruCache<String, android.graphics.drawable.Drawable.ConstantState>(96) {
    override fun sizeOf(key: String, value: android.graphics.drawable.Drawable.ConstantState): Int = 1
}
fun rounded(radius: Int, color: Int): GradientDrawable {
    val k = radius.toString() + "_" + color
    var tpl = sRoundedCache.get(k)
    if (tpl == null) {
        val g = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
        tpl = g.constantState
        sRoundedCache.put(k, tpl)
    }
    return tpl.newDrawable().mutate() as GradientDrawable
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
    // 09-28 滑动丝滑：走 BitmapLoader 统一缩略图缓存——上翻历史重复 bind 同一附件时
    // 不再主线程反复 decodeFile（此前每次滚动重绑都整图采样解码，掉帧尖峰）
    return BitmapLoader.cachedSampled(f.absolutePath, "att_" + f.absolutePath, dp(density, 200))
}

/** 全局活跃 ExoPlayer 解码器硬上限(防 OOM): 内嵌气泡最多4 + 弹窗/抓帧2 = 6(旗舰档)。
 *  名额制此前只堵 videoLoopBubble 创建入口, attach 重建/弹窗预览/抓帧路径均可绕过,
 *  真机 09-19 00:40 崩溃前 10 秒连建 7 个 ExoPlayer 仅 1 个 Release, 堆 256MB 打爆。
 *  所有 ExoPlayer 创建点统一 tryAcquire/release, 超限拒绝新创建, 彻底杜绝堆积。
 *  持有者集合模型(09-19 02:30): 此前 AtomicInteger 裸计数与真实持有者脱钩——真机日志
 *  实锤 02:12:48 名额报满 3/3 但 killOldestBubblePlayer 无可踢实例(sBubblePlayers 空),
 *  计数漂移后所有创建入口被静默拦截, 全部视频黑屏直到重启。改为集合后:
 *  1) 同一持有者重复 release 幂等(集合 remove no-op), 重复 acquire 幂等, 计数不可能漂移;
 *  2) 满员拒绝时打印当前持有者清单(类名@identityHashCode), 泄漏源头一眼可见;
 *  3) 重复归还时打印调用栈, 双还路径当场抓现行 */
object ExoGate {
    private val holders = java.util.Collections.synchronizedSet(HashSet<Any>())
    /** 全局解码器硬上限(含弹窗/抓帧): 启动时按设备内存分档动态定级, 低内存告警时自动降级 */
    @Volatile var MAX = 3
    /** 列表页内嵌气泡并发上限(同屏最多同时播放数): 始终 = MAX 之下给弹窗/抓帧留 1 */
    @Volatile var INLINE_MAX = 2
    @Volatile private var lastFullLog = 0L
    /** 名额让出广播: 弹窗关闭/终结释放后由 MainActivity 挂接"对账补建", 不滚动也能自愈被踢行 */
    @Volatile var sOnGateReleased: (() -> Unit)? = null
    @Volatile private var lastReleaseNotify = 0L

    /** 按设备总内存分档定级(QQ 式按性能放宽并发): 低端 1/2、中端 2/3、高端 4/6 */
    fun initByDevice(ctx: android.content.Context) {
        val mem = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.getMemoryInfo(mem)
        val totalGB = mem.totalMem.toFloat() / (1024f * 1024f * 1024f)
        when {
            totalGB < 4f -> { INLINE_MAX = 1; MAX = 2 }
            totalGB < 8f -> { INLINE_MAX = 2; MAX = 3 }
            else -> { INLINE_MAX = 4; MAX = 6 }   // 旗舰: 内嵌4(同屏一次展示4个视频全动) + 弹窗/抓帧2
        }
        android.util.Log.i("Nyral", "ExoGate按设备定级: 总内存=${"%.1f".format(totalGB)}GB 内嵌上限=$INLINE_MAX 全局上限=$MAX")
    }

    /** 低内存告警降级: 只降不升(避免抖动), 不杀在播, 后续新创建按更严名额执行 */
    fun downgrade(level: Int) {
        val wantInline = if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) INLINE_MAX - 1 else INLINE_MAX
        val wantMax = if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) MAX - 1 else MAX
        if (wantInline >= 1 && wantMax >= 2) {
            INLINE_MAX = wantInline
            MAX = wantMax
            android.util.Log.w("Nyral", "ExoGate低内存降级: 内嵌=$INLINE_MAX 全局=$MAX (level=$level)")
        }
    }

    fun tryAcquire(holder: Any): Boolean {
        val ok = synchronized(holders) { holders.size < MAX && holders.add(holder) }
        if (!ok) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFullLog > 3000L) {   // 限频: 满员时滚动重绑高频触发, 只留取证所需
                lastFullLog = now
                val snapshot = synchronized(holders) { holders.toList() }
                Log.w("Nyral", "ExoGate满${MAX}/${MAX} 拒绝持有者=${describe(holder)} 当前持有者=" +
                        snapshot.joinToString { describe(it) })
            }
        }
        return ok
    }
    fun release(holder: Any) {
        val removed = holders.remove(holder)
        if (!removed)
            Log.w("Nyral", "ExoGate重复归还(幂等忽略): ${describe(holder)}", Throwable("此处释放了未持有的名额——若频繁出现即泄漏/双还路径"))
        else {
            // 名额真实让出时广播(限频 500ms): MainActivity 挂对账补建, 让"弹窗关闭/被踢行"不滚动也自愈
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastReleaseNotify > 500L) {
                lastReleaseNotify = now
                sOnGateReleased?.invoke()
            }
        }
    }
    fun count(): Int = holders.size
    private fun describe(h: Any): String =
        h::class.java.simpleName + "@" + Integer.toHexString(System.identityHashCode(h))
}

/** 视频气泡并发解码上限(全局表): 同屏最多 2 个 ExoPlayer 同时硬解码, 超限暂停最早注册的,
 *  堵住"多视频气泡同时循环播放"导致的堆内存耗尽 OOM 闪退(真机 09-18 23:56 栈 MediaCodec.getBuffer 印证)。
 *  从 MainActivity 移出为公开顶层变量: 弹窗预览在名额满时可直接踢最老内嵌腾解码器。 */
val sBubblePlayers = java.util.Collections.synchronizedList(ArrayList<ExoPlayer>())

/** 表情气泡专用活跃播放器集合(独立于 sBubblePlayers):
 *  表情动图与普通视频互不干扰——视频对账(reconcileVideoBubbles)只遍历 sBubblePlayers,
 *  表情若混入会被当"离屏视频"统一 killSelf 导致全部黑屏(09-19 真机: 第3个表情起全黑);
 *  表情生命周期由其自身 attach/detach 看门狗管理, ExoGate 全局名额仍共用兜底防 OOM */
val sEmojiPlayers = java.util.Collections.synchronizedList(ArrayList<ExoPlayer>())

/** 表情气泡播放器 -> 自我终结函数(killSelf)注册表: 与 sBubbleKillHooks 同机制, 供表情对账
 *  (reconcileEmojiBubbles)按配额踢超限实例, 统一收口释放+名额+视图 */
val sEmojiKillHooks = java.util.Collections.synchronizedMap(HashMap<ExoPlayer, () -> Unit>())

/** 内嵌气泡播放器 -> 自我终结函数(killSelf)注册表: 弹窗优先级"请让位"时走统一收口,
 *  实例释放+名额归还+视图降级三件事由播放器自己的监听器完成——此前外部直接 victim.release()
 *  + ExoGate.release(), 被踢者自己的 detach 监听之后又 release 一次 → 名额双重归还计数漂移 */
val sBubbleKillHooks = java.util.Collections.synchronizedMap(HashMap<ExoPlayer, () -> Unit>())

/** 请最老的内嵌气泡播放器让位(弹窗优先): 经其自身钩子统一终结, 返回是否成功让出 */
fun killOldestBubblePlayer(): Boolean {
    if (sBubblePlayers.isEmpty()) return false
    val victim = sBubblePlayers[0]
    val hook = sBubbleKillHooks.remove(victim) ?: return false
    try { hook() } catch (_: Throwable) {}
    return true
}

// ---- 视频异步取帧缓存与回调(系统栈取帧失败时的 ExoPlayer 兜底) ----
private val sThumbCache = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
    // 按位图真实字节计内存上限(默认 32MB), 超限自动淘汰最久未用, 防止会话历史视频累积导致缓存无界增长
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
/** QQ 式滚动暂停标志: 列表滚动中 true(不创建内嵌播放器/不入队取帧), 由 MainActivity 滚动监听维护 */
@Volatile var sScrolling = false
/** 批量加载窗口标志: 切会话/重启/全量重建提交后、首帧布局完成前为 true,
 *  videoLoopBubble 入口在此窗口内一律返回 null 走缩略图, 避免"整屏大量视频行同一帧
 *  同步 inflate+prepare 硬解码"导致首屏闪烁卡顿; 布局稳定后由重建路径主动释放并对账补建 */
@Volatile var sBatchLoad = false
private val sThumbInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
private val sThumbCallbacks = java.util.Collections.synchronizedMap(HashMap<String, MutableList<() -> Unit>>())
// 取帧串行管线: 全局同一时刻仅跑一个 ExoPlayer 取帧任务, 消除重启/切会话时多条全屏 SurfaceView 叠加竞争
/** 取帧重试到顶的失败标记: key -> 失败时刻(uptimeMillis); 冷却期内拦截自动重试, 冷却后下一次渲染自动再试(取回即自愈) */
private val sThumbFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()
private const val THUMB_FAIL_COOLDOWN_MS = 20_000L
private val sThumbQueue = java.util.ArrayDeque<ThumbJob>()
private val sThumbInQueue = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
@kotlin.jvm.Volatile private var sThumbRunning = false
private class ThumbJob(val ctx: Context, val f: File, val key: String, val round: Int)

// ---- 缩略图磁盘缓存(持久化): 取帧成功落盘 JPEG, 重启/切会话后读盘免重新解码 ----
private fun thumbDiskDir(ctx: Context): File =
    File(ctx.cacheDir, "vthumb").apply { if (!exists()) mkdirs() }

private fun thumbDiskName(key: String): String {
    // 附件文件名直接作盘名(File.getName 无路径分隔符); 统一去 "|raw" 语义后缀
    val base = if (key.endsWith("|raw")) key.dropLast(4) else key
    return base + ".jpg"
}

/** 后台线程调用: 读磁盘缩略图(纯帧); 无命中返回 null */
private fun readThumbDisk(ctx: Context, key: String): Bitmap? {
    return try {
        val f = File(thumbDiskDir(ctx), thumbDiskName(key))
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (t: Throwable) { null }
}

/** 后台线程调用: 纯帧 JPEG 落盘(先写 .tmp 再 rename, 防中断半文件); 超量清理最旧一半 */
private fun writeThumbDisk(ctx: Context, key: String, bmp: Bitmap) {
    try {
        val dir = thumbDiskDir(ctx)
        val f = File(dir, thumbDiskName(key))
        val tmp = File(dir, f.name + ".tmp")
        val os = java.io.FileOutputStream(tmp)
        try { bmp.compress(Bitmap.CompressFormat.JPEG, 85, os) } finally { os.close() }
        if (tmp.length() > 0 && tmp.renameTo(f)) {
            // 粗放防膨胀: 缓存文件超 600 个删最旧一半(缓存可再生, 不必精确 LRU)
            val all = dir.listFiles()?.filter { it.name.endsWith(".jpg") } ?: emptyList()
            if (all.size > 600) {
                all.sortedBy { it.lastModified() }.take(all.size / 2).forEach { try { it.delete() } catch (_: Throwable) {} }
            }
        }
    } catch (t: Throwable) { }
}

/** 视频缩略图是否仍在异步取帧中(气泡渲染时可登记刷新回调) */
fun isThumbPending(fname: String): Boolean = sThumbInFlight.contains(fname)

/** 注册取帧完成后的刷新回调(key=附件文件名), 取到帧后在主线程执行 cb */
fun registerThumbRefresh(fname: String, cb: () -> Unit) {
    sThumbCallbacks.getOrPut(fname) { mutableListOf() }.add(cb)
}

/**
 * 纯 Java MediaExtractor 只读视频轨道真实宽高(不解码/不抓帧/无 SurfaceView),
 * 用于"临时取不到缩略图"时给内嵌循环播放气泡一个贴近真实比例的尺寸框, 避免死板 4:3。
 * 失败返回 null 由调用方 16:9 兜底。
 */
fun videoDimensionsFast(f: File): Pair<Int, Int>? {
    return try {
        var w = 0; var h = 0
        val ex = MediaExtractor()
        try {
            val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try { ex.setDataSource(pfd.fileDescriptor, 0, -1) } finally { pfd.close() }
        } catch (t: Throwable) {
            ex.setDataSource(f.absolutePath)
        }
        val n = ex.trackCount
        for (i in 0 until n) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                break
            }
        }
        ex.release()
        if (w > 0 && h > 0) Pair(w, h) else null
    } catch (t: Throwable) { null }
}

/**
 * 视频首帧缩略图(最长边 ~200dp) + 居中半透明播放三角, 作为视频气泡; 取帧/解码失败返回 null。
 * @param allowGrab false 时禁用底部 ExoPlayer 全屏抓帧兜底: 系统快路径失败直接返回 null,
 *        由调用方用固定宽高兜底 —— 仅用于"该视频本就可直接播放、只是取不到缩略图"的场景
 *        (内嵌循环播放气泡已用真实播放器渲染, 抓帧会真机全屏闪放视频 + 抢占合成层/解码器,
 *        反而把气泡顶成黑块消失), 避免无谓的侵入式抓帧。
 */
fun decodeVideoThumbnail(f: File, density: Float, ctx: Context, allowGrab: Boolean = true, withPlayButton: Boolean = true): Bitmap? {
    return try {
        val cacheKey = if (withPlayButton) f.name else f.name + "|raw"
        sThumbCache.get(cacheKey)?.let { return it }
        val req = dp(density, 200)
        // 快路径: 系统 MediaMetadataRetriever(普通视频毫秒级), 抽出独立函数供后台线程复用
        var frame: Bitmap? = mmrFrame(f)
        // 兜底: 系统栈被拒(平台 extractor 拒绝的转发视频) → ExoPlayer 异步取帧(自带纯 Java mp4 解析):
        // 非阻塞, 先返回 null 渲染文件卡片, 取到帧后回调刷新当前气泡为缩略图
        if (frame == null) {
            if (!allowGrab) return null   // 该调用方不需要全屏抓帧: 直接放弃, 由调用方 16:9 兜底
            // 滚动中不入队取帧(避免滑动过程批量占 ExoGate 抓帧, 松手刷新后自动补取)
            if (sScrolling) return null
            val key = f.name
            try {
                Log.i("UiKit", "DT A 进入ExoPlayer兜底 inflight=" + sThumbInFlight.contains(key) + " cache=" + (sThumbCache.get(key) != null) + " failed=" + sThumbFailed.containsKey(key))
                if (sThumbInFlight.contains(key)) return null
                // 失败到顶后进入冷却期: 冷却期内稳定文件卡片不重复自动取帧(避免无效风暴);
                // 冷却结束后的下一次渲染(滚动/重进/回前台)自动重新取帧, 取回即恢复缩略图自愈
                val failAt = sThumbFailed[key]
                if (failAt != null && android.os.SystemClock.uptimeMillis() - failAt < THUMB_FAIL_COOLDOWN_MS) return null
                sThumbFailed.remove(key)
                sThumbInFlight.add(key)
                enqueueThumb(ThumbJob(ctx, f, key, 0))
                Log.i("UiKit", "DT B 视频取帧入队返回")
            } catch (t: Throwable) {
                Log.w("UiKit", "DT C 视频取帧入队异常", t)
                sThumbInFlight.remove(key)
            }
        }
        val src = frame ?: return null
        // 从帧直接缩放而非采样: 帧是已解码的完整位图(抽出独立函数供后台线程复用)
        val scaled = scaleThumbFrame(src, req) ?: return null
        if (!withPlayButton) {
            // 纯帧输出(调用方自行统一画固定大小播放三角): 避免与 UiKit 自带三角叠加成"大套小"
            sThumbCache.put(cacheKey, scaled)
            return scaled
        }
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
        // 缓存最终输出(缩放+播放三角后的活位图)而非完整帧: 完整帧随后被 recycle,
        // 缓存命中返回已回收 Bitmap 会让恢复渲染拿到完整分辨率(触发视频气泡意外撑满屏)
        sThumbCache.put(cacheKey, out)
        out
    } catch (e: Exception) { null }
}

/** MMR 快路径取首帧(独立函数, 主线程同步/后台异步共用); 失败返回 null */
private fun mmrFrame(f: File): Bitmap? {
    var mmr: MediaMetadataRetriever? = null
    return try {
        mmr = MediaMetadataRetriever()
        // 优先 FileDescriptor 直连文件层(规避裸路径 content:// 误解析), 失败回退绝对路径
        try {
            val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try { mmr.setDataSource(pfd.fileDescriptor) } finally { pfd.close() }
        } catch (t: Throwable) {
            mmr.setDataSource(f.absolutePath)
        }
        retrieveFrameRetry(mmr, f)
    } catch (t: Throwable) { null } finally { try { mmr?.release() } catch (_: Throwable) {} }
}

/** 已解码帧缩放到最长边 req(独立函数供同步/后台共用); 无效尺寸返回 null */
private fun scaleThumbFrame(src: Bitmap, req: Int): Bitmap? {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return null
    val scale = minOf(1f, req.toFloat() / maxOf(w, h))
    return if (scale < 1f) {
        val scaled = Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== src) src.recycle()
        scaled
    } else src
}

// ---- 视频缩略图后台解码(治主线程 MMR 取帧卡顿): 绑定路径只查缓存, 未命中后台串行解码完回调刷新 ----
private val sThumbBgExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "nyral-thumb-bg").apply { priority = Thread.MIN_PRIORITY }
}

/** 只查缓存的缩略图读取(不解码不阻塞): 供视频气泡降级展示用 */
fun peekVideoThumb(f: File): Bitmap? =
    sThumbCache.get(f.name + "|raw") ?: sThumbCache.get(f.name)

/** 主线程触发已登记的取帧刷新回调 */
private fun fireThumbCallbacks(key: String) {
    val cbs = synchronized(sThumbCallbacks) { sThumbCallbacks.remove(key) }
    if (cbs != null) android.os.Handler(android.os.Looper.getMainLooper()).post {
        for (cb in cbs) { try { cb() } catch (_: Throwable) {} }
    }
}

/**
 * 视频缩略图后台版(绑定路径专用): 缓存命中直接返回; 未命中入后台单线程 MMR 解码,
 * 就绪后写缓存并回调刷新气泡(先渲染文件卡片占位, 不再主线程同步取帧造成滚动掉帧)。
 * MMR 失败(平台 extractor 拒绝的转发视频)自动转入既有 ExoPlayer 抓帧兜底队列(串行+冷却)。
 */
fun decodeVideoThumbnailBg(f: File, density: Float, ctx: Context): Bitmap? {
    peekVideoThumb(f)?.let { return it }
    val key = f.name
    if (sThumbInFlight.contains(key)) return null
    val failAt = sThumbFailed[key]
    if (failAt != null && android.os.SystemClock.uptimeMillis() - failAt < THUMB_FAIL_COOLDOWN_MS) return null
    sThumbInFlight.add(key)
    val req = dp(density, 200)
    sThumbBgExec.execute {
        try {
            // 磁盘缓存优先: 上次已取帧落盘(重启/切会话后内存缓存空), 读盘免重新解码, 首屏不再排队闪变
            val disk = readThumbDisk(ctx, f.name)
            if (disk != null) {
                sThumbCache.put(key + "|raw", disk)
                sThumbInFlight.remove(key)
                Log.i("UiKit", "bg thumb 磁盘命中: $key")
                fireThumbCallbacks(key)
                return@execute
            }
            val frame = mmrFrame(f)
            val scaled = if (frame != null) scaleThumbFrame(frame, req) else null
            if (scaled != null) {
                sThumbCache.put(key + "|raw", scaled)
                writeThumbDisk(ctx, f.name, scaled)
                sThumbInFlight.remove(key)
                Log.i("UiKit", "bg thumb 取帧成功: $key ${scaled.width}x${scaled.height}")
                fireThumbCallbacks(key)
                return@execute
            }
            // MMR 失败: 转入既有 Exo 抓帧兜底(须回主线程入队)
            sThumbInFlight.remove(key)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (!sThumbInFlight.contains(key)) {
                        sThumbInFlight.add(key)
                        enqueueThumb(ThumbJob(ctx, f, key, 0))
                        Log.i("UiKit", "bg thumb MMR失败转Exo抓帧: $key")
                    }
                } catch (t: Throwable) {
                    sThumbInFlight.remove(key)
                }
            }
        } catch (t: Throwable) {
            sThumbInFlight.remove(key)
            Log.w("UiKit", "bg thumb 异常", t)
        }
    }
    return null
}

/** 系统栈取帧失败时, 用 ExoPlayer(自带纯 Java mp4 解析) 后台异步解码首帧:
 *  ExoPlayer 用 SurfaceView 渲染到屏幕中央(该路径在全屏预览已验证能出画面), 播放到首帧后
 *  用 PixelCopy(API26+, 官方 SurfaceView 抓帧) 直接抓当前显示帧 → Bitmap 即收手(极短一闪)。
 *  (TextureView.getBitmap 不动/Oppo 合成不出帧; ImageReader 直连解码器触发 native 崩溃, 均已排除)
 *
 *  加固(重启/切会话老问题): 全局限串行取帧 + 黑帧校验 + 有限轮次延迟重试, 失败到顶稳定文件卡片 */
private fun enqueueThumb(job: ThumbJob) {
    val start = synchronized(sThumbQueue) {
        if (sThumbInQueue.contains(job.key)) false
        else { sThumbInQueue.add(job.key); sThumbQueue.addLast(job); true }
    }
    if (start) pumpThumb()
}

private fun pumpThumb() {
    synchronized(sThumbQueue) {
        if (sThumbRunning) return
        while (sThumbQueue.isNotEmpty()) {
            val job = sThumbQueue.pollFirst() ?: break
            sThumbInQueue.remove(job.key)
            val act = job.ctx as? android.app.Activity
            if (act == null || act.isFinishing || act.isDestroyed) {
                // 发起方已销毁(如切会话/退出): 彻底放弃该任务
                sThumbInFlight.remove(job.key)
                continue
            }
            sThumbRunning = true
            startThumbJob(job)
            return
        }
    }
}

private fun thumbJobDone(job: ThumbJob) {
    synchronized(sThumbQueue) { sThumbRunning = false }
    pumpThumb()
}

/** 取帧成功: 写缓存 + 触发气泡刷新 */
private fun thumbJobSuccess(job: ThumbJob, bmp: Bitmap) {
    // 双键写入: 绑定路径按 "|raw" 键查(纯帧), 此前只写 f.name 键导致抓帧成功后
    // renderUserContent 永远查不到 → 转发视频抓帧白干仍显示文件卡片
    sThumbCache.put(job.key, bmp)
    sThumbCache.put(job.key + "|raw", bmp)
    writeThumbDisk(job.ctx, job.f.name, bmp)   // 转发视频兜底帧也落盘, 重启后直接读盘
    sThumbFailed.remove(job.key)   // 成功取帧: 清失败标记, 后续直接命中缓存
    sThumbInFlight.remove(job.key)
    Log.i("UiKit", "exo thumb PixelCopy 取帧成功: " + job.key)
    val cbs = synchronized(sThumbCallbacks) { sThumbCallbacks.remove(job.key) }
    if (cbs != null) for (cb in cbs) { try { cb() } catch (_: Throwable) {} }
    thumbJobDone(job)
}

/** 取帧失败: 有限轮次延迟重试(覆盖重启首屏窗口未稳), 到顶后永久放弃并刷稳定文件卡片 */
private fun thumbJobFail(job: ThumbJob) {
    if (job.round < 2) {
        sThumbInFlight.add(job.key)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ enqueueThumb(ThumbJob(job.ctx, job.f, job.key, job.round + 1)) }, 2500L)
        Log.w("UiKit", "exo thumb 取帧失败 调度重试${job.round + 1}: " + job.key)
        thumbJobDone(job)
    } else {
        sThumbFailed[job.key] = android.os.SystemClock.uptimeMillis()   // 记录到顶失败时刻, 进入冷却期(冷却后自动再试自愈)
        sThumbInFlight.remove(job.key)
        Log.w("UiKit", "exo thumb 取帧重试到顶放弃: " + job.key)
        // 通知已登记的气泡重渲染为稳定文件卡片, 避免一直挂在异步等待状态
        val cbs = synchronized(sThumbCallbacks) { sThumbCallbacks.remove(job.key) }
        if (cbs != null) for (cb in cbs) { try { cb() } catch (_: Throwable) {} }
        thumbJobDone(job)
    }
}

/** 无效帧(纯黑)判定: 采样 8x8 区域, >95% 像素亮度总和<24 视为黑帧 --> 丢弃重试, 防脏帧入缓存 */
private fun isNearlyBlack(bmp: Bitmap): Boolean {
    var black = 0
    val w = bmp.width; val h = bmp.height
    for (i in 0 until 8) for (j in 0 until 8) {
        val p = try { bmp.getPixel(w * i / 8, h * j / 8) } catch (_: Throwable) { continue }
        if (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) < 24) black++
    }
    return black >= 61
}

/** 串行执行单个取帧 job: 自建全屏 SurfaceView + ExoPlayer 渲染, PixelCopy 抓首帧 */
private fun startThumbJob(job: ThumbJob) {
    if (!ExoGate.tryAcquire(job)) {
        // 全局解码器已满(内嵌气泡/弹窗预览占用): 放弃本次抓帧并进冷却, 避免无效重试堆积解码器
        sThumbFailed[job.key] = android.os.SystemClock.uptimeMillis()
        sThumbInFlight.remove(job.key)
        Log.w("UiKit", "exo thumb 放弃: 解码器名额已满 " + job.key)
        thumbJobDone(job)
        return
    }
    val ctx = job.ctx; val f = job.f; val key = job.key
    val root = (ctx as? android.app.Activity)?.window?.decorView as? android.view.ViewGroup
    if (root == null || android.os.Build.VERSION.SDK_INT < 26) { ExoGate.release(job); thumbJobFail(job); return }
    val done = AtomicBoolean(false)
    val main = android.os.Handler(ctx.mainLooper)
    // 自建全屏 SurfaceView + setVideoSurfaceView: SurfaceHolder 回调给出可靠 surface 就绪信号,
    // PixelCopy 从该 Surface 抓解码帧(避开 PlayerView.videoSurfaceView 解析/附着竞态)
    val sv = android.view.SurfaceView(ctx).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val exo = ExoPlayer.Builder(ctx).build()
    exo.setVideoSurfaceView(sv)
    exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
    var attempts = 0
    val doGrab = arrayOfNulls<Runnable>(1)
    fun cleanup() {
        try { exo.release() } catch (_: Throwable) {}
        try { root.removeView(sv) } catch (_: Throwable) {}
        ExoGate.release(job)
    }
    fun finish(result: () -> Unit) {
        if (!done.compareAndSet(false, true)) return
        cleanup()
        result()
    }
    doGrab[0] = Runnable {
        if (done.get()) return@Runnable
        try {
            val s = try { sv.holder.surface } catch (_: Throwable) { null }
            if (s == null || !s.isValid) {
                if (attempts++ < 15) { main.postDelayed({ doGrab[0]?.run() }, 200); return@Runnable }
                finish { thumbJobFail(job) }; Log.w("UiKit", "exo thumb Surface 无效超时: $key"); return@Runnable
            }
            val bmp = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(s, bmp, object : android.view.PixelCopy.OnPixelCopyFinishedListener {
                override fun onPixelCopyFinished(copyResult: Int) {
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        if (isNearlyBlack(bmp)) {
                            // 视频画面尚未真正渲染到 surface(黑帧), 视为无效重试, 防止脏帧永久入缓存
                            Log.w("UiKit", "exo thumb 黑帧丢弃 重试${attempts + 1}: " + f.name)
                            if (!done.get() && attempts++ < 15) main.postDelayed({ doGrab[0]?.run() }, 300)
                            else finish { thumbJobFail(job) }
                        } else {
                            finish { thumbJobSuccess(job, bmp) }
                        }
                    } else {
                        Log.w("UiKit", "exo thumb PixelCopy 失败 code=$copyResult 重试${attempts + 1}: " + f.name)
                        if (!done.get() && attempts++ < 15) main.postDelayed({ doGrab[0]?.run() }, 300)
                        else finish { thumbJobFail(job) }
                    }
                }
            }, main)
        } catch (t: Throwable) {
            Log.w("UiKit", "exo thumb PixelCopy 异常", t)
            if (!done.get() && attempts++ < 15) main.postDelayed({ doGrab[0]?.run() }, 300)
            else finish { thumbJobFail(job) }
        }
    }
    sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(h: android.view.SurfaceHolder) {
            Log.i("UiKit", "exo thumb surfaceCreated: " + f.name)
            main.postDelayed({ doGrab[0]?.run() }, 250)
        }
        override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, height: Int) {}
        override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
    })
    exo.addListener(object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            Log.i("UiKit", "exo thumb state=$state (2BUFFER 3READY 4END): " + f.name)
            if (state == androidx.media3.common.Player.STATE_READY && !done.get()) {
                main.postDelayed({ doGrab[0]?.run() }, 250)
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w("UiKit", "exo thumb 播放错误: " + error.errorCodeName)
        }
    })
    main.post { try { root.addView(sv) } catch (t: Throwable) { Log.w("UiKit", "exo thumb addView 异常", t); finish { thumbJobFail(job) } } }
    try {
        exo.prepare()
    } catch (t: Throwable) {
        Log.w("UiKit", "exo thumb prepare 异常", t)
        finish { thumbJobFail(job) }; return
    }
    exo.playWhenReady = true
    Log.i("UiKit", "exo thumb SurfaceView+PixelCopy 解码启动: " + f.name)
    main.postDelayed({
        finish { thumbJobFail(job) }; Log.w("UiKit", "exo thumb 取帧超时(8s): " + key)
    }, 8000)
}

/** 首帧取帧多策略重试: 部分平台/容器对 OPTION_CLOSEST_SYNC 兼容性差取帧失败, 依次换参数; 全失败返回 null */
private fun retrieveFrameRetry(mmr: MediaMetadataRetriever, f: File): Bitmap? {
    val attempts = arrayOf(
        longArrayOf(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC.toLong()),
        longArrayOf(0L, MediaMetadataRetriever.OPTION_CLOSEST.toLong()),
        longArrayOf(1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC.toLong()),
        longArrayOf(5_000L, MediaMetadataRetriever.OPTION_CLOSEST.toLong())
    )
    var err: Throwable? = null
    for (a in attempts) {
        try {
            val b = mmr.getFrameAtTime(a[0], a[1].toInt())
            if (b != null) return b
        } catch (t: Throwable) { err = t }
    }
    Log.w("UiKit", "retrieveFrameRetry: 取帧全失败 path=" + f.absolutePath, err)
    return null
}

/** 指定时间点取帧(video_frame 工具用): FileDescriptor 直连 + 多策略重试, 失败返回 null */
fun videoFrameAt(f: File, timeMs: Long): Bitmap? {
    return try {
        val mmr = MediaMetadataRetriever()
        try {
            val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try { mmr.setDataSource(pfd.fileDescriptor) } finally { pfd.close() }
        } catch (t: Throwable) {
            mmr.setDataSource(f.absolutePath)
        }
        val t = timeMs.coerceAtLeast(0L)
        val attempts = arrayOf(
            longArrayOf(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC.toLong()),
            longArrayOf(t, MediaMetadataRetriever.OPTION_CLOSEST.toLong()),
            longArrayOf(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC.toLong()),
            longArrayOf(1_000L, MediaMetadataRetriever.OPTION_CLOSEST.toLong())
        )
        var frame: Bitmap? = null
        for (a in attempts) {
            try { frame = mmr.getFrameAtTime(a[0], a[1].toInt()); if (frame != null) break } catch (t: Throwable) {}
        }
        mmr.release()
        frame
    } catch (e: Exception) { null }
}

/** 无重编码 remux 到 cache: 部分解码栈拒绝原文件(视频/容器兼容性问题), 用 MediaExtractor+MediaMuxer 原样重写容器后可正常播放; 失败返回 null */
fun remuxToCache(ctx: Context, src: File): File? {
    try {
        val dir = File(ctx.cacheDir, "remux")
        if (!dir.exists()) dir.mkdirs()
        val dst = File(dir, src.name.replace(":", "_").replace("/", "_").replace("\\", "_") + ".remux.mp4")
        if (dst.exists()) dst.delete()

        val ex = MediaExtractor()
        try {
            // 用 FileDescriptor 直连文件层, 规避 Android 16 对含全角/emoji 字符的裸路径解析成 content:// 失败的问题
            val pfd = android.os.ParcelFileDescriptor.open(src, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                ex.setDataSource(pfd.fileDescriptor, 0, -1)
            } finally {
                pfd.close()
            }
        } catch (t: Throwable) {
            ex.release()
            Log.w("UiKit", "remux: setDataSource 失败", t)
            return null
        }
        val tc = ex.trackCount
        var vIdx = -1
        for (i in 0 until tc) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            Log.w("UiKit", "remux: track[$i] mime=" + mime)
            if (mime.startsWith("video/")) { vIdx = i; break }
        }
        if (vIdx < 0) {
            Log.w("UiKit", "remux: 未找到 video track, trackCount=" + tc)
            ex.release()
            return null
        }
        ex.selectTrack(vIdx)
        Log.w("UiKit", "remux: selected video track=$vIdx, 开始 mux")

        val muxer = try {
            MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            ex.release()
            Log.w("UiKit", "remux: MediaMuxer 创建失败", t)
            return null
        }
        try {
            val outTrack = muxer.addTrack(ex.getTrackFormat(vIdx))
            muxer.start()
            val buf = java.nio.ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var n = 0
            while (true) {
                val sz = ex.readSampleData(buf, 0)
                if (sz < 0) break
                info.offset = 0
                info.size = sz
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                muxer.writeSampleData(outTrack, buf, info)
                ex.advance()
                n++
            }
            muxer.stop()
            Log.w("UiKit", "remux 完成 samples=" + n + " dst=" + dst.absolutePath)
        } finally {
            try { muxer.release() } catch (_: Throwable) {}
            ex.release()
        }
        return if (dst.length() > 0) dst else null
    } catch (t: Throwable) {
        Log.w("UiKit", "remux 异常", t)
        return null
    }
}

/** dp -> px (UiKit 内部图标/解码函数用), 避免依赖 Activity.dp() */
private fun dp(density: Float, v: Int): Int = (v * density).toInt()

/** 模型按钮背景: 浅蓝圆角底 + 居中双向箭头(⇄), 图标随 background 绘制天然居中 */
fun modelIconBg(density: Float): Drawable = object : Drawable() {
    private fun dp(v: Int) = (v * density).toInt()
    private val bg = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Ui.PRIMARY_LIGHT)
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.PRIMARY
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
        setColor(Ui.PRIMARY_LIGHT)
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.PRIMARY
        style = Paint.Style.STROKE
        strokeWidth = density * 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        bg.setBounds(bounds)
        bg.draw(canvas)
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
        color = Ui.SUB
        style = Paint.Style.STROKE
        strokeWidth = density * 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
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
        setColor(if (recording) Color.parseColor("#F0523D") else Ui.PRIMARY_LIGHT)
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (recording) Color.WHITE else Ui.PRIMARY
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
            Toast.makeText(context, context.getString(R.string.mp_file_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val mime = AttachmentStore.mimeOf(context, fileName)
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
            context.startActivity(Intent.createChooser(share, context.getString(R.string.uk_share_attach)))
        }
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.uk_open_fail, e.message), Toast.LENGTH_SHORT).show()
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
