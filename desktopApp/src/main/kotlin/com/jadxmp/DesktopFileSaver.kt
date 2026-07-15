package com.jadxmp

import com.jadxmp.ui.client.FileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * JVM [FileSaver] backed by AWT's native [FileDialog] in SAVE mode — the write-side analogue of
 * [DesktopFileOpener]. Shows the OS "save file" dialog seeded with [suggestedName], then writes the
 * bytes to the chosen path off the UI thread (`Dispatchers.IO`). File IO is JVM-only and lives here in
 * the shell (ui:app commonMain stays wasm-safe).
 *
 * Returns `false` when the user cancels *or* the write fails — a clean no-op that the workbench ignores,
 * never an uncaught throw (rule 4).
 */
class DesktopFileSaver : FileSaver {

    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean {
        val dialog = FileDialog(null as Frame?, "Save file", FileDialog.SAVE).apply {
            file = suggestedName // seeds the default name; the user may rename/redirect.
            isVisible = true
        }
        val dir = dialog.directory ?: return false
        val name = dialog.file ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { File(dir, name).writeBytes(bytes); true }.getOrDefault(false)
        }
    }
}
