package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.ResponseLinkRecorder
import java.nio.file.Path

/** Keeps the latest response of requests with a `<> path` link in a [HistoryStore]; the `.http` file is never edited. */
class HistoryLinkRecorder(private val httpFile: Path, private val store: HistoryStore) : ResponseLinkRecorder {
    override fun record(key: String, outcome: RunOutcome.Success) {
        store.recordLinked(httpFile, key, RunReport.from(listOf(outcome)), outcome.response)
    }
}
