package com.dan323.httpclient.adapters

import java.nio.file.Path

/**
 * Finds the per-user configuration folder of the app: `$XDG_CONFIG_HOME/http-client` or `~/.config/http-client`
 * on Linux and other Unixes, `~/Library/Application Support/http-client` on macOS, `%APPDATA%\http-client` on
 * Windows (`~\AppData\Roaming` when `APPDATA` is unset). The OS name, environment and home are injectable.
 * A relative `XDG_CONFIG_HOME` is ignored, as the XDG specification requires.
 */
class ConfigDirLocator(
    private val osName: String = System.getProperty("os.name", ""),
    private val env: (String) -> String? = System::getenv,
    private val home: Path = Path.of(System.getProperty("user.home", ".")),
) {
    fun locate(): Path {
        val os = osName.lowercase()
        val base = when {
            "win" in os && "darwin" !in os -> env("APPDATA")?.takeIf { it.isNotBlank() }?.let(::pathOrNull)
                ?: home.resolve("AppData").resolve("Roaming")
            "mac" in os || "darwin" in os -> home.resolve("Library").resolve("Application Support")
            else -> env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::pathOrNull)?.takeIf { it.isAbsolute }
                ?: home.resolve(".config")
        }
        return base.resolve(APP_DIR)
    }

    private fun pathOrNull(s: String): Path? = try { Path.of(s) } catch (_: Exception) { null }

    companion object {
        const val APP_DIR = "http-client"
    }
}
