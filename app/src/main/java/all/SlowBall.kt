package io.github.aixtin.nyral

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.TimeUnit

/** 全局慢放悬浮球(2026-09-24): 一键切换 animator_duration_scale(10x/1x) 并同步
 *  TypewriterCenter 慢放倍数, 免进设置页; 位置可拖动记忆, 长按关闭。
 *  慢放开关=系统全局动画缩放, 故悬浮球切换对所有自研动画点(Typewriter/状态行轮播/
 *  emoji帧动画/语音气泡动画)与系统属性动画同时生效 */
object SlowBall {
    private const val PREFS = "slow_ball"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val ON_COLOR = 0xE6FF8800.toInt()   // 慢放中: 橙
    private const val OFF_COLOR = 0xCC555555.toInt()  // 正常: 灰

    @Volatile private var wm: WindowManager? = null
    @Volatile private var ball: View? = null
    @Volatile private var showing = false

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    internal fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 当前是否处于慢放(系统 animator_duration_scale >= 2 视为已开) */
    private fun animScaleOn(ctx: Context): Boolean =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) >= 2f

    /** 显示悬浮球(右上角默认位, 位置记忆)。无悬浮窗权限时 root 补齐, 仍失败返回 false */
    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context): Boolean {
        hide()
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            if (!RootCheck.isGranted()) return false
            RootCheck.grantSelf(app)   // appops set SYSTEM_ALERT_WINDOW allow
            SystemClock.sleep(600)
            if (!Settings.canDrawOverlays(app)) return false
        }
        val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(app).apply {
            text = if (animScaleOn(app)) app.getString(R.string.slow_ball_on)
            else app.getString(R.string.slow_ball_off)
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(app, 24f).toFloat()
                setColor(if (animScaleOn(app)) ON_COLOR else OFF_COLOR)
            }
            setOnTouchListener(BallTouch(app))
            setOnLongClickListener {
                hide()
                setEnabled(app, false)
                Toast.makeText(app, app.getString(R.string.slow_ball_closed), Toast.LENGTH_SHORT).show()
                true
            }
        }
        val size = dp(app, 48f)
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lp.x = prefs.getInt(KEY_X, dp(app, 16f))
        lp.y = prefs.getInt(KEY_Y, dp(app, 220f))
        w.addView(tv, lp)
        wm = w; ball = tv; showing = true
        return true
    }

    fun hide() {
        if (showing) {
            try { wm?.removeView(ball) } catch (_: Exception) {}
            showing = false; wm = null; ball = null
        }
    }

    /** 切换慢放: root 写系统值 + 同步打字机倍数, 成功更新球外观 */
    private fun toggle(ctx: Context) {
        Thread {
            val on = animScaleOn(ctx)
            val v = if (on) 1f else 10f
            val ok = try {
                val p = ProcessBuilder("su", "-c", "settings put global animator_duration_scale $v")
                    .redirectErrorStream(true).start()
                if (p.waitFor(4000, TimeUnit.MILLISECONDS)) p.exitValue() == 0
                else { p.destroyForcibly(); false }
            } catch (e: Exception) { false }
            Handler(Looper.getMainLooper()).post {
                if (ok) {
                    TypewriterCenter.setSlowMul(if (on) 1.0 else 10.0)
                    val b = ball ?: return@post
                    (b.background as? GradientDrawable)?.setColor(if (!on) ON_COLOR else OFF_COLOR)
                    (b as? TextView)?.text = if (!on) ctx.getString(R.string.slow_ball_on)
                    else ctx.getString(R.string.slow_ball_off)
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_slow_ball_fail), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 拖动(移动)与短按(切换)分离 */
    private class BallTouch(private val ctx: Context) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var rawX = 0f; private var rawY = 0f
        private var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    rawX = lp.x.toFloat(); rawY = lp.y.toFloat()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && Math.abs(dx) + Math.abs(dy) > dp(ctx, 12f)) moved = true
                    if (moved) {
                        lp.x = (rawX + dx).toInt(); lp.y = (rawY + dy).toInt()
                        w.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    } else {
                        toggle(ctx)
                    }
                }
            }
            return true
        }
    }

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
