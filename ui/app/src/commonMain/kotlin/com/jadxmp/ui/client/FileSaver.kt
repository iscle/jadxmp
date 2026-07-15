package com.jadxmp.ui.client

/**
 * Platform seam for writing bytes to a user-chosen destination, mirroring [FileOpener] (P0#7). Saving
 * a file is inherently per-platform — a native save dialog on desktop, a browser download on web, the
 * Storage Access Framework / Downloads on android — so `ui:app` only defines the contract and each
 * shell supplies the implementation.
 *
 * [save] offers [suggestedName] as the default filename and writes [bytes]; it returns `true` when the
 * file was written and `false` when the user cancelled or the write failed. `suspend` so a shell can do
 * the dialog + write off the UI thread; the workbench launches it on a cancelable scope. When no saver
 * is wired (stub / preview), the "Save file" affordances are hidden/disabled.
 */
fun interface FileSaver {
    suspend fun save(suggestedName: String, bytes: ByteArray): Boolean
}
