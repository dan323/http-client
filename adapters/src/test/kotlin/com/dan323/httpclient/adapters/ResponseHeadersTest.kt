package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Header
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseHeadersTest {
    @Test fun http2PseudoHeadersAreLeftOut() {
        val headers = responseHeaders(
            mapOf(":status" to listOf("200"), "content-type" to listOf("application/json"), "set-cookie" to listOf("a=1", "b=2")),
        )
        assertEquals(listOf(Header("content-type", "application/json"), Header("set-cookie", "a=1"), Header("set-cookie", "b=2")), headers)
    }

    @Test fun otherHeadersKeepTheirOrderAndRepeats() {
        assertEquals(listOf(Header("x", "1"), Header("x", "2")), responseHeaders(mapOf("x" to listOf("1", "2"))))
    }
}
