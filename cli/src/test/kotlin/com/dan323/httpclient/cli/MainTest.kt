package com.dan323.httpclient.cli

import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainTest {
    private val a = RequestTemplate("a", "GET", "http://x/a")
    private val b = RequestTemplate(null, "GET", "http://x/b")

    @Test fun parsesOptions() {
        val o = parseArgs(listOf("f.http", "--env", "dev", "--request", "2", "--list"))
        assertEquals(Options(Path.of("f.http"), "dev", "2", true), o)
    }

    @Test fun rejectsBadArgs() {
        assertNull(parseArgs(emptyList()))
        assertNull(parseArgs(listOf("f.http", "--env")))
        assertNull(parseArgs(listOf("f.http", "--nope")))
        assertNull(parseArgs(listOf("a.http", "b.http")))
    }

    @Test fun selectsByNameOrIndex() {
        assertEquals(listOf(a, b), select(listOf(a, b), null))
        assertEquals(listOf(a), select(listOf(a, b), "a"))
        assertEquals(listOf(b), select(listOf(a, b), "2"))
        assertNull(select(listOf(a, b), "3"))
    }
}
