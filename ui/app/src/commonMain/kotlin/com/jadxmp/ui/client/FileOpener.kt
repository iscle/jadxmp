package com.jadxmp.ui.client

/**
 * Platform seam for picking an input container and handing its bytes to the engine.
 *
 * File access is inherently per-platform (a native `FileDialog` on desktop, an `<input type=file>` on
 * web, the SAF picker on Android), so `ui:app` only defines the contract; each shell supplies the
 * implementation. [choose] shows whatever picker the platform offers, reads the selected file, and
 * returns an [OpenRequest] carrying its bytes — or `null` if the user cancelled.
 *
 * `suspend` so a shell can do the read off the UI thread; the workbench launches it on a cancelable
 * scope. When no opener is wired (previews, the stub, wasm before upload support), the shell falls
 * back to the in-memory sample project.
 */
fun interface FileOpener {
    suspend fun choose(): OpenRequest?
}
