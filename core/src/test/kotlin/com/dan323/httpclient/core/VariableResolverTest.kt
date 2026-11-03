package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.UndefinedVariableException
import com.dan323.httpclient.core.application.VariableResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VariableResolverTest {
    @Test fun substitutesDefinedVariables() {
        assertEquals("http://h/1", VariableResolver.resolve("http://{{ host }}/{{id}}", mapOf("host" to "h", "id" to "1")))
    }

    @Test fun throwsOnUndefined() {
        assertFailsWith<UndefinedVariableException> { VariableResolver.resolve("{{nope}}", emptyMap()) }
    }

    @Test fun uuidIsFreshPerOccurrence() {
        val (a, b) = VariableResolver.resolve("{{\$uuid}} {{\$uuid}}", emptyMap()).split(" ")
        assertEquals(36, a.length)
        assertNotEquals(a, b)
    }

    @Test fun timestampsAndRandomInt() {
        assertTrue(VariableResolver.resolve("{{\$timestamp}}", emptyMap()).toLong() > 0)
        assertTrue(VariableResolver.resolve("{{\$isoTimestamp}}", emptyMap()).endsWith("Z"))
        assertTrue(VariableResolver.resolve("{{\$randomInt}}", emptyMap()).toInt() in 0..999)
    }

    @Test fun userVariableOverridesDynamic() {
        assertEquals("fixed", VariableResolver.resolve("{{\$uuid}}", mapOf("\$uuid" to "fixed")))
    }

    @Test fun valuesWithRegexSpecialCharsAreLiteral() {
        assertEquals("a\$1\\b", VariableResolver.resolve("{{v}}", mapOf("v" to "a\$1\\b")))
    }

    @Test fun fileVariablesOverrideEnvironmentAndSeeEarlierOnes() {
        val layered = VariableResolver.withFileVariables(
            mapOf("host" to "env", "user" to "bob"),
            linkedMapOf("host" to "http://{{user}}", "url" to "{{host}}/x"),
        )
        assertEquals("http://bob", layered["host"])
        assertEquals("http://bob/x", layered["url"])
    }

    @Test fun fileVariableMayReferenceDynamicVariable() {
        val vars = VariableResolver.withFileVariables(emptyMap(), mapOf("id" to "{{\$uuid}}"))
        assertEquals(36, VariableResolver.resolve("{{id}}", vars).length)
    }

    @Test fun cyclicVariablesTerminate() {
        assertFailsWith<UndefinedVariableException> { VariableResolver.resolve("{{a}}", mapOf("a" to "{{a}}{{b}}")) }
    }
}
