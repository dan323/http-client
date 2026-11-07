package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.ResponseLinks
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalResponseSaverTest {
    private val tmp: Path = Files.createTempDirectory("saver").toRealPath()
    private val dir: Path = Files.createDirectory(tmp.resolve("proj"))
    private val outside: Path = Files.createDirectory(tmp.resolve("other"))
    private val http: Path = dir.resolve("api.http").also { Files.writeString(it, "GET http://x\n") }
    private val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun saver(vararg roots: Path) = LocalResponseSaver(http, FileAccessPolicy(roots.toList()))
    private val body = byteArrayOf(0, -1, 10, 13, 65, -128)

    private fun symlink(link: Path, target: Path) {
        try { Files.createSymbolicLink(link, target) } catch (e: Exception) {
            assumeFalse(File.separatorChar == '\\', "symlinks unavailable: $e")
            throw AssertionError(e)
        }
    }

    @Test fun `writes the exact bytes and creates folders inside the root`() {
        assertEquals("out/deep/a.bin", saver().save("out/deep/a.bin", body, false))
        assertContentEquals(body, Files.readAllBytes(dir.resolve("out/deep/a.bin")))
        assertEquals(listOf("a.bin"), Files.list(dir.resolve("out/deep")).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test fun `empty body and path with spaces and backslashes`() {
        assertEquals("my dir/e.txt", saver().save("my dir\\e.txt", ByteArray(0), false))
        assertEquals(0, Files.size(dir.resolve("my dir/e.txt")))
    }

    @Test fun `without overwrite a numeric suffix goes before the extension`() {
        val s = saver()
        assertEquals("r.json", s.save("r.json", "1".toByteArray(), false))
        assertEquals("r-1.json", s.save("r.json", "2".toByteArray(), false))
        assertEquals("r-2.json", s.save("r.json", "3".toByteArray(), false))
        assertEquals("1", Files.readString(dir.resolve("r.json")))
        assertEquals("2", Files.readString(dir.resolve("r-1.json")))
        assertEquals("noext", s.save("noext", ByteArray(1), false))
        assertEquals("noext-1", s.save("noext", ByteArray(1), false))
        assertEquals(".hid", s.save(".hid", ByteArray(1), false))
        assertEquals(".hid-1", s.save(".hid", ByteArray(1), false))
    }

    @Test fun `overwrite replaces and leaves no temp files`() {
        val s = saver()
        s.save("r.json", "old".toByteArray(), true)
        assertEquals("r.json", s.save("r.json", "new".toByteArray(), true))
        assertEquals("new", Files.readString(dir.resolve("r.json")))
        assertFalse(Files.list(dir).use { st -> st.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test fun `concurrent saves of the same name end in different files`() {
        val s = saver()
        val pool = Executors.newFixedThreadPool(8)
        try {
            (0 until 16).map { i -> pool.submit<String> { s.save("c.txt", "v$i".toByteArray(), false) } }.map { it.get() }
        } finally { pool.shutdownNow() }
        val names = Files.list(dir).use { st -> st.map { it.fileName.toString() }.filter { it.startsWith("c") }.toList() }
        assertEquals(16, names.size)
        assertEquals((0 until 16).map { "v$it" }.toSet(), names.map { Files.readString(dir.resolve(it)) }.toSet())
    }

    @Test fun `rejects escapes and absolute paths`() {
        for (p in listOf("../x.txt", "a/../../x.txt", "/abs.txt", "\\abs.txt", "C:\\abs.txt", "c:abs.txt", "..\\x.txt")) {
            val e = assertFailsWith<IOException>(p) { saver().save(p, body, true) }
            assertTrue(p.trim() in e.message!!, e.message)
        }
        assertFalse(Files.exists(tmp.resolve("x.txt")))
    }

    @Test fun `empty path and folder targets are refused`() {
        assertFailsWith<IOException> { saver().save("  ", body, false) }
        assertFailsWith<IOException> { saver().save("sub/", body, false) }
        Files.createDirectory(dir.resolve("d"))
        assertFailsWith<IOException> { saver().save("d", body, true) }
        assertFailsWith<IOException> { saver().save("d", body, false) }
    }

    @Test fun `the http file itself is protected`() {
        assertFailsWith<IOException> { saver().save("api.http", body, true) }
        assertFailsWith<IOException> { saver().save("./sub/../api.http", body, true) }
        assertEquals("GET http://x\n", Files.readString(http))
    }

    @Test fun `allowed extra root is accepted, others are not`() {
        assertEquals("../other/ok.txt", saver(outside).save("../other/ok.txt", body, false))
        assertTrue(Files.exists(outside.resolve("ok.txt")))
        assertFailsWith<FileAccessDeniedException> { saver().save("../other/no.txt", body, false) }
        assertFalse(Files.exists(outside.resolve("no.txt")))
    }

    @Test fun `symlinks pointing out are refused, also when final`() {
        symlink(dir.resolve("link"), outside)
        assertFailsWith<FileAccessDeniedException> { saver().save("link/x.txt", body, true) }
        assertFailsWith<FileAccessDeniedException> { saver().save("link/new/x.txt", body, true) }
        Files.writeString(outside.resolve("t.txt"), "keep")
        symlink(dir.resolve("flink"), outside.resolve("t.txt"))
        assertFailsWith<FileAccessDeniedException> { saver().save("flink", body, true) }
        assertFailsWith<FileAccessDeniedException> { saver().save("flink", body, false) }
        symlink(dir.resolve("dangling"), outside.resolve("not-yet.txt"))
        assertFailsWith<FileAccessDeniedException> { saver().save("dangling", body, true) }
        assertEquals("keep", Files.readString(outside.resolve("t.txt")))
        assertFalse(Files.exists(outside.resolve("not-yet.txt")))
        assertFalse(Files.exists(outside.resolve("x.txt")))
        assertFalse(Files.exists(outside.resolve("new")))
    }

    @Test fun `a symlink inside the root pointing inside is fine as a folder`() {
        Files.createDirectory(dir.resolve("real"))
        symlink(dir.resolve("alias"), dir.resolve("real"))
        assertEquals("alias/x.txt", saver().save("alias/x.txt", body, true))
        assertTrue(Files.exists(dir.resolve("real/x.txt")))
    }

    @Test fun `new files are owner only and overwritten ones keep their permissions`() {
        assumeTrue(posix)
        val s = saver()
        s.save("p.txt", body, false)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("p.txt"))))
        Files.setPosixFilePermissions(dir.resolve("p.txt"), PosixFilePermissions.fromString("rw-r-----"))
        s.save("p.txt", body, true)
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("p.txt"))))
    }

    @Test fun `overwriting a read-only file is refused and keeps it`() {
        assumeTrue(posix)
        val f = dir.resolve("ro.txt")
        Files.writeString(f, "keep")
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--------"))
        assumeFalse(Files.isWritable(f), "running as a user that ignores permissions")
        val e = assertFailsWith<IOException> { saver().save("ro.txt", body, true) }
        assertTrue("read-only" in e.message!!)
        assertEquals("keep", Files.readString(f))
        // without overwrite the file is not touched either: a suffixed copy is written
        assertEquals("ro-1.txt", saver().save("ro.txt", body, false))
    }

    @Test fun `names that cannot exist on Windows are refused on every platform`() {
        val bad = listOf(
            "CON", "con.txt", "out/NUL", "nul.json", "PRN", "aux", "COM1", "com9.log", "LPT1", "lpt3.txt", "a/../CON",
            "trail.", "dir /x.txt", "out/name.", "file.txt:stream", "a<b", "a>b", "a\"b", "a|b", "a?b", "a*b", "tab\there",
        )
        for (p in bad) {
            val e = assertFailsWith<IOException>(p) { saver().save(p, body, true) }
            assertTrue("invalid file path" in e.message!!, "$p: ${e.message}")
            assertFailsWith<IOException>(p) { saver().save(p, body, false) }
        }
        // look-alikes that are fine
        for (p in listOf("console.txt", "COM10.txt", "nulled", "a.b.c", "x/.hidden", "con-sole/x")) assertEquals(p, saver().save(p, body, true))
    }

    @Test fun `a NUL character in the path is refused`() {
        val e = assertFailsWith<IOException> { saver().save("a\u0000b.txt", body, true) }
        assertTrue("invalid file path" in e.message!!)
    }

    @Test fun `all suffixes taken ends with a clear error and no temp file`() {
        val s = LocalResponseSaver(http, FileAccessPolicy(), maxSuffix = 3)
        assertEquals("r.json", s.save("r.json", body, false))
        for (n in 1..3) assertEquals("r-$n.json", s.save("r.json", body, false))
        val e = assertFailsWith<IOException> { s.save("r.json", body, false) }
        assertTrue("no free file name" in e.message!! && "r.json" in e.message!!, e.message)
        assertEquals(setOf("api.http", "r.json", "r-1.json", "r-2.json", "r-3.json"), Files.list(dir).use { st -> st.map { it.fileName.toString() }.toList().toSet() })
    }

    private fun names() = Files.list(dir).use { st -> st.map { it.fileName.toString() }.toList().toSet() }

    @Test fun `a failed publish leaves neither a reservation nor a temp file`() {
        val failing = LocalResponseSaver(http, FileAccessPolicy(), move = { _, _ -> throw IOException("disk full") }, link = { _, _ -> throw IOException("disk full") })
        assertFailsWith<IOException> { failing.save("m.json", body, false) }
        assertFailsWith<IOException> { failing.save("m.json", body, true) }
        assertEquals(setOf("api.http"), names())
        assertEquals("m.json", saver().save("m.json", body, false)) // the name was not left reserved
    }

    @Test fun `without hard links a plain move still never replaces an existing name`() {
        val noLinks = LocalResponseSaver(http, FileAccessPolicy(), link = { _, _ -> throw UnsupportedOperationException("no links") })
        assertEquals("n.txt", noLinks.save("n.txt", "one".toByteArray(), false))
        assertEquals("n-1.txt", noLinks.save("n.txt", "two".toByteArray(), false))
        assertEquals("one", Files.readString(dir.resolve("n.txt")))
        val fsErr = LocalResponseSaver(http, FileAccessPolicy(), link = { _, _ -> throw java.nio.file.FileSystemException("x", null, "not supported") })
        assertEquals("n-2.txt", fsErr.save("n.txt", "three".toByteArray(), false))
        assertEquals("three", Files.readString(dir.resolve("n-2.txt")))
        assertEquals(setOf("api.http", "n.txt", "n-1.txt", "n-2.txt"), names())
    }

    @Test fun `no empty or partial file is visible at the final name while publishing`() {
        var seen: Long? = null
        val probing = LocalResponseSaver(http, FileAccessPolicy(), link = { l, existing ->
            seen = if (Files.exists(l)) Files.size(l) else -1L // before the link nothing exists at the final name
            Files.createLink(l, existing)
        })
        probing.save("p.bin", body, false)
        assertEquals(-1L, seen)
        assertContentEquals(body, Files.readAllBytes(dir.resolve("p.bin")))
    }

    @Test fun `leading or trailing whitespace is refused as given, not trimmed`() {
        for (p in listOf(" a.txt", "a.txt ", "a.txt\t", "\na.txt", "sub/a.txt\n")) {
            val e = assertFailsWith<IOException>(p) { saver().save(p, body, true) }
            assertTrue("whitespace" in e.message!!, e.message)
        }
        assertEquals("empty file path", assertFailsWith<IOException> { saver().save("  ", body, true) }.message)
        assertEquals(setOf("api.http"), names())
    }

    @Test fun `a folder swapped for a symlink before publishing never touches an outside file`() {
        assumeFalse(File.separatorChar == '\\')
        val victim = Files.writeString(outside.resolve("x.txt"), "precious")
        for (overwrite in listOf(true, false)) {
            val sub = dir.resolve("sub$overwrite")
            val swap = { _: Path, _: Path ->
                Files.move(sub, dir.resolve("moved$overwrite"))
                symlink(sub, outside)
            }
            val swapping = LocalResponseSaver(http, FileAccessPolicy(),
                move = { a, b -> swap(a, b); Files.move(a, b, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) },
                link = { l, e -> swap(l, e); Files.createLink(l, e) })
            assertFailsWith<IOException> { swapping.save("sub$overwrite/x.txt", body, overwrite) }
            assertEquals("precious", Files.readString(victim), "overwrite=$overwrite")
            assertEquals(listOf("x.txt"), Files.list(outside).use { st -> st.map { it.fileName.toString() }.toList() })
        }
    }

    @Test fun `a folder swapped for a symlink before the last check is refused`() {
        assumeFalse(File.separatorChar == '\\')
        val victim = Files.writeString(outside.resolve("y.txt"), "precious")
        val sub = dir.resolve("late")
        Files.createDirectory(sub)
        val saver = LocalResponseSaver(http, FileAccessPolicy(), afterWrite = {
            Files.move(sub, dir.resolve("late-moved"))
            symlink(sub, outside)
        })
        val e = assertFailsWith<FileAccessDeniedException> { saver.save("late/y.txt", body, true) }
        assertTrue("nothing was written" in e.message!!, e.message)
        assertEquals("precious", Files.readString(victim))
        assertEquals(listOf("y.txt"), Files.list(outside).use { st -> st.map { it.fileName.toString() }.toList() })
    }

    @Test fun `a file that ended up outside the roots is reported, a new one is removed`() {
        // a stronger attacker who also smuggles the temp file along; simulated inside the publish step
        val swapping = LocalResponseSaver(http, FileAccessPolicy(),
            move = { tmp, target -> smuggle(tmp, target.parent); Files.move(tmp, target) },
            link = { l, e -> smuggle(e, l.parent); Files.createLink(l, e) })
        for (overwrite in listOf(true, false)) {
            val e = assertFailsWith<FileAccessDeniedException> { swapping.save("sub$overwrite/x.txt", body, overwrite) }
            assertTrue("changed while being saved" in e.message!!, e.message)
            assertEquals(!overwrite, "removed" in e.message!!, e.message)
            Files.deleteIfExists(outside.resolve("x.txt"))
            Files.delete(dir.resolve("sub$overwrite"))
        }
    }

    /** Replaces folder [sub] by a symlink to `outside` and carries [file] (by name) over, so a rename by name works there. */
    private fun smuggle(file: Path, sub: Path) {
        Files.copy(file, outside.resolve(file.fileName))
        val moved = dir.resolve("held-${sub.fileName}")
        Files.move(sub, moved)
        symlink(sub, outside)
    }

    @Test fun `a hard link to the http file is protected`() {
        val link = dir.resolve("hard.http")
        try { Files.createLink(link, http) } catch (e: Exception) {
            assumeFalse(true, "hard links unavailable: $e")
        }
        val e = assertFailsWith<IOException> { saver().save("hard.http", body, true) }
        assertTrue("the .http file itself" in e.message!!)
        assertEquals("GET http://x\n", Files.readString(http))
    }

    @Test fun `a case alias of the http file is protected where the file system ignores case`() {
        assumeTrue(Files.exists(dir.resolve("API.HTTP")), "case-sensitive file system")
        assertFailsWith<IOException> { saver().save("API.HTTP", body, true) }
        assertEquals("GET http://x\n", Files.readString(http))
    }

    @Test fun `a dangling symlink as parent folder pointing out creates nothing outside`() {
        symlink(dir.resolve("dlink"), outside.resolve("missing-dir"))
        assertFailsWith<FileAccessDeniedException> { saver().save("dlink/x.txt", body, true) }
        assertFailsWith<FileAccessDeniedException> { saver().save("dlink/sub/x.txt", body, false) }
        assertFalse(Files.exists(outside.resolve("missing-dir")))
    }

    @Test fun `end to end through SessionFactory the failed save leaves the result and leaks nothing`() {
        val secretBody = "SECRETBODY".toByteArray()
        val executor = HttpExecutor { HttpResponse(200, emptyList(), secretBody, 1) }
        val text = "GET http://h/a\n\n>> out/{{n}}.json\n###\n# @name second\nGET http://h/b\n\n>> ../escape.json\n"
        Files.writeString(http, text)
        SessionFactory(executor).use { f ->
            val out = f.create(http, FileAccessPolicy()).runAll(HttpFileParser().parse(text).requests, mapOf("n" to "one"))
            val a = assertIs<RunOutcome.Success>(out[0])
            assertEquals("out/{{n}}.json", a.savedTo) // substituted values are not shown, like in error messages
            assertContentEquals(secretBody, Files.readAllBytes(dir.resolve("out/one.json")))
            val b = assertIs<RunOutcome.Success>(out[1])
            assertEquals(200, b.response.statusCode)
            assertNull(b.savedTo)
            assertTrue("../escape.json" in b.saveError!!)
            assertFalse("SECRETBODY" in b.saveError!!)
            assertFalse(Files.exists(tmp.resolve("escape.json")))
        }
    }

    @Test fun `links are kept in the history only and the http file is never edited`() {
        var n = 0
        val executor = HttpExecutor { HttpResponse(200 + n, emptyList(), "r${n++}".toByteArray(), 1) }
        val text = "# @name one\nGET http://h/a\n\n<> old.json\n###\nGET http://h/b\n\n<> x\n###\nGET http://h/c\n"
        Files.writeString(http, text)
        val root = Files.createDirectory(tmp.resolve("hist"))
        val store = FileHistoryStore(FileHistoryStore.underRoot(root))
        val requests = HttpFileParser().parse(text).requests
        SessionFactory(executor, store).use { f ->
            val session = f.create(http, FileAccessPolicy())
            session.runAll(requests, emptyMap())
            session.runAll(requests, emptyMap())
        }
        assertEquals(text, Files.readString(http))
        assertEquals(listOf("api.http"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
        val one = assertNotNull(ResponseLinks.latest(store, http, requests[0]))
        assertEquals("r3", one.response.bodyAsString())
        val unnamed = assertNotNull(ResponseLinks.latest(store, http, requests[1]))
        assertEquals("r4", unnamed.response.bodyAsString())
        assertNull(ResponseLinks.latest(store, http, requests[2])) // no link directive
    }
}
