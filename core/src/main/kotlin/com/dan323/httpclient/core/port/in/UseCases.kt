package com.dan323.httpclient.core.port.`in`

import com.dan323.httpclient.core.domain.HttpFile
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate

fun interface ParseHttpFile {
    fun parse(text: String): HttpFile
}

fun interface RunRequest {
    /** Resolves variables (later maps override earlier ones), then sends. */
    fun run(template: RequestTemplate, variables: Map<String, String>): HttpResponse
}
