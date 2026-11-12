package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.domain.EnvironmentEditException
import com.dan323.httpclient.core.domain.applying
import com.dan323.httpclient.core.port.out.EnvironmentFileChangedException
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentScope
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonEnvironmentFileStoreTest {
    private val dir: Path = Files.createTempDirectory("httpc-envstore")
    private val shared = dir.resolve("http-client.env.json")
    private val private = dir.resolve("http-client.private.env.json")
    private val store = JsonEnvironmentFileStore(dir)

    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private val original = """
        {
          "dev": { "host": "localhost", "port": 8080, "debug": true, "gone": null, "nested": { "a": 1 } },
          "extra": [1, 2],
          "prod": { "host": "example.com" }
        }
    """.trimIndent()

    @Test fun anAbsentFileReadsAsEmptyWithoutAVersion() {
        val c = store.read(EnvironmentScope.SHARED)
        assertEquals(emptyMap(), c.environments)
        assertNull(c.version)
    }

    @Test fun readsTheEditableViewInFileOrderWithTextNumberAndBooleanValues() {
        Files.writeString(shared, original)
        val c = store.read(EnvironmentScope.SHARED)
        assertEquals(listOf("dev", "extra", "prod"), c.environments.keys.toList())
        assertEquals(mapOf("host" to "localhost", "port" to "8080", "debug" to "true"), c.environments.getValue("dev"))
        assertEquals(emptyMap(), c.environments.getValue("extra"))   // not an object: nothing to edit
        assertTrue(c.version!!.length == 64)
    }

    @Test fun anEditKeepsEverythingElseAsWritten() {
        Files.writeString(shared, original)
        val v = store.read(EnvironmentScope.SHARED).version
        store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.SetVariable("dev", "host", "127.0.0.1")), v)
        val text = Files.readString(shared)
        assertTrue(text.contains("\"port\": 8080"), text)          // still a number
        assertTrue(text.contains("\"debug\": true"), text)         // still a boolean
        assertTrue(text.contains("\"gone\": null"), text)          // a null is kept
        assertTrue(text.contains("\"nested\""), text)
        assertTrue(text.contains("\"extra\""), text)
        assertTrue(text.contains("\"host\": \"127.0.0.1\""), text)
        assertEquals(listOf("dev", "extra", "prod"), Regex("^  \"(\\w+)\"", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList())
        assertTrue(text.endsWith("}\n"))
    }

    @Test fun settingTheSameTextKeepsANumberAndAnotherTextReplacesIt() {
        Files.writeString(shared, original)
        var v = store.read(EnvironmentScope.SHARED).version
        v = store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.SetVariable("dev", "port", "8080")), v)
        assertTrue(Files.readString(shared).contains("\"port\": 8080"))
        store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.SetVariable("dev", "port", "9090")), v)
        assertTrue(Files.readString(shared).contains("\"port\": \"9090\""))
    }

    @Test fun theEditedFileReadsBackAsThePureEditsSayAndWritingReturnsItsVersion() {
        Files.writeString(shared, original)
        val before = store.read(EnvironmentScope.SHARED)
        val edits = listOf(
            EnvironmentEdit.SetVariable("dev", "token", "t"), EnvironmentEdit.RemoveVariable("dev", "port"),
            EnvironmentEdit.AddEnvironment("qa"), EnvironmentEdit.SetVariable("qa", "x", "1"),
            EnvironmentEdit.RenameEnvironment("prod", "live"), EnvironmentEdit.RemoveEnvironment("extra"),
        )
        val newVersion = store.write(EnvironmentScope.SHARED, edits, before.version)
        val after = store.read(EnvironmentScope.SHARED)
        assertEquals(newVersion, after.version)
        assertEquals(before.environments.applying(edits), after.environments)
        assertEquals(listOf("dev", "live", "qa"), after.environments.keys.toList())   // a rename keeps the place
    }

    @Test fun anEmptyEditListWritesNothing() {
        Files.writeString(shared, original)
        val v = store.read(EnvironmentScope.SHARED).version
        assertEquals(v, store.write(EnvironmentScope.SHARED, emptyList(), v))
        assertEquals(original, Files.readString(shared))
        assertNull(store.write(EnvironmentScope.PRIVATE, emptyList(), null))
        assertFalse(Files.exists(private))
    }

    @Test fun aNewFileIsCreatedAndAPrivateOneIsOwnerOnly() {
        assertTrue(store.write(EnvironmentScope.PRIVATE, listOf(EnvironmentEdit.AddEnvironment("dev"), EnvironmentEdit.SetVariable("dev", "token", "s3cret")), null) != null)
        assertEquals(mapOf("token" to "s3cret"), store.read(EnvironmentScope.PRIVATE).environments.getValue("dev"))
        assumeTrue(Files.getFileStore(private).supportsFileAttributeView("posix"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(private)))
        assertFalse(Files.exists(shared))
    }

    @Test fun aFileChangedAfterItWasReadIsNotOverwritten() {
        Files.writeString(shared, original)
        val v = store.read(EnvironmentScope.SHARED).version
        Files.writeString(shared, original.replace("localhost", "elsewhere"))
        assertFailsWith<EnvironmentFileChangedException> { store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.AddEnvironment("qa")), v) }
        assertTrue(Files.readString(shared).contains("elsewhere"))
        assertFailsWith<EnvironmentFileChangedException> { store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.AddEnvironment("qa")), null) }   // it appeared
        Files.delete(shared)
        assertFailsWith<EnvironmentFileChangedException> { store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.AddEnvironment("qa")), v) }   // it vanished
    }

    @Test fun aFileThatDoesNotParseIsNeverReplacedAndTheMessageHasNoContent() {
        Files.writeString(shared, """{"dev": {"token": "very-secret" """)
        val e = assertFailsWith<EnvironmentFileException> { store.read(EnvironmentScope.SHARED) }
        assertTrue(e.message!!.contains("malformed JSON"))
        assertFalse(e.message!!.contains("very-secret"))
        assertFailsWith<EnvironmentFileException> { store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.AddEnvironment("qa")), "x") }
        Files.writeString(shared, "[1]")
        assertTrue(assertFailsWith<EnvironmentFileException> { store.read(EnvironmentScope.SHARED) }.message!!.contains("JSON object"))
        Files.write(shared, byteArrayOf(0x7b, 0x22, -1, -2, 0x7d))
        assertTrue(assertFailsWith<EnvironmentFileException> { store.read(EnvironmentScope.SHARED) }.message!!.contains("UTF-8"))
    }

    @Test fun editsThatCannotBeAppliedWriteNothingAndNameNoValues() {
        Files.writeString(shared, original)
        val v = store.read(EnvironmentScope.SHARED).version
        fun fail(vararg edits: EnvironmentEdit) = assertFailsWith<EnvironmentEditException> { store.write(EnvironmentScope.SHARED, edits.toList(), v) }.message!!
        assertEquals("Unknown environment: nope", fail(EnvironmentEdit.SetVariable("nope", "a", "secret")))
        assertEquals("The environment prod already exists.", fail(EnvironmentEdit.RenameEnvironment("dev", "prod")))
        assertTrue(fail(EnvironmentEdit.SetVariable("extra", "a", "secret")).contains("not a JSON object"))
        assertTrue(fail(EnvironmentEdit.SetVariable("dev", "bad name", "v")).contains("variable name"))
        // the second edit fails: the first one is not written either
        fail(EnvironmentEdit.AddEnvironment("qa"), EnvironmentEdit.AddEnvironment("qa"))
        assertEquals(original, Files.readString(shared))
    }

    @Test fun aLinkIsNotReplaced() {
        val target = Files.writeString(dir.resolve("real.json"), original)
        try { Files.createSymbolicLink(shared, target) } catch (_: Exception) { assumeTrue(false) }
        val v = store.read(EnvironmentScope.SHARED).version
        val e = assertFailsWith<EnvironmentFileException> { store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.AddEnvironment("qa")), v) }
        assertTrue(e.message!!.contains("link"))
        assertTrue(Files.isSymbolicLink(shared))
        assertEquals(original, Files.readString(target))
    }

    @Test fun aBrokenLinkIsAnErrorNotAnAbsentFile() {
        try { Files.createSymbolicLink(shared, dir.resolve("missing.json")) } catch (_: Exception) { assumeTrue(false) }
        assertFailsWith<EnvironmentFileException> { store.read(EnvironmentScope.SHARED) }
    }

    @Test fun theVersionChangesWithTheContent() {
        Files.writeString(shared, original)
        val a = store.read(EnvironmentScope.SHARED).version
        val b = store.write(EnvironmentScope.SHARED, listOf(EnvironmentEdit.AddEnvironment("qa")), a)
        assertNotEquals(a, b)
    }

    @Test fun theLocatorEditsTheNearestEnvironmentFolderOrTheFilesOwnFolder() {
        val project = Files.createDirectories(dir.resolve("project/.git").parent)
        Files.createDirectories(project.resolve(".git"))
        val sub = Files.createDirectories(project.resolve("api"))
        val http = Files.writeString(sub.resolve("a.http"), "GET http://x\n")
        val locator = UpwardEnvironmentLocator(boundaries = emptySet())
        assertEquals(sub.toAbsolutePath().normalize(), locator.storeFor(http).directory)                 // none yet: next to the file
        Files.writeString(project.resolve("http-client.env.json"), "{}")
        assertEquals(project.toRealPath(), locator.storeFor(http).directory.toRealPath())               // the one find() would use
        assertTrue(locator.find(http) != null)                                                            // and find() agrees there is a folder
    }

    @Test fun whatTheStoreWritesIsWhatTheRepositoryReadsForARun() {
        store.write(EnvironmentScope.SHARED, listOf(
            EnvironmentEdit.AddEnvironment("\$shared"), EnvironmentEdit.SetVariable("\$shared", "common", "c"),
            EnvironmentEdit.AddEnvironment("dev"), EnvironmentEdit.SetVariable("dev", "host", "h"),
        ), null)
        store.write(EnvironmentScope.PRIVATE, listOf(EnvironmentEdit.AddEnvironment("dev"), EnvironmentEdit.SetVariable("dev", "host", "private-h")), null)
        val dev = JsonEnvironmentRepository(dir).load().single { it.name == "dev" }
        assertEquals(mapOf("common" to "c", "host" to "private-h"), dev.variables)
    }
}
