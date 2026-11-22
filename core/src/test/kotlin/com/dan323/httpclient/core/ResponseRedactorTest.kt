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
        val redactor = ResponseRedactor(session.substitutedValues())
        val body = redactor.redact(response.bodyAsString())
        assertFalse("s3cret" in body)
        assertTrue("{{key}}" in body)
        assertEquals("{{key}}", redactor.redact(response.header("X-Key")!!))
    }

    @Test fun shortValuesAreHiddenToo() {
        val t = RequestTemplate(null, "GET", "http://h/", listOf(Header("X-Pin", "{{pin}}")))
        val response = session.run(t, mapOf("pin" to "7"))
        assertEquals("{{pin}}", ResponseRedactor(session.substitutedValues()).redact(response.header("X-Pin")!!))
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
        val shown = ResponseRedactor(session.substitutedValues()).redact(response.header("Authorization")!!)
        assertFalse(encoded in shown)
        assertFalse("pw-123" in shown)
    }

    @Test fun valuesOfChainedResponsesAreHidden() {
        val login = RequestTemplate("login", "POST", "http://h/login")
        val use = RequestTemplate(null, "GET", "http://h/me", listOf(Header("X-T", "{{login.response.body.$.token}}")))
        val outcomes = session.runChained(listOf(login, use), use, emptyMap())
        val response = (outcomes.last() as com.dan323.httpclient.core.application.RunOutcome.Success).response
        assertFalse("tok-9" in ResponseRedactor(session.substitutedValues()).redact(response.header("X-T")!!))
    }

    @Test fun valuesOfEveryRequestOfACallAreKeptAndTheNextCallStartsFresh() {
        val a = RequestTemplate(null, "GET", "http://h/{{one}}")
        val b = RequestTemplate(null, "GET", "http://h/{{two}}")
        session.runAll(listOf(a, b), mapOf("one" to "1111", "two" to "2222"))
        assertEquals(setOf("1111", "2222"), session.substitutedValues().keys)
        session.run(b, mapOf("two" to "2222"))
        assertEquals(setOf("2222"), session.substitutedValues().keys)
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

    @Test fun overlappingValuesAreHiddenAsOneStretchWhateverTheOrder() {
        val r = ResponseRedactor(mapOf("xT" to "short", "TOPSECRET" to "key"))
        val shown = r.redact("a xTOPSECRET b")
        assertFalse("OPS" in shown || "TOP" in shown || "ECRET" in shown, shown)
        assertEquals("a {{short}}{{key}} b", shown)
        // and the other way round: the value that starts first is the longer one
        assertEquals("{{key}}", ResponseRedactor(mapOf("TOPSECRET" to "key", "ECRETxyz" to "tail")).redact("TOPSECRET"))
        assertEquals("{{key}}{{tail}}", ResponseRedactor(mapOf("TOPSECRET" to "key", "ECRETxyz" to "tail")).redact("TOPSECRETxyz"))
    }

    @Test fun aValueInsideALongerOneStillShowsOnlyTheLongerName() {
        assertEquals("{{long}}", ResponseRedactor(mapOf("secret" to "long", "cre" to "inner")).redact("secret"))
    }

    @Test fun aPrefixHandlesOverlappingValuesAtTheEdge() {
        val r = ResponseRedactor(mapOf("xT" to "short", "TOPSECRET" to "key"))
        // the edge falls after "xTOPS" of "xTOPSECRET": the whole stretch is hidden and nothing of it follows
        assertEquals("{{short}}{{key}}", r.redactPrefix("xTOPSECRET", 5))
        assertEquals("", r.redactPrefix("xTOPSECRET", 0))
        assertEquals("ab{{short}}{{key}}", r.redactPrefix("abxTOPSECRETcd", 4))
    }

    @Test fun aHugeTextFullOfMatchesIsProcessedOnlyUpToTheOutputLimit() {
        // a million matches would be a million groups and a five-million-character result; with a limit the work stops early
        val text = "a".repeat(1_000_000)
        val shown = ResponseRedactor(mapOf("a" to "v")).redactPrefix(text, text.length, limit = 1000)
        assertTrue(shown.length in 1000..1010, "stops soon after the limit: ${shown.length}")
        assertTrue(shown.startsWith("{{v}}{{v}}"))
    }

    @Test fun aLongStretchOfPlainTextIsNotCopiedBeyondTheLimit() {
        val text = "b".repeat(1_000_000) + "TOPSECRET"
        val shown = ResponseRedactor(mapOf("TOPSECRET" to "k")).redactPrefix(text, text.length, limit = 100)
        assertEquals("b".repeat(100), shown)
    }

    @Test fun theLimitDoesNotChangeWhatIsShownWhenTheOutputIsShorterThanIt() {
        val r = ResponseRedactor(mapOf("xT" to "short", "TOPSECRET" to "key"))
        assertEquals(r.redactPrefix("ab xTOPSECRET cd", 16), r.redactPrefix("ab xTOPSECRET cd", 16, limit = 1000))
        assertEquals("ab {{short}}{{key}} c", r.redactPrefix("ab xTOPSECRET cd", 16, limit = 21))
    }

    @Test fun aLongRunOfOverlappingMatchesOfOneValueStaysOneGroupWithOneName() {
        // every position of "aaaa..." matches "aa" and extends the same group; its names must not grow with it
        val text = "a".repeat(1_000_000)
        val r = ResponseRedactor(mapOf("aa" to "two"))
        assertEquals("{{two}}", r.redactPrefix(text, text.length))
        assertEquals("{{two}}", r.redact(text))
        assertEquals("{{two}}", r.redactPrefix(text, text.length, limit = 1000)) // the group is still one when the limit is small
    }

    @Test fun overlappingValuesKeepEachNameOnceInOrder() {
        val r = ResponseRedactor(mapOf("ab" to "p", "bc" to "q", "cd" to "p"))
        assertEquals("{{p}}{{q}}", r.redact("abcd")) // p (ab), q (bc), then cd is p again: shown once
    }

    @Test fun aLongValueOverARepetitiveBodyCostsTimeInProportionToTheBodyNotTheProduct() {
        // 65,536 a's over a million a's: a matcher that compares the whole value at every position makes ~6 x 10^10 comparisons
        val value = "a".repeat(65_536)
        val text = "a".repeat(1_000_000)
        val started = System.nanoTime()
        val shown = ResponseRedactor(mapOf(value to "v")).redact(text)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals("{{v}}", shown)
        assertTrue(millis < 5_000, "took $millis ms")
        assertEquals("{{v}}", ResponseRedactor(mapOf(value to "v")).redactPrefix(text, text.length, limit = 100))
    }

    @Test fun anOccurrenceThatReachesBackIntoSeveralEarlierStretchesJoinsThemAll() {
        // ab (0,2) and cd (2,4) are separate stretches; bcde (1,5) reaches into both, and cd lies inside it
        val r = ResponseRedactor(mapOf("ab" to "a", "cd" to "b", "bcde" to "c"))
        assertEquals("{{a}}{{c}}", r.redact("abcde"))
        assertEquals("x{{a}}{{c}}y", r.redact("xabcdey"))
    }

    @Test fun separateStretchesStaySeparateAndKeepTheirTextBetween() {
        val r = ResponseRedactor(mapOf("ab" to "a", "cd" to "b"))
        assertEquals("{{a}}{{b}}-{{a}}", r.redact("abcd-ab")) // abcd: two stretches that touch, not overlap
        assertEquals("1{{a}}2{{b}}3", r.redact("1ab2cd3"))
    }

    @Test fun aValueFoundInsideALongerOneAddsNoNameEvenWhenItEndsFirst() {
        val r = ResponseRedactor(mapOf("secret" to "long", "cre" to "inner"))
        assertEquals("{{long}}", r.redact("secret"))
        assertEquals("a{{inner}}b", r.redact("acreb")) // alone it is named
    }

    @Test fun everyFormOfAValueIsFoundWithTheAutomatonToo() {
        val r = ResponseRedactor(mapOf("a b" to "v"))
        assertEquals("{{v}} {{v}} {{v}}", r.redact("a b a%20b a+b"))
    }

    @Test fun aHeaderNameIsRedactedWhateverItsCase() {
        val r = ResponseRedactor(mapOf("Topsecret" to "key"))
        assertEquals("{{key}}", r.redactHeaderName("topsecret"))
        assertEquals("{{key}}", r.redactHeaderName("TOPSECRET"))
        assertEquals("x-{{key}}-y", r.redactHeaderName("X-TopSecret-Y"))
        assertEquals("X-Request-Id", r.redactHeaderName("X-Request-Id")) // untouched, with its case
        assertEquals("X-Whatever", ResponseRedactor(emptyMap()).redactHeaderName("X-Whatever"))
    }
}
