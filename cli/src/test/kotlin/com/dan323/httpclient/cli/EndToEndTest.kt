package com.dan323.httpclient.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EndToEndTest {
    private lateinit var server: HttpServer
    private val dir = Files.createTempDirectory("httpc-e2e")

    @BeforeTest fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val reply = "${ex.requestMethod} ${ex.requestHeaders.getFirst("X-Token")} $body".toByteArray()
            ex.responseHeaders.add("X-Reply", "yes")
            ex.sendResponseHeaders(201, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()
        val port = server.address.port
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:$port","token":"abc"}}""")
        Files.writeString(dir.resolve("payload.txt"), "from-file")
    }

    @AfterTest fun stopServer() = server.stop(0)

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Result(code, o.toString(), e.toString())
    }

    private fun httpFile(content: String) = dir.resolve("t.http").also { Files.writeString(it, content) }.toString()

    @Test fun sendsResolvedRequestAndPrintsResponse() {
        val f = httpFile("POST {{base}}/echo\nX-Token: {{token}}\n\nhello\n")
        val r = cli(f, "--env", "dev")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "HTTP 201")
        assertContains(r.out, "x-reply: yes")
        assertContains(r.out, "POST abc hello")
    }

    @Test fun sendsFileBody() {
        val f = httpFile("POST {{base}}/echo\n\n< payload.txt\n")
        val r = cli(f, "--env", "dev")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "POST null from-file")
    }

    @Test fun runsOnlySelectedRequest() {
        val f = httpFile("# @name one\nGET {{base}}/echo\n\n###\n# @name two\nDELETE {{base}}/echo\n")
        val r = cli(f, "--env", "dev", "--request", "two")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "DELETE")
        assertEquals(false, r.out.contains("### one"))
    }

    @Test fun reportsErrorsWithExitCodeOne() {
        val f = httpFile("GET {{nope}}/x\n")
        val r = cli(f, "--env", "dev")
        assertEquals(1, r.code)
        assertContains(r.err, "Undefined variable: nope")
        assertEquals(1, cli(f, "--env", "missing").code)
        assertEquals(2, cli().code)
    }
}
