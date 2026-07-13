package com.jadxmp

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.jadxmp.ui.client.CoreApiDecompilerClient
import com.jadxmp.ui.workbench.JadxWorkbenchApp

/**
 * Browser entry point: the runnable jadxmp decompiler, fully client-side (no backend).
 *
 * It mounts ui:app's [JadxWorkbenchApp] — the exact same Compose workbench the desktop app uses —
 * into the page via Compose's wasm/js [ComposeViewport] (attaches to `document.body`). The workbench
 * is backed by a [CoreApiDecompilerClient] (the real `core:api` engine compiled to wasm and running
 * in the browser) and a [BrowserFileOpener] so the Open button / start-page drop zone bring up a
 * native file picker. Picking a `.dex`/`.apk` reads its bytes in-browser and feeds them to the
 * engine, which populates the class tree; selecting a class decompiles it to Java on demand.
 *
 * Single-threaded note: on wasm/js there is one thread, shared by the UI and the engine (the
 * scheduler's parallelism is 1). Per-class decompilation is dispatched via `Dispatchers.Default`
 * (see [CoreApiDecompilerClient]) so the event loop can paint the Loading state before work starts,
 * and work happens one class at a time on user interaction. A very large APK's initial load still
 * parses on the UI thread — see BrowserFileOpener's KDoc for the documented limitation.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        val client = remember { CoreApiDecompilerClient() }
        val fileOpener = remember { BrowserFileOpener() }
        JadxWorkbenchApp(client = client, fileOpener = fileOpener)
    }
}
