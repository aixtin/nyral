package io.github.aixtin.droidagent

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 附件预览层：从 MainActivity 拆分。
 * 全屏媒体(图/视频)预览、PDF 分页预览、文本预览、音频预览与原图解码。
 * 全部以 Activity 扩展函数承载，块内互调复用隐式 receiver，仅解码线程池块内自持。
 */

/** 预览块专用后台线程池（大图解码 / PDF 单页渲染） */
private val previewIo = Executors.newSingleThreadExecutor()

private fun Context.dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

/** 附件入口分发: 按 mime 选择媒体/PDF/文本/音频预览或系统外部打开 */
fun Activity.openAttachmentPreview(files: List<String>, startIndex: Int) {
    val act: Activity = this
    val idx = if (startIndex in files.indices) startIndex else 0
    val file = files[idx]
    val f = AttachmentStore.fileOf(act, file)
    if (f == null) {
        Toast.makeText(act, "附件文件已不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val mime = AttachmentStore.mimeOf(file)
    when {
        mime.startsWith("image/") || mime.startsWith("video/") -> {
            // 同一条消息的图片/视频: 全屏左右滑动切换浏览
            val media = files.filter { fn ->
                val ff = AttachmentStore.fileOf(act, fn)
                ff != null && AttachmentStore.mimeOf(fn).let { it.startsWith("image/") || it.startsWith("video/") }
            }
            val mi = media.indexOf(file).coerceAtLeast(0)
            showMediaPreviewDialog(media, mi)
        }
        mime == "application/pdf" -> showPdfPreviewDialog(file)
        mime.startsWith("text/") -> showTextPreviewDialog(file)
        mime.startsWith("audio/") -> showAudioPreviewDialog(file)
        else -> openAttachmentExternal(act, file)
    }
}

/** 全屏媒体预览: 同消息多图/视频左右滑动切换; 图片单击关闭/双击缩放, 视频自动播放当前页 */
private fun Activity.showMediaPreviewDialog(media: List<String>, startIndex: Int) {
    val act: Activity = this
    if (media.isEmpty()) return
    val d = Dialog(act)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    // 外层留边距, 露出圆角: 整卡黑底圆角, 顶部标题栏白底仅顶部圆角
    val outer = FrameLayout(act).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    outer.addView(createMediaPreviewContent(media, startIndex, d))
    d.setContentView(outer)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    d.show()
}

private fun Activity.createMediaPreviewContent(media: List<String>, startIndex: Int, d: Dialog): View {
    val act: Activity = this
    val screenW = resources.displayMetrics.widthPixels
    val screenH = resources.displayMetrics.heightPixels
    // 内容区宽度: 外层留边距后实际可用宽度
    val contentW = screenW - dp(20)
    lateinit var indicator: TextView
    val root = FrameLayout(act).apply {
        background = rounded(dp(20), Color.BLACK)
        // 内容裁剪到圆角范围内, 视频/图片铺满底部时底角仍保持圆角
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
    // 垂直容器: 顶部标题栏占一行, 媒体内容在其下方填充剩余空间, 不被标题遮挡
    val vStack = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val hsv = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val strip = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val videoViews = arrayOfNulls<PlayerView>(media.size)
    media.forEachIndexed { i, file ->
        val page = FrameLayout(act).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val mime = AttachmentStore.mimeOf(file)
        if (mime.startsWith("image/")) {
            val iv = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    bottomMargin = dp(8)   // 底部留白收窄, 配合 root 圆角裁剪, 不遮圆角
                }
                setBackgroundColor(Color.BLACK)
            }
            val f = AttachmentStore.fileOf(act, file)
            // 大图解码移到后台线程, 避免大图在主线程解码卡顿
            if (f != null) {
                previewIo.execute {
                    val bmp = decodeFullBitmap(f)
                    runOnUiThread { iv.setImageBitmap(bmp) }
                }
            }
            // 缩放平移状态: 双击在 1x/2x 间切换; 放大后可单指拖动, 边界钳制不拖出
            var scale = 1f
            var tx = 0f
            var ty = 0f
            fun clampAndApply() {
                if (scale <= 1.01f) {
                    tx = 0f; ty = 0f
                } else {
                    val maxX = iv.width * (scale - 1f) / 2f
                    val maxY = iv.height * (scale - 1f) / 2f
                    tx = tx.coerceIn(-maxX, maxX)
                    ty = ty.coerceIn(-maxY, maxY)
                }
                iv.scaleX = scale
                iv.scaleY = scale
                iv.translationX = tx
                iv.translationY = ty
            }
            val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (scale > 1.01f) {
                        tx -= distanceX
                        ty -= distanceY
                        clampAndApply()
                        return true
                    }
                    return false
                }
            })
            gd.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { d.dismiss(); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scale = if (scale > 1.01f) 1f else 2f
                    tx = 0f; ty = 0f
                    iv.animate().scaleX(scale).scaleY(scale)
                        .translationX(0f).translationY(0f).setDuration(200).start()
                    return true
                }
                override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
            })
            iv.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
            page.addView(iv)
        } else {
            // 视频内核改用 Media3 ExoPlayer: 自带纯 Java 实现的 MP4 Extractor, 绕开系统 MediaPlayer/MediaExtractor
            // 栈对特定转发视频(社交平台转存/含特殊字符/容器非标准)的拒绝(No content provider / instantiate extractor 失败)
            val pv = PlayerView(act).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
                    bottomMargin = dp(8)   // 底部留白收窄, 播放控件悬浮于视频画面内, 不遮底部圆角
                }
                useController = true
            }
            val f = AttachmentStore.fileOf(act, file)
            if (f != null) {
                val exo = ExoPlayer.Builder(act).build()
                exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
                // 接收端气泡循环播放管线: 预览弹窗内视频/动图 播放完一次自动重播(loop), 对齐微信大动图
                exo.repeatMode = ExoPlayer.REPEAT_MODE_ALL
                exo.prepare()
                exo.playWhenReady = true
                pv.player = exo
                pv.setBackgroundColor(Color.BLACK)
            }
            videoViews[i] = pv
            page.addView(pv)
        }
        strip.addView(page)
    }
    hsv.addView(strip)

    // 顶部标题栏(白底融合整体 UI): 文件名 + 页码 + 关闭; 仅顶部两角圆角(与整卡黑底圆角衔接)
    val topBarBg = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadii = floatArrayOf(
            dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
            0f, 0f, 0f, 0f)
    }
    val topBar = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = topBarBg
        setPadding(dp(10), dp(8), dp(6), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    topBar.addView(TextView(act).apply {
        text = media.mapNotNull { AttachmentStore.fileOf(act, it)?.name }.getOrNull(startIndex) ?: "预览"
        textSize = 15f
        setTextColor(Color.parseColor("#1A1A1A"))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    topBar.addView(TextView(act).apply {
        text = "${startIndex + 1}/${media.size}"
        textSize = 14f
        setTextColor(Color.parseColor("#0B93F6"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(4), dp(8), dp(4))
    }.also { indicator = it })
    topBar.addView(TextView(act).apply {
        text = "✕"
        textSize = 22f
        setTextColor(Color.parseColor("#1A1A1A"))
        setPadding(dp(14), dp(2), dp(12), dp(2))
        setOnClickListener { d.dismiss() }
    })
    vStack.addView(topBar)
    vStack.addView(hsv)
    root.addView(vStack)

    fun currentPage(): Int =
        if (media.isEmpty()) 0 else (hsv.scrollX.toFloat() / contentW).let { Math.round(it).coerceIn(0, media.size - 1) }

    fun onPageChanged(page: Int) {
        indicator.text = "${page + 1}/${media.size}"
        media.forEachIndexed { i, fn ->
            val pv = videoViews[i] ?: return@forEachIndexed
            if (i == page) {
                val p = pv.player
                if (p != null && !p.isPlaying) { try { p.play() } catch (_: Exception) {} }
            } else {
                val p = pv.player
                if (p != null && p.isPlaying) { try { p.pause() } catch (_: Exception) {} }
            }
        }
    }

    // 抬手后(含惯性滑动)重新定位当前页: 滚动停止时吸附到最近整页并同步页码, 避免停在中缝/页码错位
    hsv.setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                hsv.postDelayed({
                    val target = currentPage()
                    hsv.smoothScrollTo(target * contentW, 0)
                    onPageChanged(target)
                }, 60)
        }
        false
    }
    d.setOnDismissListener { media.forEachIndexed { i, _ -> videoViews[i]?.player?.release() } }

    hsv.post {
        hsv.scrollTo(startIndex * contentW, 0)
        onPageChanged(startIndex)
    }
    return root
}

/** 全屏 PDF 预览: 分页式, 一页一屏, 上下翻页; 内存恒定一页(几百页不 OOM, 不再一通到底) */
private fun Activity.showPdfPreviewDialog(fileName: String) {
    val act: Activity = this
    val f = AttachmentStore.fileOf(act, fileName) ?: return
    val d = Dialog(act)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val screenW = resources.displayMetrics.widthPixels
    val contentW = screenW - dp(24)
    // 外层留边距露出圆角: 整卡浅底圆角, 标题栏白底仅顶部圆角
    val outer = FrameLayout(act).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(dp(20), Color.parseColor("#F7F7F8"))
    }
    val topBarBg = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadii = floatArrayOf(
            dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
            0f, 0f, 0f, 0f)
    }
    root.addView(LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = topBarBg
        setPadding(dp(16), dp(10), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(act).apply {
            text = "📄 ${f.name}"
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A1A"))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(act).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(14), dp(2), dp(12), dp(2))
            setOnClickListener { d.dismiss() }
        })
    })
    // 中间: 单页展示区(权重1), 页码悬浮底中
    val pageFrame = FrameLayout(act).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val pageIv = ImageView(act).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    pageFrame.addView(pageIv)
    val pageNo = TextView(act).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#66000000"))
        }
        setPadding(dp(10), dp(3), dp(10), dp(3))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) }
    }
    pageFrame.addView(pageNo)
    root.addView(pageFrame)
    // 底部导航: 上一页 / 页码 / 下一页
    val nav = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(6), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val prevBtn = TextView(act).apply {
        text = "‹ 上一页"
        textSize = 14f
        setTextColor(Color.parseColor("#1A1A1A"))
        setBackgroundColor(Color.parseColor("#00000000"))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val nextBtn = TextView(act).apply {
        text = "下一页 ›"
        textSize = 14f
        setTextColor(Color.parseColor("#1A1A1A"))
        setBackgroundColor(Color.parseColor("#00000000"))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    nav.addView(prevBtn)
    nav.addView(TextView(act).apply {
        text = "· · ·"
        textSize = 14f
        setTextColor(Color.parseColor("#BBBBBB"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(8) }
    })
    nav.addView(nextBtn)
    root.addView(nav)
    outer.addView(root)
    d.setContentView(outer)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    // 分页渲染状态
    var renderer: PdfRenderer? = null
    var cur = 0
    var total = 0
    var lastPage: PdfRenderer.Page? = null
    var renderSeq = 0   // 渲染序号: 翻页递增, 过期渲染结果直接丢弃, 避免快速翻页时旧页覆盖新页
    fun renderPage(i: Int) {
        val r = renderer ?: return
        val seq = ++renderSeq
        // 渲染移到后台线程, 大 PDF 单页渲染不再阻塞主线程
        previewIo.execute {
            try {
                lastPage?.let { try { it.close() } catch (e: Exception) { } }
                lastPage = null
                val pg = try { r.openPage(i) } catch (e: Exception) { null } ?: return@execute
                lastPage = pg
                val targetH = (pg.height.toFloat() / pg.width * contentW).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(contentW, targetH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                runOnUiThread {
                    if (seq != renderSeq) { bmp.recycle(); return@runOnUiThread }
                    pageIv.setImageBitmap(bmp)
                    pageNo.text = "${i + 1} / $total"
                    prevBtn.isEnabled = i > 0
                    nextBtn.isEnabled = i < total - 1
                    prevBtn.setTextColor(if (i > 0) Color.parseColor("#1A1A1A") else Color.parseColor("#BBBBBB"))
                    nextBtn.setTextColor(if (i < total - 1) Color.parseColor("#1A1A1A") else Color.parseColor("#BBBBBB"))
                }
            } catch (e: Exception) {
                // 渲染失败静默, 保持上一页画面
            }
        }
    }
    var pfd: ParcelFileDescriptor? = null
    try {
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd)
        total = renderer!!.pageCount
        renderPage(0)
    } catch (e: Exception) {
        Toast.makeText(act, "PDF 解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        d.dismiss()
    }
    prevBtn.setOnClickListener { if (cur > 0) { cur--; renderPage(cur) } }
    nextBtn.setOnClickListener { if (cur < total - 1) { cur++; renderPage(cur) } }
    // 左右滑动翻页(保留上下页按钮), 左滑下一页 / 右滑上一页
    val pdfSwipe = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)
            if (abs(dx) > abs(dy) * 1.4f && abs(dx) > dp(60)) {
                if (dx < 0 && cur < total - 1) { cur++; renderPage(cur) }
                else if (dx > 0 && cur > 0) { cur--; renderPage(cur) }
                return true
            }
            return false
        }
    })
    pageFrame.setOnTouchListener { _, ev -> pdfSwipe.onTouchEvent(ev); true }
    d.setOnDismissListener {
        lastPage?.let { try { it.close() } catch (e: Exception) { } }
        try { renderer?.close() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
    }
    d.setOnKeyListener { _, keyCode, _ ->
        if (keyCode == KeyEvent.KEYCODE_BACK) { d.dismiss(); true } else false
    }
    d.show()
}

