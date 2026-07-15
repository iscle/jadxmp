package com.jadxmp.ui.workbench

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Desktop (JVM/skiko) decode. `Image.makeFromEncoded` handles PNG/JPEG/GIF/WebP/BMP; it throws on
 * malformed data, so [runCatching] turns any failure into the `null` the contract promises.
 */
internal actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
