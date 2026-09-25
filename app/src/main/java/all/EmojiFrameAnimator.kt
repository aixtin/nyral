package io.github.aixtin.nyral

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 方案C v6 (09-20 自研重构): 在 v4b 基础上拔根五刀。
 *
 * 1) isShortEmoji 主线程纯缓存查询: 永不碰 MMR(连发新表情零阻塞), 未命中默认短表情,
 *    后台 warmup 探测补齐; 长表情/损坏文件写失败缓存, attach 直接回退 ExoPlayer 兜底。
 * 2) 全局单消息调度器: 一个 tick 驱动全部活跃实例, 按最早到期动态延迟, 消息风暴消除;
 *    滚动中统一暂停(QQ 式), 墙钟冻结, 松手由对账 resumeIfReady 恢复。
 * 3) 抽帧失败自治: 失败写缓存 + 保垫底图(首帧先行已上屏), 绝不循环重抽, 绝不闪黑。
 * 4) killSelf 不再清空视图: 只回收 Bitmap 与集合归属, 视图保留最后帧, 杜绝"清空→黑窗→重建"。
 * 5) 抽帧 4 线程 + 超时兜底(5s 放弃), 垫底图必达。
 *
 * 生命周期(沿用 v4 范式):
 *   attach 回屏 -> 帧就绪则播 / 软释放后重抽(等待期保留旧帧)
 *   detach 离屏 -> 暂停 + 看门狗 30s 后 softRelease(保留视图+当前帧+tag)
 *   softRelease  -> 回屏 startDecode 重抽续播
 *   killSelf     -> 收口(抽帧失败/行销毁): 停止 + 释放帧 + 移除集合, 视图保留最后帧(不清空)
 */
object EmojiFrameAnimator {

    /** 短表情阈值: 时长上限 2s */
    const val MAX_DURATION_MS = 3000L
    /** 短表情阈值: 体积上限 3MB */
    const val MAX_BYTES = 3L * 1024 * 1024
    /** 抽帧上限: 超过视为长表情, 不适用帧动画 */
    const val MAX_FRAMES = 24
    /** 帧最长边缩放上限(内存保护) */
    const val MAX_SCALE = 240
    /** 同屏帧动画实例上限(独立于 ExoGate, 内存保护; 超限直接放弃帧动画走 ExoPlayer 兜底) */
    const val MAX_ACTIVE = 10
    /** 离屏软释放看门狗: 30s 后释放帧数组防 Bitmap 堆积 */
    const val RELEASE_DELAY_MS = 30_000L
    /** 抽帧超时兜底: 超过即放弃并标记失败(防极端文件卡死抽帧线程) */
    const val DECODE_TIMEOUT_MS = 5_000L

    val sActive = java.util.Collections.synchronizedList(ArrayList<Controller>())
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 短表情判定缓存(后台探测写入): key = 路径 + 修改时间, 文件更新自动失效 */
    private val sShortCache = ConcurrentHashMap<String, Boolean>()
    /** 长表情缓存(后台探测写入): 时长超限, 帧动画不适用, 走 ExoPlayer 兜底(既有行为) */
    private val sLongCache = ConcurrentHashMap<String, Boolean>()
    /** 失败缓存(后台探测/抽帧失败写入): 该文件帧动画确认不可用, attach 前拦截直接静态缩略图,
     *  不落 ExoPlayer(防"废柴文件"成批转交解码器造成并发内存洪峰 OOM, 09-20 闪退根因) */
    private val sFailedCache = ConcurrentHashMap<String, Boolean>()

    private fun cacheKey(f: File) = f.absolutePath + "@" + f.lastModified()

    /** 短表情判定(主线程安全): 只查缓存, 永不碰 MMR。
     *  未命中默认 true(表情文件小, 按短表情处理, 首帧先行+失败自治兜底), 后台 warmup 补齐探测。 */
    fun isShortEmoji(f: File): Boolean {
        if (f.length() <= 0L || f.length() > MAX_BYTES) return false
        val key = cacheKey(f)
        sShortCache[key]?.let { return it }
        if (sFailedCache.containsKey(key)) return false
        if (sLongCache.containsKey(key)) return false
        return true
    }

    /** 帧动画确认不可用(抽帧失败/损坏): attach 前拦截, 直接静态缩略图, 不落 ExoPlayer 兜底 */
    fun isFailed(f: File): Boolean =
        f.length() > 0L && sFailedCache.containsKey(cacheKey(f))

