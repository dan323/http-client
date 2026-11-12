package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.WindowSettings
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonSettingsStoreTest {
    private val root = Files.createTempDirectory("httpc-settings")
    private val dir = root.resolve("cfg").resolve("http-client")   // does not exist yet

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun realFile(name: String): String = Files.writeString(root.resolve(name), "GET http://x\n").toString()
    private fun store() = JsonSettingsStore(dir)

    @Test fun `round trip creates the missing folder`() {
        val a = realFile("a.http")
        val s = DesktopSettings(a, mapOf(a to "dev"), WindowSettings(900, 700, 10, 20, true), listOf(a))
        assertNull(store().save(s))
        assertEquals(s, store().load().settings)
        assertNull(store().load().warning)
    }

    @Test fun `no file gives defaults without a warning`() {
        val l = store().load()
        assertEquals(DesktopSettings(), l.settings)
        assertNull(l.warning)
    }

    @Test fun `file is owner only on posix`() {
        store().save(DesktopSettings())
        val file = dir.resolve("settings.json")
        assumeTrue(file.fileSystem.supportedFileAttributeViews().contains("posix"))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
    }

    @Test fun `unknown fields are ignored`() {
        val a = realFile("a.http")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settings.json"), """{"version":1,"future":{"x":1},"lastFile":${q(a)},"window":{"width":5,"height":6,"extra":true}}""")
        val l = store().load()
        assertNull(l.warning)
        assertEquals(a, l.settings.lastFile)
        assertEquals(WindowSettings(5, 6), l.settings.window)
    }

    @Test fun `newer version is ignored and backed up on the next save`() {
        Files.createDirectories(dir)
        val original = """{"version":99,"lastFile":"x"}"""
        Files.writeString(dir.resolve("settings.json"), original)
        val st = store()
        val l = st.load()
        assertEquals(DesktopSettings(), l.settings)
        assertNotNull(l.warning)
        assertEquals(original, Files.readString(dir.resolve("settings.json")))   // not touched by load
        assertNull(st.save(DesktopSettings(recentFiles = emptyList())))
        assertEquals(original, Files.readString(dir.resolve("settings.json.corrupt")))
        assertNull(store().load().warning)
    }

    @Test fun `corrupt empty and foreign files fall back to defaults and each keeps its own backup`() {
        val contents = listOf("", "{not json", "[1,2]", """{"name":"other app"}""")
        for ((i, content) in contents.withIndex()) {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("settings.json"), content)
            val st = store()
            val l = st.load()
            assertEquals(DesktopSettings(), l.settings, "case $i")
            assertNotNull(l.warning, "case $i")
            assertNull(st.save(DesktopSettings()))
            assertTrue(Files.readString(dir.resolve("settings.json")).contains("\"version\""))
        }
        assertEquals(contents, backupNames(contents.size).map { Files.readString(dir.resolve(it)) })
    }

    private fun backupNames(count: Int) = listOf("settings.json.corrupt") + (1 until count).map { "settings.json.corrupt-$it" }

    private fun brokenSave(content: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settings.json"), content)
        val st = store()
        st.load()
        assertNull(st.save(DesktopSettings()))
    }

    @Test fun `a newer version file is kept even when a backup already exists`() {
        brokenSave("{broken")
        val newer = """{"version":99,"lastFile":"x"}"""
        brokenSave(newer)
        assertEquals("{broken", Files.readString(dir.resolve("settings.json.corrupt")))
        assertEquals(newer, Files.readString(dir.resolve("settings.json.corrupt-1")))
    }

    @Test fun `only the newest five backups are kept`() {
        (0..6).forEach { brokenSave("broken $it") }
        val kept = Files.list(dir).use { l -> l.map { it.fileName.toString() }.filter { it.startsWith("settings.json.corrupt") }.sorted().toList() }
        assertEquals(5, kept.size)
        val contents = kept.map { Files.readString(dir.resolve(it)) }.toSet()
        assertEquals((2..6).map { "broken $it" }.toSet(), contents)
    }

    @Test fun `a backup name taken by a folder is skipped and pruning failures are ignored`() {
        Files.createDirectories(dir.resolve("settings.json.corrupt").resolve("sub"))   // cannot be deleted while non empty
        (1..6).forEach { brokenSave("broken $it") }
        assertEquals("broken 6", Files.readString(dir.resolve("settings.json.corrupt-6")))
        assertTrue(Files.isDirectory(dir.resolve("settings.json.corrupt")))
        assertTrue(Files.readString(dir.resolve("settings.json")).contains("\"version\""))
    }

    @Test fun `a broken file that vanished before the save needs no backup`() {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settings.json"), "{broken")
        val st = store()
        st.load()
        Files.delete(dir.resolve("settings.json"))
        assertNull(st.save(DesktopSettings(recentFiles = listOf("a"))))
        assertFalse(Files.exists(dir.resolve("settings.json.corrupt")))
    }

    @Test fun `the record history choice round trips and an older file without it means off`() {
        assertNull(store().save(DesktopSettings(recordHistory = true)))
        assertTrue(store().load().settings.recordHistory)
        Files.writeString(dir.resolve("settings.json"), """{"version":1,"recentFiles":[]}""")
        val loaded = store().load()
        assertFalse(loaded.settings.recordHistory)
        assertNull(loaded.warning)
    }

    @Test fun `a transient read error never replaces the file and saving resumes after it clears`() {
        val a = realFile("a.http")
        val original = DesktopSettings(a, mapOf(a to "dev"), WindowSettings(900, 700, 10, 20), listOf(a))
        assertNull(store().save(original))
        var failing = true
        val st = JsonSettingsStore(dir, AtomicReportWriter()) { if (failing) throw java.io.IOException("locked") else Files.readString(it) }
        val l = st.load()
        assertEquals(DesktopSettings(), l.settings)
        assertTrue(l.warning!!.contains("locked"))
        val msg = st.save(DesktopSettings(window = WindowSettings(1, 2)))
        assertTrue(msg!!.contains("locked"))   // skipped and reported
        assertEquals(original, store().load().settings)   // nothing written
        failing = false
        assertNull(st.save(DesktopSettings(window = WindowSettings(1000, 800))))
        val merged = store().load().settings
        assertEquals(WindowSettings(1000, 800), merged.window)
        assertEquals(a, merged.lastFile)
        assertEquals(listOf(a), merged.recentFiles)
        assertEquals(mapOf(a to "dev"), merged.environments)
        assertFalse(Files.exists(dir.resolve("settings.json.corrupt")))   // not treated as corrupt
    }

    @Test fun `a file that turns out to be corrupt once readable is backed up`() {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settings.json"), "{broken")
        var failing = true
        val st = JsonSettingsStore(dir, AtomicReportWriter()) { if (failing) throw java.io.IOException("locked") else Files.readString(it) }
        st.load()
        failing = false
        assertNull(st.save(DesktopSettings(lastFile = "x")))
        assertEquals("{broken", Files.readString(dir.resolve("settings.json.corrupt")))
        assertEquals("x", store().load().settings.lastFile)
    }

    @Test fun `a newly created folder is owner only on posix`() {
        store().save(DesktopSettings())
        assumeTrue(dir.fileSystem.supportedFileAttributeViews().contains("posix"))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir))
    }

    @Test fun `environments are cut to the most recently used 50`() {
        val envs = (1..60).associate { "f$it" to "e" }
        assertNull(store().save(DesktopSettings(environments = envs)))
        assertEquals((11..60).map { "f$it" }, store().load().settings.environments.keys.toList())
        val touched = DesktopSettings(environments = (1..50).associate { "f$it" to "e" }).opened("f1").withEnvironment("new", "e")
        assertEquals(50, touched.environments.size)
        assertFalse("f2" in touched.environments)   // least recently used went first
        assertTrue("f1" in touched.environments)
        assertEquals(touched.environments, touched.opened("unknown").environments)
    }

    @Test fun `recents are bounded deduplicated most recent first and missing files are kept`() {
        var s = DesktopSettings()
        val files = (1..12).map { realFile("f$it.http") }
        files.forEach { s = s.opened(it) }
        s = s.opened(files[5])   // moves to the front, no duplicate
        assertEquals(10, s.recentFiles.size)
        assertEquals(files[5], s.recentFiles.first())
        assertEquals(1, s.recentFiles.count { it == files[5] })
        assertNull(store().save(s))
        Files.delete(Path.of(s.recentFiles[1]))   // e.g. an unplugged drive: still remembered
        assertEquals(s, store().load().settings)
    }

    @Test fun `last file that no longer exists is kept for the app to skip`() {
        val a = realFile("a.http")
        store().save(DesktopSettings(lastFile = a))
        Files.delete(Path.of(a))
        assertEquals(a, store().load().settings.lastFile)
    }

    @Test fun `unwritable location degrades to a message`() {
        val blocker = Files.writeString(root.resolve("blocker"), "x")
        val msg = JsonSettingsStore(blocker.resolve("http-client")).save(DesktopSettings())
        assertNotNull(msg)
        assertEquals(DesktopSettings(), JsonSettingsStore(blocker.resolve("http-client")).load().settings)
    }

    @Test fun `an unreadable file gives defaults with a warning and is backed up on save`() {
        Files.createDirectories(dir)
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())   // not valid UTF-8
        Files.write(dir.resolve("settings.json"), bytes)
        val st = store()
        val l = st.load()
        assertEquals(DesktopSettings(), l.settings)
        assertNotNull(l.warning)
        assertNull(st.save(DesktopSettings()))
        assertTrue(bytes.contentEquals(Files.readAllBytes(dir.resolve("settings.json.corrupt"))))
    }

    @Test fun `a writer that fails is reported as a message`() {
        val failing = AtomicReportWriter({ _, _ -> throw java.io.IOException() }, { a, b, o -> Files.move(a, b, *o) })
        val msg = JsonSettingsStore(dir, failing).save(DesktopSettings())
        assertEquals("Could not save settings: IOException", msg)
    }

    @Test fun `settings never contain more than paths, names, geometry and the record history flag`() {
        val a = realFile("a.http")
        store().save(DesktopSettings(a, mapOf(a to "dev"), WindowSettings(1, 2), listOf(a), openFiles = listOf(a), activeFile = a, folder = root.toString(), folderOpen = true))
        val keys = Regex("\"(\\w+)\"\\s*:").findAll(Files.readString(dir.resolve("settings.json"))).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("version", "lastFile", "environments", "window", "width", "height", "x", "y", "maximized", "recentFiles", "recordHistory", "openFiles", "activeFile", "folder", "folderOpen"), keys)
    }

    @Test fun `the open tabs round trip and a file without them means not known, not none`() {
        val a = realFile("a.http"); val b = realFile("b.http")
        assertNull(store().save(DesktopSettings(a, openFiles = listOf(a, b), activeFile = b)))
        val loaded = store().load().settings
        assertEquals(listOf(a, b), loaded.openFiles)
        assertEquals(b, loaded.activeFile)
        assertNull(store().save(DesktopSettings(a, openFiles = emptyList())))
        assertEquals(emptyList(), store().load().settings.openFiles)   // every tab closed is remembered as none
        Files.writeString(dir.resolve("settings.json"), """{"version":1,"lastFile":"x","recentFiles":[]}""")
        assertNull(store().load().settings.openFiles)                  // written before tabs: the last file is used instead
    }

    @Test fun `closing every tab wins over the stored tabs when an unreadable file becomes readable`() {
        val a = realFile("a.http")
        assertNull(store().save(DesktopSettings(a, openFiles = listOf(a), activeFile = a)))
        var failing = true
        val st = JsonSettingsStore(dir, AtomicReportWriter()) { if (failing) throw java.io.IOException("locked") else Files.readString(it) }
        st.load()
        failing = false
        assertNull(st.save(DesktopSettings(openFiles = emptyList())))
        assertEquals(emptyList(), store().load().settings.openFiles)
        assertNull(store().load().settings.activeFile)
        // an in-memory value that does not know the tabs keeps the stored ones
        assertNull(store().save(DesktopSettings(a, openFiles = listOf(a), activeFile = a)))
        failing = true
        val st2 = JsonSettingsStore(dir, AtomicReportWriter()) { if (failing) throw java.io.IOException("locked") else Files.readString(it) }
        st2.load()
        failing = false
        assertNull(st2.save(DesktopSettings(window = WindowSettings(1, 2))))
        assertEquals(listOf(a), store().load().settings.openFiles)
    }

    @Test fun `a hand edited file with too many tabs or an active file outside them is normalised on load`() {
        Files.createDirectories(dir)
        val files = (1..30).joinToString(",") { "\"f$it\"" }
        Files.writeString(dir.resolve("settings.json"), """{"version":1,"openFiles":[$files],"activeFile":"f25","recentFiles":[]}""")
        val loaded = store().load().settings
        assertEquals(DesktopSettings.MAX_OPEN_FILES, loaded.openFiles!!.size)
        assertNull(loaded.activeFile)
        Files.writeString(dir.resolve("settings.json"), """{"version":1,"openFiles":["a","b"],"activeFile":"z","recentFiles":[]}""")
        assertNull(store().load().settings.activeFile)
    }

    @Test fun `the folder view choice round trips and an older file means no folder and closed`() {
        assertNull(store().save(DesktopSettings(folder = root.toString(), folderOpen = true)))
        val loaded = store().load().settings
        assertEquals(root.toString(), loaded.folder)
        assertTrue(loaded.folderOpen)
        Files.writeString(dir.resolve("settings.json"), """{"version":1,"recentFiles":[]}""")
        val old = store().load().settings
        assertNull(old.folder)
        assertFalse(old.folderOpen)
    }

    @Test fun `the folder view choice of the running app wins over the stored one after an unreadable file`() {
        assertNull(store().save(DesktopSettings(folder = "/stored", folderOpen = true)))
        var failing = true
        val st = JsonSettingsStore(dir, AtomicReportWriter()) { if (failing) throw java.io.IOException("locked") else Files.readString(it) }
        st.load(); failing = false
        assertNull(st.save(DesktopSettings(folder = "/mine", folderOpen = false)))
        assertEquals("/mine", store().load().settings.folder)
        assertFalse(store().load().settings.folderOpen)
        failing = true
        val st2 = JsonSettingsStore(dir, AtomicReportWriter()) { if (failing) throw java.io.IOException("locked") else Files.readString(it) }
        st2.load(); failing = false
        assertNull(st2.save(DesktopSettings(window = WindowSettings(1, 2))))   // knows no folder: the stored one stays
        assertEquals("/mine", store().load().settings.folder)
    }

    @Test fun `no more than the limit of open files is stored`() {
        val many = (1..30).map { "f$it.http" }
        assertNull(store().save(DesktopSettings().withOpenFiles(many, "f25.http")))
        val loaded = store().load().settings
        assertEquals(many.take(DesktopSettings.MAX_OPEN_FILES), loaded.openFiles)
        assertNull(loaded.activeFile)   // the active one was cut off
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\") + "\""
}

class ConfigDirLocatorTest {
    private val home = Path.of("home-dir")

    private fun locate(os: String, env: Map<String, String> = emptyMap()) = ConfigDirLocator(os, env::get, home).locate()

    @Test fun linux() {
        assertEquals(home.resolve(".config").resolve("http-client"), locate("Linux"))
    }

    @Test fun `linux honours an absolute XDG_CONFIG_HOME only`() {
        val xdg = Path.of("xdg-cfg").toAbsolutePath()
        assertEquals(xdg.resolve("http-client"), locate("Linux", mapOf("XDG_CONFIG_HOME" to xdg.toString())))
        assertEquals(home.resolve(".config").resolve("http-client"), locate("Linux", mapOf("XDG_CONFIG_HOME" to "relative")))
        assertEquals(home.resolve(".config").resolve("http-client"), locate("Linux", mapOf("XDG_CONFIG_HOME" to " ")))
    }

    @Test fun macos() {
        assertEquals(home.resolve("Library").resolve("Application Support").resolve("http-client"), locate("Mac OS X"))
    }

    @Test fun windows() {
        val appData = Path.of("appdata-dir")
        assertEquals(appData.resolve("http-client"), locate("Windows 11", mapOf("APPDATA" to appData.toString())))
        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("http-client"), locate("Windows 11"))
    }
}

class ConfigDirLocatorDefaultsTest {
    @Test fun `the default locator gives an absolute app folder`() {
        val dir = ConfigDirLocator().locate()
        assertTrue(dir.isAbsolute || dir.startsWith("."))
        assertEquals(ConfigDirLocator.APP_DIR, dir.fileName.toString())
    }

    @Test fun `an unparsable APPDATA or XDG value falls back to the home folder`() {
        val home = Path.of("home-dir")
        val bad = mapOf("APPDATA" to "bad\u0000path", "XDG_CONFIG_HOME" to "bad\u0000path")
        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("http-client"), ConfigDirLocator("Windows 10", bad::get, home).locate())
        assertEquals(home.resolve(".config").resolve("http-client"), ConfigDirLocator("Linux", bad::get, home).locate())
    }
}
