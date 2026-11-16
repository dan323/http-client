package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.KnownVariable
import com.dan323.httpclient.core.application.VariableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariableCompletionTest {
    private fun ctx(text: String, caret: Int = text.length) = completionContext(text, caret)

    @Test fun insideAnOpenReferenceTheTypedNameIsTheContext() {
        assertEquals(CompletionContext(6, "ba"), ctx("GET {{ba"))
        assertEquals(CompletionContext(6, ""), ctx("GET {{"))
        assertEquals(CompletionContext(8, "ba"), ctx("GET {{  ba"))
        assertEquals(CompletionContext(6, "ba"), ctx("GET {{base}}/x", caret = 8))
    }

    @Test fun outsideOrAfterAClosedReferenceThereIsNoContext() {
        assertNull(ctx("GET http://x"))
        assertNull(ctx("GET {{base}}/x"))
        assertNull(ctx("GET {{base}}"))
        assertNull(ctx("GET {{a}} {b"))
        assertNull(ctx("GET {"))
        assertNull(ctx("GET {{ba", caret = 5))         // before the braces
        assertNull(ctx("GET {{a\nlater", caret = 13))   // the reference does not continue on the next line
        assertNull(ctx("x", caret = 5))
        assertNull(ctx("x", caret = -1))
    }

    @Test fun aSecondReferenceOnTheLineIsFoundAfterAClosedOne() {
        assertEquals(CompletionContext(15, "t"), ctx("GET {{base}}/{{t"))
    }

    @Test fun aSpaceEndsTheNameExceptInDynamicVariablesWithArguments() {
        assertNull(ctx("GET {{ba se"))
        assertEquals(CompletionContext(6, "\$timestamp 1"), ctx("GET {{\$timestamp 1"))
    }

    private val known = listOf(
        KnownVariable("base", VariableKind.FILE), KnownVariable("Basket", VariableKind.ENVIRONMENT),
        KnownVariable("login.response.", VariableKind.REQUEST_RESPONSE), KnownVariable("\$uuid", VariableKind.DYNAMIC),
        KnownVariable("urlbase", VariableKind.ENVIRONMENT),
    )

    @Test fun prefixMatchesComeFirstIgnoringCaseThenThoseThatContainIt() {
        assertEquals(listOf("base", "Basket", "urlbase"), completions(known, "bas").map { it.name })
        assertEquals(listOf("\$uuid"), completions(known, "\$u").map { it.name })
    }

    @Test fun anEmptyPrefixOffersEverythingUpToTheLimitAndAnExactNameNothing() {
        assertEquals(known.map { it.name }, completions(known, "").map { it.name })
        val many = (1..30).map { KnownVariable("v$it", VariableKind.ENVIRONMENT) }
        assertEquals(MAX_SUGGESTIONS, completions(many, "").size)
        assertEquals(emptyList(), completions(known, "zzz"))
        assertEquals(listOf("urlbase"), completions(known, "base").map { it.name })   // "base" itself is complete
        assertEquals(emptyList(), completions(listOf(KnownVariable("base", VariableKind.FILE)), "base"))
    }

    private fun apply(text: String, caret: Int, name: String, kind: VariableKind): Completed {
        val c = completionContext(text, caret)!!
        return applyCompletion(text, c, caret, CompletionItem(name, name, kind))
    }

    @Test fun aVariableClosesTheReferenceAndPutsTheCaretAfterIt() {
        assertEquals(Completed("GET {{base}}", 12), apply("GET {{ba", 8, "base", VariableKind.FILE))
        assertEquals(Completed("GET {{base}}", 12), apply("GET {{", 6, "base", VariableKind.ENVIRONMENT))
    }

    @Test fun anAlreadyClosedReferenceIsNotClosedTwice() {
        assertEquals(Completed("GET {{base}}/x", 10), apply("GET {{ba}}/x", 8, "base", VariableKind.FILE))
        assertEquals(Completed("GET {{base }}/x", 10), apply("GET {{ba }}/x", 8, "base", VariableKind.FILE))
    }

    @Test fun aRequestPrefixStaysOpenForWhatFollows() {
        assertEquals(Completed("GET {{login.response.", 21), apply("GET {{lo", 8, "login.response.", VariableKind.REQUEST_RESPONSE))
    }

    @Test fun aDynamicVariableClosesAndTextAfterTheCaretIsKept() {
        assertEquals(Completed("GET {{\$uuid}} tail", 13), apply("GET {{\$u tail", 8, "\$uuid", VariableKind.DYNAMIC))
    }

    @Test fun everyKindHasItsOwnLabel() {
        assertEquals(listOf("file variable", "environment", "response", "dynamic"), listOf(VariableKind.FILE, VariableKind.ENVIRONMENT, VariableKind.REQUEST_RESPONSE, VariableKind.DYNAMIC).map(::kindLabel))
    }
}
