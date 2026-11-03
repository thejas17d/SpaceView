package com.spaceview.app.common

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * The big bottom-centre primary action on AR screens ("+": add point / capture / place).
 * 72 dp signal-yellow disc with a graphite outline so it reads on both white walls and dark floors.
 *
 * @param contentDescription TalkBack label; defaults to "Add point". Pass e.g. "Capture" or
 *   "Place here" when the same button does something else.
 */
@Composable
fun AddPointButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = stringResource(R.string.common_add_point),
    size: Dp = 72.dp,
) {
    val colors = SpaceViewTheme.colors
    val fill = if (enabled) colors.signal else colors.signal.copy(alpha = 0.38f)
    val ink = if (enabled) colors.onSignal else colors.onSignal.copy(alpha = 0.5f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = fill,
        contentColor = ink,
        border = BorderStroke(2.dp, colors.arChrome),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size).padding(size * 0.3f)) {
                val stroke = 3.dp.toPx()
                val c = center
                val r = this.size.minDimension / 2f
                drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke, StrokeCap.Square)
                drawLine(ink, Offset(c.x, c.y - r), Offset(c.x, c.y + r), stroke, StrokeCap.Square)
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddPointButtonPreview() {
    SpaceViewTheme { AddPointButton(onClick = {}) }
}
