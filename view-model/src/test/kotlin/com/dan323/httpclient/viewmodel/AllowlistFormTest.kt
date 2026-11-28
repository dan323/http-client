package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.port.out.Allowlists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllowlistFormTest {
    private val anyRoot: (String) -> String? = { null }

    @Test fun `entries are trimmed, blank lines and repeats dropped, order kept`() {
        assertEquals(listOf("B", "A"), AllowlistForm.entries("  B \n\n A\nB\n   \n"))
        assertEquals(emptyList(), AllowlistForm.entries(""))
    }

    @Test fun `text and entries round trip`() {
        val saved = Allowlists(listOf("TOKEN", "HOME"), listOf("/a", "/b c"))
        val parsed = AllowlistForm.parse(AllowlistForm.text(saved.envNames), AllowlistForm.text(saved.fileRoots))
        assertEquals(saved, parsed)
        assertEquals(Allowlists(), AllowlistForm.parse("", "\n"))
    }

    @Test fun `a valid form gives the allowlists`() {
        assertEquals(
            AllowlistForm.Checked.Valid(Allowlists(listOf("API_TOKEN", "my.var-1"), listOf("/data"))),
            AllowlistForm.check("API_TOKEN\n my.var-1 \n", "/data\n", anyRoot),
        )
    }

    @Test fun `names a reference could never read are refused, each with its own error`() {
        listOf("A B", "A,B", "A=B", "A{", "A}").forEach { assertEquals(true, AllowlistForm.envNameProblem(it)?.contains("'$it'"), it) }
        assertNull(AllowlistForm.envNameProblem("PATH"))
        val checked = AllowlistForm.check("OK\nA B\nC=D", "", anyRoot)
        assertEquals(
            AllowlistForm.Checked.Invalid(listOf(
                "Environment variable 'A B': a name cannot contain spaces",
                "Environment variable 'C=D': a name cannot contain ',', '=', '{' or '}'",
            )),
            checked,
        )
        assertEquals(2, (checked as AllowlistForm.Checked.Invalid).errors.size)
    }

    @Test fun `folders are checked by the given rule and every problem is listed`() {
        val checked = AllowlistForm.check("BAD NAME", "/ok\nrel\n/", { if (it == "/ok") null else "bad root $it" })
        assertEquals(
            AllowlistForm.Checked.Invalid(listOf("Environment variable 'BAD NAME': a name cannot contain spaces", "bad root rel", "bad root /")),
            checked,
        )
    }
}
