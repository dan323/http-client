package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.RequestTemplate

private val referencePattern = Regex("""\{\{\s*([^{}\s]+)\s*}}""")
private const val RESPONSE_MARKER = ".response."

/**
 * Names of the requests whose responses [this] reads: from the URL, header values, body, setting values and
 * assertions, and from the file variables those use (transitively). A reference `{{a.b.response.x}}` names
 * the request `a.b` (split on the first `.response.`, like `RequestSession`).
 */
private fun RequestTemplate.referencedNames(): Set<String> {
    val texts = ArrayDeque<String>()
    texts += url
    headers.forEach { texts += it.value }
    when (val b = body) {
        is Body.Text -> texts += b.content
        is Body.FromFile -> texts += b.path
        null -> {}
    }
    texts += settings.values
    texts += assertions
    val names = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    while (texts.isNotEmpty()) {
        for (m in referencePattern.findAll(texts.removeFirst())) {
            val ref = m.groupValues[1]
            val split = ref.indexOf(RESPONSE_MARKER)
            if (split > 0) names += ref.substring(0, split)
            else fileVariables[ref]?.let { if (visited.add(ref)) texts += it }
        }
    }
    return names
}

/**
 * Adds the named requests that [chosen] reads responses from (transitively), so a selected
 * request still works when it chains on earlier ones. Everything is returned in file order.
 */
fun withDependencies(all: List<RequestTemplate>, chosen: List<RequestTemplate>): List<RequestTemplate> {
    val needed = chosen.toMutableSet()
    val queue = ArrayDeque(chosen)
    while (queue.isNotEmpty()) {
        for (name in queue.removeFirst().referencedNames()) {
            all.filter { it.name == name }.forEach { if (needed.add(it)) queue.addLast(it) }
        }
    }
    return all.filter { it in needed }
}
