package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `timeout option aborts a slow response`() {
        val release = CountDownLatch(1)
        try {
            withServer({ s ->
                s.createContext("/slow") { ex ->
                    release.await(10, TimeUnit.SECONDS)
                    runCatching { ex.sendResponseHeaders(200, -1) }; ex.close()
                }
            }) { port ->
                JdkHttpExecutor().use { exec ->
                    assertFailsWith<java.net.http.HttpTimeoutException> {
                        exec.execute(HttpRequest("GET", "http://127.0.0.1:$port/slow", options = RequestOptions(timeoutMillis = 300)))
                    }
                }
                release.countDown()
            }
        } finally {
            release.countDown()
        }
    }

    private fun proxyServer(seen: AtomicReference<String?>): (HttpServer) -> Unit = { s ->
        s.createContext("/") { ex ->
            seen.set(ex.requestURI.toString())
            ex.sendResponseHeaders(200, 5); ex.responseBody.use { it.write("proxy".toByteArray()) }
        }
        Unit
    }

    @Test
    fun `proxy option routes the request through the proxy`() {
        val seen = AtomicReference<String?>()
        withServer(proxyServer(seen)) { port ->
            val r = JdkHttpExecutor().use {
                it.execute(HttpRequest("GET", "http://example.invalid/thing", options = RequestOptions(proxy = ProxyAddress("127.0.0.1", port))))
            }
            assertEquals("proxy", r.bodyAsString())
            assertEquals("http://example.invalid/thing", seen.get())
        }
    }

    @Test
    fun `unresolvable proxy host fails with a clear message`() {
        val e = assertFailsWith<IllegalArgumentException> {
            JdkHttpExecutor().use { it.execute(HttpRequest("GET", "http://127.0.0.1:1/", options = RequestOptions(proxy = ProxyAddress("no-such-host.invalid", 8080)))) }
        }
        assertTrue(e.message!!.contains("@proxy"))
    }

    @Test
    fun `no-redirect, proxy and timeout combine`() {
        val seen = AtomicReference<String?>()
        withServer({ s ->
            s.createContext("/") { ex ->
                seen.set(ex.requestURI.toString())
                ex.responseHeaders.add("Location", "/new")
                ex.sendResponseHeaders(302, -1); ex.close()
            }
        }) { port ->
            val opts = RequestOptions(timeoutMillis = 5000, followRedirects = false, proxy = ProxyAddress("127.0.0.1", port))
            val r = JdkHttpExecutor().use { it.execute(HttpRequest("GET", "http://example.invalid/old", options = opts)) }
            assertEquals(302, r.statusCode)
            assertEquals("http://example.invalid/old", seen.get())
        }
    }

    @Test
    fun `derived clients are cached and the default path uses the injected client`() = withServer(::redirectingServer) { port ->
        val url = "http://127.0.0.1:$port/old"
        val noRedirect = RequestOptions(followRedirects = false)
        JdkHttpExecutor().use { exec ->
            repeat(3) { assertEquals(302, exec.execute(HttpRequest("GET", url, options = noRedirect)).statusCode) }
            assertEquals(200, exec.execute(HttpRequest("GET", url)).statusCode)
            val field = JdkHttpExecutor::class.java.getDeclaredField("derived").apply { isAccessible = true }
            assertEquals(0, (field.get(exec) as Map<*, *>).size, "the default client never follows redirects itself, so no derived client")
        }
        val injected = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        JdkHttpExecutor(injected).use { exec ->
            repeat(3) { assertEquals(302, exec.execute(HttpRequest("GET", url, options = noRedirect)).statusCode) }
            assertEquals(200, exec.execute(HttpRequest("GET", url)).statusCode)
            val field = JdkHttpExecutor::class.java.getDeclaredField("derived").apply { isAccessible = true }
            assertEquals(1, (field.get(exec) as Map<*, *>).size)
        }
    }

    @Test
    fun `derived client keeps the injected client's configuration`() = withServer(::redirectingServer) { port ->
        val injected = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(7))
            .build()
        JdkHttpExecutor(injected).use { exec ->
            val r = exec.execute(HttpRequest("GET", "http://127.0.0.1:$port/old", options = RequestOptions(followRedirects = false)))
            assertEquals(302, r.statusCode)
            val field = JdkHttpExecutor::class.java.getDeclaredField("derived").apply { isAccessible = true }
            val c = (field.get(exec) as Map<*, *>).values.single() as HttpClient
            assertEquals(java.time.Duration.ofSeconds(7), c.connectTimeout().get())
        }
    }
}
