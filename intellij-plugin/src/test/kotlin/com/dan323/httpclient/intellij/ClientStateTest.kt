package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import com.dan323.httpclient.core.port.out.FileReader
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.StoredResponse
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.port.out.JsonExtractor
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientStateTest {
    private val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var gate: CountDownLatch? = null
    @Volatile private var entered: CountDownLatch? = null
    private val executor = HttpExecutor { r: HttpRequest ->
        sent += r.url
        entered?.countDown()
        gate?.await(10, TimeUnit.SECONDS)
        HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"token":"t"}""".toByteArray(), 1)
    }
    private val json = JsonExtractor { _, _ -> "t" }
    private var created = 0
    private val locator = EnvironmentLocator { EnvironmentRepository { listOf(Environment("dev", mapOf("base" to "http://h"))) } }
    private fun state(max: Int = 16) = ClientState(locator, max) { created++; Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, json)) }

    private val file = Path.of("a.http").toAbsolutePath()
    private val text = "# @name login\nGET {{base}}/login\n\n###\n# @name me\nGET {{base}}/me\nX: {{login.response.body.\$.token}}\n"
    private val dev = EnvironmentChoice.Named("dev")

    @Test fun freshStateHasAnEmptyReportAndAnyContentMakesItNonEmpty() {
        assertTrue(state().lastReport.isEmpty)
        assertTrue(Report().isEmpty)
        assertFalse(Report(message = "m").isEmpty)
        assertFalse(Report(environment = "dev").isEmpty)
        assertFalse(Report(sections = listOf(Section(OutcomeDisplay.Reused("t", "s")))).isEmpty)
    }

    @Test fun defaultChoiceIsNoneAndNeverSilentlyChanges() {
        val s = state()
        assertEquals(EnvironmentChoice.None, s.environmentChoice)
        s.environmentChoice = EnvironmentChoice.Named("gone")
        assertTrue(s.run(file, text, 5))
        assertTrue(s.lastReport.message!!.contains("gone"), s.lastReport.message)
        assertEquals(EnvironmentChoice.Named("gone"), s.environmentChoice)
    }

    @Test fun noneRunsWithoutEnvironmentAndNamedUsesIt() {
        val s = state()
        s.run(file, "GET http://x/y\n", 0)
        assertEquals("none", s.lastReport.environment)
        s.environmentChoice = dev
        s.run(file, text, 0)
        assertEquals("dev", s.lastReport.environment)
        assertEquals("http://h/login", sent.last())
    }

    @Test fun runAllSendsEveryRequestInOrderAndReportsEachOne() {
        val s = state()
        s.environmentChoice = dev
        assertTrue(s.runAll(file, text))
        assertEquals(listOf("http://h/login", "http://h/me"), sent.toList())
        assertEquals(2, s.lastReport.sections.size)
        assertEquals("dev", s.lastReport.environment)
    }

    @Test fun runAllOnAFileWithoutRequestsSaysSoAndReleasesTheGuard() {
        val s = state()
        assertTrue(s.runAll(file, "# nothing here\n"))
        assertEquals("The file has no requests.", s.lastReport.message)
        assertFalse(s.isRunning)
        assertTrue(s.run(file, text, 5))
    }

    @Test fun sessionIsReusedAndResetChainSendsAgain() {
        val s = state()
        s.environmentChoice = dev
        s.run(file, text, 5)
        s.run(file, text, 5)
        assertEquals(1, created)
        assertEquals(listOf("http://h/login", "http://h/me", "http://h/me"), sent.toList())
        s.resetChain()
        assertTrue(s.lastReport.message!!.startsWith("Chain reset"))
        s.run(file, text, 5)
        assertEquals(5, sent.size)
    }

    @Test fun aChangeOfAccessDuringARunLetsItFinishButNoLaterRunReusesItsSession() {
        val s = state()
        s.environmentChoice = dev
        gate = CountDownLatch(1); entered = CountDownLatch(1)
        val t = thread { s.run(file, text, 5) }
        assertTrue(entered!!.await(10, TimeUnit.SECONDS))
        s.accessChanged()
        gate!!.countDown(); t.join(10_000)
        assertFalse(s.lastReport.message?.startsWith("Chain reset") ?: false)   // quiet: the report of the run stays
        assertEquals(1, created)
        gate = null; entered = null
        s.run(file, text, 5)
        assertEquals(2, created)                                                // a new session, made with the new access
        assertEquals(listOf("http://h/login", "http://h/me", "http://h/login", "http://h/me"), sent.toList())
    }

    @Test fun secondRunWhileRunningIsRefusedAndKeepsTheReport() {
        val s = state()
        gate = CountDownLatch(1); entered = CountDownLatch(1)
        val t = thread { s.run(file, "GET http://x/slow\n", 0) }
        assertTrue(entered!!.await(10, TimeUnit.SECONDS))
        assertTrue(s.isRunning)
        assertFalse(s.run(file, "GET http://x/other\n", 0))
        s.resetChain()
        assertFalse(s.lastReport.message?.startsWith("Chain reset") ?: false)
        gate!!.countDown(); t.join(10_000)
        assertFalse(s.isRunning)
        assertEquals(listOf("http://x/slow"), sent.toList())
        assertTrue(s.run(file, "GET http://x/other\n", 0))
    }

    @Test fun cancelStopsTheRunReportsCancelledAndAllowsTheNextRun() {
        val s = state()
        s.environmentChoice = dev
        val running = mutableListOf<Boolean>()
        s.onRunning { running += it }
        s.cancel()   // nothing running: ignored
        gate = CountDownLatch(1); entered = CountDownLatch(1)
        val t = thread { s.run(file, text, 5) }
        assertTrue(entered!!.await(10, TimeUnit.SECONDS))
        s.cancel()
        t.join(10_000)
        assertFalse(t.isAlive)
        assertFalse(s.isRunning)
        assertEquals(listOf(true, false), running.toList())
        assertEquals("Cancelled", s.lastReport.message)
        assertEquals(0, s.cachedSessions)   // the cancelled request is not remembered
        gate = null
        assertTrue(s.run(file, text, 5))
        assertEquals(listOf("http://h/login", "http://h/login", "http://h/me"), sent.toList())
    }

    @Test fun aCancelRightAfterTheRunStartsIsNotErased() {
        val s = state()
        s.environmentChoice = dev
        s.onRunning { if (it) s.cancel() }   // the cancel lands between the run being claimed and its request being sent
        assertTrue(s.run(file, text, 5))
        assertEquals(emptyList(), sent.toList())
        assertEquals("Cancelled", s.lastReport.message)
        assertFalse(s.isRunning)
    }

    @Test fun aListenerThatThrowsOnStartDoesNotLeaveTheRunGuardSet() {
        val s = state()
        s.environmentChoice = dev
        val remove = s.onRunning { if (it) error("boom") }
        assertFailsWith<IllegalStateException> { s.run(file, text, 5) }
        assertFalse(s.isRunning)
        remove()
        assertTrue(s.run(file, text, 5))
    }

    @Test fun runningNotificationsArriveInTransitionOrder() {
        val s = state()
        s.environmentChoice = dev
        val seen = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        s.onRunning { seen += it }
        repeat(20) { s.run(file, text, 5) }
        assertEquals(List(20) { listOf(true, false) }.flatten(), seen.toList())
    }

    @Test fun editingTheFileDropsItsRememberedResponses() {
        val s = state()
        s.environmentChoice = dev
        s.run(file, text, 5)
        s.run(file, text + "\n", 5)
        assertEquals(4, sent.size)
        assertEquals(1, s.cachedSessions)
    }

    @Test fun forgetDropsTheFileAndSessionsAreBounded() {
        val s = state(max = 2)
        s.environmentChoice = dev
        (1..4).forEach { s.run(Path.of("f$it.http").toAbsolutePath(), text, 5) }
        assertEquals(2, s.cachedSessions)
        s.forget(Path.of("f4.http"))
        assertEquals(1, s.cachedSessions)
    }

    @Test fun reportListenersCanBeUnregistered() {
        val s = state()
        val got = mutableListOf<Report>()
        val remove = s.onReport { got += it }
        s.run(file, "GET http://x/y\n", 0)
        val n = got.size
        assertTrue(n > 0)
        remove()
        s.run(file, "GET http://x/y\n", 0)
        assertEquals(n, got.size)
    }

    @Test fun rememberedTextsAreBoundedAndEvictionDropsTheSession() {
        val s = state(2)
        val files = (1..5).map { Path.of("f$it.http").toAbsolutePath() }
        files.forEach { s.run(it, text, 5) }
        assertEquals(2, s.rememberedTexts)
        assertTrue(s.cachedSessions <= 2)
    }

    // ---- showLinked (T27) ----

    private val linkedText = "# @name a\nGET http://h/a\n\n<> a.json\n\n###\n# @name b\nGET http://h/b\n"

    private fun fakeStore(stored: StoredResponse? = null, fail: Boolean = false, keys: MutableList<String> = mutableListOf()) = object : HistoryStore {
        override fun record(httpFile: Path, report: RunReport, responses: List<HttpResponse?>): HistoryRun = error("unused")
        override fun list(httpFile: Path): List<HistoryRun> = error("unused")
        override fun read(httpFile: Path, runId: String): HistoryRun? = error("unused")
        override fun readResponse(httpFile: Path, runId: String, index: Int): StoredResponse? = error("unused")
        override fun latestResponse(httpFile: Path, requestName: String): StoredResponse? = error("unused")
        override fun recordLinked(httpFile: Path, key: String, report: RunReport, response: HttpResponse): HistoryRun = error("unused")
        override fun latestLinked(httpFile: Path, key: String): StoredResponse? {
            keys += key
            if (fail) throw java.io.IOException("disk")
            return stored
        }
    }

    private fun linkedState(store: HistoryStore?) = ClientState(locator, history = store) { Result.failure(IllegalStateException("no session")) }

    private val storedA = StoredResponse(
        "run1", java.time.Instant.parse("2026-01-02T03:04:05Z"), 0, "a", "GET", "http://h/a",
        HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"x":1}""".toByteArray(), 5), false, 7,
    )

    @Test fun showLinkedShowsTheLatestRecordedResponseWithoutSendingAnything() {
        val keys = mutableListOf<String>()
        val s = linkedState(fakeStore(storedA, keys = keys))
        assertTrue(s.showLinked(file, linkedText, 1))
        val report = s.lastReport
        assertTrue(report.message!!.startsWith("Linked response: a"), report.message)
        assertEquals(listOf("a"), keys)
        val shown = report.sections.single().display as OutcomeDisplay.Response
        assertEquals(200, shown.status)
        assertEquals(0, created)
        assertTrue(sent.isEmpty())
    }

    @Test fun showLinkedExplainsEveryCaseWithNothingToShow() {
        val s = linkedState(fakeStore(null))
        s.showLinked(file, linkedText, 1)
        assertTrue(s.lastReport.message!!.contains("Run it first"), s.lastReport.message)
        assertTrue(s.lastReport.sections.isEmpty())
        s.showLinked(file, linkedText, 7)
        assertEquals("This request has no <> link.", s.lastReport.message)
        s.showLinked(file, "GET http://h/\n\n###\nFOO bar baz\n", 0)
        assertTrue(s.lastReport.message!!.isNotBlank())
        s.showLinked(file, "", 0)
        assertEquals("No request found at the caret.", s.lastReport.message)
        linkedState(fakeStore(fail = true)).also { it.showLinked(file, linkedText, 1) }
            .let { assertTrue(it.lastReport.message!!.startsWith("Could not read the linked response: disk")) }
        linkedState(null).also { it.showLinked(file, linkedText, 1) }
            .let { assertEquals("No response history is available.", it.lastReport.message) }
    }

    @Test fun showLinkedReportsAnUnreadableHistoryFolderWithTheProductionStore() {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val root = java.nio.file.Files.createTempDirectory("hist")
        val store = com.dan323.httpclient.adapters.FileHistoryStore({ root }, onWarning = {})
        val report = RunReport(listOf(com.dan323.httpclient.core.application.ReportEntry("a", "GET", "http://h/a", 200, 1, emptyList(), 0, null)))
        store.recordLinked(file, "a", report, storedA.response)
        val s = linkedState(store)
        s.showLinked(file, linkedText, 1)
        assertTrue(s.lastReport.message!!.startsWith("Linked response: a"), s.lastReport.message)
        java.nio.file.Files.setPosixFilePermissions(root, emptySet())
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(!java.nio.file.Files.isReadable(root))   // root can read anything
            s.showLinked(file, linkedText, 1)
            assertTrue(s.lastReport.message!!.startsWith("Could not read the linked response: cannot list"), s.lastReport.message)
            assertFalse(s.lastReport.message!!.contains("Run it first"))
        } finally {
            java.nio.file.Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        // the folder above it denies traversal: still a read error, not "nothing recorded"
        val parent = java.nio.file.Files.createTempDirectory("histparent")
        val nested = com.dan323.httpclient.adapters.FileHistoryStore({ parent.resolve("inner") }, onWarning = {})
        nested.recordLinked(file, "a", report, storedA.response)
        val t = linkedState(nested)
        java.nio.file.Files.setPosixFilePermissions(parent, emptySet())
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(!java.nio.file.Files.isReadable(parent))
            t.showLinked(file, linkedText, 1)
            assertTrue(t.lastReport.message!!.startsWith("Could not read the linked response: cannot read"), t.lastReport.message)
        } finally {
            java.nio.file.Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun showLinkedRefusesWhileARunIsInProgress() {
        val s = ClientState(locator, history = fakeStore(storedA)) { Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, json)) }
        entered = CountDownLatch(1)
        gate = CountDownLatch(1)
        val running = thread { s.run(file, "GET http://x/slow\n", 0) }
        assertTrue(entered!!.await(10, TimeUnit.SECONDS))
        val before = s.lastReport
        assertFalse(s.showLinked(file, linkedText, 1))
        assertEquals(before, s.lastReport)
        gate!!.countDown()
        running.join(10_000)
        assertTrue(s.showLinked(file, linkedText, 1))
    }
}
