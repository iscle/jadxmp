package com.jadxmp

import com.jadxmp.ui.client.FileOpener
import com.jadxmp.ui.client.OpenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import web.blob.byteArray
import web.dom.document
import web.events.EventHandler
import web.events.EventType
import web.events.addEventListener
import web.events.removeEventListener
import web.file.File
import web.focus.FocusEvent
import web.html.HtmlTagName
import web.html.InputType
import web.html.file
import web.timers.Timeout
import web.timers.clearTimeout
import web.timers.setTimeout
import web.window.window
import kotlin.coroutines.resume

/**
 * Browser [FileOpener]: opens a native file picker (`<input type="file">`) restricted to the
 * supported container formats, reads the selected file's bytes fully in the browser, and returns an
 * [OpenRequest] carrying them. This is the web analogue of desktop's `DesktopFileOpener` — file IO
 * is inherently per-platform, so `ui:app`'s commonMain only defines the [FileOpener] contract and
 * this wasmJs/js shell supplies the browser implementation (commonMain stays wasm-safe, no DOM).
 *
 * ## Async, non-blocking
 * [choose] is `suspend` and never blocks the single browser thread:
 *  - [pickFile] wraps the picker's `change`/`cancel` events in a [suspendCancellableCoroutine], so
 *    the coroutine parks (yielding the thread to the UI) until the user picks or dismisses.
 *  - [web.blob.byteArray] reads the file via the `Blob.arrayBuffer()` Promise, `await`-ed — again
 *    non-blocking. The bytes are then handed to [CoreApiDecompilerClient], which decompiles on the
 *    (single) wasm dispatcher.
 *
 * ## Graceful failure (mirrors desktop)
 * A cancelled picker and a failed read both resolve to `null`, which the workbench treats as a clean
 * no-op open — the current project is left untouched, never an uncaught throw. The byte read is
 * wrapped in `runCatching`: if `arrayBuffer()`'s Promise rejects (the file was moved/deleted or its
 * permission revoked between pick and read, or the allocation fails on a huge file) the exception is
 * swallowed to a `null` rather than escaping `choose()` into the workbench's fire-and-forget
 * `scope.launch { … }` as an uncaught coroutine exception (there is no CoroutineExceptionHandler).
 *
 * ## Single-threaded responsiveness (documented limitation)
 * In the browser there is exactly one thread; the engine and the UI share it (the decompiler's
 * parallelism is 1). Reading the file and per-class decompilation are dispatched through coroutines
 * so the UI can paint between steps, and classes decompile lazily one-at-a-time on selection — the
 * expensive path is naturally chunked by user interaction. The one unavoidably-synchronous span is
 * the initial container parse inside `Decompiler.load` (building the class-name index): for a very
 * large APK this runs to completion on the UI thread and can briefly freeze the page, because the
 * yield/chunking would have to live inside the engine (`core:*`), which this shell must not modify.
 * The workbench shows a Loading state before load begins; finer-grained progress/yielding during
 * load is engine work (see docs/UI-DESIGN.md §4 item 6).
 */
class BrowserFileOpener : FileOpener {

    override suspend fun choose(): OpenRequest? {
        val file = pickFile() ?: return null
        // Blob.arrayBuffer() → ByteArray, awaited (non-blocking). File is a Blob. Guard the read: a
        // rejected Promise here must end the open as a clean no-op, not an uncaught coroutine throw.
        val bytes = runCatching { file.byteArray() }.getOrNull() ?: return null
        return OpenRequest(name = file.name, bytes = bytes)
    }

    /**
     * Show the browser file picker and suspend until the user selects a file (returns it) or
     * dismisses the dialog (returns `null`). The `<input>` is created detached; modern browsers open
     * the picker from `click()` without it being in the DOM, so there's no visible chrome and nothing
     * to clean up from the page. The continuation keeps the element alive until it settles.
     *
     * Resume is driven by three signals, whichever fires first, guarded so it resolves exactly once:
     *  - `change` — a file was chosen (resumes with it);
     *  - `cancel` — the dialog was dismissed (resumes `null`); reliable only on Chrome 113+/
     *    Firefox 109+/Safari 16.4+;
     *  - a `window` `focus` fallback — on older browsers dismissing the picker fires neither `change`
     *    nor `cancel`, which would park this coroutine forever and hang the "Open" gesture. So when
     *    focus returns to the window we start a short debounce; if no `change` lands within it (a real
     *    pick fires `change` right after focus) we treat it as a cancel and resume `null`.
     *
     * [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation] detaches the focus listener
     * and timer if the workbench cancels the open while the picker is still open.
     */
    private suspend fun pickFile(): File? = suspendCancellableCoroutine { continuation ->
        val input = document.createElement(HtmlTagName.input)
        input.type = InputType.file
        input.accept = ACCEPT

        var settled = false
        var focusTimeout: Timeout? = null
        val focusType = EventType<FocusEvent>("focus")
        // Held so cleanup can removeEventListener the exact same reference it registered.
        var focusListener: ((FocusEvent) -> Unit)? = null

        fun cleanup() {
            focusListener?.let { window.removeEventListener(focusType, it) }
            clearTimeout(focusTimeout)
        }

        fun settle(file: File?) {
            if (settled) return
            settled = true
            cleanup()
            continuation.resume(file)
        }

        val onFocus: (FocusEvent) -> Unit = {
            // `change` fires just after focus on a real pick, so debounce to let it win the race.
            clearTimeout(focusTimeout)
            focusTimeout = setTimeout({ settle(null) }, FOCUS_CANCEL_DEBOUNCE_MS)
        }
        focusListener = onFocus

        input.onchange = EventHandler { settle(input.files?.item(0)) }
        input.oncancel = EventHandler { settle(null) }
        window.addEventListener(focusType, onFocus)
        continuation.invokeOnCancellation { cleanup() }

        input.click()
    }

    private companion object {
        /** Accept hint for the picker; mirrors the engine's supported input containers. */
        const val ACCEPT = ".dex,.apk,.jar,.zip,.aar,.aab,.class"

        /**
         * Debounce after the window regains focus before treating a picker with no `change` event as
         * a cancel. Long enough for a real selection's `change` (which fires within a few ms of focus)
         * to arrive and win; short enough that a genuine cancel doesn't leave "Open" hanging.
         */
        const val FOCUS_CANCEL_DEBOUNCE_MS = 500
    }
}
