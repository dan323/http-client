package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunControllerTest {
    private val sent = mutableListOf<String>()
    private val executor = object : HttpExecutor {
        override fun execute(request: HttpRequest) = HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"token":"t"}""".toByteArray(), 5)
    }.let { inner -> object : HttpExecutor { override fun execute(request: HttpRequest): HttpResponse { sent += request.url; return inner.execute(request) } } }
    private val json = object : JsonExtractor { override fun extract(json: String, path: String): String? = "t" }

    private var vars = mapOf("base" to "http://h")
    private val locator = EnvironmentLocator { EnvironmentRepository { listOf(Environment("dev", vars)) } }
    private var sessionsCreated = 0
    private val controller = RunController(locator) {
        sessionsCreated++
        Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, json))
    }
    private val file = Path.of("/tmp/x/a.http")
    private val text = "# @name login\nGET {{base}}/login\n\n###\n# @name me\nGET {{base}}/me\nX: {{login.response.body.\$.token}}\n"
    private val dev = EnvironmentChoice.Named("dev")

    @Test fun requestAtCaretPicksTheLastRequestStartingBeforeTheLine() {
        val reqs = com.dan323.httpclient.core.application.HttpFileParser().parse(text).requests
        assertEquals("login", requestAt(reqs, 1)?.name)
        assertEquals("me", requestAt(reqs, 6)?.name)
    }

    @Test fun secondRunReusesChainedResponseAndResetSendsItAgain() {
        val first = controller.run(file, text, RunTarget.AtIndex(1), dev)
        assertEquals(listOf("http://h/login", "http://h/me"), sent)
        assertIs<RunResult.Completed>(first)
        val reused = mutableListOf<String?>()
        controller.run(file, text, RunTarget.AtIndex(1), dev, onReused = { r -> reused += r.map { it.name } })
        assertEquals(3, sent.size)
        assertEquals(listOf<String?>("login"), reused)
        assertEquals(1, sessionsCreated)
        controller.resetChain()
        controller.run(file, text, RunTarget.AtIndex(1), dev)
        assertEquals(5, sent.size)
    }

    @Test fun invalidatingDropsEvenARunningSessionSoTheNextRunGetsANewOne() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : HttpExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return HttpResponse(200, emptyList(), ByteArray(0), 1)
            }
        }
        var created = 0
        val gated = RunController(locator) { created++; Result.success(RequestSession(RequestRunner(blocking, FileReader { ByteArray(0) }), blocking, json)) }
        fun runInBackground(): Thread = Thread { gated.run(file, "GET http://h/a\n", RunTarget.All, dev) }.also { it.start() }

        // resetChain keeps a running session: it is cached again after the run, with what it was allowed when created
        var t = runInBackground()
        entered.await(10, java.util.concurrent.TimeUnit.SECONDS)
        gated.resetChain()
        release.countDown(); t.join(10_000)
        assertEquals(1, gated.cachedSessions)

        // invalidateSessions drops it although it is running: the run finishes, the next one gets a new session
        val entered2 = java.util.concurrent.CountDownLatch(1)
        val release2 = java.util.concurrent.CountDownLatch(1)
        val blocking2 = object : HttpExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                entered2.countDown(); release2.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return HttpResponse(200, emptyList(), ByteArray(0), 1)
            }
        }
        val gated2 = RunController(locator) { created++; Result.success(RequestSession(RequestRunner(blocking2, FileReader { ByteArray(0) }), blocking2, json)) }
        var outcome: RunResult? = null
        t = Thread { outcome = gated2.run(file, "GET http://h/a\n", RunTarget.All, dev) }.also { it.start() }
        entered2.await(10, java.util.concurrent.TimeUnit.SECONDS)
        gated2.invalidateSessions()
        assertEquals(0, gated2.cachedSessions)
        release2.countDown(); t.join(10_000)
        assertIs<RunResult.Completed>(outcome)          // the running request finished normally
        assertEquals(0, gated2.cachedSessions)            // and its session was not cached again
        val before = created
        gated2.run(file, "GET http://h/a\n", RunTarget.All, dev)
        assertEquals(before + 1, created)
    }

    @Test fun changedVariablesStartAFreshSessionAndDropTheStaleOne() {
        controller.run(file, text, RunTarget.AtIndex(1), dev)
        vars = mapOf("base" to "http://other")
        controller.run(file, text, RunTarget.AtIndex(1), dev)
        assertEquals(2, sessionsCreated)
        assertEquals(1, controller.cachedSessions)
        assertTrue(sent.contains("http://other/login"))
    }

    @Test fun rejectionsExplainWhy() {
        assertEquals(RunResult.Reason.NO_REQUEST, (controller.run(file, text, RunTarget.AtLine(-1), dev) as RunResult.Rejected).reason)
        assertEquals(RunResult.Reason.ENVIRONMENT, (controller.run(file, text, RunTarget.All, EnvironmentChoice.Named("nope")) as RunResult.Rejected).reason)
        assertEquals(RunResult.Reason.PARSE, (controller.run(file, "GET\n", RunTarget.All, dev) as RunResult.Rejected).reason)
    }

    @Test fun fileAccessErrorsAreRejected() {
        val c = RunController(locator) { Result.failure(IllegalArgumentException("bad root")) }
        val r = c.run(file, text, RunTarget.All, dev) as RunResult.Rejected
        assertEquals(RunResult.Reason.FILE_ACCESS, r.reason)
        assertEquals("File access configuration error: bad root", r.message)
        assertEquals("login", r.request?.name)
    }

    @Test fun noneRunsWithoutEnvironment() {
        var seen: String? = "unset"
        val r = controller.run(file, "GET http://h/x\n", RunTarget.All, EnvironmentChoice.None, onEnvironment = { seen = it })
        assertNull(seen)
        assertEquals(1, (r as RunResult.Completed).outcomes.size)
    }

    @Test fun outcomeDisplayMapsStatusAndPrettyBody() {
        val r = controller.run(file, "GET http://h/x\n", RunTarget.All, EnvironmentChoice.None) as RunResult.Completed
        val d = r.outcomes.single().display() as OutcomeDisplay.Response
        assertEquals(StatusClass.SUCCESS, d.statusClass)
        assertEquals("200 OK", d.statusLabel)
        assertTrue(d.isJson)
        assertEquals("{\n    \"token\": \"t\"\n}", d.prettyBody)
        assertEquals("application/json", d.contentType)
        val f = RunOutcome.Failure(r.outcomes.single().request, "boom").display()
        assertEquals("boom", (f as OutcomeDisplay.Error).message)
    }
}
