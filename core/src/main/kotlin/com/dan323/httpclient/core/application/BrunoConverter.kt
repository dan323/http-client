package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.Part
import com.dan323.httpclient.core.domain.PartContent
import com.dan323.httpclient.core.domain.RequestTemplate

/**
 * A converted Bruno request. [warnings] name everything that could not be carried over (scripts, tests, other
 * authentication modes, ...): nothing is dropped silently.
 */
data class BrunoRequest(val template: RequestTemplate, val sequence: Int, val warnings: List<String>)

/** An environment from `an environment file`; [secrets] are the names listed in `vars:secret` (Bruno keeps their values outside the file). */
data class BrunoEnvironment(val environment: Environment, val secrets: List<String>)

/** Converts the text of Bruno `.bru` files into [RequestTemplate]s and [Environment]s. Pure text work, no I/O. */
object BrunoConverter {
    private val methods = setOf("get", "post", "put", "delete", "patch", "options", "head", "connect", "trace")
    private val processEnv = Regex("""\{\{\s*process\.env\.([A-Za-z0-9_.-]+)\s*}}""")
    private const val BOUNDARY = "BrunoBoundary"

    /** Blocks that carry nothing a request needs, so ignoring them is not worth a warning. */
    private val quiet = setOf("meta", "docs", "settings", "params:query", "params:path", "headers", "assert") +
        setOf("body:json", "body:text", "body:xml", "body:form-urlencoded", "body:multipart-form", "auth:basic", "auth:bearer")

    /**
     * [fallbackName] names a request without `meta { name }` (usually its file name without `.bru`).
     * [rootPrefix] is put before the relative file paths of multipart parts: Bruno resolves them from the collection
     * root, a `.http` file from its own folder, so a request converted into a subfolder needs `../` for each level.
     */
    fun request(text: String, fallbackName: String, rootPrefix: String = ""): BrunoRequest {
        val file = BruParser.parse(text)
        val warnings = file.problems.map { "malformed block: $it" }.toMutableList()
        val methodBlock = file.blocks.firstOrNull { it.name in methods }
            ?: return BrunoRequest(RequestTemplate(fallbackName, "GET", ""), 0, warnings + "no request block (get, post, ...) found")
        val meta = file.block("meta")?.entries().orEmpty()
        val name = meta.firstOrNull { it.key == "name" }?.value?.ifBlank { null } ?: fallbackName
        val sequence = meta.firstOrNull { it.key == "seq" }?.value?.toIntOrNull() ?: 0
        val type = meta.firstOrNull { it.key == "type" }?.value
        if (type != null && type != "http") warnings += "request type '$type' is not supported, treated as http"

        val settings = methodBlock.entries().associate { it.key to it.value }
        var url = fix(settings["url"].orEmpty())
        url = withParams(url, file, warnings)

        val headers = file.block("headers")?.entries().orEmpty().filter { it.enabled }.map { Header(it.key, fix(it.value)) }.toMutableList()
        val declared = headers.toList()
        val body = body(settings["body"], file, headers, warnings, rootPrefix).takeUnless { splitsRequest(it, warnings) }
        // a dropped body must not leave the Content-Type that was added for it
        if (body == null) { headers.clear(); headers.addAll(declared) }
        auth(settings["auth"], file, headers, warnings)

        val assertions = assertions(file.block("assert"), warnings)
        for (block in file.blocks) {
            if (block.name in quiet || block.name in methods) continue
            warnings += when {
                block.name.startsWith("script:") || block.name == "tests" -> "block '${block.name}' (JavaScript) is not converted"
                block.name.startsWith("vars:") -> "block '${block.name}' is not converted (set the variables in the environment or as file variables)"
                block.name.startsWith("auth:") -> "authentication '${block.name}' is not converted"
                block.name.startsWith("body:") -> "body '${block.name}' is not converted"
                else -> "block '${block.name}' is not converted"
            }
        }
        val template = RequestTemplate(
            name = name.trim().replace(Regex("\\s+"), "-"),
            method = methodBlock.name.uppercase(),
            url = url,
            headers = headers,
            body = body,
            assertions = assertions,
        )
        return BrunoRequest(template, sequence, warnings)
    }

