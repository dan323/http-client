package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.JsonRunReport
import com.dan323.httpclient.adapters.AtomicReportWriter
import com.dan323.httpclient.adapters.JUnitXmlReport
import com.dan323.httpclient.core.port.out.OutputWriter
import com.dan323.httpclient.core.port.out.ReportWriter
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.application.RequestTimeouts
import com.dan323.httpclient.adapters.DefaultTimeout
import com.dan323.httpclient.adapters.UpwardEnvironmentLocator
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.UnknownEnvironmentException
import com.dan323.httpclient.core.application.resolve
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.adapters.FileAccessConfigException
import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.adapters.FileHistoryStore
import com.dan323.httpclient.adapters.SessionFactory
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Files
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import com.dan323.httpclient.core.application.ReportEntry
import kotlin.system.exitProcess

private const val USAGE = """Usage: httpc <file.http> [--env NAME] [--request NAME|INDEX] [--allow-env NAME[,NAME]] [--list]
                  [--report-json FILE] [--report-junit FILE] [--allow-file-root DIR]
                  [--quiet] [--output FILE] [--var NAME=VALUE] [--timeout DURATION]

  --env NAME       use variables from NAME in http-client.env.json (nearest http-client.env.json, searching
                   upward from the file; http-client.private.env.json beside it is merged)
  --request X      run only the request with @name X, or the Nth request (1-based)
  --allow-env N    let {{${'$'}env.N}} read these OS environment variables (comma separated, repeatable);
                   none are readable by default; -Dhttpclient.allowEnv and HTTPC_ALLOW_ENV are merged in
  --allow-file-root DIR  also let `< path` bodies be read from DIR (repeatable); by default only the
                   .http file's folder is readable (symlinks resolved)
  --list           list the requests in the file and exit (cannot be combined with --report-*)
  --report-json F  write a JSON run report (results, assertions, timings, totals) to F
  --report-junit F write a JUnit XML run report to F
  --quiet          print no per-request output on stdout (failed assertions and errors still go to stderr)
  --output F       write the raw response body (byte for byte) of the single selected request to F (needs
                   --request unless the file holds one request; owner-only file; cannot be combined with --list)
  --var N=V        set variable N to V, overriding file and environment variables (repeatable; V may be
                   empty or contain '=')
  --timeout D      total time limit for requests without `# @timeout` (headers and body), such as 30, 500ms,
                   45s or 2m (plain numbers are seconds); default 60s; -Dhttpclient.timeout and HTTPC_TIMEOUT
                   set it when the option is absent

A summary line ("3 requests, 2 passed, 1 failed in 1.2s") always goes to stderr after a run.
(report/output paths must differ from the .http file and each other;
exit 1 = failed run, 3 = passed but a report/output file is unwritable)"""

data class Options(
    val file: Path,
    val env: String? = null,
    val request: String? = null,
    val list: Boolean = false,
    val allowEnv: Set<String> = emptySet(),
    val reportJson: Path? = null,
    val reportJunit: Path? = null,
    val allowFileRoots: List<Path> = emptyList(),
    val quiet: Boolean = false,
    val output: Path? = null,
    val vars: Map<String, String> = emptyMap(),
    /** Default total timeout in milliseconds, or null to use `-Dhttpclient.timeout` / `HTTPC_TIMEOUT` / the built-in default. */
    val timeoutMillis: Long? = null,
)

/**
 * Parses `name=value` for `--var`: the name is everything before the first `=` and must not be blank or contain
 * whitespace or braces; the value is the rest (may be empty or contain `=`). Null when malformed.
 */
internal fun parseVar(text: String): Pair<String, String>? {
    val eq = text.indexOf('=')
    if (eq <= 0) return null
    val name = text.substring(0, eq)
    if (name.any { it.isWhitespace() || it == '{' || it == '}' }) return null
    return name to text.substring(eq + 1)
}

/**
 * Returns null (after the caller prints usage) when the arguments are invalid: unknown or repeated options, a missing,
 * empty or flag-like (`--...`) option value, an invalid path, or the same file for both reports.
 */
fun parseArgs(args: List<String>, err: PrintStream = System.err): Options? = try {
    parseArgsUnchecked(args, err)
} catch (_: InvalidPathException) {
    null
}

