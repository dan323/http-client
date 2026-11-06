package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rendered reports must not contain a variable value that was substituted into a URL and echoed by a failure. */
class ReportRedactionTest {
    private val secret = "s3cr3t-T0KEN value"

    private fun report(): RunReport {
        val executor = HttpExecutor { req -> error("failed ${req.url}") }
        val session = RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }
        val outcome = session.runAll(listOf(RequestTemplate("r", "GET", "http://h/?t={{tok}}")), mapOf("tok" to secret)).single()
        assertTrue(outcome is RunOutcome.Failure)
        return RunReport.from(listOf(outcome))
    }

    private fun assertClean(rendered: String) {
        val encoded = java.net.URLEncoder.encode(secret, Charsets.UTF_8)
        assertFalse(secret in rendered, rendered)
        assertFalse("s3cr3t" in rendered, rendered)
        assertFalse(encoded in rendered, rendered)
        assertTrue("{{tok}}" in rendered, rendered)
    }

    @Test fun junitXmlDoesNotContainSecret() = assertClean(JUnitXmlReport.render(report()))

    @Test fun jsonReportDoesNotContainSecret() = assertClean(JsonRunReport.render(report()))
}
