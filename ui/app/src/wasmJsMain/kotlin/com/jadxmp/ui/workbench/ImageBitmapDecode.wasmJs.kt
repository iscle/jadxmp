package com.jadxmp.ui.workbench

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Browser (Kotlin/Wasm + skiko) decode — the hard-gate target (rule 1). Same skiko path as desktop/JS;
 * Compose/wasm bundles skiko. [runCatching] guarantees the never-throw contract on malformed bytes.
 */
internal actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
