package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentScope.PRIVATE
import com.dan323.httpclient.core.port.out.EnvironmentScope.SHARED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentEditorMessagesTest {
    @Test fun saveMessages() {
        assertEquals("Nothing to save.", saveMessage(SaveOutcome.NothingToSave))
        assertEquals("Cannot save: 1 problem below.", saveMessage(SaveOutcome.Invalid(listOf(DraftProblem(null, null, "x")))))
        assertEquals("Cannot save: 2 problems below.", saveMessage(SaveOutcome.Invalid(listOf(DraftProblem(null, null, "x"), DraftProblem(0, null, "y")))))
        assertEquals("Saved the shared file.", saveMessage(SaveOutcome.Saved(listOf(SHARED))))
        assertEquals("Saved the private and shared files.", saveMessage(SaveOutcome.Saved(listOf(PRIVATE, SHARED))))
        assertEquals("disk full", saveMessage(SaveOutcome.Failed("disk full")))
    }

    @Test fun changedAndPartialFailuresSayWhatWasWritten() {
        val changed = saveMessage(SaveOutcome.Changed(PRIVATE))
        assertTrue("private file changed on disk" in changed && "nothing was saved" in changed, changed)
        val after = saveMessage(SaveOutcome.Changed(SHARED, listOf(PRIVATE)))
        assertTrue("shared file changed" in after && "after the private file was saved" in after, after)
        val failed = saveMessage(SaveOutcome.Failed("disk full", listOf(PRIVATE)))
        assertTrue(failed.startsWith("disk full") && "private file was already saved" in failed, failed)
    }

    @Test fun reloadIsNeededWhenTheDraftNoLongerMatches() {
        assertTrue(needsReload(SaveOutcome.Changed(SHARED)))
        assertTrue(needsReload(SaveOutcome.Failed("x", listOf(SHARED))))
        assertFalse(needsReload(SaveOutcome.Failed("x")))
        assertFalse(needsReload(SaveOutcome.Saved(listOf(SHARED))))
        assertFalse(needsReload(SaveOutcome.NothingToSave))
        assertFalse(needsReload(SaveOutcome.Invalid(emptyList())))
    }

    @Test fun problemLinesNameTheEnvironmentAndVariable() {
        val shared = EnvironmentFileContent(mapOf("dev" to mapOf("a" to "1")), "1")
        val draft = EnvironmentDraft.of(shared, EnvironmentFileContent(emptyMap(), null))
            .addVariable(0).addEnvironment()
        val lines = problemLines(
            draft,
            listOf(
                DraftProblem(0, 1, "bad name"),
                DraftProblem(0, 0, "dup"),
                DraftProblem(1, null, "empty"),
                DraftProblem(null, null, "global"),
                DraftProblem(7, 3, "gone"),
            ),
        )
        assertEquals(listOf("dev / variable 2: bad name", "dev / a: dup", "(new environment): empty", "global", "gone"), lines)
    }
}
