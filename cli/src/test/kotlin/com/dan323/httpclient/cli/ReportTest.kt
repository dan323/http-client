package com.dan323.httpclient.cli

import com.dan323.httpclient.adapters.JsonRunReport
import com.dan323.httpclient.mock.MockServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
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

    @Test fun reportsNeverReplaceASavedResponse() {
        val base = "http://127.0.0.1:${server.port}"
        val f = file("GET $base/get\n>> saved.json\n")
        val (code, _, err) = cli(f, "--report-json", dir.resolve("sub/../saved.json").toString(), "--report-junit", dir.resolve("ok.xml").toString())
        assertEquals(3, code, err)
        assertContains(err, "Cannot write report")
        assertContains(err, "saved by '>>'")
        val saved = Files.readString(dir.resolve("saved.json"))
        assertFalse(saved.contains("\"entries\""), saved) // still the HTTP response, not a report
        assertContains(saved, "url")
        assertTrue(Files.exists(dir.resolve("ok.xml"))) // the other report is still written
        // a different name is fine, and --output colliding with a saved response is refused as well
        val (code2, _, err2) = cli(f, "--output", dir.resolve("saved-1.json").toString())
        assertEquals(3, code2, err2)
        assertContains(err2, "Cannot write output")
    }

    @Test fun corruptHistoryWarningsGoToTheCallersErrorStream() {
        val base = "http://127.0.0.1:${server.port}"
        val f = file("GET $base/get\n<> link.json\n")
        val bad = Files.createDirectories(dir.resolve(".http-history/r.http/20200101T000000000Z-deadbeef"))
        Files.writeString(bad.resolve("run.json"), "{not json")
        val (code, _, err) = cli(f)
        assertEquals(0, code, err)
        assertContains(err, "warning: skipping unreadable history entry")
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
        assertEquals(1, code, err)
        assertContains(err, "Cannot write report")
        assertContains(err, "Run failed")
    }

    @Test fun earlyFailureWithUnwritableReportStillExitsOne() {
        val blocker = dir.resolve("blocker").also { Files.writeString(it, "x") }
        val (code, _, err) = cli(file("GET http://x/\n"), "--request", "nope", "--report-json", blocker.resolve("r.json").toString())
        assertEquals(1, code, err)
        assertContains(err, "Cannot write report")
    }

    @Test fun reportsGoThroughTheReportWriterPort() {
        val written = mutableMapOf<java.nio.file.Path, String>()
        val f = file("GET http://127.0.0.1:${server.port}/get\n")
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val json = dir.resolve("never-created.json")
        val code = run(listOf(f, "--report-json", json.toString()), PrintStream(o), PrintStream(e)) { path, content -> written[path] = content }
        assertEquals(0, code, e.toString())
        assertTrue(JsonRunReport.parse(written.getValue(json)).passed)
        assertFalse(Files.exists(json))
    }

    @Test fun reportOptionPointingAtTheInputFileIsBadUsage() {
        val f = file("GET http://x/\n")
        val before = Files.readString(dir.resolve("r.http"))
        assertNull(parseArgs(listOf(f, "--report-json", f)))
        assertNull(parseArgs(listOf(f, "--report-junit", "$f/../r.http")))
        val (code, _, err) = cli(f, "--report-junit", f)
        assertEquals(2, code)
        assertContains(err, "would overwrite the input file")
        assertEquals(before, Files.readString(dir.resolve("r.http")))
    }

    @Test fun reportOptionPointingAtTheInputThroughASymlinkIsBadUsage() {
        val f = file("GET http://x/\n")
        val link = dir.resolve("link.xml")
        val created = try { Files.createSymbolicLink(link, dir.resolve("r.http")); true } catch (_: Exception) { false }
        assumeTrue(created, "no symlink support/permission")
        assertNull(parseArgs(listOf(f, "--report-junit", link.toString())))
    }

    @Test fun reportPathsDifferingOnlyByCaseAreBadUsage() {
        assertNull(parseArgs(listOf("f.http", "--report-json", dir.resolve("R.xml").toString(), "--report-junit", dir.resolve("r.xml").toString())))
        assertNull(parseArgs(listOf("f.http", "--report-json", dir.resolve("new/R.xml").toString(), "--report-junit", dir.resolve("new/../new/r.XML").toString())))
        assertNotNull(parseArgs(listOf("f.http", "--report-json", dir.resolve("a.json").toString(), "--report-junit", dir.resolve("b.xml").toString())))
    }

    @Test fun reportWithListIsBadUsage() {
        val f = file("GET http://x/\n")
        val out = dir.resolve("l.json")
        assertNull(parseArgs(listOf(f, "--list", "--report-json", out.toString())))
        assertNull(parseArgs(listOf(f, "--report-junit", out.toString(), "--list")))
        val (code, _, err) = cli(f, "--list", "--report-json", out.toString())
        assertEquals(2, code)
        assertContains(err, "cannot be combined with --list")
        assertFalse(Files.exists(out))
        assertNotNull(parseArgs(listOf(f, "--list")))
    }

    @Test fun reportOptionsAreParsedIntoPaths() {
        val o = parseArgs(listOf("f.http", "--report-json", "a.json", "--report-junit", "b.xml"))!!
        assertEquals(java.nio.file.Path.of("a.json"), o.reportJson)
        assertEquals(java.nio.file.Path.of("b.xml"), o.reportJunit)
        val none = parseArgs(listOf("f.http"))!!
        assertNull(none.reportJson); assertNull(none.reportJunit)
        assertNull(parseArgs(listOf("f.http", "--report-json", "a", "--report-json", "b")))
        assertNull(parseArgs(listOf("f.http", "--report-junit", "a", "--report-junit", "b")))
    }

    @Test fun reportFileIsOwnerOnlyOnPosix() {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val f = file("GET http://127.0.0.1:${server.port}/get\n")
        val json = dir.resolve("perm.json")
        assertEquals(0, cli(f, "--report-json", json.toString()).first)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(json)))
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

    @Test fun failOnHttpErrorFailsRunAndReports() {
        val base = "http://127.0.0.1:${server.port}"
        val f = file("# @name boom\nGET $base/status/503\n")
        val plain = cli(f)
        assertEquals(0, plain.first, plain.third)
        val json = dir.resolve("r.json"); val xml = dir.resolve("r.xml")
        val (code, out, err) = cli(f, "--fail-on-http-error", "--quiet", "--report-json", json.toString(), "--report-junit", xml.toString())
        assertEquals(1, code, err)
        assertEquals("", out)
        assertContains(err, "FAIL HTTP 503 from boom (--fail-on-http-error)")
        assertContains(err, "1 request, 0 passed, 1 failed")
        val report = JsonRunReport.parse(Files.readString(json))
        assertEquals("HTTP 503", report.entries[0].httpError)
        assertEquals("1", parseXml(xml).documentElement.getAttribute("failures"))
    }

    @Test fun failOnHttpErrorLeavesSuccessAndPrerequisitesAlone() {
        val base = "http://127.0.0.1:${server.port}"
        val ok = file("GET $base/get\n")
        assertEquals(0, cli(ok, "--fail-on-http-error").first)
        val chained = file("# @name pre\nGET $base/status/404\n\n###\n# @name main\nGET $base/get\n")
        assertEquals(1, cli(chained, "--fail-on-http-error").first)
        assertEquals(0, cli(chained, "--fail-on-http-error", "--request", "main").first)
        val dep = file("# @name pre\nGET $base/status/404\n\n###\n# @name main\n# @assert status == 200\nGET $base/get?x={{pre.response.status}}\n")
        assertEquals(0, cli(dep, "--fail-on-http-error", "--request", "main").first)
    }
}
