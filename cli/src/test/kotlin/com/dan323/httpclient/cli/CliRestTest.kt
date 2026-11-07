package com.dan323.httpclient.cli

import com.dan323.httpclient.mock.MockServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliRestTest {
    private val server = MockServer(0)
    private val dir = Files.createTempDirectory("httpc-rest")
    private val base get() = "http://127.0.0.1:${server.port}"

    @BeforeTest fun setUp() { server.start() }
    @AfterTest fun tearDown() { server.stop(); dir.toFile().deleteRecursively() }

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Triple(code, o.toString(), e.toString())
    }

    private fun file(content: String) = dir.resolve("r.http").also { Files.writeString(it, content) }.toString()

    // --- --var parsing

    @Test fun varParsingAllowsEqualsAndEmptyValuesButNotEmptyNames() {
        assertEquals("a" to "b=c", parseVar("a=b=c"))
        assertEquals("a" to "", parseVar("a="))
        assertNull(parseVar("=x"))
        assertNull(parseVar("noequals"))
        assertNull(parseVar("a b=1"))
        assertNull(parseVar("{{a}}=1"))
    }

    @Test fun varOptionIsRepeatableAndRejectsBadInput() {
        val o = parseArgs(listOf("f.http", "--var", "a=1", "--var", "b=x=y"))!!
        assertEquals(mapOf("a" to "1", "b" to "x=y"), o.vars)
        assertNull(parseArgs(listOf("f.http", "--var")))
        assertNull(parseArgs(listOf("f.http", "--var", "=secret")))
        assertNull(parseArgs(listOf("f.http", "--var", "novalue")))
        assertNull(parseArgs(listOf("f.http", "--var", "a=1", "--var", "a=2")))
    }

    @Test fun usageErrorDoesNotEchoTheVarValue() {
        val (code, _, err) = cli(file("GET http://x/\n"), "--var", "=topsecret")
        assertEquals(2, code)
        assertFalse(err.contains("topsecret"))
    }

    @Test fun varOverridesFileVariablesAndIsSeenByOtherFileVariables() {
        val f = file("@host = $base\n@path = /anything/{{who}}\n@who = file\n\nGET {{host}}{{path}}\n")
        val (code, out, err) = cli(f, "--var", "who=cli")
        assertEquals(0, code, err)
        assertContains(out, "/anything/cli")
        assertFalse(out.contains("/anything/file"))
    }

    @Test fun varOverridesEnvironmentVariables() {
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev": {"who": "env"}}""")
        val f = file("GET $base/anything/{{who}}\n")
        assertContains(cli(f, "--env", "dev").second, "/anything/env")
        assertContains(cli(f, "--env", "dev", "--var", "who=cli").second, "/anything/cli")
    }

    @Test fun varValueIsScrubbedFromFailureMessages() {
        val f = file("GET http://bad host/{{tok}}\n")
        val (code, _, err) = cli(f, "--var", "tok=s3cr3tvalue")
        assertEquals(1, code)
        assertFalse(err.contains("s3cr3tvalue"), err)
    }

    // --- --quiet and summary

    @Test fun quietSuppressesPerRequestOutputButKeepsSummaryAndErrors() {
        val f = file("# @name ok\n# @assert status == 200\nGET $base/get\n\n###\n# @name bad\n# @assert status == 200\nGET $base/status/500\n")
        val (code, out, err) = cli(f, "--quiet")
        assertEquals(1, code, err)
        assertEquals("", out)
        assertContains(err, "FAIL status == 200")
        assertContains(err, "2 requests, 1 passed, 1 failed in ")
    }

    @Test fun quietStillReportsRequestErrorsOnStderr() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val (code, out, err) = cli(file("GET http://127.0.0.1:$port/x\n"), "--quiet")
        assertEquals(1, code)
        assertEquals("", out)
        assertContains(err, "Error:")
        assertContains(err, "1 request, 0 passed, 1 failed in ")
    }

    @Test fun summaryGoesToStderrAndStdoutKeepsTheNormalOutput() {
        val (code, out, err) = cli(file("GET $base/get\n"))
        assertEquals(0, code, err)
        assertContains(out, "HTTP 200")
        assertFalse(out.contains("passed,"))
        assertTrue(Regex("1 request, 1 passed, 0 failed in \\d+\\.\\ds").containsMatchIn(err), err)
    }

    @Test fun summaryFormat() {
        val r = com.dan323.httpclient.core.application.RunReport(emptyList())
        assertEquals("0 requests, 0 passed, 0 failed in 1.2s", summary(r, 1234))
    }

    @Test fun quietAndVarWithListAreBadUsage() {
        assertNull(parseArgs(listOf("f.http", "--list", "--quiet")))
        assertNull(parseArgs(listOf("f.http", "--list", "--var", "a=1")))
        assertNull(parseArgs(listOf("f.http", "--list", "--output", dir.resolve("o.txt").toString())))
        assertTrue(parseArgs(listOf("f.http", "--quiet"))!!.quiet)
    }

    // --- --output

    @Test fun outputWritesTheBodyOfTheSingleRequestThroughThePort() {
        val written = mutableMapOf<Path, ByteArray>()
        val f = file("GET $base/anything/hello\n")
        val target = dir.resolve("never-created.txt")
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = run(listOf(f, "--output", target.toString()), PrintStream(o), PrintStream(e), { p, c -> written[p] = c })
        assertEquals(0, code, e.toString())
        assertContains(String(written.getValue(target), Charsets.UTF_8), "/anything/hello")
        assertFalse(Files.exists(target))
    }

    @Test fun outputWithRequestSelectorWritesThatBodyOnly() {
        val f = file("# @name one\nGET $base/anything/first\n\n###\n# @name two\nGET $base/anything/second\n")
        val target = dir.resolve("sub/body.json")
        val (code, _, err) = cli(f, "--request", "two", "--output", target.toString())
        assertEquals(0, code, err)
        val text = Files.readString(target)
        assertContains(text, "/anything/second")
        assertFalse(text.contains("first"))
    }

    @Test fun outputWithSeveralRequestsIsBadUsageAndSendsNothing() {
        val f = file("GET $base/get\n\n###\nGET $base/get\n")
        val target = dir.resolve("o.txt")
        val (code, out, err) = cli(f, "--output", target.toString())
        assertEquals(2, code)
        assertContains(err, "exactly one selected request")
        assertEquals("", out)
        assertFalse(Files.exists(target))
    }

    @Test fun outputIsNotWrittenWhenTheRequestFails() {
        val target = dir.resolve("o.txt")
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(1, cli(file("GET http://127.0.0.1:$port/x\n"), "--output", target.toString()).first)
        assertFalse(Files.exists(target))
    }

    @Test fun unwritableOutputGivesExitThreeOnAPassedRunAndOneOnAFailedRun() {
        val blocker = dir.resolve("blocker").also { Files.writeString(it, "x") }
        val bad = blocker.resolve("o.txt").toString()
        val (code, _, err) = cli(file("GET $base/get\n"), "--output", bad)
        assertEquals(3, code, err)
        assertContains(err, "Cannot write output")
        assertContains(err, "only the report/output file")
        val (code2, _, err2) = cli(file("# @assert status == 200\nGET $base/status/500\n"), "--output", bad)
        assertEquals(1, code2, err2)
    }

    @Test fun outputPathRules() {
        val f = file("GET http://x/\n")
        assertNull(parseArgs(listOf(f, "--output", f)))
        assertNull(parseArgs(listOf(f, "--output", "$f/../r.http")))
        assertNull(parseArgs(listOf(f, "--output", dir.resolve("R.HTTP").toString())))
        val rep = dir.resolve("rep.json").toString()
        assertNull(parseArgs(listOf(f, "--output", rep, "--report-json", rep)))
        assertNull(parseArgs(listOf(f, "--output", dir.resolve("REP.json").toString(), "--report-json", rep)))
        assertNull(parseArgs(listOf(f, "--report-junit", rep, "--output", dir.resolve("Rep.JSON").toString())))
        assertNull(parseArgs(listOf(f, "--output", "a", "--output", "b")))
        assertNull(parseArgs(listOf(f, "--output")))
        assertNotNull(parseArgs(listOf(f, "--output", dir.resolve("o.txt").toString(), "--report-json", rep)))
        assertEquals(2, cli(f, "--output", f).first)
        assertEquals("GET http://x/\n", Files.readString(dir.resolve("r.http")))
    }

    @Test fun outputFileIsOwnerOnlyOnPosix() {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val target = dir.resolve("secret.txt")
        assertEquals(0, cli(file("GET $base/get\n"), "--output", target.toString()).first)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }
}
