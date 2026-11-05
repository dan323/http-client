package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.UnknownEnvironmentException
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.core.application.select
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentLocationTest {
    private val root = Files.createTempDirectory("envloc")
    private fun write(rel: String, text: String): Path =
        root.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    @Test fun findsEnvFileInParentFolders() {
        write("http-client.env.json", """{"dev":{"a":"1"}}""")
        val file = write("api/v1/x.http", "GET http://x")
        assertEquals(listOf("dev"), UpwardEnvironmentLocator().environments(file).map { it.name })
    }

    @Test fun nearestDirectoryWinsAndFarOnesAreIgnored() {
        write("http-client.env.json", """{"far":{}}""")
        write("api/http-client.env.json", """{"near":{"a":"1"}}""")
        write("api/http-client.private.env.json", """{"near":{"secret":"s"}}""")
        val file = write("api/v1/x.http", "GET http://x")
        val envs = UpwardEnvironmentLocator().environments(file)
        assertEquals(listOf("near"), envs.map { it.name })
        assertEquals(mapOf("a" to "1", "secret" to "s"), envs.single().variables)
    }

    @Test fun jsonNullIsUndefinedNotTheStringNull() {
        write("http-client.env.json", """{"dev":{"a":null,"b":"x","c":3}}""")
        val dev = JsonEnvironmentRepository(root).load().single()
        assertEquals(mapOf("b" to "x", "c" to "3"), dev.variables)
    }

    @Test fun malformedJsonNamesTheFileAndPosition() {
        val f = write("http-client.env.json", """{"dev":{"a":}}""")
        val e = assertFailsWith<EnvironmentFileException> { JsonEnvironmentRepository(root).load() }
        assertTrue(e.message!!.contains(f.toString()), e.message)
        assertTrue(e.message!!.contains("offset") || e.message!!.contains("position") || e.message!!.contains("JSON"), e.message)
    }

    @Test fun malformedPrivateFileAndNonObjectRootNameTheFile() {
        write("http-client.env.json", """{"dev":{}}""")
        val p = write("http-client.private.env.json", "[1]")
        val e = assertFailsWith<EnvironmentFileException> { JsonEnvironmentRepository(root).load() }
        assertTrue(e.message!!.contains(p.toString()))
    }

    @Test fun choiceNeverFallsBack() {
        write("http-client.env.json", """{"dev":{"a":"1"},"prod":{"a":"2"}}""")
        val envs = JsonEnvironmentRepository(root).load()
        assertNull(EnvironmentChoice.None.select(envs))
        assertEquals("prod", EnvironmentChoice.Named("prod").select(envs)!!.name)
        val e = assertFailsWith<UnknownEnvironmentException> { EnvironmentChoice.Named("gone").select(envs) }
        assertTrue(e.message!!.contains("gone") && e.message!!.contains("dev"))
        assertFailsWith<UnknownEnvironmentException> { EnvironmentChoice.Named("x").select(emptyList()) }
    }

    @Test fun allowlistMergesExtraWithPropertyAndVariable() {
        val v = SystemEnvironmentVariables.fromConfiguration(
            property = { if (it == "httpclient.allowEnv") "A" else null }, env = { "B" }, extra = setOf("C"),
        )
        assertTrue(v.isAllowed("A") && v.isAllowed("C"))
        val fromVar = SystemEnvironmentVariables.fromConfiguration(property = { null }, env = { if (it == "HTTPC_ALLOW_ENV") "B" else null }, extra = setOf("C"))
        assertTrue(fromVar.isAllowed("B") && fromVar.isAllowed("C") && !fromVar.isAllowed("D"))
    }
}
