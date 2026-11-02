package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.UndefinedVariableException
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestSessionTest {
    private val sent = mutableListOf<HttpRequest>()
    private val executor = HttpExecutor { req ->
        sent += req
        HttpResponse(200, listOf(Header("X-Id", "h1")), """{"token":"tok"}""".toByteArray(), 1)
    }
    // Fake extractor: only understands "$.token".
    private val session = RequestSession(
        RequestRunner(executor) { error("no files") },
        executor,
    ) { json, path -> if (path == "$.token" && "tok" in json) "tok" else null }

    private val login = RequestTemplate("login", "POST", "http://h/login")

    @Test fun laterRequestsUseEarlierResponses() {
        session.run(login, emptyMap())
        session.run(RequestTemplate(null, "GET", "http://h/{{login.response.status}}?t={{login.response.body.$.token}}"), emptyMap())
        assertEquals("http://h/200?t=tok", sent.last().url)
    }

    @Test fun headersAndWholeBody() {
        session.run(login, emptyMap())
        session.run(
            RequestTemplate(null, "GET", "http://h", listOf(Header("A", "{{login.response.headers.x-id}}")),
                com.dan323.httpclient.core.domain.Body.Text("{{login.response.body.*}}")),
            emptyMap(),
        )
        assertEquals("h1", sent.last().headers.single().value)
        assertEquals("""{"token":"tok"}""", sent.last().body!!.toString(Charsets.UTF_8))
    }

    @Test fun referencingUnrunOrMissingFails() {
        assertFailsWith<UndefinedVariableException> {
            session.run(RequestTemplate(null, "GET", "http://h/{{login.response.status}}"), emptyMap())
        }
        session.run(login, emptyMap())
        assertFailsWith<UndefinedVariableException> {
            session.run(RequestTemplate(null, "GET", "http://h/{{login.response.body.$.nope}}"), emptyMap())
        }
    }
}

class RunAllTest {
    @Test fun reportsFailuresAndContinues() {
        val executor = HttpExecutor { HttpResponse(204, emptyList(), ByteArray(0), 1) }
        val session = RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }
        val seen = mutableListOf<com.dan323.httpclient.core.application.RunOutcome>()
        val outcomes = session.runAll(
            listOf(RequestTemplate(null, "GET", "http://h/{{nope}}"), RequestTemplate(null, "GET", "http://h")),
            emptyMap(),
        ) { seen += it }
        assertEquals(2, outcomes.size)
        assertEquals(outcomes, seen)
        assertEquals("Undefined variable: nope", (outcomes[0] as com.dan323.httpclient.core.application.RunOutcome.Failure).message)
        assertEquals(204, (outcomes[1] as com.dan323.httpclient.core.application.RunOutcome.Success).response.statusCode)
    }
}
