package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTimeoutTest {
    private fun load(property: String? = null, env: String? = null) =
        DefaultTimeout.load({ if (it == DefaultTimeout.PROPERTY) property else null }, { if (it == DefaultTimeout.ENV_VAR) env else null })

    @Test fun defaultsToSixtySeconds() = assertEquals(60_000L, load().getOrThrow())

    @Test fun propertyWinsOverTheEnvironmentVariable() = assertEquals(5_000L, load("5s", "10m").getOrThrow())

    @Test fun environmentVariableIsUsedWithoutTheProperty() = assertEquals(600_000L, load(null, "10m").getOrThrow())

    @Test fun blankValuesAreIgnored() = assertEquals(60_000L, load("  ", null).getOrThrow())

    @Test fun badValueIsAFailureNamingTheSettingNeverTheDefault() {
        val e = load("soon").exceptionOrNull()!!
        assertTrue(e.message!!.contains("httpclient.timeout / HTTPC_TIMEOUT") && e.message!!.contains("'soon'"), e.message)
        assertTrue(load("0").isFailure)
    }

    private class Capturing : HttpExecutor {
        val timeouts = mutableListOf<Long?>()
        override fun execute(request: HttpRequest): HttpResponse {
            timeouts += request.options.timeoutMillis
            return HttpResponse(200, emptyList(), ByteArray(0), 1)
        }
    }

    private fun timeoutSentBy(request: String, session: (SessionFactory, Path) -> RequestSession): Long? {
        val exec = Capturing()
        val file = Files.createTempDirectory("timeout").resolve("a.http")
        SessionFactory(exec).use { f ->
            session(f, file).runAll(HttpFileParser().parse(request).requests, emptyMap())
        }
        return exec.timeouts.single()
    }

    /** The redirect follower hands each hop what is left of the deadline, so the executor sees at most the limit, a little less. */
    private fun assertAbout(limit: Long, actual: Long?) =
        assertTrue(actual != null && actual in (limit - 1_000)..limit, "expected about $limit ms but was $actual")

    @Test fun sessionsGetTheBuiltInDefaultUnlessGivenAnother() {
        assertAbout(60_000L, timeoutSentBy("GET http://h/\n") { f, file -> f.create(file, FileAccessPolicy()) })
        assertAbout(9_000L, timeoutSentBy("GET http://h/\n") { f, file -> f.create(file, FileAccessPolicy(), defaultTimeoutMillis = 9_000) })
        assertAbout(1_000L, timeoutSentBy("# @timeout 1\nGET http://h/\n") { f, file -> f.create(file, FileAccessPolicy(), defaultTimeoutMillis = 9_000) })
    }

    @Test fun createConfiguredReadsTheConfiguredDefaultAndReportsABadOne() {
        val property = DefaultTimeout.PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "45s")
            assertAbout(45_000L, timeoutSentBy("GET http://h/\n") { f, file -> f.createConfigured(file).getOrThrow() })
            System.setProperty(property, "soon")
            SessionFactory(Capturing()).use { f ->
                val r = f.createConfigured(Files.createTempDirectory("timeout").resolve("a.http"))
                assertTrue(r.isFailure && r.exceptionOrNull()!!.message!!.contains("httpclient.timeout"))
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
