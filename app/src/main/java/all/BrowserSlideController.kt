package io.github.aixtin.nyral

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs


/**
 * 浏览器页右侧跟手滑出手势控制器（镜像 DrawerDragController）：
 * - 关闭态: 双击屏幕右缘 EDGE_DP 内 -> 整页从右往左推入（translationX: +screenW -> 0）
 * - 打开态: 双击屏幕右缘 EDGE_DP 内 -> 整页往右推回（0 -> +screenW）
 *   (2026-10-02 展开/关闭均由右缘左滑改为双击右缘, 避免与表格横滑手势冲突)
 * 与左抽屉(左缘右滑)区域/方向互补, 互不冲突
 */
internal class BrowserSlideController(private val act: MainActivity) {
    private companion object {
        const val EDGE_DP = 48          // 触发区宽度
        const val FLING_VX = 500f       // 吸附速度阈值(px/s)
        const val SNAP_FRAC = 0.5f      // 吸附位置阈值
        const val TAP_MS = 300L         // 双击判定时间窗(ms)
    }
    private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
    private var tracker: android.view.VelocityTracker? = null
    private var mode = 0               // 0=无 1=待开(关闭态右缘按下) 2=待关(打开态左缘按下) 3=汉堡跟手(打开态右缘按下) 4=收汉堡(汉堡开且左缘按下)
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startTrans = 0f
    private val w get() = act.browserPage.root.translationX.coerceAtLeast(0f)
    // 双击右缘展开(2026-10-02): 取代关闭态右缘左滑, 避免与表格横滑手势冲突
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private fun panel() = act.browserPage.root

    fun isDragging() = dragging

