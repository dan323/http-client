package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.JsonSettingsStore
import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.LoadedSettings
import com.dan323.httpclient.core.port.out.SettingsStore
import com.dan323.httpclient.core.port.out.WindowSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {
    private val root = Files.createTempDirectory("httpc-persist")
    private val cfg = root.resolve("cfg")

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun http(name: String, body: String = "GET http://localhost/x\n"): Path = Files.writeString(root.resolve(name), body)
    private fun persistence() = SettingsPersistence(JsonSettingsStore(cfg), debounceMillis = 20)
    private fun state(p: SettingsPersistence? = persistence()) = AppState(settings = p)

    @Test fun `file environment and recents survive a restart`() {
        val f = http("a.http")
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"x":"1"},"prod":{"x":"2"}}""")
        state().use { s ->
            s.open(f)
            s.environment = "prod"
        }   // close flushes
        state().use { s ->
            s.restoreLastFile()
            assertEquals(canonical(f), canonical(s.file!!))
            assertEquals("prod", s.environment)
            assertEquals(listOf(canonical(f)), s.recentFiles.map { canonical(it) })
            assertNull(s.message)
        }
    }

    @Test fun `a vanished environment is not restored`() {
        val f = http("a.http")
        val env = Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"x":"1"}}""")
        state().use { it.open(f); it.environment = "dev" }
        Files.writeString(env, """{"qa":{"x":"1"}}""")
        state().use { it.restoreLastFile(); assertNotNull(it.file); assertNull(it.environment) }
    }

    @Test fun `restoring a deleted file starts empty without a message`() {
        val f = http("a.http")
        state().use { it.open(f) }
        Files.delete(f)
        state().use { s ->
            s.restoreLastFile()
            assertNull(s.file)
            assertNull(s.message)
            assertTrue(s.recentFiles.isEmpty())
        }
    }

    @Test fun `opening a recent file that vanished drops it with a message`() {
        val a = http("a.http")
        val b = http("b.http")
        state().use { s ->
            s.open(a); s.open(b)
            assertEquals(listOf("b.http", "a.http"), s.recentFiles.map { it.fileName.toString() })
            Files.delete(a)
            s.openRecent(s.recentFiles.last())
            assertEquals(listOf("b.http"), s.recentFiles.map { it.fileName.toString() })
            assertNotNull(s.message)
            assertEquals("b.http", s.file!!.fileName.toString())
        }
    }

    @Test fun `window geometry is saved and restored`() {
        state().use { it.rememberWindow(WindowSettings(900, 700, 5, 6, true)) }
        state().use { assertEquals(WindowSettings(900, 700, 5, 6, true), it.savedWindow()) }
    }

    @Test fun `a corrupt settings file does not stop the app and is surfaced`() {
        Files.createDirectories(cfg)
        Files.writeString(cfg.resolve("settings.json"), "{broken")
        state().use { s ->
            assertNotNull(s.message)
            s.restoreLastFile()
            assertNull(s.file)
        }
        assertEquals("{broken", Files.readString(cfg.resolve("settings.json")))   // never overwritten silently
    }

    @Test fun `without persistence nothing is written`() {
        val f = http("a.http")
        AppState().use { it.open(f); it.environment = "x" }
        assertTrue(!Files.exists(cfg))
    }

    @Test fun `saves are debounced off the calling thread and a failing store never throws`() {
        val saves = CopyOnWriteArrayList<Pair<String, DesktopSettings>>()
        val warnings = CopyOnWriteArrayList<String>()
        val store = object : SettingsStore {
            override fun load() = LoadedSettings(DesktopSettings())
            override fun save(settings: DesktopSettings): String? { saves += Thread.currentThread().name to settings; return "disk full" }
        }
        SettingsPersistence(store, debounceMillis = 10_000, onWarning = { warnings += it }).use { p ->
            repeat(5) { i -> p.update { it.withWindow(WindowSettings(600 + i, 500)) } }
            assertTrue(saves.isEmpty())   // still waiting for the debounce
            p.flush()
            assertEquals(1, saves.size)
            assertEquals(604, saves[0].second.window!!.width)
            p.update { it.withWindow(WindowSettings(700, 500)) }
            p.flush()
            assertEquals(listOf("disk full"), warnings)   // reported once
        }
    }

    @Test fun `close flushes a pending change`() {
        val saves = CopyOnWriteArrayList<DesktopSettings>()
        val store = object : SettingsStore {
            override fun load() = LoadedSettings(DesktopSettings())
            override fun save(settings: DesktopSettings): String? { saves += settings; return null }
        }
        SettingsPersistence(store, debounceMillis = 60_000).use { it.update { s -> s.opened("p") } }
        assertEquals(1, saves.size)
    }

    @Test fun `geometry is clamped to the screens`() {
        val screens = listOf(Bounds(0, 0, 1920, 1080))
        assertEquals(Placement(1280, 800, null, null, false), WindowGeometry.restore(null, screens))
        assertEquals(Placement(1280, 800, null, null, false), WindowGeometry.restore(WindowSettings(-5, 0, 10, 10), screens).copy(x = null, y = null, maximized = false))
        assertEquals(Placement(1920, 1080, null, null, false), WindowGeometry.restore(WindowSettings(99999, 99999), screens))
        assertEquals(Placement(600, 400, 10, 10, false), WindowGeometry.restore(WindowSettings(10, 10, 10, 10), screens))
        // off screen: position dropped, size kept
        assertEquals(Placement(800, 600, null, null, true), WindowGeometry.restore(WindowSettings(800, 600, 5000, 5000, true), screens))
        assertEquals(Placement(800, 600, null, null, false), WindowGeometry.restore(WindowSettings(800, 600, -3000, 10), screens))
        // second monitor to the left is valid when attached
        val two = screens + Bounds(-1920, 0, 1920, 1080)
        assertEquals(Placement(800, 600, -1500, 100, false), WindowGeometry.restore(WindowSettings(800, 600, -1500, 100), two))
        // no display information: size sane, position dropped only when there is a screen list to check
        assertEquals(800, WindowGeometry.restore(WindowSettings(800, 600, 1, 1), emptyList()).width)
    }
}
