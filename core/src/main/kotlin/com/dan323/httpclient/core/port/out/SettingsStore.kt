package com.dan323.httpclient.core.port.out

/** Window geometry in device-independent pixels. [x] and [y] are null when the position was never set. */
data class WindowSettings(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null,
    val maximized: Boolean = false,
)

/**
 * Access the user granted in the settings of the desktop app or the IDE plugin, on top of the system property and the
 * environment variable: the OS variables `{{$env.NAME}}` may read ([envNames]) and the folders `< path` bodies and
 * `>> path` saves may use besides the `.http` file's own ([fileRoots], as written; checked when a session is created).
 * Kept only in the user's own settings, never in a file that comes with a project.
 */
data class Allowlists(val envNames: List<String> = emptyList(), val fileRoots: List<String> = emptyList())

/**
 * What the desktop app remembers between runs. Only paths, names and window geometry: never variable values,
 * headers, bodies or any other secret. Paths are plain text (canonical absolute paths as written by the app).
 *
 * @property environments the last chosen environment name, by file path, at most [MAX_ENVIRONMENTS] (least recently used dropped first)
 * @property recentFiles most recent first, without duplicates, at most [MAX_RECENT] entries
 * @property recordHistory whether ordinary runs are stored in the run history (off by default: stored responses are raw)
 * @property openFiles the files open in tabs when the app was last used, in tab order, at most [MAX_OPEN_FILES]; null in settings written before tabs existed (then [lastFile] is reopened), empty when no tab was open
 * @property activeFile the one of [openFiles] that was on screen, or null
 * @property folder the folder shown in the folder view, or null (then the folder of the file on screen is shown)
 * @property folderOpen whether the folder view is shown
 * @property allowlists the access granted in the Settings dialog; null when it was never set (then nothing is added)
 */
data class DesktopSettings(
    val lastFile: String? = null,
    val environments: Map<String, String> = emptyMap(),
    val window: WindowSettings? = null,
    val recentFiles: List<String> = emptyList(),
    val recordHistory: Boolean = false,
    val openFiles: List<String>? = null,
    val activeFile: String? = null,
    val folder: String? = null,
    val folderOpen: Boolean = false,
    val allowlists: Allowlists? = null,
) {
    /** Marks [path] as the last opened file and moves it to the front of the recent files. */
    fun opened(path: String): DesktopSettings = copy(
            lastFile = path,
            recentFiles = bounded(listOf(path) + recentFiles),
            environments = environments[path]?.let { boundedEnvironments((environments - path) + (path to it)) } ?: environments,
        )

    fun withoutRecent(path: String): DesktopSettings =
        copy(recentFiles = recentFiles - path, lastFile = lastFile?.takeIf { it != path })

    /** Remembers [name] for [path]; null forgets the choice. */
    fun withEnvironment(path: String, name: String?): DesktopSettings =
        copy(environments = if (name == null) environments - path else boundedEnvironments((environments - path) + (path to name)))

    fun withWindow(window: WindowSettings?): DesktopSettings = copy(window = window)

    fun withRecordHistory(on: Boolean): DesktopSettings = copy(recordHistory = on)

    fun withFolder(path: String?): DesktopSettings = copy(folder = path)

    fun withFolderOpen(on: Boolean): DesktopSettings = copy(folderOpen = on)

    fun withAllowlists(allowlists: Allowlists): DesktopSettings = copy(allowlists = allowlists)

    /** Remembers the open tabs ([files] in tab order, at most [MAX_OPEN_FILES]) and which one is on screen. */
    fun withOpenFiles(files: List<String>, active: String?): DesktopSettings {
        val kept = files.distinct().take(MAX_OPEN_FILES)
        return copy(openFiles = kept, activeFile = active?.takeIf { it in kept })
    }

    companion object {
        const val MAX_RECENT = 10
        const val MAX_ENVIRONMENTS = 50
        const val MAX_OPEN_FILES = 20

        /** Keeps the last [MAX_ENVIRONMENTS] entries (the map is ordered least recently used first). */
        fun boundedEnvironments(environments: Map<String, String>): Map<String, String> =
            if (environments.size <= MAX_ENVIRONMENTS) environments else environments.entries.toList().takeLast(MAX_ENVIRONMENTS).associate { it.key to it.value }

        /** De-duplicates (keeping the first occurrence) and cuts to [MAX_RECENT]. */
        fun bounded(paths: List<String>): List<String> = paths.distinct().take(MAX_RECENT)
    }
}

/** The result of [SettingsStore.load]: usable [settings] (defaults when nothing valid was stored) and a [warning] to show, if any. */
data class LoadedSettings(val settings: DesktopSettings, val warning: String? = null)

/**
 * Persists [DesktopSettings]. Implementations never throw: a store that cannot read or write degrades to defaults
 * and reports why. Safe for concurrent use.
 */
interface SettingsStore {
    fun load(): LoadedSettings

    /** Returns a message when the settings could not be saved, null on success. */
    fun save(settings: DesktopSettings): String?
}
