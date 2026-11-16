package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.style.TextDecoration
import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.Severity
import com.dan323.httpclient.core.application.SourceRange
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `{{variable}}` checks in the editor: which names count as defined, the underlines and the problems list. */
@OptIn(ExperimentalTestApi::class)
class EditorProblemsTest {
    private fun fixture(root: Path, request: String = "GET {{base}}/get\n"): AppState {
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
        val file = root.resolve("t.http")
        Files.writeString(file, request)
        return AppState().also { it.open(file) }
    }

    private fun messages(state: AppState) = state.problems.map { it.message }

    @Test fun aVariableIsUndefinedUntilAnEnvironmentThatHasItIsChosen() {
        val root = Files.createTempDirectory("httpc-problems")
        try {
            val state = fixture(root)
            assertEquals(1, state.problems.size)
            assertTrue(messages(state).single().contains("base"), messages(state).toString())
            state.environment = "dev"
            assertEquals(emptyList(), state.problems)
            state.environment = null
            assertEquals(1, state.problems.size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun editingTheTextUpdatesTheProblems() {
        val root = Files.createTempDirectory("httpc-problems")
        try {
            val state = fixture(root)
            state.environment = "dev"
            state.edit("GET {{base}}/{{missing}}\n")
            assertEquals(1, state.problems.size)
            assertTrue(messages(state).single().contains("missing"))
            state.edit("@missing = 1\nGET {{base}}/{{missing}}\n")
            assertEquals(emptyList(), state.problems)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun anEnvironmentThatNoLongerExistsDefinesNothing() {
        val root = Files.createTempDirectory("httpc-problems")
        try {
            val state = fixture(root)
            state.environment = "gone"
            assertEquals(1, state.problems.size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun closingATabRefreshesTheEnvironmentsOfTheTabThatComesOnScreen() {
        val root = Files.createTempDirectory("httpc-problems")
        try {
            val state = fixture(root)                       // t.http
            val other = root.resolve("u.http").also { Files.writeString(it, "GET {{base}}/u\n") }
            state.environment = "dev"
            state.open(other)
            state.environment = "dev"
            assertEquals(emptyList(), state.problems)
            // the shared file changes (say, through the environment editor of the second tab): `base` is gone
            Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"other":"1"}}""")
            state.closeTab(1)
            assertEquals(1, state.problems.size, "the revealed tab must see the new variable names")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun noOpenFileHasNoProblems() = assertEquals(emptyList(), AppState().problems)

    @Test fun problemsAreUnderlinedByTheirSeverity() {
        val p = HighlightPalette.Light
        val text = "GET {{a}}/{{b}}\n"
        val error = Diagnostic("c", Severity.ERROR, "m", SourceRange(4, 5, 0, 4))
        val warning = Diagnostic("c", Severity.WARNING, "m", SourceRange(10, 5, 0, 10))
        val outside = Diagnostic("c", Severity.ERROR, "m", SourceRange(100, 5, 0, 0))
        val styled = highlight(text, p, listOf(error, warning, outside))
        fun underlined(at: Int) = styled.spanStyles.filter { it.start <= at && at < it.end && it.item.textDecoration == TextDecoration.Underline }
        assertEquals(p.error, underlined(5).single().item.color)
        assertEquals(p.warning, underlined(11).single().item.color)
        assertEquals(0, underlined(0).size)
        assertEquals(text, styled.text)
    }

    @Test fun theProblemsListShowsLinesAndACountAndIsGoneWhenFixed() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-problems")
        try {
            val state = fixture(root, "# c\nGET {{base}}/get\n")
            setContent { AppRoot(state) }
            onNodeWithTag("problems").assertExists()
            onNodeWithText("1 error").assertExists()
            onNodeWithText("Line 2:", substring = true).assertExists()
            state.environment = "dev"
            waitForIdle()
            onNodeWithTag("problems").assertDoesNotExist()
        } finally { root.toFile().deleteRecursively() }
    }
}
