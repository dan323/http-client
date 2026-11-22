package com.dan323.httpclient.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedResolverTest {
    private val loopback = listOf(InetAddress.getByName("127.0.0.1"))

    @Test fun answersWithinTheLimit() = assertEquals(loopback, BoundedResolver(1000, lookup = { loopback })("h"))

    @Test fun aSlowLookupCountsAsUnresolvable() {
        val slow = BoundedResolver(50, lookup = { Thread.sleep(5000); loopback })
        assertFailsWith<UnknownHostException> { slow("h") }
    }

    @Test fun aFailedLookupKeepsItsKind() {
        assertFailsWith<UnknownHostException> { BoundedResolver(1000, lookup = { throw UnknownHostException(it) })("h") }
    }

    @Test fun theCallsRemainingTimeCapsTheWait() {
        val started = System.nanoTime()
        assertFailsWith<UnknownHostException> { BoundedResolver(10_000, remaining = { 60 }, lookup = { Thread.sleep(5000); loopback })("h") }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000, "gave up near the 60 ms budget, not the 10 s default")
    }

    @Test fun noTimeLeftMeansNoLookup() {
        val called = java.util.concurrent.atomic.AtomicBoolean()
        BoundedResolver(1000, remaining = { 0 }, lookup = { called.set(true); loopback }).use { r ->
            assertFailsWith<UnknownHostException> { r("h") }
            Thread.sleep(100) // a lookup that was started would have run by now
            assertFalse(called.get(), "no lookup is started when no time is left")
        }
    }

    @Test fun whenAllWorkersAreBusyTheNextLookupIsRejectedNotTimedOut() {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        // a 5 s wait: a rejection comes back at once, a timeout would take the whole 5 s
        BoundedResolver(5_000, workers = 1, lookup = {
            entered.countDown()
            // like a resolver that ignores the interrupt of a cancelled caller
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            loopback
        }).use { r ->
            val first = Thread { runCatching { r("a") } }.also { it.start() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the first lookup is running")
            val started = System.nanoTime()
            assertFailsWith<UnknownHostException> { r("b") }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000, "rejected without waiting for the 5 s")
            release.countDown()
            first.join(10_000)
        }
    }
}
