package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The reason a run is refused says what went wrong, not a stock text: each rejection carries the message of its cause. */
class RunRejectionMessagesTest {
    private val executor = HttpExecutor { HttpResponse(200, emptyList(), ByteArray(0), 1) }
    private val file = Path.of("/tmp/x/a.http")
    private fun controller(locator: EnvironmentLocator = EnvironmentLocator { null }, fileAccess: Result<Unit> = Result.success(Unit)) =
        RunController(locator) { fileAccess.map { RequestSession(RequestRunner(executor) { ByteArray(0) }, executor) { _, _ -> null } } }

    @Test fun `a file that does not parse is refused with the parse error`() {
        val r = controller().run(file, "FROBNICATE http://x\n", RunTarget.All, EnvironmentChoice.None)
        assertIs<RunResult.Rejected>(r)
        assertEquals(RunResult.Reason.PARSE, r.reason)
        assertTrue("unknown HTTP method 'FROBNICATE'" in r.message, r.message)
    }

    @Test fun `an environment that does not exist is refused naming it`() {
        val locator = EnvironmentLocator { EnvironmentRepository { listOf(Environment("dev", emptyMap())) } }
        val r = controller(locator).run(file, "GET http://x\n", RunTarget.All, EnvironmentChoice.Named("prod"))
        assertIs<RunResult.Rejected>(r)
        assertEquals(RunResult.Reason.ENVIRONMENT, r.reason)
        assertTrue("prod" in r.message, r.message)
    }

    @Test fun `an unreadable environment file is refused with the reason`() {
        val locator = EnvironmentLocator { throw EnvironmentFileException(Path.of("/tmp/x/http-client.env.json"), "not valid JSON") }
        val r = controller(locator).run(file, "GET http://x\n", RunTarget.All, EnvironmentChoice.Named("dev"))
        assertIs<RunResult.Rejected>(r)
        assertEquals(RunResult.Reason.ENVIRONMENT, r.reason)
        assertTrue("not valid JSON" in r.message, r.message)
    }

    @Test fun `a file access problem is refused with the request that would have run`() {
        val r = controller(fileAccess = Result.failure(IllegalStateException("bad root"))).run(file, "# @name a\nGET http://x\n", RunTarget.AtIndex(0), EnvironmentChoice.None)
        assertIs<RunResult.Rejected>(r)
        assertEquals(RunResult.Reason.FILE_ACCESS, r.reason)
        assertEquals("File access configuration error: bad root", r.message)
        assertEquals("a", r.request?.name)
        val all = controller(fileAccess = Result.failure(IllegalStateException("bad root"))).run(file, "# @name first\nGET http://x\n", RunTarget.All, EnvironmentChoice.None)
        assertEquals("first", (all as RunResult.Rejected).request?.name)
    }
}