/** 全屏文本预览 */
private fun Activity.showTextPreviewDialog(fileName: String) {
    val act: Activity = this
    val f = AttachmentStore.fileOf(act, fileName) ?: return
    val d = Dialog(act)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val outer = FrameLayout(act).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(dp(20), Color.parseColor("#F7F7F8"))
    }
    val content = try {
        f.readText()
    } catch (e: Exception) {
        "无法读取文本: ${e.message}"
    }
    val topBarBg = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadii = floatArrayOf(
            dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
            0f, 0f, 0f, 0f)
    }
    root.addView(LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = topBarBg
        setPadding(dp(16), dp(10), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(act).apply {
            text = "📄 ${f.name}"
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A1A"))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(act).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(14), dp(2), dp(12), dp(2))
            setOnClickListener { d.dismiss() }
        })
    })
    val scroll = ScrollView(act).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    scroll.addView(TextView(act).apply {
        text = content
        textSize = 15f
        setTextColor(Color.parseColor("#1A1A1A"))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        // 自由复制: 长按出现选择手柄, 可拖选任意片段复制(系统自带全选/复制菜单)
        setTextIsSelectable(true)
    })
    root.addView(scroll)
    root.addView(TextView(act).apply {
        text = "点击 ✕ 关闭"
        textSize = 13f
        setTextColor(Color.parseColor("#8A8A8A"))
        setBackgroundColor(Color.parseColor("#F7F7F8"))
        setPadding(dp(16), dp(10), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    outer.addView(root)
    d.setContentView(outer)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    root.setOnClickListener { d.dismiss() }
    d.setOnKeyListener { _, keyCode, _ ->
        if (keyCode == KeyEvent.KEYCODE_BACK) { d.dismiss(); true } else false
    }
    d.show()
}

/** 音频预览弹窗: 播放/暂停 + 进度条 */
private fun Activity.showAudioPreviewDialog(fileName: String) {
    val act: Activity = this
    val f = AttachmentStore.fileOf(act, fileName) ?: return
    val d = Dialog(act)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val player = MediaPlayer()
    player.setAudioStreamType(AudioManager.STREAM_MUSIC)
    try {
        player.setDataSource(f.absolutePath)
        player.prepare()
    } catch (e: Exception) {
        Toast.makeText(act, "音频加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        return
    }
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(dp(20), Color.WHITE)
        setPadding(dp(24), dp(28), dp(24), dp(20))
    }
    root.addView(TextView(act).apply {
        text = "🎵 ${f.name}"
        textSize = 16f
        setTextColor(Color.parseColor("#1A1A1A"))
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
    })
    val playBtn = Button(act).apply {
        text = "播放"
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#0B93F6"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(16) }
    }
    val seek = SeekBar(act).apply {
        max = player.duration.coerceAtLeast(1)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    val timeTv = TextView(act).apply {
        text = "00:00 / ${fmtDuration(player.duration.toLong())}"
        textSize = 13f
        setTextColor(Color.parseColor("#8A8A8A"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
    }
    root.addView(playBtn); root.addView(seek); root.addView(timeTv)
    root.addView(Button(act).apply {
        text = "关闭"
        setTextColor(Color.parseColor("#1A1A1A"))
        setBackgroundColor(Color.parseColor("#F1F2F4"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        setOnClickListener { d.dismiss() }
    })
    val handler = Handler(Looper.getMainLooper())
    val ticker = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                seek.progress = player.currentPosition
                timeTv.text = "${fmtDuration(player.currentPosition.toLong())} / ${fmtDuration(player.duration.toLong())}"
            }
            handler.postDelayed(this, 500)
        }
    }
    playBtn.setOnClickListener {
        if (player.isPlaying) { player.pause(); playBtn.text = "播放" }
        else { player.start(); playBtn.text = "暂停" }
    }
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) { player.seekTo(progress); timeTv.text = "${fmtDuration(progress.toLong())} / ${fmtDuration(player.duration.toLong())}" }
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
    player.setOnCompletionListener { runOnUiThread { playBtn.text = "播放"; seek.progress = seek.max } }
    d.setOnDismissListener { handler.removeCallbacks(ticker); try { player.release() } catch (_: Exception) {} }
    d.setContentView(root)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    d.show()
    handler.post(ticker)
}

/** 加载原图(限制到屏幕2倍, 避免大图 OOM) */
private fun Activity.decodeFullBitmap(f: File, maxSide: Int = resources.displayMetrics.widthPixels * 2): Bitmap? {
    val act: Activity = this
    return try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (e: Exception) { null }
}
