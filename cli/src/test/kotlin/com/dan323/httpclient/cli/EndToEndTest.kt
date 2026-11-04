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
        server.createContext("/login") { ex ->
            val reply = """{"token":"zzz"}""".toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong())
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

    @Test fun chainsResponsesIntoLaterRequests() {
        val f = httpFile("# @name login\nPOST {{base}}/login\n\n###\nGET {{base}}/echo\nX-Token: {{login.response.body.$.token}}\n")
        val r = cli(f, "--env", "dev")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "GET zzz")
    }

    @Test fun selectedRequestRunsItsPrerequisites() {
        val f = httpFile("# @name login\nPOST {{base}}/login\n\n###\n# @name me\nGET {{base}}/echo\nX-Token: {{login.response.body.$.token}}\n")
        val r = cli(f, "--env", "dev", "--request", "me")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "### login")
        assertContains(r.out, "GET zzz")
    }

    @Test fun passingAssertionsExitZero() {
        val f = httpFile("# @assert status == 200\n# @assert body.$.token exists\n# @assert body.$.token == zzz\nPOST {{base}}/login\n")
        val r = cli(f, "--env", "dev")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "PASS status == 200")
        assertContains(r.out, "Assertions: 3 passed, 0 failed, 0 not run")
    }

    @Test fun failingAssertionExitsOne() {
        val f = httpFile("# @assert status == 200\n# @assert status == 404\nPOST {{base}}/login\n")
        val r = cli(f, "--env", "dev")
        assertEquals(1, r.code)
        assertContains(r.out, "PASS status == 200")
        assertContains(r.out, "FAIL status == 404 -- expected status to equal 404, but was 200")
        assertContains(r.out, "Assertions: 1 passed, 1 failed, 0 not run")
    }

    @Test fun failedRequestAssertionsAreCountedAsNotRun() {
        val f = httpFile(
            "# @assert status == 200\n# @assert body.\$.token == zzz\nPOST {{base}}/login\n\n###\n" +
                "# @assert status == 200\n# @assert status == 201\nGET {{base}}/{{undefinedVar}}\n",
        )
        val r = cli(f, "--env", "dev")
        assertEquals(1, r.code)
        assertContains(r.err, "Undefined variable: undefinedVar")
        assertContains(r.out, "NOT RUN status == 201")
        assertContains(r.out, "Assertions: 2 passed, 0 failed, 2 not run")
    }

    @Test fun assertionsSupportVariables() {
        val f = httpFile("# @assert body.\$.token == {{token}}\nPOST {{base}}/login\n")
        val r = cli(f, "--env", "dev")
        assertEquals(1, r.code)
        assertContains(r.out, "FAIL body.\$.token == {{token}} -- expected body.\$.token to equal abc, but was zzz")
    }

    @Test fun requestSelectionOnlyAssertsTheSelectedRequest() {
        val f = httpFile(
            "# @name login\n# @assert status == 500\nPOST {{base}}/login\n\n###\n" +
                "# @name me\n# @assert status == 201\nGET {{base}}/echo\nX-Token: {{login.response.body.\$.token}}\n",
        )
        val r = cli(f, "--env", "dev", "--request", "me")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "### login")
        assertEquals(false, r.out.contains("status == 500"))
        assertContains(r.out, "Assertions: 1 passed, 0 failed, 0 not run")
        assertEquals(1, cli(f, "--env", "dev").code)
    }

    @Test fun typoInExpressionFailsLoudly() {
        val f = httpFile("# @assert stauts !exists\n# @assert\nPOST {{base}}/login\n")
        val r = cli(f, "--env", "dev")
        assertEquals(1, r.code)
        assertContains(r.out, "unknown expression 'stauts'")
        assertContains(r.out, "missing expression")
        assertContains(r.out, "Assertions: 0 passed, 2 failed, 0 not run")
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
