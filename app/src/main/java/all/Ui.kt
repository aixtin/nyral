package io.github.aixtin.nyral

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 全局 UI 规范（主页与设置系页面共用，消除割裂感）
 * 视觉基调与 MainActivity 对齐：
 * - 背景 #F7F7F8（浅灰）
 * - 卡片/标题栏白色、主色 #0B93F6、正文 #1A1A1A、次要 #999999
 * - 输入框圆角浅灰底 #EFEFF1、主按钮圆角蓝底白字
 */
object Ui {
    // 语义色随当前主题动态刷新（启动/切换主题时由 Ui.applyTheme 统一赋值）
    @Volatile var BG: Int = DefaultTheme.bg
    @Volatile var PRIMARY: Int = DefaultTheme.primary
    @Volatile var PRIMARY_LIGHT: Int = DefaultTheme.primaryLight
    @Volatile var TEXT: Int = DefaultTheme.text
    @Volatile var SUB: Int = DefaultTheme.sub
    @Volatile var DIVIDER: Int = DefaultTheme.divider
    @Volatile var INPUT_BG: Int = DefaultTheme.inputBg
    @Volatile var STROKE: Int = DefaultTheme.stroke
    @Volatile var DANGER: Int = DefaultTheme.danger
    @Volatile var DANGER_LIGHT: Int = DefaultTheme.dangerLight
    @Volatile var SURFACE: Int = DefaultTheme.surface
    /** 选中圆点绿（勾选标记统一用绿色点，LED 荧光绿） */
    val GREEN = 0xFF00E676.toInt()

    /** 应用主题：刷新全局语义色（MainActivity 启动/切换主题时调用一次） */
    fun applyTheme(t: AppTheme) {
        BG = t.bg
        PRIMARY = t.primary
        PRIMARY_LIGHT = t.primaryLight
        TEXT = t.text
        SUB = t.sub
        DIVIDER = t.divider
        INPUT_BG = t.inputBg
        STROKE = t.stroke
        DANGER = t.danger
        DANGER_LIGHT = t.dangerLight
        SURFACE = t.surface
    }

