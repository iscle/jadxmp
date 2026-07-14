package com.jadxmp.ui.client

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic bridge for OS file drag-and-drop into the workbench.
 *
 * Drag-and-drop is inherently per-platform — a Compose `Modifier.dragAndDropTarget` on desktop, DOM
 * `dragover`/`drop` on web — so, exactly like [FileOpener], `ui:app`'s commonMain only defines the
 * contract and each shell drives it. A shell reads the dropped file's bytes and calls [dropFile] with
 * an [OpenRequest] built the *same way* its [FileOpener] builds one from a picked file; the workbench
 * collects [drops] and routes each through the identical `openProject` path a picked file takes, so a
 * dropped and a browsed file are indistinguishable downstream. [dragActive] lets a shell light up the
 * drop zone while a drag hovers.
 *
 * Wasm-safe: no `java.*`, no DOM, no threads — just coroutine flows shared by every target.
 */
class FileDropController {
    // replay = 0: a drop only matters live. extraBufferCapacity absorbs a burst (multi-file drop) so
    // tryEmit never has to suspend or drop from a non-suspend platform callback. The workbench installs
    // its collector at composition, well before any user drag, so no early emission is lost.
    private val _drops = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 8)

    /** Files the user dropped, in arrival order. The workbench collects this and opens each. */
    val drops: SharedFlow<OpenRequest> = _drops.asSharedFlow()

    private val _dragActive = MutableStateFlow(false)

    /** True while an OS drag hovers the window, so the UI can highlight the drop target. */
    val dragActive: StateFlow<Boolean> = _dragActive.asStateFlow()

    /**
     * A platform shell calls this after reading a dropped file's bytes into an [OpenRequest] (mirroring
     * how its [FileOpener] builds one). Fire-and-forget: buffered for the workbench's collector.
     */
    fun dropFile(request: OpenRequest) {
        _drops.tryEmit(request)
    }

    /** A shell toggles this as a drag enters/leaves the window (drives the drop-zone highlight). */
    fun setDragActive(active: Boolean) {
        _dragActive.value = active
    }
}
