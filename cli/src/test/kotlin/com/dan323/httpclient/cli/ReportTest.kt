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
    @AfterTest fun tearDown() {
        server.stop()
        dir.toFile().deleteRecursively()
    }

    /** A port that was just bound and released, so nothing listens on it. */
    private fun closedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun parseXml(p: java.nio.file.Path) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(p.toFile())

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
        val f = file("# @name down\nGET http://127.0.0.1:${closedPort()}/x\n")
        val xml = dir.resolve("e.xml")
        assertEquals(1, cli(f, "--report-junit", xml.toString()).first)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile())
        assertEquals(1, doc.getElementsByTagName("error").length)
    }

    @Test fun missingReportPathValueIsBadUsage() {
        assertEquals(2, cli(file("GET http://x/\n"), "--report-json").first)
        assertNull(parseArgs(listOf("f.http", "--report-junit")))
    }

    @Test fun optionFollowedByFlagOrEmptyIsBadUsage() {
        assertNull(parseArgs(listOf("f.http", "--report-json", "--env", "dev")))
        assertNull(parseArgs(listOf("f.http", "--env", "--list")))
        assertNull(parseArgs(listOf("f.http", "--report-junit", "")))
        assertNull(parseArgs(listOf("f.http", "--env", "a", "--env", "b")))
        assertEquals(2, cli(file("GET http://x/\n"), "--report-json", "--list").first)
        assertFalse(Files.exists(java.nio.file.Path.of("--list")))
    }

    @Test fun invalidPathAndIdenticalPathsAreBadUsage() {
        assertNull(parseArgs(listOf("f.http", "--report-json", "a\u0000b")))
        val same = dir.resolve("same.out").toString()
        assertNull(parseArgs(listOf("f.http", "--report-json", same, "--report-junit", "$same/../same.out")))
        assertEquals(2, cli(file("GET http://x/\n"), "--report-json", same, "--report-junit", same).first)
    }

    @Test fun nestedParentDirectoriesAreCreatedAndNoTempFilesRemain() {
        val f = file("GET http://127.0.0.1:${server.port}/get\n")
        val json = dir.resolve("a/b/c/report.json")
        assertEquals(0, cli(f, "--report-json", json.toString()).first)
        assertTrue(Files.isRegularFile(json))
        Files.list(json.parent).use { assertEquals(listOf("report.json"), it.map { p -> p.fileName.toString() }.toList()) }
    }

    @Test fun unwritableReportGivesExitThreeAndTheOtherReportIsStillWritten() {
        val f = file("GET http://127.0.0.1:${server.port}/get\n")
        val blocker = dir.resolve("blocker").also { Files.writeString(it, "x") }
        val xml = dir.resolve("ok.xml")
        val (code, _, err) = cli(f, "--report-json", blocker.resolve("r.json").toString(), "--report-junit", xml.toString())
        assertEquals(3, code, err)
        assertContains(err, "Cannot write report")
        assertContains(err, "Run passed")
        assertEquals("1", parseXml(xml).documentElement.getAttribute("tests"))
    }

    @Test fun unwritableReportKeepsFailureInMessage() {
        val f = file("# @assert status == 200\nGET http://127.0.0.1:${server.port}/status/500\n")
        val blocker = dir.resolve("blocker").also { Files.writeString(it, "x") }
        val (code, _, err) = cli(f, "--report-junit", blocker.resolve("r.xml").toString())
        assertEquals(3, code)
        assertContains(err, "Run failed")
    }

    @Test fun earlyExitsStillWriteAnErrorReport() {
        val xml = dir.resolve("early.xml")
        val json = dir.resolve("early.json")
        val f = file("GET http://127.0.0.1:${server.port}/get\n")
        assertEquals(1, cli(f, "--request", "nope", "--report-junit", xml.toString(), "--report-json", json.toString()).first)
        assertEquals("1", parseXml(xml).documentElement.getAttribute("errors"))
        assertFalse(JsonRunReport.parse(Files.readString(json)).passed)
        val missing = dir.resolve("missing.xml")
        assertEquals(1, cli(dir.resolve("none.http").toString(), "--report-junit", missing.toString()).first)
        assertTrue(Files.exists(missing))
    }

    @Test fun prerequisiteIsMarkedInJsonAndOmittedFromJunitWhenPassing() {
        val base = "http://127.0.0.1:${server.port}"
        val f = file("# @name first\nGET $base/get\n\n###\n# @name second\n# @assert status == 200\nGET $base/get?x={{first.response.body.\$.url}}\n")
        val json = dir.resolve("p.json"); val xml = dir.resolve("p.xml")
        assertEquals(0, cli(f, "--request", "second", "--report-json", json.toString(), "--report-junit", xml.toString()).first)
        assertEquals(listOf(true, false), JsonRunReport.parse(Files.readString(json)).entries.map { it.prerequisite })
        assertEquals("1", parseXml(xml).documentElement.getAttribute("tests"))
        assertEquals("2. second", (parseXml(xml).getElementsByTagName("testcase").item(0) as org.w3c.dom.Element).getAttribute("name"))
    }
}
