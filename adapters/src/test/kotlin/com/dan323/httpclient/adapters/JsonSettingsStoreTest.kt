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

    @Test fun `corrupt empty and foreign files fall back to defaults and keep the first backup`() {
        for ((i, content) in listOf("", "{not json", "[1,2]", """{"name":"other app"}""").withIndex()) {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("settings.json"), content)
            val st = store()
            val l = st.load()
            assertEquals(DesktopSettings(), l.settings, "case $i")
            assertNotNull(l.warning, "case $i")
            assertNull(st.save(DesktopSettings()))
            assertTrue(Files.readString(dir.resolve("settings.json")).contains("\"version\""))
            assertEquals("", Files.readString(dir.resolve("settings.json.corrupt")), "the first backup is kept, case $i")
        }
    }

    @Test fun `recents are bounded deduplicated most recent first and missing files dropped on load`() {
        var s = DesktopSettings()
        val files = (1..12).map { realFile("f$it.http") }
        files.forEach { s = s.opened(it) }
        s = s.opened(files[5])   // moves to the front, no duplicate
        assertEquals(10, s.recentFiles.size)
        assertEquals(files[5], s.recentFiles.first())
        assertEquals(1, s.recentFiles.count { it == files[5] })
        assertNull(store().save(s.copy(recentFiles = s.recentFiles + root.resolve("gone.http").toString())))
        Files.delete(Path.of(s.recentFiles[1]))
        val loaded = store().load().settings
        assertEquals(s.recentFiles.filter { it != s.recentFiles[1] }, loaded.recentFiles)
        assertFalse(loaded.recentFiles.any { it.endsWith("gone.http") })
    }

    @Test fun `last file that no longer exists is dropped`() {
        val a = realFile("a.http")
        store().save(DesktopSettings(lastFile = a))
        Files.delete(Path.of(a))
        assertNull(store().load().settings.lastFile)
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

    @Test fun `a failing backup is reported by save and the broken file is left alone`() {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settings.json"), "{broken")
        Files.createDirectories(dir.resolve("settings.json.corrupt").resolve("sub"))   // backup name taken by a non empty folder
        val st = store()
        st.load()
        assertNull(st.save(DesktopSettings()))   // an existing backup is never overwritten, the save still goes ahead
        assertTrue(Files.readString(dir.resolve("settings.json")).contains("\"version\""))
    }

    @Test fun `a writer that fails is reported as a message`() {
        val failing = AtomicReportWriter({ _, _ -> throw java.io.IOException() }, { a, b, o -> Files.move(a, b, *o) })
        val msg = JsonSettingsStore(dir, failing).save(DesktopSettings())
        assertEquals("Could not save settings: IOException", msg)
    }

    @Test fun `settings never contain more than paths names and geometry`() {
        val a = realFile("a.http")
        store().save(DesktopSettings(a, mapOf(a to "dev"), WindowSettings(1, 2), listOf(a)))
        val keys = Regex("\"(\\w+)\"\\s*:").findAll(Files.readString(dir.resolve("settings.json"))).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("version", "lastFile", "environments", "window", "width", "height", "x", "y", "maximized", "recentFiles"), keys)
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
