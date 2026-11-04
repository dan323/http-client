package com.dan323.httpclient.adapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemEnvironmentVariablesTest {
    @Test fun readsFromSource() {
        val env = SystemEnvironmentVariables { mapOf("A" to "1") }
        assertEquals("1", env.get("A"))
        assertNull(env.get("B"))
    }

    @Test fun defaultsToProcessEnvironment() {
        val name = System.getenv().keys.first()
        assertEquals(System.getenv(name), SystemEnvironmentVariables().get(name))
    }
}
