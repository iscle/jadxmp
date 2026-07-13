package com.jadxmp

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jadxmp.ui.client.CoreApiDecompilerClient
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
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        title = "jadxmp",
    ) {
        JadxWorkbenchApp(client = client, fileOpener = fileOpener)
    }
}
