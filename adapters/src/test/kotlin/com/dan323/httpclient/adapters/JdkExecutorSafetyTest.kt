package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.RequestOptions
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdkExecutorSafetyTest {
    private val release = CountDownLatch(1)

    /** Sends headers and a few body bytes, then stalls until the test ends. */
    private fun stallingServer(): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.sendResponseHeaders(200, 100)
            ex.responseBody.write("hello".toByteArray())
            ex.responseBody.flush()
            release.await(30, TimeUnit.SECONDS)
            ex.close()
        }
        s.start()
        return s
    }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}/"

    @Test
    fun `timeout bounds the body read, not only the headers`() {
        val s = stallingServer()
        try {
            JdkHttpExecutor().use { ex ->
                val start = System.nanoTime()
                assertFailsWith<HttpTimeoutException> {
                    ex.execute(HttpRequest("GET", s.url(), options = RequestOptions(timeoutMillis = 500)))
                }
                assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000)
            }
        } finally {
            release.countDown(); s.stop(0)
        }
    }

    @Test
    fun `interrupt is surfaced as cancellation and the flag is restored`() {
        val s = stallingServer()
        try {
            JdkHttpExecutor().use { ex ->
                var failure: Throwable? = null
                var flag = false
                val t = Thread {
                    try {
                        ex.execute(HttpRequest("GET", s.url()))
                    } catch (e: Throwable) {
                        failure = e
                        flag = Thread.currentThread().isInterrupted
                    }
                }
                t.start()
                Thread.sleep(500)
                t.interrupt()
                t.join(10_000)
                assertTrue(!t.isAlive, "executor must return after interrupt")
                assertTrue(failure is CancellationException, "was $failure")
                assertTrue(flag, "interrupt flag must be restored")
            }
        } finally {
            release.countDown(); s.stop(0)
        }
    }

    @Test
    fun `default client has a 30 second connect timeout`() {
        val c = JdkHttpExecutor::class.java.getDeclaredField("client").also { it.isAccessible = true }
            .get(JdkHttpExecutor()) as java.net.http.HttpClient
        assertEquals(30_000L, c.connectTimeout().get().toMillis())
    }

    @Test
    fun `restricted Host header is dropped and the target host is used`() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val b = (ex.requestHeaders.getFirst("Host") ?: "").toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start()
        try {
            val body = JdkHttpExecutor().use {
                it.execute(HttpRequest("GET", s.url(), listOf(Header("Host", "evil.example")))).bodyAsString()
            }
            assertEquals("127.0.0.1:${s.address.port}", body)
        } finally { s.stop(0) }
    }
}
