package com.jadxmp

import com.jadxmp.ui.client.FileDropController
import com.jadxmp.ui.client.OpenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import web.blob.byteArray
import web.dnd.DragEvent
import web.dom.document
import web.events.EventType
import web.events.addEventListener

/**
 * Browser OS-file drop support — the web analogue of desktop's [desktopFileDropTarget]. Compose's
 * canvas does not receive OS drag-and-drop on wasmJs/js, so drop is wired at the DOM level (exactly as
 * [BrowserFileOpener] wires the `<input type=file>` picker at the DOM level): `dragover`/`drop`
 * listeners on `document`, so a file dropped anywhere on the page opens.
 *
 * Browser rules honoured:
 *  - **`dragover` must `preventDefault()`** or the browser refuses the drop and instead navigates the
 *    tab to the dropped file. Doing so also marks the page a valid drop target.
 *  - **`drop` must `preventDefault()`** so the browser doesn't open/download the file itself.
 *
 * The dropped [web.file.File] is a `Blob`; its bytes are read via `Blob.arrayBuffer()`
 * ([web.blob.byteArray], a suspend wrapper) on a coroutine — non-blocking on the single browser
 * thread — then handed to the workbench as the same [OpenRequest] the picker builds. A rejected read
 * (file moved/deleted between drop and read, or an allocation failure) is swallowed to a no-op, never
 * an uncaught throw. Non-file drags (text/links) carry no `files` and are ignored.
 *
 * [setDragActive] lights up the start-page drop zone while a drag hovers.
 */
fun installBrowserFileDrop(controller: FileDropController) {
    // App-lifetime scope for the async byte reads kicked off from the (non-suspend) DOM callbacks.
    val scope = CoroutineScope(Dispatchers.Default)

    // Explicit `(DragEvent) -> Unit` types select the plain-handler addEventListener overload (there is
    // also an EventHandler-taking one; an untyped lambda is ambiguous between them). Mirrors how
    // BrowserFileOpener registers its window focus/blur listeners.
    val onDragOver: (DragEvent) -> Unit = { event ->
        event.preventDefault() // REQUIRED: allow the drop (else the browser opens the file itself).
        controller.setDragActive(true)
    }
    val onDragLeave: (DragEvent) -> Unit = {
        controller.setDragActive(false)
    }
    val onDrop: (DragEvent) -> Unit = { event ->
        event.preventDefault()
        controller.setDragActive(false)
        // First file only; ignore non-file drags (no files present → no-op).
        val file = event.dataTransfer?.files?.item(0)
        if (file != null) {
            scope.launch {
                val bytes = runCatching { file.byteArray() }.getOrNull()
                if (bytes != null) controller.dropFile(OpenRequest(name = file.name, bytes = bytes))
            }
        }
    }

    document.addEventListener(EventType<DragEvent>("dragover"), onDragOver)
    document.addEventListener(EventType<DragEvent>("dragleave"), onDragLeave)
    document.addEventListener(EventType<DragEvent>("drop"), onDrop)
}
