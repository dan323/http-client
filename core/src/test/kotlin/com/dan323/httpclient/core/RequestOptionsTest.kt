package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.domain.RequestOptions
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestOptionsTest {
    private val runner = RequestRunner({ error("unused") }) { error("no files") }

    private fun options(settings: Map<String, String>, vars: Map<String, String> = emptyMap()) =
        runner.resolve(RequestTemplate("r", "GET", "http://x", settings = settings), vars).options

    @Test
    fun `defaults without settings`() = assertEquals(RequestOptions(), options(emptyMap()))

    @Test
    fun `resolves timeout units, no-redirect and proxy`() {
        assertEquals(5000L, options(mapOf("timeout" to "5")).timeoutMillis)
        assertEquals(250L, options(mapOf("timeout" to "250 ms")).timeoutMillis)
        assertEquals(120_000L, options(mapOf("timeout" to "2m")).timeoutMillis)
        assertEquals(false, options(mapOf("no-redirect" to "")).followRedirects)
        assertEquals("h:3128", options(mapOf("proxy" to "{{p}}"), mapOf("p" to "h:3128")).proxy)
    }

    @Test
    fun `invalid timeout fails`() {
        assertFailsWith<IllegalArgumentException> { options(mapOf("timeout" to "soon")) }
    }
}
