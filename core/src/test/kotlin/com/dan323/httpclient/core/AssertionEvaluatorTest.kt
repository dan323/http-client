package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.AssertionEvaluator
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.ResponseExtractor
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssertionEvaluatorTest {
    // Fake JSON extractor: "$.id" -> "7", "$.n" -> "5", "$.name" -> "bob".
    private val extractor = ResponseExtractor { _, p -> mapOf("$.id" to "7", "$.n" to "5", "$.name" to "bob")[p] }
    private val evaluator = AssertionEvaluator(extractor)
    private val response = HttpResponse(200, listOf(Header("Content-Type", "application/json")), "{}".toByteArray(), 1)

    private fun passes(a: String) = evaluator.evaluate(a, response).passed

    @Test fun statusAndNumbers() {
        assertTrue(passes("status == 200"))
        assertFalse(passes("status == 201"))
        assertTrue(passes("status != 404"))
        assertTrue(passes("body.$.n > 3"))
        assertTrue(passes("body.$.n >= 5"))
        assertFalse(passes("body.$.n < 5"))
        assertTrue(passes("body.$.n == 5.0"))
    }

    @Test fun textOperators() {
        assertTrue(passes("headers.content-type contains json"))
        assertTrue(passes("headers.content-type !contains xml"))
        assertTrue(passes("headers.content-type startsWith application/"))
        assertTrue(passes("headers.content-type endsWith /json"))
        assertTrue(passes("body.$.name matches b.b"))
        assertFalse(passes("body.$.name matches b"))
        assertTrue(passes("body.$.name == bob"))
    }

    @Test fun existence() {
        assertTrue(passes("body.$.id exists"))
        assertFalse(passes("body.$.nope exists"))
        assertTrue(passes("body.$.nope !exists"))
        assertTrue(passes("headers.X-Missing !exists"))
    }

    @Test fun failureReportsExpectedAndActual() {
        val r = evaluator.evaluate("status == 201", response)
        assertFalse(r.passed)
        assertEquals("201", r.expected)
        assertEquals("200", r.actual)
        assertContains(r.message, "but was 200")
        assertNull(evaluator.evaluate("body.$.nope == 1", response).actual)
    }

    @Test fun invalidAssertionsFailWithMessage() {
        assertContains(evaluator.evaluate("status", response).message, "missing operator")
        assertContains(evaluator.evaluate("status ~~ 1", response).message, "unknown operator")
        assertContains(evaluator.evaluate("status ==", response).message, "expected value")
        assertContains(evaluator.evaluate("body.$.name > 1", response).message, "needs numbers")
        assertFalse(passes("body.$.name matches ["))
    }

    @Test fun parserKeepsAssertionsSeparateFromSettings() {
        val r = HttpFileParser().parse("# @timeout 5\n# @assert status == 200\n// @assert body.$.id exists\nGET http://x\n").requests.single()
        assertEquals(listOf("status == 200", "body.$.id exists"), r.assertions)
        assertEquals(mapOf("timeout" to "5"), r.settings)
    }

    @Test fun sessionAttachesResultsToSuccess() {
        val executor = HttpExecutor { response }
        val session = RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, p -> if (p == "$.id") "7" else null }
        val file = HttpFileParser().parse("# @assert status == 200\n# @assert status == 500\nGET http://x\n\n###\nGET http://y\n")
        val (a, b) = session.runAll(file.requests, emptyMap()).map { it as RunOutcome.Success }
        assertEquals(listOf(true, false), a.assertions.map { it.passed })
        assertFalse(a.assertionsPassed)
        assertTrue(b.assertions.isEmpty() && b.assertionsPassed)
        assertTrue(RunOutcome.Success(file.requests[0], response).assertions.isEmpty())
    }
}
