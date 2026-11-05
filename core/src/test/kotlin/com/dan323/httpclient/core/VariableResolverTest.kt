package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.UndefinedVariableException
import com.dan323.httpclient.core.application.VariableExpansionException
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
        val env = mapOf("host" to "env", "user" to "bob")
        val files = linkedMapOf("host" to "http://{{user}}", "url" to "{{host}}/x")
        assertEquals("http://bob", VariableResolver.resolve("{{host}}", env, fileVariables = files))
        assertEquals("http://bob/x", VariableResolver.resolve("{{url}}", env, fileVariables = files))
    }

    @Test fun fileVariableMayReferenceDynamicVariable() {
        assertEquals(36, VariableResolver.resolve("{{id}}", emptyMap(), fileVariables = mapOf("id" to "{{\$uuid}}")).length)
    }

    @Test fun fileVariableSelfReferenceMeansEnvironmentValue() {
        assertEquals("http://env/api", VariableResolver.resolve("{{host}}", mapOf("host" to "env"), fileVariables = mapOf("host" to "http://{{host}}/api")))
    }

    @Test fun fileVariablesMayUseLaterOnes() {
        val files = linkedMapOf("a" to "{{b}}!", "b" to "later")
        assertEquals("later!", VariableResolver.resolve("{{a}}", emptyMap(), fileVariables = files))
    }

    @Test fun selfReferencingFileVariableWithoutEnvironmentValueIsUndefined() {
        assertFailsWith<UndefinedVariableException> { VariableResolver.resolve("{{a}}", emptyMap(), fileVariables = mapOf("a" to "{{a}}")) }
    }

    @Test fun mutuallyReferencingFileVariablesFailWithTheChain() {
        val files = linkedMapOf("a" to "x{{b}}", "b" to "{{c}}", "c" to "{{a}}")
        val e = assertFailsWith<VariableExpansionException> { VariableResolver.resolve("{{a}}", emptyMap(), fileVariables = files) }
        assertTrue("a -> b -> c -> a" in e.message!!, e.message)
    }

    @Test fun cycleThroughATextWithOtherReferencesStillFails() {
        assertFailsWith<VariableExpansionException> {
            VariableResolver.resolve("{{a}}", mapOf("b" to "1"), fileVariables = linkedMapOf("a" to "{{c}}{{b}}", "c" to "{{a}}"))
        }
    }

    @Test fun exponentialFanOutIsBoundedInsteadOfHanging() {
        val files = linkedMapOf<String, String>()
        for (i in 0 until 40) files["v$i"] = "{{v${i + 1}}}{{v${i + 1}}}"
        files["v40"] = "x"
        val e = assertFailsWith<VariableExpansionException> { VariableResolver.resolve("{{v0}}", emptyMap(), fileVariables = files) }
        assertTrue("too large" in e.message!!, e.message)
    }

    @Test fun manyUsesOfOneFileVariableDoNotExhaustTheBudget() {
        val out = VariableResolver.resolve("{{x}}".repeat(10_001), emptyMap(), fileVariables = mapOf("x" to "a"))
        assertEquals("a".repeat(10_001), out)
        val viaEnv = VariableResolver.resolve("{{x}}".repeat(10_001), mapOf("h" to "b"), fileVariables = mapOf("x" to "{{h}}"))
        assertEquals("b".repeat(10_001), viaEnv)
    }

    @Test fun dynamicValuesInFileVariablesAreStillFreshPerUse() {
        val out = VariableResolver.resolve("{{u}} {{u}}", emptyMap(), fileVariables = mapOf("u" to "{{\$uuid}}"))
        val (a, b) = out.split(" ")
        assertNotEquals(a, b)
    }

    @Test fun expansionBudgetBoundaryIsExactly10000() {
        fun chain(n: Int): LinkedHashMap<String, String> {
            val files = linkedMapOf<String, String>()
            for (i in 0 until n) files["v$i"] = "{{v${i + 1}}}"
            files["v$n"] = "{{h}}"
            return files
        }
        // 10,000 distinct expansions are allowed (depth is limited separately, so use wide files).
        val wide = linkedMapOf<String, String>()
        for (i in 0 until 10_000) wide["w$i"] = "{{h}}"
        val ok = VariableResolver.resolve((0 until 10_000).joinToString("") { "{{w$it}}" }, mapOf("h" to "z"), fileVariables = wide)
        assertEquals("z".repeat(10_000), ok)
        wide["w10000"] = "{{h}}"
        val e = assertFailsWith<VariableExpansionException> {
            VariableResolver.resolve((0..10_000).joinToString("") { "{{w$it}}" }, mapOf("h" to "z"), fileVariables = wide)
        }
        assertTrue("too large" in e.message!!, e.message)
        assertEquals("z", VariableResolver.resolve("{{v0}}", mapOf("h" to "z"), fileVariables = chain(50)))
    }

    @Test fun nestingBeyond100LevelsFails() {
        val files = linkedMapOf<String, String>()
        for (i in 0 until 101) files["v$i"] = "{{v${i + 1}}}"
        files["v101"] = "end"
        val e = assertFailsWith<VariableExpansionException> { VariableResolver.resolve("{{v0}}", emptyMap(), fileVariables = files) }
        assertTrue("100 levels" in e.message!!, e.message)
    }

    @Test fun selfReferenceWithoutEnvironmentValueIsUndefinedNotCircular() {
        val e = assertFailsWith<UndefinedVariableException> {
            VariableResolver.resolve("{{host}}", emptyMap(), fileVariables = mapOf("host" to "{{host}}/api"))
        }
        assertEquals("host", e.variable)
    }

    @Test fun cycleChainContainsOnlyTheCycle() {
        val e = assertFailsWith<VariableExpansionException> {
            VariableResolver.resolve("{{x}}", emptyMap(), fileVariables = linkedMapOf("x" to "{{a}}", "a" to "{{b}}", "b" to "{{a}}"))
        }
        assertTrue("a -> b -> a" in e.message!! && "x" !in e.message!!, e.message)
    }

    @Test fun environmentValuesDoNotReferenceOtherVariables() {
        assertEquals("{{host}}/v1", VariableResolver.resolve("{{api}}", mapOf("api" to "{{host}}/v1", "host" to "h")))
    }

    @Test fun deepButLegitimateChainsStillResolve() {
        val files = linkedMapOf<String, String>()
        for (i in 0 until 50) files["v$i"] = "{{v${i + 1}}}"
        files["v50"] = "end"
        assertEquals("end", VariableResolver.resolve("{{v0}}", emptyMap(), fileVariables = files))
    }

    @Test fun environmentValuesWithBracesAreNotReinterpreted() {
        assertEquals("pa{{ss", VariableResolver.resolve("{{secret}}", mapOf("secret" to "pa{{ss")))
        assertEquals("a{{x}}b", VariableResolver.resolve("{{secret}}", mapOf("secret" to "a{{x}}b")))
    }

    @Test fun environmentValuesBroughtInThroughFileVariablesAreNotReinterpreted() {
        val files = mapOf("auth" to "Bearer {{secret}}")
        assertEquals("Bearer pa{{ss", VariableResolver.resolve("{{auth}}", mapOf("secret" to "pa{{ss"), fileVariables = files))
        assertEquals("Bearer {{x}}", VariableResolver.resolve("{{auth}}", mapOf("secret" to "{{x}}"), fileVariables = files))
    }

    @Test fun extraAndDynamicResultsAreNotReinterpreted() {
        assertEquals("{{x}}", VariableResolver.resolve("{{r.response.body}}", emptyMap(), extra = { "{{x}}" }))
        assertEquals("{{x}}", VariableResolver.resolve("{{v}}", emptyMap(), extra = { "{{x}}" }, fileVariables = mapOf("v" to "{{r.response.body}}")))
    }

    @Test fun spacedBuiltInNamesMatchOverrides() {
        assertEquals("fixed", VariableResolver.resolve("{{\$uuid }}", mapOf("\$uuid" to "fixed")))
        assertEquals("fixed", VariableResolver.resolve("{{ \$random.email  \t}}", mapOf("\$random.email" to "fixed")))
        assertEquals("fixed", VariableResolver.resolve("{{\$randomInt }}", mapOf("\$randomInt" to "fixed")))
    }

    @Test fun dynamicReferenceNeverSpansLines() {
        val text = "{{\$uuid\n(}}"
        assertEquals(text, VariableResolver.resolve(text, emptyMap()))
        val text2 = "{{\$random.integer\n(1, 2)}}"
        assertEquals(text2, VariableResolver.resolve(text2, emptyMap()))
    }

    @Test
    fun unclosedDynamicReferenceWithLongWhitespaceIsLinear() {
        val start = System.nanoTime()
        val spaces = " ".repeat(200_000)
        for (text in listOf("{{\$uuid$spaces", "{{\$timestamp$spaces", "{{\$randomInt 1$spaces", "{{\$x$spaces", "{{\$uuid$spaces\t(" + spaces)) {
            assertEquals(text, VariableResolver.resolve(text, emptyMap()))
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000, "resolution should be linear")
    }
}
