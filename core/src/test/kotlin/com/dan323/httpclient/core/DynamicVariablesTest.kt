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
    private val allowing = object : EnvironmentVariables {
        override fun get(name: String) = env[name]
        override fun isAllowed(name: String) = name in setOf("HOME_X", "NOPE", "TRICKY")
        override val allowHint = "pass --allow-env NAME"
    }
    private fun dyn(seed: Int = 1) = DynamicVariables(Random(seed), clock, allowing)
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

    @Test fun longBoundaries() {
        val max = Long.MAX_VALUE
        val min = Long.MIN_VALUE
        fun between(a: Long, b: Long, seed: Int = 1) = r("{{\$random.integer($a, $b)}}", dyn(seed)).toLong()
        assertEquals(max, between(max, max))
        assertEquals(min, between(min, min))
        val d = dyn()
        val top = (1..400).map { r("{{\$random.integer(${max - 2}, $max)}}", d).toLong() }.toSet()
        assertEquals(setOf(max - 2, max - 1, max), top)
        for (seed in 1..50) {
            assertTrue(between(-5, max, seed) >= -5)
            between(min, max, seed)
            assertTrue(between(min, min + 1, seed) in min..min + 1)
            assertTrue(between(min + 1, max, seed) >= min + 1)
        }
        assertEquals(max, r("{{\$randomInt $max $max}}").toLong())
        assertTrue(r("{{\$randomInt -5 $max}}").toLong() >= -5)
        assertTrue(invalid("{{\$random.integer($max, $min)}}").contains("from must not exceed to"))
        assertTrue(invalid("{{\$random.integer(1, 9223372036854775808)}}").contains("not a whole number"))
    }

    @Test fun randomFloat() {
        val d = dyn()
        repeat(100) { assertTrue(r("{{\$random.float(1.5, 2.5)}}", d).toDouble().let { it >= 1.5 && it < 2.5 }) }
        assertTrue(invalid("{{\$random.float(2, 2)}}").contains("from must be less than to"))
        assertTrue(invalid("{{\$random.float(3, 2)}}").contains("from must be less than to"))
    }

    @Test fun floatIsNeverScientificNotation() {
        // The only double in [1e10, nextUp(1e10)) is 1e10 itself.
        assertEquals("10000000000.0", r("{{\$random.float(1e10, ${Math.nextUp(1e10)})}}"))
        assertEquals("0.0005", r("{{\$random.float(5e-4, ${Math.nextUp(5e-4)})}}"))
        val d = dyn()
        repeat(200) {
            val v = r("{{\$random.float(0, 1e-5)}}", d)
            assertTrue(Regex("-?\\d+\\.\\d+").matches(v), v)
            assertTrue(v.toDouble() < 1e-5)
        }
        assertTrue(Regex("\\d+\\.\\d+").matches(r("{{\$random.float(1e300, 1e301)}}")))
    }

    @Test fun floatNeverReturnsUpperBound() {
        val to = Math.nextUp(1.0)
        val d = dyn()
        repeat(500) { assertEquals(1.0, r("{{\$random.float(1.0, $to)}}", d).toDouble()) }
    }

    @Test fun defaultRandomnessWorksAndVaries() {
        val d = DynamicVariables(clock = clock)
        assertNotEquals(r("{{\$uuid}}", d), r("{{\$uuid}}", d))
        assertNotEquals(r("{{\$random.alphanumeric(32)}}", d), r("{{\$random.alphanumeric(32)}}", d))
        assertEquals(4, java.util.UUID.fromString(r("{{\$uuid}}", d)).version())
    }

    @Test fun offsetEdgeCases() {
        val leap = DynamicVariables(Random(1), Clock.fixed(Instant.parse("2024-03-01T00:00:00Z"), ZoneOffset.UTC), allowing)
        assertEquals("2024-02-29T00:00:00Z", r("{{\$isoTimestamp -1 d}}", leap))
        assertEquals("2023-03-01T00:00:00Z", r("{{\$isoTimestamp -1 y}}", leap))
        val newYear = DynamicVariables(Random(1), Clock.fixed(Instant.parse("2024-12-31T23:59:59Z"), ZoneOffset.UTC), allowing)
        assertEquals("2025-01-01T00:00:00Z", r("{{\$isoTimestamp 1 s}}", newYear))
        assertEquals("2024-12-30T23:59:59Z", r("{{\$isoTimestamp -1 d}}", newYear))
        // Long.MAX ms is about 292 million years: still inside java.time's range, so it is a valid (huge) value.
        assertTrue(r("{{\$timestamp ${Long.MAX_VALUE} ms}}").toLong() > 0)
        assertTrue(invalid("{{\$timestamp ${Long.MAX_VALUE} d}}").contains("out of range"))
        assertTrue(invalid("{{\$timestamp ${Long.MIN_VALUE} h}}").contains("out of range"))
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

    @Test fun envNotOnAllowlistIsRejectedWithHint() {
        val e = assertFailsWith<InvalidDynamicVariableException> { r("{{\$env.SECRET}}") }
        assertEquals("Invalid dynamic variable {{\$env.SECRET}}: environment variable 'SECRET' is not allowed; pass --allow-env NAME", e.message)
    }

    @Test fun processEnvAlias() {
        assertEquals("/home/x", r("{{\$processEnv HOME_X}}"))
        assertFailsWith<InvalidDynamicVariableException> { r("{{\$processEnv SECRET}}") }
        assertTrue(invalid("{{\$processEnv}}").contains("\$processEnv NAME"))
    }

    @Test fun envValuesAreNotReExpanded() {
        val tricky = object : EnvironmentVariables {
            override fun get(name: String) = "{{\$env.HOME_X}} {{x}}"
            override fun isAllowed(name: String) = name == "TRICKY"
        }
        val d = DynamicVariables(Random(1), clock, tricky)
        assertEquals("{{\$env.HOME_X}} {{x}}", r("{{\$env.TRICKY}}", d))
    }

    @Test fun defaultDynamicHasNoEnv() {
        val e = assertFailsWith<InvalidDynamicVariableException> { VariableResolver.resolve("{{\$env.PATH}}", emptyMap()) }
        assertTrue(e.message!!.contains("not allowed"))
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

    @Test fun onlyDollarFollowedByLetterIsDynamic() {
        // Handlebars/Go-template style text is passed through untouched, as before.
        for (text in listOf("{{\$ foo}}", "{{\$x := 1}}", "{{\$ x }}")) assertEquals(text, r(text))
        // `$` alone or followed by a digit keeps its old behaviour: an undefined variable.
        assertFailsWith<UndefinedVariableException> { r("{{\$}}") }
        assertFailsWith<UndefinedVariableException> { r("{{\$1}}") }
    }

    @Test fun jsonAdjacentBracesAndMultiLineBehaveAsBefore() {
        assertEquals("{\"a\":7}", r("{\"a\":{{\$randomInt 7 7}}}"))
        assertEquals("v}", VariableResolver.resolve("{{x}}}", mapOf("x" to "v")))
        // A dynamic reference never swallows following lines; text between two references stays literal.
        assertEquals("{{\$randomInt 1\n2 3}}", r("{{\$randomInt 1\n2 3}}"))
        assertEquals("a7\n{{\$foo\n x}}", r("a{{\$randomInt 7 7}}\n{{\$foo\n x}}"))
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
