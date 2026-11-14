package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The environment editor panel and its wiring in [AppState], driven through the real composables. */
@OptIn(ExperimentalTestApi::class)
class EnvironmentEditorUiTest {
    private fun fixture(root: Path): AppState {
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
        val file = root.resolve("t.http")
        Files.writeString(file, "GET {{base}}/get\n")
        return AppState().also { it.open(file) }
    }

    @Test fun editorOpensEditsSavesAndRefreshesTheEnvironmentList() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-envui")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("env-edit-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor != null }
            onNodeWithTag("env-save").assertIsNotEnabled()
            onNodeWithTag("env-add").performClick()
            onNodeWithTag("env-name-1").performTextInput("prod")
            onNodeWithTag("env-save").performClick()
            waitUntil(timeoutMillis = 10_000) { state.environments == listOf("dev", "prod") }   // the list is read again after the save
            assertEquals("Saved the shared file.", state.envEditor?.status)
            assertEquals(listOf("dev", "prod"), state.environments)
            assertTrue(Files.readString(root.resolve("http-client.env.json")).contains("prod"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun privateValuesAreMaskedAndSavedInThePrivateFile() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-envui")
        try {
            val state = fixture(root)
            setContent { AppRoot(state) }
            onNodeWithTag("env-edit-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor != null }
            onNodeWithTag("env-add-var-0").performClick()
            onNodeWithTag("env-var-name-0-1").performTextInput("token")
            onNodeWithTag("env-var-value-0-1").performTextInput("s3cret")
            onNodeWithTag("env-save").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor?.status?.startsWith("Saved") == true }
            // The new variable is private by default, so it went to the private file and the shared one is untouched.
            assertTrue(Files.readString(root.resolve("http-client.private.env.json")).contains("s3cret"))
            assertTrue(!Files.readString(root.resolve("http-client.env.json")).contains("s3cret"))
            assertEquals(false, state.envEditor?.reveal)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun invalidDraftListsProblemsAndWritesNothing() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-envui")
        try {
            val state = fixture(root)
            val before = Files.readString(root.resolve("http-client.env.json"))
            setContent { AppRoot(state) }
            onNodeWithTag("env-edit-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor != null }
            onNodeWithTag("env-add").performClick()
            onNodeWithTag("env-name-1").performTextInput("dev")   // repeats the first environment
            onNodeWithTag("env-save").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor?.problems?.isNotEmpty() == true }
            assertEquals("Cannot save: 1 problem below.", state.envEditor?.status)
            assertEquals(before, Files.readString(root.resolve("http-client.env.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun closingDropsTheDraftAndAnotherFileClosesTheEditor() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-envui")
        try {
            val state = fixture(root)
            val other = root.resolve("u.http").also { Files.writeString(it, "GET http://x\n") }
            setContent { AppRoot(state) }
            onNodeWithTag("env-edit-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor != null }
            onNodeWithTag("env-add").performClick()
            onNodeWithText("Discard and close").performClick()
            assertNull(state.envEditor)
            state.showEnvironmentEditor(true)
            assertNotNull(state.envEditor)
            state.open(other)
            assertNull(state.envEditor)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unreadableFileIsReportedAndNothingCanBeEdited() = runComposeUiTest {
        val root = Files.createTempDirectory("httpc-envui")
        try {
            val state = fixture(root)
            Files.writeString(root.resolve("http-client.env.json"), "{not json")
            setContent { AppRoot(state) }
            onNodeWithTag("env-edit-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor?.loadError != null }
            onNodeWithTag("env-load-error").assertExists()
            onNodeWithTag("env-save").assertIsNotEnabled()
        } finally { root.toFile().deleteRecursively() }
    }
}
