package com.jadxmp.ui.workbench

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode encoded image [bytes] (PNG / JPEG / GIF / WebP / BMP) to a Compose [ImageBitmap], or `null`
 * when the bytes are not a decodable image on this platform.
 *
 * The one piece of [ImageViewer] that is genuinely platform-specific, so it lives behind `expect`/
 * `actual` (rule 1: the shared UI stays platform-free). Actuals: skiko
 * (`org.jetbrains.skia.Image.makeFromEncoded(...).toComposeImageBitmap()`) on the desktop / JS / wasm
 * Compose targets, which all bundle skiko; Android's `BitmapFactory`. **Must never throw** — a decode
 * failure returns `null` so the viewer falls back to a placeholder + hex dump (rule 4).
 */
internal expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
