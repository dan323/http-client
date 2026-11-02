package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate

sealed interface RunOutcome {
    val request: RequestTemplate

    data class Success(override val request: RequestTemplate, val response: HttpResponse) : RunOutcome
    data class Failure(override val request: RequestTemplate, val message: String) : RunOutcome
}