private fun parseArgsUnchecked(args: List<String>, err: PrintStream): Options? {
    var file: Path? = null
    var env: String? = null
    var request: String? = null
    var list = false
    val allowEnv = mutableSetOf<String>()
    var reportJson: Path? = null
    var reportJunit: Path? = null
    val allowFileRoots = mutableListOf<Path>()
    var quiet = false
    var output: Path? = null
    val vars = LinkedHashMap<String, String>()
    var timeoutMillis: Long? = null
    var i = 0
    fun value(): String? = args.getOrNull(++i)?.takeIf { it.isNotEmpty() && !it.startsWith("--") }
    while (i < args.size) {
        when (val a = args[i]) {
            "--env" -> { if (env != null) return null; env = value() ?: return null }
            "--request" -> { if (request != null) return null; request = value() ?: return null }
            "--allow-env" -> allowEnv += SystemEnvironmentVariables.parseNames(value() ?: return null)
            "--allow-file-root" -> allowFileRoots.add(
                try { FileAccessPolicy.validateRoot(value() ?: return null, requireAbsolute = false) } catch (e: FileAccessConfigException) {
                    err.println("httpc: --allow-file-root: ${e.message}"); return null
                },
            )
            "--list" -> list = true
            "--quiet" -> quiet = true
            "--output" -> { if (output != null) return null; output = Path.of(value() ?: return null) }
            "--var" -> {
                // the value is never echoed: it may be a secret
                val (name, v) = parseVar(value() ?: return null)
                    ?: run { err.println("httpc: --var: expected NAME=VALUE with a non-empty name (no spaces or braces)"); return null }
                if (name in vars) { err.println("httpc: --var: '$name' given more than once"); return null }
                vars[name] = v
            }
            "--timeout" -> {
                if (timeoutMillis != null) return null
                timeoutMillis = try { RequestTimeouts.parseMillis(value() ?: return null, label = "--timeout") } catch (e: IllegalArgumentException) {
                    err.println("httpc: ${e.message}"); return null
                }
            }
            "--report-json" -> { if (reportJson != null) return null; reportJson = Path.of(value() ?: return null) }
            "--report-junit" -> { if (reportJunit != null) return null; reportJunit = Path.of(value() ?: return null) }
            else -> if (a.startsWith("--") || file != null || a.isEmpty()) return null else file = Path.of(a)
        }
        i++
    }
    val input = file ?: return null
    if (reportJson != null && reportJunit != null && samePath(reportJson, reportJunit)) return null
    val targets = listOf("--report-json" to reportJson, "--report-junit" to reportJunit, "--output" to output)
    for ((flag, target) in targets) {
        if (target == null) continue
        if (samePath(target, input)) {
            err.println("httpc: $flag: would overwrite the input file $input"); return null
        }
        if (list) {
            err.println("httpc: $flag cannot be combined with --list (nothing is run, so there is nothing to write)"); return null
        }
    }
    if (output != null && ((reportJson != null && samePath(output, reportJson)) || (reportJunit != null && samePath(output, reportJunit)))) {
        err.println("httpc: --output: same file as a report option"); return null
    }
    if (list && (quiet || vars.isNotEmpty() || timeoutMillis != null)) {
        err.println("httpc: --quiet, --var and --timeout cannot be combined with --list (nothing is run)"); return null
    }
    return Options(input, env, request, list, allowEnv, reportJson, reportJunit, allowFileRoots, quiet, output, vars, timeoutMillis)
}

/** True when both paths name the same file: equal after normalisation, or the same existing file (symlinks, hard links). */
internal fun samePath(a: Path, b: Path): Boolean {
    if (a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()) return true
    if (sameFolderCaseInsensitiveName(a, b)) return true
    return try {
        Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
    } catch (_: java.io.IOException) {
        false
    }
}

/**
 * Same real parent folder and file names equal ignoring case. On a case-insensitive file system such paths are one
 * file even when it does not exist yet (so `isSameFile` cannot tell); on a case-sensitive one this is conservative:
 * two reports differing only by case are rejected as well.
 */
private fun sameFolderCaseInsensitiveName(a: Path, b: Path): Boolean {
    val na = a.fileName?.toString() ?: return false
    val nb = b.fileName?.toString() ?: return false
    if (!na.equals(nb, ignoreCase = true)) return false
    return realParent(a) == realParent(b)
}

