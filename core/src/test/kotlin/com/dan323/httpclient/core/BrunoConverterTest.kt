package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.BrunoConverter
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpFileWriter
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.PartContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrunoConverterTest {
    private fun convert(text: String) = BrunoConverter.request(text.trimIndent() + "\n", "fallback")

    @Test fun getWithHeadersAndSequence() {
        val r = convert("""
            meta {
              name: Get user
              type: http
              seq: 3
            }

            get {
              url: {{host}}/users/1
              body: none
              auth: none
            }

            headers {
              Accept: application/json
              ~X-Off: no
            }
        """)
        assertEquals(3, r.sequence)
        assertEquals("Get-user", r.template.name)
        assertEquals("GET", r.template.method)
        assertEquals("{{host}}/users/1", r.template.url)
        assertEquals(listOf(Header("Accept", "application/json")), r.template.headers)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun jsonBodyAddsContentType() {
        val r = convert("""
            post {
              url: http://h/x
              body: json
            }

            body:json {
              {
                "a": 1
              }
            }
        """)
        assertEquals(Body.Text("{\n  \"a\": 1\n}"), r.template.body)
        assertEquals(Header("Content-Type", "application/json"), r.template.headers.single())
        assertEquals("fallback", r.template.name)
    }

    @Test fun existingContentTypeIsKept() {
        val r = convert("""
            post {
              url: http://h/x
              body: text
            }

            headers {
              content-type: text/csv
            }

            body:text {
              a,b
            }
        """)
        assertEquals(listOf(Header("content-type", "text/csv")), r.template.headers)
    }

    @Test fun formUrlEncodedSkipsDisabledFields() {
        val r = convert("""
            post {
              url: http://h/x
              body: formUrlEncoded
            }

            body:form-urlencoded {
              a: 1
              ~b: 2
              c: {{v}}
            }
        """)
        assertEquals(Body.Text("a=1&c={{v}}"), r.template.body)
        assertEquals("application/x-www-form-urlencoded", r.template.headers.single().value)
    }

    @Test fun multipartTextAndFileParts() {
        val r = convert("""
            post {
              url: http://h/x
              body: multipartForm
            }

            body:multipart-form {
              title: hello
              doc: @file(data/payload.json)
            }
        """)
        val body = r.template.body as Body.Multipart
        assertEquals(PartContent.Text("hello"), body.parts[0].content)
        assertEquals(PartContent.File("data/payload.json"), body.parts[1].content)
        assertTrue("filename=\"payload.json\"" in body.parts[1].headers.single().value)
        assertEquals("multipart/form-data; boundary=${body.boundary}", r.template.headers.single().value)
    }

    @Test fun pathAndQueryParams() {
        val r = convert("""
            get {
              url: http://h/users/:id?a=1
              body: none
            }

            params:query {
              a: 1
              b: two
              ~c: off
            }

            params:path {
              id: 42
            }
        """)
        assertEquals("http://h/users/42?a=1&b=two", r.template.url)
    }

    @Test fun basicAndBearerAuth() {
        val basic = convert("""
            get {
              url: http://h
              auth: basic
            }

            auth:basic {
              username: alice
              password: {{pw}}
            }
        """)
        assertEquals(Header("Authorization", "Basic alice:{{pw}}"), basic.template.headers.single())
        val bearer = convert("""
            get {
              url: http://h
              auth: bearer
            }

            auth:bearer {
              token: {{t}}
            }
        """)
        assertEquals(Header("Authorization", "Bearer {{t}}"), bearer.template.headers.single())
    }

    @Test fun unsupportedPartsAreReportedNotDropped() {
        val r = convert("""
            get {
              url: http://h
              body: graphql
              auth: oauth2
            }

            vars:post-response {
              a: res.body.id
            }

            script:post-response {
              bru.setVar("a", 1);
            }

            tests {
              test("x", () => {});
            }
        """)
        val all = r.warnings.joinToString("\n")
        for (expected in listOf("graphql", "oauth2", "vars:post-response", "script:post-response", "'tests' (JavaScript)")) {
            assertTrue(expected in all, "missing warning about $expected in:\n$all")
        }
        assertNull(r.template.body)
    }

    @Test fun inheritedHeadersAuthAndVariablesAreApplied() {
        val inherited = BrunoConverter.settings("""
            headers {
              X-Team: a
              Accept: text/plain
              ~X-Off: no
            }

            auth {
              mode: bearer
            }

            auth:bearer {
              token: {{t}}
            }

            vars:pre-request {
              region: eu
            }
        """.trimIndent() + "\n")
        assertTrue(inherited.warnings.isEmpty())
        val r = BrunoConverter.request("get {\n  url: http://h\n  auth: inherit\n}\n\nheaders {\n  accept: application/json\n}\n\nvars:pre-request {\n  own: 1\n}\n", "f", inherited = inherited)
        assertEquals(
            listOf(Header("X-Team", "a"), Header("accept", "application/json"), Header("Authorization", "Bearer {{t}}")),
            r.template.headers,
        )
        assertEquals(mapOf("region" to "eu", "own" to "1"), r.template.fileVariables)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun aRequestWithItsOwnAuthIgnoresTheInheritedOne() {
        val inherited = BrunoConverter.settings("auth {\n  mode: bearer\n}\n\nauth:bearer {\n  token: x\n}\n")
        val noAuth = BrunoConverter.request("get {\n  url: http://h\n  auth: none\n}\n", "f", inherited = inherited)
        assertTrue(noAuth.template.headers.isEmpty())
        val without = BrunoConverter.request("get {\n  url: http://h\n}\n", "f", inherited = inherited)
        assertTrue(without.template.headers.isEmpty())
    }

    @Test fun settingsCombineAndChildWins() {
        val parent = BrunoConverter.settings("headers {\n  A: 1\n  B: 1\n}\n\nauth {\n  mode: bearer\n}\n\nauth:bearer {\n  token: p\n}\n\nvars:pre-request {\n  v: p\n}\n")
        val child = BrunoConverter.settings("headers {\n  b: 2\n}\n\nauth {\n  mode: none\n}\n\nvars:pre-request {\n  v: c\n}\n")
        val merged = parent.then(child)
        assertEquals(listOf(Header("A", "1"), Header("b", "2")), merged.headers)
        assertNull(merged.auth)
        assertTrue(merged.authSet)
        assertEquals(mapOf("v" to "c"), merged.variables)
        val inheritOnly = parent.then(BrunoConverter.settings("auth {\n  mode: inherit\n}\n"))
        assertEquals(Header("Authorization", "Bearer p"), inheritOnly.auth)
    }

    @Test fun settingsReportWhatTheyCannotConvert() {
        val s = BrunoConverter.settings("script:pre-request {\n  x()\n}\n\nauth {\n  mode: oauth2\n}\n\nvars:post-response {\n  a: b\n}\n")
        val all = s.warnings.joinToString("\n")
        for (e in listOf("script:pre-request", "oauth2", "vars:post-response")) assertTrue(e in all, all)
    }

    @Test fun fileVariablesAreWrittenWhereTheyChange() {
        val a = convert("get {\n  url: http://h/a\n}\n\nvars:pre-request {\n  v: 1\n}\n").template
        val b = convert("get {\n  url: http://h/b\n}\n\nvars:pre-request {\n  v: 1\n}\n").template
        val c = convert("get {\n  url: http://h/c\n}\n\nvars:pre-request {\n  v: 2\n}\n").template
        val text = HttpFileWriter.write(listOf(a, b, c))
        assertEquals(2, Regex("^@v = ", RegexOption.MULTILINE).findAll(text).count(), text)
        val parsed = HttpFileParser().parse(text).requests
        assertEquals(listOf("1", "1", "2"), parsed.map { it.fileVariables["v"] })
    }

    @Test fun assertionsAreMapped() {
        val r = convert("""
            get {
              url: http://h
              body: none
            }

            assert {
              res.status: eq 200
              res.body.data.id: isDefined
              res.body.items[0].n: gt 3
              res.headers.content-type: contains json
              res.body.gone: isUndefined
              ~res.status: eq 500
              res.responseTime: lt 100
              res.body.x: isJson
            }
        """)
        assertEquals(
            listOf(
                "status == 200", "body.$.data.id exists", "body.$.items[0].n > 3",
                "headers.content-type contains json", "body.$.gone !exists",
            ),
            r.template.assertions,
        )
        assertEquals(2, r.warnings.size)
    }

    @Test fun processEnvIsRewritten() {
        val r = convert("""
            get {
              url: {{process.env.API}}/x
            }
        """)
        assertEquals("{{\$processEnv API}}/x", r.template.url)
    }

    @Test fun missingRequestBlockAndUnclosedBlock() {
        val none = convert("meta {\n  name: x\n}")
        assertTrue(none.warnings.single().contains("no request block"))
        val open = convert("get {\n  url: http://h\n")
        assertTrue(open.warnings.any { it.contains("not closed") })
        assertEquals("http://h", open.template.url)
    }

    @Test fun environmentVariablesAndSecrets() {
        val e = BrunoConverter.environment("dev", """
            vars {
              host: http://localhost
              ~off: x
              home: {{process.env.HOME}}
            }
            vars:secret [
              token,
              ~skip
              key
            ]
        """.trimIndent() + "\n")
        assertEquals("dev", e.environment.name)
        assertEquals(mapOf("host" to "http://localhost", "home" to "{{\$processEnv HOME}}"), e.environment.variables)
        assertEquals(listOf("token", "key"), e.secrets)
    }

    @Test fun writtenHttpFileParsesBack() {
        val a = convert("""
            meta {
              name: Login
              seq: 1
            }

            post {
              url: {{host}}/login
              body: json
            }

            assert {
              res.status: eq 200
            }

            body:json {
              {"u": "a"}
            }
        """).template
        val b = convert("""
            post {
              url: {{host}}/up
              body: multipartForm
            }

            body:multipart-form {
              t: x
              f: @file(p.json)
            }
        """).template
        val parsed = HttpFileParser().parse(HttpFileWriter.write(listOf(a, b), listOf("converted")))
        assertEquals(2, parsed.requests.size)
        val (first, second) = parsed.requests
        assertEquals("Login", first.name)
        assertEquals("POST", first.method)
        assertEquals(listOf("status == 200"), first.assertions)
        assertEquals(Body.Text("{\"u\": \"a\"}"), first.body)
        val multipart = second.body as Body.Multipart
        assertEquals(PartContent.Text("x"), multipart.parts[0].content)
        assertEquals(PartContent.File("p.json"), multipart.parts[1].content)
    }

    @Test fun multipartFilesGetTheRootPrefixAndAWarning() {
        val text = "post {\n  url: http://h\n  body: multipartForm\n}\n\nbody:multipart-form {\n  a: @file(d/x.json)\n  b: @file(/abs/y.json)\n}\n"
        val r = BrunoConverter.request(text, "f", "../../")
        val parts = (r.template.body as Body.Multipart).parts
        assertEquals(PartContent.File("../../d/x.json"), parts[0].content)
        assertEquals(PartContent.File("/abs/y.json"), parts[1].content)
        assertEquals(1, r.warnings.size)
        assertTrue("collection root" in r.warnings.single())
    }

    @Test fun aBodyLineStartingWithHashesIsLeftOutWithAWarning() {
        val r = convert("""
            post {
              url: http://h
              body: text
            }

            body:text {
              a
              ### b
            }
        """)
        assertNull(r.template.body)
        assertTrue(r.template.headers.isEmpty(), "no Content-Type without a body")
        assertTrue(r.warnings.any { "###" in it })
    }

    @Test fun anOwnAuthorizationHeaderBeatsInheritedAuth() {
        val inherited = BrunoConverter.settings("auth {\n  mode: bearer\n}\n\nauth:bearer {\n  token: inherited\n}\n")
        val r = BrunoConverter.request("get {\n  url: http://h\n  auth: inherit\n}\n\nheaders {\n  authorization: Token mine\n}\n", "f", inherited = inherited)
        assertEquals(listOf(Header("authorization", "Token mine")), r.template.headers)
        val fromFolderHeader = BrunoConverter.settings("headers {\n  Authorization: Token folder\n}\n\nauth {\n  mode: bearer\n}\n\nauth:bearer {\n  token: x\n}\n")
        val r2 = BrunoConverter.request("get {\n  url: http://h\n  auth: inherit\n}\n", "f", inherited = fromFolderHeader)
        assertEquals(listOf(Header("Authorization", "Token folder")), r2.template.headers)
    }

    @Test fun multiLineValuesAreParsedWholeAndNotConvertedSilently() {
        val block = com.dan323.httpclient.core.application.BruParser.parse("vars:pre-request {\n  payload: \'\'\'\n    hello\n      world\n  \'\'\'\n  next: 1\n}\n").blocks.single()
        assertEquals(listOf("payload" to "hello\n  world", "next" to "1"), block.entries().map { it.key to it.value })
        val r = convert("""
            get {
              url: http://h
            }

            headers {
              X-Big: '''
                a
                b
              '''
              X-Ok: 1
            }

            vars:pre-request {
              payload: '''
                hello
                world
              '''
              ok: 2
            }
        """)
        assertEquals(listOf(Header("X-Ok", "1")), r.template.headers)
        assertEquals(mapOf("ok" to "2"), r.template.fileVariables)
        assertEquals(2, r.warnings.size)
        assertTrue(r.warnings.all { "multi-line" in it })
    }

    @Test fun multiLineEnvironmentValuesAreReported() {
        val e = BrunoConverter.environment("dev", "vars {\n  a: \'\'\'\n    x\n    y\n  \'\'\'\n  b: 1\n}\n")
        assertEquals(mapOf("b" to "1"), e.environment.variables)
        assertTrue(e.warnings.single().contains("multi-line"))
    }
}
