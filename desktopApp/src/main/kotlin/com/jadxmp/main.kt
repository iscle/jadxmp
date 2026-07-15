package com.jadxmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jadxmp.ui.client.CoreApiDecompilerClient
import com.jadxmp.ui.client.FileDropController
import com.jadxmp.ui.workbench.JadxWorkbenchApp

/**
 * Desktop entry point: the runnable jadxmp decompiler.
 *
 * It launches ui:app's [JadxWorkbenchApp] backed by a [CoreApiDecompilerClient] (the real `core:api`
 * engine, embedded directly in-process) and wires a native [DesktopFileOpener] so the Open button and
 * start-page drop zone bring up an AWT file dialog. Picking a `.dex`/`.apk` reads its bytes and feeds
 * them to the engine, which populates the class tree; selecting a class decompiles it to Java on
 * demand and renders it in the code viewer.
 */
fun main() = application {
    val client = remember { CoreApiDecompilerClient() }
    val fileOpener = remember { DesktopFileOpener() }
    val fileSaver = remember { DesktopFileSaver() }
    val projectExporter = remember { DesktopProjectExporter() }
    val settingsStore = remember { DesktopSettingsStore() }
    val dropController = remember { FileDropController() }
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        title = "jadxmp",
    ) {
        // Whole-window drop target: a file dropped anywhere opens; the start-page zone shows the
        // hover highlight the controller drives.
        Box(Modifier.fillMaxSize().desktopFileDropTarget(dropController)) {
            JadxWorkbenchApp(
                client = client,
                fileOpener = fileOpener,
                dropController = dropController,
                settingsStore = settingsStore,
                fileSaver = fileSaver,
                projectExporter = projectExporter,
            )
        }
    }
}
