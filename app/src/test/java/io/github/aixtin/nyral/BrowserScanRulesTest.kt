package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 坐标换算/样式排除/吸顶与越界判定 纯函数测试(2026-10-05) */
class BrowserScanRulesTest {

    // ---- 视口坐标换算(与 JS collect/collectFrame 一致: 矩形位置+穿透偏移, 不加文档滚动) ----
    @Test
    fun `视口坐标叠加穿透偏移`() {
        assertEquals(120, BrowserScanRules.viewportX(rectLeft = 80, offsetX = 40))
        assertEquals(-10, BrowserScanRules.viewportX(rectLeft = -50, offsetX = 40))
        assertEquals(305, BrowserScanRules.viewportY(rectTop = 300, offsetY = 5))
    }

    @Test
    fun `坐标换算不叠加文档滚动偏移`() {
        // 滚动后重扫即为新视口所见, 换算仅含 iframe 穿透偏移(行为规范)
        val scrolled = BrowserScanRules.viewportY(rectTop = 200, offsetY = 0)
        assertEquals(200, scrolled)
    }

    // ---- 尺寸下限(JS: <24 弃采) ----
    @Test
    fun `宽或高小于24弃采`() {
        assertTrue(BrowserScanRules.belowMinSize(23, 100))
        assertTrue(BrowserScanRules.belowMinSize(100, 10))
        assertTrue(BrowserScanRules.belowMinSize(0, 0))
    }

    @Test
    fun `尺寸等于24或以上不弃`() {
        assertFalse(BrowserScanRules.belowMinSize(24, 24))
        assertFalse(BrowserScanRules.belowMinSize(24, 100))
        assertFalse(BrowserScanRules.belowMinSize(100, 24))
    }

    // ---- 样式排除(visibility=hidden / display=none / opacity=0) ----
    @Test
    fun `隐藏样式三条件分别命中`() {
        assertTrue(BrowserScanRules.excludedByStyle("hidden", "block", "1"))
        assertTrue(BrowserScanRules.excludedByStyle("visible", "none", "0.5"))
        assertTrue(BrowserScanRules.excludedByStyle("visible", "block", "0"))
    }

    @Test
    fun `可见样式不排除`() {
        assertFalse(BrowserScanRules.excludedByStyle("visible", "block", "0.99"))
        assertFalse(BrowserScanRules.excludedByStyle("visible", "inline", "1"))
    }

    // ---- 吸顶/固定判定(历史问题5行为规范: 吸顶未排除曾致误点) ----
    @Test
    fun `sticky与fixed判为吸顶固定`() {
        assertTrue(BrowserScanRules.isStickyOrFixed("sticky"))
        assertTrue(BrowserScanRules.isStickyOrFixed("fixed"))
    }

    @Test
    fun `常规定位不判为吸顶固定`() {
        assertFalse(BrowserScanRules.isStickyOrFixed("relative"))
        assertFalse(BrowserScanRules.isStickyOrFixed("absolute"))
        assertFalse(BrowserScanRules.isStickyOrFixed("static"))
    }

    // ---- 视口越界判定(历史问题4行为规范: 百度浮层曾报 x=1440/1800 非视口坐标) ----
    @Test
    fun `横坐标越界判定`() {
        // 1080 宽视口上报 x=1440 即越界(历史问题4复现)
        assertTrue(BrowserScanRules.outOfViewport(x = 1440, y = 200, w = 40, h = 40, viewportW = 1080, viewportH = 2400))
        assertTrue(BrowserScanRules.outOfViewport(x = 1800, y = 200, w = 40, h = 40, viewportW = 1080, viewportH = 2400))
        assertTrue(BrowserScanRules.outOfViewport(x = -1, y = 200, w = 40, h = 40, viewportW = 1080, viewportH = 2400))
    }

    @Test
    fun `纵坐标越界判定`() {
        assertTrue(BrowserScanRules.outOfViewport(x = 100, y = 2600, w = 40, h = 40, viewportW = 1080, viewportH = 2400))
        assertTrue(BrowserScanRules.outOfViewport(x = 100, y = -5, w = 40, h = 40, viewportW = 1080, viewportH = 2400))
    }

    @Test
    fun `整体移出视口也判越界`() {
        // 元素整体在左/上边缘外: x+w<=0 或 y+h<=0
        assertTrue(BrowserScanRules.outOfViewport(x = 100, y = 100, w = -200, h = 40, viewportW = 1080, viewportH = 2400))
    }

    @Test
    fun `视口内元素不越界`() {
        assertFalse(BrowserScanRules.outOfViewport(x = 100, y = 200, w = 200, h = 60, viewportW = 1080, viewportH = 2400))
        // 贴右/下边缘但在视口内(坐标 < 视口宽高)不算越界
        assertFalse(BrowserScanRules.outOfViewport(x = 1079, y = 2399, w = 1, h = 1, viewportW = 1080, viewportH = 2400))
    }

    @Test
    fun `零尺寸视口与元素恰在边界`() {
        // x == viewportW 视为越界, x == viewportW - 1 不越界
        assertTrue(BrowserScanRules.outOfViewport(x = 1080, y = 0, w = 1, h = 1, viewportW = 1080, viewportH = 2400))
        assertFalse(BrowserScanRules.outOfViewport(x = 1079, y = 0, w = 1, h = 1, viewportW = 1080, viewportH = 2400))
    }
}
