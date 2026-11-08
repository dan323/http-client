package com.dan323.httpclient.desktop

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyntaxHighlightingTest {
    private val p = HighlightPalette.Light
    private val text = "# note\nPOST {{base}}/x\nAccept: */*\n\nbody\n###\nGET /y\n"
    private val styled = highlight(text, p)

    private fun colorAt(needle: String, from: Int = 0) =
        styled.spanStyles.lastOrNull { it.start <= text.indexOf(needle, from) && text.indexOf(needle, from) < it.end }?.item?.color

    @Test fun requestSummaryColoursOnlyTheMethod() {
        val s = requestSummary("GET", "http://x/y", p)
        assertEquals("GET http://x/y", s.text)
        assertEquals(1, s.spanStyles.size)
        assertEquals(p.method, s.spanStyles[0].item.color)
        assertEquals(0, s.spanStyles[0].start)
        assertEquals(3, s.spanStyles[0].end)
    }

    @Test fun textIsUnchanged() = assertEquals(text, styled.text)

    @Test fun coloursEachTokenKind() {
        assertEquals(p.comment, colorAt("# note"))
        assertEquals(p.method, colorAt("POST"))
        assertEquals(p.variable, colorAt("{{base}}"))
        assertEquals(p.url, colorAt("/x"))
        assertEquals(p.headerName, colorAt("Accept"))
        assertEquals(p.separator, colorAt("###"))
        assertEquals(p.method, colorAt("GET"))
    }

    @Test fun plainPartsAreNotStyled() {
        assertNull(colorAt("body"))
        assertNull(colorAt("*/*"))
    }

    @Test fun methodIsBold() {
        assertEquals(FontWeight.Bold, styled.spanStyles.first { it.start == text.indexOf("POST") }.item.fontWeight)
    }
}
