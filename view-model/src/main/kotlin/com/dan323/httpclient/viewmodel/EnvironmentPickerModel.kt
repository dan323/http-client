package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.port.out.EnvironmentFileException

/** What an environment picker shows: its [items], which one is [selected], and an optional tooltip [hint]. */
data class EnvironmentPickerState(val items: List<EnvironmentItem>, val selected: Int, val hint: String?)

/**
 * UI-toolkit-free logic of an environment picker (the combo in the header of an IDE editor). Everything the outside
 * world provides is a parameter, so it runs in plain unit tests:
 *
 * - [loadNames]: the environment names for the picker's file; may throw (unreadable env file, a file that has no
 *   local path); the message becomes the hint and the current choice is then never shown as "(missing)".
 * - [getChoice] / [setChoice]: the shared choice holder.
 * - [subscribe]: registers a listener for choice changes made elsewhere and returns the function that unregisters it.
 * - [background] runs [loadNames] off the UI thread, [ui] runs [render] on it.
 *
 * A reload that is overtaken by a newer one, or that finishes after [dispose], is dropped.
 */
class EnvironmentPickerModel(
    private val loadNames: () -> List<String>,
    private val getChoice: () -> EnvironmentChoice,
    private val setChoice: (EnvironmentChoice) -> Unit,
    private val subscribe: (() -> Unit) -> (() -> Unit),
    private val background: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val render: (EnvironmentPickerState) -> Unit,
) {
    @Volatile private var generation = 0
    @Volatile private var disposed = false
    @Volatile private var applying = false
    @Volatile private var current = EnvironmentPickerState(environmentItems(emptyList(), getChoice()), 0, null)
    private var unsubscribe: (() -> Unit)? = null

    val state: EnvironmentPickerState get() = current

    /** Starts following choice changes made elsewhere and loads the first list. */
    fun start() {
        unsubscribe = subscribe { ui { if (!disposed && current.items[current.selected].choice != getChoice()) reload() } }
        reload()
    }

    /** Re-reads the environments (call when the list is about to be shown). */
    fun reload() {
        if (disposed) return
        val mine = ++generation
        background {
            var names: List<String>? = null
            var hint: String? = null
            try { names = loadNames() }
            catch (e: EnvironmentFileException) { hint = e.message }
            // Anything else (e.g. InvalidPathException): keep the picker usable with the (none) entry and say why.
            catch (e: Exception) { hint = "Cannot read environments: ${e.message ?: e.javaClass.simpleName}" }
            ui {
                if (disposed || mine != generation) return@ui
                val chosen = getChoice()
                val items = environmentItems(names, chosen)
                val state = EnvironmentPickerState(items, selectedIndex(items, chosen), environmentHint(names, chosen, hint))
                current = state
                applying = true
                try { render(state) } finally { applying = false }
            }
        }
    }

    /** The user picked entry [index]. Ignored while [render] is running (setting a combo programmatically fires its listener). */
    fun select(index: Int) {
        if (applying || disposed) return
        current.items.getOrNull(index)?.let { setChoice(it.choice) }
    }

    /** Stops listening and drops pending results. */
    fun dispose() {
        disposed = true
        unsubscribe?.invoke()
        unsubscribe = null
    }
}
