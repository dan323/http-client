package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what the JDK client does with user-set headers across a redirect (the reviewer was unsure). Measured on
 * the JDK that runs the build: a manually set `Cookie` (and `Authorization`) is NOT forwarded to the redirect
 * target, even to another port on the same host, so the jar cannot leak through the JDK's own redirect
 * handling. The flip side: the JDK also never re-sends the jar's cookie on same-site hops, which is why
 * RequestSession follows redirects itself.
 */
class JdkRedirectHeaderTest {
    private fun forwarded(targetHost: String): String {
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { ex ->
            val body = "cookie=${ex.requestHeaders.getFirst("Cookie")}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        target.start()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { ex ->
            ex.responseHeaders.add("Location", "http://$targetHost:${target.address.port}/x")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        origin.start()
        try {
            return JdkHttpExecutor().execute(
                HttpRequest("GET", "http://127.0.0.1:${origin.address.port}/", listOf(Header("Cookie", "sid=secret"), Header("Authorization", "Bearer t")))
            ).bodyAsString()
        } finally {
            origin.stop(0); target.stop(0)
        }
    }

    @Test
    fun `manually set Cookie is not forwarded across a cross-host redirect`() {
        assertEquals("cookie=null", forwarded("localhost"))
    }

    @Test
    fun `manually set Cookie is not forwarded to another port of the same host either`() {
        assertEquals("cookie=null", forwarded("127.0.0.1"))
    }
}
