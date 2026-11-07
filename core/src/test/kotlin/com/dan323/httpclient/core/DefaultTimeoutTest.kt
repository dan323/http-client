package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestOptionsParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestTimeouts
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.FileReader
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DefaultTimeoutTest {
    private class Capturing : HttpExecutor {
        val sent = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            sent += request
            return HttpResponse(200, emptyList(), ByteArray(0), 1)
        }
    }

    private val noFiles = FileReader { error("no files") }

    private fun timeoutOf(text: String, default: Long?): Long? {
        val exec = Capturing()
        val runner = RequestRunner(exec, noFiles, DynamicVariables(), default)
        runner.run(HttpFileParser().parse(text).requests.single(), emptyMap())
        return exec.sent.single().options.timeoutMillis
    }

    @Test fun builtInDefaultIsSixtySeconds() = assertEquals(60_000L, RequestTimeouts.DEFAULT_MILLIS)

    @Test fun aRequestWithoutTimeoutGetsTheDefault() = assertEquals(5_000L, timeoutOf("GET http://h/", 5_000))

    @Test fun theRequestSettingBeatsTheDefault() = assertEquals(2_000L, timeoutOf("# @timeout 2s\nGET http://h/", 60_000))

    @Test fun withoutADefaultThereIsNoTimeoutAsBefore() = assertNull(timeoutOf("GET http://h/", null))

    @Test fun parserAppliesTheDefaultOnlyWhenTheSettingIsAbsent() {
        assertEquals(7_000L, RequestOptionsParser.parse(emptyMap(), 7_000) { it }.timeoutMillis)
        assertEquals(1L, RequestOptionsParser.parse(mapOf("timeout" to "1ms"), 7_000) { it }.timeoutMillis)
    }

    @Test fun parsesDurations() {
        assertEquals(30_000L, RequestTimeouts.parseMillis("30"))
        assertEquals(500L, RequestTimeouts.parseMillis("500MS"))
        assertEquals(120_000L, RequestTimeouts.parseMillis(" 2 m "))
    }

    @Test fun errorsNameTheSettingAndQuoteTheWrittenText() {
        val zero = assertFailsWith<IllegalArgumentException> { RequestTimeouts.parseMillis("0", label = "--timeout") }
        assertEquals("Invalid --timeout value: '0' (must be greater than zero)", zero.message)
        val written = assertFailsWith<IllegalArgumentException> { RequestTimeouts.parseMillis("sekret", "{{t}}") }
        assertEquals("Invalid @timeout value: '{{t}}'", written.message)
        assertFailsWith<IllegalArgumentException> { RequestTimeouts.parseMillis("1.5s") }
        assertFailsWith<IllegalArgumentException> { RequestTimeouts.parseMillis("") }
        assertFailsWith<IllegalArgumentException> { RequestTimeouts.parseMillis("9223372036854775807 m") }
    }
}
