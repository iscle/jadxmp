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
import web.file.File
import web.file.FileList

/**
 * Browser OS-file drop support — the web analogue of desktop's [desktopFileDropTarget]. Compose's
 * canvas does not receive OS drag-and-drop on wasmJs/js, so drop is wired at the DOM level (exactly as
 * [BrowserFileOpener] wires the `<input type=file>` picker at the DOM level): `dragenter`/`dragover`/
 * `dragleave`/`drop` listeners on `document`, so a file dropped anywhere on the page opens.
 *
 * Browser rules honoured:
 *  - **`dragover` must `preventDefault()`** or the browser refuses the drop and instead navigates the
 *    tab to the dropped file. Doing so also marks the page a valid drop target.
 *  - **`drop` must `preventDefault()`** so the browser doesn't open/download the file itself.
 *
 * The chosen [web.file.File] is a `Blob`; its bytes are read via `Blob.arrayBuffer()`
 * ([web.blob.byteArray], a suspend wrapper) on a coroutine — non-blocking on the single browser
 * thread — then handed to the workbench as the same [OpenRequest] the picker builds. A rejected read
 * (file moved/deleted between drop and read, or an allocation failure) is swallowed to a no-op, never
 * an uncaught throw. Non-file drags (text/links) carry no `files` and are ignored.
 *
 * **Which dropped file opens:** the first one with a supported container extension ([firstSupportedFile],
 * mirroring the desktop drop's "first supported container" and [BrowserFileOpener]'s `accept` list). If
 * none of the dropped files is a supported format, nothing opens — the engine is never handed a file it
 * can't parse — rather than blindly opening `files[0]`.
 *
 * [setDragActive] lights up the start-page drop zone while a drag hovers. It's driven by a `dragenter`/
 * `dragleave` **depth counter**: those events bubble to `document` from every child element the pointer
 * crosses, so clearing the highlight on each raw `dragleave` would flicker it; counting enters against
 * leaves and clearing only at zero keeps it steady until the drag truly leaves the page.
 */
fun installBrowserFileDrop(controller: FileDropController) {
    // App-lifetime scope for the async byte reads kicked off from the (non-suspend) DOM callbacks.
    val scope = CoroutineScope(Dispatchers.Default)

    // Highlight depth: `dragenter`/`dragleave` bubble to `document` from each child element the pointer
    // crosses, so a bare setDragActive(false) per `dragleave` flickers the highlight. Count enters vs.
    // leaves and clear only at zero. Safe as a plain `var` — the browser is single-threaded.
    var dragDepth = 0

    // Explicit `(DragEvent) -> Unit` types select the plain-handler addEventListener overload (there is
    // also an EventHandler-taking one; an untyped lambda is ambiguous between them). Mirrors how
    // BrowserFileOpener registers its window focus/blur listeners.
    val onDragEnter: (DragEvent) -> Unit = { event ->
        event.preventDefault() // Marks the page a valid drop target (with `dragover` below).
        dragDepth++
        controller.setDragActive(true)
    }
    val onDragOver: (DragEvent) -> Unit = { event ->
        event.preventDefault() // REQUIRED: allow the drop (else the browser opens the file itself).
    }
    val onDragLeave: (DragEvent) -> Unit = {
        // Only the leave that balances the outermost enter clears the highlight — child boundaries in
        // between net out. Clamp at zero so a stray/unbalanced leave can't drive it negative and stick.
        dragDepth--
        if (dragDepth <= 0) {
            dragDepth = 0
            controller.setDragActive(false)
        }
    }
    val onDrop: (DragEvent) -> Unit = { event ->
        event.preventDefault()
        dragDepth = 0 // The drag ended; reset the counter so the next drag starts clean.
        controller.setDragActive(false)
        // Open the first dropped file with a supported extension; if none qualifies (or it was a
        // non-file drag with no `files`), open nothing rather than feed the engine an unsupported file.
        val file = firstSupportedFile(event.dataTransfer?.files)
        if (file != null) {
            scope.launch {
                val bytes = runCatching { file.byteArray() }.getOrNull()
                if (bytes != null) controller.dropFile(OpenRequest(name = file.name, bytes = bytes))
            }
        }
    }

    document.addEventListener(EventType<DragEvent>("dragenter"), onDragEnter)
    document.addEventListener(EventType<DragEvent>("dragover"), onDragOver)
    document.addEventListener(EventType<DragEvent>("dragleave"), onDragLeave)
    document.addEventListener(EventType<DragEvent>("drop"), onDrop)
}

/**
 * Input container extensions the engine accepts — the drop-side mirror of [BrowserFileOpener]'s
 * `accept` list (and desktop's `SUPPORTED_INPUT_EXTS`); all three list the same seven formats. Kept
 * here as a local constant because the opener's copy is a private constant in another file; if the set
 * of accepted formats changes, update both.
 */
private val SUPPORTED_INPUT_EXTS = listOf(".dex", ".apk", ".jar", ".zip", ".aar", ".aab", ".class")

/** True when [name] ends in one of [SUPPORTED_INPUT_EXTS] (case-insensitive). Pure; the drop filter. */
private fun hasSupportedInputExtension(name: String): Boolean =
    SUPPORTED_INPUT_EXTS.any { name.endsWith(it, ignoreCase = true) }

/**
 * First file in [files] whose name has a [supported extension][hasSupportedInputExtension], or `null`
 * when the drag carried no files (non-file drag) or none is a supported container. Unlike the desktop
 * drop — which falls back to the first readable file — an all-unsupported drop opens nothing here, so
 * the engine is never handed a format it can't parse.
 */
private fun firstSupportedFile(files: FileList?): File? {
    if (files == null) return null
    for (i in 0 until files.length) {
        val file = files.item(i) ?: continue
        if (hasSupportedInputExtension(file.name)) return file
    }
    return null
}
