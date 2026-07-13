package com.jadxmp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Rounding from the mockup: badges/chips are barely softened, controls and cards sit around 8–10dp,
 * dialogs and windows at 12–14dp. Nothing is a full pill except the switch/toggle track — this is a
 * precision instrument, not a consumer app.
 */
val JadxShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), // badges, kbd hints, syntax-swatch chips
    small = RoundedCornerShape(8.dp), // buttons, filter fields, tree rows, tabs
    medium = RoundedCornerShape(10.dp), // cards, list items, stat tiles
    large = RoundedCornerShape(12.dp), // panels, popovers, dialogs
    extraLarge = RoundedCornerShape(14.dp), // window frames, drop zone
)
