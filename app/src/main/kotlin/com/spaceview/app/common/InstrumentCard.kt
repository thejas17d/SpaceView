package com.spaceview.app.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Flat card with a 1 dp hairline border: the base surface of every non-AR screen
 * ("spec sheet" look, no elevation, 8 dp corners).
 *
 * @param onClick makes the whole card one touch target (≥ 48 dp tall content recommended).
 */
@Composable
fun InstrumentCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val color = MaterialTheme.colorScheme.surfaceContainerLowest
    val border = BorderStroke(1.dp, SpaceViewTheme.colors.hairline)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            Column(content = content)
        }
    }
}
