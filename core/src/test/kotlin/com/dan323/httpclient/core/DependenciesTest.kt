package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

class DependenciesTest {
    @Test fun addsTransitiveDependenciesInFileOrder() {
        val login = RequestTemplate("login", "POST", "http://x/login")
        val me = RequestTemplate("me", "GET", "http://x/{{login.response.body.$.id}}")
        val other = RequestTemplate("other", "GET", "http://x/o")
        val last = RequestTemplate("last", "GET", "http://x", listOf(Header("A", "{{me.response.status}}")))
        val all = listOf(login, other, me, last)
        assertEquals(listOf(login, me, last), withDependencies(all, listOf(last)))
        assertEquals(listOf(other), withDependencies(all, listOf(other)))
    }
}
