package com.dan323.httpclient.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Completion in a real (light) editor: what is offered, what is inserted, and that typing on asks again. */
class HttpCompletionTest : BasePlatformTestCase() {
    private fun variables(n: Int) = (1..n).joinToString("") { "@v$it = x\n" }

    fun testOffersFileVariablesInsideBraces() {
        myFixture.configureByText("a.http", "@host = x\n\nGET {{h<caret>\n")
        myFixture.completeBasic()
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)   // the popup stays open (it asks again as you type): take the first
        assertEquals("@host = x\n\nGET {{host}}\n", myFixture.editor.document.text)
        assertEquals("@host = x\n\nGET {{host}}".length, myFixture.editor.caretModel.offset)
    }

    fun testNothingOutsideBraces() {
        myFixture.configureByText("a.http", "@host = x\n\nGET ho<caret>\n")
        assertEmpty(myFixture.completeBasic()?.toList().orEmpty())
    }

    fun testAllNamesAreOfferedAndTypingNarrowsThem() {
        myFixture.configureByText("a.http", variables(13) + "\nGET {{<caret>\n")
        val all = myFixture.completeBasic()!!.map { it.lookupString }
        assertTrue("v13" in all)              // not cut at 12
        myFixture.type("v13")
        assertEquals(listOf("v13"), myFixture.lookupElementStrings)
    }
}
