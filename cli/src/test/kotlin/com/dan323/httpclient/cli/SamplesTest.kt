package com.dan323.httpclient.cli

import com.dan323.httpclient.mock.MockServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the files in /samples against the mock server so they cannot silently rot. */
class SamplesTest {
    private val server = MockServer(0)
    private val dir = Files.createTempDirectory("httpc-samples")

    @BeforeTest fun setUp() {
        server.start()
        Files.list(Path.of("../samples")).use { files ->
            files.filter { it.fileName.toString().let { n -> n.endsWith(".http") || n.endsWith(".json") } }
                .forEach { Files.copy(it, dir.resolve(it.fileName)) }
        }
        // Same shape as the real env file, pointing at the ephemeral port.
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.port}","user":"alice"}}""")
    }

    @AfterTest fun tearDown() = server.stop()

    private class Result(val code: Int, val out: String, val err: String)

    private fun sample(name: String, vararg extra: String): Result {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = run(listOf(dir.resolve(name).toString(), "--env", "dev") + extra, PrintStream(o), PrintStream(e))
        return Result(code, o.toString(), e.toString())
    }

    @Test fun basic() {
        val r = sample("basic.http")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "HTTP 204")
        assertContains(r.out, "\"user\": \"alice\"")
    }

    @Test fun variablesHasTwoExpectedFailures() {
        val r = sample("variables.http")
        assertEquals(1, r.code)
        assertContains(r.err, "Undefined variable: password")
        assertContains(r.err, "Undefined variable: nope")
        assertContains(r.out, "### dynamic")
    }

    @Test fun chainingWorksAndMissingPathFails() {
        val whole = sample("chaining.http")
        assertEquals(1, whole.code)
        assertContains(whole.err, "Undefined variable: login.response.body.$.nope")
        assertContains(whole.out, "\"authenticated\": true")

        val single = sample("chaining.http", "--request", "use-token")
        assertEquals(0, single.code, single.err)
        assertContains(single.out, "### login")
        assertContains(single.out, "\"token\": \"secret-")
    }

    @Test fun assertions() {
        val r = sample("assertions.http")
        assertEquals(1, r.code)
        assertContains(r.out, "PASS body.\$.json.n > 3")
        assertContains(r.out, "FAIL body.\$.args.user == nobody")
        assertContains(r.out, "PASS body.\$.args.user == {{user}}")
        assertTrue(Regex("""Assertions: \d+ passed, 1 failed""").containsMatchIn(r.out), r.out)
        assertEquals(0, sample("assertions.http", "--request", "numbers").code)
    }

    @Test fun fileBody() {
        val r = sample("file-body.http")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "\"source\": \"payload.json\"")
    }

    @Test fun methods() {
        val r = sample("methods.http")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "HTTP 404")
        assertFalse(r.out.contains("HTTP 302"), "redirects should be followed")
    }
}
