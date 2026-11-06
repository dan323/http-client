package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.SettingsStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the current [DesktopSettings] in memory and writes them through [store] on a background thread: changes
 * are debounced by [debounceMillis] so a burst (window drag) causes one write, and [close] flushes a pending
 * change. Never throws and never touches the UI thread; a failed save is reported once through [onWarning].
 */
class SettingsPersistence(
    private val store: SettingsStore,
    private val debounceMillis: Long = 500,
    private val onWarning: (String) -> Unit = { System.err.println("warning: $it") },
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "settings-saver").apply { isDaemon = true } }
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var dirty = false
    private var warned = false
    private var closed = false

    /** The warning from loading (corrupt or newer file), if any. */
    val loadWarning: String?

    @Volatile var current: DesktopSettings
        private set

    init {
        val loaded = try { store.load() } catch (e: Exception) {
            com.dan323.httpclient.core.port.out.LoadedSettings(DesktopSettings(), "Could not read settings: ${e.message ?: e::class.simpleName}")
        }
        current = loaded.settings
        loadWarning = loaded.warning
    }

    fun update(change: (DesktopSettings) -> DesktopSettings) {
        synchronized(lock) {
            val next = change(current)
            if (next == current || closed) return
            current = next
            dirty = true
            pending?.cancel(false)
            pending = try {
                executor.schedule({ flushNow() }, debounceMillis, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) { null }
        }
    }

    private fun flushNow() {
        val snapshot = synchronized(lock) { if (!dirty) return; dirty = false; current }
        val problem = try { store.save(snapshot) } catch (e: Exception) { e.message ?: e::class.simpleName }
        if (problem != null && !warned) { warned = true; onWarning(problem) }
    }

    /** Writes a pending change now (on the calling thread). */
    fun flush() {
        synchronized(lock) { pending?.cancel(false); pending = null }
        flushNow()
    }

    override fun close() {
        flush()
        synchronized(lock) { closed = true }
        executor.shutdown()
    }
}
