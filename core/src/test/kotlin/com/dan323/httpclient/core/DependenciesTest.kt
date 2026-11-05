package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

class DependenciesTest {
    private val login = RequestTemplate("login", "POST", "http://x/login")
    private val other = RequestTemplate("other", "GET", "http://x/o")

    private fun deps(chosen: RequestTemplate, vararg all: RequestTemplate) = withDependencies(all.toList(), listOf(chosen))

    @Test fun addsTransitiveDependenciesInFileOrder() {
        val me = RequestTemplate("me", "GET", "http://x/{{login.response.body.$.id}}")
        val last = RequestTemplate("last", "GET", "http://x", listOf(Header("A", "{{me.response.status}}")))
        val all = listOf(login, other, me, last)
        assertEquals(listOf(login, me, last), withDependencies(all, listOf(last)))
        assertEquals(listOf(other), withDependencies(all, listOf(other)))
    }

    @Test fun followsReferencesThroughFileVariables() {
        val r = RequestTemplate(
            "r", "GET", "http://x/", listOf(Header("Authorization", "Bearer {{token}}")),
            fileVariables = mapOf("token" to "{{login.response.body.$.t}}"),
        )
        assertEquals(listOf(login, r), deps(r, login, other, r))
    }

    @Test fun followsFileVariablesThroughOtherFileVariables() {
        val r = RequestTemplate(
            "r", "GET", "{{base}}",
            fileVariables = linkedMapOf("token" to "{{login.response.body.$.t}}", "base" to "http://x/{{token}}"),
        )
        assertEquals(listOf(login, r), deps(r, login, other, r))
    }

    @Test fun ignoresFileVariablesThatAreNotUsed() {
        val r = RequestTemplate("r", "GET", "http://x", fileVariables = mapOf("token" to "{{login.response.body.$.t}}"))
        assertEquals(listOf(r), deps(r, login, r))
    }

    @Test fun cyclicFileVariablesDoNotLoopForever() {
        val r = RequestTemplate("r", "GET", "{{a}}", fileVariables = linkedMapOf("a" to "{{b}}", "b" to "{{a}}{{login.response.status}}"))
        assertEquals(listOf(login, r), deps(r, login, r))
    }

    @Test fun followsAssertions() {
        val r = RequestTemplate("r", "GET", "http://x", assertions = listOf("status == {{login.response.status}}"))
        assertEquals(listOf(login, r), deps(r, login, r))
    }

    @Test fun followsSettings() {
        val r = RequestTemplate("r", "GET", "http://x", settings = mapOf("timeout" to "{{login.response.body.\$.t}}"))
        assertEquals(listOf(login, r), deps(r, login, r))
    }

    @Test fun followsSettingsThroughFileVariables() {
        val r = RequestTemplate(
            "r", "GET", "http://x", settings = mapOf("proxy" to "{{p}}"),
            fileVariables = mapOf("p" to "{{login.response.body.$.proxy}}"),
        )
        assertEquals(listOf(login, r), deps(r, login, r))
    }

    @Test fun followsBodyAndFileBodyPath() {
        val text = RequestTemplate("t", "POST", "http://x", body = Body.Text("{{login.response.body.*}}"))
        val file = RequestTemplate("f", "POST", "http://x", body = Body.FromFile("{{login.response.body.\$.p}}"))
        assertEquals(listOf(login, text), deps(text, login, text))
        assertEquals(listOf(login, file), deps(file, login, file))
    }

    @Test fun findsRequestNamesContainingDots() {
        val dotted = RequestTemplate("auth.login", "POST", "http://x/login")
        val r = RequestTemplate("r", "GET", "http://x/{{auth.login.response.status}}")
        assertEquals(listOf(dotted, r), deps(r, dotted, other, r))
    }

    @Test fun splitsOnTheFirstResponseMarker() {
        val a = RequestTemplate("a", "GET", "http://x/a")
        val r = RequestTemplate("r", "GET", "http://x/{{a.response.body.response.x}}")
        assertEquals(listOf(a, r), deps(r, a, other, r))
    }

    @Test fun toleratesSpacesInsideBraces() {
        val r = RequestTemplate("r", "GET", "http://x/{{ login.response.status }}")
        assertEquals(listOf(login, r), deps(r, login, r))
    }
}
