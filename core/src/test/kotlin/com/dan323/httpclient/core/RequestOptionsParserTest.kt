package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestOptionsParser
import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestOptionsParserTest {
    private fun parse(settings: Map<String, String>, vars: Map<String, String> = emptyMap()) =
        RequestOptionsParser.parse(settings) { t -> Regex("""\{\{(\w+)}}""").replace(t) { vars.getValue(it.groupValues[1]) } }

    @Test fun defaults() = assertEquals(RequestOptions(), parse(emptyMap()))

    @Test fun parsesAllSettingsCaseInsensitively() {
        val o = parse(mapOf("TIMEOUT" to "2 S", "No-Redirect" to "", "no-cookie-jar" to "true", "proxy" to "http://[::1]:8080"))
        assertEquals(RequestOptions(2000, false, ProxyAddress("::1", 8080), false), o)
    }

    @Test fun flagFalseKeepsTheDefault() =
        assertEquals(RequestOptions(), parse(mapOf("no-redirect" to "false", "no-cookie-jar" to "FALSE")))

    @Test fun errorsQuoteTheWrittenValueNotTheResolvedOne() {
        val e = assertFailsWith<IllegalArgumentException> { parse(mapOf("timeout" to "{{t}}"), mapOf("t" to "sekret")) }
        assertEquals("Invalid @timeout value: '{{t}}'", e.message)
    }

    @Test fun rejectsBadValues() {
        listOf(
            mapOf("timeout" to "0"), mapOf("timeout" to "99999999999999999999"), mapOf("timeout" to "9223372036854775807 m"),
            mapOf("no-redirect" to "maybe"), mapOf("proxy" to "https://h"), mapOf("proxy" to "u:p@h"), mapOf("proxy" to "h/path"),
        ).forEach { assertFailsWith<IllegalArgumentException>(it.toString()) { parse(it) } }
    }
}
