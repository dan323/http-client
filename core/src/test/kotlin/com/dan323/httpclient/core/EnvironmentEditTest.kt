package com.dan323.httpclient.core

import com.dan323.httpclient.core.domain.EditableEnvironments
import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.domain.EnvironmentEditException
import com.dan323.httpclient.core.domain.EnvironmentNames
import com.dan323.httpclient.core.domain.applying
import com.dan323.httpclient.core.domain.problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnvironmentEditTest {
    private val base: EditableEnvironments = linkedMapOf(
        "dev" to linkedMapOf("host" to "localhost", "port" to "8080"),
        "prod" to linkedMapOf("host" to "example.com"),
    )

    @Test fun setsAddsAndReplacesVariablesKeepingTheOrder() {
        val r = base.applying(listOf(
            EnvironmentEdit.SetVariable("dev", "host", "127.0.0.1"),
            EnvironmentEdit.SetVariable("dev", "token", "t"),
        ))
        assertEquals(listOf("host", "port", "token"), r.getValue("dev").keys.toList())
        assertEquals("127.0.0.1", r.getValue("dev").getValue("host"))
        assertEquals("localhost", base.getValue("dev").getValue("host"))   // the original is not touched
    }

    @Test fun removesVariablesAndIgnoresAMissingOne() {
        val r = base.applying(listOf(EnvironmentEdit.RemoveVariable("dev", "port"), EnvironmentEdit.RemoveVariable("dev", "nope")))
        assertEquals(listOf("host"), r.getValue("dev").keys.toList())
    }

    @Test fun addsRenamesAndRemovesEnvironmentsKeepingTheirPlace() {
        val r = base.applying(listOf(
            EnvironmentEdit.AddEnvironment("test"),
            EnvironmentEdit.RenameEnvironment("dev", "local"),
            EnvironmentEdit.RemoveEnvironment("prod"),
        ))
        assertEquals(listOf("local", "test"), r.keys.toList())
        assertEquals("8080", r.getValue("local").getValue("port"))
        assertEquals(emptyMap(), r.getValue("test"))
    }

    @Test fun refusesUnknownAndDuplicateNamesWithoutValuesInTheMessage() {
        fun fail(edit: EnvironmentEdit) = assertFailsWith<EnvironmentEditException> { base.applying(listOf(edit)) }.message!!
        assertEquals("Unknown environment: x", fail(EnvironmentEdit.SetVariable("x", "a", "secret-value")))
        assertEquals("Unknown environment: x", fail(EnvironmentEdit.RemoveVariable("x", "a")))
        assertEquals("Unknown environment: x", fail(EnvironmentEdit.RemoveEnvironment("x")))
        assertEquals("Unknown environment: x", fail(EnvironmentEdit.RenameEnvironment("x", "y")))
        assertEquals("The environment dev already exists.", fail(EnvironmentEdit.AddEnvironment("dev")))
        assertEquals("The environment prod already exists.", fail(EnvironmentEdit.RenameEnvironment("dev", "prod")))
    }

    @Test fun checksNamesOfWhatAnEditWrites() {
        assertNotNull(EnvironmentNames.environmentProblem(""))
        assertNotNull(EnvironmentNames.environmentProblem(" dev"))
        assertNull(EnvironmentNames.environmentProblem("dev 2"))
        assertNull(EnvironmentNames.environmentProblem(EnvironmentNames.SHARED))
        assertNotNull(EnvironmentNames.variableProblem(""))
        assertNotNull(EnvironmentNames.variableProblem("a b"))
        assertNotNull(EnvironmentNames.variableProblem("a{b"))
        assertNull(EnvironmentNames.variableProblem("api.key-1"))
        assertNotNull(EnvironmentEdit.SetVariable("dev", "bad name", "v").problem())
        assertNotNull(EnvironmentEdit.AddEnvironment("").problem())
        assertNull(EnvironmentEdit.RemoveVariable("dev", "bad name").problem())   // an existing odd name can still be removed
        assertFailsWith<EnvironmentEditException> { base.applying(listOf(EnvironmentEdit.SetVariable("dev", "a b", "v"))) }
    }

    @Test fun theSharedPseudoEnvironmentCannotBeRenamedButCanBeEdited() {
        val withShared: EditableEnvironments = linkedMapOf(EnvironmentNames.SHARED to linkedMapOf("a" to "1"), "dev" to emptyMap())
        assertFailsWith<EnvironmentEditException> { withShared.applying(listOf(EnvironmentEdit.RenameEnvironment(EnvironmentNames.SHARED, "x"))) }
        assertFailsWith<EnvironmentEditException> { withShared.applying(listOf(EnvironmentEdit.RenameEnvironment("dev", EnvironmentNames.SHARED))) }
        val r = withShared.applying(listOf(EnvironmentEdit.SetVariable(EnvironmentNames.SHARED, "b", "2"), EnvironmentEdit.AddEnvironment("qa")))
        assertEquals(mapOf("a" to "1", "b" to "2"), r.getValue(EnvironmentNames.SHARED))
        assertEquals(listOf(EnvironmentNames.SHARED, "dev", "qa"), r.keys.toList())
    }

    @Test fun anEmptyListChangesNothing() {
        assertEquals(base, base.applying(emptyList()))
    }
}
