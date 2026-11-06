package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import com.dan323.httpclient.core.port.out.FileReader
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunControllerMoreTest {
    private val sent = CopyOnWriteArrayList<String>()
    private var gate: CountDownLatch? = null
    private val entered = CountDownLatch(1)
    private val executor = HttpExecutor { r ->
        sent += r.url
        if (gate != null) { entered.countDown(); gate!!.await(5, TimeUnit.SECONDS) }
        HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"token":"t"}""".toByteArray(), 1)
    }
    private val json = JsonExtractor { _, _ -> "t" }
    private var vars = mapOf("base" to "http://h")
    private val envs = mutableListOf(Environment("dev", vars), Environment("prod", mapOf("base" to "http://p")))
    private val locator = EnvironmentLocator { EnvironmentRepository { envs.map { if (it.name == "dev") Environment("dev", vars) else it } } }
    private var created = 0
    private val controller = RunController(locator) {
        created++
        Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, json))
    }
    private val file = Path.of("/tmp/x/a.http")
    private val text = "# @name login\nGET {{base}}/login\n\n###\n# @name me\nGET {{base}}/me\nX: {{login.response.body.\$.token}}\n\n###\n# @name other\nGET {{base}}/other\n"
    private val dev = EnvironmentChoice.Named("dev")

    @Test fun caretRunsRequestWithItsDependenciesOnly() {
        // line 6 (0-based) is inside "me"
        val r = controller.run(file, text, RunTarget.AtLine(6), dev) as RunResult.Completed
        assertEquals("dev", r.environment)
        assertEquals(listOf("http://h/login", "http://h/me"), sent)
        assertEquals(listOf("login", "me"), r.outcomes.map { it.request.name })
    }

    @Test fun requestAtHandlesEdges() {
        val reqs = HttpFileParser().parse(text).requests
        assertNull(requestAt(reqs, -1))
        assertEquals("other", requestAt(reqs, 1000)?.name)
        assertNull(requestAt(emptyList(), 3))
    }

    @Test fun allRunsEveryRequestInOrderAndReportsEachOutcome() {
        val seen = mutableListOf<String?>()
        controller.run(file, text, RunTarget.All, dev, onOutcome = { seen += it.request.name })
        assertEquals(listOf<String?>("login", "me", "other"), seen)
    }

    @Test fun indexOutOfRangeIsRejected() {
        val r = controller.run(file, text, RunTarget.AtIndex(9), dev) as RunResult.Rejected
        assertEquals(RunResult.Reason.NO_REQUEST, r.reason)
        assertEquals("No such request: 10", r.message)
    }

    @Test fun sessionsAreKeptPerFileAndPerEnvironment() {
        controller.run(file, text, RunTarget.AtIndex(0), dev)
        controller.run(file, text, RunTarget.AtIndex(0), EnvironmentChoice.Named("prod"))
        controller.run(Path.of("/tmp/x/b.http"), text, RunTarget.AtIndex(0), dev)
        controller.run(file, text, RunTarget.AtIndex(0), EnvironmentChoice.None)
        assertEquals(4, created)
        assertEquals(4, controller.cachedSessions)
        controller.run(file, text, RunTarget.AtIndex(0), dev)
        assertEquals(4, created)
    }

    @Test fun failedSessionCreationIsNotCached() {
        var fail = true
        val c = RunController(locator) {
            if (fail) Result.failure(IllegalStateException("x")) else Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, json))
        }
        assertTrue(c.run(file, text, RunTarget.All, dev) is RunResult.Rejected)
        assertEquals(0, c.cachedSessions)
        fail = false
        assertTrue(c.run(file, text, RunTarget.All, dev) is RunResult.Completed)
    }

    @Test fun runningSessionIsNotEvictedByARunWithChangedVariables() {
        gate = CountDownLatch(1)
        val first = thread { controller.run(file, text, RunTarget.AtIndex(0), dev) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vars = mapOf("base" to "http://other")
        val g = gate; gate = null
        controller.run(file, text, RunTarget.AtIndex(0), dev)
        assertEquals(2, controller.cachedSessions) // stale one survives while running
        g!!.countDown(); first.join(5000)
        controller.run(file, text, RunTarget.AtIndex(0), dev)
        assertEquals(1, controller.cachedSessions) // dropped once idle
    }
}
