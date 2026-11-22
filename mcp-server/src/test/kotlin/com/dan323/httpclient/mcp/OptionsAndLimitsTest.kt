package com.dan323.httpclient.mcp

import com.dan323.httpclient.adapters.RequestRefusedException
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptionsAndLimitsTest {
    private val dir = Files.createTempDirectory("opts").toRealPath().toString()

    @Test fun parsesFlagsAndDefaults() {
        val o = ServerOptions.parse(listOf("--root", dir, "--allow-host", "*.example.com", "--allow-write", "--allow-env", "A,B",
            "--max-requests", "5", "--total-timeout", "2m", "--request-timeout", "10s", "--max-body-bytes", "9")).getOrThrow()
        assertEquals(listOf("*.example.com"), o.allowHosts); assertTrue(o.allowWrite); assertEquals(setOf("A", "B"), o.allowEnv)
        assertEquals(5, o.maxRequests); assertEquals(120_000, o.totalMillis); assertEquals(10_000, o.requestTimeoutMillis); assertEquals(9, o.maxBodyBytes)
        val d = ServerOptions.parse(listOf("--root", dir)).getOrThrow()
        assertEquals(null, d.allowHosts); assertEquals(100, d.maxRequests); assertEquals(300_000, d.totalMillis)
    }

    @Test fun refusesBadArguments() {
        for (args in listOf(
            emptyList(), listOf("--root"), listOf("--root", "relative"), listOf("--root", "$dir/missing"),
            listOf("--root", dir, "--bogus"), listOf("--root", dir, "--max-requests", "0"), listOf("--root", dir, "--allow-host", "a b"),
            listOf("--root", dir, "--total-timeout", "soon"),
        )) assertTrue(ServerOptions.parse(args).isFailure, args.toString())
    }

    private class Clock(var now: Long = 0) { fun read() = now }

    @Test fun stopsAfterTheRequestCapAndAfterTheDeadline() {
        val sent = mutableListOf<Long?>()
        val clock = Clock()
        val limits = CallLimits({ r -> sent += r.options.timeoutMillis; HttpResponse(200, emptyList(), ByteArray(0), 0) }, 2, 1000, clock::read)
        val req = HttpRequest("GET", "http://h/")
        limits.begin()
        limits.execute(req); limits.execute(req)
        assertFailsWith<RequestRefusedException> { limits.execute(req) }
        limits.begin() // a new call starts again
        clock.now = 600_000_000 // 600 ms in: 400 ms left, which also caps the request timeout
        limits.execute(req)
        assertEquals(400L, sent.last())
        clock.now = 1_000_000_000
        assertFailsWith<RequestRefusedException> { limits.execute(req) }
    }
}
