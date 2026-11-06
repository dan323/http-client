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

    @Test fun unknownNamesKeepTheChoiceLabelInsteadOfMissing() {
        val cur = EnvironmentChoice.Named("dev")
        val items = environmentItems(null, cur)
        assertEquals("dev", selectedItem(items, cur).label)
    }

    @Test fun environmentsNamedLikeSyntheticEntriesDoNotCollide() {
        val cur = EnvironmentChoice.Named(NONE_LABEL)
        val items = environmentItems(listOf(NONE_LABEL, "x (missing)"), cur)
        assertEquals(EnvironmentChoice.None, items[selectedIndex(items, EnvironmentChoice.None)].choice)
        assertEquals(1, selectedIndex(items, cur))
        assertEquals(2, selectedIndex(items, EnvironmentChoice.Named("x (missing)")))
    }

    @Test fun existingNameIsSelected() {
        val cur = EnvironmentChoice.Named("dev")
        assertEquals("dev", selectedItem(environmentItems(listOf("dev"), cur), cur).label)
    }
}
