package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.domain.RequestTemplate
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CookieSessionTest {
    private fun server(): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/login") { ex ->
            ex.responseHeaders.add("Set-Cookie", "sid=s1; Path=/; HttpOnly")
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        s.createContext("/me") { ex ->
            val body = (ex.requestHeaders.getFirst("Cookie") ?: "none").toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        return s
    }

    private fun session(exec: JdkHttpExecutor) =
        RequestSession(RequestRunner(exec, LocalFileReader(Path.of("."))), exec, KotlinxJsonExtractor())

    private fun get(url: String, settings: Map<String, String> = emptyMap()) = RequestTemplate(null, "GET", url, settings = settings)

    @Test
    fun `cookie from login is sent next, also via derived clients, but not across sessions`() {
        val srv = server()
        JdkHttpExecutor().use { exec ->
            try {
                val base = "http://127.0.0.1:${srv.address.port}"
                val s = session(exec)
                s.run(get("$base/login"), emptyMap())
                assertEquals("sid=s1", s.run(get("$base/me"), emptyMap()).bodyAsString())
                assertEquals("sid=s1", s.run(get("$base/me", mapOf("no-redirect" to "")), emptyMap()).bodyAsString())
                assertEquals("none", s.run(get("$base/me", mapOf("no-cookie-jar" to "")), emptyMap()).bodyAsString())
                assertEquals("none", session(exec).run(get("$base/me"), emptyMap()).bodyAsString())
            } finally {
                srv.stop(0)
            }
        }
    }

    @Test
    fun `no-cookie-jar login stores nothing`() {
        val srv = server()
        JdkHttpExecutor().use { exec ->
            try {
                val base = "http://127.0.0.1:${srv.address.port}"
                val s = session(exec)
                s.run(get("$base/login", mapOf("no-cookie-jar" to "true")), emptyMap())
                assertEquals("none", s.run(get("$base/me"), emptyMap()).bodyAsString())
            } finally {
                srv.stop(0)
            }
        }
    }

    private fun echoServer(): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val body = "${ex.requestMethod} ${ex.requestURI.path} cookie=${ex.requestHeaders.getFirst("Cookie")}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        return s
    }

    @Test
    fun `login 302 with Set-Cookie stores the cookie and the redirect hop already carries it`() {
        val echo = echoServer()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/login") { ex ->
            ex.responseHeaders.add("Set-Cookie", "sid=s1; Path=/")
            ex.responseHeaders.add("Location", "/me")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        srv.createContext("/me") { ex ->
            val body = (ex.requestHeaders.getFirst("Cookie") ?: "none").toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        srv.start()
        JdkHttpExecutor().use { exec ->
            try {
                val base = "http://127.0.0.1:${srv.address.port}"
                val s = session(exec)
                val r = s.run(RequestTemplate(null, "POST", "$base/login", body = com.dan323.httpclient.core.domain.Body.Text("u=1")), emptyMap())
                assertEquals("sid=s1", r.bodyAsString())
                val noRedirect = session(exec).run(get("$base/login", mapOf("no-redirect" to "")), emptyMap())
                assertEquals(302, noRedirect.statusCode)
            } finally {
                srv.stop(0); echo.stop(0)
            }
        }
    }

    @Test
    fun `cookies are not sent to another host after a cross-host redirect`() {
        val target = echoServer() // reached as localhost, while the cookie belongs to 127.0.0.1
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/seed") { ex ->
            ex.responseHeaders.add("Set-Cookie", "sid=secret; Path=/")
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        origin.createContext("/go") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${target.address.port}/landed")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        origin.start()
        JdkHttpExecutor().use { exec ->
            try {
                val s = session(exec)
                val base = "http://127.0.0.1:${origin.address.port}"
                s.run(get("$base/seed"), emptyMap())
                assertEquals("GET /landed cookie=null", s.run(get("$base/go"), emptyMap()).bodyAsString())
                assertEquals("GET /landed cookie=null", s.run(RequestTemplate(null, "GET", "$base/go", listOf(com.dan323.httpclient.core.domain.Header("Cookie", "mine=1"))), emptyMap()).bodyAsString())
            } finally {
                origin.stop(0); target.stop(0)
            }
        }
    }
}
