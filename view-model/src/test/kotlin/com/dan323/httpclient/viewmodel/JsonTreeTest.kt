package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonTreeTest {
    private val doc = parseJsonContainer("""{"a":1,"b":{"c":"x","d":[true,null]},"e":{},"f":"""" + "y".repeat(400) + """"}""")!!

    // Long strings are cut to 300 characters including the opening quote.
    private val longF = "f: \"" + "y".repeat(299) + "…\""

    private fun summary(rows: List<JsonRow>) = rows.map { (if (it.key != null) "${it.key}: " else "") + it.text + (if (it.comma) "," else "") }

    @Test fun expandedTreeListsEveryLineWithCommas() {
        val rows = flattenJson(doc, emptySet())
        assertEquals(
            listOf("{", "a: 1,", "b: {", "c: \"x\",", "d: [", "true,", "null", "]", "},", "e: {},", longF, "}"),
            summary(rows),
        )
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 2, 1, 1, 1, 0), rows.map { it.depth })
    }

    @Test fun collapsedNodeHidesChildrenAndShowsSummary() {
        val rows = flattenJson(doc, setOf("$.b"))
        assertEquals(listOf("{", "a: 1,", "b: {…},", "e: {},", longF, "}"), summary(rows))
        val collapsed = rows.first { it.key == "b" }
        assertEquals(JsonKind.COLLAPSED, collapsed.kind)
        assertEquals("2 keys", collapsed.summary)
        assertEquals(true, collapsed.toggle)
    }

    @Test fun kindsAndPaths() {
        val rows = flattenJson(doc, emptySet())
        assertEquals(listOf(JsonKind.NUMBER, JsonKind.STRING, JsonKind.BOOLEAN, JsonKind.NULL, JsonKind.EMPTY),
            listOf("$.a", "$.b.c", "$.b.d[0]", "$.b.d[1]", "$.e").map { p -> rows.first { it.path == p && it.kind != JsonKind.CLOSE }.kind })
    }

    @Test fun onlyObjectsAndArraysGetATree() {
        assertNull(parseJsonContainer("plain text"))
        assertNull(parseJsonContainer("42"))
        assertNull(parseJsonContainer("{broken"))
        assertEquals(1, flattenJson(parseJsonContainer("[]")!!, emptySet()).size)
    }
}