    /** 后台预热探测: 短表情写短缓存, 长表情写长缓存(走 ExoPlayer), 无时长/损坏写失败缓存(缩略图);
     *  幂等, 可反复调用零主线程开销 */
    fun warmup(f: File) {
        if (f.length() <= 0L || f.length() > MAX_BYTES) return
        val key = cacheKey(f)
        if (sShortCache.containsKey(key) || sLongCache.containsKey(key) || sFailedCache.containsKey(key)) return
        sDecodeExec.execute {
            val r = try {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    when {
                        dur in 1..MAX_DURATION_MS -> 1   // 短表情: 帧动画
                        dur > MAX_DURATION_MS -> 2       // 长表情: ExoPlayer 兜底
                        else -> 3                        // 无时长/损坏: 帧动画不可用
                    }
                } finally { try { mmr.release() } catch (_: Throwable) {} }
            } catch (t: Throwable) { 3 }
            when (r) {
                1 -> sShortCache[key] = true
                2 -> sLongCache[key] = true
                else -> sFailedCache[key] = true
            }
        }
    }

    /** 递归判断视图树里是否有活跃(非 dead)帧动画实例(对账识别用) */
    fun hasActiveFrame(v: View): Boolean {
        if (v is FrameLayout && v.tag == "emoji_frame_anim") {
            if (sActive.any { it.frame === v && !it.dead }) return true
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            if (hasActiveFrame(v.getChildAt(i))) return true
        }
        return false
    }

    /** 递归找到可见行的活跃帧动画控制器(对账续播用); 无则 null */
    fun controllerOf(v: View): Controller? {
        if (v is FrameLayout && v.tag == "emoji_frame_anim") {
            sActive.firstOrNull { it.frame === v && !it.dead }?.let { return it }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            controllerOf(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /**
     * 挂载帧动画到 frame(短表情专用): frame 需已按宽高比适配; 成功返回 true, 失败 false 回退 ExoPlayer。
     * 超限时让位(杀不可见/最老实例腾名额, 保留视图不黑), 失败缓存命中/抽帧失败返回 false 由调用方兜底。
     */
    fun attach(frame: FrameLayout, f: File): Boolean {
        // 失败缓存命中: 该文件帧动画不可用, 直接回退(防死循环重抽)
        if (sFailedCache.containsKey(cacheKey(f))) return false
        // 同 frame 已有控制器: 同文件未软释放视为已接管不重复创建;
        // 换文件/已软释放则让位(killSelf 保留视图最后帧, 不闪黑), 再按新文件重建
        sActive.firstOrNull { it.frame === frame && !it.dead }?.let { old ->
            if (old.f.absolutePath == f.absolutePath && !old.released) return true
            old.killSelf()
        }
        // 全局活跃实例超限: 让位腾名额——优先杀不可见(离屏)实例, 其次杀最老已播完的;
        // killSelf 保留视图最后帧, 新表情继续帧动画, 杜绝"满员降级→缩略图未命中深色占位闪黑"
        if (sActive.size >= MAX_ACTIVE) {
            val victim = sActive.firstOrNull { !it.dead && !it.attached }
                ?: sActive.firstOrNull { !it.dead && it.frames != null && !it.playing }
                ?: sActive.firstOrNull { !it.dead }
            victim?.killSelf()
            if (sActive.size >= MAX_ACTIVE) return false
        }
        val iv = ImageView(frame.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        // 不清空已有子视图(调用方可铺缓存缩略图垫底): 帧动画 ImageView 叠加其上,
        // 抽帧完成前真缩略图可见, 消除"attach 抽帧排队期空白/闪黑"
        frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.tag = "emoji_frame_anim"
        val ctrl = Controller(frame, iv, f)
        sActive.add(ctrl)
        frame.addOnAttachStateChangeListener(ctrl.attachListener)
        ctrl.startDecode()
        return true
    }

    // ---- 全局单消息调度器: 一个 tick 驱动全部活跃实例 ----
    @Volatile private var tickScheduled = false
    private val sTick = object : Runnable {
        override fun run() {
            tickScheduled = false
            // 滚动中统一暂停(QQ 式): 墙钟冻结, 主线程零刷帧; 滚动结束由对账 resumeIfReady 恢复
            if (sScrolling) { pauseAll(); return }
            var nextDelay = Long.MAX_VALUE
            val now = SystemClock.uptimeMillis()
            for (c in sActive.toTypedArray()) {
                if (c.dead || !c.playing || !c.attached) continue
                val d = c.frameTick(now)
                if (d > 0 && d < nextDelay) nextDelay = d
            }
            if (nextDelay != Long.MAX_VALUE) {
                tickScheduled = true
                mainHandler.postDelayed(this, nextDelay)
            }
        }
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        mainHandler.removeCallbacks(sTick)
        mainHandler.post(sTick)
    }

    /** 滚动开始(由 MainActivity onScrollStateChanged 在 sScrolling=true 时调用): 立即统一暂停 */
    fun onScrollStart() {
        pauseAll()
        mainHandler.removeCallbacks(sTick)
        tickScheduled = false
    }

    private fun pauseAll() {
        for (c in sActive.toTypedArray()) { if (!c.dead) c.pause() }
    }

    /** 帧动画控制器: 抽帧 -> 换帧循环(全局调度驱动) */
    class Controller internal constructor(
        val frame: FrameLayout,
        val iv: ImageView,
        val f: File
    ) {
        @Volatile var frames: List<Bitmap>? = null
        var intervalMs = 100L
        internal var playing = false
        @Volatile var dead = false
        internal var attached = false
        internal var released = false
        private var releasePending = false
        @Volatile private var decoding = false
        private var startUptime = 0L   // 本轮播放起始墙钟(0 帧对应时刻)
        private var pausedElapsed = 0L // 暂停时已播放时长(ms), play 时折算回 startUptime
        private var lastIdx = 0        // 当前显示帧索引(softRelease 保留该帧垫底)
        private var lastShownIdx = -1  // 已上屏帧索引(避免重复 setImageBitmap)

        /** 由全局调度器按绝对墙钟推进; 返回下次调度延迟(ms); -1 表示实例无需再调度 */
        fun frameTick(now: Long): Long {
            val arr = frames ?: return -1
            if (arr.isEmpty()) return -1
            // 慢放联动(09-24): 帧间隔按全局慢放倍数放大(系统 animator_duration_scale 只管属性动画)
            val eff = (intervalMs * TypewriterCenter.slowMul()).toLong().coerceAtLeast(1L)
            if (arr.size == 1) {
                if (lastShownIdx != 0) { iv.setImageBitmap(arr[0]); lastShownIdx = 0 }
                lastIdx = 0
                return eff
            }
            val el = (now - startUptime).coerceAtLeast(0L)
            val i = ((el / eff) % arr.size).toInt()
            if (i != lastShownIdx) {
                iv.setImageBitmap(arr[i])
                lastShownIdx = i
            }
            lastIdx = i
            return eff
        }

        private val watchdog = Runnable { if (releasePending) softRelease() }

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attached = true
                releasePending = false
                mainHandler.removeCallbacks(watchdog)
                if (dead) return
                if (released || frames == null) {
                    // 软释放后回屏/抽帧未完成: 重新抽帧(幂等: decoding/frames 双判空)
                    startDecode()
                    return
                }
                if (!sScrolling) play()
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (dead) return
                attached = false
                pause()
                releasePending = true
                mainHandler.postDelayed(watchdog, RELEASE_DELAY_MS)
            }
        }

        /** 后台抽帧(四线程, 首帧先行, 超时兜底): 抽帧中/已软释放可重入, 幂等防重复入队 */
        fun startDecode() {
            if (dead) return
            if (frames != null) return
            if (decoding) return
            decoding = true
            val key = cacheKey(f)
            sDecodeExec.execute {
                val arr = extractFrames(f) { first ->
                    // 首帧就绪立即上屏(静态占位): 队列靠后的表情也秒现第一帧, 等待期不空白
                    mainHandler.post {
                        if (!dead && attached && frames == null) iv.setImageBitmap(first)
                    }
                }
                mainHandler.post {
                    decoding = false
                    if (dead) { arr?.forEach { try { it.recycle() } catch (_: Throwable) {} }; return@post }
                    if (arr == null || arr.size < 2) {
                        // 抽帧失败自治: 写失败缓存 + 保留垫底(首帧已上屏/缩略图), 不再循环重抽;
                        // 行保留静态图, 对账 hasActiveFrame=false 后重建走 ExoPlayer 兜底
                        sFailedCache[key] = true
                        killSelf()
                        return@post
                    }
                    frames = arr
                    intervalMs = computeInterval(f, arr.size)
                    pausedElapsed = 0L
                    lastIdx = 0
                    lastShownIdx = -1
                    if (attached && !sScrolling) play()
                }
            }
            // 超时兜底: 5s 未完成视为失败(线程可能卡在 MMR), 保垫底不清空视图
            mainHandler.postDelayed({
                if (decoding && frames == null && !dead) {
                    sFailedCache[key] = true
                    killSelf()
                }
            }, DECODE_TIMEOUT_MS)
        }

        fun play() {
            if (dead) return
            if (!playing) {
                playing = true
                startUptime = SystemClock.uptimeMillis() - pausedElapsed
            }
            if (!sScrolling) scheduleTick()
        }

        fun pause() {
            if (playing) {
                pausedElapsed = (SystemClock.uptimeMillis() - startUptime).coerceAtLeast(0L)
                val arr = frames
                if (arr != null && arr.isNotEmpty() && intervalMs > 0) {
                    pausedElapsed = pausedElapsed % ((intervalMs * TypewriterCenter.slowMul()).toLong().coerceAtLeast(1L) * arr.size)
                }
            }
            playing = false
        }

        /** 滚动停止对账续播入口: 帧就绪且未播则播, 未就绪/软释放则重新抽帧(幂等) */
        fun resumeIfReady() {
            if (dead || !attached) return
            if (frames == null) { startDecode(); return }
            if (!playing) play()
        }

        /** 软释放: 保留视图+当前帧+tag+sActive 归属, 仅回收其余帧。
         *  回屏时 attachListener 触发 startDecode 重抽, 等待期显示保留帧, 上滑不再闪黑。 */
        fun softRelease() {
            if (dead || released) return
            pause()
            releasePending = false
            mainHandler.removeCallbacks(watchdog)
            val arr = frames
            val keep = arr?.getOrNull(lastIdx.coerceIn(0, (arr.size - 1).coerceAtLeast(0)))
            arr?.forEach { b -> if (b !== keep) try { b.recycle() } catch (_: Throwable) {} }
            frames = null
            released = true
            lastShownIdx = -1
            if (keep != null) iv.setImageBitmap(keep)
        }

        /** 统一收口(抽帧失败/行销毁): 停止 + 释放帧 + 移除集合。
         *  v6: 不再清空视图——保留最后显示帧/垫底图, 杜绝"清空→黑窗→重建"闪黑;
         *  dead 实例 hasActiveFrame=false, 对账可按需重建走兜底链路。 */
        fun killSelf() {
            if (dead) return
            dead = true
            pause()
            releasePending = false
            mainHandler.removeCallbacks(watchdog)
            sActive.remove(this)
            frames?.forEach { try { it.recycle() } catch (_: Throwable) {} }
            frames = null
            released = true
            // 视图与最后帧保留(不清空), 仅解绑监听防泄漏
            try { frame.removeOnAttachStateChangeListener(attachListener) } catch (_: Throwable) {}
        }
    }

    // ---- 抽帧实现 ----
    private val sDecodeExec = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "nyral-emoji-frame").apply { priority = Thread.MIN_PRIORITY }
    }

    private fun extractFrames(f: File, onFirst: ((Bitmap) -> Unit)? = null): List<Bitmap>? {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L || dur > MAX_DURATION_MS) return null
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) return null
            val scale = minOf(1f, MAX_SCALE.toFloat() / maxOf(w, h))
            // 按 100ms 节奏抽帧(10fps 观感流畅), 上限 MAX_FRAMES
            val n = ((dur + 99) / 100).toInt().coerceIn(2, MAX_FRAMES)
            val out = ArrayList<Bitmap>(n)
            for (i in 0 until n) {
                val us = i * dur * 1000L / n
                val bmp = try { mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Throwable) { null }
                if (bmp != null) {
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                    } else bmp
                    if (scaled !== bmp) { try { bmp.recycle() } catch (_: Throwable) {} }
                    out.add(scaled)
                    // 首帧即回传(后台线程): 主线程立即显示静态首帧, 等待期不再是深色占位/空白
                    if (i == 0 && onFirst != null) onFirst(scaled)
                }
            }
            if (out.size < 2) null else out
        } catch (t: Throwable) { null } finally {
            try { mmr?.release() } catch (_: Throwable) {}
        }
    }

    private fun computeInterval(f: File, n: Int): Long {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L) 100L else (dur / n).coerceIn(40L, 500L)
        } catch (t: Throwable) { 100L } finally {
            try { mmr?.release() } catch (_: Throwable) {}
        }
    }
}
