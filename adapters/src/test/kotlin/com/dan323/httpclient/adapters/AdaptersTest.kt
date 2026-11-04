package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.RequestOptions
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdaptersTest {
    @Test
    fun `executor round-trips against a local server`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { ex ->
            val body = ex.requestBody.readBytes()
            ex.responseHeaders.add("X-Method", ex.requestMethod)
            ex.sendResponseHeaders(201, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/echo"
            val r = JdkHttpExecutor().execute(HttpRequest("POST", url, body = "hi".toByteArray()))
            assertEquals(201, r.statusCode)
            assertEquals("hi", r.bodyAsString())
            assertEquals("POST", r.header("x-method"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `private env overrides shared env`() {
        val dir = Files.createTempDirectory("env")
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"host":"a","port":8080}}""")
        Files.writeString(dir.resolve("http-client.private.env.json"), """{"dev":{"host":"b"}}""")
        val dev = JsonEnvironmentRepository(dir).load().single()
        assertEquals(mapOf("host" to "b", "port" to "8080"), dev.variables)
    }

    private fun withServer(setup: (HttpServer) -> Unit, block: (Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        setup(server)
        server.start()
        try { block(server.address.port) } finally { server.stop(0) }
    }

    private fun redirectingServer(s: HttpServer) {
        s.createContext("/old") { ex ->
            ex.responseHeaders.add("Location", "/new")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        s.createContext("/new") { ex ->
            ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) }
        }
    }

    @Test
    fun `follows redirects by default and not with followRedirects false`() = withServer(::redirectingServer) { port ->
        val url = "http://127.0.0.1:$port/old"
        assertEquals(200, JdkHttpExecutor().execute(HttpRequest("GET", url)).statusCode)
        val r = JdkHttpExecutor().execute(HttpRequest("GET", url, options = RequestOptions(followRedirects = false)))
        assertEquals(302, r.statusCode)
        assertEquals("/new", r.header("location"))
    }

    @Test
    fun `timeout option aborts a slow response`() = withServer({ s ->
        s.createContext("/slow") { ex ->
            Thread.sleep(1500)
            runCatching { ex.sendResponseHeaders(200, -1) }; ex.close()
        }
    }) { port ->
        assertFailsWith<java.net.http.HttpTimeoutException> {
            JdkHttpExecutor().execute(HttpRequest("GET", "http://127.0.0.1:$port/slow", options = RequestOptions(timeoutMillis = 200)))
        }
    }

    @Test
    fun `proxy option routes the request through the proxy`() {
        var seen: String? = null
        withServer({ s ->
            s.createContext("/") { ex ->
                seen = ex.requestURI.toString()
                ex.sendResponseHeaders(200, 5); ex.responseBody.use { it.write("proxy".toByteArray()) }
            }
        }) { port ->
            val r = JdkHttpExecutor().execute(
                HttpRequest("GET", "http://example.invalid/thing", options = RequestOptions(proxy = "127.0.0.1:$port")),
            )
            assertEquals("proxy", r.bodyAsString())
            assertEquals("http://example.invalid/thing", seen)
        }
    }
}
