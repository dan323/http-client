package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuardedHttpExecutorTest {
    private class Recorder : HttpExecutor {
        val sent = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse { sent += request; return HttpResponse(200, emptyList(), ByteArray(0), 0) }
    }

    private val loopback = HostAllowList { listOf(java.net.InetAddress.getByName("127.0.0.1")) }
    private fun get(url: String = "http://127.0.0.1/x", method: String = "GET", options: RequestOptions = RequestOptions()) =
        HttpRequest(method, url, options = options)

    @Test fun readOnlyMethodsPassAndOthersAreRefusedBeforeSending() {
        val r = Recorder(); val g = GuardedHttpExecutor(r, loopback)
        listOf("GET", "head", "Options").forEach { g.execute(get(method = it)) }
        listOf("POST", "PUT", "PATCH", "DELETE", "TRACE").forEach { m ->
            val e = assertFailsWith<RequestRefusedException>(m) { g.execute(get(method = m)) }
            assertTrue("--allow-write" in e.message!!)
        }
        assertEquals(3, r.sent.size)
    }

    @Test fun allowWriteOpensTheOtherMethods() {
        val r = Recorder()
        GuardedHttpExecutor(r, loopback, allowWrite = true).execute(get(method = "DELETE"))
        assertEquals(1, r.sent.size)
    }

    @Test fun aProxyIsRefused() {
        val r = Recorder()
        assertFailsWith<RequestRefusedException> {
            GuardedHttpExecutor(r, loopback).execute(get(options = RequestOptions(proxy = ProxyAddress("proxy.test", 3128))))
        }
        assertTrue(r.sent.isEmpty())
    }

    @Test fun aHostOutsideTheListIsRefusedBeforeSending() {
        val r = Recorder()
        assertFailsWith<RequestRefusedException> { GuardedHttpExecutor(r, loopback).execute(get("http://8.8.8.8/")) }
        assertTrue(r.sent.isEmpty())
    }

    @Test fun timeoutsAreClampedAndFilledIn() {
        val r = Recorder(); val g = GuardedHttpExecutor(r, loopback, maxTimeoutMillis = 5_000)
        g.execute(get())
        g.execute(get(options = RequestOptions(timeoutMillis = 3_600_000)))
        g.execute(get(options = RequestOptions(timeoutMillis = 1_000)))
        assertEquals(listOf(5_000L, 5_000L, 1_000L), r.sent.map { it.options.timeoutMillis })
    }

    @Test fun nonPositiveLimitIsRejected() { assertFailsWith<IllegalArgumentException> { GuardedHttpExecutor(Recorder(), maxTimeoutMillis = 0) } }

    @Test fun everyRedirectHopIsChecked() {
        val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var target = ""
        allowed.createContext("/start") { ex -> ex.responseHeaders.add("Location", target); ex.sendResponseHeaders(302, -1); ex.close() }
        allowed.start()
        try {
            val port = allowed.address.port
            target = "http://127.0.0.2:$port/elsewhere" // loopback too, but not the one listed below
            val dir = Files.createTempDirectory("guard")
            val file = dir.resolve("a.http").also { Files.writeString(it, "GET http://127.0.0.1:$port/start\n") }
            SessionFactory(GuardedHttpExecutor(JdkHttpExecutor(), HostAllowList(listOf("127.0.0.1")))).use { factory ->
                val out = factory.create(file, FileAccessPolicy(), persistence = false)
                    .runAll(HttpFileParser().parse(Files.readString(file)).requests, emptyMap()).single()
                val failure = assertIs<RunOutcome.Failure>(out)
                assertTrue("127.0.0.2" in failure.message, failure.message)
            }
        } finally { allowed.stop(0) }
    }

    @Test fun theSessionLimitBoundsTheTimeoutBeforeRedirectsAreFollowed() {
        val r = Recorder()
        val dir = Files.createTempDirectory("clamp")
        val file = dir.resolve("a.http").also { Files.writeString(it, "# @timeout 60m\nGET http://127.0.0.1/a\n\n###\nGET http://127.0.0.1/b\n") }
        SessionFactory(r).use { f ->
            f.create(file, FileAccessPolicy(), maxTimeoutMillis = 5_000).runAll(HttpFileParser().parse(Files.readString(file)).requests, emptyMap())
        }
        assertEquals(2, r.sent.size)
        // the follower hands each hop what is left of the chain's deadline, so a little under the limit
        r.sent.forEach { assertTrue(it.options.timeoutMillis!! in 4_000L..5_000L, "${it.options.timeoutMillis}") }
    }

    @Test fun withoutPersistenceSavingAndLinksFail() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
        server.start()
        try {
            val dir = Files.createTempDirectory("nopersist")
            val file = dir.resolve("a.http").also { Files.writeString(it, "GET http://127.0.0.1:${server.address.port}/\n>>! out.txt\n<> link.json\n") }
            val requests = HttpFileParser().parse(Files.readString(file)).requests
            val history = Files.createTempDirectory("history")
            SessionFactory(history = FileHistoryStore(historyDirFor = { history })).use { f ->
                val out = assertIs<RunOutcome.Success>(f.create(file, FileAccessPolicy(), persistence = false).runAll(requests, emptyMap()).single())
                assertTrue(out.saveError!!.contains("not available"), out.saveError)
                assertTrue(out.linkError!!.contains("not available"), out.linkError)
                assertTrue(Files.notExists(dir.resolve("link.json")))
                Files.list(history).use { assertEquals(0, it.count()) }
                assertTrue(Files.notExists(dir.resolve("out.txt")))
            }
            SessionFactory().use { f ->
                assertIs<RunOutcome.Success>(f.create(file, FileAccessPolicy()).runAll(requests, emptyMap()).single())
                assertTrue(Files.exists(dir.resolve("out.txt")))
            }
        } finally { server.stop(0) }
    }
}
