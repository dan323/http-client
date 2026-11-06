package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlinx.coroutines.runBlocking
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.display
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

    @Test fun equalOutcomesGetSeparateState() = runBlocking {
        val models = OutcomeModels()
        val a = failure(1); val b = failure(1)
        assertEquals(a, b)
        models.update(listOf(a, b))
        assertEquals(2, models.size)
        assertTrue(models[a] !== models[b])
    }

    @Test fun jsonResponsesGetTheirOwnUiState() = runBlocking {
        val models = OutcomeModels()
        val a = success("{\"a\":1}"); val b = success("{\"a\":1}")
        models.update(listOf(a, b))
        val ua = models[a]!!.ui!!; val ub = models[b]!!.ui!!
        assertTrue(ua !== ub)
        ua.raw = true
        assertTrue(!ub.raw)
    }

    @Test fun aBodyThatCannotBeDisplayedDoesNotThrow() = runBlocking {
        val models = OutcomeModels(render = { error("bad body") })
        val s = success("raw text")
        models.update(listOf(s))
        val d = models[s]!!.display as OutcomeDisplay.Error
        assertTrue("bad body" in d.message && "raw text" in d.message, d.message)
    }

    @Test fun aJsonParseFailureFallsBackToTheRawBody() = runBlocking {
        val models = OutcomeModels(parseJson = { error("deep") })
        val s = success("{}")
        models.update(listOf(s))
        val e = models[s]!!
        assertTrue(e.display is OutcomeDisplay.Response)
        assertEquals(null, e.ui!!.json)
    }

    @Test fun processedOutcomesAreNotProcessedAgain() = runBlocking {
        var renders = 0
        val models = OutcomeModels(render = { renders++; it.display() })
        val a = failure(1); val b = failure(2)
        models.update(listOf(a))
        models.update(listOf(a, b))
        models.update(listOf(a, b))
        assertEquals(2, renders)
    }

    private fun success(body: String) = RunOutcome.Success(
        RequestTemplate(name = "s", method = "GET", url = "http://x/s"),
        com.dan323.httpclient.core.domain.HttpResponse(200, emptyList(), body.toByteArray(), 1),
    )
}
