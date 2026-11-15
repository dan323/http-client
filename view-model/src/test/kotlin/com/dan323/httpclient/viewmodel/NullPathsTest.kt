package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.domain.EnvironmentEdit.AddEnvironment
import com.dan323.httpclient.core.domain.EnvironmentEdit.SetVariable
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentScope.PRIVATE
import com.dan323.httpclient.core.port.out.EnvironmentScope.SHARED
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The paths where a value is absent (null, no name, no detail) and the fallback answers; each is a place the Kotlin mutators (`?:`, `?.`) can break. */
class NullPathsTest {
    // BodyFileName
    @Test fun `a Content-Disposition without a filename falls back to the URL`() {
        assertEquals("a.json", suggestedFileName("http://x/a.json", "attachment", null))
        assertEquals("a.json", suggestedFileName("http://x/a.json", null, null))
    }

    @Test fun `a filename that sanitizes to nothing falls back to the URL`() {
        assertEquals("a.json", suggestedFileName("http://x/a.json", "attachment; filename=\"...\"", null))
    }

    @Test fun `a URL without a usable segment is named response with the extension of the type`() {
        assertEquals("response.json", suggestedFileName("http://x/", null, "application/json"))
        assertEquals("response.bin", suggestedFileName("http://x/", null, null))
        assertEquals("response.txt", suggestedFileName("http://x/...", null, "text/plain"))
        assertEquals("response.csv", suggestedFileName("http://x/{{id}}", null, "text/csv"))
    }

    @Test fun `an undecodable filename star falls back to the plain filename`() {
        assertEquals("plain.txt", suggestedFileName("http://x/", "attachment; filename*=UTF-8''%ZZ; filename=\"plain.txt\"", null))
        assertEquals("response.bin", suggestedFileName("http://x/", "attachment; filename*=UTF-8''%ZZ", null))
    }

    // HistoryLabels
    @Test fun `an entry label leaves out a missing duration and a missing error`() {
        assertEquals("a  ·  200", entryLabel(ReportEntry("a", "GET", "http://x", 200, null, emptyList(), 0, null)))
        assertEquals("a  ·  failed", entryLabel(ReportEntry("a", "GET", "http://x", null, null, emptyList(), 0, null)))
        assertEquals("a  ·  failed: boom", entryLabel(ReportEntry("a", "GET", "http://x", null, null, emptyList(), 0, "boom")))
    }

    // OutcomeDisplay
    @Test fun `json helpers pass through what is not a json object or array`() {
        assertNull(prettyJsonOrNull("not json"))
        assertNull(prettyJsonOrNull("123"))
        assertEquals("not json", prettyOrRaw("not json"))
        assertTrue(prettyOrRaw("{\"a\":1}").contains("\n"))
        assertNull(parseJsonContainer("\"text\""))
        assertNull(parseJsonContainer("nope"))
    }

    @Test fun `a request summary and an assertion label leave out what is absent`() {
        assertEquals("GET http://x", requestSummary(RequestTemplate(null, "GET", "http://x")))
        assertEquals("login  ·  GET http://x", requestSummary(RequestTemplate("login", "GET", "http://x")))
        assertEquals("PASS a", OutcomeDisplay.AssertionLine("a", true, null).label)
        assertEquals("FAIL a", OutcomeDisplay.AssertionLine("a", false, null).label)
        assertEquals("FAIL a", OutcomeDisplay.AssertionLine("a", false, "  ").label)
        assertEquals("FAIL a -- why", OutcomeDisplay.AssertionLine("a", false, "why").label)
    }

    // EnvironmentItems
    @Test fun `a known environment is not listed twice and an unknown one is marked missing`() {
        assertEquals(listOf("(none)", "dev"), environmentItems(listOf("dev"), EnvironmentChoice.Named("dev")).map { it.label })
        assertEquals(listOf("(none)", "dev", "x (missing)"), environmentItems(listOf("dev"), EnvironmentChoice.Named("x")).map { it.label })
        assertEquals(listOf("(none)", "x"), environmentItems(null, EnvironmentChoice.Named("x")).map { it.label })
        assertEquals(listOf("(none)"), environmentItems(null, EnvironmentChoice.None).map { it.label })
    }

    // FolderTree
    @Test fun `a reload drops an open folder that the new listings do not mention`() {
        val root = Path.of("/r")
        val sub = Path.of("/r/sub")
        val entry = FolderEntry(sub, "sub", true)
        val tree = FolderTree(root, setOf(sub), mapOf(root to FolderContents(listOf(entry)), sub to FolderContents(emptyList())))
        val fresh = tree.reloaded(mapOf(root to FolderContents(listOf(entry))))
        assertEquals(listOf(root), fresh.foldersToList())
    }

    // ResponseDiff
    @Test fun `a second body that is not text is not compared`() {
        fun resp(body: ByteArray) = HttpResponse(200, emptyList(), body, 1)
        val d = diffResponses(resp("text".toByteArray()), resp(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertTrue(d.body is BodyDiff.Skipped)
        assertEquals("a body is not text", (d.body as BodyDiff.Skipped).reason)
    }

    // EnvironmentDraft
    private val none = EnvironmentFileContent(emptyMap(), null)
    private fun content(vararg envs: Pair<String, Map<String, String>>) = EnvironmentFileContent(linkedMapOf(*envs), "v")

    @Test fun `a new environment with a private variable writes only its name to the shared file`() {
        val draft = EnvironmentDraft.of(none, none).addEnvironment("qa").addVariable(0)
            .updateVariable(0, 0) { it.copy(name = "token", value = "s") }
        assertEquals(listOf(AddEnvironment("qa")), draft.edits(SHARED))
        assertTrue(SetVariable("qa", "token", "s") in draft.edits(PRIVATE))
        assertEquals(false, draft.hasSharedCopy(0, "token"))
    }

    @Test fun `a new private variable in an existing environment leaves the shared file alone`() {
        val draft = EnvironmentDraft.of(content("dev" to mapOf("host" to "a")), none).addVariable(0)
            .updateVariable(0, 1) { it.copy(name = "key", value = "k") }
        assertEquals(emptyList(), draft.edits(SHARED))
        assertEquals(listOf(AddEnvironment("dev"), SetVariable("dev", "key", "k")), draft.edits(PRIVATE))
        assertEquals(false, draft.hasSharedCopy(0, "key"))
    }

    @Test fun `a shared variable made private moves and one that was never shared has no shared copy`() {
        val draft = EnvironmentDraft.of(content("dev" to mapOf("host" to "a")), content("dev" to mapOf("key" to "k")))
        assertEquals(false, draft.hasSharedCopy(0, "host"))
        assertEquals(false, draft.hasSharedCopy(0, "key"))
        assertEquals(false, draft.hasSharedCopy(5, "host"))
        val moved = draft.updateVariable(0, 0) { it.copy(private = true) }
        assertEquals(listOf(SHARED to false), listOf(SHARED to moved.edits(SHARED).isEmpty()))
    }

    @Test fun `a private variable with no private original reports no shared copy`() {
        val draft = EnvironmentDraft.of(content("dev" to mapOf("host" to "a")), none)
        val priv = draft.updateVariable(0, 0) { it.copy(private = true) }
        assertEquals(false, priv.hasSharedCopy(0, "host"))
    }
}
