package com.dan323.httpclient.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starts the server wiring against a local HTTP server and tries to break each rule of docs/mcp-safety-model.md. */
class McpServerTest {
    private val root = Files.createTempDirectory("mcp-root").toRealPath()
    private val outside = Files.createTempDirectory("mcp-outside").toRealPath()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${http.address.port}"
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    private val closers = mutableListOf<AutoCloseable>()
    private var id = 0

    init {
        http.createContext("/echo") { ex ->
            hits.incrementAndGet()
            val sent = ex.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" } + "\n" + ex.requestURI
            ex.responseHeaders.add("X-Echo", ex.requestHeaders.getFirst("X-Secret") ?: "none")
            val b = sent.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        http.createContext("/big") { ex -> hits.incrementAndGet(); val b = "x".repeat(5000).toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        http.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "http://example.invalid/steal"); ex.sendResponseHeaders(302, -1); ex.close() }
        http.createContext("/post") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(204, -1); ex.close() }
        http.createContext("/utf16") { ex ->
            hits.incrementAndGet()
            // declares UTF-16LE but carries the UTF-8 bytes of the secret, which the charset-aware redaction does not see
            ex.responseHeaders.add("Content-Type", "text/plain; charset=UTF-16LE")
            val b = "TOPSECRET and more".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        http.createContext("/next") { ex -> hits.incrementAndGet(); ex.responseHeaders.add("X-Next", "http://example.invalid/x"); ex.sendResponseHeaders(204, -1); ex.close() }
        http.start()
    }

    @AfterTest fun tearDown() {
        http.stop(0); closers.forEach { it.close() }
        root.toFile().deleteRecursively(); outside.toFile().deleteRecursively()
    }

    private fun server(configure: ServerOptions.() -> ServerOptions): McpServer = server(*emptyArray<String>(), configure = configure)

    private fun server(vararg extra: String, configure: ServerOptions.() -> ServerOptions = { this }): McpServer {
        val options = ServerOptions.parse(listOf("--root", root.toString()) + extra).getOrThrow().configure()
        val (s, c) = buildServer(options); closers += c; return s
    }

    private fun write(name: String, text: String) = root.resolve(name).also { Files.writeString(it, text.replace("BASE", base)) }

