package com.dan323.httpclient.core.port.out

import com.dan323.httpclient.core.domain.EditableEnvironments
import com.dan323.httpclient.core.domain.EnvironmentEdit
import java.nio.file.Path

/** The two files an environment folder can hold: the shared one (committed) and the private one (secrets, overriding the shared values). */
enum class EnvironmentScope { SHARED, PRIVATE }

/**
 * What one environment file held when it was read: the [environments] as written (not merged with the other file; `$shared`
 * included as an entry), only variables with a text, number or boolean value, and a [version] that identifies the content
 * (null when the file does not exist). Pass the version back to [EnvironmentFileStore.write].
 */
class EnvironmentFileContent(val environments: EditableEnvironments, val version: String?)

/** The file changed (or appeared, or vanished) after it was read; nothing was written. */
class EnvironmentFileChangedException(val file: Path) :
    RuntimeException("The environment file $file changed after it was read; reload it before saving.")

/**
 * Reads and changes the environment files of one folder. Writing keeps everything an edit does not touch exactly as it
 * was (values of other types, nulls, other keys, their order) and never overwrites a file that cannot be read.
 */
interface EnvironmentFileStore {
    /** The folder whose files this store edits. */
    val directory: Path

    /** The file of [scope] as the editor shows it; an absent file is empty with a null version. Throws [EnvironmentFileException] when it cannot be read. */
    fun read(scope: EnvironmentScope): EnvironmentFileContent

    /**
     * Applies [edits] to the file of [scope] (creating it when absent) and returns its new version (null only when there were no edits and the file does not exist). Nothing is written when
     * the file no longer has [expectedVersion] ([EnvironmentFileChangedException]), when it cannot be read
     * ([EnvironmentFileException]), when it is a link, or when an edit cannot be applied
     * ([com.dan323.httpclient.core.domain.EnvironmentEditException]).
     */
    fun write(scope: EnvironmentScope, edits: List<EnvironmentEdit>, expectedVersion: String?): String?
}

/** Finds the store for the environment files that apply to a `.http` file: the nearest enclosing folder that has one, else the file's own folder. */
fun interface EnvironmentFileStoreLocator {
    fun storeFor(httpFile: Path): EnvironmentFileStore
}
