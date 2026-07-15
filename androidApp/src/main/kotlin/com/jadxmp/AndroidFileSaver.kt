package com.jadxmp

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jadxmp.ui.client.FileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android [FileSaver] — the write-side analogue of the desktop save dialog / web download.
 *
 * ## Documented simplification (allowed by the task)
 * A full Storage Access Framework `ACTION_CREATE_DOCUMENT` flow needs an Activity-result round trip that
 * this injected, Activity-agnostic seam can't cleanly bridge to a `suspend` call. So instead of a
 * user-chosen destination this writes to Downloads, permission-free:
 *  - **API 29+ (Q):** inserts into the `MediaStore` Downloads collection (visible in the Files/Downloads
 *    UI, no runtime permission).
 *  - **API 24-28:** writes to the app-specific external Downloads dir (`getExternalFilesDir`), which also
 *    needs no permission but is app-scoped rather than the shared Downloads folder.
 *
 * The write runs off the UI thread (`Dispatchers.IO`). Any failure is swallowed to `false` (rule 4).
 * The `MediaStore.Downloads` symbols are API 29+ but compile against compileSdk 36; the `SDK_INT` guard
 * keeps them off older runtimes.
 */
class AndroidFileSaver(context: Context) : FileSaver {
    private val appContext = context.applicationContext

    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStoreDownloads(suggestedName, bytes)
                } else {
                    saveToAppExternalDownloads(suggestedName, bytes)
                }
            }.getOrDefault(false)
        }

    private fun saveToMediaStoreDownloads(name: String, bytes: ByteArray): Boolean {
        val resolver = appContext.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending) ?: return false
        // The insert created a row flagged IS_PENDING=1. If opening the stream fails (null), writing the
        // bytes throws, or clearing the pending flag throws, that row would linger forever as an
        // invisible orphan in Downloads. So guard open+write+update as one unit and, on ANY failure,
        // delete the row before returning false — rolling back the half-created entry (rule 4).
        return runCatching {
            val stream = resolver.openOutputStream(uri) ?: error("openOutputStream returned null")
            stream.use { it.write(bytes) }
            // Clear the pending flag so the file becomes visible to other apps.
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) } // best-effort rollback; itself never throws.
            false
        }
    }

    private fun saveToAppExternalDownloads(name: String, bytes: ByteArray): Boolean {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
        File(dir, name).writeBytes(bytes)
        return true
    }
}
