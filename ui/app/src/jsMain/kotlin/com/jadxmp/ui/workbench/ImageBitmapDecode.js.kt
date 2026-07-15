package com.jadxmp.ui.workbench

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Browser (Kotlin/JS + skiko) decode — identical to the desktop path; Compose/JS bundles skiko.
 * [runCatching] guarantees the never-throw contract on malformed bytes.
 */
internal actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
