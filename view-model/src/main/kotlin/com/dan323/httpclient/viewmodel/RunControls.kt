package com.dan323.httpclient.viewmodel

/**
 * What the run buttons of a front end offer. [running] is true from the moment a send starts until its result
 * (or its cancellation) is shown; while it is true, only Cancel is available.
 */
data class RunControls(val running: Boolean, val hasFile: Boolean = true) {
    /** Send, Send all and Reset chain. */
    val canSend: Boolean get() = hasFile && !running

    /** Cancel is offered only while a run is in progress. */
    val canCancel: Boolean get() = running

    /** Opening, creating and saving files and editing the text are refused while running. */
    val canEdit: Boolean get() = !running

    val sendLabel: String get() = if (running) "Running…" else "Send"

    companion object {
        const val CANCEL_LABEL = "Cancel"
    }
}

/**
 * The line shown for a cancelled run (a cancelled run is an outcome, not an error): `Cancelled`, with how many
 * requests had finished before it, because those results stay on screen.
 */
fun cancelledLabel(completed: Int): String = when (completed) {
    0 -> "Cancelled"
    1 -> "Cancelled (1 request had finished)"
    else -> "Cancelled ($completed requests had finished)"
}
