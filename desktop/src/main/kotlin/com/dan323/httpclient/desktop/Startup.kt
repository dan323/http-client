package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.JsonSettingsStore
import com.dan323.httpclient.core.port.out.SettingsStore
import com.dan323.httpclient.core.port.out.WindowSettings
import java.nio.file.Path

/** Builds the app state for a start: settings never block startup, a path argument wins over the remembered last file. */
fun createAppState(args: Array<String>, newStore: () -> SettingsStore = { JsonSettingsStore() }): AppState {
    val persistence = try { SettingsPersistence(newStore()) } catch (_: Throwable) { null }
    return AppState(settings = persistence).also { st ->
        val arg = args.firstOrNull()
        if (arg != null) st.open(Path.of(arg)) else st.restoreLastFile()
    }
}

/**
 * Follows the window so that the saved geometry is the normal (not maximized) one: a maximized window keeps the
 * last normal size and only sets the flag, and a zero-sized window (minimized, not laid out) is ignored.
 */
class WindowTracker(private var normal: Placement) {
    fun observe(width: Int, height: Int, x: Int?, y: Int?, maximized: Boolean): WindowSettings {
        if (!maximized && width > 0 && height > 0) normal = Placement(width, height, x, y, false)
        return WindowSettings(normal.width, normal.height, normal.x, normal.y, maximized)
    }
}

/** Runs [action] on a daemon thread and waits at most [timeoutMillis]; returns whether it finished in time. */
fun runWithin(timeoutMillis: Long, action: () -> Unit): Boolean {
    val worker = Thread { action() }.apply { isDaemon = true; start() }
    worker.join(timeoutMillis)
    return !worker.isAlive
}
