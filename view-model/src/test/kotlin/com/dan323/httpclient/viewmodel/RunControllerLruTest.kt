package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import com.dan323.httpclient.core.port.out.FileReader
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class RunControllerLruTest {
    private val sent = CopyOnWriteArrayList<String>()
    @Volatile private var gate: CountDownLatch? = null
    private val entered = CountDownLatch(1)
    private val executor = HttpExecutor { r ->
        sent += r.url
        gate?.let { entered.countDown(); it.await(10, TimeUnit.SECONDS) }
        HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"token":"t"}""".toByteArray(), 1)
    }
    private val locator = EnvironmentLocator { EnvironmentRepository { listOf(Environment("dev", mapOf("base" to "http://h"))) } }
    private val controller = RunController(locator, 2) {
        Result.success(RequestSession(RequestRunner(executor, FileReader { ByteArray(0) }), executor, JsonExtractor { _, _ -> "t" }))
    }
    private val text = "# @name login\nGET {{base}}/login\n\n###\n# @name me\nGET {{base}}/me\nX: {{login.response.body.\$.token}}\n"
    private val dev = EnvironmentChoice.Named("dev")
    private fun f(n: String) = Path.of(n).toAbsolutePath()
    private fun runMe(file: Path) = controller.run(file, text, RunTarget.AtIndex(1), dev)
    private fun logins() = sent.count { it.endsWith("/login") }

    @Test fun leastRecentlyUsedIsEvicted() {
        runMe(f("a.http")); runMe(f("b.http"))
        runMe(f("a.http")) // a is now most recent
        runMe(f("c.http")) // evicts b
        assertEquals(2, controller.cachedSessions)
        val before = logins()
        runMe(f("a.http")); assertEquals(before, logins(), "a kept its session")
        runMe(f("b.http")); assertEquals(before + 1, logins(), "b was evicted, login re-sent")
    }

    @Test fun forgetDropsOnlyThatFile() {
        runMe(f("a.http")); runMe(f("b.http"))
        controller.forget(f("a.http"))
        assertEquals(1, controller.cachedSessions)
        val before = logins()
        runMe(f("b.http")); assertEquals(before, logins())
        runMe(f("a.http")); assertEquals(before + 1, logins())
    }

    @Test fun runningSessionIsNeverEvictedNorReset() {
        gate = CountDownLatch(1)
        val t = thread { runMe(f("a.http")) }
        assertEquals(true, entered.await(10, TimeUnit.SECONDS))
        val g = gate; gate = null
        runMe(f("b.http")); runMe(f("c.http")); runMe(f("d.http")) // would evict a if idle
        assertEquals(true, controller.cachedSessions >= 1)
        controller.resetChain()
        controller.forget(f("a.http"))
        assertEquals(1, controller.cachedSessions, "only the running session is left")
        g!!.countDown(); t.join(10_000)
    }
}
