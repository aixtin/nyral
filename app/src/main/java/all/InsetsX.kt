package all

import android.os.Build
import android.view.WindowInsets

/**
 * WindowInsets.Type 安全封装(2026-11-08)。
 * minSdk 29, 但 WindowInsets.Type.systemBars()/ime()/getInsets(int) 均为 API 30 新增:
 * Android 10(SDK 29)机型上直接调用抛 java.lang.NoSuchMethodError —— 这是 Error 不是 Exception,
 * 现有 try/catch(Exception) 完全拦不住, vivo V2002A(Android 10) 因此启动即闪退。
 * 统一入口: API<30 走已废弃但存在的 systemWindowInsets, IME 无对应值按 0 处理(不崩即降级)。
 */
object InsetsX {

    private const val API_R = 30

    private val hasType: Boolean = Build.VERSION.SDK_INT >= API_R

    fun systemBars(insets: WindowInsets?): android.graphics.Insets = if (insets == null) {
        android.graphics.Insets.NONE
    } else if (hasType) {
        insets.getInsets(WindowInsets.Type.systemBars())
    } else {
        @Suppress("DEPRECATION")
        android.graphics.Insets.of(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom
        )
    }

    /**
     * 键盘高度: API<30 无 ime 类型, 返回 0。
     * 该类机型不靠 insets 让位, 而是 MainActivity 在 initThemeAndWindow 里对 SDK<30 使用
     * SOFT_INPUT_ADJUST_RESIZE, 交系统压缩窗口实现键盘避让(bodyWrap 权重自适应)。
     * 注意: ADJUST_NOTHING 下 getWindowVisibleDisplayFrame 不会反映键盘, 别再走可见区差值方案。
     */
    fun ime(insets: WindowInsets?): android.graphics.Insets = if (insets != null && hasType) {
        insets.getInsets(WindowInsets.Type.ime())
    } else {
        android.graphics.Insets.NONE
    }

    fun imeBottom(insets: WindowInsets?): Int = ime(insets).bottom

    fun systemBarsBottom(insets: WindowInsets?): Int = systemBars(insets).bottom
}