/** Real path of the parent: the nearest existing ancestor resolved through symlinks, plus the not-yet-existing rest. */
private fun realParent(p: Path): Path? {
    val parent = p.toAbsolutePath().normalize().parent ?: return null
    var existing: Path = parent
    while (!Files.exists(existing)) existing = existing.parent ?: return parent
    return try {
        existing.toRealPath().resolve(existing.relativize(parent)).normalize()
    } catch (_: java.io.IOException) {
        parent
    }
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
 * running), 2 bad usage (also `--output` when the run selects several requests; nothing is sent), 3 a requested
 * report or output file could not be written (the message says whether the run itself passed; all are always attempted). When a report is requested and the run cannot start (missing file, unknown
 * environment or request, unexpected exception) an error report with a single failed entry is still written.
 */
fun run(args: List<String>, out: PrintStream, err: PrintStream, outputWriter: OutputWriter = AtomicReportWriter(), writer: ReportWriter = AtomicReportWriter()): Int {
    val opts = parseArgs(args, err) ?: run { err.println(USAGE); return 2 }
    return try {
        execute(opts, out, err, writer, outputWriter)
    } catch (e: Exception) {
        failWithReport(opts, err, writer, "Unexpected error: ${e.message ?: e}")
    }
}

private fun execute(opts: Options, out: PrintStream, err: PrintStream, writer: ReportWriter, outputWriter: OutputWriter): Int {
    if (!Files.isRegularFile(opts.file)) return failWithReport(opts, err, writer, "No such file: ${opts.file}")

    val parsed = HttpFileParser().parse(Files.readString(opts.file))
    parsed.warnings.forEach { err.println("warning: ${opts.file}: $it") }
    // `--var` wins over everything: the resolver prefers file variables to the variable map, so the overridden
    // names are dropped from the file variables (other file variables referring to them then see the --var value).
    val requests = if (opts.vars.isEmpty()) parsed.requests else parsed.requests.map {
        if (it.fileVariables.keys.any { k -> k in opts.vars }) it.copy(fileVariables = it.fileVariables - opts.vars.keys) else it
    }

    if (opts.list) {
        requests.forEachIndexed { i, r -> out.println("${i + 1}. ${r.name ?: "-"}  ${r.method} ${r.url}") }
        return 0
    }

    val variables = try {
        (EnvironmentChoice.of(opts.env).resolve(UpwardEnvironmentLocator(), opts.file)?.variables ?: emptyMap()) + opts.vars
    } catch (e: UnknownEnvironmentException) {
        return failWithReport(opts, err, writer, e.message ?: "Unknown environment: ${opts.env}")
    } catch (e: EnvironmentFileException) {
        return failWithReport(opts, err, writer, e.message ?: "Invalid environment file")
    }
    val selected = select(requests, opts.request)
        ?: return failWithReport(opts, err, writer, "No such request: ${opts.request}")
    if (opts.output != null && selected.size != 1) {
        err.println("httpc: --output needs exactly one selected request (${selected.size} would run); pick one with --request")
        return 2
    }
    val defaultTimeout = opts.timeoutMillis ?: DefaultTimeout.load().getOrElse {
        return failWithReport(opts, err, writer, it.message ?: "Invalid default timeout")
    }
    val chosen = withDependencies(requests, selected)
    val started = System.nanoTime()
    // Prerequisites run for their responses only: assertions are evaluated for the selected requests.
    var saveFailed = false
    val outcomes = SessionFactory(history = FileHistoryStore(onWarning = { err.println("warning: $it") })).use { factory ->
    val session = factory.create(opts.file, FileAccessPolicy(opts.allowFileRoots, FileAccessPolicy.CLI_HINT), opts.allowEnv, "pass --allow-env NAME", defaultTimeout)
    session.runAll(chosen, variables, assertFor = { r -> selected.any { it === r } }) { outcome ->
        val r = outcome.request
        if (!opts.quiet) out.println("### ${r.name ?: "${r.method} ${r.url}"}")
        when (outcome) {
            is RunOutcome.Success -> {
                val resp = outcome.response
                if (!opts.quiet) {
                    out.println("HTTP ${resp.statusCode} (${resp.durationMillis} ms)")
                    resp.headers.forEach { out.println("${it.name}: ${it.value}") }
                    out.println()
                    out.println(resp.bodyAsString())
                }
                outcome.savedTo?.let { err.println("Saved response: $it") }
                outcome.saveError?.let { err.println("Error: $it"); saveFailed = true }
                outcome.linkError?.let { err.println("warning: $it") }
                outcome.assertions.forEach {
                    if (!it.passed) (if (opts.quiet) err else out).println("FAIL ${it.assertion} -- ${it.message}")
                    else if (!opts.quiet) out.println("PASS ${it.assertion}")
                }
            }
            is RunOutcome.Failure -> {
                err.println("Error: ${outcome.message}")
                if (!opts.quiet) outcome.request.assertions.takeIf { outcome.assertionsNotRun > 0 }?.forEach { out.println("NOT RUN $it") }
            }
        }
    }
    }
    val results = outcomes.filterIsInstance<RunOutcome.Success>().flatMap { it.assertions }
    val notRun = outcomes.filterIsInstance<RunOutcome.Failure>().sumOf { it.assertionsNotRun }
    if (!opts.quiet && (results.isNotEmpty() || notRun > 0)) {
        out.println("Assertions: ${results.count { it.passed }} passed, ${results.count { !it.passed }} failed, $notRun not run")
    }
    val report = RunReport.from(outcomes) { o -> selected.none { it === o.request } }
    val elapsed = (System.nanoTime() - started) / 1_000_000
    err.println(summary(report, elapsed))
    // Responses saved by `>>` / `>>!` are not replaced by a report or --output that names the same file (symlinks, case aliases too).
    val inputDir = (opts.file.toAbsolutePath().parent ?: opts.file.toAbsolutePath().root)
    val saved = outcomes.filterIsInstance<RunOutcome.Success>().mapNotNull { it.savedTo?.let(inputDir::resolve) }
    var written = writeReports(opts, report, err, writer, saved)
    opts.output?.let { path ->
        // the raw bytes as received: byte-exact for binary bodies; text keeps its original charset (no transcoding)
        val body = (outcomes.firstOrNull { it.request === selected[0] } as? RunOutcome.Success)?.response?.body
        if (body != null && !writeOutput(outputWriter, path, body, err, saved)) written = false
    }
    if (!report.passed || saveFailed) {
        if (!written) err.println("Run failed; the report/output file could not be written either.")
        return 1
    }
    if (!written) err.println("Run passed; only the report/output file could not be written.")
    return if (written) 0 else 3
}

