package com.dan323.httpclient.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedResolverTest {
    private val loopback = listOf(InetAddress.getByName("127.0.0.1"))

    @Test fun answersWithinTheLimit() = assertEquals(loopback, BoundedResolver(1000) { loopback }("h"))

    @Test fun aSlowLookupCountsAsUnresolvable() {
        val slow = BoundedResolver(50) { Thread.sleep(5000); loopback }
        assertFailsWith<UnknownHostException> { slow("h") }
    }

    @Test fun aFailedLookupKeepsItsKind() {
        assertFailsWith<UnknownHostException> { BoundedResolver(1000) { throw UnknownHostException(it) }("h") }
    }
}
