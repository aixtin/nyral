package io.github.aixtin.nyral

import io.github.aixtin.nyral.DebugRoutes.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DebugServer 鉴权/路由/连接复用 纯函数对照测试(2026-10-05) */
class DebugRoutesTest {

    // ---- 路径规范化(剥离 query) ----
    @Test
    fun `剥离查询串`() {
        assertEquals(PathInfo("/v1/logs", "level=warn&limit=20"), DebugRoutes.splitPath("/v1/logs?level=warn&limit=20"))
        assertEquals(PathInfo("/v1/file/read", "path=/sdcard/a.txt"), DebugRoutes.splitPath("/v1/file/read?path=/sdcard/a.txt"))
    }

    @Test
    fun `无查询串时query为空`() {
        assertEquals(PathInfo("/v1/state", ""), DebugRoutes.splitPath("/v1/state"))
        assertEquals(PathInfo("/v1/chat", ""), DebugRoutes.splitPath("/v1/chat"))
    }

    // ---- 路由表(GET/POST 关键端点) ----
    @Test
    fun `GET端点命中`() {
        assertEquals("GET /v1/state", DebugRoutes.routeOf("GET", "/v1/state"))
        assertEquals("GET /v1/ui", DebugRoutes.routeOf("GET", "/v1/ui"))
        assertEquals("GET /v1/logs", DebugRoutes.routeOf("GET", "/v1/logs"))
        assertEquals("GET /v1/ping", DebugRoutes.routeOf("GET", "/v1/ping"))
        assertEquals("GET /v1/status", DebugRoutes.routeOf("GET", "/v1/status"))
        assertEquals("GET /v1/file/read", DebugRoutes.routeOf("GET", "/v1/file/read"))
    }

    @Test
    fun `POST端点命中`() {
        assertEquals("POST /v1/chat", DebugRoutes.routeOf("POST", "/v1/chat"))
        assertEquals("POST /v1/mem/search", DebugRoutes.routeOf("POST", "/v1/mem/search"))
        assertEquals("POST /v1/browser/scan", DebugRoutes.routeOf("POST", "/v1/browser/scan"))
        assertEquals("POST /v1/browser/highlight/xy", DebugRoutes.routeOf("POST", "/v1/browser/highlight/xy"))
        assertEquals("POST /v1/app/launch", DebugRoutes.routeOf("POST", "/v1/app/launch"))
        assertEquals("POST /v1/js/run", DebugRoutes.routeOf("POST", "/v1/js/run"))
        assertEquals("POST /v1/sh/run", DebugRoutes.routeOf("POST", "/v1/sh/run"))
    }

    @Test
    fun `方法不匹配返回null`() {
        // 相同路径错误方法
        assertNull(DebugRoutes.routeOf("POST", "/v1/state"))
        assertNull(DebugRoutes.routeOf("GET", "/v1/chat"))
        assertNull(DebugRoutes.routeOf("PUT", "/v1/chat"))
    }

    @Test
    fun `未知路径返回null`() {
        assertNull(DebugRoutes.routeOf("GET", "/v1/unknown"))
        assertNull(DebugRoutes.routeOf("POST", "/v2/chat"))
        assertNull(DebugRoutes.routeOf("DELETE", "/v1/chat"))
        // 带 query 的原始路径不应直接匹配(先 splitPath 剥离)
        assertNull(DebugRoutes.routeOf("GET", "/v1/logs?level=warn"))
    }

    @Test
    fun `路径尾部斜杠不命中`() {
        assertNull(DebugRoutes.routeOf("GET", "/v1/state/"))
    }

    // ---- 鉴权(仅 header X-Auth-Token 精确相等) ----
    @Test
    fun `token精确匹配通过`() {
        assertTrue(DebugRoutes.authorized("abc123", "abc123"))
        assertTrue(DebugRoutes.authorized("", ""))
    }

    @Test
    fun `token不匹配或缺失拒绝`() {
        assertFalse(DebugRoutes.authorized("abc124", "abc123"))
        assertFalse(DebugRoutes.authorized(null, "abc123"))
        // 大小写敏感
        assertFalse(DebugRoutes.authorized("ABC123", "abc123"))
    }

    @Test
    fun `期望token为空时仅空token通过`() {
        assertTrue(DebugRoutes.authorized("", ""))
        assertFalse(DebugRoutes.authorized("anything", ""))
    }

    // ---- 连接复用(GET + keep-alive) ----
    @Test
    fun `GET且声明keep-alive可复用`() {
        assertTrue(DebugRoutes.reusable("GET", true))
    }

    @Test
    fun `POST或无keep-alive不复用`() {
        assertFalse(DebugRoutes.reusable("POST", true))
        assertFalse(DebugRoutes.reusable("GET", false))
        assertFalse(DebugRoutes.reusable("POST", false))
    }
}
