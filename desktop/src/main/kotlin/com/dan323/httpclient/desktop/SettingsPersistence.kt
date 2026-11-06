package com.dan323.httpclient.desktop

import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.SettingsStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Keeps the current [DesktopSettings] in memory and writes them through [store] on a background thread: changes
 * are debounced by [debounceMillis] so a burst (window drag) causes one write, and [close] flushes a pending
 * change. Saves are serialised and each takes its snapshot only once it holds the save lock, so the newest
 * snapshot is always the one written last, whichever thread saves. [close] waits at most [closeWaitMillis] for a
 * save in flight. Never throws and never touches the UI thread; a failed save is reported once through [onWarning].
 */
class SettingsPersistence(
    private val store: SettingsStore,
    private val debounceMillis: Long = 500,
    private val onWarning: (String) -> Unit = { System.err.println("warning: $it") },
    private val closeWaitMillis: Long = 2000,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "settings-saver").apply { isDaemon = true } }
    private val lock = Any()
    private val saveLock = ReentrantLock()
    private var pending: ScheduledFuture<*>? = null
    private var dirty = false
    private val warned = AtomicBoolean(false)
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

    /** Saves the newest snapshot if there is a change, waiting at most [waitMillis] for a save in flight. */
    private fun flushNow(waitMillis: Long = Long.MAX_VALUE) {
        if (!saveLock.tryLock(waitMillis, TimeUnit.MILLISECONDS)) return
        try {
            val snapshot = synchronized(lock) { if (!dirty) return; dirty = false; current }
            val problem = try { store.save(snapshot) } catch (e: Exception) { e.message ?: e::class.simpleName }
            if (problem != null && warned.compareAndSet(false, true)) onWarning(problem)
        } finally {
            saveLock.unlock()
        }
    }

    /** Writes a pending change now (on the calling thread). */
    fun flush() {
        synchronized(lock) { pending?.cancel(false); pending = null }
        flushNow()
    }

    override fun close() {
        synchronized(lock) { pending?.cancel(false); pending = null; closed = true }
        flushNow(closeWaitMillis)
        executor.shutdown()
    }
}
