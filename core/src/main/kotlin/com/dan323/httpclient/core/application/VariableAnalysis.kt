package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpFile
import com.dan323.httpclient.core.domain.RequestTemplate

enum class Severity { ERROR, WARNING }

/**
 * A span of the source text. [offset] and [length] count UTF-16 chars (what the IDE and Compose text fields use);
 * [line] and [column] are 0-based and locate the start (a tab counts as one column).
 */
data class SourceRange(val offset: Int, val length: Int, val line: Int, val column: Int) {
    val end: Int get() = offset + length
    operator fun contains(position: Int) = position in offset until end
}

/** A problem found by [VariableAnalysis]. [message] names variables but never contains a variable value. */
data class Diagnostic(val code: String, val severity: Severity, val message: String, val range: SourceRange)

/** Stable diagnostic codes. */
object DiagnosticCodes {
    const val PARSE_ERROR = "parse-error"
    const val UNDEFINED_VARIABLE = "undefined-variable"
    const val UNKNOWN_REQUEST = "unknown-request"
    const val REQUEST_NOT_RUN_YET = "request-not-run-yet"
    const val SELF_REFERENCE = "self-reference"
    const val MALFORMED_REFERENCE = "malformed-reference"
    const val INVALID_DYNAMIC_VARIABLE = "invalid-dynamic-variable"
    const val CIRCULAR_VARIABLE = "circular-variable"
    const val UNTERMINATED_REFERENCE = "unterminated-reference"
    const val INVALID_REFERENCE_TEXT = "invalid-reference-text"
    const val DUPLICATE_REQUEST_NAME = "duplicate-request-name"
    const val VARIABLE_DEFINED_LATER = "variable-defined-later"
}

enum class VariableKind { ENVIRONMENT, FILE, REQUEST_RESPONSE, DYNAMIC }

/**
 * A name usable inside `{{ }}`. For [VariableKind.REQUEST_RESPONSE] the name is the prefix `login.response.`
 * (the part after it is `status`, `headers.Name` or `body...`); for dynamic variables it is a template such as
 * `$random.integer(from, to)` or `$env.NAME`.
 */
data class KnownVariable(val name: String, val kind: VariableKind)

enum class OccurrenceKind {
    /** `{{x}}` where x is an environment or file variable (or undefined). */
    VARIABLE_USE,
    /** The `x` of `@x = value`. */
    FILE_VARIABLE_DEFINITION,
    /** The `x` of `# @name x`. */
    REQUEST_DECLARATION,
    /** The `x` of `{{x.response...}}`. */
    REQUEST_REFERENCE,
}

/** [range] covers exactly the name, so it can be replaced for a rename. */
data class Occurrence(val name: String, val kind: OccurrenceKind, val range: SourceRange)

/**
 * Pure, IO-free analysis of the `{{variables}}` of a `.http` text, for editor intelligence. It follows the run-time
 * rules of [VariableResolver] (same reference pattern and lookup order) and [RequestSession] (a response reference
 * names the request before the first `.response.`; it needs an earlier request with that name).
 *
 * What is scanned is what is resolved at run time: the request line and query continuation lines, header values,
 * body text, the path of a `< path` body (not the file's contents), the path of a `>> path` / `>>! path` save, `@x = value` values, `# @assert` expressions
 * (without a trailing ` # comment`) and the values of the settings that are resolved (`timeout`, `no-redirect`,
 * `no-cookie-jar`, `proxy`). Comments, header names, `# @name` and trailing response handlers are not.
 *
 * Limits: whether a response path (`body.$.x`) finds anything, or whether a `$env.X` variable is allowed and set,
 * is only known at run time, so nothing is flagged for them. A file variable's value is checked against the
 * variables defined anywhere in the file (a value that uses a later variable gets a warning, since only requests
 * after that definition can use it), and cycles are detected on the final value of each name.
 *
 * Environment variables are given by name only; values never enter the analysis. If no environment is chosen
 * pass an empty collection (every non-file variable is then undefined, as at run time), or the union of all
 * environments' names for a lenient editor.
 */
