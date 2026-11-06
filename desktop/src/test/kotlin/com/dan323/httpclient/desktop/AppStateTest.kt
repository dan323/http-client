package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.application.RunOutcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppStateTest {
    private lateinit var server: HttpServer
    private val logins = java.util.concurrent.atomic.AtomicInteger()
    private val dir = Files.createTempDirectory("httpc-desktop")

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { ex ->
            logins.incrementAndGet()
            val b = """{"token":"zzz"}""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/echo") { ex ->
            val b = (ex.requestHeaders.getFirst("X-Token") ?: "none").toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.address.port}"}}""")
    }

    @AfterTest fun stop() = server.stop(0)

    private val chained = "# @name login\nPOST {{base}}/login\n\n###\n# @name me\nGET {{base}}/echo\nX-Token: {{login.response.body.$.token}}\n"

    private fun bodyOf(o: RunOutcome) = (o as RunOutcome.Success).response.bodyAsString()

    @Test fun opensFileAndListsRequestsAndEnvironments() {
        val file = dir.resolve("a.http").also { Files.writeString(it, chained) }
        val state = AppState()
        state.open(file)
        assertEquals(listOf("login", "me"), state.requests.map { it.name })
        assertEquals(listOf("dev"), state.environments)
        assertFalse(state.dirty)
    }

    @Test fun sendRunsSelectedRequestWithPrerequisites() {
        val file = dir.resolve("b.http").also { Files.writeString(it, chained) }
        val state = AppState()
        state.open(file)
        state.environment = "dev"
        state.selected = 1
        state.run(all = false)
        assertEquals(listOf("login", "me"), state.outcomes.map { it.request.name })
        assertEquals("zzz", bodyOf(state.outcomes.last()))
        assertFalse(state.running)
    }

    @Test fun editsAreSavedBeforeRunning() {
        val file = dir.resolve("c.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
        val state = AppState()
        state.open(file)
        state.environment = "dev"
        state.edit("GET {{base}}/echo\nX-Token: edited\n")
        assertTrue(state.dirty)
        state.run(all = true)
        assertEquals("edited", bodyOf(state.outcomes.single()))
        assertEquals("GET {{base}}/echo\nX-Token: edited\n", Files.readString(file))
        assertFalse(state.dirty)
    }

    @Test fun environmentsComeFromParentFolderAndStaleNameIsAnError() {
        val sub = Files.createDirectories(dir.resolve("nested/deeper"))
        val file = sub.resolve("e.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
        val state = AppState()
        state.open(file)
        assertEquals(listOf("dev"), state.environments)
        state.environment = "gone"
        state.run(all = true)
        assertTrue(state.message!!.contains("Unknown environment: gone"), state.message)
        assertTrue(state.outcomes.isEmpty())
    }

    @Test fun malformedEnvFileIsShownWithItsName() {
        val bad = Files.createTempDirectory("httpc-bad")
        bad.resolve("http-client.env.json").also { Files.writeString(it, "{nope") }
        val file = bad.resolve("e.http").also { Files.writeString(it, "GET http://x/\n") }
        val state = AppState()
        state.open(file)
        // file name only: Windows may report the temp dir in its long form (RUNNER~1 vs runneradmin)
        assertTrue(state.message!!.contains("http-client.env.json"), state.message)
    }

    @Test fun failuresAreShownAsOutcomes() {
        val file = dir.resolve("d.http").also { Files.writeString(it, "GET {{nope}}/x\n") }
        val state = AppState()
        state.open(file)
        state.run(all = true)
        assertTrue(state.outcomes.single() is RunOutcome.Failure)
    }

    @Test fun badFileAccessConfigurationIsReportedAsFailure() {
        val file = dir.resolve("a.http")
        val outcomes = DesktopRunner(policy = { com.dan323.httpclient.adapters.FileAccessPolicy.load({ "relative" }, { null }) }).use {
            it.execute(file, "GET http://127.0.0.1:${server.address.port}/echo\n", null, null)
        }
        val f = outcomes.single() as RunOutcome.Failure
        assertTrue("relative" in f.message)
    }

    @Test fun secondSendReusesTheLoginResponse() {
        val file = dir.resolve("cache.http").also { Files.writeString(it, chained) }
        AppState().use { state ->
            state.open(file)
            state.environment = "dev"
            state.selected = 1
            state.run(all = false)
            state.run(all = false)
            assertEquals(1, logins.get())
            assertEquals(listOf("me"), state.outcomes.map { it.request.name })
            assertEquals("zzz", bodyOf(state.outcomes.single()))
            state.resetChain()
            state.run(all = false)
            assertEquals(2, logins.get())
        }
    }
}
