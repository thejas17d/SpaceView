package com.spaceview.app.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * The transparency checkerboard drawn behind a background-free cutout (PLAN v3 W2), so the user sees that the
 * background is gone. Two quiet surface tones from the theme (light and dark).
 */
@Composable
fun Checkerboard(modifier: Modifier = Modifier) {
    val a = MaterialTheme.colorScheme.surfaceContainerLow
    val b = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val cell = 8.dp.toPx()
        drawRect(a)
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            if ((r + c) % 2 == 1) drawRect(b, topLeft = Offset(c * cell, r * cell), size = Size(cell, cell))
        }
    }
}
