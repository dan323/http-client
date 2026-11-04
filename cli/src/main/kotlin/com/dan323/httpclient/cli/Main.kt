package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Files
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Usage: httpc <file.http> [--env NAME] [--request NAME|INDEX] [--list]

  --env NAME       use variables from NAME in http-client.env.json (next to the file)
  --request X      run only the request with @name X, or the Nth request (1-based)
  --list           list the requests in the file and exit"""

data class Options(
    val file: Path,
    val env: String? = null,
    val request: String? = null,
    val list: Boolean = false,
)

/** Returns null (after the caller prints usage) when the arguments are invalid. */
fun parseArgs(args: List<String>): Options? {
    var file: Path? = null
    var env: String? = null
    var request: String? = null
    var list = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--env" -> env = args.getOrNull(++i) ?: return null
            "--request" -> request = args.getOrNull(++i) ?: return null
            "--list" -> list = true
            else -> if (a.startsWith("--") || file != null) return null else file = Path.of(a)
        }
        i++
    }
    return Options(file ?: return null, env, request, list)
}

/** Picks the request selected by name or 1-based index; all requests when no selector is given. */
fun select(requests: List<RequestTemplate>, selector: String?): List<RequestTemplate>? {
    if (selector == null) return requests
    val byName = requests.filter { it.name == selector }
    if (byName.isNotEmpty()) return byName
    return selector.toIntOrNull()?.let { requests.getOrNull(it - 1) }?.let(::listOf)
}

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}

/** Runs the CLI and returns the process exit code: 0 ok, 1 failure, 2 bad usage. */
fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    val opts = parseArgs(args) ?: run { err.println(USAGE); return 2 }
    if (!Files.isRegularFile(opts.file)) return fail(err, "No such file: ${opts.file}")

    val dir = opts.file.toAbsolutePath().parent
    val requests = HttpFileParser().parse(Files.readString(opts.file)).requests

    if (opts.list) {
        requests.forEachIndexed { i, r -> out.println("${i + 1}. ${r.name ?: "-"}  ${r.method} ${r.url}") }
        return 0
    }

    val variables = if (opts.env == null) emptyMap() else {
        JsonEnvironmentRepository(dir).load().firstOrNull { it.name == opts.env }?.variables
            ?: return fail(err, "Unknown environment: ${opts.env}")
    }
    val chosen = select(requests, opts.request)?.let { withDependencies(requests, it) }
        ?: return fail(err, "No such request: ${opts.request}")
    val executor = JdkHttpExecutor()
    val session = RequestSession(RequestRunner(executor, LocalFileReader(dir)), executor, KotlinxJsonExtractor())

    val outcomes = session.runAll(chosen, variables) { outcome ->
        val r = outcome.request
        out.println("### ${r.name ?: "${r.method} ${r.url}"}")
        when (outcome) {
            is RunOutcome.Success -> {
                val resp = outcome.response
                out.println("HTTP ${resp.statusCode} (${resp.durationMillis} ms)")
                resp.headers.forEach { out.println("${it.name}: ${it.value}") }
                out.println()
                out.println(resp.bodyAsString())
                outcome.assertions.forEach {
                    out.println(if (it.passed) "PASS ${it.assertion}" else "FAIL ${it.assertion} -- ${it.message}")
                }
            }
            is RunOutcome.Failure -> err.println("Error: ${outcome.message}")
        }
    }
    val results = outcomes.filterIsInstance<RunOutcome.Success>().flatMap { it.assertions }
    if (results.isNotEmpty()) out.println("Assertions: ${results.count { it.passed }} passed, ${results.count { !it.passed }} failed")
    val failed = outcomes.any { it is RunOutcome.Failure || (it is RunOutcome.Success && !it.assertionsPassed) }
    return if (failed) 1 else 0
}

private fun fail(err: PrintStream, message: String): Int {
    err.println("Error: $message")
    return 1
}
