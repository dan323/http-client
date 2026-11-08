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
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
