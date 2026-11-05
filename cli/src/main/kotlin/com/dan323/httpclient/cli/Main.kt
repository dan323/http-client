package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.JsonRunReport
import com.dan323.httpclient.core.application.JUnitXmlReport
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Files
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import com.dan323.httpclient.core.application.ReportEntry
import kotlin.system.exitProcess

private const val USAGE = """Usage: httpc <file.http> [--env NAME] [--request NAME|INDEX] [--allow-env NAME[,NAME]] [--list]
                  [--report-json FILE] [--report-junit FILE] [--allow-file-root DIR]

  --env NAME       use variables from NAME in http-client.env.json (next to the file)
  --request X      run only the request with @name X, or the Nth request (1-based)
  --allow-env N    let {{${'$'}env.N}} read these OS environment variables (comma separated, repeatable);
                   none are readable by default
  --allow-file-root DIR  also let `< path` bodies be read from DIR (repeatable); by default only the
                   .http file's folder is readable (symlinks resolved)
  --list           list the requests in the file and exit
  --report-json F  write a JSON run report (results, assertions, timings, totals) to F
  --report-junit F write a JUnit XML run report to F"""

data class Options(
    val file: Path,
    val env: String? = null,
    val request: String? = null,
    val list: Boolean = false,
    val allowEnv: Set<String> = emptySet(),
    val reportJson: Path? = null,
    val reportJunit: Path? = null,
    val allowFileRoots: List<Path> = emptyList(),
)

/**
 * Returns null (after the caller prints usage) when the arguments are invalid: unknown or repeated options, a missing,
 * empty or flag-like (`--...`) option value, an invalid path, or the same file for both reports.
 */
fun parseArgs(args: List<String>): Options? = try {
    parseArgsUnchecked(args)
} catch (_: InvalidPathException) {
    null
}

private fun parseArgsUnchecked(args: List<String>): Options? {
    var file: Path? = null
    var env: String? = null
    var request: String? = null
    var list = false
    val allowEnv = mutableSetOf<String>()
    var reportJson: Path? = null
    var reportJunit: Path? = null
    val allowFileRoots = mutableListOf<Path>()
    var i = 0
    fun value(): String? = args.getOrNull(++i)?.takeIf { it.isNotEmpty() && !it.startsWith("--") }
    while (i < args.size) {
        when (val a = args[i]) {
            "--env" -> { if (env != null) return null; env = value() ?: return null }
            "--request" -> { if (request != null) return null; request = value() ?: return null }
            "--allow-env" -> allowEnv += SystemEnvironmentVariables.parseNames(value() ?: return null)
            "--allow-file-root" -> allowFileRoots.add(Path.of(value() ?: return null))
            "--list" -> list = true
            "--report-json" -> { if (reportJson != null) return null; reportJson = Path.of(value() ?: return null) }
            "--report-junit" -> { if (reportJunit != null) return null; reportJunit = Path.of(value() ?: return null) }
            else -> if (a.startsWith("--") || file != null || a.isEmpty()) return null else file = Path.of(a)
        }
        i++
    }
    if (reportJson != null && reportJunit != null && reportJson.toAbsolutePath().normalize() == reportJunit.toAbsolutePath().normalize()) return null
    return Options(file ?: return null, env, request, list, allowEnv, reportJson, reportJunit, allowFileRoots)
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

/**
 * Runs the CLI and returns the process exit code: 0 ok, 1 request/assertion failure (or any other error before
 * running), 2 bad usage, 3 a requested report could not be written (the message says whether the run itself passed;
 * both reports are always attempted). When a report is requested and the run cannot start (missing file, unknown
 * environment or request, unexpected exception) an error report with a single failed entry is still written.
 */
fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    val opts = parseArgs(args) ?: run { err.println(USAGE); return 2 }
    return try {
        execute(opts, out, err)
    } catch (e: Exception) {
        failWithReport(opts, err, "Unexpected error: ${e.message ?: e}")
    }
}

