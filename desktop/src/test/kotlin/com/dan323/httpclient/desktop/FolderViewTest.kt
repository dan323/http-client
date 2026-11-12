package com.dan323.httpclient.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.launch
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

    @Test fun theStateHolderListsOpensAndReloadsWithoutGoingBlank() = runBlocking {
        file("a.http"); file("api/b.http")
        val st = FolderTreeState(root.resolve("ws"))
        st.load()
        assertEquals(listOf("api", "a.http"), st.tree.rows().map { it.entry!!.name })
        st.toggle(root.resolve("ws/api"))
        assertEquals(listOf("api", "b.http", "a.http"), st.tree.rows().map { it.entry!!.name })
        file("c.http")
        st.reload()                                         // new files appear, the opened folder stays open
        assertEquals(listOf("api", "b.http", "a.http", "c.http"), st.tree.rows().map { it.entry!!.name })
        Files.delete(root.resolve("ws/api/b.http")); Files.delete(root.resolve("ws/api"))
        st.reload()                                         // a folder deleted outside the app is closed, not an error row for good
        assertEquals(listOf("a.http", "c.http"), st.tree.rows().map { it.entry!!.name })
    }

    @Test fun twoTogglesAtOnceEndUpAsTwoClicks() = runBlocking {
        file("api/b.http")
        val st = FolderTreeState(root.resolve("ws"))
        st.load()
        val api = root.resolve("ws/api")
        kotlinx.coroutines.coroutineScope {
            repeat(2) { launch(kotlinx.coroutines.Dispatchers.Default) { st.toggle(api) } }
        }
        assertFalse(st.tree.isExpanded(api))                // opened, then closed: never half-way
    }

    @Test fun aMissingRootSaysSo() = runBlocking {
        val st = FolderTreeState(root.resolve("nope"))
        st.load()
        assertEquals("Could not read this folder: the folder does not exist", st.tree.rows().single().note)
    }

    @Test fun theFolderOfTheFirstFileIsTheRootAndStaysWhileFilesInsideItAreShown() {
        val a = file("a.http"); val b = file("api/b.http"); val outside = root.resolve("elsewhere/z.http").also { Files.createDirectories(it.parent); Files.writeString(it, "GET http://x\n") }
        AppState().use { app ->
            assertNull(app.folderRoot)
            app.open(a)
            assertEquals(a.toAbsolutePath().parent, app.folderRoot)
            app.open(b)                                     // inside the root: the tree keeps its root
            assertEquals(a.toAbsolutePath().parent, app.folderRoot)
            app.switchTo(0)
            assertEquals(a.toAbsolutePath().parent, app.folderRoot)
            app.open(outside)                               // outside it: the root moves to that file's folder
            assertEquals(outside.toAbsolutePath().parent, app.folderRoot)
            val other = Files.createDirectories(root.resolve("other"))
            app.chooseFolder(other)
            assertEquals(other, app.folderRoot)
            assertTrue(app.folderOpen)
            app.followFile()                                // back to following the files
            assertNull(app.chosenFolder)
            assertEquals(outside.toAbsolutePath().parent, app.folderRoot)
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
        assertEquals(a.toAbsolutePath().parent, app.folderRoot)   // opening from the tree does not re-root it
        assertTrue(exists("folder-row-a.http") && exists("folder-row-b.http"))
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

@OptIn(ExperimentalTestApi::class)
class FolderViewSettingsTest {
    @Test fun aStoredFolderThatIsNotAValidPathIsIgnoredAtStart() {
        val store = object : com.dan323.httpclient.core.port.out.SettingsStore {
            override fun load() = com.dan323.httpclient.core.port.out.LoadedSettings(com.dan323.httpclient.core.port.out.DesktopSettings(folder = "bad\u0000path", folderOpen = true))
            override fun save(settings: com.dan323.httpclient.core.port.out.DesktopSettings): String? = null
        }
        SettingsPersistence(store).use { p -> AppState(settings = p).use { app -> assertNull(app.chosenFolder); assertTrue(app.folderOpen); assertTrue(app.message!!.contains("remembered folder")) } }
    }
}
