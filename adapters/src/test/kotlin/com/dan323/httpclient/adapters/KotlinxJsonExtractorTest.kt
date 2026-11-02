package com.dan323.httpclient.adapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KotlinxJsonExtractorTest {
    private val x = KotlinxJsonExtractor()
    private val doc = """{"token":"abc","n":3,"user":{"tags":["a","b"],"my key":true},"nil":null}"""

    @Test fun extractsValues() {
        assertEquals("abc", x.extract(doc, "$.token"))
        assertEquals("3", x.extract(doc, "$.n"))
        assertEquals("b", x.extract(doc, "$.user.tags[1]"))
        assertEquals("true", x.extract(doc, "$.user['my key']"))
        assertEquals("""["a","b"]""", x.extract(doc, "$.user.tags"))
        assertEquals(doc, x.extract(doc, "$"))
    }

    @Test fun returnsNullWhenAbsentOrInvalid() {
        assertNull(x.extract(doc, "$.missing"))
        assertNull(x.extract(doc, "$.user.tags[5]"))
        assertNull(x.extract(doc, "$.nil"))
        assertNull(x.extract(doc, "token"))
        assertNull(x.extract("not json", "$.a"))
    }
}

class JsonPrettyTest {
    @Test fun indentsJsonAndIgnoresOtherText() {
        assertEquals("{\n    \"a\": 1\n}", prettyJson("""{"a":1}"""))
        assertNull(prettyJson("plain"))
        assertNull(prettyJson("{broken"))
    }
}
