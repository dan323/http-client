package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class BodySearchTest {
    @Test fun findsNonOverlappingMatchesIgnoringCaseByDefault() {
        assertEquals(listOf(0..1, 3..4), findMatches("aAxAa", "aa"))
        assertEquals(listOf(0..1), findMatches("aAxAa", "aA", matchCase = true).take(1))
        assertEquals(listOf(0..1), findMatches("aaa", "aa"))   // matches do not overlap
        assertEquals(emptyList(), findMatches("abc", ""))
        assertEquals(emptyList(), findMatches("abc", "z"))
    }

    @Test fun stopsAtTheLimit() {
        assertEquals(MAX_SEARCH_MATCHES, findMatches("a".repeat(MAX_SEARCH_MATCHES + 10), "a").size)
    }

    @Test fun steppingWrapsAround() {
        assertEquals(1, stepMatch(0, 3, true))
        assertEquals(0, stepMatch(2, 3, true))
        assertEquals(2, stepMatch(0, 3, false))
        assertEquals(0, stepMatch(5, 0, true))
    }

    @Test fun labels() {
        assertEquals("", matchLabel("", 0, 0))
        assertEquals("No matches", matchLabel("x", 0, 0))
        assertEquals("2 of 5", matchLabel("x", 1, 5))
        assertEquals("1 of $MAX_SEARCH_MATCHES+", matchLabel("x", 0, MAX_SEARCH_MATCHES))
    }
}
