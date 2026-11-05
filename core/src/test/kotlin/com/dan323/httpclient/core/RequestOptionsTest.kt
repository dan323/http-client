package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestOptionsTest {
    private val runner = RequestRunner({ error("unused") }) { error("no files") }

    private fun options(settings: Map<String, String>, vars: Map<String, String> = emptyMap()) =
        runner.resolve(RequestTemplate("r", "GET", "http://x", settings = settings), vars).options

    @Test
    fun `defaults without settings`() = assertEquals(RequestOptions(), options(emptyMap()))

    private fun failure(settings: Map<String, String>, vars: Map<String, String> = emptyMap()) =
        assertFailsWith<IllegalArgumentException> { options(settings, vars) }.message!!

    @Test
    fun `resolves timeout units, no-redirect and proxy`() {
        assertEquals(5000L, options(mapOf("timeout" to "5")).timeoutMillis)
        assertEquals(250L, options(mapOf("timeout" to "250 ms")).timeoutMillis)
        assertEquals(120_000L, options(mapOf("timeout" to "2m")).timeoutMillis)
        assertEquals(false, options(mapOf("no-redirect" to "")).followRedirects)
        assertEquals(ProxyAddress("h", 3128), options(mapOf("proxy" to "{{p}}"), mapOf("p" to "h:3128")).proxy)
    }

    @Test
    fun `timeout units are case-insensitive and whitespace is trimmed`() {
        assertEquals(5000L, options(mapOf("timeout" to "5S")).timeoutMillis)
        assertEquals(500L, options(mapOf("timeout" to "500MS")).timeoutMillis)
        assertEquals(60_000L, options(mapOf("timeout" to " 1M ")).timeoutMillis)
    }

    @Test
    fun `timeout may use a variable`() =
        assertEquals(3000L, options(mapOf("timeout" to "{{t}}s"), mapOf("t" to "3")).timeoutMillis)

    @Test
    fun `invalid timeouts fail with a clear message`() {
        for (bad in listOf("soon", "", "0", "0ms", "1.5s", "-1", "99999999999999999999", "9223372036854775807m")) {
            assertTrue(failure(mapOf("timeout" to bad)).startsWith("Invalid @timeout"), "timeout '$bad'")
        }
    }

    @Test
    fun `no-redirect accepts true or false and rejects other values`() {
        assertEquals(false, options(mapOf("no-redirect" to "true")).followRedirects)
        assertEquals(true, options(mapOf("no-redirect" to "false")).followRedirects)
        assertEquals(true, options(mapOf("no-redirect" to "{{x}}"), mapOf("x" to "false")).followRedirects)
        assertTrue(failure(mapOf("no-redirect" to "maybe")).startsWith("Invalid @no-redirect"))
    }

    @Test
    fun `proxy forms and default port`() {
        assertEquals(ProxyAddress("h", 80), options(mapOf("proxy" to "h")).proxy)
        assertEquals(ProxyAddress("h", 8080), options(mapOf("proxy" to "http://h:8080/")).proxy)
        assertEquals(ProxyAddress("::1", 8080), options(mapOf("proxy" to "[::1]:8080")).proxy)
    }

    @Test
    fun `invalid proxies fail at resolve time`() {
        for (bad in listOf("", "  ", "h:abc", "h:0", "h:70000", "socks://h:1080", "https://h:443", "u:p@h:8080", "::1:8080", "h:80/path")) {
            assertTrue(failure(mapOf("proxy" to bad)).startsWith("Invalid @proxy"), "proxy '$bad'")
        }
    }

    @Test
    fun `undefined variable in proxy does not leak into the host`() {
        assertFailsWith<com.dan323.httpclient.core.application.UndefinedVariableException> { options(mapOf("proxy" to "{{nope}}:80")) }
    }

    @Test
    fun `setting names are case-insensitive and unknown settings are ignored`() {
        val o = options(mapOf("TimeOut" to "1", "NO-Cookie-Jar" to "", "whatever" to "x"))
        assertEquals(RequestOptions(timeoutMillis = 1000, useCookieJar = false), o)
        assertEquals(RequestOptions(), options(mapOf("whatever" to "x", "no-cookie-jar" to "false")))
    }

    @Test
    fun `timeout, no-redirect and proxy combine`() {
        val o = options(mapOf("timeout" to "2s", "no-redirect" to "", "proxy" to "p:1"))
        assertEquals(RequestOptions(2000, false, ProxyAddress("p", 1)), o)
    }
}
