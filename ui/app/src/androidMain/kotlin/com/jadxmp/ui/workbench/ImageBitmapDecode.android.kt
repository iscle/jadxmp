package com.jadxmp.ui.workbench

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android decode via the platform `BitmapFactory` (no skiko on Android Compose). `decodeByteArray`
 * returns `null` on undecodable data rather than throwing, and [runCatching] covers anything unexpected,
 * satisfying the never-throw contract. (Android is a later target per CLAUDE.md; this keeps the viewer
 * honest there instead of stubbing it out.)
 */
internal actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
