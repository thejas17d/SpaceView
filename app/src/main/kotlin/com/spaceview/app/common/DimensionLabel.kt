package com.spaceview.app.common

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.engine.FormattedLength
import com.spaceview.engine.LengthUnit

/**
 * Drafting-style dimension label: a solid pill with the value in tabular figures and the ± in a
 * lighter, smaller weight ("45.5 cm ± 0.5").
 *
 * @param value value with its unit, e.g. "45.5 cm" or "5′ 8″".
 * @param tolerance the ± part without the sign, e.g. "0.5"; null hides it.
 * @param contentDescription TalkBack text; defaults to the visible text. Prefer the
 *   [FormattedLength] overload, which speaks units out ("centimetres").
 * @param onDarkChrome true when drawn over the camera feed (graphite pill, light text).
 */
@Composable
fun DimensionLabel(
    value: String,
    modifier: Modifier = Modifier,
    tolerance: String? = null,
    contentDescription: String? = null,
    onDarkChrome: Boolean = false,
) {
    val colors = SpaceViewTheme.colors
    val description = contentDescription ?: if (tolerance == null) value else "$value ± $tolerance"
    Surface(
        modifier = modifier.clearAndSetSemantics { this.contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = if (onDarkChrome) colors.arChrome else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (onDarkChrome) colors.onArChrome else MaterialTheme.colorScheme.onSurface,
        border = if (onDarkChrome) null else BorderStroke(1.dp, colors.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (tolerance != null) {
                Text(
                    "± $tolerance",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onDarkChrome) colors.onArChrome.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** [DimensionLabel] for an engine-formatted length; TalkBack speaks the units out. */
@Composable
fun DimensionLabel(
    length: FormattedLength,
    modifier: Modifier = Modifier,
    onDarkChrome: Boolean = false,
) {
    val value = if (length.unit == LengthUnit.FEET_INCHES) length.value else "${length.value} ${length.unitSymbol}"
    DimensionLabel(
        value = value,
        modifier = modifier,
        tolerance = length.plusMinus,
        contentDescription = spokenLength(length),
        onDarkChrome = onDarkChrome,
    )
}

/**
 * Engineering-drawing dimension line across the available width: 1.5 dp line, end ticks
 * (extension lines) and a [DimensionLabel] centred on the line. For review cards and item detail.
 */
@Composable
fun DimensionLine(
    length: FormattedLength,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.5.dp.toPx()
            val tick = 8.dp.toPx()
            val arrow = 5.dp.toPx()
            val y = size.height / 2f
            val left = stroke / 2f
            val right = size.width - stroke / 2f
            drawLine(lineColor, Offset(left, y), Offset(right, y), stroke)
            drawLine(lineColor, Offset(left, y - tick), Offset(left, y + tick), stroke)
            drawLine(lineColor, Offset(right, y - tick), Offset(right, y + tick), stroke)
            // Open arrowheads at both ends, pointing at the extension lines.
            drawLine(lineColor, Offset(left, y), Offset(left + arrow, y - arrow * 0.6f), stroke)
            drawLine(lineColor, Offset(left, y), Offset(left + arrow, y + arrow * 0.6f), stroke)
            drawLine(lineColor, Offset(right, y), Offset(right - arrow, y - arrow * 0.6f), stroke)
            drawLine(lineColor, Offset(right, y), Offset(right - arrow, y + arrow * 0.6f), stroke)
        }
        DimensionLabel(length)
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DimensionPreview() {
    SpaceViewTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DimensionLabel("45.5 cm", tolerance = "0.5")
                DimensionLabel("61 cm", tolerance = "1", onDarkChrome = true)
                DimensionLine(FormattedLength("45.5", "cm", "0.5", LengthUnit.CM, 0.1))
            }
        }
    }
}
