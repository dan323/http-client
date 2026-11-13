package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.domain.EditableEnvironments
import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.domain.applying
import com.dan323.httpclient.core.port.out.EnvironmentFileChangedException
import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentScope
import com.dan323.httpclient.core.port.out.EnvironmentScope.PRIVATE
import com.dan323.httpclient.core.port.out.EnvironmentScope.SHARED
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentEditorTest {
    /** Two files in memory; a write applies the edits with the real `applying` and bumps the version. */
    private class FakeStore(shared: EditableEnvironments? = null, private: EditableEnvironments? = null) : EnvironmentFileStore {
        override val directory: Path = Path.of("env")
        val files = mutableMapOf<EnvironmentScope, EditableEnvironments?>(SHARED to shared, PRIVATE to private)
        val versions = mutableMapOf<EnvironmentScope, Int>(SHARED to 1, PRIVATE to 1)
        var readFails: EnvironmentScope? = null
        var writeFails: EnvironmentScope? = null
        var raceOnWrite: EnvironmentScope? = null
        var readFailsAfterWrite = false
        val writes = mutableListOf<EnvironmentScope>()

        override fun read(scope: EnvironmentScope): EnvironmentFileContent {
            if (readFails == scope) throw EnvironmentFileException(directory.resolve(scope.name), "unreadable")
            val f = files[scope]
            return EnvironmentFileContent(f.orEmpty(), if (f == null) null else versions.getValue(scope).toString())
        }

        override fun write(scope: EnvironmentScope, edits: List<EnvironmentEdit>, expectedVersion: String?): String? {
            if (writeFails == scope) throw EnvironmentFileException(directory.resolve(scope.name), "disk full")
            if (raceOnWrite == scope) throw EnvironmentFileChangedException(directory.resolve(scope.name))
            files[scope] = files[scope].orEmpty().applying(edits)
            writes += scope
            if (readFailsAfterWrite) readFails = scope
            return versions.merge(scope, 1, Int::plus).toString()
        }
    }

    private fun store() = FakeStore(
        shared = mapOf("dev" to mapOf("host" to "a", "token" to "public")),
        private = mapOf("dev" to mapOf("token" to "secret")),
    )

    private fun loaded(s: FakeStore = store()) = EnvironmentEditor(s).also { assertNull(it.load()) }

    @Test fun `load shows both files and a missing file as empty`() {
        val e = loaded(FakeStore(shared = mapOf("dev" to mapOf("a" to "1"))))
        assertEquals(listOf("dev"), e.draft.environments.map { it.name })
        assertNull(e.draft.version(PRIVATE))
        assertNull(e.loadError)
    }

    @Test fun `a file that cannot be read leaves an empty draft that cannot be saved, and a later load recovers`() {
        val s = store().also { it.readFails = PRIVATE }
        val e = EnvironmentEditor(s)
        val error = e.load()
        assertNotNull(error)
        assertEquals(error, e.loadError)
        assertTrue(e.draft.environments.isEmpty())
        assertTrue(e.save() is SaveOutcome.Failed)
        s.readFails = null
        assertNull(e.load())
        assertNull(e.loadError)
        assertEquals(1, e.draft.environments.size)
    }

    @Test fun `saving an unchanged draft writes nothing`() {
        val s = store()
        assertEquals(SaveOutcome.NothingToSave, loaded(s).save())
        assertTrue(s.writes.isEmpty())
    }

    @Test fun `saving writes only the files that changed and then shows the files as they are`() {
        val s = store()
        val e = loaded(s)
        e.edit { it.updateVariable(0, 0) { v -> v.copy(value = "b") } }
        assertEquals(SaveOutcome.Saved(listOf(SHARED)), e.save())
        assertEquals(listOf(SHARED), s.writes)
        assertEquals("b", s.files.getValue(SHARED)!!.getValue("dev").getValue("host"))
        assertEquals("b", e.draft.environments[0].variables[0].value)
        assertEquals(SaveOutcome.NothingToSave, e.save())
        e.edit { it.updateVariable(0, 1) { v -> v.copy(private = false) } }
        assertEquals(SaveOutcome.Saved(listOf(SHARED, PRIVATE)), e.save())
        assertEquals(mapOf("host" to "b", "token" to "secret"), s.files.getValue(SHARED)!!.getValue("dev"))
        assertEquals(emptyMap(), s.files.getValue(PRIVATE)!!.getValue("dev"))
    }

    @Test fun `a draft with problems is not saved`() {
        val s = store()
        val e = loaded(s)
        e.edit { it.addEnvironment("") }
        val out = e.save()
        assertTrue(out is SaveOutcome.Invalid && out.problems.isNotEmpty())
        assertTrue(s.writes.isEmpty())
        assertEquals(2, e.draft.environments.size)
    }

    @Test fun `a file changed on disk is reported before anything is written`() {
        val s = store()
        val e = loaded(s)
        e.edit { it.updateVariable(0, 0) { v -> v.copy(value = "b") }.updateVariable(0, 1) { v -> v.copy(value = "x") } }
        s.versions[PRIVATE] = 7
        assertEquals(SaveOutcome.Changed(PRIVATE), e.save())
        assertTrue(s.writes.isEmpty())
        assertEquals(false, e.needsReload)
        assertNull(e.load())
        assertEquals(SaveOutcome.NothingToSave, e.save())
    }

    @Test fun `a race on the first write reports the change and nothing needs a reload`() {
        val s = store().also { it.raceOnWrite = SHARED }
        val e = loaded(s)
        e.edit { it.updateVariable(0, 0) { v -> v.copy(value = "b") } }
        assertEquals(SaveOutcome.Changed(SHARED), e.save())
        assertEquals(false, e.needsReload)
    }

    @Test fun `a failure after the first file was written says so and blocks saving until a reload`() {
        val s = store().also { it.writeFails = PRIVATE }
        val e = loaded(s)
        e.edit { it.updateVariable(0, 0) { v -> v.copy(value = "b") }.updateVariable(0, 1) { v -> v.copy(value = "x") } }
        val out = e.save()
        assertTrue(out is SaveOutcome.Failed && out.written == listOf(SHARED) && "disk full" in out.message)
        assertTrue(e.needsReload)
        s.writeFails = null
        val again = e.save()
        assertTrue(again is SaveOutcome.Failed && again.written.isEmpty())
        assertNull(e.load())
        assertEquals(false, e.needsReload)
        assertEquals("b", e.draft.environments[0].variables[0].value)
    }

    @Test fun `a race on the second write also needs a reload`() {
        val s = store().also { it.raceOnWrite = PRIVATE }
        val e = loaded(s)
        e.edit { it.updateVariable(0, 0) { v -> v.copy(value = "b") }.updateVariable(0, 1) { v -> v.copy(value = "x") } }
        assertEquals(SaveOutcome.Changed(PRIVATE), e.save())
        assertTrue(e.needsReload)
    }

    @Test fun `a failure to read after saving is reported with what was written`() {
        val s = store().also { it.readFailsAfterWrite = true }
        val e = loaded(s)
        e.edit { it.updateVariable(0, 0) { v -> v.copy(value = "b") } }
        val out = e.save()
        assertTrue(out is SaveOutcome.Failed && out.written == listOf(SHARED) && "unreadable" in out.message)
        assertNotNull(e.loadError)
        assertTrue(e.draft.environments.isEmpty())
    }
}
