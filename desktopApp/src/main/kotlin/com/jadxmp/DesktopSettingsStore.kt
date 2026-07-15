package com.jadxmp

import com.jadxmp.ui.client.SettingsStore
import com.jadxmp.ui.client.UiSettings
import java.io.File
import java.util.concurrent.Executors

/**
 * Desktop [SettingsStore]: persists [UiSettings] as a small JSON file under the user's config dir
 * (`%APPDATA%\jadxmp` on Windows, `~/Library/Application Support/jadxmp` on macOS, `$XDG_CONFIG_HOME`
 * or `~/.config/jadxmp` elsewhere). File IO is JVM-only and lives here in the shell — ui:app's
 * commonMain stays wasm-safe and only owns the pure serialize/parse (see [UiSettings]).
 *
 * [save] must not block the caller: it's invoked on the UI/EDT thread on every settings change, and a
 * rapid burst (e.g. Ctrl+wheel font zoom fires ~19 saves in a flick) would jank the UI if each did
 * synchronous disk IO. So the actual `mkdirs()` + `writeText` run on a single-thread background
 * [writer] and [save] returns immediately. That one thread runs writes in submission (FIFO) order, so
 * the LAST value still wins and no write is reordered ahead of an earlier one.
 *
 * Both operations are best-effort: a failed read degrades to defaults and a failed write is dropped
 * rather than thrown (rule 4), so a read-only home dir or a hand-corrupted file never crashes the app.
 */
class DesktopSettingsStore : SettingsStore {
    private val file: File by lazy { resolveConfigDir().resolve(FILE_NAME) }

    // Serializes background writes: one thread → FIFO order → last-write-wins with no reordering. Daemon
    // so a queued/in-flight best-effort settings write never keeps the JVM alive past app exit.
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jadxmp-settings-writer").apply { isDaemon = true }
    }

    // Whole body guarded: the raw file read AND the parse (plus the lazy config-dir resolution) are
    // wrapped so load() — which runs during startup composition — degrades to defaults instead of
    // crashing launch, even if a future non-total parse throws (rule 4). Normal + missing-file cases
    // are unchanged (no file → parse(null) → defaults).
    override fun load(): UiSettings =
        runCatching { UiSettings.parse(if (file.isFile) file.readText() else null) }
            .getOrDefault(UiSettings())

    override fun save(settings: UiSettings) {
        // Snapshot is implicit: UiSettings is immutable, so capturing `settings` fixes the value for
        // this submission. Hand the disk IO to the background thread and return at once — the UI thread
        // never blocks on mkdirs()/writeText(). Submissions run in order, so a later save can't land
        // before an earlier one and the final on-disk value is the last save().
        runCatching {
            writer.execute {
                // Best-effort on the background thread: a read-only dir / IO error is swallowed, never
                // thrown (rule 4) — and now that the write is off-thread it can't reach the caller.
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(settings.serialize())
                }
            }
        } // execute() only throws once the executor is shut down (it never is); swallow to stay non-throwing.
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