private fun execute(opts: Options, out: PrintStream, err: PrintStream): Int {
    if (!Files.isRegularFile(opts.file)) return failWithReport(opts, err, "No such file: ${opts.file}")

    val dir = opts.file.toAbsolutePath().parent
    val parsed = HttpFileParser().parse(Files.readString(opts.file))
    parsed.warnings.forEach { err.println("warning: ${opts.file}: $it") }
    val requests = parsed.requests

    if (opts.list) {
        requests.forEachIndexed { i, r -> out.println("${i + 1}. ${r.name ?: "-"}  ${r.method} ${r.url}") }
        return 0
    }

    val variables = if (opts.env == null) emptyMap() else {
        JsonEnvironmentRepository(dir).load().firstOrNull { it.name == opts.env }?.variables
            ?: return failWithReport(opts, err, "Unknown environment: ${opts.env}")
    }
    val selected = select(requests, opts.request)
        ?: return failWithReport(opts, err, "No such request: ${opts.request}")
    val chosen = withDependencies(requests, selected)
    val executor = JdkHttpExecutor()
    val session = RequestSession(RequestRunner(executor, LocalFileReader(dir, FileAccessPolicy(opts.allowFileRoots, FileAccessPolicy.CLI_HINT)), DynamicVariables(env = SystemEnvironmentVariables(opts.allowEnv, "pass --allow-env NAME"))), executor, KotlinxJsonExtractor())

    // Prerequisites run for their responses only: assertions are evaluated for the selected requests.
    val outcomes = session.runAll(chosen, variables, assertFor = { r -> selected.any { it === r } }) { outcome ->
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
            is RunOutcome.Failure -> {
                err.println("Error: ${outcome.message}")
                outcome.request.assertions.takeIf { outcome.assertionsNotRun > 0 }?.forEach { out.println("NOT RUN $it") }
            }
        }
    }
    val results = outcomes.filterIsInstance<RunOutcome.Success>().flatMap { it.assertions }
    val notRun = outcomes.filterIsInstance<RunOutcome.Failure>().sumOf { it.assertionsNotRun }
    if (results.isNotEmpty() || notRun > 0) {
        out.println("Assertions: ${results.count { it.passed }} passed, ${results.count { !it.passed }} failed, $notRun not run")
    }
    val report = RunReport.from(outcomes) { o -> selected.none { it === o.request } }
    val failed = outcomes.any { it is RunOutcome.Failure || (it is RunOutcome.Success && !it.assertionsPassed) }
    val exit = if (failed) 1 else 0
    return if (writeReports(opts, report, err)) exit else {
        err.println("Run ${if (failed) "failed" else "passed"}; only the report could not be written.")
        3
    }
}

/** Writes the requested reports independently; returns false when any of them failed (each failure is printed). */
private fun writeReports(opts: Options, report: RunReport, err: PrintStream): Boolean {
    val suite = opts.file.fileName?.toString() ?: "httpc"
    val json = opts.reportJson?.let { writeReport(it, err) { JsonRunReport.render(report) } } ?: true
    val junit = opts.reportJunit?.let { writeReport(it, err) { JUnitXmlReport.render(report, suite) } } ?: true
    return json && junit
}

private fun writeReport(path: Path, err: PrintStream, content: () -> String): Boolean = try {
    val target = path.toAbsolutePath()
    val parent = target.parent
    parent?.let { Files.createDirectories(it) }
    val tmp = Files.createTempFile(parent, ".httpc-report-", ".tmp")
    try {
        Files.writeString(tmp, content())
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
    true
} catch (e: Exception) {
    err.println("Error: Cannot write report $path: ${e.message ?: e}")
    false
}

/** Fails with exit 1 and, when reports were requested, writes a report holding the single error. */
private fun failWithReport(opts: Options, err: PrintStream, message: String): Int {
    val entry = ReportEntry("httpc", "-", opts.file.toString(), null, null, emptyList(), 0, message)
    val code = fail(err, message)
    return if (writeReports(opts, RunReport(listOf(entry)), err)) code else 3
}

private fun fail(err: PrintStream, message: String): Int {
    err.println("Error: $message")
    return 1
}
