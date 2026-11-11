package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.FileHistoryStore
import com.dan323.httpclient.adapters.JsonSettingsStore
import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.mock.MockServer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val root = Files.createTempDirectory("httpc-history")
    private lateinit var server: MockServer
    private lateinit var file: Path

    /** A real store whose files live under [root], so tests never touch the user's folders. */
    private fun store() = FileHistoryStore(historyDirFor = { f -> root.resolve("store").resolve(f.fileName.toString()) }, onWarning = {})

    private val twoRequests = "# @name ok\nGET {{base}}/get\n\n###\n# @name missing\nGET {{base}}/status/404\n"

    @BeforeTest fun start() {
        server = MockServer(0).start()
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.port}"}}""")
        file = Files.writeString(root.resolve("t.http"), twoRequests)
    }

    @AfterTest fun stop() { server.stop(); root.toFile().deleteRecursively() }

    private fun state(history: HistoryStore? = store(), settings: SettingsPersistence? = null) =
        AppState(runner = DesktopRunner(history = history ?: store()), settings = settings, history = history).also {
            it.open(file)
            it.environment = "dev"
        }

    @Test fun `nothing is recorded while the setting is off`() {
        val store = store()
        state(store).use { it.run(all = true) }
        assertEquals(emptyList(), store.list(file.toAbsolutePath()))
    }

    @Test fun `a run is recorded with its responses when the setting is on`() {
        val store = store()
        state(store).use { s ->
            s.recordHistory = true
            s.run(all = true)
            val run = store.list(file.toAbsolutePath()).single()
            assertEquals(listOf("ok", "missing"), run.report.entries.map { it.name })
            assertEquals(listOf(200, 404), run.report.entries.map { it.status })
            assertEquals(200, store.readResponse(file.toAbsolutePath(), run.id, 0)!!.response.statusCode)
            assertEquals(404, store.readResponse(file.toAbsolutePath(), run.id, 1)!!.response.statusCode)
            assertNull(s.message)
        }
    }

    @Test fun `a request that failed is recorded without a response`() {
        val store = store()
        Files.writeString(file, "# @name down\nGET http://127.0.0.1:1/x\n")
        state(store).use { s ->
            s.recordHistory = true
            s.run(all = true)
            val run = store.list(file.toAbsolutePath()).single()
            assertNotNull(run.report.entries.single().error)
            assertNull(store.readResponse(file.toAbsolutePath(), run.id, 0))
        }
    }

    @Test fun `a store that fails does not fail the run`() {
        val real = store()
        val failing = object : HistoryStore by real {
            override fun record(httpFile: Path, report: RunReport, responses: List<HttpResponse?>): HistoryRun = throw IOException("disk full")
        }
        state(failing).use { s ->
            s.recordHistory = true
            s.run(all = true)
            assertEquals(2, s.outcomes.size)
            assertEquals("Could not record history: disk full", s.message)
        }
    }

    @Test fun `a cancelled run records the requests that had finished`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        slow.createContext("/fast") { ex -> ex.sendResponseHeaders(204, -1); ex.close() }
        slow.createContext("/slow") { ex -> entered.countDown(); release.await(10, TimeUnit.SECONDS); ex.sendResponseHeaders(204, -1); ex.close() }
        slow.start()
        try {
            val port = slow.address.port
            Files.writeString(file, "# @name fast\nGET http://127.0.0.1:$port/fast\n\n###\n# @name slow\nGET http://127.0.0.1:$port/slow\n")
            val store = store()
            state(store).use { s ->
                s.recordHistory = true
                val runner = Thread { s.run(all = true) }.apply { start() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                s.cancel()
                runner.join(10_000)
                assertTrue(s.cancelled)
                assertEquals(listOf("fast"), store.list(file.toAbsolutePath()).single().report.entries.map { it.name })
            }
        } finally { release.countDown(); slow.stop(0) }
    }

    @Test fun `the setting survives a restart and defaults to off`() {
        val cfg = root.resolve("cfg")
        fun persistence() = SettingsPersistence(JsonSettingsStore(cfg), debounceMillis = 20)
        AppState(settings = persistence(), history = store()).use { assertFalse(it.recordHistory); it.recordHistory = true }
        AppState(settings = persistence(), history = store()).use { assertTrue(it.recordHistory) }
    }

    @Test fun `without a history store the option is not offered and the panel stays closed`() {
        AppState().use { s ->
            assertFalse(s.canRecordHistory)
            s.showHistory(true)
            assertFalse(s.historyOpen)
            assertNull(s.historyView)
        }
    }

    @Test fun `the panel lists runs and follows new runs and file switches`() {
        val store = store()
        state(store).use { s ->
            s.recordHistory = true
            s.showHistory(true)
            val view = s.historyView!!
            assertEquals(emptyList(), view.runs)
            s.run(all = true)
            assertEquals(1, view.runs.size)           // refreshed after the recorded run
            view.selectRun(view.runs.single().id)
            s.run(all = true)
            assertEquals(2, view.runs.size)
            assertNotNull(view.selectedRun)           // the selection stays on a refresh
            val other = Files.writeString(root.resolve("o.http"), "GET http://127.0.0.1:1/\n")
            s.open(other)
            assertEquals(emptyList(), view.runs)      // another file has its own history
            assertNull(view.selectedRun)
            s.showHistory(false)
            assertFalse(s.historyOpen)
        }
    }

    private fun recordTwo(store: HistoryStore): Pair<HistoryRun, HistoryRun> {
        val abs = file.toAbsolutePath()
        fun entry(status: Int) = ReportEntry("r", "GET", "http://x/a", status, 5, emptyList(), 0, null)
        fun resp(status: Int, body: String, vararg h: Pair<String, String>) = HttpResponse(status, h.map { Header(it.first, it.second) }, body.toByteArray(), 5)
        val first = store.record(abs, RunReport(listOf(entry(200))), listOf(resp(200, "{\"a\":1}", "X-V" to "1")))
        val second = store.record(abs, RunReport(listOf(entry(500))), listOf(resp(500, "{\"a\":2}", "X-V" to "2")))
        return first to second
    }

    @Test fun `history state opens stored responses, marks two and compares them`() {
        val store = store()
        val (first, second) = recordTwo(store)
        val view = HistoryState(store)
        view.load(file.toAbsolutePath())
        assertEquals(listOf(second.id, first.id), view.runs.map { it.id })   // newest first
        assertFalse(view.canCompare)

        view.selectRun(first.id); view.open(0); view.markOpenedAsA()
        view.selectRun(second.id); view.open(0); view.markOpenedAsB()
        assertTrue(view.canCompare)
        view.compare()
        val diff = view.diff!!
        assertEquals("A: 200  →  B: 500", diff.statusLabel)
        assertEquals(1, diff.headers.size)
        view.closeCompare()
        assertNull(view.diff)
        view.markOpenedAsA()                       // a new mark discards a shown comparison
        assertNull(view.diff)
    }

    @Test fun `history state reports a missing response and an unreadable history`() {
        val store = store()
        val abs = file.toAbsolutePath()
        val run = store.record(abs, RunReport(listOf(ReportEntry("down", "GET", "http://x", null, null, emptyList(), 0, "refused"))), listOf(null))
        val view = HistoryState(store)
        view.load(abs)
        view.selectRun(run.id)
        view.open(0)
        assertNull(view.opened)
        assertEquals("That request has no stored response.", view.error)

        val broken = object : HistoryStore by store {
            override fun list(httpFile: Path): List<HistoryRun> = throw IllegalStateException("unreadable")
        }
        val view2 = HistoryState(broken)
        view2.load(abs)
        assertEquals(emptyList(), view2.runs)
        assertEquals("Could not read the history: unreadable", view2.error)
        view2.load(null)
        assertNull(view2.error)
    }

    @Test fun `a response read that finishes after the selection changed is discarded`() {
        val real = store()
        val (first, second) = recordTwo(real)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : HistoryStore by real {
            override fun readResponse(httpFile: Path, runId: String, index: Int) =
                real.readResponse(httpFile, runId, index).also { if (runId == first.id) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        }
        val view = HistoryState(slow)
        view.load(file.toAbsolutePath())
        view.selectRun(first.id)
        val reader = Thread { view.open(0) }.also { it.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        view.selectRun(second.id)                   // moves on while the first read is pending
        release.countDown(); reader.join(5000)
        assertNull(view.opened)
        assertNull(view.openedIndex)
        view.open(0)
        assertEquals(second.id, view.opened?.runId)
    }

    @Test fun `a history load that finishes after a file switch is discarded`() {
        val real = store()
        recordTwo(real)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val a = file.toAbsolutePath()
        val slow = object : HistoryStore by real {
            override fun list(httpFile: Path): List<HistoryRun> =
                real.list(httpFile).also { if (httpFile == a) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        }
        val view = HistoryState(slow)
        val loader = Thread { view.load(a) }.also { it.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        view.load(root.resolve("other.http").toAbsolutePath())   // the user switched files meanwhile
        release.countDown(); loader.join(5000)
        assertEquals(emptyList(), view.runs)
    }

    @Test fun `switching file clears the old runs at once and loads the new ones in the background`() {
        val real = store()
        recordTwo(real)
        val a = file.toAbsolutePath()
        val view = HistoryState(real)
        view.load(a)
        assertEquals(2, view.runs.size)
        val gate = CountDownLatch(1)
        val gated = object : HistoryStore by real {
            override fun list(httpFile: Path): List<HistoryRun> { gate.await(5, TimeUnit.SECONDS); return real.list(httpFile) }
        }
        val v3 = HistoryState(gated)
        v3.loadInBackground(a)
        assertEquals(emptyList(), v3.runs)          // returns before the read finished
        gate.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (v3.runs.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(2, v3.runs.size)
        view.loadInBackground(root.resolve("elsewhere.http").toAbsolutePath())
        assertEquals(emptyList(), view.runs)        // the old file's runs are gone immediately
    }
}
