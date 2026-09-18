package io.github.aixtin.nyral

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// v8.6 取证版已清版(2026-09-18 全面扫描): scrollVerticallyBy/onLayoutChildren 的
// 每帧日志与 range 计算(12ms 节流仍高频)在长会话/键盘动画期间拖慢主线程。
// 类壳保留(MainActivity 引用不变), 行为回归原生 LinearLayoutManager。
class NyralLayoutManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes)
