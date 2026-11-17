package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorIntelligenceTest {
    private val text = "@host = x\n\n###\n# @name login\nPOST {{host}}/a\n\n###\nGET {{host}}/b?t={{login.response.body.\$.t}}&k={{key}}\n"
    private val envs = mapOf("dev" to setOf("key"), "prod" to emptySet())

    private fun undefined(choice: EnvironmentChoice) =
        analyzeFor(text, choice, envs).diagnostics.map { it.message }

    @Test fun `no environment makes environment variables undefined`() {
        assertTrue(undefined(EnvironmentChoice.None).any { "key" in it })
    }

    @Test fun `the chosen environment's names are known, another's are not`() {
        assertTrue(undefined(EnvironmentChoice.Named("dev")).none { "key" in it })
        assertTrue(undefined(EnvironmentChoice.Named("prod")).any { "key" in it })
    }

    @Test fun `a name that is not an environment leaves them undefined`() {
        assertTrue(undefined(EnvironmentChoice.Named("gone")).any { "key" in it })
    }

    private fun declaration(at: String, nth: Int = 0): Int? {
        val analysis = analyzeFor(text, EnvironmentChoice.None, envs)
        var from = -1
        repeat(nth + 1) { from = text.indexOf(at, from + 1) }
        return declarationOf(analysis, from + 1)?.offset
    }

    @Test fun `a use goes to the file variable definition`() {
        assertEquals(text.indexOf("host = x"), declaration("host}}/a"))
    }

    @Test fun `a response reference goes to the request name`() {
        assertEquals(text.indexOf("login\n"), declaration("login.response"))
    }

    @Test fun `a name defined again goes to the definition in force at the use`() {
        val t = "@host = a\nGET {{host}}/1\n\n###\n@host = b\nGET {{host}}/2\n"
        val analysis = analyzeFor(t, EnvironmentChoice.None, envs)
        fun target(use: String) = declarationOf(analysis, t.indexOf(use) + 3)?.offset
        assertEquals(t.indexOf("host = a"), target("{{host}}/1"))
        assertEquals(t.indexOf("host = b"), target("{{host}}/2"))
    }

    @Test fun `a use above its only definition goes to it`() {
        val t = "GET {{host}}/1\n\n###\n@host = b\nGET http://x\n"
        assertEquals(t.indexOf("host = b"), declarationOf(analyzeFor(t, EnvironmentChoice.None, envs), t.indexOf("host}}") + 1)?.offset)
    }

    @Test fun `a definition, an environment variable and a bare position have no target`() {
        assertNull(declaration("host = x"))
        assertNull(declaration("key}}"))
        assertNull(declarationOf(analyzeFor(text, EnvironmentChoice.None, envs), 0.coerceAtLeast(text.indexOf("###"))))
    }
}

class EditBetweenTest {
    private fun apply(old: String, new: String) = editBetween(old, new).let { old.substring(0, it.start) + it.replacement + old.substring(it.end) }

    @Test fun `the edit turns the old text into the new`() {
        for ((a, b) in listOf("abc" to "abc", "abc" to "", "" to "abc", "{{ba}}" to "{{base}}", "x{{ba}}y" to "x{{basket}}y", "aaa" to "aa", "aa" to "aaa", "ab" to "ba")) {
            assertEquals(b, apply(a, b), "$a -> $b")
        }
    }

    @Test fun `only the differing middle is replaced`() {
        assertEquals(TextEdit(4, 4, "se"), editBetween("{{ba}}", "{{base}}"))
        assertEquals(TextEdit(0, 0, ""), editBetween("same", "same").let { TextEdit(0, 0, it.replacement) })
    }
}
