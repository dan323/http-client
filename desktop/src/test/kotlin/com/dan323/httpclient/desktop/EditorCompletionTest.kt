package com.dan323.httpclient.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Completion of `{{names}}` in the editor, through the real composables. */
@OptIn(ExperimentalTestApi::class)
class EditorCompletionTest {
    private fun fixture(root: Path, request: String = "\nGET http://x\n"): AppState {
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x","basket":"1"}}""")
        val file = root.resolve("t.http")
        Files.writeString(file, request)
        return AppState().also { it.open(file); it.environment = "dev" }
    }

    @Test fun typingOpeningBracesAndANameOffersTheMatchingVariables() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("suggestions").assertDoesNotExist()
            onNodeWithTag("editor").performTextInput("{{ba")
            waitForIdle()
            onNodeWithTag("suggestion-0").assertExists()
            onNodeWithTag("suggestion-1").assertExists()
            onNodeWithTag("suggestion-2").assertDoesNotExist()   // only base and basket match "ba"
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun clickingASuggestionInsertsItAndClosesTheReference() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("editor").performTextInput("{{ba")
            waitForIdle()
            onNodeWithTag("suggestion-0").performClick()
            waitForIdle()
            assertEquals("{{base}}\nGET http://x\n", state.text)
            onNodeWithTag("suggestions").assertDoesNotExist()
            onNodeWithTag("editor").assertIsFocused()   // the keyboard stays in the editor
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun downThenEnterPicksTheSecondSuggestionAndEscapeHidesTheList() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("editor").performTextInput("{{ba")
            waitForIdle()
            onNodeWithTag("editor").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag("suggestions").assertDoesNotExist()
            assertEquals("{{ba\nGET http://x\n", state.text)
            onNodeWithTag("editor").performTextInput("s")   // typing again brings the list back
            waitForIdle()
            onNodeWithTag("suggestion-0").assertExists()
            onNodeWithTag("editor").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("{{basket}}\nGET http://x\n", state.text)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun tabAcceptsTheFirstSuggestionButShiftTabAndAPlainEnterDoNot() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("editor").performTextInput("{{ba")
            waitForIdle()
            onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }          // a line break, not an acceptance
            waitForIdle()
            assertTrue(state.text.startsWith("{{ba"), state.text)
            assertTrue("}}" !in state.text.substringBefore("GET"), state.text)
            onNodeWithTag("editor").performTextInput("")                             // keep the field focused
            onNodeWithTag("editor").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
            waitForIdle()
            assertTrue("}}" !in state.text.substringBefore("GET"), state.text)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun tabAcceptsTheFirstSuggestion() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("editor").performTextInput("{{ba")
            waitForIdle()
            onNodeWithTag("editor").performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            assertEquals("{{base}}\nGET http://x\n", state.text)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun nothingIsOfferedOutsideAReferenceOrWhenNothingMatches() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("editor").performTextInput("abc")
            waitForIdle()
            onNodeWithTag("suggestions").assertDoesNotExist()
            onNodeWithTag("editor").performTextInput("{{zzz")
            waitForIdle()
            onNodeWithTag("suggestions").assertDoesNotExist()
            assertTrue(state.text.startsWith("abc{{zzz"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun fileVariablesAndEarlierRequestsAreOffered() {
        val root = Files.createTempDirectory("httpc-complete")
        try {
            val state = fixture(root, "@host = h\n# @name login\nGET http://x\n\n###\nGET http://y/\n")
            val names = state.knownVariablesAt(state.text.length).map { it.name }
            assertTrue("host" in names && "login.response." in names && "base" in names, names.toString())
            assertEquals(emptyList(), AppState().knownVariablesAt(0).map { it.name })
        } finally { root.toFile().deleteRecursively() }
    }
}
