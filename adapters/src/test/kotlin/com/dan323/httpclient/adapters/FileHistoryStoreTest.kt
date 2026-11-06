package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.AssertionResult
import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileHistoryStoreTest {
    private val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private class TickClock(var now: Instant = Instant.parse("2026-11-06T10:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now.also { now = now.plusMillis(5) }
    }

    private fun entry(name: String, status: Int? = 200) =
        ReportEntry(name, "GET", "http://x/$name", status, 12, listOf(AssertionResult("status == 200", true, "200", "200", "ok")), 0, null)

    private fun report(vararg names: String) = RunReport(names.map { entry(it) })
    private fun response(body: String, status: Int = 200) = HttpResponse(status, listOf(Header("Content-Type", "text/plain"), Header("Set-Cookie", "a=1"), Header("Set-Cookie", "b=2")), body.toByteArray(), 7)

    private val dir: Path = createTempDirectory("history")
    private val http: Path = dir.resolve("api.http").also { it.writeText("GET http://x\n") }
    private val warnings = mutableListOf<String>()

    private fun store(maxRuns: Int = 50, maxBody: Int = 1024, clock: Clock = TickClock()) =
        FileHistoryStore(FileHistoryStore.nextToHttpFile, maxRuns, maxBody, clock) { warnings += it }

    private fun runFolder() = dir.resolve(".http-history").resolve("api.http")

    @Test fun recordsAndReadsBackEverything() {
        val s = store()
        val run = s.record(http, report("login", "boom"), listOf(response("hello"), null))
        val listed = s.list(http)
        assertEquals(listOf(run.id), listed.map { it.id })
        assertEquals(run.report, listed[0].report)
        assertEquals(run.timestamp, listed[0].timestamp)
        assertEquals(run.id, s.read(http, run.id)?.id)
        val r = assertNotNull(s.readResponse(http, run.id, 0))
        assertEquals(200, r.response.statusCode)
        assertEquals("hello", r.response.bodyAsString())
        assertEquals(7, r.response.durationMillis)
        assertEquals(listOf("a=1", "b=2"), r.response.headerValues("set-cookie"))
        assertEquals("login", r.requestName)
        assertEquals("GET", r.method)
        assertEquals(0, r.index)
        assertFalse(r.bodyTruncated)
        assertNull(s.readResponse(http, run.id, 1), "no response was recorded")
        assertNull(s.readResponse(http, run.id, 9))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test fun listsNewestFirstAndFindsLatestByName() {
        val s = store()
        val a = s.record(http, report("login", "me"), listOf(response("a1"), response("a2")))
        val b = s.record(http, report("login"), listOf(response("b1")))
        assertEquals(listOf(b.id, a.id), s.list(http).map { it.id })
        assertEquals("b1", s.latestResponse(http, "login")?.response?.bodyAsString())
        assertEquals("a2", s.latestResponse(http, "me")?.response?.bodyAsString())
        assertNull(s.latestResponse(http, "nope"))
        assertNull(s.latestResponse(http, " "))
        assertEquals(emptyList(), s.list(dir.resolve("other.http")))
    }

    @Test fun mismatchedResponsesAreRejected() {
        assertFailsWith<IllegalArgumentException> { store().record(http, report("a"), emptyList()) }
    }

    @Test fun keepsOnlyTheNewestRuns() {
        val s = store(maxRuns = 3)
        val ids = (1..5).map { s.record(http, report("r"), listOf(response("$it"))).id }
        assertEquals(ids.takeLast(3).reversed(), s.list(http).map { it.id })
        Files.list(runFolder()).use { assertEquals(3, it.count()) }
        assertEquals("5", s.latestResponse(http, "r")?.response?.bodyAsString())
    }

    @Test fun truncatesBigBodiesWithAMarker() {
        val s = store(maxBody = 10)
        val run = s.record(http, report("big", "small"), listOf(response("0123456789ABCDEF"), response("tiny")))
        val big = assertNotNull(s.readResponse(http, run.id, 0))
        assertTrue(big.bodyTruncated)
        assertEquals(16, big.originalBodySize)
        assertContentEquals("0123456789".toByteArray(), big.response.body)
        val small = assertNotNull(s.readResponse(http, run.id, 1))
        assertFalse(small.bodyTruncated)
        assertEquals(4, small.originalBodySize)
    }

    @Test fun storesBinaryBodiesExactly() {
        val bytes = ByteArray(256) { it.toByte() }
        val s = store()
        val run = s.record(http, report("bin"), listOf(HttpResponse(200, emptyList(), bytes, 1)))
        assertContentEquals(bytes, s.readResponse(http, run.id, 0)?.response?.body)
    }

    @Test fun filesAreOwnerOnlyOnPosix() {
        assumeTrue(posix)
        val s = store()
        val run = s.record(http, report("a"), listOf(response("secret")))
        val runDir = runFolder().resolve(run.id)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(".http-history"))))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(runDir)))
        for (f in listOf("run.json", "0.body")) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(runDir.resolve(f))), f)
    }

    @Test fun leavesNoTempFilesAndWritesOnlyInsideTheHistoryFolder() {
        val s = store()
        val run = s.record(http, report("a"), listOf(response("x")))
        Files.list(dir).use { assertEquals(setOf("api.http", ".http-history"), it.map { p -> p.fileName.toString() }.toList().toSet()) }
        Files.list(runFolder().resolve(run.id)).use { assertEquals(setOf("run.json", "0.body"), it.map { p -> p.fileName.toString() }.toList().toSet()) }
    }

    @Test fun rootIsInjectableAndKeepsEquallyNamedFilesApart() {
        val root = dir.resolve("elsewhere")
        val s = FileHistoryStore(FileHistoryStore.underRoot(root), onWarning = { warnings += it })
        val other = dir.resolve("sub").also { Files.createDirectories(it) }.resolve("api.http")
        s.record(http, report("a"), listOf(response("one")))
        s.record(other, report("a"), listOf(response("two")))
        assertEquals("one", s.latestResponse(http, "a")?.response?.bodyAsString())
        assertEquals("two", s.latestResponse(other, "a")?.response?.bodyAsString())
        assertFalse(Files.exists(dir.resolve(".http-history")))
        Files.list(root).use { assertEquals(2, it.count()) }
    }

    @Test fun skipsCorruptAndForeignEntries() {
        val s = store()
        val good = s.record(http, report("a"), listOf(response("ok")))
        val folder = runFolder()
        val corrupt = folder.resolve("20260101T000000000Z-deadbeef").also { Files.createDirectory(it) }
        corrupt.resolve("run.json").writeText("{ not json")
        val wrongId = folder.resolve("20260101T000000001Z-deadbeef").also { Files.createDirectory(it) }
        wrongId.resolve("run.json").writeText("""{"version":1,"id":"other","timestamp":"2026-01-01T00:00:00Z","report":{},"responses":[]}""")
        val newer = folder.resolve("20260101T000000002Z-deadbeef").also { Files.createDirectory(it) }
        newer.resolve("run.json").writeText("""{"version":99,"id":"20260101T000000002Z-deadbeef","timestamp":"2026-01-01T00:00:00Z","report":{},"responses":[]}""")
        val incomplete = folder.resolve("20260101T000000003Z-deadbeef").also { Files.createDirectory(it) }
        folder.resolve("notes.txt").writeText("hi")
        Files.createDirectory(folder.resolve("random-folder"))
        assertEquals(listOf(good.id), s.list(http).map { it.id })
        assertTrue(warnings.size >= 3, warnings.toString())
        assertTrue(Files.exists(incomplete), "a possibly running writer's folder is not touched")
        // a missing or damaged body only loses that response
        Files.delete(runFolder().resolve(good.id).resolve("0.body"))
        assertNull(s.readResponse(http, good.id, 0))
        assertNotNull(s.read(http, good.id))
    }

    @Test fun symlinkedEntriesAreNotFollowed() {
        assumeTrue(posix)
        val s = store()
        val run = s.record(http, report("a"), listOf(response("ok")))
        val secret = dir.resolve("secret.txt").also { it.writeText("TOP-SECRET") }
        val body = runFolder().resolve(run.id).resolve("0.body")
        Files.delete(body)
        Files.createSymbolicLink(body, secret)
        assertNull(s.readResponse(http, run.id, 0))
    }

    @Test fun staleIncompleteRunFoldersAreRemoved() {
        val clock = TickClock()
        val s = store(clock = clock)
        val old = runFolder().also { Files.createDirectories(it) }.resolve("20260101T000000000Z-deadbeef").also { Files.createDirectory(it) }
        s.record(http, report("a"), listOf(response("x")))
        assertFalse(Files.exists(old))
    }

    @Test fun rejectsTraversalInRunIds() {
        val s = store()
        s.record(http, report("a"), listOf(response("x")))
        for (bad in listOf("..", "../x", "..\\x", ".", "", "a/b", "20260101T000000000Z-deadbeef/../..", "C:\\x")) {
            assertNull(s.read(http, bad), bad)
            assertNull(s.readResponse(http, bad, 0), bad)
        }
    }

    @Test fun sanitizesNames() {
        assertEquals("api.http", HistoryNames.safeComponent("api.http"))
        assertEquals("_", HistoryNames.safeComponent(".."))
        assertEquals("_", HistoryNames.safeComponent("."))
        assertEquals("_", HistoryNames.safeComponent(""))
        assertEquals("_.._x", HistoryNames.safeComponent("/../x"))
        assertEquals("a_b_c_d_e_f_g_h_i", HistoryNames.safeComponent("a<b>c:d\"e|f?g*h\ni"))
        assertEquals("a_b", HistoryNames.safeComponent("a\\b"))
        assertEquals("_CON", HistoryNames.safeComponent("CON"))
        assertEquals("_nul.http", HistoryNames.safeComponent("nul.http"))
        assertEquals("_LPT1", HistoryNames.safeComponent("LPT1."))
        assertEquals("COM10", HistoryNames.safeComponent("COM10"))
        assertEquals("name", HistoryNames.safeComponent("name. . "))
        assertEquals(80, HistoryNames.safeComponent("x".repeat(500)).length)
        assertEquals("_", HistoryNames.safeComponent("...   "))
    }

    @Test fun hostileHttpFileNamesStayInsideTheHistoryFolder() {
        val s = store()
        val weird = dir.resolve("a b.http").also { it.writeText("") }
        s.record(weird, report("a"), listOf(response("x")))
        assertTrue(Files.isDirectory(dir.resolve(".http-history").resolve("a b.http")))
    }

    @Test fun runIdsAreUniqueAndSortable() {
        val ids = (1..200).map { HistoryNames.newRunId(Instant.parse("2026-11-06T10:00:00Z"), SecureRandom()) }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { HistoryNames.isRunId(it) })
        assertEquals(Instant.parse("2026-11-06T10:00:00Z"), HistoryNames.timeOf(ids[0]))
        assertTrue(HistoryNames.newRunId(Instant.parse("2026-11-06T10:00:00Z"), SecureRandom()) < HistoryNames.newRunId(Instant.parse("2026-11-06T10:00:01Z"), SecureRandom()))
    }

    @Test fun concurrentWritersDoNotLoseOrCorruptRuns() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            // separate store instances, as separate processes would have; the same instant on purpose
            val fixed = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC)
            val tasks = (1..24).map { i -> Callable { store(clock = fixed).record(http, report("r"), listOf(response("body-$i"))).id } }
            val ids = pool.invokeAll(tasks).map { it.get() }
            assertEquals(24, ids.toSet().size)
            val listed = store().list(http)
            assertEquals(24, listed.size)
            val bodies = listed.map { store().readResponse(http, it.id, 0)!!.response.bodyAsString() }.toSet()
            assertEquals((1..24).map { "body-$it" }.toSet(), bodies)
            assertTrue(warnings.isEmpty(), warnings.toString())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun invalidSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { FileHistoryStore(maxRuns = 0) }
        assertFailsWith<IllegalArgumentException> { FileHistoryStore(maxBodyBytes = -1) }
    }

    @Test fun failedAssertionsAndErrorsSurviveTheRoundTrip() {
        val failing = ReportEntry("x", "POST", "http://x", null, null, listOf(AssertionResult("status == 200", false, "200", "500", "bad")), 1, "boom", true)
        val s = store()
        val run = s.record(http, RunReport(listOf(failing)), listOf(null))
        assertEquals(failing, s.read(http, run.id)?.report?.entries?.single())
    }

    @Test fun `the latest record of every link key survives runs of other keys`() {
        val s = store(maxRuns = 3)
        s.recordLinked(http, "a", report("a"), response("A"))
        repeat(10) { s.recordLinked(http, "b", report("b"), response("B$it")) }
        s.record(http, report("x"), listOf(response("plain")))
        assertEquals("A", s.latestLinked(http, "a")!!.response.bodyAsString())
        assertEquals("B9", s.latestLinked(http, "b")!!.response.bodyAsString())
        // 3 newest runs + the older run that holds the newest "a"; "b" is inside the window
        assertEquals(4, s.list(http).size)
        // a newer record of "a" replaces the old one, which is then pruned
        s.recordLinked(http, "a", report("a"), response("A2"))
        s.recordLinked(http, "a", report("a"), response("A3"))
        s.recordLinked(http, "a", report("a"), response("A4"))
        assertEquals("A4", s.latestLinked(http, "a")!!.response.bodyAsString())
        assertEquals("B9", s.latestLinked(http, "b")!!.response.bodyAsString())
        assertEquals(4, s.list(http).size)
    }

    @Test fun `ordinary runs and entries without keys are still limited to maxRuns`() {
        val s = store(maxRuns = 2)
        repeat(6) { s.record(http, report("r$it"), listOf(response("b$it"))) }
        assertEquals(2, s.list(http).size)
        // an old file without keys loads and is pruned as before
        val old = runFolder().resolve(s.list(http).last().id).resolve("run.json")
        assertFalse("\"key\"" in Files.readString(old))
    }
}
