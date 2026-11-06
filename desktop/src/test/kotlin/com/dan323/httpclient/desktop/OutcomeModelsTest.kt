package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutcomeModelsTest {
    private fun failure(n: Int) = RunOutcome.Failure(RequestTemplate(name = "r$n", method = "GET", url = "http://x/$n"), "boom $n")

    @Test fun entriesForOutcomesThatLeftTheListAreEvicted() = runBlocking {
        val models = OutcomeModels()
        val a = failure(1); val b = failure(2)
        models.update(listOf(a, b))
        assertEquals(2, models.size)
        models.update(listOf(b))
        assertEquals(1, models.size)
        assertTrue(models[b] != null && models[a] == null)
        models.update(emptyList())
        assertEquals(0, models.size)
    }
}
