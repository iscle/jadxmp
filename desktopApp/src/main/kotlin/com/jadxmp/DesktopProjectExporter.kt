package com.jadxmp

import com.jadxmp.ui.client.ExportRequest
import com.jadxmp.ui.client.ProjectExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

/**
 * JVM [ProjectExporter] that writes the whole decompiled project to a **directory tree** the user picks —
 * the desktop analogue of the web/android ZIP download. Shows a Swing directory chooser, then writes each
 * `<package>/<Simple>.java` (and `resources/…`) file under the chosen folder off the UI thread
 * (`Dispatchers.IO`). File IO is JVM-only and lives here in the shell, keeping `ui:app` commonMain
 * wasm-safe.
 *
 * Returns `false` when the user cancels *or* the write fails — a clean no-op the workbench surfaces as
 * "Export cancelled", never an uncaught throw (rule 4). A defensive path-escape check keeps a stray
 * `..`/absolute entry from ever landing outside the chosen directory, even though the engine's paths are
 * already relative and sanitized.
 */
class DesktopProjectExporter : ProjectExporter {

    override suspend fun export(request: ExportRequest): Boolean {
        val dir = chooseDirectory() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val root = dir.canonicalFile
                val rootPath = root.path + File.separator
                for (file in request.files) {
                    val target = File(root, file.path).canonicalFile
                    // Zip-slip guard: never write outside the chosen folder.
                    if (target.path != root.path && !target.path.startsWith(rootPath)) continue
                    target.parentFile?.mkdirs()
                    target.writeBytes(file.bytes)
                }
                true
            }.getOrDefault(false)
        }
    }

    /** Native "choose a folder" dialog; null when the user cancels. Runs on the calling (UI) dispatcher. */
    private fun chooseDirectory(): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export decompiled sources to folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
