package com.dan323.httpclient.adapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemEnvironmentVariablesTest {
    private val source = { mapOf("A" to "1", "SECRET" to "s") }

    @Test fun readsOnlyAllowedNames() {
        val env = SystemEnvironmentVariables(setOf("A"), source = source)
        assertTrue(env.isAllowed("A"))
        assertEquals("1", env.get("A"))
        assertFalse(env.isAllowed("SECRET"))
        assertNull(env.get("SECRET"))
    }

    @Test fun allowedButUnsetIsNull() {
        assertNull(SystemEnvironmentVariables(setOf("B"), source = source).get("B"))
    }

    @Test fun allowsNothingByDefault() {
        val env = SystemEnvironmentVariables(source = source)
        assertFalse(env.isAllowed("A"))
        assertNull(env.get("A"))
    }

    @Test fun parsesNameLists() {
        assertEquals(setOf("A", "B", "C"), SystemEnvironmentVariables.parseNames(" A, B ,,C "))
        assertEquals(emptySet(), SystemEnvironmentVariables.parseNames(null))
    }

    @Test fun configurationPrefersPropertyOverEnvVar() {
        val both = SystemEnvironmentVariables.fromConfiguration({ "P" }, { "E" })
        assertTrue(both.isAllowed("P")); assertFalse(both.isAllowed("E"))
        val envOnly = SystemEnvironmentVariables.fromConfiguration({ null }, { "E,F" })
        assertTrue(envOnly.isAllowed("E")); assertTrue(envOnly.isAllowed("F"))
        assertFalse(SystemEnvironmentVariables.fromConfiguration({ null }, { null }).isAllowed("E"))
    }

    @Test fun defaultsToProcessEnvironment() {
        // PATH (or Path on Windows) exists in practically every environment; skip silently otherwise.
        val name = listOf("PATH", "Path").firstOrNull { System.getenv(it) != null } ?: return
        assertEquals(System.getenv(name), SystemEnvironmentVariables(setOf(name)).get(name))
        assertNull(SystemEnvironmentVariables().get(name))
        assertNull(SystemEnvironmentVariables(setOf("HTTPC_SURELY_UNSET_X")).get("HTTPC_SURELY_UNSET_X"))
    }
}
