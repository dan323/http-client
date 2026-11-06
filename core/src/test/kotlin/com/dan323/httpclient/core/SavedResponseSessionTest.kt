package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.ResponseLinks
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.domain.ResponseSave
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.ResponseLinkRecorder
import com.dan323.httpclient.core.port.out.ResponseSaver
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedResponseSessionTest {
    private val body = byteArrayOf(0, 1, -1, 65)
    private var fail = false
    private val executor = HttpExecutor { if (fail) throw IOException("down") else HttpResponse(200, emptyList(), body, 1) }
    private val saved = mutableListOf<Triple<String, ByteArray, Boolean>>()
    private val linked = mutableListOf<String>()
    private var saverBehaviour: (String) -> String = { it }

    private fun session(saver: ResponseSaver? = ResponseSaver { p, b, o -> saved += Triple(p, b, o); saverBehaviour(p) }, links: ResponseLinkRecorder? = ResponseLinkRecorder { k, _ -> linked += k }) =
        RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }.withPersistence(saver, links)

    private fun req(save: ResponseSave? = null, links: List<String> = emptyList(), assertions: List<String> = emptyList(), name: String? = "a") =
        RequestTemplate(name, "GET", "http://h", saveResponse = save, responseLinks = links, assertions = assertions, startLine = 4)

    private fun run(r: RequestTemplate, vars: Map<String, String> = emptyMap(), s: RequestSession = session()) = s.runAll(listOf(r), vars).single()

    @Test fun `saves the exact bytes with the resolved path`() {
        val o = run(req(ResponseSave("out-{{id}}.bin", true)), mapOf("id" to "7"))
        assertIs<RunOutcome.Success>(o)
        assertEquals("out-7.bin", o.savedTo)
        assertEquals("out-7.bin", saved.single().first)
        assertContentEquals(body, saved.single().second)
        assertTrue(saved.single().third)
    }

    @Test fun `saves also when an assertion failed`() {
        val o = run(req(ResponseSave("o.json", false), assertions = listOf("status == 500"))) as RunOutcome.Success
        assertFalse(o.assertionsPassed)
        assertEquals("o.json", o.savedTo)
    }

    @Test fun `no save when the request failed`() {
        fail = true
        assertIs<RunOutcome.Failure>(run(req(ResponseSave("o.json", false), links = listOf("l"))))
        assertTrue(saved.isEmpty())
        assertTrue(linked.isEmpty())
    }

    @Test fun `a rejected save keeps the response and hides substituted values`() {
        saverBehaviour = { throw IOException("File '$it' is outside") }
        val o = run(req(ResponseSave("../{{secretdir}}/x", false)), mapOf("secretdir" to "hunter22secret")) as RunOutcome.Success
        assertEquals(200, o.response.statusCode)
        assertNull(o.savedTo)
        assertTrue("{{secretdir}}" in o.saveError!!, o.saveError)
        assertFalse("hunter22secret" in o.saveError!!)
    }

    @Test fun `undefined variable in the path fails only the save`() {
        val o = run(req(ResponseSave("{{nope}}.json", false))) as RunOutcome.Success
        assertTrue("nope" in o.saveError!!)
        assertTrue(saved.isEmpty())
    }

    @Test fun `without a saver the error says so`() {
        val o = run(req(ResponseSave("o", false)), s = session(saver = null)) as RunOutcome.Success
        assertTrue("not available" in o.saveError!!)
    }

    @Test fun `links are recorded by name or by position and failures are reported`() {
        run(req(links = listOf("x")))
        run(req(links = listOf("x"), name = null))
        run(req(name = "plain"))
        assertEquals(listOf("a", "unnamed line 5"), linked)
        assertEquals("a", ResponseLinks.key(req()))
        val o = run(req(links = listOf("x")), s = session(links = ResponseLinkRecorder { _, _ -> throw IOException("disk full") })) as RunOutcome.Success
        assertEquals("Cannot keep the response link: disk full", o.linkError)
        assertEquals(200, o.response.statusCode)
    }

    @Test fun `the shown saved path hides substituted values like error messages do`() {
        saverBehaviour = { it }
        val o = run(req(ResponseSave("out/{{token}}.json", false)), mapOf("token" to "hunter22secret")) as RunOutcome.Success
        assertEquals("out/hunter22secret.json", saved.single().first) // the real write uses the value
        assertEquals("out/{{token}}.json", o.savedTo)
        assertFalse("hunter22secret" in o.savedTo!!)
    }

    private fun prerequisiteFile() = com.dan323.httpclient.core.application.HttpFileParser().parse(
        "# @name pre\nGET http://h/pre\n\n>> pre.json\n<> pre-link.json\n###\n" +
            "# @name main\n# @import pre\nGET http://h/main?x={{pre.response.status}}\n\n>> main.json\n<> main-link.json\n",
    ).requests

    @Test fun `a prerequisite request saves and links when it runs for a chained request`() {
        val all = prerequisiteFile()
        val outcomes = session().runChained(all, all[1], emptyMap())
        assertEquals(listOf("pre", "main"), outcomes.map { it.request.name })
        assertEquals(listOf("pre.json", "main.json"), outcomes.map { (it as RunOutcome.Success).savedTo })
        assertEquals(listOf("pre", "main"), linked)
        assertEquals(listOf("pre.json", "main.json"), saved.map { it.first })
    }

    @Test fun `a reused prerequisite neither saves nor links again`() {
        val all = prerequisiteFile()
        val s = session()
        s.runChained(all, all[1], emptyMap())
        s.runChained(all, all[1], emptyMap())
        assertEquals(listOf("pre.json", "main.json", "main.json"), saved.map { it.first })
        assertEquals(listOf("pre", "main", "main"), linked)
    }

    @Test fun `a prerequisite whose save fails does not stop the chain`() {
        val all = prerequisiteFile()
        saverBehaviour = { if (it == "pre.json") throw IOException("read-only") else it }
        val outcomes = session().runChained(all, all[1], emptyMap())
        assertTrue("read-only" in (outcomes[0] as RunOutcome.Success).saveError!!)
        assertEquals("main.json", (outcomes[1] as RunOutcome.Success).savedTo)
    }
}
