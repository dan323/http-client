package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplaceTest {
    @Test fun replaceMatchSwapsTheRangeAndReportsTheEndOfTheInsertedText() {
        assertEquals(Replacement("GET http://new/x", 14), replaceMatch("GET http://old/x", 11..13, "new"))
        assertEquals(Replacement("a-X-c", 3), replaceMatch("a-b-c", 2..2, "X"))
        assertEquals(Replacement("ac", 1), replaceMatch("abc", 1..1, ""))
        assertEquals(Replacement("abcZ", 4), replaceMatch("abc", 3..5, "Z"))   // a range past the end is cut to the text
    }

    @Test fun theNextMatchIsTheFirstOneAtOrAfterTheCaretAndWraps() {
        val matches = listOf(0..1, 5..6, 9..10)
        assertEquals(0, nextMatchIndex(matches, 0))
        assertEquals(1, nextMatchIndex(matches, 2))
        assertEquals(1, nextMatchIndex(matches, 5))
        assertEquals(2, nextMatchIndex(matches, 6))
        assertEquals(0, nextMatchIndex(matches, 11))      // past the last: wrap
        assertEquals(0, nextMatchIndex(emptyList(), 3))
    }

    @Test fun replacingAByAAMovesOnInsteadOfLooping() {
        val first = replaceMatch("a a", 0..0, "aa")
        val matches = findMatches(first.text, "a")
        assertEquals(listOf(0..0, 1..1, 3..3), matches)
        assertEquals(2, nextMatchIndex(matches, first.caret))
    }

    @Test fun replaceAllCountsAndIgnoresCaseUnlessAsked() {
        assertEquals(ReplacedAll("x-x-X", 2), replaceAll("a-A-X", "a", "x"))
        assertEquals(ReplacedAll("x-A-X", 1), replaceAll("a-A-X", "a", "x", matchCase = true))
        assertEquals(ReplacedAll("abc", 0), replaceAll("abc", "", "x"))
        assertEquals(ReplacedAll("abc", 0), replaceAll("abc", "z", "x"))
        assertEquals(ReplacedAll("aaaa", 2), replaceAll("aa", "a", "aa"))        // the inserted text is not searched again
        assertEquals(ReplacedAll("", 3), replaceAll("aaa", "a", ""))
    }

    @Test fun replaceAllHasNoLimitOfFiveThousand() {
        val n = MAX_SEARCH_MATCHES + 10
        assertEquals(n, replaceAll("a".repeat(n), "a", "b").count)
    }

    @Test fun theStatusLabel() {
        assertEquals("No matches", replacedLabel(0))
        assertEquals("Replaced 1 match", replacedLabel(1))
        assertEquals("Replaced 7 matches", replacedLabel(7))
    }

    @Test fun thePreviousMatchIsTheLastOneThatStartsBeforeTheCaretAndWraps() {
        val matches = listOf(0..1, 5..6, 9..10)
        assertEquals(1, previousMatchIndex(matches, 9))
        assertEquals(2, previousMatchIndex(matches, 20))
        assertEquals(2, previousMatchIndex(matches, 0))      // before the first: wrap to the last
        assertEquals(0, previousMatchIndex(matches, 1))
        assertEquals(0, previousMatchIndex(emptyList(), 4))
    }
}