/** `3 requests, 2 passed, 1 failed in 1.2s`: prerequisites count, as in the report; a request passes when it completed and its assertions held. */
internal fun summary(report: RunReport, elapsedMillis: Long): String {
    val n = report.requests
    return "$n ${if (n == 1) "request" else "requests"}, ${report.passedRequests} passed, ${report.failedRequests} failed in " +
        String.format(java.util.Locale.ROOT, "%.1fs", elapsedMillis / 1000.0)
}

/** Writes the requested reports independently; returns false when any of them failed (each failure is printed). */
private fun writeReports(opts: Options, report: RunReport, err: PrintStream, writer: ReportWriter, saved: List<Path> = emptyList()): Boolean {
    val suite = opts.file.fileName?.toString() ?: "httpc"
    val json = opts.reportJson?.let { writeFile(writer, it, "report", err, saved) { JsonRunReport.render(report) } } ?: true
    val junit = opts.reportJunit?.let { writeFile(writer, it, "report", err, saved) { JUnitXmlReport.render(report, suite) } } ?: true
    return json && junit
}

private fun writeFile(writer: ReportWriter, path: Path, what: String, err: PrintStream, saved: List<Path> = emptyList(), content: () -> String): Boolean = try {
    refuseSavedResponse(path, saved)
    writer.write(path, content())
    true
} catch (e: Exception) {
    err.println("Error: Cannot write $what $path: ${e.message ?: e}")
    false
}

private fun refuseSavedResponse(path: Path, saved: List<Path>) {
    if (saved.any { samePath(path, it) }) throw java.io.IOException("it is a response saved by '>>' / '>>!' in this run, which is kept")
}

private fun writeOutput(writer: OutputWriter, path: Path, bytes: ByteArray, err: PrintStream, saved: List<Path>): Boolean = try {
    refuseSavedResponse(path, saved)
    writer.write(path, bytes)
    true
} catch (e: Exception) {
    err.println("Error: Cannot write output $path: ${e.message ?: e}")
    false
}

/** Fails with exit 1 and, when reports were requested, writes a report holding the single error. */
private fun failWithReport(opts: Options, err: PrintStream, writer: ReportWriter, message: String): Int {
    val entry = ReportEntry("httpc", "-", opts.file.toString(), null, null, emptyList(), 0, message)
    val code = fail(err, message)
    writeReports(opts, RunReport(listOf(entry)), err, writer)
    return code
}

private fun fail(err: PrintStream, message: String): Int {
    err.println("Error: $message")
    return 1
}
