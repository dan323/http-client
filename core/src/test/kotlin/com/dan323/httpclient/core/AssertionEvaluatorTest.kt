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
import com.dan323.httpclient.core.port.out.JsonExtractor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssertionEvaluatorTest {
    // The real ResponseExtractor; only the JSON port is faked (the JSON library lives in an adapter).
    private val json = JsonExtractor { _, p ->
        mapOf(
            "$.id" to "7", "$.n" to "5", "$.name" to "bob", "$.big" to "9007199254740993", "$.zeros" to "007",
            "$.ver" to "1.10", "$.nan" to "NaN", "$.empty" to "", "$.words" to "hello  world", "$.multi" to "a\nb\nc",
        )[p]
    }
    private val evaluator = AssertionEvaluator(ResponseExtractor(json))
    private val xml = "<r><a x=\"1\">hi</a></r>"
    private fun response(body: String = "{}") =
        HttpResponse(200, listOf(Header("Content-Type", "application/json")), body.toByteArray(), 1)

    private fun passes(a: String, body: String = "{}") = evaluator.evaluate(a, response(body)).passed
    private fun message(a: String, body: String = "{}") = evaluator.evaluate(a, response(body)).message

    @Test fun statusAndNumbers() {
        assertTrue(passes("status == 200"))
        assertFalse(passes("status == 201"))
        assertTrue(passes("status != 404"))
        assertFalse(passes("status != 200"))
        assertTrue(passes("body.$.n > 3"))
        assertTrue(passes("body.$.n >= 5"))
        assertFalse(passes("body.$.n < 5"))
        assertFalse(passes("body.$.n > 5"))
        assertTrue(passes("body.$.n <= 5"))
    }

    @Test fun numberEqualitySemantics() {
        assertTrue(passes("body.$.n == 5.0"))       // numeric: same BigDecimal value
        assertTrue(passes("body.$.n == 5e0"))
        assertFalse(passes("body.$.id == 007"))     // leading zeros are not a number: text comparison
        assertTrue(passes("body.$.zeros == 007"))
        assertFalse(passes("body.$.zeros == 7"))
        assertTrue(passes("body.$.ver == 1.1"))     // documented: 1.10 equals 1.1 numerically ...
        assertFalse(passes("body.$.ver == \"1.1\"")) // ... unless the expected value is quoted (exact text)
        assertTrue(passes("body.$.ver == \"1.10\""))
        assertFalse(passes("body.$.big == 9007199254740992")) // above 2^53, still exact
        assertTrue(passes("body.$.big == 9007199254740993"))
        assertTrue(passes("body.$.big > 9007199254740992"))
    }

    @Test fun nanAndInfinityAreText() {
        assertTrue(passes("body.$.nan == NaN"))
        assertFalse(passes("body.$.n == NaN"))
        assertFalse(passes("body.$.nan == 1"))
        assertContains(message("body.$.nan > 1"), "needs numbers")
        assertContains(message("body.$.n < Infinity"), "needs numbers")
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

    @Test fun negativeTextOperators() {
        assertFalse(passes("headers.content-type contains xml"))
        assertFalse(passes("headers.content-type !contains json"))
        assertFalse(passes("headers.content-type startsWith json"))
        assertFalse(passes("headers.content-type endsWith application"))
        assertFalse(passes("body.$.name != bob"))
    }

    @Test fun existence() {
        assertTrue(passes("body.$.id exists"))
        assertFalse(passes("body.$.nope exists"))
        assertTrue(passes("body.$.nope !exists"))
        assertFalse(passes("body.$.id !exists"))
        assertTrue(passes("headers.X-Missing !exists"))
        assertTrue(passes("headers.content-type exists"))
    }

    @Test fun missingValueFailsEveryValueOperator() {
        for (op in listOf("==", "!=", "<", ">", "contains", "!contains", "startsWith", "endsWith", "matches")) {
            val r = evaluator.evaluate("body.$.nope $op 1", response())
            assertFalse(r.passed, "missing value must fail '$op'")
            assertNull(r.actual)
            assertContains(r.message, "but was missing")
        }
        assertFalse(passes("headers.X-Missing != x"))
    }

    @Test fun realExtractorBodyRegexXPathAndWholeBody() {
        assertTrue(passes("body.regex:id=(\\d+) == 42", "id=42"))
        assertTrue(passes("body.regex:nothing !exists", "id=42"))
        assertTrue(passes("body./r/a == hi", xml))
        assertTrue(passes("body.xpath:count(//a) == 1", xml))
        assertTrue(passes("body./r/b !exists", xml))
        assertTrue(passes("body.* contains hi", xml))
    }

    @Test fun xpathStringFunctionsYieldEmptyStringNotMissing() {
        // Documented: a string-valued XPath that finds nothing is "" (exists holds), a node-set that finds nothing is missing.
        assertTrue(passes("body.xpath:string(//nope) exists", xml))
        assertTrue(passes("body.xpath:string(//nope) == \"\"", xml))
        assertTrue(passes("body.//nope !exists", xml))
    }

    @Test fun emptyAndQuotedExpectedValues() {
        assertTrue(passes("body.$.empty == \"\""))
        assertFalse(passes("body.$.name == \"\""))
        assertTrue(passes("body.$.name != \"\""))
        assertTrue(passes("body.$.name == \"bob\""))
        assertTrue(passes("body.$.name == 'bob'"))
        assertContains(message("body.$.name =="), "needs an expected value")
    }

    @Test fun innerSpacesInExpectedAreKept() {
        assertTrue(passes("body.$.words == hello  world"))
        assertFalse(passes("body.$.words == hello world"))
        assertTrue(passes("body.$.words contains o  w"))
    }

    @Test fun expressionsWithSpacesNeedQuotes() {
        assertTrue(passes("\"body.regex:(a b)\" == \"a b\"", "xx a b yy"))
        assertTrue(passes("'body.regex:(a b)' == 'a b'", "xx a b yy"))
        val r = evaluator.evaluate("body.regex:a b == x", response("a b"))
        assertContains(r.message, "unknown operator 'b'")
        assertContains(r.message, "quoted")
        assertContains(message("\"body.regex:a == x"), "unterminated quote")
    }

    @Test fun regexMatchesNewlinesAndGuardsAgainstBacktracking() {
        assertTrue(passes("body.$.multi matches a.*c"))
        assertTrue(passes("body.* matches .*foo.*", "line1\nfoo\nline3"))
        val r = evaluator.evaluate("body.* matches (?:(x+)|x)+\\1y", response("x".repeat(40) + "!"))
        assertFalse(r.passed)
        assertContains(r.message, "invalid assertion")
        assertContains(r.message, "too long")
    }

    @Test fun badRegexReportsMessage() {
        val r = evaluator.evaluate("body.$.name matches [", response())
        assertFalse(r.passed)
        assertContains(r.message, "bad regex")
    }

    @Test fun unknownExpressionsAreInvalidNotMissing() {
        for (a in listOf("stauts !exists", "bodyy.$.x != 1", "header.X !contains y", "status.x exists", "\$.id exists")) {
            val r = evaluator.evaluate(a, response())
            assertFalse(r.passed, a)
            assertContains(r.message, "unknown expression")
        }
    }

    @Test fun invalidAssertionsFailWithMessage() {
        assertContains(message(""), "missing expression")
        assertContains(message("status"), "missing operator")
        assertContains(message("status ~~ 1"), "unknown operator")
        assertContains(message("status =="), "expected value")
        assertContains(message("body.$.name > 1"), "needs numbers")
    }

    @Test fun failureReportsExpectedAndActual() {
        val r = evaluator.evaluate("status == 201", response())
        assertFalse(r.passed)
        assertEquals("201", r.expected)
        assertEquals("200", r.actual)
        assertContains(r.message, "but was 200")
    }

    @Test fun failureMessagesTruncateLongValues() {
        val long = "x".repeat(5000)
        val r = evaluator.evaluate("body.* == y", response(long))
        assertFalse(r.passed)
        assertTrue(r.message.length < 400, "message was ${r.message.length} chars")
        assertContains(r.message, "5000 chars")
    }

    @Test fun parserKeepsAssertionsSeparateFromSettings() {
        val r = HttpFileParser().parse("# @timeout 5\n# @assert status == 200\n// @assert body.$.id exists\nGET http://x\n").requests.single()
        assertEquals(listOf("status == 200", "body.$.id exists"), r.assertions)
        assertEquals(mapOf("timeout" to "5"), r.settings)
    }

    @Test fun parserKeepsBareAssertAndStripsTrailingComment() {
        val r = HttpFileParser().parse(
            "# @assert\n# @assert status == 200 # why\n# @assert body.* contains \"a # b\" # c\n# @assert body.* matches a#b\nGET http://x\n",
        ).requests.single()
        assertEquals(listOf("", "status == 200", "body.* contains \"a # b\"", "body.* matches a#b"), r.assertions)
        assertContains(message(r.assertions[0]), "missing expression")
    }

    private fun session(vararg bodies: HttpResponse, fail: Boolean = false): RequestSession {
        var i = 0
        val executor = HttpExecutor { if (fail && i++ == 0) error("boom") else bodies[minOf(i++, bodies.size - 1)] }
        return RequestSession(RequestRunner(executor) { error("no files") }, executor, json)
    }

    @Test fun sessionAttachesResultsToSuccess() {
        val file = HttpFileParser().parse("# @assert status == 200\n# @assert status == 500\nGET http://x\n\n###\nGET http://y\n")
        val (a, b) = session(response()).runAll(file.requests, emptyMap()).map { it as RunOutcome.Success }
        assertEquals(listOf(true, false), a.assertions.map { it.passed })
        assertFalse(a.assertionsPassed)
        assertTrue(b.assertions.isEmpty() && b.assertionsPassed)
    }

    @Test fun sessionResolvesVariablesInAssertions() {
        val file = HttpFileParser().parse("@who = bob\n# @assert body.$.name == {{who}}\n# @assert status == {{code}}\n# @assert body.$.name == {{nope}}\nGET http://x\n")
        val out = session(response()).runAll(file.requests, mapOf("code" to "200")).single() as RunOutcome.Success
        assertEquals(listOf(true, true, false), out.assertions.map { it.passed })
        assertContains(out.assertions[2].message, "Undefined variable: nope")
        assertEquals("body.$.name == {{who}}", out.assertions[0].assertion)
    }

    @Test fun sessionResolvesResponseReferencesInAssertions() {
        val file = HttpFileParser().parse("# @name first\nGET http://x\n\n###\n# @assert body.$.id == {{first.response.body.$.id}}\nGET http://y\n")
        val outcomes = session(response()).runAll(file.requests, emptyMap())
        assertTrue((outcomes[1] as RunOutcome.Success).assertions.single().passed)
    }

    @Test fun failedRequestReportsAssertionsNotRun() {
        val file = HttpFileParser().parse("# @assert status == 200\n# @assert status == 201\nGET http://x\n\n###\n# @assert status == 200\nGET http://y\n")
        val (a, b) = session(response(), fail = true).runAll(file.requests, emptyMap())
        assertEquals(2, (a as RunOutcome.Failure).assertionsNotRun)
        assertTrue((b as RunOutcome.Success).assertionsPassed)
    }

    @Test fun runChainedOnlyAssertsTheRequestedOne() {
        val file = HttpFileParser().parse(
            "# @name login\n# @assert status == 500\nGET http://x\n\n###\n# @assert status == 200\nGET http://y/{{login.response.status}}\n",
        )
        val outcomes = session(response()).runChained(file.requests, file.requests[1], emptyMap())
        assertEquals(2, outcomes.size)
        assertTrue((outcomes[0] as RunOutcome.Success).assertions.isEmpty())
        assertTrue((outcomes[1] as RunOutcome.Success).assertions.single().passed)
    }
}
