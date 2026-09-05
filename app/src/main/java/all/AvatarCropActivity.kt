package io.github.aixtin.droidagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * 头像 1:1 自由裁剪页
 * - 由设置页选图后启动，展示原图 + 1:1 正方形裁剪框
 * - 图片支持双指缩放 / 单指拖动
 * - 裁剪框支持整体拖动、四角与四边缩放（始终保持 1:1）
 * - 框外区域半透明暗化，确认后输出裁剪结果临时文件返回给设置页
 *
 * Intent 参数：
 * - EXTRA_URI   相册返回的图片 Uri
 * - EXTRA_IS_AI true=裁剪 AI 头像 false=裁剪用户头像
 *
 * 返回：
 * - RESULT_OK + EXTRA_RESULT_PATH = 裁剪后 1:1 正方形 PNG 临时文件绝对路径
 */
class AvatarCropActivity : Activity() {

    companion object {
        const val EXTRA_URI = "crop_uri"
        const val EXTRA_IS_AI = "crop_is_ai"
        const val EXTRA_RESULT_PATH = "crop_result_path"
        const val REQ_CROP = 3100
    }

    private lateinit var cropView: CropImageView
    private var isAi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_URI)
            }
        }.getOrNull() ?: intent?.getStringExtra(EXTRA_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) { finish(); return }
        isAi = intent?.getBooleanExtra(EXTRA_IS_AI, false) ?: false

        window.statusBarColor = Color.parseColor("#141414")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        val root = Ui.pageRoot(this)
        root.setBackgroundColor(Color.parseColor("#141414"))

        // 顶部深色标题栏
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#141414"))
            addView(TextView(this@AvatarCropActivity).apply {
                text = "‹"
                textSize = 26f
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, dp(14), 0)
                setOnClickListener { finish() }
            })
            addView(TextView(this@AvatarCropActivity).apply {
                text = if (isAi) "裁剪 AI 头像" else "裁剪头像"
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@AvatarCropActivity).apply {
                text = "拖动/缩放调整"
                textSize = 12f
                setTextColor(0xFFAAAAAA.toInt())
            })
        })

        // 中部裁剪区
        cropView = CropImageView(this)
        root.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部操作区
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(18))
            setBackgroundColor(Color.parseColor("#141414"))
        }
        bottom.addView(Ui.dialogCancelBtn(this, "取消") { finish() })
        val confirmBtn = Ui.primaryBtn(this, "完成") { doCrop() }
        bottom.addView(confirmBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(24) })
        root.addView(bottom)

        setContentView(root)

        loadBitmap(uri)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 采样解码原图，限制最长边 <= 2048 防 OOM；一次读入字节后离线解码，规避跨 Activity Uri 授权/二次打开流失败 */
    private fun loadBitmap(uri: Uri) {
        try {
            val bytes: ByteArray? = when (uri.scheme) {
                "file" -> runCatching { uri.path?.let { java.io.File(it).readBytes() } }.getOrNull()
                else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
                finish(); return
            }
            val bmp = BitmapLoader.decodeSampledBytes(bytes, 2048)
            if (bmp == null) {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
                finish(); return
            }
            cropView.setBitmap(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "图片解码失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 从裁剪框输出 1:1 正方形并写临时文件返回 */
    private fun doCrop() {
        val out = cropView.cropBitmap() ?: run {
            Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val tmp = File(cacheDir, "avatar_crop_${System.currentTimeMillis()}.png")
            FileOutputStream(tmp).use { fos ->
                out.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, tmp.absolutePath))
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            if (!out.isRecycled) out.recycle()
        }
    }
}

/**
 * 1:1 自由裁剪 View
 * - 图片 fitCenter 到视图内，支持双指缩放、单指拖动
 * - 裁剪框保持正方形：可整体拖动、拖动四角/四边缩放
 * - 绘制：图片 → 框外暗化遮罩 → 白色框线 + 手柄
 */
class CropImageView(context: Context) : View(context) {
    /** 防空区间 coerceIn（浮点边界误差导致 max<min 时返回 min，避免 IllegalArgumentException 崩溃） */
    private fun Float.safeCoerce(min: Float, max: Float): Float =
        if (max < min) min else this.coerceIn(min, max)

    private var src: Bitmap? = null

    // 图片变换：src 坐标 -> view 坐标
    private val matrix = Matrix()
    // 裁剪框（view 坐标，1:1 正方形）
    private val cropRect = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val maskPaint = Paint().apply {
        color = Color.parseColor("#99000000")
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        color = Color.WHITE
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        color = Color.parseColor("#66FFFFFF")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // 手势状态
    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downPointerDist = 0f
    private var downRect = RectF()
    private var activeHandle = -1
    private var downMatrix = Matrix()
    private var downFocusX = 0f
    private var downFocusY = 0f
    private var downScale = 1f

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG_IMAGE = 1
        private const val MODE_DRAG_CROP = 2
        private const val MODE_RESIZE_CROP = 3
        private const val MODE_PINCH = 4

        private const val HANDLE_NONE = -1
        private const val HANDLE_LT = 0
        private const val HANDLE_RT = 1
        private const val HANDLE_RB = 2
        private const val HANDLE_LB = 3
        private const val HANDLE_T = 4
        private const val HANDLE_R = 5
        private const val HANDLE_B = 6
        private const val HANDLE_L = 7
    }

    fun setBitmap(bmp: Bitmap) {
        src = bmp
        if (width > 0 && height > 0) initTransform()
        else requestLayout()
        invalidate()
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        initTransform()
    }

    private fun initTransform() {
        val bmp = src ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        // fitCenter：图片完整显示并留 8% 边距
        val availW = w * 0.92f
        val availH = h * 0.92f
        val scale = minOf(availW / bmp.width, availH / bmp.height)
        val dx = (w - bmp.width * scale) / 2f
        val dy = (h - bmp.height * scale) / 2f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)

        // 裁剪框初始：图片可视区内居中的正方形（取图片短边显示尺寸的 85%）
        val side = minOf(bmp.width, bmp.height).toFloat() * scale * 0.85f
        val cx = w / 2f
        val cy = h / 2f
        cropRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        clampCropRectToImage()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = src ?: return

        // 底图
        canvas.drawColor(Color.parseColor("#141414"))
        canvas.drawBitmap(bmp, matrix, paint)

        // 框外暗化遮罩（裁剪框外四个矩形区域）
        drawOutsideMask(canvas, width.toFloat(), height.toFloat())

        // 九宫格线
        drawGrid(canvas, cropRect)

        // 裁剪框边框
        canvas.drawRect(cropRect, framePaint)

        // 四角手柄（小方块）
        val hs = dpf(6f)
        val corners = arrayOf(
            floatArrayOf(cropRect.left, cropRect.top),
            floatArrayOf(cropRect.right, cropRect.top),
            floatArrayOf(cropRect.right, cropRect.bottom),
            floatArrayOf(cropRect.left, cropRect.bottom)
        )
        for ((x, y) in corners) {
            canvas.drawRect(RectF(x - hs, y - hs, x + hs, y + hs), handlePaint)
        }
    }

    /** 用四个矩形把裁剪框外区域涂暗 */
    private fun drawOutsideMask(canvas: Canvas, w: Float, h: Float) {
        val c = cropRect
        // 上
        if (c.top > 0) canvas.drawRect(0f, 0f, w, c.top, maskPaint)
        // 下
        if (c.bottom < h) canvas.drawRect(0f, c.bottom, w, h, maskPaint)
        // 左
        if (c.left > 0) canvas.drawRect(0f, c.top, c.left, c.bottom, maskPaint)
        // 右
        if (c.right < w) canvas.drawRect(c.right, c.top, w, c.bottom, maskPaint)
    }

    private fun drawGrid(canvas: Canvas, r: RectF) {
        val w = r.width()
        val h = r.height()
        canvas.drawLine(r.left + w / 3f, r.top, r.left + w / 3f, r.bottom, gridPaint)
        canvas.drawLine(r.left + w * 2 / 3f, r.top, r.left + w * 2 / 3f, r.bottom, gridPaint)
        canvas.drawLine(r.left, r.top + h / 3f, r.right, r.top + h / 3f, gridPaint)
        canvas.drawLine(r.left, r.top + h * 2 / 3f, r.right, r.top + h * 2 / 3f, gridPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = src ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = MODE_NONE
                lastX = event.x
                lastY = event.y
                downRect.set(cropRect)
                downMatrix.set(matrix)

                val handle = hitHandle(event.x, event.y)
                if (handle != HANDLE_NONE) {
                    mode = MODE_RESIZE_CROP
                    activeHandle = handle
                } else if (cropRect.contains(event.x, event.y)) {
                    mode = MODE_DRAG_CROP
                } else {
                    mode = MODE_DRAG_IMAGE
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    mode = MODE_PINCH
                    val d = dist(event)
                    downPointerDist = if (d > 1f) d else 1f
                    downRect.set(cropRect)
                    downMatrix.set(matrix)
                    downScale = currentScale()
                    downFocusX = (event.getX(0) + event.getX(1)) / 2f
                    downFocusY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_DRAG_IMAGE -> {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        lastX = event.x
                        lastY = event.y
                        clampImageCover()
                        invalidate()
                    }
                    MODE_DRAG_CROP -> {
                        var nx = cropRect.left + (event.x - lastX)
                        var ny = cropRect.top + (event.y - lastY)
                        val side = cropRect.width()
                        // 限制在图片可视区域内（safeCoerce 防空区间浮点误差崩溃）
                        val imgRect = imageViewRect()
                        if (side <= imgRect.width() && side <= imgRect.height()) {
                            nx = nx.safeCoerce(maxOf(0f, imgRect.left), minOf(width.toFloat() - side, imgRect.right - side))
                            ny = ny.safeCoerce(maxOf(0f, imgRect.top), minOf(height.toFloat() - side, imgRect.bottom - side))
                        }
                        cropRect.set(nx, ny, nx + side, ny + side)
                        lastX = event.x
                        lastY = event.y
                        invalidate()
                    }
                    MODE_RESIZE_CROP -> resizeCrop(event.x, event.y)
                    MODE_PINCH -> {
                        if (event.pointerCount == 2) {
                            val d = dist(event)
                            val scale = downScale * (d / downPointerDist)
                            // 限制缩放范围
                            val clamped = scale.coerceIn(0.4f, 8f)
                            applyZoom(downFocusX, downFocusY, clamped, downMatrix, downRect)
                            invalidate()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 双指结束回落到单指拖动模式；完全抬起后复位
                if (mode == MODE_PINCH && event.pointerCount >= 2) {
                    // 还有一根手指在屏幕上
                    mode = MODE_DRAG_IMAGE
                    lastX = event.x
                    lastY = event.y
                } else {
                    mode = MODE_NONE
                }
                activeHandle = HANDLE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    /** 命中裁剪框手柄 */
    private fun hitHandle(x: Float, y: Float): Int {
        val t = dpf(28f)
        val r = cropRect
        val pts = arrayOf(
            intArrayOf(HANDLE_LT, if (x >= r.left - t && x <= r.left + t && y >= r.top - t && y <= r.top + t) 1 else 0),
            intArrayOf(HANDLE_RT, if (x >= r.right - t && x <= r.right + t && y >= r.top - t && y <= r.top + t) 1 else 0),
            intArrayOf(HANDLE_RB, if (x >= r.right - t && x <= r.right + t && y >= r.bottom - t && y <= r.bottom + t) 1 else 0),
            intArrayOf(HANDLE_LB, if (x >= r.left - t && x <= r.left + t && y >= r.bottom - t && y <= r.bottom + t) 1 else 0),
            intArrayOf(HANDLE_T, if (x >= r.left && x <= r.right && y >= r.top - t && y <= r.top + t) 1 else 0),
            intArrayOf(HANDLE_R, if (x >= r.right - t && x <= r.right + t && y >= r.top && y <= r.bottom) 1 else 0),
            intArrayOf(HANDLE_B, if (x >= r.left && x <= r.right && y >= r.bottom - t && y <= r.bottom + t) 1 else 0),
            intArrayOf(HANDLE_L, if (x >= r.left - t && x <= r.left + t && y >= r.top && y <= r.bottom) 1 else 0)
        )
        for ((id, hit) in pts) {
            if (hit == 1) return id
        }
        return HANDLE_NONE
    }

    /** 调整裁剪框大小（保持 1:1） */
    private fun resizeCrop(x: Float, y: Float) {
        val r = cropRect
        val imgRect = imageViewRect()
        val minSide = dpf(80f)
        var left = r.left
        var top = r.top
        var right = r.right
        var bottom = r.bottom

        when (activeHandle) {
            HANDLE_LT -> { left = x; top = y }
            HANDLE_RT -> { right = x; top = y }
            HANDLE_RB -> { right = x; bottom = y }
            HANDLE_LB -> { left = x; bottom = y }
            HANDLE_T -> top = y
            HANDLE_R -> right = x
            HANDLE_B -> bottom = y
            HANDLE_L -> left = x
        }

        // 由拖动的"对角线方向"决定边长锚点，保持正方形
        var side = 0f
        when (activeHandle) {
            HANDLE_LT -> {
                val cx = r.right; val cy = r.bottom
                val d = maxOf(abs(x - cx), abs(y - cy))
                side = d.safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                left = cx - side; top = cy - side; right = cx; bottom = cy
            }
            HANDLE_RT -> {
                val cx = r.left; val cy = r.bottom
                val d = maxOf(abs(x - cx), abs(y - cy))
                side = d.safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                right = cx + side; top = cy - side; left = cx; bottom = cy
            }
            HANDLE_RB -> {
                val cx = r.left; val cy = r.top
                val d = maxOf(abs(x - cx), abs(y - cy))
                side = d.safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                right = cx + side; bottom = cy + side; left = cx; top = cy
            }
            HANDLE_LB -> {
                val cx = r.right; val cy = r.top
                val d = maxOf(abs(x - cx), abs(y - cy))
                side = d.safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                left = cx - side; bottom = cy + side; right = cx; top = cy
            }
            HANDLE_T -> {
                side = (r.bottom - y).safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                top = r.bottom - side; bottom = r.bottom
            }
            HANDLE_B -> {
                side = (y - r.top).safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                bottom = r.top + side; top = r.top
            }
            HANDLE_L -> {
                side = (r.right - x).safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                left = r.right - side
            }
            HANDLE_R -> {
                side = (x - r.left).safeCoerce(minSide, minOf(imgRect.width(), imgRect.height()))
                left = r.left
            }
        }

        // 边界约束：裁剪框必须在图片可视区域内
        val maxSide = minOf(imgRect.width(), imgRect.height())
        if (side > maxSide) side = maxSide
        cropRect.set(left, top, left + side, top + side)
        clampCropRectToImage()
        invalidate()
    }

    /** 图片可视区域（view 坐标） */
    private fun imageViewRect(): RectF {
        val bmp = src ?: return RectF()
        val f = FloatArray(9)
        matrix.getValues(f)
        val sx = f[Matrix.MSCALE_X]
        val sy = f[Matrix.MSCALE_Y]
        val dx = f[Matrix.MTRANS_X]
        val dy = f[Matrix.MTRANS_Y]
        return RectF(dx, dy, dx + bmp.width * sx, dy + bmp.height * sy)
    }

    /** 裁剪框整体不越出图片可视区域（用于拖动时），并保证仍完整可见 */
    private fun clampCropRectToImage() {
        val imgRect = imageViewRect()
        val side = cropRect.width()
        if (side <= 0f) return
        // 合法活动范围 = 图片区域 ∩ 视图区域
        val minX = maxOf(0f, imgRect.left)
        val maxX = minOf(width.toFloat() - side, imgRect.right - side)
        val minY = maxOf(0f, imgRect.top)
        val maxY = minOf(height.toFloat() - side, imgRect.bottom - side)
        if (maxX < minX || maxY < minY) return // 图片过小，保持原位置由覆盖约束保证
        var l = cropRect.left.coerceIn(minX, maxX)
        var t = cropRect.top.coerceIn(minY, maxY)
        cropRect.set(l, t, l + side, t + side)
    }

    /** 缩放：以焦点为中心，基于 down 状态矩阵重算 */
    private fun applyZoom(fx: Float, fy: Float, scale: Float, base: Matrix, baseRect: RectF) {
        matrix.set(base)
        // 焦点对应的图片坐标（基于 base 矩阵）
        val f = FloatArray(9)
        base.getValues(f)
        val sx = f[Matrix.MSCALE_X]
        val sy = f[Matrix.MSCALE_Y]
        val dx = f[Matrix.MTRANS_X]
        val dy = f[Matrix.MTRANS_Y]
        val imgX = (fx - dx) / sx
        val imgY = (fy - dy) / sy

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(fx - imgX * scale, fy - imgY * scale)

        // 确保图片覆盖裁剪框区域：缩放后若图片小于裁剪框则回退
        val after = imageViewRect()
        val needW = baseRect.width()
        val needH = baseRect.height()
        if (after.width() < needW * 0.999f || after.height() < needH * 0.999f) {
            // 最小缩放 = 让图片至少覆盖裁剪框
            val sw = (src?.width ?: 1).toFloat()
            val sh = (src?.height ?: 1).toFloat()
            val minScale = maxOf(needW / sw, needH / sh)
            matrix.reset()
            matrix.postScale(minScale, minScale)
            matrix.postTranslate(fx - imgX * minScale, fy - imgY * minScale)
        }
        // 平移边界：图片区域必须盖住裁剪框
        clampImageCover()
    }

    /** 平移/缩放后保证图片覆盖裁剪框（图片边缘不越过裁剪框中心区域） */
    private fun clampImageCover() {
        val imgRect = imageViewRect()
        val r = cropRect
        // 图片不能比裁剪框小（应已保证），只约束平移使裁剪框在图片内
        val maxDX = imgRect.right - r.right
        val minDX = imgRect.left - r.left
        val maxDY = imgRect.bottom - r.bottom
        val minDY = imgRect.top - r.top
        // 裁剪框必须完全落在图片内：imgRect 需包裹 cropRect
        val f = FloatArray(9)
        matrix.getValues(f)
        var dx = f[Matrix.MTRANS_X]
        var dy = f[Matrix.MTRANS_Y]
        if (minDX > 0 || maxDX < 0) {
            // 需要整体平移使图片包裹裁剪框
            dx -= when {
                minDX > 0 -> minDX
                else -> maxDX
            }
        }
        if (minDY > 0 || maxDY < 0) {
            dy -= when {
                minDY > 0 -> minDY
                else -> maxDY
            }
        }
        f[Matrix.MTRANS_X] = dx
        f[Matrix.MTRANS_Y] = dy
        matrix.setValues(f)
    }

    /** 输出裁剪结果：裁剪框区域 -> 1:1 正方形 Bitmap */
    fun cropBitmap(): Bitmap? {
        val bmp = src ?: return null
        val f = FloatArray(9)
        matrix.getValues(f)
        val sx = f[Matrix.MSCALE_X]
        val sy = f[Matrix.MSCALE_Y]
        val dx = f[Matrix.MTRANS_X]
        val dy = f[Matrix.MTRANS_Y]

        // 裁剪框的图片坐标
        val imgLeft = (cropRect.left - dx) / sx
        val imgTop = (cropRect.top - dy) / sy
        val imgSide = cropRect.width() / sx

        // 越界保护 + 整数化（向下取整，防止 createBitmap 越界）
        val bmpW = bmp.width
        val bmpH = bmp.height
        var cLeft = (imgLeft.coerceIn(0f, bmpW.toFloat() - 1f)).toInt()
        var cTop = (imgTop.coerceIn(0f, bmpH.toFloat() - 1f)).toInt()
        var side = (imgSide.coerceIn(1f, bmpW.toFloat())).toInt()
        side = side.coerceAtMost(bmpW - cLeft)
        side = side.coerceAtMost(bmpH - cTop)
        if (side <= 0) return null

        return Bitmap.createBitmap(bmp, cLeft, cTop, side, side)
    }

    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}
