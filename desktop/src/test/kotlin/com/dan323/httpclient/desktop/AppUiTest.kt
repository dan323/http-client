package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.dan323.httpclient.mock.MockServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Drives the real composables headlessly: open a file, pick an environment, click Send. */
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    private fun fixture(server: MockServer): AppState {
        val dir = Files.createTempDirectory("httpc-ui")
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.port}"}}""")
        val file = dir.resolve("t.http")
        Files.writeString(
            file,
            "# @name login\nPOST {{base}}/post\nContent-Type: application/json\n\n{\"token\":\"abc\"}\n\n###\n" +
                "# @name me\nGET {{base}}/bearer\nAuthorization: Bearer {{login.response.body.$.json.token}}\n",
        )
        return AppState().also { it.open(file) }
    }

    @Test fun listsRequestsAndShowsFileName() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)
            setContent { AppRoot(state) }
            onNodeWithText("t.http").assertExists()
            onNodeWithText("login").assertExists()
            onNodeWithText("me").assertExists()
        } finally { server.stop() }
    }

    @Test fun sendRunsChainAndShowsResponse() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)
            state.environment = "dev"
            setContent { AppRoot(state) }
            onNodeWithText("me").performClick()          // select the second request
            assertEquals(1, state.selected)
            onNodeWithText("Send").performClick()
            waitUntil(timeoutMillis = 10_000) { state.outcomes.size == 2 && !state.running }
            waitForIdle()
            onNodeWithText("authenticated", substring = true).assertExists()
            assertEquals(listOf("login", "me"), state.outcomes.map { it.request.name })
        } finally { server.stop() }
    }

    @Test fun errorsAreShownInTheResponsePane() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)     // no environment selected -> {{base}} undefined
            setContent { AppRoot(state) }
            onNodeWithText("Send all").performClick()
            waitUntil(timeoutMillis = 10_000) { state.outcomes.isNotEmpty() && !state.running }
            waitForIdle()
            onAllNodes(hasText("Undefined variable: base", substring = true)).assertCountEquals(2) // one per request
        } finally { server.stop() }
    }

    @Test fun environmentPickerListsEnvironments() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)
            setContent { AppRoot(state) }
            onNodeWithText("Environment: none").performClick()
            onNodeWithText("dev").performClick()
            assertEquals("dev", state.environment)
        } finally { server.stop() }
    }

    @Test fun ctrlEnterSendsAndCtrlSSaves() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)
            state.environment = "dev"
            setContent { AppRoot(state) }
            state.edit(state.text + "\n")
            assertEquals(true, state.dirty)
            onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.S); keyUp(Key.CtrlLeft) }
            waitForIdle()
            assertEquals(false, state.dirty)
            onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft) }
            waitUntil(timeoutMillis = 10_000) { state.outcomes.isNotEmpty() && !state.running }
            assertEquals("login", state.outcomes.first().request.name)
        } finally { server.stop() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.sendAll(state: AppState) {
        state.environment = "dev"
        state.selected = 1
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 10_000) { state.outcomes.size == 2 && !state.running }
        waitForIdle()
    }

    @Test fun headersTabAndStatusBadge() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)
            setContent { AppRoot(state) }
            sendAll(state)
            onAllNodesWithText("200 OK").assertCountEquals(2)       // one badge per response
            onAllNodesWithText("Headers (", substring = true)[0].performClick()
            onAllNodesWithText("content-type", substring = true)[0].assertExists()
        } finally { server.stop() }
    }

    @Test fun jsonNodesCollapseAndRawViewShowsOriginalText() = runComposeUiTest {
        val server = MockServer(0).start()
        try {
            val state = fixture(server)
            setContent { AppRoot(state) }
            sendAll(state)
            onAllNodesWithText("\"authenticated\"", substring = true).assertCountEquals(1)
            onAllNodesWithText("▾", substring = true)[0].performClick()    // collapse the root object of the first response
            waitForIdle()
            onAllNodesWithText("{…}", substring = true).assertCountEquals(1)
            onAllNodesWithText("Raw")[1].performClick()                    // second response -> raw text
            waitForIdle()
            onNodeWithText("\"authenticated\": true", substring = true).assertExists()
        } finally { server.stop() }
    }
}
