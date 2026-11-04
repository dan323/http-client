package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.JsonRunReport
import com.dan323.httpclient.mock.MockServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTest {
    private val server = MockServer(0)
    private val dir = Files.createTempDirectory("httpc-report")

    @BeforeTest fun setUp() { server.start() }
    @AfterTest fun tearDown() { server.stop() }

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Triple(code, o.toString(), e.toString())
    }

    private fun file(content: String) = dir.resolve("r.http").also { Files.writeString(it, content) }.toString()

    @Test fun writesBothReportsAndKeepsExitCode() {
        val base = "http://127.0.0.1:${server.port}"
        val f = file("# @name good\n# @assert status == 200\nGET $base/get\n\n###\n# @name bad\n# @assert status == 200\nGET $base/status/500\n")
        val json = dir.resolve("out/report.json")
        val xml = dir.resolve("report.xml")
        val (code, out, err) = cli(f, "--report-json", json.toString(), "--report-junit", xml.toString())
        assertEquals(1, code, err)
        assertContains(out, "PASS status == 200")
        assertContains(out, "Assertions: 1 passed, 1 failed, 0 not run")

        val report = JsonRunReport.parse(Files.readString(json))
        assertEquals(listOf("good", "bad"), report.entries.map { it.name })
        assertEquals(listOf(200, 500), report.entries.map { it.status })
        assertFalse(report.passed)
        assertTrue(report.entries[0].passed)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile())
        assertEquals("2", doc.documentElement.getAttribute("tests"))
        assertEquals("1", doc.documentElement.getAttribute("failures"))
        assertEquals(1, doc.getElementsByTagName("failure").length)
    }

    @Test fun passingRunExitsZeroAndReportsPassed() {
        val f = file("GET http://127.0.0.1:${server.port}/get\n")
        val json = dir.resolve("ok.json")
        assertEquals(0, cli(f, "--report-json", json.toString()).first)
        assertTrue(JsonRunReport.parse(Files.readString(json)).passed)
    }

    @Test fun failedRequestIsRecordedAsError() {
        val f = file("# @name down\nGET http://127.0.0.1:1/x\n")
        val xml = dir.resolve("e.xml")
        assertEquals(1, cli(f, "--report-junit", xml.toString()).first)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile())
        assertEquals(1, doc.getElementsByTagName("error").length)
    }

    @Test fun missingReportPathValueIsBadUsage() {
        assertEquals(2, cli(file("GET http://x/\n"), "--report-json").first)
        assertNull(parseArgs(listOf("f.http", "--report-junit")))
    }
}