class VariableAnalysis private constructor(
    private val lines: LineTable,
    private val environment: Set<String>,
    private val requests: List<RequestTemplate>,
    private val definitions: List<Definition>,
    private val blockStarts: IntArray,
    /** Source line to the index of the request of that block, or the number of requests before a request-less block. */
    private val blockRequestIndex: IntArray,
    val diagnostics: List<Diagnostic>,
    /** Every occurrence, sorted by offset. */
    val occurrences: List<Occurrence>,
) {
    private val byName: Map<String, List<Occurrence>> = occurrences.groupBy { it.name }

    /** All occurrences of [name] (variable uses and definitions, request declarations and references). */
    fun findUsages(name: String): List<Occurrence> = byName[name].orEmpty()

    /** Occurrences of the variable [name]: uses and `@name =` definitions. */
    fun findVariableUsages(name: String): List<Occurrence> =
        findUsages(name).filter { it.kind == OccurrenceKind.VARIABLE_USE || it.kind == OccurrenceKind.FILE_VARIABLE_DEFINITION }

    /** Occurrences of the request [name]: its `# @name` and the `{{name.response...}}` references. */
    fun findRequestUsages(name: String): List<Occurrence> =
        findUsages(name).filter { it.kind == OccurrenceKind.REQUEST_DECLARATION || it.kind == OccurrenceKind.REQUEST_REFERENCE }

    /** The occurrence whose name contains [offset] (also right after its last char), or null. */
    fun occurrenceAt(offset: Int): Occurrence? {
        var lo = 0
        var hi = occurrences.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val o = occurrences[mid]
            when {
                offset < o.range.offset -> hi = mid - 1
                offset > o.range.end -> lo = mid + 1
                else -> return o
            }
        }
        return null
    }

    /**
     * The names that can be used in `{{ }}` at [offset]: file variables defined on earlier lines (inside the value of
     * `@x = ...` all of the others), environment variables, the response prefixes of the requests before the
     * one containing [offset], and the dynamic variables. Each name appears once; a file variable hides the
     * environment variable of the same name.
     */
    fun knownVariablesAt(offset: Int): List<KnownVariable> {
        val line = lines.lineOf(offset.coerceIn(0, lines.length))
        val result = LinkedHashMap<String, KnownVariable>()
        val inDefinition = definitions.firstOrNull { it.line == line }
        for (d in definitions) {
            val visible = if (inDefinition != null) d.name != inDefinition.name else d.line < line
            if (visible) result.putIfAbsent(d.name, KnownVariable(d.name, VariableKind.FILE))
        }
        environment.sorted().forEach { result.putIfAbsent(it, KnownVariable(it, VariableKind.ENVIRONMENT)) }
        val block = blockStarts.binarySearch(line).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val before = blockRequestIndex[block]
        for (i in 0 until before) {
            val name = requests[i].name ?: continue
            result.putIfAbsent("$name.response.", KnownVariable("$name.response.", VariableKind.REQUEST_RESPONSE))
        }
        DYNAMIC_TEMPLATES.forEach { result.putIfAbsent(it, KnownVariable(it, VariableKind.DYNAMIC)) }
        return result.values.toList()
    }

    internal class Definition(val name: String, val line: Int)

    companion object {
        /** The dynamic variables of [DynamicVariables] as templates; `$env.NAME` and `$processEnv NAME` need an allow-list. */
        val DYNAMIC_TEMPLATES: List<String> = listOf(
            "\$uuid", "\$timestamp", "\$isoTimestamp", "\$randomInt",
            "\$random.uuid", "\$random.email", "\$random.integer(from, to)", "\$random.float(from, to)",
            "\$random.alphabetic(n)", "\$random.alphanumeric(n)", "\$random.hexadecimal(n)",
            "\$env.NAME", "\$processEnv NAME",
        )

        /** Most lines (or blocks) given up before the whole file is reported as one parse error. */
        private const val MAX_REPAIRS = 50

        /**
         * Analyses [text]. [file] is the parsed form of [text] when the caller already has it; otherwise it is parsed
         * here. A malformed file does not blank the analysis: each line the parser rejects is reported as a
         * [DiagnosticCodes.PARSE_ERROR] (once per `###` block) and left out, and the rest of the file is analysed with
         * the same offsets, so completion, find-usages and diagnostics keep working while a request is half typed.
         * The request that failed to parse is not in the analysis (it has no `name.response.` prefix, and its
         * references are not scanned). Only a file with more than [MAX_REPAIRS] bad lines is reported as parse errors alone.
         */
        fun analyze(text: String, environmentVariables: Collection<String> = emptySet(), file: HttpFile? = null): VariableAnalysis {
            val environment = environmentVariables.toHashSet()
            if (file != null) return Analyzer(text, LineTable(text), environment, file, emptyList()).run()
            val original = LineTable(text)
            val errors = ArrayList<Diagnostic>()
            val reported = HashSet<Int>()
            var source = text
            var repairs = 0
            while (true) {
                val table = LineTable(source)
                try {
                    return Analyzer(source, table, environment, HttpFileParser().parse(source), errors).run()
                } catch (e: HttpParseException) {
                    val line = (e.line - 1).coerceIn(0, table.count - 1)
                    val block = table.blockOf(line)
                    if (reported.add(block)) {
                        val range = SourceRange(original.start(line), original.end(line) - original.start(line), line, 0)
                        errors += Diagnostic(DiagnosticCodes.PARSE_ERROR, Severity.ERROR, e.message ?: "parse error", range)
                    }
                    if (++repairs > MAX_REPAIRS) {
                        return VariableAnalysis(
                            original, environment, emptyList(), emptyList(), intArrayOf(0), intArrayOf(0),
                            errors.sortedBy { it.range.offset }, emptyList(),
                        )
                    }
                    // A blank line cannot be the cause on its own: drop the whole block instead.
                    source = if (table.text(line).isBlank()) table.blank(source, table.blockStart(block), table.blockEnd(block))
                    else table.blank(source, line, line + 1)
                }
            }
        }
    }

    /** Line starts of a text split like `String.lines()` (`\n`, `\r\n` and `\r`). */
    internal class LineTable(private val text: String) {
        private val starts: IntArray
        private val ends: IntArray
        val length = text.length
        val count: Int get() = starts.size

        init {
            val s = ArrayList<Int>()
            val e = ArrayList<Int>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') {
                    s += start; e += i
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    start = i + 1
                }
                i++
            }
            s += start; e += text.length
            starts = s.toIntArray(); ends = e.toIntArray()
        }

        fun start(line: Int) = starts[line]
        fun end(line: Int) = ends[line]
        fun text(line: Int): String = text.substring(starts[line], ends[line])

        private val separators: List<Int> by lazy { (0 until count).filter { text(it).startsWith("###") } }

        /** The `###` block a line belongs to (the separator line itself belongs to the block above it). */
        fun blockOf(line: Int): Int = separators.count { it < line }
        fun blockStart(block: Int): Int = if (block == 0) 0 else separators[block - 1] + 1
        fun blockEnd(block: Int): Int = if (block < separators.size) separators[block] else count

        /** [source] with the text of lines [from] until [to] replaced by spaces; offsets and line breaks stay. */
        fun blank(source: String, from: Int, to: Int): String {
            val sb = StringBuilder(source)
            for (l in from until to) for (i in starts[l] until ends[l]) sb.setCharAt(i, ' ')
            return sb.toString()
        }

        fun lineOf(offset: Int): Int {
            val i = starts.binarySearch(offset)
            return if (i >= 0) i else -i - 2
        }

        fun range(offset: Int, length: Int): SourceRange {
            val line = lineOf(offset)
            return SourceRange(offset, length, line, offset - starts[line])
        }
    }

    private sealed interface Context
    /** Text of the request with this index in the file. */
    private class InRequest(val index: Int, val request: RequestTemplate) : Context
    /** The value of file variable [owner]. */
    private class InValue(val owner: String) : Context {
        /** True for the last definition of the name: the value requests at the end of the file see. */
        var isFinal = false
    }
    private class Segment(val offset: Int, val text: String, val context: Context)
    private class Edge(val from: String, val to: String, val range: SourceRange)

    private class Analyzer(
        private val text: String,
        private val lines: LineTable,
        private val environment: Set<String>,
        private val file: HttpFile,
        parseErrors: List<Diagnostic>,
    ) {
        private val requests = file.requests
        private val segments = ArrayList<Segment>()
        private val diagnostics = ArrayList<Diagnostic>(parseErrors)
        private val occurrences = ArrayList<Occurrence>()
        private val definitions = ArrayList<Definition>()
        private val edges = ArrayList<Edge>()
        private val position = file.variables.keys.withIndex().associate { it.value to it.index }
        private val firstIndexOfName = HashMap<String, Int>()
        private val dynamicChecks = HashMap<String, String>()
        private val parser = HttpFileParser()
        private val settingTag = Regex("""^(?:#|//)\s*@([A-Za-z][\w-]*)(?:\s+(.*))?$""")
        private val nameTag = Regex("""^(?:#|//)\s*@name\s+(\S+)""")

        fun run(): VariableAnalysis {
            requests.forEachIndexed { i, r -> r.name?.let { firstIndexOfName.putIfAbsent(it, i) } }
            val startsAtLine = requests.withIndex().associate { it.value.startLine to it.index }
            val blockStarts = ArrayList<Int>().apply { add(0) }
            for (l in 0 until lines.count) if (lines.text(l).startsWith("###")) blockStarts += l + 1
            val blockRequest = IntArray(blockStarts.size)
            var seen = 0
            for ((b, start) in blockStarts.withIndex()) {
                val end = if (b + 1 < blockStarts.size) blockStarts[b + 1] - 1 else lines.count
                val idx = startsAtLine[start]
                blockRequest[b] = idx ?: seen
                if (idx != null) seen = idx + 1
                collect(start, end, idx?.let { InRequest(it, requests[it]) })
            }
            duplicateNames()
            val lastByName = HashMap<String, InValue>()
            valueContexts.forEach { lastByName[it.owner] = it }
            valueContexts.forEach { it.isFinal = lastByName[it.owner] === it }
            segments.forEach { scan(it) }
            cycles()
            diagnostics.sortWith(compareBy({ it.range.offset }, { it.code }))
            occurrences.sortBy { it.range.offset }
            return VariableAnalysis(
                lines, environment, requests, definitions, blockStarts.toIntArray(), blockRequest, diagnostics, occurrences,
            )
        }

        // ---- pass 1: find the resolvable regions of each block, mirroring HttpFileParser.parseBlock ----

        private fun segment(offset: Int, value: String, ctx: Context) {
            if (value.contains("{{")) segments += Segment(offset, value, ctx)
        }

        private fun collect(from: Int, to: Int, owner: InRequest?) {
            var i = from
            var declared: Pair<Int, MatchResult>? = null
            val pending = ArrayList<Segment>()
            while (i < to) {
                val raw = lines.text(i)
                val t = raw.trim()
                if (t.isEmpty()) { i++; continue }
                val lead = raw.length - raw.trimStart().length
                val base = lines.start(i) + lead
                val fv = HttpLineSyntax.fileVariable(t)
                if (fv != null) {
                    val name = fv.groupValues[1]
                    val g = fv.groups[1]!!
                    occurrences += Occurrence(name, OccurrenceKind.FILE_VARIABLE_DEFINITION, lines.range(base + g.range.first, name.length))
                    definitions += Definition(name, i)
                    val v = fv.groups[2]!!
                    val ctx = InValue(name)
                    valueContexts += ctx
                    segment(base + v.range.first, v.value, ctx)
                    i++; continue
                }
                if (HttpLineSyntax.isComment(t)) {
                    nameTag.find(t)?.let { declared = i to it }
                    settingTag.matchEntire(t)?.let { m ->
                        val key = m.groupValues[1]
                        val g = m.groups[2]
                        if (g != null && owner != null) {
                            if (key == "assert") {
                                val expr = parser.stripTrailingComment(g.value)
                                if (expr.contains("{{")) pending += Segment(base + g.range.first, expr, owner)
                            } else if (key.lowercase() in RESOLVED_SETTINGS) {
                                val v = g.value.trim()
                                if (v.contains("{{")) pending += Segment(base + g.range.first, v, owner)
                            }
                        }
                    }
                    i++; continue
                }
                break
            }
            declared?.let { (line, m) ->
                val g = m.groups[1]!!
                val lead = lines.text(line).let { it.length - it.trimStart().length }
                val name = g.value
                val offset = lines.start(line) + lead + g.range.first
                occurrences += Occurrence(name, OccurrenceKind.REQUEST_DECLARATION, lines.range(offset, name.length))
                declarations += name to lines.range(offset, name.length)
            }
            if (owner == null || i >= to) return
            segments += pending

            fun scanLine(l: Int, fromColumn: Int = 0) {
                val raw = lines.text(l)
                segment(lines.start(l) + fromColumn, raw.substring(fromColumn), owner)
            }
            fun leadOf(l: Int) = lines.text(l).let { it.length - it.trimStart().length }

            scanLine(i, leadOf(i))
            i++
            while (i < to && HttpLineSyntax.isQueryContinuation(lines.text(i).trim())) { scanLine(i, leadOf(i)); i++ }
            var headers = 0
            while (i < to && lines.text(i).isNotBlank()) {
                val raw = lines.text(i)
                val t = raw.trim()
                if (HttpLineSyntax.isResponseHandler(t) || HttpLineSyntax.isResponseRedirect(t)) break
                if (!HttpLineSyntax.isComment(t)) {
                    val idx = t.indexOf(':')
                    if (idx < 0 && raw.first().isWhitespace() && headers > 0) {
                        scanLine(i, leadOf(i))
                    } else if (idx > 0) {
                        scanLine(i, leadOf(i) + idx + 1)
                        headers++
                    }
                }
                i++
            }
            val bodyFrom = if (i < to && lines.text(i).isNotBlank()) i else i + 1
            val handler = BooleanArray(maxOf(0, to - bodyFrom))
            var inScript = false
            for (j in bodyFrom until to) {
                val line = lines.text(j)
                val t = line.trim()
                when {
                    inScript -> { handler[j - bodyFrom] = true; if (HttpLineSyntax.endsScript(line)) inScript = false }
                    HttpLineSyntax.startsScript(t) -> {
                        handler[j - bodyFrom] = true
                        if (!HttpLineSyntax.endsScript(t.substring(t.indexOf("{%") + 2))) inScript = true
                    }
                    HttpLineSyntax.isResponseHandler(t) || HttpLineSyntax.isResponseRedirect(t) -> handler[j - bodyFrom] = true
                }
            }
            var lastBody = -1
            for (j in bodyFrom until to) if (!handler[j - bodyFrom] && lines.text(j).isNotBlank()) lastBody = j
            // `>> path` / `>>! path` after the last body line: the path is resolved when the response is saved.
            for (j in bodyFrom until to) {
                if (j <= lastBody) continue
                val raw = lines.text(j)
                val save = HttpLineSyntax.responseSave(raw.trim()) ?: continue
                if (save.path.isEmpty()) continue
                val marker = leadOf(j) + if (save.overwrite) 3 else 2
                segment(lines.start(j) + raw.indexOf(save.path, marker), save.path, owner)
            }
            if (lastBody < 0) return
            val first = lines.text(bodyFrom).trim()
            if (lastBody == bodyFrom && HttpLineSyntax.isFileReference(first)) {
                // The path is resolved, the file's contents are not.
                val afterMarker = first.removePrefix("<").removePrefix("@")
                val skip = first.length - afterMarker.length + (afterMarker.length - afterMarker.trimStart().length)
                scanLine(bodyFrom, leadOf(bodyFrom) + skip)
            } else {
                val start = lines.start(bodyFrom)
                segment(start, text.substring(start, lines.end(lastBody)), owner)
            }
        }

        private val declarations = ArrayList<Pair<String, SourceRange>>()
        private val valueContexts = ArrayList<InValue>()

        private fun duplicateNames() {
            val seenAt = HashMap<String, Int>()
            for ((name, range) in declarations) {
                val first = seenAt.putIfAbsent(name, range.line)
                if (first != null) {
                    add(
                        DiagnosticCodes.DUPLICATE_REQUEST_NAME, Severity.WARNING,
                        "Request name '$name' is already used on line ${first + 1}; {{$name.response...}} reads the latest response",
                        range,
                    )
                }
            }
        }

        // ---- pass 2: classify every reference ----

        private fun add(code: String, severity: Severity, message: String, range: SourceRange) {
            diagnostics += Diagnostic(code, severity, message, range)
        }

        private fun scan(seg: Segment) {
            val body = seg.text
            val owner = (seg.context as? InValue)?.owner
            val found = VariableResolver.pattern.findAll(body).toList()
            found.forEach { reference(seg, it, seg.context, owner) }
            // A `{{` that is neither inside a reference nor directly before one starts nothing at run time.
            val lastClose = body.lastIndexOf("}}")
            var mi = 0
            var open = body.indexOf("{{")
            while (open >= 0) {
                while (mi < found.size && found[mi].range.last < open) mi++
                val covered = mi < found.size && found[mi].range.first <= open
                val precedesReference = mi < found.size && found[mi].range.first == open + 1
                if (!covered && !precedesReference) {
                    val r = lines.range(seg.offset + open, 2)
                    if (lastClose < open + 2) add(DiagnosticCodes.UNTERMINATED_REFERENCE, Severity.WARNING, "Unterminated '{{': no closing '}}'", r)
                    else add(DiagnosticCodes.INVALID_REFERENCE_TEXT, Severity.WARNING, "'{{' does not start a valid variable reference and is sent as written", r)
                }
                open = body.indexOf("{{", open + 1)
            }
        }

        private fun reference(seg: Segment, m: MatchResult, ctx: Context, owner: String?) {
            val name = VariableResolver.nameOf(m)
            val g = m.groups[1]!!
            val nameRange = lines.range(seg.offset + g.range.first, name.length)
            val isFile = name in file.variables

            // 1. file variable
            if (ctx is InRequest) {
                if (name in ctx.request.fileVariables) { use(name, nameRange); return }
            } else if (isFile && LookupOrder.fileBeforeEnvironment(name, owner) { position.getValue(it) }) {
                use(name, nameRange); edge(owner!!, name, nameRange, ctx as InValue); return
            }
            // 2. environment
            if (name in environment) { use(name, nameRange); return }
            // 3. a file variable defined after the owner
            if (ctx is InValue && isFile && LookupOrder.fileAfterEnvironment(name, owner)) {
                use(name, nameRange)
                edge(owner!!, name, nameRange, ctx)
                add(
                    DiagnosticCodes.VARIABLE_DEFINED_LATER, Severity.WARNING,
                    "Variable '$name' is defined below this line; only requests after its definition can use '${owner}'",
                    nameRange,
                )
                return
            }
            // 4. response of a request
            val split = name.indexOf(RESPONSE)
            if (split > 0) { responseReference(name, split, nameRange, ctx); return }
            // 5. dynamic variable
            if (name.startsWith("$")) { dynamicVariable(name, nameRange); return }
            if (name.endsWith(".response") && name.length > ".response".length) {
                add(DiagnosticCodes.MALFORMED_REFERENCE, Severity.ERROR, "Malformed response reference '$name': expected '${name}.status', '${name}.headers.<Name>' or '${name}.body...'", nameRange)
                return
            }
            use(name, nameRange)
            add(DiagnosticCodes.UNDEFINED_VARIABLE, Severity.ERROR, "Undefined variable: $name", nameRange)
        }

        private fun use(name: String, range: SourceRange) {
            occurrences += Occurrence(name, OccurrenceKind.VARIABLE_USE, range)
        }

        private fun edge(from: String, to: String, range: SourceRange, ctx: InValue) {
            if (ctx.isFinal && from != to) edges += Edge(from, to, range)
        }

        private fun responseReference(name: String, split: Int, range: SourceRange, ctx: Context) {
            val request = name.substring(0, split)
            val part = name.substring(split + RESPONSE.length)
            val prefix = lines.range(range.offset, request.length)
            occurrences += Occurrence(request, OccurrenceKind.REQUEST_REFERENCE, prefix)
            val first = firstIndexOfName[request]
            when {
                first == null -> add(DiagnosticCodes.UNKNOWN_REQUEST, Severity.ERROR, "Unknown request '$request': no request in this file has that @name", prefix)
                ctx is InRequest && first == ctx.index ->
                    add(DiagnosticCodes.SELF_REFERENCE, Severity.WARNING, "Request '$request' cannot read its own response the first time it runs (it can on a later run in the same session)", prefix)
                ctx is InRequest && first > ctx.index ->
                    add(DiagnosticCodes.REQUEST_NOT_RUN_YET, Severity.WARNING, "Request '$request' comes later in the file, so its response is only available here if it already ran in this session", prefix)
            }
            val problem = partProblem(part) ?: return
            add(DiagnosticCodes.MALFORMED_REFERENCE, Severity.ERROR, problem, lines.range(range.offset + split + 1, name.length - split - 1))
        }

        private fun partProblem(part: String): String? = when {
            part.isEmpty() -> "Malformed response reference: nothing after '.response.' (expected status, headers.<Name> or body...)"
            part == "status" || part == "body.*" -> null
            part.startsWith("headers.") -> if (part.length == "headers.".length) "Malformed response reference: missing header name after 'headers.'" else null
            part.startsWith("body.") -> if (part.length == "body.".length) "Malformed response reference: missing expression after 'body.' (use body.*, a JSONPath, an XPath or regex:...)" else null
            else -> "Unknown response part '${part.take(40)}' (expected status, headers.<Name> or body...)"
        }

        private fun dynamicVariable(name: String, range: SourceRange) {
            val head = name.takeWhile { !it.isWhitespace() && it != '(' }
            if (head.startsWith("\$env.") || head == "\$processEnv") return // allow-list and value are run-time matters
            val problem = dynamicChecks.getOrPut(name) {
                try {
                    if (DynamicVariables.DEFAULT.evaluate(name) == null) UNKNOWN else ""
                } catch (e: InvalidDynamicVariableException) {
                    e.message ?: "Invalid dynamic variable"
                }
            }
            when (problem) {
                "" -> {}
                UNKNOWN -> add(DiagnosticCodes.UNDEFINED_VARIABLE, Severity.ERROR, "Undefined variable: $name", range)
                else -> add(DiagnosticCodes.INVALID_DYNAMIC_VARIABLE, Severity.ERROR, problem, range)
            }
        }

        // ---- cycles between file variables, using the same lookup order as the resolver ----

        private fun cycles() {
            val graph = HashMap<String, MutableList<Edge>>()
            edges.forEach { graph.getOrPut(it.from) { ArrayList() } += it }
            val state = HashMap<String, Int>() // 1 on the path, 2 finished
            val reported = HashSet<Int>()
            for (root in graph.keys) {
                if (state.containsKey(root)) continue
                val path = ArrayList<Edge>()
                val stack = ArrayList<Pair<String, Iterator<Edge>>>()
                state[root] = 1
                stack += root to graph.getValue(root).iterator()
                while (stack.isNotEmpty()) {
                    val (node, it) = stack.last()
                    if (!it.hasNext()) { state[node] = 2; stack.removeAt(stack.size - 1); if (path.isNotEmpty()) path.removeAt(path.size - 1); continue }
                    val e = it.next()
                    when (state[e.to]) {
                        1 -> {
                            val startAt = path.indexOfFirst { p -> p.from == e.to }.let { i -> if (i < 0) path.size else i }
                            val cycle = path.subList(startAt, path.size) + e
                            val names = cycle.map { c -> c.from } + e.to
                            val message = "Circular variable reference: ${names.joinToString(" -> ")}"
                            for (c in cycle) if (reported.add(c.range.offset)) add(DiagnosticCodes.CIRCULAR_VARIABLE, Severity.ERROR, message, c.range)
                        }
                        null -> {
                            state[e.to] = 1
                            path += e
                            stack += e.to to (graph[e.to]?.iterator() ?: emptyList<Edge>().iterator())
                        }
                    }
                }
            }
        }
    }
}

private const val RESPONSE = ".response."
private const val UNKNOWN = "\u0000unknown"
private val RESOLVED_SETTINGS = setOf("timeout", "no-redirect", "no-cookie-jar", "proxy")
