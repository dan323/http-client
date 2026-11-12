package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FolderViewTest {
    private val root = Files.createTempDirectory("httpc-folderview")
    private val cfg = root.resolve("cfg")
    private fun file(name: String, text: String = "GET http://127.0.0.1:1/x\n"): Path = root.resolve("ws").resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun androidx.compose.ui.test.ComposeUiTest.exists(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theTreeShowsTheRootThenOpensAFolderAndReloads() = runBlocking {
        file("a.http"); file("api/b.http")
        val tree = FolderTreeState(root.resolve("ws"))
        tree.load(tree.root)
        assertEquals(listOf("api", "a.http"), tree.rows().map { it.entry!!.name })
        tree.toggle(root.resolve("ws/api"))
        assertEquals(listOf("api", "b.http", "a.http"), tree.rows().map { it.entry!!.name })
        assertEquals(listOf(0, 1, 0), tree.rows().map { it.depth })
        file("c.http")
        tree.reload()                                       // new files appear, the opened folder stays open
        assertEquals(listOf("api", "b.http", "a.http", "c.http"), tree.rows().map { it.entry!!.name })
        tree.toggle(root.resolve("ws/api"))
        assertEquals(listOf("api", "a.http", "c.http"), tree.rows().map { it.entry!!.name })
    }

    @Test fun anEmptyOrUnreadableFolderSaysSo() = runBlocking {
        val empty = Files.createDirectories(root.resolve("empty"))
        val t1 = FolderTreeState(empty); t1.load(empty)
        assertEquals("(no .http files)", t1.rows().single().note)
        val t2 = FolderTreeState(root.resolve("nope")); t2.load(t2.root)
        assertTrue(t2.rows().single().note!!.startsWith("Could not read"))
    }

    @Test fun theFolderOfTheFileOnScreenIsTheDefaultRoot() {
        val a = file("a.http")
        AppState().use { app ->
            assertNull(app.folderRoot)
            app.open(a)
            assertEquals(a.toAbsolutePath().parent, app.folderRoot)
            val other = Files.createDirectories(root.resolve("other"))
            app.chooseFolder(other)
            assertEquals(other, app.folderRoot)
            assertTrue(app.folderOpen)
            app.showFolder(false)
            assertFalse(app.folderOpen)
        }
    }

    @Test fun thePanelOpensFilesAndFoldersInTheTree() = runComposeUiTest {
        val a = file("a.http"); file("api/b.http")
        val app = AppState()
        app.open(a)
        setContent { AppRoot(app) }
        assertFalse(exists("folder-tree"))
        onNodeWithTag("folder-toggle").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("folder-row-api") }
        onNodeWithTag("folder-row-api").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("folder-row-b.http") }
        onNodeWithTag("folder-row-b.http").performClick()
        waitForIdle()
        assertEquals(listOf("a.http", "b.http"), app.tabs.map { it.label })
        assertEquals("b.http", app.file!!.fileName.toString())
        onNodeWithTag("folder-close").performClick()
        waitForIdle()
        assertFalse(exists("folder-tree"))
        app.close()
    }

    @Test fun theFolderChoiceAndWhetherItIsShownComeBackAtTheNextStart() {
        val other = Files.createDirectories(root.resolve("other"))
        SettingsPersistence(com.dan323.httpclient.adapters.JsonSettingsStore(cfg), debounceMillis = 20).use { p ->
            AppState(settings = p).use { it.chooseFolder(other) }
        }
        SettingsPersistence(com.dan323.httpclient.adapters.JsonSettingsStore(cfg), debounceMillis = 20).use { p ->
            AppState(settings = p).use { app ->
                assertEquals(other.toAbsolutePath(), app.folderRoot)
                assertTrue(app.folderOpen)
                app.showFolder(false)
            }
        }
        SettingsPersistence(com.dan323.httpclient.adapters.JsonSettingsStore(cfg), debounceMillis = 20).use { p ->
            AppState(settings = p).use { assertFalse(it.folderOpen) }
        }
    }
}