    fun onIntercept(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = android.view.VelocityTracker.obtain()
                tracker?.addMovement(ev)
                dragging = false
                downX = ev.rawX
                downY = ev.rawY
                val openNow = act.browserPage.open
                val sw = act.resources.displayMetrics.widthPixels
                // 展开态全屏可滑: 汉堡/浏览器展开后任意位置横滑跟手收(方向不限, 斜率判定保竖滑);
                // 关闭态不再右缘左滑开浏览器(2026-10-02 改双击右缘), 避免与表格横滑冲突;
                // 浏览器开时右缘左滑仍保留展开汉堡入口
                val rightThird = sw * 2f / 3f
                mode = when {
                    act.tokenMask.visibility == View.VISIBLE -> 0
                    // 主页汉堡页(抽屉)打开时浏览器手势让位: 右缘左滑归抽屉关闭, 避免误开浏览器
                    act.drawerOpen -> 0
                    // 汉堡展开: 全屏任意方向跟手收
                    act.browserPage.hamburgerOpen -> 4
                    // 浏览器展开+右缘: 左滑展开汉堡(保留入口), 右滑交给 WebView 内部
                    openNow && ev.rawX >= rightThird -> 3
                    // 浏览器展开: 全屏任意方向跟手收
                    openNow -> 2
                    else -> 0
                }
                // 双击屏幕右缘: 关闭态=展开浏览器, 展开态=关闭浏览器(取代右缘左滑, 避免与表格横滑冲突)
                if (ev.rawX >= sw - EDGE_DP &&
                    act.tokenMask.visibility != View.VISIBLE && !act.drawerOpen && !act.browserPage.hamburgerOpen &&
                    (mode == 0 || openNow)) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val tapSlop = slop * 2
                    if (now - lastTapTime in 1..TAP_MS &&
                        abs(ev.rawX - lastTapX) < tapSlop && abs(ev.rawY - lastTapY) < tapSlop) {
                        lastTapTime = 0   // 消费本次双击, 避免连续触发
                        dragging = true
                        if (openNow) act.browserPage.close() else act.browserPage.open()
                        return true       // 拦截第二击, 不传给页面
                    }
                    lastTapTime = now
                    lastTapX = ev.rawX
                    lastTapY = ev.rawY
                }
                startTrans = if (mode == 3 || mode == 4) act.browserPage.hamburgerPanel.translationX
                              else panel().translationX
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode != 0 && !dragging) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    // 方向锁定: 未开=左滑展开, 已开=右滑收回(反方向, 同向滑动不接管)
                    val dirOk = when (mode) {
                        3 -> dx < 0
                        2, 4 -> dx > 0
                        else -> false
                    }
                    // 放宽水平斜率(0.7): 与左侧抽屉同策略, 斜向滑动即锁定浏览器/汉堡拖拽, 防带动下层消息列表
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 0.7 && dirOk) {
                        dragging = true
                        if (mode == 3 || mode == 4) {
                            act.hamburgerDragBase()
                            // 汉堡面板跟手平移同样缓存纹理: 面板内容首次露出不再逐帧光栅化(防卡顿/闪烁)
                            act.browserPage.hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        }
                        // 跟手平移期间开硬件层: WebView 以纹理平移, 避免露白边闪白
                        act.browserPage.root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        tracker?.addMovement(ev)
                        return true
                    }
                }
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> reset()
        }
        return false
    }

    fun onTouch(ev: MotionEvent): Boolean {
        if (!dragging) return false
        val isBurger = mode == 3 || mode == 4
        val burgerW = act.browserPage.burgerHideX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (isBurger) {
                    // 汉堡跟手: 未开 startTrans=burgerHideX 左滑 dx<0 展开; 已开 startTrans=0 右滑 dx>0 收回(反方向)
                    val dx = ev.rawX - downX
                    val trans = (startTrans + dx).coerceIn(0f, burgerW)
                    act.browserPage.hamburgerPanel.translationX = trans
                    // 跟手实时联动主界面下沉: 与主页右滑抽屉 setMainSink(frac) 同机制(面板滑入即主页下沉, 不再等抬手吸附)
                    val frac = (burgerW - trans) / burgerW
                    act.setMainSink(frac)
                    if (frac > 0.02f) {
                        val hm = act.browserPage.hamburgerMask
                        if (hm.visibility != View.VISIBLE) hm.visibility = View.VISIBLE
                        hm.alpha = frac
                    }
                } else {
                    // 整页跟手: 未开 startTrans=+screenW 左滑 dx<0 展开; 已开 startTrans=0 右滑 dx>0 收回(反方向)
                    val sw = act.resources.displayMetrics.widthPixels
                    val dx = ev.rawX - downX
                    val trans = (startTrans + dx).coerceIn(0f, sw.toFloat())
                    panel().translationX = trans
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                if (isBurger) {
                    val frac = (burgerW - act.browserPage.hamburgerPanel.translationX) / burgerW // 展开比例
                    val open = when {
                        vx > FLING_VX -> false
                        vx < -FLING_VX -> true  // 未开左滑快=展开; 已开拖拽方向为右滑, 此分支不会出现
                        frac > SNAP_FRAC -> true
                        else -> false
                    }
                    snapBurger(open)
                } else {
                    val sw = act.resources.displayMetrics.widthPixels
                    val frac = (sw - panel().translationX) / sw   // 显现比例
                    val open = when {
                        vx > FLING_VX -> false
                        vx < -FLING_VX -> true  // 未开左滑快=展开; 已开拖拽方向为右滑, 此分支不会出现
                        frac > SNAP_FRAC -> true
                        else -> false
                    }
                    snap(open)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isBurger) snapBurger(act.browserPage.hamburgerOpen)
                else snap(act.browserPage.open)
                reset()
            }
        }
        return true
    }

    /** 汉堡面板吸附: 展开动画到 0 / 收起动画回右外 */
    private fun snapBurger(open: Boolean) {
        if (open) act.browserPage.expandHamburger()
        else act.browserPage.collapseHamburger()
    }

    /** 抬手吸附到开/关: 打开时走 browserPage.open()(加载默认页+整页动画), 关闭时走 browserPage.close() */
    private fun snap(open: Boolean) {
        val sw = act.resources.displayMetrics.widthPixels
        if (open) {
            act.browserPage.open()
            return
        }
        val target = sw.toFloat()
        val cur = panel().translationX
        val dist = abs(target - cur)
        val dur = (170 + 130 * (dist / sw)).toLong().coerceIn(150, 300)
        val dec = DecelerateInterpolator(1.3f)
        act.browserPage.open = false
        act.browserPage.resetTakeoverState()
        act.browserPage.onOpenChange?.invoke(false)
        panel().animate().translationX(sw.toFloat()).setDuration(dur).setInterpolator(dec)
            .withEndAction {
                act.browserPage.root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                act.browserPage.hideHighlight()
            }.start()
    }

    /** 返回键/✕ 关闭: 走 BrowserPage.close() */
    internal fun close() { act.browserPage.close() }

    private fun reset() {
        mode = 0
        dragging = false
        tracker?.recycle()
        tracker = null
    }
}
