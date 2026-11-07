package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.RequestOptions
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Security suite: explicit `Cookie`, `Authorization` and `Proxy-Authorization` headers must never follow a
 * redirect to another origin (host or port), on every JDK, and are kept on same-origin hops. Redirects are
 * followed by the core `RedirectFollower` through [JdkHttpExecutor], not by the JDK client. (A scheme change
 * needs TLS and is covered by RedirectFollowerTest, which sees the origin comparison directly.)
 */
class RedirectCredentialsTest {
    private val creds = listOf(
        Header("Cookie", "sid=secret"), Header("Authorization", "Bearer t"), Header("Proxy-Authorization", "Basic abc"),
    )
    private val names = listOf("Cookie", "Authorization", "Proxy-Authorization")
    // The JDK client itself never puts Proxy-Authorization on the wire for a direct connection, so only these two
    // can be observed on a same-origin hop (RedirectFollowerTest checks all three at the request level).
    private val kept = listOf("Cookie", "Authorization")
    private val all = listOf("sid=secret", "Bearer t")

    private class Srv(val server: HttpServer) {
        val seen = CopyOnWriteArrayList<Map<String, String?>>()
        val port get() = server.address.port
    }

    /** A server that records the credential headers of each request and answers `/start` with a 302 to [redirectTo]. */
    private fun serve(redirectTo: ((String) -> String?)? = null): Srv {
        val s = Srv(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0))
        s.server.createContext("/") { ex ->
            s.seen += names.associateWith { ex.requestHeaders.getFirst(it) }
            val to = redirectTo?.invoke(ex.requestURI.path)
            if (to != null) {
                ex.responseHeaders.add("Location", to)
                ex.sendResponseHeaders(302, -1)
            } else {
                ex.sendResponseHeaders(204, -1)
            }
            ex.close()
        }
        s.server.start()
        return s
    }

    private fun run(origin: Srv, target: Srv, options: RequestOptions = RequestOptions()) {
        try {
            JdkHttpExecutor().use { it.execute(HttpRequest("GET", "http://127.0.0.1:${origin.port}/start", creds, options = options)) }
        } finally {
            origin.server.stop(0); target.server.stop(0)
        }
    }

    private fun assertDropped(target: Srv) {
        assertEquals(1, target.seen.size)
        names.forEach { assertNull(target.seen.single()[it], "$it must not reach the other origin") }
    }

    @Test
    fun `credentials are dropped when the redirect goes to another host name`() {
        val target = serve()
        val origin = serve { if (it == "/start") "http://localhost:${target.port}/x" else null }
        run(origin, target)
        assertDropped(target)
    }

    @Test
    fun `credentials are dropped when the redirect goes to another port of the same host`() {
        val target = serve()
        val origin = serve { if (it == "/start") "http://127.0.0.1:${target.port}/x" else null }
        run(origin, target)
        assertDropped(target)
    }

    @Test
    fun `credentials are kept on a same-origin hop and dropped on the next cross-origin hop`() {
        val target = serve()
        val origin = serve { if (it == "/start") "/mid" else "http://localhost:${target.port}/x" }
        run(origin, target)
        assertEquals(2, origin.seen.size)
        assertEquals(all, kept.map { origin.seen[1][it] })
        assertDropped(target)
    }

    @Test
    fun `credentials are kept on an absolute same-origin redirect`() {
        lateinit var origin: Srv
        val target = serve()
        origin = serve { if (it == "/start") "http://127.0.0.1:${origin.port}/next" else null }
        run(origin, target)
        assertEquals(2, origin.seen.size)
        origin.seen.forEach { seen -> assertEquals(all, kept.map { seen[it] }) }
        assertEquals(0, target.seen.size)
    }

    @Test
    fun `credentials are kept on a relative Location`() {
        val target = serve()
        val origin = serve { if (it == "/start") "/next" else null }
        run(origin, target)
        assertEquals(all, kept.map { origin.seen[1][it] })
    }

    @Test
    fun `no-redirect returns the first response and contacts nobody else`() {
        val target = serve()
        val origin = serve { if (it == "/start") "http://localhost:${target.port}/x" else null }
        run(origin, target, RequestOptions(followRedirects = false))
        assertEquals(1, origin.seen.size)
        assertEquals(0, target.seen.size)
    }
}
