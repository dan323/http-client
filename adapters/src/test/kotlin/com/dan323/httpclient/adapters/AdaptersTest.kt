package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.HttpRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
