package io.github.aixtin.droidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * ApiClient 单元测试（2026-09-05，纯 JVM 运行）。
 * Mock HTTP 服务基于 JDK java.base 的 ServerSocket 手写（不依赖 jdk.httpserver 模块，
 * 规避 188 构建环境下 com.sun.net.httpserver 编译不可见的问题）。
 */
private class MockServer(
    private val handler: (path: String, headers: Map<String, String>) -> Pair<Int, String>,
) {
    private val server = ServerSocket(0)
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)

    fun start(): MockServer {
        Thread {
            try {
                while (running.get()) {
                    val sock = server.accept()
                    Thread { handle(sock) }.start()
                }
            } catch (_: Exception) {
                // server.close() 后 accept 抛异常即退出
            }
        }.start()
        return this
    }

    fun port(): Int = server.localPort

    fun stop() {
        running.set(false)
        server.close()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = br.readLine() ?: return
                val path = requestLine.split(" ").getOrElse(1) { "/" }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val cl = headers["content-length"]?.toIntOrNull() ?: 0
                if (cl > 0) {
                    val buf = CharArray(cl)
                    var off = 0
                    while (off < cl) {
                        val n = br.read(buf, off, cl - off)
                        if (n < 0) break
                        off += n
                    }
                }
                val (code, body) = handler(path, headers)
                writeResponse(s, code, body)
            } catch (_: Exception) {
                // 客户端异常断开忽略
            }
        }
    }

    private fun writeResponse(s: Socket, code: Int, body: String) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        s.getOutputStream().use {
            it.write(head.toByteArray(Charsets.UTF_8))
            it.write(bytes)
            it.flush()
        }
    }
}

class ApiClientTest {

    /** 起一个本地 mock 服务器, 按 path 返回 (code, body)。 */
    private fun startServer(routes: Map<String, Pair<Int, String>>): MockServer =
        MockServer { path, _ -> routes[path] ?: (200 to "{}") }.start()

    @Test
    fun postJson_200_parsesObject() {
        val srv = startServer(mapOf("/ok" to (200 to """{"a":1,"b":"x"}""")))
        try {
            val r = ApiClient.postJson("http://127.0.0.1:${srv.port()}/ok", """{"q":1}""")
            assertTrue(r.isSuccess)
            val j = r.getOrThrow()
            assertEquals(1, j.getInt("a"))
            assertEquals("x", j.getString("b"))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun getJson_404_returnsHttpErrorWithBody() {
        val srv = startServer(mapOf("/missing" to (404 to """{"err":"not found"}""")))
        try {
            val r = ApiClient.getJson("http://127.0.0.1:${srv.port()}/missing")
            assertTrue(r.isFailure)
            val e = r.exceptionOrNull()
            assertTrue(e is ApiClient.HttpError)
            assertEquals(404, (e as ApiClient.HttpError).code)
            assertTrue(e.body.contains("not found"))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun postJson_500_failsWithHttpError() {
        val srv = startServer(mapOf("/boom" to (500 to "server exploded")))
        try {
            val r = ApiClient.postJson("http://127.0.0.1:${srv.port()}/boom", "{}")
            assertTrue(r.isFailure)
            val e = r.exceptionOrNull()
            assertTrue(e is ApiClient.HttpError)
            assertEquals(500, (e as ApiClient.HttpError).code)
        } finally {
            srv.stop()
        }
    }

    @Test
    fun postJson_malformedBody_failsWithParseError() {
        val srv = startServer(mapOf("/bad" to (200 to "this-is-not-json")))
        try {
            val r = ApiClient.postJson("http://127.0.0.1:${srv.port()}/bad", "{}")
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull()?.message?.contains("解析失败") == true)
        } finally {
            srv.stop()
        }
    }

    @Test
    fun getJson_emptyBody_fails() {
        val srv = startServer(mapOf("/empty" to (200 to "")))
        try {
            val r = ApiClient.getJson("http://127.0.0.1:${srv.port()}/empty")
            assertTrue(r.isFailure)
        } finally {
            srv.stop()
        }
    }

    @Test
    fun connectRefused_orUnreachable_fails() {
        // 端口 1 通常无人监听；无论 refused 还是 reset，都应收敛为 failure 而非抛出
        val r = ApiClient.getJson("http://127.0.0.1:1/", connectMs = 2000, readMs = 2000)
        assertTrue(r.isFailure)
    }

    @Test
    fun bearer_builds_authorization_header() {
        val h = ApiClient.bearer("tok123")
        assertEquals("Bearer tok123", h["Authorization"])
    }

    @Test
    fun postJson_sends_contentType_and_accept() {
        var captured: Map<String, String>? = null
        val srv = MockServer { _, headers ->
            captured = headers
            200 to """{}"""
        }.start()
        try {
            ApiClient.postJson("http://127.0.0.1:${srv.port()}/", "{}")
            assertNotNull(captured)
            assertTrue(captured!!["content-type"]!!.startsWith("application/json"))
            assertTrue(captured!!["accept"]!!.contains("application/json"))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun getJson_success_simpleObject() {
        val srv = startServer(mapOf("/simple" to (200 to """{"ok":true}""")))
        try {
            val r = ApiClient.getJson("http://127.0.0.1:${srv.port()}/simple")
            assertTrue(r.isSuccess)
            assertTrue(r.getOrThrow().getBoolean("ok"))
        } finally {
            srv.stop()
        }
    }
}
