package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.ResponseRedactor
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseRedactorTest {
    private val echo = HttpExecutor { req ->
        // echoes what it received, like httpbin's /headers
        HttpResponse(200, req.headers.map { Header(it.name, it.value) }, """{"url":"${req.url}","token":"tok-9"}""".toByteArray(), 1)
    }
    private val session = RequestSession(RequestRunner(echo) { error("no files") }, echo) { json, path ->
        if (path == "$.token") Regex("\"token\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) else null
    }

    @Test fun hidesPrivateEnvironmentValuesInBodyAndHeaders() {
        val t = RequestTemplate(null, "GET", "http://h/?k={{key}}", listOf(Header("X-Key", "{{key}}")))
        val response = session.run(t, mapOf("key" to "s3cret"))
        val out = ResponseRedactor(session.substitutedValues()).redact(response)
        assertFalse("s3cret" in out.bodyAsString())
        assertTrue("{{key}}" in out.bodyAsString())
        assertEquals("{{key}}", out.header("X-Key"))
    }

    @Test fun shortValuesAreHiddenToo() {
        val t = RequestTemplate(null, "GET", "http://h/", listOf(Header("X-Pin", "{{pin}}")))
        val response = session.run(t, mapOf("pin" to "7"))
        assertEquals("{{pin}}", ResponseRedactor(session.substitutedValues()).redact(response).header("X-Pin"))
    }

    @Test fun urlEncodedFormIsHidden() {
        val redactor = ResponseRedactor(mapOf("a b&c" to "v"))
        assertEquals("q={{v}} and {{v}}", redactor.redact("q=a%20b%26c and a+b%26c"))
    }

    @Test fun basicAuthBase64IsHidden() {
        val t = RequestTemplate(null, "GET", "http://h/", listOf(Header("Authorization", "Basic {{u}} {{p}}")))
        val response = session.run(t, mapOf("u" to "alice", "p" to "pw-123"))
        val encoded = Base64.getEncoder().encodeToString("alice:pw-123".toByteArray())
        assertEquals("Basic $encoded", response.header("Authorization"))
        val out = ResponseRedactor(session.substitutedValues()).redact(response)
        assertFalse(encoded in out.header("Authorization")!!)
        assertFalse("pw-123" in out.header("Authorization")!!)
    }

    @Test fun valuesOfChainedResponsesAreHidden() {
        val login = RequestTemplate("login", "POST", "http://h/login")
        val use = RequestTemplate(null, "GET", "http://h/me", listOf(Header("X-T", "{{login.response.body.$.token}}")))
        val outcomes = session.runChained(listOf(login, use), use, emptyMap())
        val response = (outcomes.last() as com.dan323.httpclient.core.application.RunOutcome.Success).response
        val out = ResponseRedactor(session.substitutedValues()).redact(response)
        assertFalse("tok-9" in out.header("X-T")!!)
    }

    @Test fun valuesOfEveryRequestOfACallAreKeptAndTheNextCallStartsFresh() {
        val a = RequestTemplate(null, "GET", "http://h/{{one}}")
        val b = RequestTemplate(null, "GET", "http://h/{{two}}")
        session.runAll(listOf(a, b), mapOf("one" to "1111", "two" to "2222"))
        assertEquals(setOf("1111", "2222"), session.substitutedValues().keys)
        session.run(b, mapOf("two" to "2222"))
        assertEquals(setOf("2222"), session.substitutedValues().keys)
    }

    @Test fun binaryBodyWithoutAMatchIsUntouched() {
        val bytes = byteArrayOf(0, -1, -2, 5)
        val r = HttpResponse(200, emptyList(), bytes, 1)
        assertContentEquals(bytes, ResponseRedactor(mapOf("secret" to "s")).redact(r).body)
    }

    @Test fun noValuesChangesNothing() {
        assertEquals("text", ResponseRedactor(emptyMap()).redact("text"))
    }

    @Test fun aPrefixKeepsTheFirstCharactersAndHidesAValueThatStartsInsideThem() {
        val r = ResponseRedactor(mapOf("TOPSECRET" to "key"))
        assertEquals("ab{{key}}", r.redactPrefix("abTOPSECRETcd", 4)) // TOPSECRET starts at 2, before the edge: hidden whole, nothing after it
        assertEquals("abc", r.redactPrefix("abcTOPSECRET", 3)) // starts at the edge: not shown at all
        assertEquals("abTOP", ResponseRedactor(emptyMap()).redactPrefix("abTOPxx", 5)) // plain text is cut at the edge
    }

    @Test fun aPrefixEdgeNeverLeavesAPieceOfAValueAndDoesNotDependOnPlaceholderLength() {
        val longName = "N".repeat(200)
        val r = ResponseRedactor(mapOf("TOPSECRET" to "key", "E" to longName))
        // the text ends in the start of a value; the placeholder for "E" would be 204 characters long
        val text = "x".repeat(95) + "TOPSE"
        // the caller keeps less than the text minus a margin longer than any piece of a value: the fragment at the end starts at 95
        for (keep in 0..95) {
            val shown = r.redactPrefix(text, keep)
            assertFalse("TOPS" in shown, "keep=$keep: $shown")
            assertFalse("{{" in shown.drop(keep), "the placeholder of E (204 characters) is not what ends the text: $shown")
        }
        assertEquals("x".repeat(95), r.redactPrefix(text, 95))
    }

    @Test fun aPrefixLongerThanTheTextIsTheWholeTextRedacted() {
        val r = ResponseRedactor(mapOf("s3cret" to "k"))
        assertEquals("a {{k}} b", r.redactPrefix("a s3cret b", 1000))
        assertEquals("", r.redactPrefix("abc", -5))
        assertEquals("abc", ResponseRedactor(emptyMap()).redactPrefix("abcdef", 3))
    }
}
