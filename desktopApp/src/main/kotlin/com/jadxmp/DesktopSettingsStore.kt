package com.jadxmp

import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.UiSettings
import java.io.File

/**
 * Desktop [SettingsStore]: persists [UiSettings] as a small JSON file under the user's config dir
 * (`%APPDATA%\jadxmp` on Windows, `~/Library/Application Support/jadxmp` on macOS, `$XDG_CONFIG_HOME`
 * or `~/.config/jadxmp` elsewhere). File IO is JVM-only and lives here in the shell — ui:app's
 * commonMain stays wasm-safe and only owns the pure serialize/parse (see [UiSettings]).
 *
 * Both operations are best-effort: a failed read degrades to defaults and a failed write is dropped
 * rather than thrown (rule 4), so a read-only home dir or a hand-corrupted file never crashes the app.
 */
class DesktopSettingsStore : SettingsStore {
    private val file: File by lazy { resolveConfigDir().resolve(FILE_NAME) }

    override fun load(): UiSettings =
        UiSettings.parse(runCatching { if (file.isFile) file.readText() else null }.getOrNull())

    override fun save(settings: UiSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(settings.serialize())
        }
    }

    private fun resolveConfigDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        val base = when {
            os.contains("win") ->
                System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming"
            os.contains("mac") -> "$home/Library/Application Support"
            else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
        }
        return File(base, APP_DIR)
    }

    private companion object {
        const val APP_DIR = "jadxmp"
        const val FILE_NAME = "ui-settings.json"
    }
}
