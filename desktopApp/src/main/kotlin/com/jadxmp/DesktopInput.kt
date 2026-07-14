package com.jadxmp

import com.jadxmp.ui.client.OpenRequest
import java.io.File

/**
 * Shared desktop file→[OpenRequest] plumbing, used by both the "Open" dialog ([DesktopFileOpener]) and
 * drag-and-drop ([desktopFileDropTarget]) so a picked file and a dropped file produce byte-identical
 * requests. Desktop is the only place file IO is allowed (ui:app's commonMain stays wasm-safe), so all
 * byte reading lives here in the shell.
 */

/** Input container formats jadxmp accepts; mirrors the engine's supported inputs and the picker filter. */
internal val SUPPORTED_INPUT_EXTS = listOf(".dex", ".apk", ".jar", ".aar", ".aab", ".zip", ".class")

internal fun File.hasSupportedExtension(): Boolean =
    SUPPORTED_INPUT_EXTS.any { name.endsWith(it, ignoreCase = true) }

/**
 * Read [file] fully into an [OpenRequest], or `null` if the read fails (moved/deleted/permission).
 * A `null` is a clean no-op open — the workbench simply does nothing, leaving the current project
 * untouched rather than surfacing a failed session. Blocking IO: callers wrap it in `Dispatchers.IO`.
 */
internal fun openRequestFromFile(file: File): OpenRequest? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return OpenRequest(name = file.name, bytes = bytes)
}
