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
 * What the desktop app remembers between runs. Only paths, names and window geometry: never variable values,
 * headers, bodies or any other secret. Paths are plain text (canonical absolute paths as written by the app).
 *
 * @property environments the last chosen environment name, by file path
 * @property recentFiles most recent first, without duplicates, at most [MAX_RECENT] entries
 */
data class DesktopSettings(
    val lastFile: String? = null,
    val environments: Map<String, String> = emptyMap(),
    val window: WindowSettings? = null,
    val recentFiles: List<String> = emptyList(),
) {
    /** Marks [path] as the last opened file and moves it to the front of the recent files. */
    fun opened(path: String): DesktopSettings = copy(lastFile = path, recentFiles = bounded(listOf(path) + recentFiles))

    fun withoutRecent(path: String): DesktopSettings =
        copy(recentFiles = recentFiles - path, lastFile = lastFile?.takeIf { it != path })

    /** Remembers [name] for [path]; null forgets the choice. */
    fun withEnvironment(path: String, name: String?): DesktopSettings =
        copy(environments = if (name == null) environments - path else environments + (path to name))

    fun withWindow(window: WindowSettings?): DesktopSettings = copy(window = window)

    companion object {
        const val MAX_RECENT = 10

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
