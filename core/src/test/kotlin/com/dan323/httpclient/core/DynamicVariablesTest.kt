package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.InvalidDynamicVariableException
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.UndefinedVariableException
import com.dan323.httpclient.core.application.VariableResolver
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.EnvironmentVariables
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DynamicVariablesTest {
    private val clock = Clock.fixed(Instant.parse("2024-01-31T10:00:00Z"), ZoneOffset.UTC)
    private val env = mapOf("HOME_X" to "/home/x")
    private fun dyn(seed: Int = 1) = DynamicVariables(Random(seed), clock, EnvironmentVariables { env[it] })
    private fun r(text: String, d: DynamicVariables = dyn()) = VariableResolver.resolve(text, emptyMap(), dynamic = d)

    @Test fun seededRandomIsDeterministic() {
        val text = "{{\$random.integer(1, 1000000)}} {{\$random.alphanumeric(12)}} {{\$random.uuid}} {{\$random.email}} {{\$random.float(0, 5)}}"
        assertEquals(r(text, dyn(7)), r(text, dyn(7)))
        assertNotEquals(r(text, dyn(7)), r(text, dyn(8)))
    }

    @Test fun uuidIsVersion4AndFresh() {
        val (a, b) = r("{{\$uuid}} {{\$random.uuid}}").split(" ")
        assertNotEquals(a, b)
        for (u in listOf(a, b)) assertEquals(4, java.util.UUID.fromString(u).version())
    }

    @Test fun timestampOffsets() {
        val base = clock.instant().epochSecond
        assertEquals("$base", r("{{\$timestamp}}"))
        assertEquals("${base + 86400}", r("{{\$timestamp 1 d}}"))
        assertEquals("${base - 7200}", r("{{\$timestamp -2 h}}"))
        assertEquals("${base + 90}", r("{{\$timestamp 90s}}"))
        assertEquals("${base + 5}", r("{{\$timestamp +5 s}}"))
        assertEquals("${base + 60}", r("{{\$timestamp 1 m}}"))
        assertEquals("${base + 7 * 86400}", r("{{\$timestamp 1 w}}"))
        assertEquals("2024-01-31T10:00:00.250Z", r("{{\$isoTimestamp 250 ms}}"))
    }

    @Test fun calendarOffsets() {
        assertEquals("2024-01-31T10:00:00Z", r("{{\$isoTimestamp}}"))
        assertEquals("2024-02-29T10:00:00Z", r("{{\$isoTimestamp 1 M}}"))
        assertEquals("2023-12-31T10:00:00Z", r("{{\$isoTimestamp -1 M}}"))
        assertEquals("2025-01-31T10:00:00Z", r("{{\$isoTimestamp 1 y}}"))
        assertEquals("2024-02-01T10:00:00Z", r("{{ \$isoTimestamp  1 d }}"))
    }

    @Test fun randomIntRange() {
        val d0 = dyn()
        repeat(200) { assertTrue(r("{{\$randomInt}}", d0).toInt() in 0..999) }
        val d = dyn()
        val seen = (1..300).map { r("{{\$randomInt 3 5}}", d).toInt() }.toSet()
        assertEquals(setOf(3, 4, 5), seen)
        assertEquals("7", r("{{\$randomInt 7 7}}"))
        assertEquals("-2", r("{{\$randomInt -2 -2}}"))
    }

    @Test fun randomIntegerIsInclusive() {
        val d = dyn()
        val seen = (1..300).map { r("{{\$random.integer(1, 3)}}", d).toInt() }.toSet()
        assertEquals(setOf(1, 2, 3), seen)
        assertEquals("5", r("{{\$random.integer( 5 ,5 )}}"))
        assertTrue(r("{{\$random.integer(-10, -5)}}").toInt() in -10..-5)
        r("{{\$random.integer(0, ${Long.MAX_VALUE})}}").toLong()
    }

    @Test fun randomFloat() {
        val d = dyn()
        repeat(100) { assertTrue(r("{{\$random.float(1.5, 2.5)}}", d).toDouble().let { it >= 1.5 && it < 2.5 }) }
        assertEquals("2.0", r("{{\$random.float(2, 2)}}"))
    }

    @Test fun randomStrings() {
        assertTrue(Regex("[A-Za-z]{10}").matches(r("{{\$random.alphabetic(10)}}")))
        assertTrue(Regex("[A-Za-z0-9]{16}").matches(r("{{\$random.alphanumeric(16)}}")))
        assertTrue(Regex("[0-9a-f]{32}").matches(r("{{\$random.hexadecimal(32)}}")))
        assertEquals("", r("{{\$random.alphabetic(0)}}"))
        assertTrue(Regex("[a-z0-9]{8}@[a-z]{6}\\.com").matches(r("{{\$random.email}}")))
        assertTrue(Regex("[a-z0-9]{8}@[a-z]{6}\\.com").matches(r("{{\$random.email()}}")))
    }

    @Test fun envVariables() {
        assertEquals("/home/x", r("{{\$env.HOME_X}}"))
        val e = assertFailsWith<UndefinedVariableException> { r("{{\$env.NOPE}}") }
        assertEquals("Undefined variable: \$env.NOPE", e.message)
    }

    @Test fun defaultDynamicHasNoEnv() {
        assertFailsWith<UndefinedVariableException> { VariableResolver.resolve("{{\$env.PATH}}", emptyMap()) }
    }

    @Test fun unknownDynamicIsUndefined() {
        val e = assertFailsWith<UndefinedVariableException> { r("{{\$bogus}}") }
        assertEquals("Undefined variable: \$bogus", e.message)
        assertFailsWith<UndefinedVariableException> { r("{{\$random.nothing(1)}}") }
        assertFailsWith<UndefinedVariableException> { r("{{\$}}") }
    }

    private fun invalid(text: String) = assertFailsWith<InvalidDynamicVariableException> { r(text) }.message!!

    @Test fun malformedArgumentsFailClearly() {
        assertTrue(invalid("{{\$timestamp tomorrow}}").contains("offset"))
        assertTrue(invalid("{{\$timestamp 1 x}}").contains("unknown offset unit 'x'"))
        assertTrue(invalid("{{\$timestamp 1}}").contains("offset"))
        assertTrue(invalid("{{\$timestamp 99999999999999999999 d}}").contains("too large"))
        assertTrue(invalid("{{\$timestamp 9999999999 y}}").contains("out of range"))
        assertTrue(invalid("{{\$randomInt 5}}").contains("\$randomInt <from> <to>"))
        assertTrue(invalid("{{\$randomInt 9 1}}").contains("from must not exceed to"))
        assertTrue(invalid("{{\$randomInt a b}}").contains("not a whole number"))
        assertTrue(invalid("{{\$random.integer}}").contains("parentheses"))
        assertTrue(invalid("{{\$random.integer(1)}}").contains("2 argument"))
        assertTrue(invalid("{{\$random.integer(1,2,3)}}").contains("2 argument"))
        assertTrue(invalid("{{\$random.integer(1,)}}").contains("2 argument"))
        assertTrue(invalid("{{\$random.integer(5, 1)}}").contains("from must not exceed to"))
        assertTrue(invalid("{{\$random.float(a, 1)}}").contains("not a number"))
        assertTrue(invalid("{{\$random.float(0, NaN)}}").contains("not a number"))
        assertTrue(invalid("{{\$random.alphabetic(x)}}").contains("whole number"))
        assertTrue(invalid("{{\$random.alphabetic(-1)}}").contains("between 0 and"))
        assertTrue(invalid("{{\$random.alphabetic(100000)}}").contains("between 0 and"))
        assertTrue(invalid("{{\$random.alphabetic}}").contains("parentheses"))
        assertTrue(invalid("{{\$uuid now}}").contains("no arguments"))
        assertTrue(invalid("{{\$random.email(1)}}").contains("no arguments"))
        assertTrue(invalid("{{\$env.}}").contains("\$env.NAME"))
    }

    @Test fun userVariableStillOverridesDynamic() {
        assertEquals("fixed", VariableResolver.resolve("{{\$random.email}}", mapOf("\$random.email" to "fixed")))
    }

    @Test fun plainNamesWithSpacesAreStillLiteral() {
        assertEquals("{{a b}}", VariableResolver.resolve("{{a b}}", emptyMap()))
    }

    @Test fun fileVariableCanHoldDynamicWithArguments() {
        val vars = VariableResolver.withFileVariables(emptyMap(), mapOf("t" to "{{\$timestamp 1 d}}"))
        assertEquals("${clock.instant().epochSecond + 86400}", VariableResolver.resolve("{{t}}", vars, dynamic = dyn()))
    }

    @Test fun runnerUsesInjectedDynamicVariables() {
        val runner = RequestRunner({ error("unused") }, { error("no files") }, dyn())
        val req = runner.resolve(RequestTemplate("r", "GET", "http://x{{\$env.HOME_X}}/{{\$isoTimestamp 1 d}}"), emptyMap())
        assertEquals("http://x/home/x/2024-02-01T10:00:00Z", req.url)
    }
}
