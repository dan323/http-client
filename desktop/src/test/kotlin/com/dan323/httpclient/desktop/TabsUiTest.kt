package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabsUiTest {
    private val dir = Files.createTempDirectory("httpc-tabs-ui")
    private fun state(): AppState = AppState().also {
        it.open(dir.resolve("a.http").also { f -> Files.writeString(f, "# @name first\nGET http://x/a\n") })
        it.open(dir.resolve("b.http").also { f -> Files.writeString(f, "# @name second\nGET http://x/b\n") })
    }

    private fun androidx.compose.ui.test.ComposeUiTest.exists(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theTabBarListsTheFilesAndClickingSwitches() = runComposeUiTest {
        val s = state()
        setContent { AppRoot(s) }
        onNodeWithTag("tab-0").assertExists(); onNodeWithTag("tab-1").assertExists()
        onNodeWithText("second").assertExists()
        onNodeWithTag("tab-0").performClick()
        waitForIdle()
        assertEquals(0, s.activeTab)
        onNodeWithText("first").assertExists()
        s.close()
    }

    @Test fun closingATabWithEditsShowsThePromptAndDiscardCloses() = runComposeUiTest {
        val s = state()
        s.edit("# @name changed\nGET http://x/b\n")
        setContent { AppRoot(s) }
        onNodeWithTag("tab-close-1").performClick()
        waitForIdle()
        assertTrue(exists("close-prompt"))
        onNodeWithTag("close-cancel").performClick()
        waitForIdle()
        assertTrue(!exists("close-prompt"))
        onNodeWithTag("tab-close-1").performClick()
        onNodeWithTag("close-discard").performClick()
        waitForIdle()
        assertEquals(1, s.tabs.size)
        assertTrue(!exists("tab-1"))
        s.close()
    }
}
