package io.github.aixtin.nyral

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// v8.4 插桩取证版已清版(2026-09-18 全面扫描): scrollBy/fling/scrollTo 等入口的
// Log.getStackTraceString(全栈抓取)在流式追底/键盘动画期间每帧执行, 是主线程卡顿掉帧
// 的重要因素(疑似长气泡吸底跳跃残留诱因)。类壳保留, 行为回归原生 RecyclerView。
class NyralRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr)
