package com.jadxmp

import com.jadxmp.ui.client.FileOpener
import com.jadxmp.ui.client.OpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * JVM-only [FileOpener] backed by AWT's native [FileDialog]. Desktop is the only place file IO is
 * allowed (ui:app's commonMain must stay wasm-safe and never touch `java.*`), so all byte reading
 * lives here in the shell.
 *
 * Shows the OS-native "open file" dialog, then reads the selected `.dex`/`.apk`/`.jar`/… into memory
 * off the UI thread and hands the bytes to the engine via [OpenRequest]. Returns null when the user
 * cancels *or* the read fails; a null is a no-op open (the workbench simply does nothing), so a failed
 * read here leaves the current project untouched rather than surfacing a failed session.
 */
class DesktopFileOpener : FileOpener {

    override suspend fun choose(): OpenRequest? {
        val dialog = FileDialog(null as Frame?, "Open dex / apk / jar", FileDialog.LOAD).apply {
            // Best-effort filter (honored on Windows/Linux; macOS greys out non-matching files).
            setFilenameFilter { _, name -> SUPPORTED.any { name.endsWith(it, ignoreCase = true) } }
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, name)
        val bytes = withContext(Dispatchers.IO) {
            runCatching { file.readBytes() }.getOrNull()
        } ?: return null
        return OpenRequest(name = file.name, bytes = bytes)
    }

    private companion object {
        val SUPPORTED = listOf(".dex", ".apk", ".jar", ".aar", ".aab", ".zip", ".class")
    }
}
