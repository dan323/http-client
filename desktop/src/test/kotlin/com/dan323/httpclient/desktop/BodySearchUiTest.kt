package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BodySearchUiTest {
    private fun outcome(body: String) = RunOutcome.Success(
        RequestTemplate("r", "GET", "http://x/data.txt"),
        HttpResponse(200, listOf(Header("Content-Type", "text/plain")), body.toByteArray(), 5),
    )

    @Test fun searchCountsMatchesAndStepsThroughThem() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome("one needle\ntwo\nthree needle\nfour needle"))) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagExists("body-search-toggle") }
        assertTrue(!onAllNodesWithTagExists("body-search"))     // hidden until asked for
        onNodeWithTag("body-search-toggle").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagExists("body-search") }
        onNodeWithTag("body-search").performTextInput("NEEDLE")
        waitUntil(timeoutMillis = 10_000) { textOf("body-search-count") == "1 of 3" }
        onNodeWithTag("body-search-next").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { textOf("body-search-count") == "2 of 3" }
        onNodeWithTag("body-search-prev").performClick()
        onNodeWithTag("body-search-prev").performClick()
        waitUntil(timeoutMillis = 10_000) { textOf("body-search-count") == "3 of 3" }   // wraps around
        onNodeWithTag("body-search-toggle").performClick()                               // closing the search clears it
        waitUntil(timeoutMillis = 10_000) { !onAllNodesWithTagExists("body-search") }
        onNodeWithTag("body-search-toggle").performClick()
        waitUntil(timeoutMillis = 10_000) { textOf("body-search-count") == "" }
    }

    @Test fun noMatchesAreSaidAndNeedNoStepping() = runComposeUiTest {
        setContent { ResponsePane(listOf(outcome("hello"))) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagExists("body-search-toggle") }
        assertTrue(!onAllNodesWithTagExists("body-search"))     // hidden until asked for
        onNodeWithTag("body-search-toggle").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagExists("body-search") }
        onNodeWithTag("body-search").performTextInput("zzz")
        waitUntil(timeoutMillis = 10_000) { textOf("body-search-count") == "No matches" }
        onNodeWithTag("body-search-next").assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String): String? =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    @Test fun highlightedLinesMarkMatchesAndTheCurrentOne() {
        val text = "ab\nxabx\nab"
        val matches = listOf(0..1, 4..5, 8..9)
        val lines = highlightedLines(text, matches, current = 1)
        assertEquals(listOf("ab", "xabx", "ab"), lines.map { it.text })
        assertEquals(1, lines[0].spanStyles.size)
        assertEquals(1, lines[1].spanStyles.size)
        assertEquals(1, lines[1].spanStyles.single().start)
        assertEquals(3, lines[1].spanStyles.single().end)
        assertTrue(lines[1].spanStyles.single().item.background != lines[0].spanStyles.single().item.background)   // the current one stands out
        assertEquals(emptyList(), highlightedLines("a", emptyList(), 0).single().spanStyles)
    }

    @Test fun aMatchSpanningALineBreakIsClippedToEachLine() {
        val lines = highlightedLines("ab\ncd", listOf(1..3), 0)    // "b\nc"
        assertEquals(1, lines[0].spanStyles.single().start)
        assertEquals(2, lines[0].spanStyles.single().end)
        assertEquals(0, lines[1].spanStyles.single().start)
        assertEquals(1, lines[1].spanStyles.single().end)
    }

    @Test fun writeBodyWritesTheExactBytesAndReportsFailures() {
        val dir = Files.createTempDirectory("httpc-save")
        try {
            val bytes = byteArrayOf(0, 10, -1, 13, 10)
            val target: Path = dir.resolve("out.bin")
            assertEquals("Saved 5 bytes to $target", writeBody(target, bytes))
            assertTrue(bytes.contentEquals(Files.readAllBytes(target)))
            assertTrue(writeBody(dir.resolve("missing").resolve("x"), bytes).startsWith("Could not save the body:"))
        } finally { dir.toFile().deleteRecursively() }
    }
}
