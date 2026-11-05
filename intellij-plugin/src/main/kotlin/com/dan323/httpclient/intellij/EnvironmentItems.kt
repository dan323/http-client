package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.EnvironmentChoice

/** Label of the "no environment" entry, used by both the tool-window combo and the popup action. */
const val NONE_LABEL = "(none)"

/** One selectable entry: what is shown and the choice it stands for. */
data class EnvironmentItem(val label: String, val choice: EnvironmentChoice)

/**
 * The entries offered for [names], always starting with "(none)". A current choice naming an environment that is
 * not in [names] is kept as a visible "(missing)" entry, so the UI never pretends another environment is selected.
 */
fun environmentItems(names: List<String>, current: EnvironmentChoice): List<EnvironmentItem> {
    val items = mutableListOf(EnvironmentItem(NONE_LABEL, EnvironmentChoice.None))
    names.mapTo(items) { EnvironmentItem(it, EnvironmentChoice.Named(it)) }
    if (current is EnvironmentChoice.Named && current.name !in names) {
        items += EnvironmentItem("${current.name} (missing)", current)
    }
    return items
}

/** The entry that shows [current]. */
fun selectedItem(items: List<EnvironmentItem>, current: EnvironmentChoice): EnvironmentItem =
    items.first { it.choice == current }
