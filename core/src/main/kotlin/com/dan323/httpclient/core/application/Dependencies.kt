package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.RequestTemplate

private val referencePattern = Regex("""\{\{\s*([^{}\s.]+)\.response\.""")

private fun RequestTemplate.referencedNames(): Set<String> {
    val texts = listOf(url) + headers.map { it.value } + when (val b = body) {
        is Body.Text -> listOf(b.content)
        is Body.FromFile -> listOf(b.path)
        null -> emptyList()
    }
    return texts.flatMap { t -> referencePattern.findAll(t).map { it.groupValues[1] } }.toSet()
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
