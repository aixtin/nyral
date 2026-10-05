package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DebugRoutes 缺口用例补充(2026-10-06):
 * 补齐 routeOf 未覆盖的 22 个端点命中分支(与 DebugServer.kt 路由表一致)。
 */
class DebugRoutesGapTest {

    @Test
    fun `剩余GET端点命中`() {
        assertEquals("GET /v1/screen", DebugRoutes.routeOf("GET", "/v1/screen"))
        assertEquals("GET /v1/browser", DebugRoutes.routeOf("GET", "/v1/browser"))
        assertEquals("GET /v1/app/screenshot", DebugRoutes.routeOf("GET", "/v1/app/screenshot"))
    }

    @Test
    fun `touch类端点命中`() {
        assertEquals("POST /v1/touch", DebugRoutes.routeOf("POST", "/v1/touch"))
        assertEquals("POST /v1/key", DebugRoutes.routeOf("POST", "/v1/key"))
        assertEquals("POST /v1/input", DebugRoutes.routeOf("POST", "/v1/input"))
    }

    @Test
    fun `browser操作端点命中`() {
        assertEquals("POST /v1/browser/open", DebugRoutes.routeOf("POST", "/v1/browser/open"))
        assertEquals("POST /v1/browser/close", DebugRoutes.routeOf("POST", "/v1/browser/close"))
        assertEquals("POST /v1/browser/status", DebugRoutes.routeOf("POST", "/v1/browser/status"))
        assertEquals("POST /v1/browser/think", DebugRoutes.routeOf("POST", "/v1/browser/think"))
        assertEquals("POST /v1/browser/highlight", DebugRoutes.routeOf("POST", "/v1/browser/highlight"))
        assertEquals("POST /v1/browser/click", DebugRoutes.routeOf("POST", "/v1/browser/click"))
        assertEquals("POST /v1/browser/type", DebugRoutes.routeOf("POST", "/v1/browser/type"))
        assertEquals("POST /v1/browser/scroll", DebugRoutes.routeOf("POST", "/v1/browser/scroll"))
        assertEquals("POST /v1/browser/eval", DebugRoutes.routeOf("POST", "/v1/browser/eval"))
    }

    @Test
    fun `app操作端点命中`() {
        assertEquals("POST /v1/app/scan", DebugRoutes.routeOf("POST", "/v1/app/scan"))
        assertEquals("POST /v1/app/click", DebugRoutes.routeOf("POST", "/v1/app/click"))
        assertEquals("POST /v1/app/type", DebugRoutes.routeOf("POST", "/v1/app/type"))
        assertEquals("POST /v1/app/back", DebugRoutes.routeOf("POST", "/v1/app/back"))
        assertEquals("POST /v1/app/home", DebugRoutes.routeOf("POST", "/v1/app/home"))
        assertEquals("POST /v1/app/installed", DebugRoutes.routeOf("POST", "/v1/app/installed"))
        assertEquals("POST /v1/app/tap", DebugRoutes.routeOf("POST", "/v1/app/tap"))
    }

    @Test
    fun `路径大小写敏感`() {
        // 路由严格区分大小写: 大写路径不命中
        assertEquals(null, DebugRoutes.routeOf("GET", "/V1/state"))
        assertEquals(null, DebugRoutes.routeOf("post", "/v1/chat"))
        assertEquals(null, DebugRoutes.routeOf("POST", "/V1/BROWSER/SCAN"))
    }
}
