package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.dan323.httpclient.adapters.FileHistoryStore
import com.dan323.httpclient.mock.MockServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The History panel and the Record history checkbox, driven through the real composables. */
@OptIn(ExperimentalTestApi::class)
class HistoryUiTest {
    private fun fixture(server: MockServer, root: java.nio.file.Path): AppState {
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.port}"}}""")
        val file = root.resolve("t.http")
        Files.writeString(file, "# @name ok\nGET {{base}}/get\n\n###\n# @name missing\nGET {{base}}/status/404\n")
        val history = FileHistoryStore(historyDirFor = { f -> root.resolve("store").resolve(f.fileName.toString()) }, onWarning = {})
        return AppState(runner = DesktopRunner(history = history), history = history).also { it.open(file); it.environment = "dev" }
    }

    @Test fun checkboxTurnsRecordingOnAndShowsTheWarning() = runComposeUiTest {
        val server = MockServer(0).start()
        val root = Files.createTempDirectory("httpc-hui")
        try {
            val state = fixture(server, root)
            setContent { AppRoot(state) }
            onNodeWithText("Stores raw responses", substring = true).assertDoesNotExist()
            onNodeWithTag("record-history").performClick()
            assertTrue(state.recordHistory)
            onNodeWithText("Stores raw responses in .http-history", substring = true).assertExists()
        } finally { server.stop(); root.toFile().deleteRecursively() }
    }

    @Test fun emptyHistoryExplainsHowToRecord() = runComposeUiTest {
        val server = MockServer(0).start()
        val root = Files.createTempDirectory("httpc-hui")
        try {
            val state = fixture(server, root)
            setContent { AppRoot(state) }
            onNodeWithTag("history-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.historyOpen }
            onNodeWithText("No recorded runs. Turn on Record history to keep them.").assertExists()
            onNodeWithTag("history-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { !state.historyOpen }
        } finally { server.stop(); root.toFile().deleteRecursively() }
    }

    @Test fun browseOpenMarkAndCompare() = runComposeUiTest {
        val server = MockServer(0).start()
        val root = Files.createTempDirectory("httpc-hui")
        try {
            val state = fixture(server, root)
            state.recordHistory = true
            state.run(all = true)
            setContent { AppRoot(state) }
            onNodeWithTag("history-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.historyView!!.runs.size == 1 }
            onNodeWithTag("history-run-0").performClick()
            waitForIdle()
            onNodeWithTag("history-entry-0").assertExists()
            onNodeWithTag("history-entry-0").performClick()
            waitUntil(timeoutMillis = 10_000) { state.historyView!!.opened != null }
            onNodeWithText("Mark as A").performClick()
            onNodeWithTag("history-compare").assertIsNotEnabled()
            onNodeWithTag("history-entry-1").performClick()
            waitUntil(timeoutMillis = 10_000) { state.historyView!!.opened?.response?.statusCode == 404 }
            onNodeWithText("Mark as B").performClick()
            onNodeWithTag("history-compare").assertIsEnabled().performClick()
            waitForIdle()
            onNodeWithText("A: 200  →  B: 404").assertExists()
            assertTrue(onAllNodesWithText("Back to history").fetchSemanticsNodes().isNotEmpty())
            onNodeWithText("Back to history").performClick()
            waitForIdle()
            assertEquals(null, state.historyView!!.diff)
        } finally { server.stop(); root.toFile().deleteRecursively() }
    }
}
