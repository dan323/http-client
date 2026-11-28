package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.port.out.Allowlists
import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.LoadedSettings
import com.dan323.httpclient.core.port.out.SettingsStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Settings panel: the allowlists it saves are remembered and used by the next run. */
@OptIn(ExperimentalTestApi::class)
class SettingsTest {
    private lateinit var server: HttpServer
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    private val dir = Files.createTempDirectory("httpc-settings")
    private val outside = Files.createTempDirectory("httpc-granted")

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val b = ex.requestBody.readAllBytes(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        Files.writeString(outside.resolve("body.txt"), "granted")
    }

    @AfterTest fun stop() {
        server.stop(0)
        dir.toFile().deleteRecursively(); outside.toFile().deleteRecursively()
    }

    /** Settings kept in memory, like the JSON store but without a file. */
    private class MemoryStore(var stored: DesktopSettings = DesktopSettings()) : SettingsStore {
        override fun load() = LoadedSettings(stored)
        override fun save(settings: DesktopSettings): String? { stored = settings; return null }
    }

    private fun file(): Path = dir.resolve("a.http").also {
        Files.writeString(it, "POST http://127.0.0.1:${server.address.port}/\n\n< ${outside.resolve("body.txt")}\n")
    }

    private fun runOnce(state: AppState): RunOutcome { state.run(all = true); return state.outcomes.single() }

    @Test fun aFolderOutsideIsRefusedWithAHintToTheSettingsUntilItIsAllowed() {
        AppState().use { state ->
            state.open(file())
            val refused = assertIs<RunOutcome.Failure>(runOnce(state))
            assertTrue("Settings… (Allowed folders)" in refused.message, refused.message)
            assertEquals(0, hits.get())
            state.showSettings(true)
            state.settingsDraft!!.rootsText = "  $outside  \n\n"
            assertTrue(state.settingsDraft!!.dirty)
            assertTrue(state.saveSettings())
            assertNull(state.settingsDraft)
            assertEquals(Allowlists(fileRoots = listOf(outside.toString())), state.allowlists)
            assertEquals("granted", assertIs<RunOutcome.Success>(runOnce(state)).response.bodyAsString())
        }
    }

    @Test fun anInvalidEntryKeepsThePanelOpenWithItsErrorAndSavesNothing() {
        val store = MemoryStore()
        AppState(settings = SettingsPersistence(store, debounceMillis = 0)).use { state ->
            state.showSettings(true)
            state.settingsDraft!!.envText = "GOOD\nBAD NAME"
            state.settingsDraft!!.rootsText = "relative/dir"
            assertFalse(state.saveSettings())
            val errors = state.settingsDraft!!.errors
            assertEquals(2, errors.size)
            assertTrue("'BAD NAME'" in errors[0] && "'relative/dir' must be an absolute path" in errors[1], errors.toString())
            assertEquals(Allowlists(), state.allowlists)
            state.showSettings(false)   // discards
            assertNull(state.settingsDraft)
        }
        assertNull(store.stored.allowlists)
    }

    @Test fun savedListsAreRememberedAndUsedAtTheNextStart() {
        val store = MemoryStore()
        AppState(settings = SettingsPersistence(store, debounceMillis = 0)).use { state ->
            state.showSettings(true)
            state.settingsDraft!!.envText = "TOKEN\nTOKEN\n"
            state.settingsDraft!!.rootsText = outside.toString()
            assertTrue(state.saveSettings())
            assertEquals(SETTINGS_SAVED_NOTICE, state.notice)
        }
        assertEquals(Allowlists(listOf("TOKEN"), listOf(outside.toString())), store.stored.allowlists)
        AppState(settings = SettingsPersistence(store, debounceMillis = 0)).use { state ->
            assertEquals(listOf("TOKEN"), state.allowlists.envNames)
            state.open(file())
            assertEquals("granted", assertIs<RunOutcome.Success>(runOnce(state)).response.bodyAsString())
            state.showSettings(true)
            assertEquals("TOKEN", state.settingsDraft!!.envText)
            assertFalse(state.settingsDraft!!.dirty)
        }
    }

