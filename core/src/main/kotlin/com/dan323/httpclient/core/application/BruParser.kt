package com.dan323.httpclient.core.application

/** One `name { ... }` (or `name [ ... ]`) block of a Bruno `.bru` file; [lines] are its content without the 2-space indent. */
data class BruBlock(val name: String, val lines: List<String>) {
    /** One `key: value` line of a dictionary block; [enabled] is false for a `~key: value` line. */
    data class Entry(val key: String, val value: String, val enabled: Boolean)

    /** The `key: value` lines (blank lines and lines without a colon are left out). */
    fun entries(): List<Entry> = lines.mapNotNull { raw ->
        val line = raw.trim()
        val enabled = !line.startsWith("~")
        val body = line.removePrefix("~")
        val colon = body.indexOf(':')
        if (colon <= 0) null else Entry(body.substring(0, colon).trim(), body.substring(colon + 1).trim(), enabled)
    }

    /** The content as one text, lines joined by `\n`. */
    fun text(): String = lines.joinToString("\n")
}

/**
 * Splits the text of a Bruno `.bru` file into its blocks. A block starts with `name {` (or `name [`) at column 0 and
 * ends at the first line that is exactly `}` (or `]`). Content is not interpreted here, so a script or a JSON body
 * is kept as it is. Text outside blocks is ignored. An unterminated block runs to the end of the file and is reported
 * in [BruFile.problems].
 */
object BruParser {
    private val start = Regex("""^([A-Za-z][A-Za-z0-9:_-]*)\s*([{\[])\s*$""")

    fun parse(text: String): BruFile {
        val blocks = mutableListOf<BruBlock>()
        val problems = mutableListOf<String>()
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val open = start.find(lines[i])
            if (open == null) { i++; continue }
            val close = if (open.groupValues[2] == "{") "}" else "]"
            val first = i + 1
            var j = first
            while (j < lines.size && lines[j] != close) j++
            if (j == lines.size) problems += "line ${i + 1}: block '${open.groupValues[1]}' is not closed"
            blocks += BruBlock(open.groupValues[1], lines.subList(first, j).map { it.removePrefix("  ") })
            i = j + 1
        }
        return BruFile(blocks, problems)
    }
}

/** The blocks of a `.bru` file in order; [problems] are structural problems (each prefixed with its 1-based line). */
data class BruFile(val blocks: List<BruBlock>, val problems: List<String> = emptyList()) {
    fun block(name: String): BruBlock? = blocks.firstOrNull { it.name == name }
}
