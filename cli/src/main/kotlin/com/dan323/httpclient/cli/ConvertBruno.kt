package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.BrunoCollections
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val CONVERT_USAGE = """       httpc convert-bruno <collection-dir> --out <new-dir>

  convert-bruno    write a Bruno collection (a folder with bruno.json) as .http files plus http-client.env.json
                   into <new-dir>, which must not exist or must be empty; nothing is sent. Parts that cannot be
                   converted (scripts, tests, other authentication, folder settings) are listed on stderr, never
                   skipped silently; exit 1 when the folder is not a collection or cannot be read or written."""

/** `httpc convert-bruno DIR --out DIR`: returns the exit code (0 converted, 1 failed, 2 bad usage). */
internal fun convertBruno(args: List<String>, out: PrintStream, err: PrintStream): Int {
    val usage = { err.println("usage:\n$CONVERT_USAGE"); 2 }
    var source: String? = null
    var target: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--out" -> { if (target != null) return usage(); target = args.getOrNull(++i)?.takeIf { it.isNotEmpty() && !it.startsWith("--") } ?: return usage() }
            else -> if (a.startsWith("--") || source != null || a.isEmpty()) return usage() else source = a
        }
        i++
    }
    if (source == null || target == null) return usage()
    return try {
        val result = BrunoCollections.convert(Path.of(source), Path.of(target))
        result.warnings.forEach { err.println("warning: $it") }
        result.files.forEach { out.println("wrote ${Path.of(target).resolve(it)}") }
        err.println("${result.files.size} ${if (result.files.size == 1) "file" else "files"} written, ${result.warnings.size} ${if (result.warnings.size == 1) "warning" else "warnings"}")
        0
    } catch (e: java.io.IOException) {
        err.println("Error: ${e.message}")
        1
    } catch (e: InvalidPathException) {
        err.println("Error: invalid path")
        1
    }
}
