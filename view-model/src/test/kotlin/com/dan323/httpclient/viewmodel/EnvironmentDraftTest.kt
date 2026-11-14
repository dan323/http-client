package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.domain.EnvironmentEdit.AddEnvironment
import com.dan323.httpclient.core.domain.EnvironmentEdit.RemoveEnvironment
import com.dan323.httpclient.core.domain.EnvironmentEdit.RemoveVariable
import com.dan323.httpclient.core.domain.EnvironmentEdit.RenameEnvironment
import com.dan323.httpclient.core.domain.EnvironmentEdit.SetVariable
import com.dan323.httpclient.core.domain.EnvironmentNames
import com.dan323.httpclient.core.domain.HiddenContent
import com.dan323.httpclient.core.domain.applying
import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentScope.PRIVATE
import com.dan323.httpclient.core.port.out.EnvironmentScope.SHARED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnvironmentDraftTest {
    private fun content(vararg envs: Pair<String, Map<String, String>>, hidden: HiddenContent = HiddenContent.NONE) =
        EnvironmentFileContent(linkedMapOf(*envs), "v", hidden)

    private val none = EnvironmentFileContent(emptyMap(), null)

    private fun draft(shared: EnvironmentFileContent = none, private: EnvironmentFileContent = none) = EnvironmentDraft.of(shared, private)

    private val both = draft(
        content("dev" to mapOf("host" to "a", "token" to "public"), "prod" to mapOf("host" to "b")),
        content("dev" to mapOf("token" to "secret", "key" to "k"), "qa" to mapOf("x" to "1")),
    )

    @Test fun `environments come in file order, shared first, and a variable in both files shows once as private with the private value`() {
        assertEquals(listOf("dev", "prod", "qa"), both.environments.map { it.name })
        assertEquals(
            listOf(EditorVariable("host", "a", false, "host"), EditorVariable("token", "secret", true, "token"), EditorVariable("key", "k", true, "key")),
            both.environments[0].variables,
        )
        assertTrue(both.hasSharedCopy(0, "token"))
        assertFalse(both.hasSharedCopy(0, "key"))
        assertFalse(both.hasSharedCopy(0, "host"))
        assertFalse(both.hasSharedCopy(9, "token"))
        assertFalse(both.environments[0].locked)
        assertFalse(both.isDirty)
        assertEquals("v", both.version(SHARED))
        assertNull(draft().version(PRIVATE))
    }

    @Test fun `an unchanged draft has no edits`() {
        assertEquals(emptyList(), both.edits(SHARED))
        assertEquals(emptyList(), both.edits(PRIVATE))
        assertEquals(emptyList(), both.check())
    }

    @Test fun `private values are masked until revealed, an empty one is not`() {
        val v = EditorVariable("k", "secret")
        assertEquals(MASKED_VALUE, v.shown(false))
        assertEquals("secret", v.shown(true))
        assertEquals("", EditorVariable("k", "").shown(false))
        assertEquals("plain", EditorVariable("k", "plain", private = false).shown(false))
    }

    @Test fun `changing a value edits only the file of the variable`() {
        val d = both.updateVariable(0, 0) { it.copy(value = "z") }
        assertEquals(listOf(SetVariable("dev", "host", "z")), d.edits(SHARED))
        assertEquals(emptyList(), d.edits(PRIVATE))
        assertTrue(d.isDirty)
        val p = both.updateVariable(0, 2) { it.copy(value = "k2") }
        assertEquals(emptyList(), p.edits(SHARED))
        assertEquals(listOf(SetVariable("dev", "key", "k2")), p.edits(PRIVATE))
    }

    @Test fun `changing the value of a variable that is in both files changes the private one and keeps the shared copy`() {
        val d = both.updateVariable(0, 1) { it.copy(value = "other") }
        assertEquals(emptyList(), d.edits(SHARED))
        assertEquals(listOf(SetVariable("dev", "token", "other")), d.edits(PRIVATE))
    }

    @Test fun `making a variable shared moves it and its private value goes to the shared file`() {
        val d = both.updateVariable(0, 2) { it.copy(private = false) }
        assertEquals(listOf(SetVariable("dev", "key", "k")), d.edits(SHARED))
        assertEquals(listOf(RemoveVariable("dev", "key")), d.edits(PRIVATE))
        val both2 = both.updateVariable(0, 1) { it.copy(private = false) }
        assertEquals(listOf(SetVariable("dev", "token", "secret")), both2.edits(SHARED))
        assertEquals(listOf(RemoveVariable("dev", "token")), both2.edits(PRIVATE))
    }

    @Test fun `making a variable private moves it`() {
        val d = both.updateVariable(0, 0) { it.copy(private = true) }
        assertEquals(listOf(RemoveVariable("dev", "host")), d.edits(SHARED))
        assertEquals(listOf(SetVariable("dev", "host", "a")), d.edits(PRIVATE))
    }

    @Test fun `renaming a variable removes the old name and sets the new one`() {
        val d = both.updateVariable(0, 0) { it.copy(name = "url") }
        assertEquals(listOf(RemoveVariable("dev", "host"), SetVariable("dev", "url", "a")), d.edits(SHARED))
        assertEquals(emptyList(), d.edits(PRIVATE))
    }

    @Test fun `adding a variable gives a private row, removing one removes it from its file, an index out of range changes nothing`() {
        val added = both.addVariable(1)
        assertEquals(EditorVariable("", "", private = true), added.environments[1].variables.last())
        assertSame(both, both.addVariable(9))
        assertSame(both, both.updateVariable(9, 0) { it })
        assertSame(both, both.updateVariable(0, 9) { it })
        assertSame(both, both.removeEnvironment(9))
        assertSame(both, both.renameEnvironment(9, "x"))
        val removed = both.removeVariable(0, 0)
        assertEquals(listOf(RemoveVariable("dev", "host")), removed.edits(SHARED))
        val privateRemoved = both.removeVariable(0, 1)
        assertEquals(listOf(RemoveVariable("dev", "token")), privateRemoved.edits(SHARED))
        assertEquals(listOf(RemoveVariable("dev", "token")), privateRemoved.edits(PRIVATE))
    }

    @Test fun `a new environment is always added to the shared file, and to the private file only for its private variables`() {
        assertEquals(listOf(AddEnvironment("new")), both.addEnvironment("new").edits(SHARED))
        assertEquals(emptyList(), both.addEnvironment("new").edits(PRIVATE))
        val withSecret = both.addEnvironment("new").addVariable(3).updateVariable(3, 0) { it.copy(name = "t", value = "v") }
        assertEquals(listOf(AddEnvironment("new")), withSecret.edits(SHARED))
        assertEquals(listOf(AddEnvironment("new"), SetVariable("new", "t", "v")), withSecret.edits(PRIVATE))
        val shared = withSecret.updateVariable(3, 0) { it.copy(private = false) }
        assertEquals(listOf(AddEnvironment("new"), SetVariable("new", "t", "v")), shared.edits(SHARED))
        assertEquals(emptyList(), shared.edits(PRIVATE))
    }

    @Test fun `a variable added to an environment that only the other file has creates it in this file`() {
        val d = both.updateVariable(2, 0) { it.copy(private = false) }
        assertEquals(listOf(AddEnvironment("qa"), SetVariable("qa", "x", "1")), d.edits(SHARED))
        assertEquals(listOf(RemoveVariable("qa", "x")), d.edits(PRIVATE))
    }

    @Test fun `renaming an environment renames it in each file that has it and keeps its variables`() {
        val d = both.renameEnvironment(0, "local")
        assertEquals(listOf(RenameEnvironment("dev", "local")), d.edits(SHARED))
        assertEquals(listOf(RenameEnvironment("dev", "local")), d.edits(PRIVATE))
        val onlyShared = both.renameEnvironment(1, "live")
        assertEquals(listOf(RenameEnvironment("prod", "live")), onlyShared.edits(SHARED))
        assertEquals(emptyList(), onlyShared.edits(PRIVATE))
        val renamedAndChanged = d.updateVariable(0, 0) { it.copy(value = "z") }
        assertEquals(listOf(RenameEnvironment("dev", "local"), SetVariable("local", "host", "z")), renamedAndChanged.edits(SHARED))
    }

    @Test fun `removing an environment removes it from each file that has it`() {
        val d = both.removeEnvironment(0)
        assertEquals(listOf(RemoveEnvironment("dev")), d.edits(SHARED))
        assertEquals(listOf(RemoveEnvironment("dev")), d.edits(PRIVATE))
        assertEquals(listOf(RemoveEnvironment("prod")), both.removeEnvironment(1).edits(SHARED))
    }

    @Test fun `names are checked`() {
        val d = both.addEnvironment("").addEnvironment("dev").addVariable(0).addVariable(0)
            .updateVariable(0, 3) { it.copy(name = "bad name") }
            .updateVariable(0, 4) { it.copy(name = "host") }
        val problems = d.check()
        assertTrue(problems.any { it.environment == 3 && it.variable == null && "empty" in it.message })
        assertTrue(problems.any { it.environment == 4 && "already an environment named dev" in it.message })
        assertTrue(problems.any { it.environment == 0 && it.variable == 3 && "spaces or braces" in it.message })
        assertTrue(problems.any { it.environment == 0 && it.variable == 4 && "already a variable named host in dev" in it.message })
        assertEquals(4, problems.size)
    }

    @Test fun `an empty variable name is reported once, not as a duplicate`() {
        val d = both.addVariable(1).addVariable(1)
        assertEquals(listOf<Int?>(1, 2), d.check().map { it.variable })
        assertEquals(2, d.check().size)
    }

    @Test fun `the files' refusals are reported before saving`() {
        val hidden = draft(content("dev" to mapOf("a" to "1"), hidden = HiddenContent(hidden = mapOf("dev" to setOf("nested")))))
        val d = hidden.addVariable(0).updateVariable(0, 1) { it.copy(name = "nested", private = false) }
        val problems = d.check()
        assertEquals(1, problems.size)
        assertEquals(null, problems[0].environment)
        assertTrue("nested" in problems[0].message && "object or an array" in problems[0].message)
    }

    @Test fun `an environment that is not an object is locked, takes no new variable, and can still be removed`() {
        val d = draft(content("dev" to emptyMap(), hidden = HiddenContent(notObjects = setOf("dev"))))
        assertTrue(d.environments[0].locked)
        assertSame(d, d.addVariable(0))
        assertEquals(emptyList(), d.edits(SHARED))
        assertEquals(listOf(RemoveEnvironment("dev")), d.removeEnvironment(0).edits(SHARED))
        assertEquals(emptyList(), d.removeEnvironment(0).check())
    }

    @Test fun `the shared entry is an environment like any other, and cannot be renamed`() {
        val d = draft(content(EnvironmentNames.SHARED to mapOf("a" to "1")))
        assertEquals(EnvironmentNames.SHARED, d.environments[0].name)
        assertTrue(d.renameEnvironment(0, "x").check().isNotEmpty())
    }

    @Test fun `a swap of two environment names goes through a temporary name and applies`() {
        val d = both.renameEnvironment(0, "prod").renameEnvironment(1, "dev")
        assertEquals(emptyList(), d.check())
        val applied = d.baseline.shared.environments.applying(d.edits(SHARED))
        assertEquals(mapOf("host" to "a", "token" to "public"), applied["prod"])
        assertEquals(mapOf("host" to "b"), applied["dev"])
        assertEquals(listOf("prod", "dev"), applied.keys.toList().sorted().reversed())
    }

    @Test fun `a chain of renames is ordered so each target is free`() {
        val d = draft(content("a" to mapOf("x" to "1"), "b" to mapOf("x" to "2")))
            .renameEnvironment(0, "b").renameEnvironment(1, "c")
        assertEquals(listOf(RenameEnvironment("b", "c"), RenameEnvironment("a", "b")), d.edits(SHARED))
        assertEquals(emptyList(), d.check())
    }

    @Test fun `a rename onto a removed environment is applied after the removal`() {
        val d = draft(content("a" to emptyMap(), "b" to emptyMap())).removeEnvironment(1).renameEnvironment(0, "b")
        assertEquals(listOf(RemoveEnvironment("b"), RenameEnvironment("a", "b")), d.edits(SHARED))
        assertEquals(emptyList(), d.check())
    }

    @Test fun `names already in a file are not checked, only the ones an edit writes`() {
        val odd = draft(content(" dev" to mapOf("my var" to "1"), "ok" to mapOf("other" to "2")))
        assertEquals(emptyList(), odd.check())
        assertEquals(emptyList(), odd.updateVariable(1, 0) { it.copy(value = "3") }.check())
        val problems = odd.updateVariable(0, 0) { it.copy(value = "9") }.check()
        assertEquals(1, problems.size)
        assertNull(problems[0].environment)
        assertTrue(odd.renameEnvironment(1, " x").check().any { it.environment == 1 })
    }

    @Test fun `a locked environment takes no change to its variables`() {
        val d = draft(content("dev" to emptyMap(), hidden = HiddenContent(notObjects = setOf("dev"))))
        val withRow = EnvironmentDraft(listOf(d.environments[0].copy(variables = listOf(EditorVariable("a", "1")))), d.baseline)
        assertSame(withRow, withRow.updateVariable(0, 0) { it.copy(value = "2") })
        assertSame(withRow, withRow.removeVariable(0, 0))
    }

    @Test fun `the file that gains a variable is written first`() {
        assertEquals(listOf(PRIVATE, SHARED), both.updateVariable(0, 0) { it.copy(private = true) }.writeOrder())
        assertEquals(listOf(SHARED, PRIVATE), both.updateVariable(0, 2) { it.copy(private = false) }.writeOrder())
        assertEquals(listOf(SHARED), both.updateVariable(0, 0) { it.copy(value = "z") }.writeOrder())
        assertEquals(emptyList(), both.writeOrder())
    }

    @Test fun `a rename onto a name that stays taken terminates, and check reports the repeat`() {
        val d = draft(content("a" to emptyMap(), "b" to emptyMap())).renameEnvironment(0, "b")
        assertEquals(listOf(RenameEnvironment("a", "b")), d.edits(SHARED))
        assertTrue(d.isDirty)
        assertEquals(listOf(SHARED), d.writeOrder())
        assertTrue(d.check().any { "already an environment named b" in it.message })
        val two = draft(content("a" to emptyMap(), "b" to emptyMap(), "c" to emptyMap())).renameEnvironment(0, "x").renameEnvironment(1, "x")
        assertTrue(two.isDirty)
        assertTrue(two.check().isNotEmpty())
        val mixed = draft(content("a" to emptyMap(), "b" to emptyMap(), "c" to emptyMap()))
            .renameEnvironment(0, "b").renameEnvironment(1, "a").renameEnvironment(2, "a")
        assertTrue(mixed.isDirty)
        assertTrue(mixed.check().isNotEmpty())
    }

    @Test fun `moves in both directions in one save are refused`() {
        val d = draft(content("dev" to mapOf("host" to "a")), content("dev" to mapOf("key" to "k")))
            .updateVariable(0, 0) { it.copy(private = true) }.updateVariable(0, 1) { it.copy(private = false) }
        val problems = d.check()
        assertEquals(1, problems.size)
        assertTrue("one save" in problems[0].message)
        assertEquals(emptyList(), d.updateVariable(0, 1) { it.copy(private = true) }.check())
    }

    @Test fun `a renamed row still counts as moving, in the write order and in the opposing check`() {
        val files = draft(content("dev" to mapOf("host" to "a")), content("dev" to mapOf("key" to "k")))
        val renamedToPrivate = files.updateVariable(0, 0) { it.copy(name = "url", private = true) }
        assertEquals(listOf(PRIVATE, SHARED), renamedToPrivate.writeOrder())
        val both = renamedToPrivate.updateVariable(0, 1) { it.copy(private = false) }
        assertTrue(both.check().any { "one save" in it.message })
        val renamedToShared = files.updateVariable(0, 1) { it.copy(name = "other", private = false) }
        assertEquals(listOf(SHARED, PRIVATE), renamedToShared.writeOrder())
        val sameFile = files.updateVariable(0, 0) { it.copy(name = "url") }
        assertEquals(listOf(SHARED), sameFile.writeOrder())
    }
}
