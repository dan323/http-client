package com.dan323.httpclient.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertFailsWith<UnknownHostException> { BoundedResolver(1000, remaining = { 0 }, lookup = { error("must not be called") })("h") }
    }

    @Test fun whenAllWorkersAreStuckTheNextLookupFailsAtOnce() {
        val release = java.util.concurrent.CountDownLatch(1)
        BoundedResolver(30, workers = 1, lookup = {
            // like a resolver that ignores the interrupt of a cancelled caller
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            loopback
        }).use { r ->
            assertFailsWith<UnknownHostException> { r("a") } // times out, but its worker stays busy
            val started = System.nanoTime()
            assertFailsWith<UnknownHostException> { r("b") }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 200, "rejected without waiting")
            release.countDown()
        }
    }
}
