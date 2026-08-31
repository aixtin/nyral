package io.github.aixtin.droidagent

import android.view.Choreographer

/** 帧驱动打字机回调接口 */
interface TypewriterTickable {
    fun tickFrame(frameNs: Long): Boolean
}

/** 全局打字机中心: 单个 Choreographer 帧回调驱动所有气泡, 全部完成自动停 */
object TypewriterCenter {
    private val holders = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TypewriterTickable, Boolean>())
    private var callbackPosted = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            callbackPosted = false
            var any = false
            val it = holders.iterator()
            while (it.hasNext()) {
                if (it.next().tickFrame(frameTimeNanos)) any = true
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
