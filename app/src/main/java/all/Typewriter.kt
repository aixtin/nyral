package io.github.aixtin.nyral

import android.content.Context
import android.provider.Settings
import android.view.Choreographer

/** 帧驱动打字机回调接口 */
interface TypewriterTickable {
    fun tickFrame(frameNs: Long): Boolean
}

/** 全局打字机中心: 单个 Choreographer 帧回调驱动所有气泡, 全部完成自动停。
 *  慢放联动(09-24): 跟随系统 animator_duration_scale, 把真实帧间隔按慢放倍数缩放成
 *  "虚拟时间"再驱动打字机, 慢放开关开启后气泡打字动画与系统属性动画同步变慢, 便于逐帧排查
 *  slowMul 现为全局动画慢放倍数: 状态行轮播/emoji帧动画/语音气泡动画等自驱动动画点也读它 */
object TypewriterCenter {
    private val holders = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TypewriterTickable, Boolean>())
    private var callbackPosted = false
    private var slowMul = 1.0
    private var lastRealNs = 0L
    private var virtNs = 0L
    private var dbgCnt = 0L

    /** 启动时用系统当前 animator_duration_scale 初始化慢放倍数(免权限读系统值) */
    fun initFromSystem(ctx: Context) {
        slowMul = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .toDouble().coerceAtLeast(1.0)
        android.util.Log.d("SlowDbg", "initFromSystem slowMul=" + slowMul)
    }

    /** 慢放开关切换时同步倍数: 1.0=正常速度, >=2 视为慢放 */
    fun setSlowMul(mul: Double) {
        slowMul = mul.coerceAtLeast(1.0)
        android.util.Log.d("SlowDbg", "setSlowMul slowMul=" + slowMul)
    }

    /** 当前慢放倍数(stepBlock 等按需读取) */
    fun slowMul(): Double = slowMul

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            callbackPosted = false
            // 虚拟时间: 真实帧间隔按慢放倍数缩放后累计(慢放 10x = 打字机时间走 1/10)
            if (lastRealNs == 0L) {
                lastRealNs = frameTimeNanos
                virtNs = frameTimeNanos
            } else {
                val realDelta = frameTimeNanos - lastRealNs
                lastRealNs = frameTimeNanos
                virtNs += (realDelta / slowMul).toLong()
            }
            if (dbgCnt % 60 == 0L) {
                android.util.Log.d("SlowDbg", "doFrame mul=" + slowMul)
            }
            dbgCnt++
            var any = false
            val it = holders.iterator()
            while (it.hasNext()) {
                if (it.next().tickFrame(virtNs)) any = true
            }
            if (any) post()
        }
    }
    fun register(h: TypewriterTickable) {
        holders.add(h)
        post()
    }
    fun unregister(h: TypewriterTickable) {
        holders.remove(h)
    }
    private fun post() {
        if (!callbackPosted && holders.isNotEmpty()) {
            callbackPosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }
}
