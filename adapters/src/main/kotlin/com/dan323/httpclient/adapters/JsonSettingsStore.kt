package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.LoadedSettings
import com.dan323.httpclient.core.port.out.SettingsStore
import com.dan323.httpclient.core.port.out.WindowSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.CharacterCodingException
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop settings as `settings.json` in [dir] (see [ConfigDirLocator]). Written atomically through
 * [AtomicReportWriter] (owner-only on POSIX, a missing folder is created owner-only too).
 *
 * Reading never fails, and a file that was not read as broken content is never replaced:
 * - An empty, unparsable, non-UTF-8 or foreign file (no `version`), or one written by a newer version
 *   (`version` above [VERSION]), is a content problem: defaults are used with a warning, and the next save first
 *   copies the file to `settings.json.corrupt`, then `.corrupt-1`, `.corrupt-2`, ... (an existing backup is never
 *   overwritten; the newest [MAX_BACKUPS] are kept), then replaces it.
 * - An I/O error while reading (locked file, permission or drive problem) is transient: defaults are used with a
 *   warning, nothing is backed up, and each save first reads the file again. While it is still unreadable the save
 *   is skipped and reported; once it can be read, the stored settings are merged with the in-memory ones.
 * Unknown fields in a valid file are ignored. Recents are cut to [DesktopSettings.MAX_RECENT] and the per-file
 * environments to [DesktopSettings.MAX_ENVIRONMENTS]; missing files are kept (they may be on an absent drive).
 * A folder that cannot be written is reported by [save] and never throws.
 */
class JsonSettingsStore internal constructor(
    private val dir: Path,
    private val writer: AtomicReportWriter,
    private val reader: (Path) -> String,
) : SettingsStore {
    constructor(dir: Path = ConfigDirLocator().locate(), writer: AtomicReportWriter = AtomicReportWriter()) :
        this(dir, writer, { Files.readString(it) })

    private val file: Path get() = dir.resolve(FILE_NAME)
    private val lock = Any()
    private var unusableFileSeen = false
    private var unreadable = false

    /** What reading the file gave: [settings] (null when it is unusable), a [warning], and whether it was a transient [ioError]. */
    private class Read(val settings: DesktopSettings?, val warning: String? = null, val ioError: Boolean = false)

    private fun read(): Read {
        val text = try {
            if (!Files.isRegularFile(file)) return Read(DesktopSettings())
            reader(file)
        } catch (_: CharacterCodingException) {
            return Read(null, "The settings file is not valid text; using defaults (it is kept as $FILE_NAME.corrupt when settings are next saved).")
        } catch (e: Exception) {
            return Read(null, "Could not read the settings file: ${e.message ?: e::class.simpleName}; using defaults and keeping the file as it is.", ioError = true)
        }
        val dto = try {
            json.decodeFromString<SettingsDto>(text)
        } catch (_: Exception) {
            return Read(null, "The settings file is unreadable; using defaults (it is kept as $FILE_NAME.corrupt when settings are next saved).")
        }
        if (dto.version > VERSION) {
            return Read(null, "The settings file was written by a newer version; using defaults (it is kept as $FILE_NAME.corrupt when settings are next saved).")
        }
        return Read(dto.toSettings())
    }

    override fun load(): LoadedSettings = synchronized(lock) {
        val r = read()
        unreadable = r.ioError
        unusableFileSeen = r.settings == null && !r.ioError
        LoadedSettings(r.settings ?: DesktopSettings(), r.warning)
    }

    override fun save(settings: DesktopSettings): String? = synchronized(lock) {
        try {
            var toWrite = settings
            if (unreadable) {
                val r = read()
                if (r.ioError) return "Could not save settings: the existing settings file cannot be read (${r.warning}); it was left untouched."
                unreadable = false
                unusableFileSeen = r.settings == null
                r.settings?.let { toWrite = merge(it, settings) }
            }
            ensureDir()
            if (unusableFileSeen) {
                backUp()
                unusableFileSeen = false
            }
            writer.write(file, json.encodeToString(SettingsDto.from(toWrite)))
            null
        } catch (e: Exception) {
            "Could not save settings: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Stored settings that could not be read earlier, combined with what changed since: the in-memory values win. */
    private fun merge(stored: DesktopSettings, mine: DesktopSettings) = DesktopSettings(
        lastFile = mine.lastFile ?: stored.lastFile,
        environments = stored.environments + mine.environments,
        window = mine.window ?: stored.window,
        recentFiles = DesktopSettings.bounded(mine.recentFiles + stored.recentFiles),
        recordHistory = mine.recordHistory,
    )

    private fun ensureDir() {
        val existed = Files.isDirectory(dir)
        Files.createDirectories(dir)
        if (!existed) try { Files.setPosixFilePermissions(dir, OWNER_DIR) } catch (_: UnsupportedOperationException) { }
    }

    /** Copies the file to the next free backup name and keeps only the newest [MAX_BACKUPS]. */
    private fun backUp() {
        if (!Files.isRegularFile(file)) return
        val existing = backups()
        val next = (existing.keys.maxOrNull() ?: -1) + 1
        Files.copy(file, dir.resolve(backupName(next)))
        existing.toSortedMap().keys.toList().dropLast(MAX_BACKUPS - 1).forEach { n ->
            try { Files.deleteIfExists(dir.resolve(backupName(n))) } catch (_: Exception) { }   // best effort
        }
    }

    private fun backupName(n: Int) = if (n == 0) "$FILE_NAME.corrupt" else "$FILE_NAME.corrupt-$n"

    /** Existing backups by sequence number (`.corrupt` is 0). */
    private fun backups(): Map<Int, Path> = Files.newDirectoryStream(dir, "$FILE_NAME.corrupt*").use { entries ->
        entries.mapNotNull { p ->
            val name = p.fileName.toString()
            val n = if (name == "$FILE_NAME.corrupt") 0 else name.removePrefix("$FILE_NAME.corrupt-").toIntOrNull()
            n?.let { it to p }
        }.toMap()
    }

    @Serializable
    private data class WindowDto(
        val width: Int = 0,
        val height: Int = 0,
        val x: Int? = null,
        val y: Int? = null,
        val maximized: Boolean = false,
    )

    @Serializable
    private data class SettingsDto(
        val version: Int = -1,
        val lastFile: String? = null,
        val environments: Map<String, String> = emptyMap(),
        val window: WindowDto? = null,
        val recentFiles: List<String> = emptyList(),
        val recordHistory: Boolean = false,
    ) {
        init { require(version >= 1) { "not a settings file" } }

        fun toSettings(): DesktopSettings {
            return DesktopSettings(
                lastFile = lastFile,
                environments = DesktopSettings.boundedEnvironments(environments),
                window = window?.let { WindowSettings(it.width, it.height, it.x, it.y, it.maximized) },
                recentFiles = DesktopSettings.bounded(recentFiles),
                recordHistory = recordHistory,
            )
        }

        companion object {
            fun from(s: DesktopSettings) = SettingsDto(
                VERSION, s.lastFile, DesktopSettings.boundedEnvironments(s.environments),
                s.window?.let { WindowDto(it.width, it.height, it.x, it.y, it.maximized) },
                DesktopSettings.bounded(s.recentFiles),
                s.recordHistory,
            )
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"
        const val VERSION = 1
        const val MAX_BACKUPS = 5
        private val OWNER_DIR = PosixFilePermissions.fromString("rwx------")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}