    /** Parses `environments/NAME.bru`: the `vars` block gives the variables, `vars:secret` the names whose values are not in the file. */
    fun environment(name: String, text: String): BrunoEnvironment {
        val file = BruParser.parse(text)
        val vars = file.block("vars")?.entries().orEmpty().filter { it.enabled }.associate { it.key to fix(it.value) }
        val secrets = file.block("vars:secret")?.lines.orEmpty().map { it.trim().trimEnd(',').trim() }.filter { it.isNotEmpty() && !it.startsWith("~") }
        return BrunoEnvironment(Environment(name, vars), secrets)
    }

    /** `{{process.env.X}}` becomes `{{$processEnv X}}` (which still needs `--allow-env X`); other `{{...}}` text is shared by both formats. */
    private fun fix(text: String) = processEnv.replace(text) { "{{\$processEnv ${it.groupValues[1]}}}" }

    /** Path params (`:id`) are substituted; enabled query params that the URL does not hold yet are appended. */
    private fun withParams(url: String, file: BruFile, warnings: MutableList<String>): String {
        var result = url
        for (p in file.block("params:path")?.entries().orEmpty().filter { it.enabled }) {
            val marker = Regex("(?<=/):" + Regex.escape(p.key) + "(?=[/?#]|$)")
            if (marker.containsMatchIn(result)) result = marker.replace(result) { fix(p.value) }
            else warnings += "path parameter '${p.key}' does not occur in the URL"
        }
        val missing = file.block("params:query")?.entries().orEmpty().filter { it.enabled }
            .filter { !queryHas(result, it.key) }
        if (missing.isNotEmpty()) {
            val extra = missing.joinToString("&") { "${it.key}=${fix(it.value)}" }
            result += (if ('?' in result) "&" else "?") + extra
        }
        return result
    }

    private fun queryHas(url: String, key: String) =
        url.substringAfter('?', "").substringBefore('#').split('&').any { it.substringBefore('=') == key }

    private fun body(kind: String?, file: BruFile, headers: MutableList<Header>, warnings: MutableList<String>, rootPrefix: String): Body? {
        fun ensureType(type: String) {
            if (headers.none { it.name.equals("Content-Type", ignoreCase = true) }) headers += Header("Content-Type", type)
        }
        return when (kind) {
            null, "none" -> null
            "json" -> text(file, "body:json", "application/json", ::ensureType, warnings)
            "text" -> text(file, "body:text", "text/plain", ::ensureType, warnings)
            "xml" -> text(file, "body:xml", "application/xml", ::ensureType, warnings)
            "formUrlEncoded" -> {
                val fields = file.block("body:form-urlencoded")?.entries().orEmpty().filter { it.enabled }
                if (fields.isEmpty()) null else {
                    ensureType("application/x-www-form-urlencoded")
                    Body.Text(fields.joinToString("&") { "${it.key}=${fix(it.value)}" })
                }
            }
            "multipartForm" -> multipart(file, headers, warnings, rootPrefix)
            else -> { warnings += "body type '$kind' is not converted"; null }
        }
    }

    /** A body line starting with `###` would split the request when the `.http` file is read, so such a body is left out with a warning. */
    private fun splitsRequest(body: Body?, warnings: MutableList<String>): Boolean {
        val texts = when (body) {
            is Body.Text -> listOf(body.content)
            is Body.Multipart -> body.parts.mapNotNull { (it.content as? PartContent.Text)?.content }
            else -> emptyList()
        }
        val bad = texts.any { t -> t.lineSequence().any { it.startsWith("###") } }
        if (bad) warnings += "the body has a line starting with ### , which a .http file reads as the start of the next request; the body is not converted"
        return bad
    }

