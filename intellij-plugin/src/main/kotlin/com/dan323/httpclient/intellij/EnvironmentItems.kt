package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.EnvironmentChoice

/** Label of the "no environment" entry, used by both the tool-window combo and the popup action. */
const val NONE_LABEL = "(none)"

/** One selectable entry: what is shown and the choice it stands for. Entries are matched by index or identity, never by label. */
data class EnvironmentItem(val label: String, val choice: EnvironmentChoice)

/**
 * The entries offered for [names], always starting with "(none)". [names] is null when the environments could not
 * be determined (malformed env file, no `.http` file open): the current choice then keeps its plain label, since
 * nothing says it is missing. When [names] is known, a current choice absent from it is kept as a visible
 * "(missing)" entry, so the UI never pretends another environment is selected.
 */
fun environmentItems(names: List<String>?, current: EnvironmentChoice): List<EnvironmentItem> {
    val items = mutableListOf(EnvironmentItem(NONE_LABEL, EnvironmentChoice.None))
    names.orEmpty().mapTo(items) { EnvironmentItem(it, EnvironmentChoice.Named(it)) }
    if (current is EnvironmentChoice.Named && current.name !in names.orEmpty()) {
        items += EnvironmentItem(if (names == null) current.name else "${current.name} (missing)", current)
    }
    return items
}

/** Index of the entry that shows [current]. */
fun selectedIndex(items: List<EnvironmentItem>, current: EnvironmentChoice): Int =
    items.indexOfFirst { it.choice == current }.coerceAtLeast(0)

/** The entry that shows [current]. */
fun selectedItem(items: List<EnvironmentItem>, current: EnvironmentChoice): EnvironmentItem =
    items[selectedIndex(items, current)]

/** Tooltip for the picker: the read error when there is one, else a warning when [current] is not among [names]. */
fun environmentHint(names: List<String>?, current: EnvironmentChoice, error: String? = null): String? =
    error ?: if (names != null && current is EnvironmentChoice.Named && current.name !in names)
        "'${current.name}' is not defined for this file; running will fail until you pick another" else null
