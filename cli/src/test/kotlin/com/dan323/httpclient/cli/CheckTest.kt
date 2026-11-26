package com.dan323.httpclient.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** `httpc FILE --check`: the file is analysed, nothing is sent, no network is needed. */
class CheckTest {
    private val dir = Files.createTempDirectory("httpc-check")

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Result(code, o.toString(), e.toString())
    }

    private fun file(content: String) = dir.resolve("t.http").also { Files.writeString(it, content) }.toString()

    @Test fun `a clean file passes and says so`() {
        val r = cli(file("@host = http://x\nGET {{host}}/a\n"), "--check")
        assertEquals(0, r.code, r.err)
        assertEquals("", r.out)
        assertContains(r.err, "No problems")
    }

    @Test fun `an undefined variable is an error with its position, and fails`() {
        val f = file("GET http://x/a\n\n###\nGET {{base}}/b\n")
        val r = cli(f, "--check")
        assertEquals(1, r.code)
        assertContains(r.out, "$f:4:7: error: ")
        assertContains(r.out, "base")
        assertContains(r.out, "[undefined-variable]")
        assertContains(r.err, "1 error, 0 warnings")
    }

    @Test fun `a header the HTTP client sets itself is a warning that does not fail`() {
        val f = file("GET http://x/a\nHost: example.com\n")
        val r = cli(f, "--check")
        assertEquals(0, r.code, r.err)
        assertContains(r.out, "$f:2:1: warning: ")
        assertContains(r.out, "[dropped-header]")
        assertContains(r.err, "0 errors, 1 warning")
    }

    @Test fun `the chosen environment defines its variables, none does not`() {
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://secret-host"}}""")
        val f = file("GET {{base}}/b\n")
        assertEquals(1, cli(f, "--check").code)
        val withEnv = cli(f, "--check", "--env", "dev")
        assertEquals(0, withEnv.code, withEnv.out)
    }

    @Test fun `values are never printed`() {
        Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"token":"s3cr3t-value"}}""")
        val r = cli(file("GET http://x/{{other}}\nX: {{token}}\n"), "--check", "--env", "dev")
        assertFalse("s3cr3t-value" in r.out + r.err)
    }

    @Test fun `var names count as defined`() {
        val f = file("GET {{base}}/b\n")
        assertEquals(1, cli(f, "--check").code)
        assertEquals(0, cli(f, "--check", "--var", "base=x").code)
    }

    @Test fun `an unknown environment fails`() {
        val r = cli(file("GET http://x\n"), "--check", "--env", "nope")
        assertEquals(1, r.code)
        assertContains(r.err, "nope")
    }

    @Test fun `a line that does not parse is reported`() {
        val r = cli(file("GET http://x/a\nthis is not a header\n"), "--check")
        assertEquals(1, r.code)
        assertContains(r.out, "[parse-error]")
    }

    @Test fun `warnings alone pass`() {
        val f = file("# @name a\nGET http://x\n\n###\n# @name a\nGET http://y\n")
        val r = cli(f, "--check")
        assertEquals(0, r.code, r.out)
        assertContains(r.out, "$f:5:")
        assertContains(r.out, "warning: ")
        assertContains(r.out, "[duplicate-request-name]")
        assertContains(r.err, "0 errors, 1 warning")
    }

    @Test fun `problems come sorted by line and a parse error names its line once`() {
        val f = file("GET http://x/{{a}}\n\n###\nGET http://y/{{b}}\nbad header line\n")
        val lines = cli(f, "--check").out.trim().lines()
        assertEquals(listOf(1, 4, 5), lines.map { it.removePrefix("$f:").substringBefore(':').toInt() })
        val parse = lines.single { "[parse-error]" in it }
        assertFalse("Line 5" in parse, parse)
    }

    @Test fun `a malformed environment file fails and names the file`() {
        Files.writeString(dir.resolve("http-client.env.json"), "[]")
        val r = cli(file("GET http://x\n"), "--check", "--env", "dev")
        assertEquals(1, r.code)
        assertContains(r.err, "http-client.env.json")
    }

    @Test fun `a file that is not UTF-8 says so`() {
        val f = dir.resolve("bad.http").also { Files.write(it, byteArrayOf('G'.code.toByte(), 0xFF.toByte())) }.toString()
        val r = cli(f, "--check")
        assertEquals(1, r.code)
        assertContains(r.err, "not valid UTF-8")
    }

    @Test fun `a missing file fails`() {
        assertEquals(1, cli(dir.resolve("missing.http").toString(), "--check").code)
    }

    @Test fun `options that would run or write something are refused`() {
        for (extra in listOf(listOf("--list"), listOf("--request", "a"), listOf("--quiet"), listOf("--timeout", "5"), listOf("--fail-on-http-error"),
            listOf("--allow-env", "A"), listOf("--allow-file-root", "x"), listOf("--report-json", "r.json"), listOf("--output", "o.bin"))) {
            assertNull(parseArgs(listOf("f.http", "--check") + extra), extra.toString())
        }
        assertEquals(true, parseArgs(listOf("f.http", "--check", "--env", "dev", "--var", "a=b"))?.check)
    }
}
