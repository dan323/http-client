package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.EnvironmentChoice
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentItemsTest {
    @Test fun noneIsFirstAndSelectedWhenChosen() {
        val items = environmentItems(listOf("dev", "prod"), EnvironmentChoice.None)
        assertEquals(listOf(NONE_LABEL, "dev", "prod"), items.map { it.label })
        assertEquals(EnvironmentChoice.None, selectedItem(items, EnvironmentChoice.None).choice)
    }

    @Test fun staleNameStaysVisibleInsteadOfFallingBack() {
        val cur = EnvironmentChoice.Named("old")
        val items = environmentItems(listOf("dev"), cur)
        assertEquals("old (missing)", selectedItem(items, cur).label)
        assertEquals(cur, selectedItem(items, cur).choice)
    }

    @Test fun existingNameIsSelected() {
        val cur = EnvironmentChoice.Named("dev")
        assertEquals("dev", selectedItem(environmentItems(listOf("dev"), cur), cur).label)
    }
}
