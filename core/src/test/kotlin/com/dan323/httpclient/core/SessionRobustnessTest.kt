package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRobustnessTest {
    private fun session(ex: HttpExecutor) = RequestSession(RequestRunner(ex) { error("no files") }, ex) { _, _ -> null }
    private val ok = HttpExecutor { HttpResponse(204, emptyList(), ByteArray(0), 1) }
    private fun get(name: String? = null, vararg assertions: String) =
        RequestTemplate(name, "GET", "http://h/${name ?: "x"}", assertions = assertions.toList())

    @Test fun interruptedExceptionStopsTheSessionAndRestoresTheFlag() {
        var sends = 0
        val ex = HttpExecutor { sends++; throw InterruptedException("stop") }
        try {
            assertFailsWith<InterruptedException> { session(ex).runAll(listOf(get("a"), get("b")), emptyMap()) }
            assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
            assertEquals(1, sends, "queued requests must not be sent")
        } finally {
            Thread.interrupted() // do not leak the flag into other tests
        }
    }

    @Test fun cancellationWithInterruptCauseRestoresTheFlagEvenIfTheExecutorDidNot() {
        var sends = 0
        val ex = HttpExecutor { sends++; throw java.util.concurrent.CancellationException("cancelled").also { it.initCause(InterruptedException()) } }
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { session(ex).runAll(listOf(get("a"), get("b")), emptyMap()) }
            assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
            assertEquals(1, sends)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun aCallerQueuedBehindARunningRequestCanBeCancelled() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ex = HttpExecutor { inFlight.countDown(); release.await(10, TimeUnit.SECONDS); HttpResponse(200, emptyList(), ByteArray(0), 1) }
        val s = session(ex)
        val first = Thread { s.run(get("a"), emptyMap()) }.also { it.start() }
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        var failure: Throwable? = null
        val second = Thread { try { s.run(get("b"), emptyMap()) } catch (e: Throwable) { failure = e } }
        second.start()
        Thread.sleep(100)
        second.interrupt()
        second.join(5_000)
        assertFalse(second.isAlive, "queued caller must stop waiting when interrupted")
        assertTrue(failure is InterruptedException, "$failure")
        release.countDown()
        first.join(5_000)
        assertFalse(s.hasResponse("b"))
    }

    @Test fun otherExceptionsStillBecomeFailures() {
        val ex = HttpExecutor { error("boom") }
        val outcomes = session(ex).runAll(listOf(get("a"), get("b")), emptyMap())
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is RunOutcome.Failure })
    }

    @Test fun concurrentRunsAreSerialisedAndKeepAllResponses() {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val ex = HttpExecutor {
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now, ::maxOf)
            Thread.sleep(5)
            active.decrementAndGet()
            HttpResponse(200, emptyList(), ByteArray(0), 1)
        }
        val s = session(ex)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val futures = (0 until threads).map { i ->
            pool.submit {
                start.await()
                repeat(5) { j -> s.run(get("r$i-$j"), emptyMap()) }
                s.runAll(listOf(get("all$i")), emptyMap())
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, maxActive.get(), "the session must not run requests concurrently")
        for (i in 0 until threads) {
            assertTrue(s.hasResponse("all$i"))
            repeat(5) { j -> assertTrue(s.hasResponse("r$i-$j")) }
        }
    }

    @Test fun hasResponseDoesNotBlockWhileARunIsInFlight() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ex = HttpExecutor { inFlight.countDown(); release.await(10, TimeUnit.SECONDS); HttpResponse(200, emptyList(), ByteArray(0), 1) }
        val s = session(ex)
        val pool = Executors.newSingleThreadExecutor()
        val f = pool.submit { s.run(get("slow"), emptyMap()) }
        assertTrue(inFlight.await(10, TimeUnit.SECONDS))
        assertFalse(s.hasResponse("slow"))
        release.countDown()
        f.get(10, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(s.hasResponse("slow"))
    }

    private fun assertResults(vararg assertions: String, vars: Map<String, String>) =
        (session(ok).runAll(listOf(get(null, *assertions)), vars).single() as RunOutcome.Success).assertions

    @Test fun invalidAssertionKeepsItsReasonWhenAVariableIsUsed() {
        val r = assertResults("status matches {{re}}", "status < {{n}}", "status == {{u}}", vars = mapOf("re" to "(", "n" to "abc", "u" to "204"))
        assertTrue("bad regex" in r[0].message, r[0].message)
        assertTrue(r[0].message.startsWith("invalid assertion:"), r[0].message)
        assertTrue("needs numbers" in r[1].message, r[1].message)
        assertTrue(r[2].passed)
    }

    @Test fun badRegexDetailIsNotEchoedWhenValuesAreHidden() {
        val secret = "hunter2"
        val r = assertResults(
            "status matches \\N{{{s}}}", "status matches \\k<{{s}}>", "status matches (?<{{s}}>a)(?<{{s}}>b)",
            "status {{op}}",
            vars = mapOf("s" to secret, "op" to "!contains"),
        )
        r.forEach { assertFalse(secret in it.message, it.message); assertFalse("index" in it.message, it.message) }
        r.take(3).forEach { assertEquals("invalid assertion: bad regex", it.message) }
        assertFalse("contains" in r[3].message, r[3].message)
    }

    @Test fun invalidReasonNeverEchoesResolvedValues() {
        val secret = "hunter2"
        val r = assertResults(
            "status < {{s}}",
            "{{s}} == 1",
            "status {{s}} 1",
            "status matches {{s}}{",
            "status matches (?<{{s}}>x",
            "status == {{s}}",
            vars = mapOf("s" to secret),
        )
        r.forEach { assertFalse(secret in it.message, it.message); assertEquals(null, it.expected) }
        assertTrue("needs numbers" in r[0].message)
        assertTrue("unknown expression" in r[1].message)
        assertTrue("unknown operator" in r[2].message)
        assertTrue(r[3].message.startsWith("invalid assertion: bad regex"))
        assertEquals("failed (expected value contains substitutions, not shown)", r[5].message)
    }
}
