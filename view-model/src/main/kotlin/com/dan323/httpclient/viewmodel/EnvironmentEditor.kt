package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.domain.EnvironmentEditException
import com.dan323.httpclient.core.port.out.EnvironmentFileChangedException
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentScope

/** What [EnvironmentEditor.save] did. Messages never contain variable values. */
sealed interface SaveOutcome {
    /** Nothing differs from the files. */
    data object NothingToSave : SaveOutcome

    /** The draft has [problems]; nothing was written. */
    data class Invalid(val problems: List<DraftProblem>) : SaveOutcome

    /** The file of [scope] changed since it was loaded; nothing was written. Reload to see the new state (the draft is dropped). */
    data class Changed(val scope: EnvironmentScope) : SaveOutcome

    /** A file could not be read or written. [written] are the scopes already saved; when it is not empty the editor must be reloaded before saving again. */
    data class Failed(val message: String, val written: List<EnvironmentScope> = emptyList()) : SaveOutcome

    /** The files of [written] were saved and the editor shows them as they are now. */
    data class Saved(val written: List<EnvironmentScope>) : SaveOutcome
}

/**
 * The environment editor of one folder's two files: loads them into an [EnvironmentDraft], keeps the user's changes and
 * saves them. Both files are checked against their loaded versions before the first one is written, so a change on disk
 * is reported without a half-saved result; [save] is not atomic over the two files, and a failure after the first write
 * leaves [needsReload] set. Calls may come from any thread.
 */
class EnvironmentEditor(private val store: EnvironmentFileStore) {
    @Volatile var draft: EnvironmentDraft = EnvironmentDraft.EMPTY
        private set

    /** Why the files could not be loaded, or null. While set the draft is empty and cannot be saved. */
    @Volatile var loadError: String? = null
        private set

    /** A save wrote one file and failed on the other: the draft no longer matches the files and cannot be saved until [load] again. */
    @Volatile var needsReload: Boolean = false
        private set

    /** Reads both files and replaces the draft with what they hold; returns the error message, or null. */
    @Synchronized
    fun load(): String? {
        needsReload = false
        return try {
            draft = EnvironmentDraft.of(store.read(EnvironmentScope.SHARED), store.read(EnvironmentScope.PRIVATE))
            loadError = null
            null
        } catch (e: EnvironmentFileException) {
            draft = EnvironmentDraft.EMPTY
            loadError = e.message ?: "The environment files cannot be read."
            loadError
        }
    }

    /** Replaces the draft with [change] of it. */
    @Synchronized
    fun edit(change: (EnvironmentDraft) -> EnvironmentDraft) {
        draft = change(draft)
    }

    @Synchronized
    fun save(): SaveOutcome {
        if (loadError != null) return SaveOutcome.Failed("The environment files are not loaded.")
        if (needsReload) return SaveOutcome.Failed("The files changed while saving; reload before saving again.")
        val problems = draft.check()
        if (problems.isNotEmpty()) return SaveOutcome.Invalid(problems)
        val scopes = EnvironmentScope.entries.filter { draft.edits(it).isNotEmpty() }
        if (scopes.isEmpty()) return SaveOutcome.NothingToSave
        val written = mutableListOf<EnvironmentScope>()
        try {
            for (scope in scopes) {
                if (store.read(scope).version != draft.version(scope)) return SaveOutcome.Changed(scope)
            }
            for (scope in scopes) {
                store.write(scope, draft.edits(scope), draft.version(scope))
                written += scope
            }
        } catch (e: EnvironmentFileChangedException) {
            return failed(SaveOutcome.Changed(scopes[written.size]), written)
        } catch (e: EnvironmentFileException) {
            return failed(SaveOutcome.Failed(e.message ?: "An environment file cannot be saved.", written.toList()), written)
        } catch (e: EnvironmentEditException) {
            return failed(SaveOutcome.Failed(e.message ?: "An environment file refused the changes.", written.toList()), written)
        }
        val reloadError = load()
        return if (reloadError == null) SaveOutcome.Saved(written) else SaveOutcome.Failed(reloadError, written)
    }

    private fun failed(outcome: SaveOutcome, written: List<EnvironmentScope>): SaveOutcome {
        if (written.isNotEmpty()) needsReload = true
        return outcome
    }
}
