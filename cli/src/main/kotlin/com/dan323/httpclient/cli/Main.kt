package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.UndefinedVariableException
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Files
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
    val opts = parseArgs(args.toList()) ?: run { System.err.println(USAGE); exitProcess(2) }
    if (!Files.isRegularFile(opts.file)) fail("No such file: ${opts.file}")

    val dir = opts.file.toAbsolutePath().parent
    val requests = HttpFileParser().parse(Files.readString(opts.file)).requests

    if (opts.list) {
        requests.forEachIndexed { i, r -> println("${i + 1}. ${r.name ?: "-"}  ${r.method} ${r.url}") }
        return
    }

    val variables = if (opts.env == null) emptyMap() else {
        JsonEnvironmentRepository(dir).load().firstOrNull { it.name == opts.env }?.variables
            ?: fail("Unknown environment: ${opts.env}")
    }
    val chosen = select(requests, opts.request) ?: fail("No such request: ${opts.request}")
    val runner = RequestRunner(JdkHttpExecutor(), LocalFileReader(dir))

    var failed = false
    for (r in chosen) {
        println("### ${r.name ?: "${r.method} ${r.url}"}")
        try {
            val resp = runner.run(r, variables)
            println("HTTP ${resp.statusCode} (${resp.durationMillis} ms)")
            resp.headers.forEach { println("${it.name}: ${it.value}") }
            println()
            println(resp.bodyAsString())
        } catch (e: UndefinedVariableException) {
            System.err.println("Error: ${e.message}"); failed = true
        } catch (e: Exception) {
            System.err.println("Error: ${e.message ?: e::class.simpleName}"); failed = true
        }
    }
    if (failed) exitProcess(1)
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    exitProcess(1)
}
