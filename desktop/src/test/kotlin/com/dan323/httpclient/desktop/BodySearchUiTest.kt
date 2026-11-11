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
import com.dan323.httpclient.viewmodel.display
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

    @Test fun highlightTextMarksMatchesAndTheCurrentOne() {
        val text = "ab\nxabx\nab"
        val shown = highlightText(text, listOf(0..1, 4..5, 8..9), current = 1)
        assertEquals(text, shown.text)
        assertEquals(listOf(0 to 2, 4 to 6, 8 to 10), shown.spanStyles.map { it.start to it.end })
        val backgrounds = shown.spanStyles.map { it.item.background }
        assertTrue(backgrounds[1] != backgrounds[0])        // the current one stands out
        assertEquals(backgrounds[0], backgrounds[2])
        assertEquals(emptyList(), highlightText("a", emptyList(), 0).spanStyles)
    }

    private fun section(body: String, query: String, touched: Long, searchOpen: Boolean = true): Section {
        val o = outcome(body)
        val d = o.display()
        val ui = OutcomeUi(null).also { it.searchOpen = searchOpen; it.query = query; it.touched = touched }
        val text = (d as com.dan323.httpclient.viewmodel.OutcomeDisplay.Response).prettyBody
        val matches = com.dan323.httpclient.viewmodel.findMatches(text, query)
        return Section(d, ui, null, o, if (query.isEmpty()) null else highlightText(text, matches, 0), matches.firstOrNull()?.first, matches.size)
    }

    @Test fun theScrollTargetIsTheResponseSearchedLastAndCountsItemsOfEarlierOnes() {
        val first = section("aaa needle", "needle", touched = 1)
        val second = section("bbb needle", "needle", touched = 2)
        val target = scrollTargetOf(listOf(first, second))!!
        assertTrue(target.ui === second.ui)
        assertEquals(first.itemCount + 2, target.item)          // the first section's items, then header and search row
        assertEquals(4, target.matchStart)
        assertTrue(scrollTargetOf(listOf(section("x", "", 5), section("y", "zzz", 6))) == null)       // nothing matches
        val older = section("needle", "needle", touched = 9)
        assertTrue(scrollTargetOf(listOf(older, section("needle", "needle", touched = 3)))!!.ui === older.ui)
    }

    @Test fun aResponseWithoutAnOpenSearchRowHasNoSearchItem() {
        assertEquals(section("a", "", 0, searchOpen = true).itemCount, section("a", "", 0, searchOpen = false).itemCount + 1)
    }

    @Test fun writeBodyWritesTheExactBytesAndReportsFailures() {
        val dir = Files.createTempDirectory("httpc-save")
        try {
            val bytes = byteArrayOf(0, 10, -1, 13, 10)
            val target: Path = dir.resolve("out.bin")
            assertEquals("Saved 5 bytes to $target", writeBody(target, bytes))
            assertTrue(bytes.contentEquals(Files.readAllBytes(target)))
            assertTrue(writeBody(dir.resolve("missing").resolve("x"), bytes).startsWith("Could not save the body:"))
            assertEquals("Saved 1 bytes to $target", writeBody(target, byteArrayOf(7)))        // replaces an existing file
            assertTrue(byteArrayOf(7).contentEquals(Files.readAllBytes(target)))
            assertEquals(listOf("out.bin"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })   // no temp file is left behind
        } finally { dir.toFile().deleteRecursively() }
    }
}
