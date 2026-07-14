package com.jadxmp

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import com.jadxmp.ui.client.FileDropController
import com.jadxmp.ui.client.OpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Wrap desktop content in a Compose external drag-and-drop target that accepts an OS *files* drag and
 * opens the first supported container. Compose Multiplatform 1.11's `Modifier.dragAndDropTarget` +
 * `DragAndDropEvent.dragData()` (returning [DragData.FilesList]) is used rather than the older
 * desktop-only `onExternalDrag` — `dragAndDropTarget` is the current, non-deprecated API. On drop the
 * file URIs are read off the UI thread (via `Dispatchers.IO`) into the same [OpenRequest] the "Open"
 * dialog builds, then handed to the workbench through [controller]; enter/exit toggle the drop-zone
 * highlight. A failed read is swallowed to a no-op (never crashes the window — rule 4).
 *
 * Applied to a Box wrapping the whole app, so a file dropped anywhere in the window opens — not only
 * on the start-page zone (which shows the highlight [controller] drives).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.desktopFileDropTarget(controller: FileDropController): Modifier {
    val scope = rememberCoroutineScope()
    val target = remember(controller, scope) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = controller.setDragActive(true)

            override fun onExited(event: DragAndDropEvent) = controller.setDragActive(false)

            override fun onEnded(event: DragAndDropEvent) = controller.setDragActive(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                controller.setDragActive(false)
                val files = (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
                if (files.isEmpty()) return false
                // Read bytes off the UI thread; a big APK must not freeze the drop gesture.
                scope.launch {
                    val request = withContext(Dispatchers.IO) { firstOpenRequest(files) }
                    if (request != null) controller.dropFile(request)
                }
                return true
            }
        }
    }
    return dragAndDropTarget(
        // Only start a DnD session for a files drag; ignore text/other drag payloads.
        shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
        target = target,
    )
}

/**
 * Turn the dropped file specs (each a `file:` URI or a plain path, platform-dependent) into an
 * [OpenRequest], preferring the first with a supported extension and otherwise the first readable file.
 */
private fun firstOpenRequest(specs: List<String>): OpenRequest? {
    val files = specs.mapNotNull(::specToFile)
    val chosen = files.firstOrNull { it.hasSupportedExtension() } ?: files.firstOrNull() ?: return null
    return openRequestFromFile(chosen)
}

/** Parse a dropped file spec into a [File], tolerating both `file:` URIs and bare paths. */
private fun specToFile(spec: String): File? = runCatching {
    if (spec.startsWith("file:")) File(URI(spec)) else File(spec)
}.getOrNull()
