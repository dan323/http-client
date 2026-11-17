package com.dan323.httpclient.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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

/** Find and replace in the editor, through the real composables. */
@OptIn(ExperimentalTestApi::class)
class EditorFindTest {
    private fun fixture(root: Path, text: String = "GET http://x/a\nGET http://x/A\nGET http://x/b\n"): AppState {
        val file = root.resolve("t.http")
        Files.writeString(file, text)
        return AppState().also { it.open(file) }
    }

    @Test fun theFindStateSearchesOnlyWhileOpenAndHonoursTheCaseOption() {
        val f = FindState()
        f.query = "a"
        assertEquals(emptyList(), f.matches("aA"))
        f.open = true
        assertEquals(listOf(0..0, 1..1), f.matches("aA"))
        f.matchCase = true
        assertEquals(listOf(0..0), f.matches("aA"))
    }

    @Test fun matchesAreMarkedAndTheCurrentOneIsStronger() {
        val p = HighlightPalette.Light
        val styled = highlight("ab ab", p, matches = listOf(0..1, 3..4), current = 3..4)
        fun bg(at: Int) = styled.spanStyles.filter { it.start <= at && at < it.end && it.item.background != Color.Unspecified }.map { it.item.background }
        assertEquals(listOf(p.match), bg(0))
        assertEquals(listOf(p.currentMatch), bg(3))
        assertEquals(emptyList(), bg(2))
        assertEquals("ab ab", highlight("ab ab", p, matches = listOf(10..12)).text)   // a range outside the text is ignored
    }

    @Test fun theBarOpensFromTheButtonAndClosesWithEscape() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-bar").assertDoesNotExist()
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-bar").assertExists()
            onNodeWithTag("find-query").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag("find-bar").assertDoesNotExist()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun ctrlFInTheEditorOpensTheBar() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("editor").performClick()
            onNodeWithTag("editor").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
            waitForIdle()
            onNodeWithTag("find-bar").assertExists()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun nextAndPrevGoThroughTheMatchesAndWrap() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("x/a")          // matches a and A: case is ignored
            waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("2 matches")   // none selected yet
            onNodeWithTag("find-next").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("1 of 2")      // the first match is selected
            onNodeWithTag("find-next").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("2 of 2")
            onNodeWithTag("find-next").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("1 of 2")      // wrapped
            onNodeWithTag("find-prev").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("2 of 2")      // wrapped back
            onNodeWithTag("find-case").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("1 match")       // match case: only x/a; the selected x/A is no longer a match
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun replacingTheLastMatchByTextThatContainsItDoesNotSelectItsOwnOutput() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root, "a")
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("a")
            onNodeWithTag("find-replace").performTextInput("aa")
            waitForIdle()
            onNodeWithTag("find-replace-one").performClick(); waitForIdle()     // selects the match
            onNodeWithTag("find-replace-one").performClick(); waitForIdle()     // replaces it
            assertEquals("aa", state.text)
            onNodeWithTag("find-count").assertTextEquals("2 matches")           // nothing selected: the inserted text is not offered
            onNodeWithTag("find-replace-one").performClick(); waitForIdle()     // so this only selects, it does not grow the text
            assertEquals("aa", state.text)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun ctrlFWhileTheBarIsOpenMovesTheKeyboardBackToTheSearchBox() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").assertIsFocused()
            onNodeWithTag("editor").performClick()
            onNodeWithTag("editor").assertIsFocused()
            onNodeWithTag("editor").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
            waitForIdle()
            onNodeWithTag("find-query").assertIsFocused()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun theReplaceAllResultIsHiddenOnceTheTextChanges() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("x/a")
            onNodeWithTag("find-replace").performTextInput("x/q")
            waitForIdle()
            onNodeWithTag("find-replace-all").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("Replaced 2 matches")
            state.edit(state.text + "# more\n")
            waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("No matches")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun theReplaceAllResultIsHiddenOnAnotherTabWithTheSameTextAndAfterAnEditBackToTheSameText() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            val twin = root.resolve("u.http").also { Files.writeString(it, state.text) }
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("x/a")
            onNodeWithTag("find-replace").performTextInput("x/q")
            waitForIdle()
            onNodeWithTag("find-replace-all").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("Replaced 2 matches")
            val replaced = state.text
            state.edit(replaced + "#"); state.edit(replaced)               // changed and changed back: the same text again
            waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("No matches")
            state.open(twin)                                              // another tab; same text as the file had before the replacement
            state.edit(replaced)
            waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("No matches")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun theReplaceAllResultDoesNotComeBackWhenTheTabIsSwitchedAwayAndBackWithoutAnEdit() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            val other = root.resolve("u.http").also { Files.writeString(it, "GET http://u\n") }
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("x/a")
            onNodeWithTag("find-replace").performTextInput("x/q")
            waitForIdle()
            onNodeWithTag("find-replace-all").performClick(); waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("Replaced 2 matches")
            state.open(other)                // another tab (the first one stays open)
            waitForIdle()
            state.switchTo(0)                // back to the first, unchanged
            waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("No matches")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun noMatchesAreReportedAndNothingCanBeReplaced() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("zzz")
            waitForIdle()
            onNodeWithTag("find-count").assertTextEquals("No matches")
            onNodeWithTag("find-next").assertIsNotEnabled()
            onNodeWithTag("find-replace-one").assertIsNotEnabled()
            onNodeWithTag("find-replace-all").assertIsNotEnabled()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun replaceFirstSelectsTheMatchThenReplacesItAndMovesOn() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("x/a")
            onNodeWithTag("find-replace").performTextInput("x/new")
            waitForIdle()
            onNodeWithTag("find-replace-one").assertIsEnabled()
            onNodeWithTag("find-replace-one").performClick(); waitForIdle()
            assertEquals("GET http://x/a\nGET http://x/A\nGET http://x/b\n", state.text)   // the first click only selects
            onNodeWithTag("find-replace-one").performClick(); waitForIdle()
            assertEquals("GET http://x/new\nGET http://x/A\nGET http://x/b\n", state.text)
            onNodeWithTag("find-count").assertTextEquals("1 of 1")                          // the next match is selected
            onNodeWithTag("find-replace-one").performClick(); waitForIdle()
            assertEquals("GET http://x/new\nGET http://x/new\nGET http://x/b\n", state.text)
            assertTrue(state.dirty)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun replaceAllReplacesEveryMatchAndSaysHowMany() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-find")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("find-toggle").performClick()
            onNodeWithTag("find-query").performTextInput("http://x/")
            onNodeWithTag("find-replace").performTextInput("{{base}}/")
            waitForIdle()
            onNodeWithTag("find-replace-all").performClick(); waitForIdle()
            assertEquals("GET {{base}}/a\nGET {{base}}/A\nGET {{base}}/b\n", state.text)
            onNodeWithTag("find-count").assertTextEquals("Replaced 3 matches")
        } finally { root.toFile().deleteRecursively() }
    }
}
