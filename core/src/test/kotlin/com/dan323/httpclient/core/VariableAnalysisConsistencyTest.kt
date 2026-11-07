package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.VariableAnalysis
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the sample `.http` files for real (canned responses) and checks that the analysis agrees: a request is
 * flagged exactly when it fails with `Undefined variable`.
 */
class VariableAnalysisConsistencyTest {
    private val samples = File("../samples")
    private val env = mapOf("base" to "http://localhost:8080", "user" to "alice") // `dev` of samples/http-client.env.json

    /** Requests that fail at run time only because the canned response lacks the JSONPath: data-dependent, not static. */
    private val dataDependent = setOf("chaining.http" to "missing-path")

    @Test fun samplesAgreeWithRunTime() {
        val files = samples.listFiles { f -> f.name.endsWith(".http") }!!.sortedBy { it.name }
        assertTrue(files.size >= 7, "samples not found at ${samples.absolutePath}")
        for (f in files) {
            val text = f.readText()
            val file = HttpFileParser().parse(text)
            val executor = HttpExecutor { HttpResponse(200, listOf(Header("Content-Type", "application/json")), """{"json":{"token":"t1"}}""".toByteArray(), 1) }
            val session = RequestSession(RequestRunner(executor) { "{}".toByteArray() }, executor) { json, path ->
                if (path == "$.json.token" && "t1" in json) "t1" else null
            }
            val outcomes = session.runAll(file.requests, env)
            val undefinedAtRuntime = outcomes.withIndex()
                .filter { (_, o) -> o is RunOutcome.Failure && o.message.startsWith("Undefined variable") }
                .map { it.index }.toSet()

            val analysis = VariableAnalysis.analyze(text, env.keys, file)
            val flagged = analysis.diagnostics.map { d ->
                file.requests.indexOfLast { it.startLine <= d.range.line }
            }.toSet()

            val dd = undefinedAtRuntime.filter { (f.name to file.requests[it].name) in dataDependent }.toSet()
            assertEquals(undefinedAtRuntime - dd, flagged, "${f.name}: analysis and run time disagree")
        }
    }
}
