package com.jadxmp

import com.jadxmp.ui.client.FileOpener
import com.jadxmp.ui.client.OpenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import web.blob.byteArray
import web.dom.document
import web.events.Event
import web.events.EventType
import web.events.addEventListener
import web.events.removeEventListener
import web.file.File
import web.focus.FocusEvent
import web.html.HTMLInputElement
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
 * ## Making the native picker actually open (the browser rules)
 * Two browser constraints govern whether `<input>.click()` shows the dialog at all:
 *  1. **The element must be in the document.** Firefox/Safari won't show the picker for a detached
 *     `<input>`. So a single hidden [input] is created and appended to `document.body` **once**, up
 *     front, and reused for every pick (its `value` is reset each time so re-selecting the same file
 *     still fires `change`). It's positioned offscreen — never `display:none`, which can also
 *     suppress the dialog — so it takes no visible space yet remains clickable.
 *  2. **The click needs transient activation.** The picker only opens while the user-gesture
 *     activation is still live. The workbench invokes [choose] from `scope.launch { … }`, i.e. one
 *     coroutine-dispatch after the Compose "Open" click, so the click here is not in the gesture's
 *     synchronous call stack — but it runs within the same event-loop turn, well inside the browser's
 *     transient-activation window, so Chromium/Firefox still open the dialog. Keeping a pre-built,
 *     pre-attached element (no per-click DOM creation) minimizes the work between gesture and
 *     `click()`, giving that window the best chance. (Safari is stricter — it can require the click
 *     to be synchronous with the gesture — which the shell can't guarantee while the opener is driven
 *     from a launched coroutine; see the class KDoc note in [FileOpener] / docs/UI-DESIGN.md.)
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

    /**
     * The single hidden `<input type="file">`, created and attached to the document once (see the
     * class KDoc). Reused for every [pickFile]; kept offscreen rather than `display:none` so the
     * native picker still opens. Created eagerly at construction (during composition, when
     * `document.body` already exists) so no DOM work sits between the user gesture and `click()`.
     */
    private val input: HTMLInputElement = createHiddenFileInput()

    override suspend fun choose(): OpenRequest? {
        val file = pickFile() ?: return null
        // Blob.arrayBuffer() → ByteArray, awaited (non-blocking). File is a Blob. Guard the read: a
        // rejected Promise here must end the open as a clean no-op, not an uncaught coroutine throw.
        val bytes = runCatching { file.byteArray() }.getOrNull() ?: return null
        return OpenRequest(name = file.name, bytes = bytes)
    }

    /**
     * Show the browser file picker and suspend until the user selects a file (returns it) or
     * dismisses the dialog (returns `null`). Reuses the pre-attached [input]; its `value` is cleared
     * first so choosing the same file twice in a row still fires `change`. The continuation resumes
     * exactly once, driven by whichever of these fires first:
     *  - `change` — a file was chosen (resumes with it);
     *  - `cancel` — the dialog was dismissed (resumes `null`); reliable only on Chrome 113+/
     *    Firefox 109+/Safari 16.4+;
     *  - a `window` `focus` fallback — on older browsers dismissing the picker fires neither `change`
     *    nor `cancel`, which would park this coroutine forever and hang the "Open" gesture. Opening
     *    the native dialog blurs the window; only after that `blur` (the dialog really opened) do we
     *    treat a returning `focus` as a possible cancel, and only after a short debounce — a real
     *    pick's `change` fires right after focus and wins the race. Gating on the prior `blur` stops
     *    an unrelated `focus` (e.g. the Compose canvas regaining focus) from firing a false cancel.
     *
     * [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation] detaches the listeners and
     * timer if the workbench cancels the open while the picker is still open.
     */
    private suspend fun pickFile(): File? = suspendCancellableCoroutine { continuation ->
        // Reset so selecting the previously chosen file again still fires `change`.
        input.value = ""

        var settled = false
        var dialogOpened = false
        var focusTimeout: Timeout? = null
        val blurType = EventType<FocusEvent>("blur")
        val focusType = EventType<FocusEvent>("focus")
        // `change`/`cancel` are attached via addEventListener, NOT the `input.onchange`/`oncancel`
        // property setters: those setters fail to link on wasmJs (IrLinkageError — the kotlin-wrappers
        // `HTMLInputElement.onchange` accessor signature doesn't resolve at runtime), which crashed the
        // whole Open flow. addEventListener is the same path blur/focus/drag already use successfully.
        val changeType = EventType<Event>("change")
        val cancelType = EventType<Event>("cancel")
        // Held so cleanup can removeEventListener the exact same references it registered.
        var blurListener: ((FocusEvent) -> Unit)? = null
        var focusListener: ((FocusEvent) -> Unit)? = null
        var changeListener: ((Event) -> Unit)? = null
        var cancelListener: ((Event) -> Unit)? = null

        fun cleanup() {
            blurListener?.let { window.removeEventListener(blurType, it) }
            focusListener?.let { window.removeEventListener(focusType, it) }
            changeListener?.let { input.removeEventListener(changeType, it) }
            cancelListener?.let { input.removeEventListener(cancelType, it) }
            clearTimeout(focusTimeout)
        }

        fun settle(file: File?) {
            if (settled) return
            settled = true
            cleanup()
            continuation.resume(file)
        }

        val onBlur: (FocusEvent) -> Unit = {
            // The picker is open now; a later focus return may be a cancel.
            dialogOpened = true
        }
        val onFocus: (FocusEvent) -> Unit = {
            // Only a focus that follows the dialog's own blur can be a cancel; ignore spurious ones.
            // `change` fires just after focus on a real pick, so debounce to let it win the race.
            if (dialogOpened) {
                clearTimeout(focusTimeout)
                focusTimeout = setTimeout({ settle(null) }, FOCUS_CANCEL_DEBOUNCE_MS)
            }
        }
        blurListener = onBlur
        focusListener = onFocus

        val onChange: (Event) -> Unit = { settle(input.files?.item(0)) }
        val onCancel: (Event) -> Unit = { settle(null) }
        changeListener = onChange
        cancelListener = onCancel
        input.addEventListener(changeType, onChange)
        input.addEventListener(cancelType, onCancel)
        window.addEventListener(blurType, onBlur)
        window.addEventListener(focusType, onFocus)
        continuation.invokeOnCancellation { cleanup() }

        input.click()
    }

    /** Build the reusable hidden file `<input>` and attach it to the document (see class KDoc). */
    private fun createHiddenFileInput(): HTMLInputElement {
        val el = document.createElement(HtmlTagName.input)
        el.type = InputType.file
        el.accept = ACCEPT
        // Offscreen + zero-size rather than display:none, which can suppress the native picker.
        el.style.cssText = "position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0;"
        document.body.appendChild(el)
        return el
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
