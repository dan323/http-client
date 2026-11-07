package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.StoredResponse
import java.nio.file.Path

/**
 * `<> path` links. Unlike JetBrains, which rewrites the `.http` file to add a `<>` line per response, the link is kept
 * in the history index only (the tool never edits the user's file): a request with a `<>` directive has its latest
 * response recorded, and the link resolves to that response.
 */
object ResponseLinks {
    /**
     * Identifies a request inside its file: its `@name`, or for an unnamed one its position (the line where it
     * starts), which changes when lines are added above it. The text holds a space, so it never equals a name.
     */
    fun key(request: RequestTemplate): String = request.name?.takeIf { it.isNotBlank() } ?: "unnamed line ${request.startLine + 1}"

    /** The latest stored response a `<>` link of [request] points to, or null when it has no link or nothing was recorded yet. */
    fun latest(store: HistoryStore, httpFile: Path, request: RequestTemplate): StoredResponse? =
        if (request.responseLinks.isEmpty()) null else store.latestLinked(httpFile, key(request))
}