    /**
     * 通用按压回弹：按下 scale 缩小约 0.9，松开 Overshoot 回弹 1.0。
     * 挂到任意可点击 View 上即可获得一致的按压缩放反馈（全局动画规范 A/B 范围）。
     */
    fun press(v: View) {
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
            }
            false
        }
    }

    /** 弹窗果冻展开：锚点按屏幕上下半区自适应，pivot 指向锚点边缘，Overshoot 弹性放大 */
    fun jellyShow(v: View, overshoot: Float = 2.2f, startScale: Float = 0.15f, duration: Long = 360) {
        v.post {
            val screenH = v.resources.displayMetrics.heightPixels
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val centerY = loc[1] + v.height / 2f
            v.pivotX = v.width / 2f
            v.pivotY = if (centerY < screenH / 2f) 0f else v.height.toFloat()
            v.scaleX = startScale
            v.scaleY = startScale
            v.alpha = 0f
            v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(duration)
                .setInterpolator(OvershootInterpolator(overshoot))
                .start()
        }
    }

    fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    /** 状态栏：底色随主题 SURFACE，图标明暗随底亮度自适应（与主页一致） */
    fun statusBar(a: Activity) {
        a.window.statusBarColor = Ui.SURFACE
        a.window.navigationBarColor = Ui.SURFACE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dark = (Color.red(Ui.SURFACE) + Color.green(Ui.SURFACE) + Color.blue(Ui.SURFACE)) / 3 < 128
            var vis = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26 && !dark) vis = vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            a.window.decorView.systemUiVisibility = vis
        }
    }

    fun rounded(color: Int, radiusDp: Int, a: Activity): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(a, radiusDp).toFloat() }

    /** 圆角描边框：无填充，仅 stroke */
    fun roundedBorder(strokeColor: Int, radiusDp: Int, strokeDp: Int, a: Activity): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(a, radiusDp).toFloat()
            setStroke(dp(a, strokeDp), strokeColor)
        }

    /** 行内点缀圆点 span：LED 灯效果 = 亮绿核心 + 半透明光晕，以文字行中线垂直居中（替代 AbsoluteSizeSpan 的基线偏移导致的点不居中） */
    class CenterDotSpan(private val color: Int, private val sizePx: Int, private val gapPx: Int) : android.text.style.ReplacementSpan() {
        private val coreR = sizePx / 2f
        private val glowR = coreR * 1.5f
        private val glowColor = android.graphics.Color.argb(110,
            android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int = (glowR * 2f).toInt() + gapPx
        override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
            val cy = (top + bottom) / 2f
            val cx = x + glowR
            val oldColor = paint.color
            paint.isAntiAlias = true
            // 光晕（先画）
            paint.color = glowColor
            canvas.drawCircle(cx, cy, glowR, paint)
            // 核心（后画盖在光晕上）
            paint.color = color
            canvas.drawCircle(cx, cy, coreR, paint)
            paint.color = oldColor
        }
    }

    /** 行内垂直居中的实心圆点 span 工厂（sizeDp 为圆点直径 dp） */
    fun centerDot(a: Activity, color: Int, sizeDp: Int): CenterDotSpan = CenterDotSpan(color, dp(a, sizeDp), dp(a, 1))

    /** Lucide x 图标（矢量描边 → 指定颜色/尺寸 BitmapDrawable，全局替换 ×/✕） */
    fun lucideX(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_x, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide square 图标（描边圆角方块，停止生成语义；用于停止按钮） */
    fun lucideSquare(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_square, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide trash-2 图标（垃圾桶，删除语义；用于移除模型/删除附件等） */
    fun lucideTrash(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_trash, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide eye 图标（查看语义） */
    fun lucideEye(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_eye, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide download 图标（导出语义） */
    fun lucideDownload(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_download, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide refresh-cw 图标（刷新语义） */
    fun lucideRefresh(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_refresh_cw, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide badge-check 图标（完成勾，替换 ✓ 文字；行动轨道工具完成态） */
    fun lucideBadgeCheck(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_badge_check, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide wrench 图标（工具珠子，替换 🔧；行动轨道工具块） */
    fun lucideWrench(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_wrench, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide brain 图标（思考珠子，替换 💭；行动轨道思考块） */
    fun lucideBrain(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_brain, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** Lucide route 图标（流程终点：两端圆点+折线路径；行动轨道末尾结尾标记） */
    fun lucideRoute(a: Activity, color: Int, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
        val px = dp(a, sizeDp)
        val src = a.resources.getDrawable(R.drawable.lucide_route, a.theme).mutate()
        src.setTint(color)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(c)
        return android.graphics.drawable.BitmapDrawable(a.resources, bmp)
    }

    /** 页面根布局：浅灰底竖向 */
    fun pageRoot(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        // targetSdk 36 强制 edge-to-edge(09-24): 根布局消费状态栏 inset, 顶栏不再与系统状态栏叠加
        if (Build.VERSION.SDK_INT >= 35) {
            setOnApplyWindowInsetsListener { v, insets ->
                val top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                v.setPadding(0, top, 0, 0)
                insets
            }
        }
    }

    /** 自绘白色标题栏：左返回 + 标题 + 可选右侧操作 */
    fun titleBar(a: Activity, title: String, onBack: () -> Unit = { a.finish() },
                 right: ((LinearLayout) -> Unit)? = null): LinearLayout =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(a, 4), dp(a, 8), dp(a, 12), dp(a, 8))
            setBackgroundColor(Ui.SURFACE)
            addView(TextView(a).apply {
                text = "‹"
                textSize = 26f
                setTextColor(TEXT)
                setPadding(dp(a, 12), dp(a, 0), dp(a, 14), dp(a, 0))
                setOnClickListener { onBack() }
            })
            addView(TextView(a).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            right?.invoke(this)
        }

    /** 分组标题（灰底上的小灰字） */
    fun groupLabel(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 13f
        setTextColor(SUB)
        setPadding(dp(a, 4), dp(a, 14), dp(a, 4), dp(a, 6))
    }

    /** 1px 细分隔线 */
    fun divider(a: Activity): View = View(a).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1))
    }

    /** 白色圆角卡片容器（默认 16dp 圆角，承载一组列表项） */
    fun card(a: Activity, radiusDp: Int = 16): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.SURFACE)
        background = rounded(Ui.SURFACE, radiusDp, a)
    }

    /** 列表项左侧圆形/圆角图标：首字 + 主色渐变底（与模型配置页图标一致） */
    fun iconBadge(a: Activity, ch: String, seed: Int = 0, sizeDp: Int = 40): TextView =
        TextView(a).apply {
            text = if (ch.isEmpty()) "" else String(Character.toChars(ch.codePointAt(0)))
            textSize = 16f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            background = Ui.roundedBorder(Ui.PRIMARY, 10, 2, a)
            layoutParams = LinearLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp))
        }


    fun iconBadgeRes(a: Activity, iconRes: Int, seed: Int = 0, sizeDp: Int = 40): ImageView =
        ImageView(a).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(a, if (sizeDp <= 40) 9 else 11)
            setPadding(pad, pad, pad, pad)
            setColorFilter(Ui.TEXT)
            background = Ui.roundedBorder(Ui.PRIMARY, 10, 2, a)
            layoutParams = LinearLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp))
        }
    /** 列表项标题文字 */
    fun itemTitle(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 16f
        setTextColor(TEXT)
    }

    /** 列表项副标题文字 */
    fun itemSub(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 12f
        setTextColor(SUB)
        setPadding(0, dp(a, 3), 0, 0)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** 右侧箭头 › */
    fun arrow(a: Activity): TextView = TextView(a).apply {
        text = "›"
        textSize = 24f
        setTextColor(Ui.SUB)
        setPadding(dp(a, 10), 0, 0, 0)
    }

    /** 圆角浅灰底输入框（与主页输入框一致） */
    fun input(a: Activity, hint: String): EditText = EditText(a).apply {
        this.hint = hint
        textSize = 15f
        setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
        background = rounded(INPUT_BG, 12, a)
        setTextColor(TEXT)
        setHintTextColor(Ui.SUB)
    }

    /** 圆角主色按钮（白字） */
    fun primaryBtn(a: Activity, text: String, onClick: () -> Unit): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 15f
            isClickable = true
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(PRIMARY, 14, a)
            setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12))
            press(this)
            setOnClickListener { onClick() }
        }

    /** 圆角小按钮（浅蓝底蓝字，用于"拉取/+"等行内操作） */
    fun lightBtn(a: Activity, text: String, onClick: () -> Unit): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 13f
            isClickable = true
            setTextColor(PRIMARY)
            gravity = Gravity.CENTER
            background = rounded(PRIMARY_LIGHT, 12, a)
            setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8))
            press(this)
            setOnClickListener { onClick() }
        }

    /** 圆角浅红按钮（用于删除） */
    fun dangerBtn(a: Activity, text: String, onClick: () -> Unit): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 15f
            isClickable = true
            setTextColor(DANGER)
            gravity = Gravity.CENTER
            background = rounded(DANGER_LIGHT, 14, a)
            setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12))
            press(this)
            setOnClickListener { onClick() }
        }

    /** 表单小标签 */
    fun fieldLabel(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ui.SUB)
        setPadding(dp(a, 2), dp(a, 10), 0, dp(a, 4))
    }

    /** 辅助说明文字 */
    fun hint(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 11f
        setTextColor(Ui.SUB)
        setPadding(dp(a, 2), dp(a, 3), 0, 0)
    }

    // ---- 风格化弹窗（与页面同视觉：透明遮罩 + 白色圆角卡片） ----

    /**
     * 创建居中圆角卡片弹窗，返回 (dialog, 内容容器)；容器已含标题与分隔线
     * @param maxHeightRatio 大于 0 时，弹窗整体高度限制为屏幕高度的该比例，内容超长可滚动，
     *                       避免长内容把按钮顶出屏幕（键盘弹出时窗口同步压缩，不会被遮挡）
     */
    fun dialog(a: Activity, title: String, maxHeightRatio: Double = 0.0, jellyOvershoot: Float = 2.2f, animate: Boolean = false): Pair<Dialog, LinearLayout> {
        val d = Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        val w = (a.resources.displayMetrics.widthPixels * 0.86).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 18), dp(a, 20), dp(a, 16))
            background = rounded(Ui.SURFACE, 18, a)
        }
        // animTarget: 弹窗缩放动画目标。maxHeightRatio>0 时圆角白卡归属滚动视口 sv, 动画需作用于 sv
        var animTarget: View = box
        box.addView(TextView(a).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(divider(a).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(a, 12)
        })

        // 键盘弹出时压缩窗口，避免输入框被遮挡
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        if (maxHeightRatio > 0.0) {
            val maxH = (a.resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
            // 窗口高度不再固定为 maxH: 内容少时收缩到内容高度(避免下方大片空白),
            // 内容超长时由 ScrollView 封顶 maxH 内部滚动
            // 圆角白卡背景固定在滚动视口 sv 上(高度=min(内容,maxH)), box 改透明内容容器:
            // 避免内容超长滚动时 box 顶部/底部圆角被滚出视口截成直角(ask_user 多选项弹窗 bug)
            val sv = MaxHeightScrollView(a, maxH).apply {
                background = rounded(Ui.SURFACE, 18, a)
                clipToOutline = true
            }
            box.background = null
            animTarget = sv
            sv.addView(box, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            d.setContentView(sv)
            d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            d.window?.setGravity(android.view.Gravity.CENTER)
        } else {
            d.setContentView(box)
            d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            d.window?.setGravity(android.view.Gravity.CENTER)
        }
        d.setOnShowListener {
            if (animate) jellyShow(animTarget, overshoot = jellyOvershoot)
            else animTarget.post { animTarget.scaleX = 1f; animTarget.scaleY = 1f; animTarget.alpha = 1f }
        }
        return d to box
    }

    /**
     * 三明治结构弹窗：标题固定顶部、内容区（weight=1 可滚动）居中、底部按钮区固定。
     * 键盘弹出时窗口压缩只吃掉内容区，标题与按钮不会跟着动。
     * 内容区内的 EditText 获得焦点时自动滚动到底部，避免输入框被键盘遮挡。
     * @return (dialog, 内容容器, 底部按钮容器)
     */
    fun dialogFixed(a: Activity, title: String, maxHeightRatio: Double, jellyOvershoot: Float = 2.2f, animate: Boolean = false): Triple<Dialog, LinearLayout, LinearLayout> {
        val d = Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        val w = (a.resources.displayMetrics.widthPixels * 0.86).toInt()
        val maxH = (a.resources.displayMetrics.heightPixels * maxHeightRatio).toInt()

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 18), dp(a, 20), dp(a, 16))
            background = rounded(Ui.SURFACE, 18, a)
        }
        root.addView(TextView(a).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(divider(a).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(a, 12)
        })

        // 中间内容区：占满剩余空间，可滚动
        val contentBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(a).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        sv.addView(contentBox, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部按钮区：固定不动
        val bottomBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bottomBox)

        // 键盘弹出时窗口压缩，只吃掉内容区高度
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // 内容区内的 EditText 获得焦点时滚动使其可见
        // 注意不能用 fullScroll(FOCUS_DOWN): 它会把焦点移交给最后一个可聚焦控件,
        // 多输入框表单(如 SSH 连接编辑)里表现为"点上面的框光标跳到最下面的框"
        root.post {
            fun reg(v: View) {
                if (v is EditText) {
                    v.setOnFocusChangeListener { view, hasFocus ->
                        if (hasFocus) sv.post {
                            // requestRectangleOnScreen: 沿祖先链最小幅度滚动使该控件可见, 不碰焦点
                            view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), true)
                        }
                    }
                } else if (v is ViewGroup) {
                    for (i in 0 until v.childCount) reg(v.getChildAt(i))
                }
            }
            reg(contentBox)
        }

        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(w, maxH)
        d.window?.setGravity(android.view.Gravity.CENTER)
        d.setOnShowListener {
            if (animate) jellyShow(root, overshoot = jellyOvershoot)
            else root.post { root.scaleX = 1f; root.scaleY = 1f; root.alpha = 1f }
        }
        return Triple(d, contentBox, bottomBox)
    }

    /** 弹窗正文文本（水平居中） */
    fun dialogText(a: Activity, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 14f
        setTextColor(SUB)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(a, 14), 0, dp(a, 18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 弹窗浅灰底取消按钮 */
    fun dialogCancelBtn(a: Activity, text: String, onClick: () -> Unit): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 15f
            isClickable = true
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = rounded(INPUT_BG, 14, a)
            setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12))
            press(this)
            setOnClickListener { onClick() }
        }

    /** 自绘勾选圆点：选中=绿底白●，未选中=浅灰圆底 */
    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
        gravity = Gravity.CENTER
        textSize = 8f
        includeFontPadding = false
        text = "●"
        setTextColor(if (checked) GREEN else SUB)
        layoutParams = LinearLayout.LayoutParams(dp(a, 12), dp(a, 12))
    }

    /** 刷新勾选圆点样式（选中=绿色小实心点，未选中=浅灰小点，视觉同呼吸灯 6dp） */
    fun applyCheck(v: TextView, checked: Boolean, a: Activity) {
        v.text = "●"
        v.setTextColor(if (checked) GREEN else SUB)
    }

    /**
     * 限制最大高度的 ScrollView：内容不超过 maxHeight 时收缩到内容高度(不产生空白)，
     * 内容超过 maxHeight 时封顶该高度并内部滚动。
     */
    class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            val capped = when {
                mode == View.MeasureSpec.UNSPECIFIED ->
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
                size > maxHeight ->
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
                else -> heightMeasureSpec
            }
            super.onMeasure(widthMeasureSpec, capped)
        }
    }
}
