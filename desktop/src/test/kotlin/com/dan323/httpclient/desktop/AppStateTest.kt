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
    private val dir = Files.createTempDirectory("httpc-desktop")

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { ex ->
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

    @Test fun failuresAreShownAsOutcomes() {
        val file = dir.resolve("d.http").also { Files.writeString(it, "GET {{nope}}/x\n") }
        val state = AppState()
        state.open(file)
        state.run(all = true)
        assertTrue(state.outcomes.single() is RunOutcome.Failure)
    }
}
