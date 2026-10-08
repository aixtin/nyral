package io.github.aixtin.nyral

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs


/**
 * 浏览器页手势控制器（2026-10-08 手势规则重定义）：
 * - 浏览器开关: 仅双击屏幕右缘 EDGE_DP 内(关闭态双击推入, 打开态双击推回), 禁用左右滑手势
 * - 汉堡面板: 展开仅走控件(toggleBrowserPanel), 展开态右滑(左→右)跟手收起
 * 与左抽屉(开态左滑收回)区域/方向互补, 互不冲突
 */
internal class BrowserSlideController(private val act: MainActivity) {
    private companion object {
        const val EDGE_DP = 96          // 双击触发区宽度(dp; 原48被当px用实际仅48px过窄, 放宽到96dp)
        const val FLING_VX = 500f       // 吸附速度阈值(px/s)
        const val SNAP_FRAC = 0.5f      // 吸附位置阈值
        const val TAP_MS = 420L         // 双击判定时间窗(ms; 300过严手慢就超时, 放宽到420)
    }
    private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
    private var tracker: android.view.VelocityTracker? = null
    private var mode = 0               // 0=无 4=收汉堡(汉堡开且任意位置右滑)
    private var dragging = false
    private var doubleTapFired = false // 本序列由双击触发: UP 跳过吸附, 防止开/关被反向反转
    private var downX = 0f
    private var downY = 0f
    private var startTrans = 0f
    // 双击右缘开/关(2026-10-02 取代滑动手势避免与表格横滑冲突; 2026-10-08 修复失灵)
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
                doubleTapFired = false
                downX = ev.rawX
                downY = ev.rawY
                val openNow = act.browserPage.open
                val sw = act.resources.displayMetrics.widthPixels
                // 2026-10-08: 浏览器开关只走双击右缘; 汉堡展开只走控件; 唯一滑动手势=汉堡开时全屏右滑收
                mode = when {
                    act.tokenMask.visibility == View.VISIBLE -> 0
                    // 主页抽屉打开时浏览器手势让位
                    act.drawerOpen -> 0
                    // 汉堡展开: 全屏右滑跟手收(左→右关闭)
                    act.browserPage.hamburgerOpen -> 4
                    else -> 0
                }
                // 双击屏幕右缘: 关闭态=展开浏览器, 展开态=关闭浏览器; 汉堡展开时不触发
                if (ev.rawX >= sw - act.dp(EDGE_DP) &&
                    act.tokenMask.visibility != View.VISIBLE && !act.drawerOpen && !act.browserPage.hamburgerOpen) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val tapSlop = act.dp(30).toFloat()
                    if (now - lastTapTime in 1..TAP_MS &&
                        abs(ev.rawX - lastTapX) < tapSlop && abs(ev.rawY - lastTapY) < tapSlop) {
                        lastTapTime = 0   // 消费本次双击, 避免连续触发
                        dragging = true
                        doubleTapFired = true
                        if (openNow) act.browserPage.close() else act.browserPage.open()
                        return true       // 拦截第二击及后续事件, 不传给页面
                    }
                    lastTapTime = now
                    lastTapX = ev.rawX
                    lastTapY = ev.rawY
                }
                startTrans = act.browserPage.hamburgerPanel.translationX
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode != 0 && !dragging) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    // 方向锁定: 汉堡开=右滑收回(左→右), 同向滑动不接管
                    val dirOk = when (mode) {
                        4 -> dx > 0
                        else -> false
                    }
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 0.7 && dirOk) {
                        dragging = true
                        act.hamburgerDragBase()
                        // 汉堡面板跟手平移缓存纹理: 内容首次露出不逐帧光栅化(防卡顿/闪烁)
                        act.browserPage.hamburgerPanel.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
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
        val isBurger = mode == 4
        val burgerW = act.browserPage.burgerHideX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (isBurger) {
                    // 汉堡跟手: 已开 startTrans=0 右滑 dx>0 收回(反方向)
                    val dx = ev.rawX - downX
                    val trans = (startTrans + dx).coerceIn(0f, burgerW)
                    act.browserPage.hamburgerPanel.translationX = trans
                    val frac = (burgerW - trans) / burgerW
                    act.setMainSink(frac)
                    if (frac > 0.02f) {
                        val hm = act.browserPage.hamburgerMask
                        if (hm.visibility != View.VISIBLE) hm.visibility = View.VISIBLE
                        hm.alpha = frac
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                // 双击触发的开/关: 第二击后的 UP 不再吸附, 避免把刚开的又关掉(双击失灵根因)
                if (doubleTapFired) {
                    reset()
                    return true
                }
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                if (isBurger) {
                    val frac = (burgerW - act.browserPage.hamburgerPanel.translationX) / burgerW // 展开比例
                    val open = when {
                        vx > FLING_VX -> false
                        vx < -FLING_VX -> true  // 拖拽方向为右滑, 此分支不会出现
                        frac > SNAP_FRAC -> true
                        else -> false
                    }
                    snapBurger(open)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (doubleTapFired) {
                    reset()
                    return true
                }
                if (isBurger) snapBurger(act.browserPage.hamburgerOpen)
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

    /** 返回键/✕ 关闭: 走 BrowserPage.close() */
    internal fun close() { act.browserPage.close() }

    private fun reset() {
        mode = 0
        dragging = false
        doubleTapFired = false
        tracker?.recycle()
        tracker = null
    }
}
