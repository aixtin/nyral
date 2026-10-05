package io.github.aixtin.nyral

/**
 * scan 坐标/样式判定纯函数(2026-10-05):
 * 与 BrowserPageScan.kt 注入 JS 的 collect/collectFrame 规则一致(Kotlin 对照实现)。
 * sticky/固定排除与视口越界防护为行为规范(对应历史问题5/4), 供 JS 后续修复对照回归。
 */
object BrowserScanRules {

    /** 视口坐标 = 元素矩形位置 + iframe/穿透偏移; 不叠加文档滚动偏移(滚动后重扫即为新视口所见, 与 JS 一致) */
    fun viewportX(rectLeft: Int, offsetX: Int): Int = rectLeft + offsetX

    fun viewportY(rectTop: Int, offsetY: Int): Int = rectTop + offsetY

    /** 尺寸下限: JS collect 中宽或高 < 24 弃采 */
    fun belowMinSize(w: Int, h: Int, min: Int = 24): Boolean = w < min || h < min

    /** 样式排除: visibility=hidden / display=none / opacity=0(与 JS collect 一致) */
    fun excludedByStyle(visibility: String, display: String, opacity: String): Boolean =
        visibility == "hidden" || display == "none" || opacity == "0"

    /** 吸顶/固定元素判定(历史问题5行为规范: 吸顶 sticky 未排除曾致误点, JS 待补) */
    fun isStickyOrFixed(position: String): Boolean = position == "sticky" || position == "fixed"

    /** 视口越界判定(历史问题4行为规范: 百度浮层曾报 x=1440/1800 非视口坐标, 应判定越界并弃采) */
    fun outOfViewport(x: Int, y: Int, w: Int, h: Int, viewportW: Int, viewportH: Int): Boolean =
        x < 0 || y < 0 || x >= viewportW || y >= viewportH || x + w <= 0 || y + h <= 0
}
