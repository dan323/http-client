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
        assertEquals(Placement(1280, 800, 10, 10, false), WindowGeometry.restore(WindowSettings(-5, 0, 10, 10), screens))
        assertEquals(Placement(1920, 1080, null, null, false), WindowGeometry.restore(WindowSettings(99999, 99999), screens))
        assertEquals(Placement(600, 400, 10, 10, false), WindowGeometry.restore(WindowSettings(10, 10, 10, 10), screens))
        // off screen: position dropped, size kept
        assertEquals(Placement(800, 600, null, null, true), WindowGeometry.restore(WindowSettings(800, 600, 5000, 5000, true), screens))
        assertEquals(Placement(800, 600, null, null, false), WindowGeometry.restore(WindowSettings(800, 600, -3000, 10), screens))
        // second monitor to the left is valid when attached
        val two = screens + Bounds(-1920, 0, 1920, 1080)
        assertEquals(Placement(800, 600, -1500, 100, false), WindowGeometry.restore(WindowSettings(800, 600, -1500, 100), two))
        // no display information: the position cannot be checked and is dropped, the size is kept
        assertEquals(Placement(800, 600, null, null, false), WindowGeometry.restore(WindowSettings(800, 600, 1, 1), emptyList()))
    }

    private fun fakeStore(onLoad: () -> LoadedSettings = { LoadedSettings(DesktopSettings()) }, onSave: (DesktopSettings) -> String? = { null }) =
        object : SettingsStore {
            override fun load() = onLoad()
            override fun save(settings: DesktopSettings) = onSave(settings)
        }

    @Test fun `a store that throws on load degrades to defaults with a warning`() {
        SettingsPersistence(fakeStore(onLoad = { throw IllegalStateException("boom") })).use { p ->
            assertEquals(DesktopSettings(), p.current)
            assertTrue(p.loadWarning!!.contains("boom"))
        }
        SettingsPersistence(fakeStore(onLoad = { throw IllegalStateException() })).use { p ->
            assertTrue(p.loadWarning!!.contains("IllegalStateException"))
        }
    }

    @Test fun `a store that throws on save is reported once and never propagates`() {
        val warnings = CopyOnWriteArrayList<String>()
        SettingsPersistence(fakeStore(onSave = { throw java.io.IOException() }), debounceMillis = 60_000, onWarning = { warnings += it }).use { p ->
            p.update { it.opened("a") }
            p.flush()
            p.update { it.opened("b") }
            p.flush()
        }
        assertEquals(listOf("IOException"), warnings)
    }

    @Test fun `unchanged settings are not saved and updates after close are ignored`() {
        val saves = CopyOnWriteArrayList<DesktopSettings>()
        val p = SettingsPersistence(fakeStore(onSave = { saves += it; null }), debounceMillis = 60_000)
        p.update { it }
        p.flush()
        assertTrue(saves.isEmpty())
        p.close()
        p.update { it.opened("late") }
        assertNull(p.current.lastFile)
        p.flush()
        assertTrue(saves.isEmpty())
    }

    @Test fun `the debounce eventually writes in the background`() {
        val saved = java.util.concurrent.CountDownLatch(1)
        val thread = java.util.concurrent.atomic.AtomicReference<String>()
        SettingsPersistence(fakeStore(onSave = { thread.set(Thread.currentThread().name); saved.countDown(); null }), debounceMillis = 10).use { p ->
            p.update { it.opened("a") }
            assertTrue(saved.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("settings-saver", thread.get())
        }
    }

    @Test fun `removing a recent file also forgets it as last file`() {
        val a = http("a.http")
        state().use { s ->
            s.open(a)
            val entry = s.recentFiles.single()
            Files.delete(a)
            s.openRecent(entry)
            assertTrue(s.recentFiles.isEmpty())
            assertNotNull(s.message)
        }
        state().use { it.restoreLastFile(); assertNull(it.file) }
    }

    @Test fun `changing the environment of no file is not remembered`() {
        state().use { s -> s.environment = "x"; assertEquals("x", s.environment) }
        assertNull(persistence().use { it.current.environments.values.firstOrNull() })
    }

    @Test fun `window geometry with a position partly on screen keeps it and zero sizes use defaults`() {
        val screens = listOf(Bounds(0, 0, 1000, 700))
        // 200 px of the window are visible on the right edge
        assertEquals(Placement(800, 600, 800, 50, false), WindowGeometry.restore(WindowSettings(800, 600, 800, 50), screens))
        // too little visible horizontally or vertically, or above the top edge
        assertNull(WindowGeometry.restore(WindowSettings(800, 600, 950, 50), screens).x)
        assertNull(WindowGeometry.restore(WindowSettings(800, 600, 10, 680), screens).x)
        assertNull(WindowGeometry.restore(WindowSettings(800, 600, 10, -200), screens).x)
        // x without y is no position
        assertNull(WindowGeometry.restore(WindowSettings(800, 600, 10, null), screens).x)
        // zero size falls back to the default but never exceeds a small screen
        assertEquals(Placement(1000, 700, null, null, false), WindowGeometry.restore(WindowSettings(0, 0), screens))
        // tiny screens still allow the minimum size
        assertEquals(600, WindowGeometry.restore(WindowSettings(100, 100), listOf(Bounds(0, 0, 300, 200))).width)
    }

    @Test fun `current screens match the display availability`() {
        val screens = WindowGeometry.currentScreens()
        if (java.awt.GraphicsEnvironment.isHeadless()) assertTrue(screens.isEmpty())
        else assertTrue(screens.isNotEmpty() && screens.all { it.width > 0 && it.height > 0 })
    }

    @Test fun `window tracker keeps the normal size while maximized and ignores empty sizes`() {
        val t = WindowTracker(Placement(1280, 800, null, null, false))
        assertEquals(WindowSettings(1000, 600, 5, 6, false), t.observe(1000, 600, 5, 6, false))
        assertEquals(WindowSettings(1000, 600, 5, 6, true), t.observe(1920, 1080, 0, 0, true))
        assertEquals(WindowSettings(1000, 600, 5, 6, false), t.observe(0, 0, 0, 0, false))
        assertEquals(WindowSettings(700, 500, null, null, false), t.observe(700, 500, null, null, false))
        // a minimized Windows frame sits at (-32000, -32000): not remembered, in either coordinate
        assertEquals(WindowSettings(700, 500, null, null, false), t.observe(160, 28, -32000, -32000, false))
        assertEquals(WindowSettings(700, 500, null, null, false), t.observe(800, 600, 10, -32000, false))
        assertEquals(WindowSettings(700, 500, null, null, false), t.observe(800, 600, -32000, 10, false))
    }

    @Test fun `run within reports whether the action finished in time`() {
        var ran = false
        assertTrue(runWithin(5000) { ran = true })
        assertTrue(ran)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            assertTrue(!runWithin(20) { release.await() })
        } finally { release.countDown() }
    }

    @Test fun `startup opens the argument over the remembered file and survives a failing store`() {
        val a = http("a.http")
        val b = http("b.http")
        val store = JsonSettingsStore(cfg)
        SettingsPersistence(store, debounceMillis = 20).use { it.update { s -> s.opened(canonical(a)) } }
        createAppState(emptyArray()) { store }.use { assertEquals(canonical(a), canonical(it.file!!)) }
        createAppState(arrayOf(b.toString())) { store }.use { assertEquals(canonical(b), canonical(it.file!!)) }
        createAppState(emptyArray()) { throw IllegalStateException("no store") }.use { assertNull(it.file) }
    }

    @Test fun `a save in flight cannot overwrite the final snapshot written by close`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val saved = CopyOnWriteArrayList<Int>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val store = fakeStore(onSave = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); gate.await() }
            saved += it.window!!.width
            null
        })
        val p = SettingsPersistence(store, debounceMillis = 1)
        p.update { it.withWindow(WindowSettings(1, 1)) }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))   // the background save of snapshot 1 is stuck
        p.update { it.withWindow(WindowSettings(2, 1)) }
        val closer = Thread { p.close() }.apply { start() }
        while (closer.state == Thread.State.RUNNABLE) Thread.onSpinWait()   // close() waits for the save in flight instead of racing it
        gate.countDown()
        closer.join(10_000)
        assertEquals(listOf(1, 2), saved.toList())   // the newest snapshot is written last
    }

    @Test fun `close gives up waiting for a stuck save after the bound`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val p = SettingsPersistence(fakeStore(onSave = { entered.countDown(); gate.await(); null }), debounceMillis = 1, closeWaitMillis = 20)
        try {
            p.update { it.opened("a") }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            p.update { it.opened("b") }
            val done = runWithin(10_000) { p.close() }
            assertTrue(done)   // returned although the save is still stuck
        } finally { gate.countDown() }
    }

    @Test fun `a fullscreen window is remembered as maximized`() {
        val t = WindowTracker(Placement(1280, 800, null, null, false))
        assertEquals(WindowSettings(1280, 800, null, null, true), t.observe(2560, 1440, 0, 0, true))
    }

    @Test fun `recent files missing right now are hidden but stay stored`() {
        val a = http("a.http")
        val b = http("b.http")
        state().use { it.open(a); it.open(b) }
        Files.move(a, root.resolve("moved.http"))   // e.g. an absent drive
        state().use { s ->
            assertEquals(listOf("b.http"), s.recentFiles.map { it.fileName.toString() })
            s.open(b)
        }
        Files.move(root.resolve("moved.http"), a)
        state().use { s -> assertEquals(listOf("b.http", "a.http"), s.recentFiles.map { it.fileName.toString() }) }
    }
}
