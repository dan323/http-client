package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasicAuthTest {
    private val runner = RequestRunner(HttpExecutor { error("not sent") }) { error("no files") }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun resolve(headers: List<Header>, vars: Map<String, String> = emptyMap()): HttpRequest =
        runner.resolve(RequestTemplate(null, "GET", "http://h", headers), vars)

    private fun auth(value: String, vars: Map<String, String> = emptyMap(), name: String = "Authorization") =
        resolve(listOf(Header(name, value)), vars).headers.single().value

    @Test fun spaceSeparated() = assertEquals("Basic ${b64("user:pass")}", auth("Basic user pass"))
    @Test fun colonSeparated() = assertEquals("Basic ${b64("user:pass")}", auth("Basic user:pass"))
    @Test fun alreadyEncodedUntouched() = assertEquals("Basic ${b64("user:pass")}", auth("Basic ${b64("user:pass")}"))
    @Test fun encodedWithPaddingUntouched() = assertEquals("Basic dXNlcjE6cA==", auth("Basic dXNlcjE6cA=="))
    @Test fun variablesResolveFirst() =
        assertEquals("Basic ${b64("ann:p w")}", auth("Basic {{u}} {{p}}", mapOf("u" to "ann", "p" to "p w")))
    @Test fun variableHoldingEncodedValueUntouched() =
        assertEquals("Basic ${b64("a:b")}", auth("Basic {{t}}", mapOf("t" to b64("a:b"))))
    @Test fun variableHoldingColonCredentials() =
        assertEquals("Basic ${b64("a:b")}", auth("Basic {{t}}", mapOf("t" to "a:b")))
    @Test fun emptyPassword() = assertEquals("Basic ${b64("user:")}", auth("Basic user:"))
    @Test fun colonInPassword() = assertEquals("Basic ${b64("user:pa:ss")}", auth("Basic user:pa:ss"))
    @Test fun spaceInPassword() = assertEquals("Basic ${b64("user:pa ss")}", auth("Basic user pa ss"))
    @Test fun nonAscii() = assertEquals("Basic ${b64("üser:pässwörd€")}", auth("Basic üser:pässwörd€"))
    @Test fun headerNameAndSchemeCase() {
        assertEquals("Basic ${b64("u:p")}", auth("basic u p", name = "authorization"))
        assertEquals("Basic ${b64("u:p")}", auth("BASIC   u:p", name = "AUTHORIZATION"))
    }
    @Test fun repeatedHeadersEachHandled() {
        val h = resolve(listOf(Header("Authorization", "Basic a b"), Header("X-A", "Basic a b"), Header("Authorization", "Basic c:d")))
        assertEquals(listOf("Basic ${b64("a:b")}", "Basic a b", "Basic ${b64("c:d")}"), h.headers.map { it.value })
    }
    @Test fun otherSchemesUntouched() {
        for (v in listOf("Bearer a b", "Bearer {{t}}", "Digest user pass", "Basic", "Basic ", "Token a:b"))
            assertEquals(v.replace("{{t}}", "tok"), auth(v, mapOf("t" to "tok")), v)
    }

    @Test fun failureMessageHidesEncodedAndPlainCredentials() {
        val secretPass = "hunter2-S3CR3T"
        val encoded = b64("bob:$secretPass")
        for (value in listOf("Basic {{u}} {{p}}", "Basic bob $secretPass", "Basic bob:$secretPass")) {
            val exec = HttpExecutor { req ->
                error("rejected ${req.headers.single().value} / bob:$secretPass / $secretPass / " +
                    java.net.URLEncoder.encode(encoded, Charsets.UTF_8))
            }
            val s = RequestSession(RequestRunner(exec) { error("no files") }, exec) { _, _ -> null }
            val t = RequestTemplate(null, "GET", "http://h", listOf(Header("Authorization", value)))
            val f = s.runAll(listOf(t), mapOf("u" to "bob", "p" to secretPass)).single() as RunOutcome.Failure
            assertFalse(encoded in f.message, f.message)
            assertFalse(java.net.URLEncoder.encode(encoded, Charsets.UTF_8) in f.message, f.message)
            assertFalse(secretPass in f.message, f.message)
            assertTrue("rejected Basic " in f.message, f.message)
        }
    }
}