    @Test fun revokingAFolderAppliesToTheNextRunAndSavingNothingNewKeepsTheChain() {
        val store = MemoryStore(DesktopSettings(allowlists = Allowlists(fileRoots = listOf(outside.toString()))))
        AppState(settings = SettingsPersistence(store, debounceMillis = 0)).use { state ->
            state.open(file())
            assertIs<RunOutcome.Success>(runOnce(state))
            state.showSettings(true)
            assertTrue(state.saveSettings())   // unchanged: no notice, nothing forgotten
            assertNull(state.notice)
            state.showSettings(true)
            state.settingsDraft!!.rootsText = ""
            assertTrue(state.saveSettings())
            assertIs<RunOutcome.Failure>(runOnce(state))
        }
        assertEquals(Allowlists(), store.stored.allowlists)
    }

    @Test fun anEnvironmentEditorStillLoadingWhenSettingsOpenIsDroppedAndTheFormsNeverStack() {
        val blocking = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stores = com.dan323.httpclient.core.port.out.EnvironmentFileStoreLocator { f ->
            if (blocking.get()) { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            com.dan323.httpclient.adapters.UpwardEnvironmentLocator().storeFor(f)
        }
        AppState(environmentStores = stores).use { state ->
            state.open(file())
            blocking.set(true)
            val loading = Thread { state.showEnvironmentEditor(true) }.also { it.start() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            state.showSettings(true)
            release.countDown(); loading.join(10_000)
            assertNull(state.envEditor)                    // the late load did not open behind the panel
            assertTrue(state.settingsDraft != null)
            blocking.set(false)
            state.showEnvironmentEditor(true)              // refused while Settings is open
            assertNull(state.envEditor)
            state.showSettings(false)
            state.showEnvironmentEditor(true)
            assertTrue(state.envEditor != null)
            state.showSettings(true)                       // refused while the editor is open: its edits stay
            assertNull(state.settingsDraft)
        }
    }

    @Test fun savingWhileARequestRunsLetsItFinishButTheNextRunUsesTheNewLists() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/slow") { ex ->
            entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val b = "slow".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        val store = MemoryStore(DesktopSettings(allowlists = Allowlists(fileRoots = listOf(outside.toString()))))
        val f = dir.resolve("b.http").also {
            Files.writeString(it, "GET http://127.0.0.1:${server.address.port}/slow\n\n###\nPOST http://127.0.0.1:${server.address.port}/\n\n< ${outside.resolve("body.txt")}\n")
        }
        AppState(settings = SettingsPersistence(store, debounceMillis = 0)).use { state ->
            state.open(f)
            val running = Thread { state.run(all = true) }.also { it.start() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            state.showSettings(true)
            state.settingsDraft!!.rootsText = ""          // revoke while the first request is in flight
            assertTrue(state.saveSettings())
            release.countDown(); running.join(10_000)
            state.selected = 1
            state.run(all = false)
            assertIs<RunOutcome.Failure>(state.outcomes.last())   // the session of the run in flight was not reused
        }
    }

    @Test fun thePanelIsOpenedEditedAndSavedThroughTheUi() = runComposeUiTest {
        val state = AppState()
        try {
            setContent { AppRoot(state) }
            onNodeWithTag("settings-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.settingsDraft != null }
            onNodeWithTag("settings-env").performTextReplacement("API_TOKEN")
            onNodeWithTag("settings-roots").performTextReplacement("not/absolute")
            onNodeWithTag("settings-save").performClick()
            waitForIdle()
            onNodeWithTag("settings-error").assertExists()
            onNodeWithTag("settings-roots").performTextReplacement(outside.toString())
            onNodeWithTag("settings-save").performClick()
            waitUntil(timeoutMillis = 10_000) { state.settingsDraft == null }
            assertEquals(Allowlists(listOf("API_TOKEN"), listOf(outside.toString())), state.allowlists)
        } finally { state.close() }
    }

    @Test fun theSettingsAndTheEnvironmentEditorNeverHideEachOther() = runComposeUiTest {
        val state = AppState().also { it.open(file()) }
        try {
            setContent { AppRoot(state) }
            onNodeWithTag("settings-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.settingsDraft != null }
            onNodeWithTag("env-edit-toggle").assertIsNotEnabled()
            onNodeWithTag("settings-env").performTextReplacement("X")
            onNodeWithText("Discard settings").assertExists()
            onNodeWithTag("settings-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.settingsDraft == null }
            assertEquals(Allowlists(), state.allowlists)   // discarded
            onNodeWithTag("env-edit-toggle").performClick()
            waitUntil(timeoutMillis = 10_000) { state.envEditor != null }
            onNodeWithTag("settings-toggle").assertIsNotEnabled()
        } finally { state.close() }
    }
}
