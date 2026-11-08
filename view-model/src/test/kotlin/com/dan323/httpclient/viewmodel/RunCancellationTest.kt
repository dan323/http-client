package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import com.dan323.httpclient.core.port.out.FileReader
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunCancellationTest {
    private val sent = CopyOnWriteArrayList<String>()
    private val entered = CountDownLatch(1)
    private var hang = true

    // Like JdkHttpExecutor: an interrupted wait cancels the request.
    private val executor = HttpExecutor { r ->
        sent += r.url
        if (hang && r.url.endsWith("/me")) {
            entered.countDown()
            try { CountDownLatch(1).await(10, TimeUnit.SECONDS) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Request cancelled").also { it.initCause(e) }
            }
        }
        HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"token":"t"}""".toByteArray(), 1)
    }
    private val locator = EnvironmentLocator { EnvironmentRepository { listOf(Environment("dev", mapOf("base" to "http://h"))) } }
    private val controller = RunController(locator) {
        Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, JsonExtractor { _, _ -> "t" }))
    }
    private val file = Path.of("/tmp/x/cancel.http")
    private val text = "# @name login\nGET {{base}}/login\n\n###\n# @name me\nGET {{base}}/me\nX: {{login.response.body.\$.token}}\n"
    private val dev = EnvironmentChoice.Named("dev")

    private fun runMe(cancellation: RunCancellation, done: MutableList<RunOutcome> = CopyOnWriteArrayList()) =
        cancellation.track { controller.run(file, text, RunTarget.AtIndex(1), dev, onOutcome = { done += it }) }

    @Test fun cancellingASendingRunReturnsCancelledAndLeavesTheThreadClean() {
        val cancellation = RunCancellation()
        var result: Tracked<RunResult>? = null
        var flag = true
        val done = CopyOnWriteArrayList<RunOutcome>()
        val t = thread { result = runMe(cancellation, done); flag = Thread.currentThread().isInterrupted }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cancellation.cancel()
        t.join(10_000)
        assertEquals(Tracked.Cancelled, result)
        assertFalse(flag, "the interrupt flag must not leak to a pooled thread")
        assertEquals(listOf("login"), done.map { it.request.name }, "what finished before the cancel stays")
    }

    @Test fun aCancelledRequestIsNotRememberedAndNextRunSendsEverythingAgain() {
        val cancellation = RunCancellation()
        val t = thread { runMe(cancellation) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cancellation.cancel()
        t.join(10_000)
        assertEquals(0, controller.cachedSessions)
        hang = false
        sent.clear()
        cancellation.reset()
        val second = runMe(cancellation)
        assertIs<Tracked.Done<RunResult>>(second)
        assertEquals(listOf("http://h/login", "http://h/me"), sent)
    }

    @Test fun cancelBeforeTheBlockStartsSkipsIt() {
        val cancellation = RunCancellation()
        cancellation.cancel()
        assertTrue(cancellation.isCancelRequested)
        var ran = false
        assertEquals(Tracked.Cancelled, cancellation.track { ran = true })
        assertFalse(ran)
        cancellation.reset()
        assertFalse(cancellation.isCancelRequested)
        assertEquals(Tracked.Done(5), cancellation.track { 5 })
    }

    @Test fun cancelOutsideATrackedBlockDoesNotInterruptTheCaller() {
        val cancellation = RunCancellation()
        cancellation.cancel()
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun otherFailuresPropagateAndACancelThatFinishedFirstIsDone() {
        val cancellation = RunCancellation()
        assertFailsWith<IllegalStateException> { cancellation.track { error("boom") } }
        assertEquals(Tracked.Done("ok"), cancellation.track { cancellation.cancel(); "ok".also { Thread.interrupted() } })
        assertFalse(Thread.currentThread().isInterrupted)
        // a cancelled run that fails for another reason reports that reason
        cancellation.reset()
        assertFailsWith<IllegalStateException> { cancellation.track { cancellation.cancel(); Thread.interrupted(); error("boom") } }
    }

    @Test fun controlsOfferOnlyWhatMakesSenseForTheState() {
        val idle = RunControls(running = false)
        assertTrue(idle.canSend); assertFalse(idle.canCancel); assertTrue(idle.canEdit)
        assertEquals("Send", idle.sendLabel)
        val busy = RunControls(running = true)
        assertFalse(busy.canSend); assertTrue(busy.canCancel); assertFalse(busy.canEdit)
        assertEquals("Running…", busy.sendLabel)
        assertFalse(RunControls(running = false, hasFile = false).canSend)
        assertFalse(RunControls(running = true, hasFile = false).canSend)
    }

    @Test fun cancelledLabelMentionsTheFinishedRequests() {
        assertEquals("Cancelled", cancelledLabel(0))
        assertEquals("Cancelled (1 request had finished)", cancelledLabel(1))
        assertEquals("Cancelled (3 requests had finished)", cancelledLabel(3))
    }
}