    private fun call(s: McpServer, tool: String, vararg args: Pair<String, String>): Pair<String, Boolean> {
        val params = JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(tool),
            "arguments" to JsonObject(args.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })))
        val reply = Json.parseToJsonElement(s.handle(JsonObject(mapOf(
            "jsonrpc" to kotlinx.serialization.json.JsonPrimitive("2.0"), "id" to kotlinx.serialization.json.JsonPrimitive(++id),
            "method" to kotlinx.serialization.json.JsonPrimitive("tools/call"), "params" to params)).toString())!!).jsonObject
        val result = reply["result"]!!.jsonObject
        return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content to (result["isError"]!!.jsonPrimitive.content == "true")
    }

    // ---- protocol

    @Test fun initializeListsToolsAndNotificationsGetNoReply() {
        val s = server()
        val init = Json.parseToJsonElement(s.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")!!).jsonObject
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(s.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val list = Json.parseToJsonElement(s.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!).jsonObject
        val names = list["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("list_requests", "list_environments", "analyze", "run", "run_all"), names.toSet())
    }

    @Test fun unknownVersionFallsBackAndBadMessagesGetErrors() {
        val s = server()
        assertTrue("2025-06-18" in s.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999"}}""")!!)
        assertTrue("-32700" in s.handle("{nope")!!)
        assertTrue("-32600" in s.handle("[]")!!)
        assertTrue("-32601" in s.handle("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""")!!)
        assertTrue("-32602" in s.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rm_rf"}}""")!!)
        assertEquals("""{"jsonrpc":"2.0","id":5,"result":{}}""", s.handle("""{"jsonrpc":"2.0","id":5,"method":"ping"}"""))
    }

    // ---- rule 1: roots

    @Test fun pathsOutsideTheRootsAreRefused() {
        val s = server()
        Files.writeString(outside.resolve("o.http"), "GET $base/echo\n")
        Files.createSymbolicLink(root.resolve("link.http"), outside.resolve("o.http"))
        write("notes.txt", "x")
        for (p in listOf("../${outside.fileName}/o.http", outside.resolve("o.http").toString(), "link.http", "notes.txt", "missing.http")) {
            val (text, error) = call(s, "list_requests", "file" to p)
            assertTrue(error, p); assertFalse("GET" in text, p)
        }
        assertEquals(0, hits.get())
    }

    @Test fun anAbsolutePathInsideARootWorks() {
        val f = write("a.http", "### one\nGET BASE/echo\n")
        val (text, error) = call(server(), "list_requests", "file" to f.toString())
        assertFalse(error); assertTrue("/echo" in text)
    }

    @Test fun listRequestsShowsTheRequestsAsWritten() {
        write("a.http", "@host = BASE\n### one\nGET {{host}}/echo?k={{secret}}\n")
        val (text, _) = call(server(), "list_requests", "file" to "a.http")
        assertTrue("{{host}}/echo?k={{secret}}" in text)
    }

    @Test fun anEnvironmentFileCannotBeEchoedAsABody() {
        write("http-client.private.env.json", """{"dev":{"k":"TOPSECRET"}}""")
        write("a.http", "POST BASE/echo\n\n< ./http-client.private.env.json\n")
        val (text, error) = call(server("--allow-write", "--allow-unconfirmed-write"), "run", "file" to "a.http", "request" to "1")
        assertFalse("TOPSECRET" in text)
        assertTrue("environment file" in text || error)
    }

    // ---- rule 2: hosts

    @Test fun aRedirectToAHostThatIsNotAllowedIsNotFollowed() {
        write("a.http", "GET BASE/redir\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue("example.invalid" in text && "not an allowed host" in text, text)
        assertTrue(call(server(), "run", "file" to "a.http", "request" to "1").second, "a failed request is an error result")
    }

    @Test fun aRequestToAHostThatIsNotAllowedSendsNothing() {
        write("a.http", "GET http://example.com/\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue(error); assertTrue("not an allowed host" in text)
    }

    @Test fun aProxyIsRefused() {
        write("a.http", "# @proxy http://127.0.0.1:9\nGET BASE/echo\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue(error); assertTrue("@proxy" in text); assertEquals(0, hits.get())
    }

    // ---- rule 3: read only

    @Test fun aPostWithoutAllowWriteIsRefusedBeforeAnythingIsSent() {
        write("a.http", "### first\nGET BASE/echo\n\n### second\nPOST BASE/post\n")
        val (text, error) = call(server(), "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("--allow-write" in text)
        assertEquals(0, hits.get(), "the GET must not go out when a later request is refused")
    }

    @Test fun aPostRunsWithAllowWrite() {
        write("a.http", "POST BASE/post\n")
        val (text, error) = call(server("--allow-write", "--allow-unconfirmed-write"), "run", "file" to "a.http", "request" to "1")
        assertFalse(error); assertTrue("\"status\": 204" in text); assertEquals(1, hits.get())
    }

    @Test fun aChainedPostIsRefusedToo() {
        write("a.http", "### login\n# @name login\nPOST BASE/post\n\n### use\nGET BASE/echo?x={{login.response.status}}\n")
        val (_, error) = call(server(), "run", "file" to "a.http", "request" to "2")
        assertTrue(error); assertEquals(0, hits.get())
    }

    // ---- rule 4: private values

    @Test fun listEnvironmentsReturnsNamesOnly() {
        write("http-client.env.json", """{"dev":{"host":"h"}}""")
        write("http-client.private.env.json", """{"dev":{"key":"TOPSECRET"}}""")
        write("a.http", "GET BASE/echo\n")
        val (text, _) = call(server(), "list_environments", "file" to "a.http")
        assertTrue("dev" in text); assertFalse("TOPSECRET" in text)
    }

    @Test fun aPrivateValueEchoedBackByTheServerIsRedacted() {
        write("http-client.env.json", """{"dev":{"base":"$base"}}""")
        write("http-client.private.env.json", """{"dev":{"key":"TOPSECRET"}}""")
        write("a.http", "GET {{base}}/echo?k={{key}}\nX-Secret: {{key}}\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text)
        assertFalse("TOPSECRET" in text, text)
        assertTrue("{{key}}" in text, text)
    }

    @Test fun anUnknownEnvironmentNamesTheAvailableOnes() {
        write("http-client.env.json", """{"dev":{"host":"h"}}""")
        write("a.http", "GET BASE/echo\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1", "environment" to "prod")
        assertTrue(error); assertTrue("dev" in text)
    }

    @Test fun analyzeSendsNothingAndReportsUndefinedVariables() {
        write("a.http", "GET BASE/echo?k={{nope}}\n")
        val (text, error) = call(server(), "analyze", "file" to "a.http")
        assertFalse(error); assertTrue("undefined-variable" in text); assertEquals(0, hits.get())
    }

    // ---- rule 5, 6: no writes, no ambient authority

    @Test fun saveDirectivesWriteNothing() {
        write("a.http", "GET BASE/echo\n>> out.txt\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue("saveError" in text); assertFalse(Files.exists(root.resolve("out.txt")))
    }

    @Test fun processEnvironmentIsNotReadableByDefault() {
        write("a.http", "GET BASE/echo?h={{\$env.PATH}}\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        val path = System.getenv("PATH") ?: error("PATH is not set in this environment; the test needs it to prove that \$env is closed")
        assertFalse(path in text)
        assertEquals(0, hits.get(), "the request must fail before it is sent")
    }

    // ---- rule 7: bounds

    @Test fun aBodyIsCutAtTheLimit() {
        write("a.http", "GET BASE/big\n")
        val (text, _) = call(server("--max-body-bytes", "100"), "run", "file" to "a.http", "request" to "1")
        assertTrue("bodyTruncated" in text); assertFalse("x".repeat(101) in text)
    }

    @Test fun aFileLargerThanTheRequestCapIsRefusedBeforeSending() {
        write("a.http", (1..3).joinToString("\n") { "### r$it\nGET BASE/echo\n" })
        val (text, error) = call(server("--max-requests", "2"), "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("--max-requests" in text); assertEquals(0, hits.get())
    }

    // ---- rule 8: labelled

    @Test fun responsesAreLabelledAsUntrusted() {
        write("a.http", "GET BASE/echo\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        val result = Json.parseToJsonElement(text).jsonObject["results"]!!.jsonArray.single().jsonObject
        val remote = result["remote"]!!.jsonObject
        assertEquals("true", remote["untrusted"]!!.jsonPrimitive.content)
        assertTrue(remote.keys.containsAll(setOf("notice", "headers", "body")))
        assertTrue(result.keys.none { it == "body" || it == "headers" }, "remote text only lives under \"remote\"")
    }

    @Test fun runSelectsByNameOrIndexAndReportsAMissingOne() {
        write("a.http", "### a\n# @name a\nGET BASE/echo\n")
        assertFalse(call(server(), "run", "file" to "a.http", "request" to "a").second)
        assertTrue(call(server(), "run", "file" to "a.http", "request" to "9").second)
        assertTrue(call(server(), "run", "file" to "a.http").second)
        assertNotNull(call(server(), "run_all", "file" to "a.http").first)
    }

    // ---- review fixes

    @Test fun anUnreadableFileIsAnErrorAndTheServerKeepsServing() {
        Files.write(root.resolve("bad.http"), byteArrayOf(0x47, 0x45, 0x54, 0x20, -1, -2, -3, 10))
        write("good.http", "GET BASE/echo\n")
        val s = server()
        val (text, error) = call(s, "list_requests", "file" to "bad.http")
        assertTrue(error); assertTrue("cannot be read" in text)
        assertFalse(call(s, "list_requests", "file" to "good.http").second)
    }

    @Test fun environmentFilesAboveTheRootAreNotUsed() {
        val sub = Files.createDirectory(root.resolve("requests"))
        write("http-client.private.env.json", """{"dev":{"key":"TOPSECRET"}}""")
        Files.writeString(sub.resolve("a.http"), "GET $base/echo?k={{key}}\n")
        val (options, close) = ServerOptions.parse(listOf("--root", sub.toString())).getOrThrow().let { it to buildServer(it) }
        closers += close.second
        val s = close.first
        assertTrue("TOPSECRET" !in call(s, "list_environments", "file" to "a.http").first)
        assertEquals("""{
    "environments": []
}""", call(s, "list_environments", "file" to "a.http").first)
        assertTrue(call(s, "run", "file" to "a.http", "request" to "1", "environment" to "dev").second)
        assertEquals(sub.toRealPath(), options.roots.single())
    }

    @Test fun anEnvironmentFileInTheSameRootStillWorks() {
        val sub = Files.createDirectory(root.resolve("requests"))
        write("http-client.env.json", """{"dev":{"k":"v"}}""")
        Files.writeString(sub.resolve("a.http"), "GET $base/echo\n")
        assertTrue("dev" in call(server(), "list_environments", "file" to "requests/a.http").first)
    }

    @Test fun aBodyDeclaringAnotherCharsetDoesNotSmuggleAValueOut() {
        write("http-client.env.json", """{"dev":{"base":"$base"}}""")
        write("http-client.private.env.json", """{"dev":{"key":"TOPSECRET"}}""")
        write("a.http", "GET {{base}}/utf16?k={{key}}\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text); assertTrue("\"status\": 200" in text, text) // a real response came back
        assertFalse("TOPSECRET" in text, text)
    }

    @Test fun aSecretCutInTwoByTheResponseLimitIsNotLeftBehindAsAFragment() {
        http.createContext("/straddle") { ex -> val b = ("x".repeat(95) + "TOPSECRET and the rest").toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        write("http-client.env.json", """{"dev":{"base":"$base","key":"TOPSECRET"}}""")
        write("a.http", "GET {{base}}/straddle?k={{key}}\n")
        val (text, error) = call(server { copy(maxResponseBytes = 100) }, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text); assertFalse("TOPSE" in text, text)
    }

    @Test fun aValueInTheMiddleOfTheKeptTextIsHiddenWholeNotCutInTwoByTheTailDrop() {
        http.createContext("/inside") { ex -> val b = ("x".repeat(70) + "TOPSECRET" + "x".repeat(30)).toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        write("http-client.env.json", """{"dev":{"base":"$base","key":"TOPSECRET"}}""")
        write("a.http", "GET {{base}}/inside?k={{key}}\n")
        val (text, error) = call(server { copy(maxResponseBytes = 100) }, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text); assertFalse("TOP" in text, text)
    }

    @Test fun aValueThatStartsBeforeTheKeptEdgeIsHiddenWholeAndNothingAfterItIsShown() {
        // the longest substituted value is the base URL; the kept part is the limit minus 12 times its length
        val keep = 400 - 12 * base.length
        val start = keep - 4 // TOPSECRET starts 4 characters before the edge and runs past it
        assertTrue(start > 0, "the test needs a limit that leaves something to keep")
        http.createContext("/edge") { ex -> val b = ("x".repeat(start) + "TOPSECRET" + "y".repeat(400 - start - 9)).toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        write("http-client.env.json", """{"dev":{"base":"$base","key":"TOPSECRET"}}""")
        write("a.http", "GET {{base}}/edge?k={{key}}\n")
        val (text, error) = call(server { copy(maxResponseBytes = 400) }, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text)
        val body = Json.parseToJsonElement(text).jsonObject["results"]!!.jsonArray.single().jsonObject["remote"]!!.jsonObject["body"]!!.jsonPrimitive.content
        assertEquals("x".repeat(start) + "{{key}}", body)
        assertFalse("TOP" in text, text); assertFalse("yyy" in text, text)
    }

    @Test fun overlappingValuesWithALongNameDoNotLetAFragmentThroughTheTailGuard() {
        val longName = "N".repeat(200)
        http.createContext("/overlap") { ex -> val b = ("x".repeat(95) + "TOPSE").toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        write("http-client.env.json", """{"dev":{"base":"$base","key":"TOPSECRET","$longName":"E"}}""")
        write("a.http", "GET {{base}}/overlap?k={{key}}&e={{$longName}}\n")
        val (text, error) = call(server { copy(maxResponseBytes = 100) }, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text); assertFalse("TOPS" in text, text)
    }

    @Test fun aUrlEncodedNonAsciiValueCutByTheResponseLimitLeavesNoFragment() {
        val encoded = java.net.URLEncoder.encode("éééé", Charsets.UTF_8) // 24 characters for 4
        http.createContext("/enc") { ex -> val b = ("x".repeat(80) + encoded + "tail").toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        write("http-client.env.json", """{"dev":{"base":"$base","key":"éééé"}}""")
        write("a.http", "GET {{base}}/enc?k={{key}}\n")
        val (text, error) = call(server { copy(maxResponseBytes = 100) }, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text); assertTrue("\"status\": 200" in text, text); assertFalse("%C" in text, text)
    }

    @Test fun aRefusalDoesNotEchoAnAllowedProcessVariableTheHostCameFrom() {
        write("a.http", "GET http://{{\$env.TARGET}}/\n")
        val options = ServerOptions.parse(listOf("--root", root.toString(), "--allow-env", "TARGET")).getOrThrow()
        val env = com.dan323.httpclient.adapters.SystemEnvironmentVariables(setOf("TARGET"), source = { mapOf("TARGET" to "Secret-Host.example.com") })
        val (s, c) = buildServer(options, env); closers += c
        val (text, error) = call(s, "run", "file" to "a.http", "request" to "1")
        assertTrue(error); assertFalse("secret-host" in text.lowercase(), text); assertTrue("{{\$env.TARGET}}" in text, text)
    }

    @Test fun anEnvironmentFileThatIsALinkOutOfTheRootIsIgnoredAndItsSiblingStaysUsable() {
        Files.writeString(outside.resolve("p.json"), """{"dev":{"key":"TOPSECRET","extra":"1"}}""")
        write("http-client.env.json", """{"dev":{"base":"$base"}}""")
        Files.createSymbolicLink(root.resolve("http-client.private.env.json"), outside.resolve("p.json"))
        write("a.http", "GET {{base}}/echo?k={{key}}\n")
        val s = server()
        assertTrue("dev" in call(s, "list_environments", "file" to "a.http").first)
        val (text, error) = call(s, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertTrue(error); assertFalse("TOPSECRET" in text); assertEquals(0, hits.get()) // {{key}} is undefined: the outside file was not read
    }

    @Test fun aHostWrittenOutIsCheckedBeforeAWriteEvenWhenTheUrlHasAResponseReference() {
        write("a.http", "# @name p\nPOST BASE/post\n\n###\nGET http://example.invalid/{{p.response.status}}\n")
        val (text, error) = call(server("--allow-write"), "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("example.invalid" in text); assertEquals(0, hits.get(), "the POST must not go out")
    }

    @Test fun aHostTakenFromAResponseIsRefusedBeforeAWrite() {
        write("a.http", "# @name p\nPOST BASE/post\n\n###\nGET {{p.response.headers.X-Next}}\n")
        val (_, error) = call(server("--allow-write"), "run_all", "file" to "a.http")
        assertTrue(error); assertEquals(0, hits.get(), "the POST could not be undone")
    }

    @Test fun aHostTakenFromAResponseIsCheckedWhenSentIfOnlyReadsCameBefore() {
        write("a.http", "# @name n\nGET BASE/next\n\n###\nGET {{n.response.headers.X-Next}}\n")
        val (text, error) = call(server(), "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("not an allowed host" in text, text); assertEquals(1, hits.get())
    }

    @Test fun aHostFromAnAllowedProcessVariableIsCheckedBeforeAWrite() {
        write("a.http", "POST BASE/post\n\n###\nGET http://{{\$env.PATH}}/\n")
        val (_, error) = call(server("--allow-write", "--allow-env", "PATH"), "run_all", "file" to "a.http")
        assertTrue(error); assertEquals(0, hits.get())
    }

    @Test fun oneAnswerWhetherAFileIsMissingOrOutsideTheRoots() {
        Files.writeString(outside.resolve("o.http"), "GET $base/echo\n")
        val s = server()
        val existing = call(s, "list_requests", "file" to outside.resolve("o.http").toString())
        val missing = call(s, "list_requests", "file" to outside.resolve("nope.http").toString())
        assertEquals(existing, missing)
    }

    @Test fun aRelativePathTriesEveryRoot() {
        val second = Files.createTempDirectory("mcp-second").toRealPath()
        try {
            Files.writeString(second.resolve("b.http"), "GET $base/echo\n")
            Files.createSymbolicLink(root.resolve("b.http"), outside) // a first candidate that leads out of its root
            val (o, c) = ServerOptions.parse(listOf("--root", root.toString(), "--root", second.toString())).getOrThrow().let { it to buildServer(it) }
            closers += c.second
            assertFalse(call(c.first, "list_requests", "file" to "b.http").second)
            assertEquals(2, o.roots.size)
        } finally { second.toFile().deleteRecursively() }
    }

    // ---- confirmation of writes (elicitation)

    private fun elicitingServer(vararg extra: String, answer: (JsonObject) -> JsonObject?, asked: MutableList<JsonObject>): McpServer {
        val s = server("--allow-write", *extra)
        s.peer = McpServer.Peer { method, params -> assertEquals("elicitation/create", method); asked += params; answer(params) }
        s.handle("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}}}}""")
        return s
    }

    private fun yes() = Json.parseToJsonElement("""{"action":"accept","content":{"confirm":true}}""").jsonObject

    @Test fun aWriteAsksFirstShowingMethodAndUrlAsWrittenAndRunsOnYes() {
        write("http-client.env.json", """{"dev":{"base":"$base","key":"TOPSECRET"}}""")
        write("a.http", "POST {{base}}/post?k={{key}}\n")
        val asked = mutableListOf<JsonObject>()
        val s = elicitingServer(answer = { yes() }, asked = asked)
        val (text, error) = call(s, "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text); assertEquals(1, hits.get())
        val message = asked.single()["message"]!!.jsonPrimitive.content
        assertTrue("POST {{base}}/post?k={{key}}" in message, message); assertFalse("TOPSECRET" in message)
    }

    @Test fun declineCancelOrNoAnswerSendsNothing() {
        write("a.http", "### a\nGET BASE/echo\n\n###\nPOST BASE/post\n")
        for (answer in listOf<JsonObject?>(
            Json.parseToJsonElement("""{"action":"decline"}""").jsonObject, Json.parseToJsonElement("""{"action":"cancel"}""").jsonObject,
            Json.parseToJsonElement("""{"action":"accept","content":{"confirm":false}}""").jsonObject,
            Json.parseToJsonElement("""{"action":"accept"}""").jsonObject,
            Json.parseToJsonElement("""{"action":"accept","content":{"confirm":"true"}}""").jsonObject, null,
        )) {
            val s = elicitingServer(answer = { answer }, asked = mutableListOf())
            val (text, error) = call(s, "run_all", "file" to "a.http")
            assertTrue(error, answer.toString()); assertTrue("did not allow" in text)
        }
        assertEquals(0, hits.get(), "not even the GET before the POST goes out")
    }

    @Test fun readsAreNotAskedAbout() {
        write("a.http", "GET BASE/echo\n")
        val asked = mutableListOf<JsonObject>()
        val s = elicitingServer(answer = { yes() }, asked = asked)
        assertFalse(call(s, "run", "file" to "a.http", "request" to "1").second)
        assertTrue(asked.isEmpty())
    }

    @Test fun aClientThatCannotAskIsRefusedUnlessTheUserAllowedThat() {
        write("a.http", "GET BASE/echo\n\n###\nPOST BASE/post\n")
        val closed = server("--allow-write")
        closed.peer = McpServer.Peer { _, _ -> error("must not be asked") }
        val (text, error) = call(closed, "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("--allow-unconfirmed-write" in text); assertEquals(0, hits.get())
        val open = server("--allow-write", "--allow-unconfirmed-write")
        open.peer = McpServer.Peer { _, _ -> error("must not be asked") }
        assertFalse(call(open, "run_all", "file" to "a.http").second)
        assertEquals(2, hits.get())
    }

    @Test fun readsNeedNoConfirmationCapability() {
        write("a.http", "GET BASE/echo\n")
        assertFalse(call(server("--allow-write"), "run", "file" to "a.http", "request" to "1").second)
    }

    @Test fun overStdioTheServerAsksOnTheSameChannelAnswersAPingMeanwhileAndReadsTheAnswer() {
        write("a.http", "POST BASE/post\n")
        val s = server("--allow-write")
        val input = java.io.PipedWriter(); val reader = java.io.BufferedReader(java.io.PipedReader(input))
        val bytes = java.io.ByteArrayOutputStream()
        val thread = Thread { s.serve(reader, java.io.PrintStream(bytes, true)) }.also { it.start() }
        fun send(line: String) { input.write(line + "\n"); input.flush() }
        fun waitFor(text: String) { val end = System.currentTimeMillis() + 10_000; while (text !in bytes.toString() && System.currentTimeMillis() < end) Thread.sleep(20) }
        try {
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{"elicitation":{}}}}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run","arguments":{"file":"a.http","request":"1"}}}""")
            waitFor("elicitation/create")
            assertTrue("elicitation/create" in bytes.toString())
            assertEquals(0, hits.get(), "nothing is sent while the question is open")
            send("""{"jsonrpc":"2.0","id":77,"method":"ping"}""")
            waitFor("\"id\":77")
            assertTrue("\"id\":77,\"result\":{}" in bytes.toString(), "a ping is answered while the user is deciding")
            send("""{"jsonrpc":"2.0","id":"httpc-1","result":{"action":"accept","content":{"confirm":true}}}""")
            waitFor("\"id\":2,")
            assertEquals(1, hits.get()); assertTrue("\"id\":2," in bytes.toString())
        } finally {
            input.close(); thread.join(5000)
            assertFalse(thread.isAlive, "the serve loop ends when its input does")
        }
    }
}
