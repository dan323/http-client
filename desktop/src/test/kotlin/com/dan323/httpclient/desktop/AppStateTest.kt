package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.application.RunOutcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppStateTest {
    private lateinit var server: HttpServer
    private val logins = java.util.concurrent.atomic.AtomicInteger()
    private val slowHits = java.util.concurrent.atomic.AtomicInteger()
    private val entered = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.CountDownLatch(1)
    private val dir = Files.createTempDirectory("httpc-desktop")

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { ex ->
            logins.incrementAndGet()
            val b = """{"token":"zzz"}""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/echo") { ex ->
            val b = (ex.requestHeaders.getFirst("X-Token") ?: "none").toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/slow") { ex ->
            slowHits.incrementAndGet()
            entered.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val b = "slow".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.address.port}"}}""")
    }

    @AfterTest fun stop() { release.countDown(); server.stop(0) }

    private fun slowFile(name: String) = dir.resolve(name).also { Files.writeString(it, "GET {{base}}/slow\n") }

    private val chained = "# @name login\nPOST {{base}}/login\n\n###\n# @name me\nGET {{base}}/echo\nX-Token: {{login.response.body.$.token}}\n"

    private fun bodyOf(o: RunOutcome) = (o as RunOutcome.Success).response.bodyAsString()

    @Test fun opensFileAndListsRequestsAndEnvironments() {
        val file = dir.resolve("a.http").also { Files.writeString(it, chained) }
        AppState().use { state ->
            state.open(file)
            assertEquals(listOf("login", "me"), state.requests.map { it.name })
            assertEquals(listOf("dev"), state.environments)
            assertFalse(state.dirty)
        }
    }

    @Test fun sendRunsSelectedRequestWithPrerequisites() {
        val file = dir.resolve("b.http").also { Files.writeString(it, chained) }
        AppState().use { state ->
            state.open(file)
            state.environment = "dev"
            state.selected = 1
            state.run(all = false)
            assertEquals(listOf("login", "me"), state.outcomes.map { it.request.name })
            assertEquals("zzz", bodyOf(state.outcomes.last()))
            assertFalse(state.running)
        }
    }

    @Test fun editsAreSavedBeforeRunning() {
        val file = dir.resolve("c.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
        AppState().use { state ->
            state.open(file)
            state.environment = "dev"
            state.edit("GET {{base}}/echo\nX-Token: edited\n")
            assertTrue(state.dirty)
            state.run(all = true)
            assertEquals("edited", bodyOf(state.outcomes.single()))
            assertEquals("GET {{base}}/echo\nX-Token: edited\n", Files.readString(file))
            assertFalse(state.dirty)
        }
    }

    @Test fun environmentsComeFromParentFolderAndStaleNameIsAnError() {
        val sub = Files.createDirectories(dir.resolve("nested/deeper"))
        val file = sub.resolve("e.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
        AppState().use { state ->
            state.open(file)
            assertEquals(listOf("dev"), state.environments)
            state.environment = "gone"
            state.run(all = true)
            assertTrue(state.message!!.contains("Unknown environment: gone"), state.message)
            assertTrue(state.outcomes.isEmpty())
        }
    }

    @Test fun malformedEnvFileIsShownWithItsName() {
        val bad = Files.createTempDirectory("httpc-bad")
        bad.resolve("http-client.env.json").also { Files.writeString(it, "{nope") }
        val file = bad.resolve("e.http").also { Files.writeString(it, "GET http://x/\n") }
        AppState().use { state ->
            state.open(file)
            // file name only: Windows may report the temp dir in its long form (RUNNER~1 vs runneradmin)
            assertTrue(state.message!!.contains("http-client.env.json"), state.message)
        }
    }

    @Test fun failuresAreShownAsOutcomes() {
        val file = dir.resolve("d.http").also { Files.writeString(it, "GET {{nope}}/x\n") }
        AppState().use { state ->
            state.open(file)
            state.run(all = true)
            assertTrue(state.outcomes.single() is RunOutcome.Failure)
        }
    }

    @Test fun badFileAccessConfigurationIsReportedAsFailure() {
        val file = dir.resolve("a.http")
        val outcomes = DesktopRunner(policy = { com.dan323.httpclient.adapters.FileAccessPolicy.load({ "relative" }, { null }) }).use {
            it.execute(file, "GET http://127.0.0.1:${server.address.port}/echo\n", null, null)
        }
        val f = outcomes.single() as RunOutcome.Failure
        assertTrue("relative" in f.message)
    }

    @Test fun secondSendReusesTheLoginResponse() {
        val file = dir.resolve("cache.http").also { Files.writeString(it, chained) }
        AppState().use { state ->
            state.open(file)
            state.environment = "dev"
            state.selected = 1
            state.run(all = false)
            state.run(all = false)
            assertEquals(1, logins.get())
            assertEquals(listOf("me"), state.outcomes.map { it.request.name })
            assertEquals("zzz", bodyOf(state.outcomes.single()))
            state.resetChain()
            state.run(all = false)
            assertEquals(2, logins.get())
        }
    }

    @Test fun resetChainSetsATransientNoticeClearedByTheNextRunOrClearNotice() {
        AppState().use { state ->
            state.open(slowFile("notice.http"))
            assertEquals(null, state.notice)
            state.resetChain()
            assertEquals("Chain reset", state.notice)
            state.clearNotice()
            assertEquals(null, state.notice)
            state.resetChain()
            state.open(slowFile("notice2.http"))
            assertEquals(null, state.notice)
        }
    }

    @Test fun resettingTheChainAgainChangesTheNoticeId() {
        AppState().use { state ->
            state.resetChain()
            val first = state.noticeId
            state.resetChain()
            assertEquals("Chain reset", state.notice)
            assertNotEquals(first, state.noticeId)
        }
    }

    @Test fun simultaneousRunsExecuteOnce() {
        AppState().use { state ->
            state.open(slowFile("race.http"))
            state.environment = "dev"
            val n = 8
            val barrier = java.util.concurrent.CyclicBarrier(n)
            val finished = java.util.concurrent.CountDownLatch(n - 1)
            val threads = (1..n).map {
                Thread {
                    barrier.await()
                    state.run(all = true)
                    // The winner stays blocked in the server until release, so only the n - 1 losers can get here.
                    // Do not test state.running: a loser can return before the winner has set it.
                    finished.countDown()
                }.also { it.start() }
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // every losing thread has returned from run() while the winner is still inside the server
            assertTrue(finished.await(15, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            threads.forEach { it.join(15_000) }
            assertEquals(1, slowHits.get())
            assertEquals(1, state.outcomes.size)
        }
    }

    @Test fun openingAVanishedRecentFileWhileRunningIsRefusedAndLeavesTheList() {
        val gone = dir.resolve("gone.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
        val cfg = Files.createTempDirectory("httpc-cfg")
        try {
            AppState(settings = SettingsPersistence(com.dan323.httpclient.adapters.JsonSettingsStore(cfg.resolve("c")))).use { state ->
                state.open(gone)
                state.open(slowFile("busy.http"))
                state.environment = "dev"
                Files.delete(gone)
                val runner = Thread { state.run(all = true) }.also { it.start() }
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                state.openRecent(state.recentFiles.last())
                assertEquals(2, state.recentFiles.size)
                assertTrue(state.message!!.startsWith("Cannot"))
                release.countDown()
                runner.join(15_000)
                state.openRecent(state.recentFiles.last())   // idle again: removed
                assertEquals(1, state.recentFiles.size)
            }
        } finally { cfg.toFile().deleteRecursively() }
    }

    @Test fun openingAnotherFileWhileRunningIsRefusedAndKeepsOutcomesWithTheirFile() {
        val other = dir.resolve("other.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
        AppState().use { state ->
            val slow = slowFile("switch.http")
            state.open(slow)
            state.environment = "dev"
            val t = Thread { state.run(all = true) }.also { it.start() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            state.open(other)
            assertEquals(slow, state.file)
            assertTrue(state.message!!.contains("running"), state.message)
            release.countDown()
            t.join(15_000)
            assertEquals(listOf("slow"), state.outcomes.map { bodyOf(it) })
            assertEquals(slow, state.file)
        }
    }

    @Test fun failedOpenKeepsThePreviousFileAndText() {
        AppState().use { state ->
            val good = dir.resolve("good.http").also { Files.writeString(it, "GET http://x/\n") }
            state.open(good)
            state.open(dir.resolve("missing.http"))
            assertEquals(good, state.file)
            assertEquals("GET http://x/\n", state.text)
            assertTrue(state.message != null)
        }
    }

    @Test fun failedEnvironmentRefreshDropsTheOldEnvironments() {
        val d = Files.createTempDirectory("httpc-envs")
        Files.writeString(d.resolve("http-client.env.json"), """{"dev":{}}""")
        val file = d.resolve("e.http").also { Files.writeString(it, "GET http://x/\n") }
        AppState().use { state ->
            state.open(file)
            assertEquals(listOf("dev"), state.environments)
            Files.writeString(d.resolve("http-client.env.json"), "{nope")
            state.save()
            assertEquals(emptyList(), state.environments)
            assertTrue(state.message!!.contains("http-client.env.json"), state.message)
        }
    }

    @Test fun staleSelectionReportsInsteadOfRunningNothing() {
        AppState().use { state ->
            state.open(dir.resolve("s.http").also { Files.writeString(it, "GET {{base}}/echo\n") })
            state.environment = "dev"
            state.selected = 5
            state.run(all = false)
            assertTrue(state.outcomes.isEmpty())
            assertTrue(state.message!!.contains("No request"), state.message)
        }
    }

    @Test fun saveErrorSurvivesTheRunAttempt() {
        AppState().use { state ->
            val file = dir.resolve("sv.http").also { Files.writeString(it, "GET {{base}}/echo\n") }
            state.open(file)
            state.environment = "dev"
            Files.delete(file)
            Files.createDirectory(file)   // writing the file now fails on every platform
            state.run(all = true)
            assertTrue(state.message != null)
            assertTrue(state.outcomes.isEmpty())
            assertFalse(state.running)
        }
    }

    @Test fun requestListIsParsedOncePerEdit() {
        AppState().use { state ->
            state.open(dir.resolve("p.http").also { Files.writeString(it, chained) })
            assertTrue(state.requests === state.requests)
            val before = state.requests
            state.edit("GET http://x/\n")
            assertEquals(1, state.requests.size)
            assertTrue(before !== state.requests)
        }
    }

    @Test fun malformedEnvFileDoesNotBlockSendingWithoutAnEnvironment() {
        val bad = Files.createTempDirectory("httpc-bad2")
        Files.writeString(bad.resolve("http-client.env.json"), "{nope")
        val file = bad.resolve("e.http").also { Files.writeString(it, "GET http://127.0.0.1:${server.address.port}/echo\n") }
        AppState().use { state ->
            state.open(file)
            state.edit("GET http://127.0.0.1:${server.address.port}/echo\nX-Token: t\n")
            assertTrue(state.save())   // the write succeeded even though the env refresh failed
            assertFalse(state.dirty)
            assertTrue(state.message!!.contains("http-client.env.json"), state.message)
            assertEquals(emptyList(), state.environments)
            state.run(all = true)
            assertEquals("t", bodyOf(state.outcomes.single()))
        }
    }

    @Test fun editAndSaveAreRefusedWhileRunning() {
        AppState().use { state ->
            val file = slowFile("busy.http")
            state.open(file)
            state.environment = "dev"
            val t = Thread { state.run(all = true) }.also { it.start() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            state.edit("GET http://changed/\n")
            assertEquals("GET {{base}}/slow\n", state.text)
            assertFalse(state.dirty)
            assertFalse(state.save())
            assertTrue(state.message!!.contains("running"), state.message)
            release.countDown()
            t.join(15_000)
            assertEquals("GET {{base}}/slow\n", Files.readString(file))
        }
    }

    @Test fun cancelStopsTheRunShowsCancelledAndRemembersNothing() {
        AppState().use { state ->
            val file = slowFile("cancel.http")
            state.open(file)
            state.environment = "dev"
            assertFalse(state.controls.canCancel)
            state.cancel()   // nothing is running: ignored, and it must not cancel the next run
            val t = Thread { state.run(all = false) }.also { it.start() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(state.controls.canCancel)
            state.cancel()
            t.join(15_000)
            assertFalse(t.isAlive)
            assertFalse(state.running)
            assertTrue(state.cancelled)
            assertEquals("Cancelled", state.cancelledLabel)
            assertTrue(state.outcomes.isEmpty())
            assertEquals(null, state.message)   // not an error
            // The cancelled request is sent again, not served from a remembered response.
            release.countDown()
            state.run(all = false)
            assertFalse(state.cancelled)
            assertEquals(2, slowHits.get())
            assertEquals("slow", bodyOf(state.outcomes.single()))
        }
    }

    @Test fun cancelKeepsOutcomesThatFinishedBeforeIt() {
        AppState().use { state ->
            val file = dir.resolve("partial.http").also { Files.writeString(it, "GET {{base}}/echo\n\n###\nGET {{base}}/slow\n") }
            state.open(file)
            state.environment = "dev"
            val t = Thread { state.run(all = true) }.also { it.start() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            state.cancel()
            t.join(15_000)
            assertEquals("Cancelled (1 request had finished)", state.cancelledLabel)
            assertEquals(1, state.outcomes.size)
            state.open(dir.resolve("other.http").also { Files.writeString(it, "GET {{base}}/echo\n") })   // another tab has its own results
            assertEquals(null, state.cancelledLabel)
            assertTrue(state.outcomes.isEmpty())
            state.switchTo(0)
            assertEquals("Cancelled (1 request had finished)", state.cancelledLabel)
            assertEquals(1, state.outcomes.size)
        }
    }

    @Test fun staleSelectionClearsThePreviousRunsOutcomes() {
        AppState().use { state ->
            state.open(dir.resolve("clr.http").also { Files.writeString(it, "GET {{base}}/echo\n") })
            state.environment = "dev"
            state.run(all = true)
            assertEquals(1, state.outcomes.size)
            state.selected = 5
            state.run(all = false)
            assertTrue(state.outcomes.isEmpty())
            assertTrue(state.message!!.contains("No request"), state.message)
        }
    }
}