    private fun text(file: BruFile, block: String, type: String, ensureType: (String) -> Unit, warnings: MutableList<String>): Body? {
        val content = file.block(block)?.lines?.joinToString("\n")?.trimEnd()
        if (content.isNullOrEmpty()) { warnings += "body is declared but '$block' is empty or missing"; return null }
        ensureType(type)
        return Body.Text(fix(content))
    }

    private fun multipart(file: BruFile, headers: MutableList<Header>, warnings: MutableList<String>, rootPrefix: String): Body? {
        val fields = file.block("body:multipart-form")?.entries().orEmpty().filter { it.enabled }
        if (fields.isEmpty()) return null
        headers.removeAll { it.name.equals("Content-Type", ignoreCase = true) }
        headers += Header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        val parts = fields.map { f ->
            val value = f.value.substringBefore(" @contentType(").trim()
            val file = Regex("""^@file\((.*)\)$""").find(value)?.groupValues?.get(1)
            if (file != null) {
                val paths = file.split('|')
                if (paths.size > 1) warnings += "multipart field '${f.key}' lists several files; only the first is kept"
                val given = paths.first().trim()
                val absolute = given.startsWith("/") || given.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(given)
                val path = if (absolute) given else rootPrefix + given
                if (!absolute) warnings += "multipart file '$given' is relative to the collection root; the files are not copied, so keep them at the same place relative to the converted requests.http (or pass --allow-file-root)"
                val shown = path.substringAfterLast('/').substringAfterLast('\\')
                Part(listOf(Header("Content-Disposition", "form-data; name=\"${f.key}\"; filename=\"$shown\"")), PartContent.File(path))
            } else {
                Part(listOf(Header("Content-Disposition", "form-data; name=\"${f.key}\"")), PartContent.Text(fix(value)))
            }
        }
        return Body.Multipart(BOUNDARY, parts)
    }

    private fun auth(mode: String?, file: BruFile, headers: MutableList<Header>, warnings: MutableList<String>) {
        when (mode) {
            null, "none", "inherit" -> {}
            "basic" -> {
                val e = file.block("auth:basic")?.entries().orEmpty().associate { it.key to it.value }
                headers += Header("Authorization", "Basic ${fix(e["username"].orEmpty())}:${fix(e["password"].orEmpty())}")
            }
            "bearer" -> {
                val token = file.block("auth:bearer")?.entries().orEmpty().firstOrNull { it.key == "token" }?.value.orEmpty()
                headers += Header("Authorization", "Bearer ${fix(token)}")
            }
            else -> warnings += "authentication '$mode' is not converted"
        }
        if (mode == "inherit") warnings += "authentication is inherited from the collection or folder, which is not converted"
    }

    private val operators = mapOf(
        "eq" to "==", "neq" to "!=", "gt" to ">", "gte" to ">=", "lt" to "<", "lte" to "<=",
        "contains" to "contains", "notContains" to "!contains", "startsWith" to "startsWith", "endsWith" to "endsWith",
        "matches" to "matches",
    )

    private fun assertions(block: BruBlock?, warnings: MutableList<String>): List<String> =
        block?.entries().orEmpty().filter { it.enabled }.mapNotNull { e ->
            val expression = when {
                e.key == "res.status" -> "status"
                e.key == "res.body" -> "body.*"
                e.key.startsWith("res.body.") -> "body.$." + e.key.removePrefix("res.body.")
                e.key.startsWith("res.body[") -> "body.$" + e.key.removePrefix("res.body")
                e.key.startsWith("res.headers.") -> "headers." + e.key.removePrefix("res.headers.")
                else -> { warnings += "assertion '${e.key}: ${e.value}' is not converted (unsupported expression)"; return@mapNotNull null }
            }
            val operator = e.value.substringBefore(' ')
            val expected = e.value.substringAfter(' ', "").trim()
            when {
                operator == "isDefined" -> "$expression exists"
                operator == "isUndefined" -> "$expression !exists"
                operator in operators && expected.isNotEmpty() -> "$expression ${operators.getValue(operator)} ${fix(expected)}"
                else -> { warnings += "assertion '${e.key}: ${e.value}' is not converted (unsupported operator '$operator')"; null }
            }
        }
}
