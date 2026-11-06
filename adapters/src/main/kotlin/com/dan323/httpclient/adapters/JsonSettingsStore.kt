package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.LoadedSettings
import com.dan323.httpclient.core.port.out.SettingsStore
import com.dan323.httpclient.core.port.out.WindowSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Desktop settings as `settings.json` in [dir] (see [ConfigDirLocator]). Written atomically through
 * [AtomicReportWriter] (owner-only on POSIX, missing folder created).
 *
 * Reading never fails: an empty, unparsable or foreign file (no `version`), or one written by a newer version
 * (`version` above [VERSION]), is ignored and defaults are used with a warning. Such a file is never overwritten
 * silently: the first save after it was seen copies it to `settings.json.corrupt` (unless that backup already
 * exists, so the oldest broken file is the one kept), then replaces it. Unknown fields in a valid file are ignored.
 * Recent files and the last file that no longer exist are dropped on load; recents are cut to
 * [DesktopSettings.MAX_RECENT]. A folder that cannot be written is reported by [save] and never throws.
 */
class JsonSettingsStore(
    private val dir: Path = ConfigDirLocator().locate(),
    private val writer: AtomicReportWriter = AtomicReportWriter(),
) : SettingsStore {
    private val file: Path get() = dir.resolve(FILE_NAME)
    private val backup: Path get() = dir.resolve("$FILE_NAME.corrupt")
    private val lock = Any()
    private var unusableFileSeen = false

    override fun load(): LoadedSettings = synchronized(lock) {
        val text = try {
            if (!Files.isRegularFile(file)) return LoadedSettings(DesktopSettings())
            Files.readString(file)
        } catch (e: Exception) {
            unusableFileSeen = true
            return LoadedSettings(DesktopSettings(), "Could not read the settings file: ${e.message ?: e::class.simpleName}; using defaults.")
        }
        val dto = try {
            json.decodeFromString<SettingsDto>(text)
        } catch (_: Exception) {
            unusableFileSeen = true
            return LoadedSettings(DesktopSettings(), "The settings file is unreadable; using defaults (it is kept as $FILE_NAME.corrupt when settings are next saved).")
        }
        if (dto.version > VERSION) {
            unusableFileSeen = true
            return LoadedSettings(DesktopSettings(), "The settings file was written by a newer version; using defaults (it is kept as $FILE_NAME.corrupt when settings are next saved).")
        }
        LoadedSettings(dto.toSettings())
    }

    override fun save(settings: DesktopSettings): String? = synchronized(lock) {
        try {
            if (unusableFileSeen) {
                Files.createDirectories(dir)
                if (Files.isRegularFile(file) && !Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES)
                unusableFileSeen = false
            }
            writer.write(file, json.encodeToString(SettingsDto.from(settings)))
            null
        } catch (e: Exception) {
            "Could not save settings: ${e.message ?: e::class.simpleName}"
        }
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
    ) {
        init { require(version >= 1) { "not a settings file" } }

        fun toSettings(): DesktopSettings {
            val recent = DesktopSettings.bounded(recentFiles.filter(::exists))
            return DesktopSettings(
                lastFile = lastFile?.takeIf(::exists),
                environments = environments,
                window = window?.let { WindowSettings(it.width, it.height, it.x, it.y, it.maximized) },
                recentFiles = recent,
            )
        }

        private fun exists(p: String): Boolean = try { Files.isRegularFile(Path.of(p)) } catch (_: Exception) { false }

        companion object {
            fun from(s: DesktopSettings) = SettingsDto(
                VERSION, s.lastFile, s.environments,
                s.window?.let { WindowDto(it.width, it.height, it.x, it.y, it.maximized) },
                DesktopSettings.bounded(s.recentFiles),
            )
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}
