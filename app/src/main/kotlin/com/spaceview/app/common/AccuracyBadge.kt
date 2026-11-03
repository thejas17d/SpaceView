package com.spaceview.app.common

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.ui.theme.DarkInstrumentColors
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.engine.Quality

/** Short badge label: "Good" / "Fair" / "Rough". */
@StringRes
fun Quality.labelRes(): Int = when (this) {
    Quality.GOOD -> R.string.accuracy_good
    Quality.FAIR -> R.string.accuracy_fair
    Quality.ROUGH -> R.string.accuracy_rough
}

/** TalkBack name: "Good accuracy" / "Fair accuracy" / "Rough estimate". */
@StringRes
fun Quality.contentDescriptionRes(): Int = when (this) {
    Quality.GOOD -> R.string.a11y_accuracy_good
    Quality.FAIR -> R.string.a11y_accuracy_fair
    Quality.ROUGH -> R.string.a11y_accuracy_rough
}

/** One-line plain-language meaning, e.g. "Fine for planning. Check before buying." */
@StringRes
fun Quality.hintRes(): Int = when (this) {
    Quality.GOOD -> R.string.accuracy_good_hint
    Quality.FAIR -> R.string.accuracy_fair_hint
    Quality.ROUGH -> R.string.accuracy_rough_hint
}

/** Badge colour for [quality]; [onDarkChrome] picks the variant legible on AR's graphite pills. */
@Composable
fun accuracyColor(quality: Quality, onDarkChrome: Boolean = false): Color {
    val c = if (onDarkChrome) DarkInstrumentColors else SpaceViewTheme.colors
    return when (quality) {
        Quality.GOOD -> c.accuracyGood
        Quality.FAIR -> c.accuracyFair
        Quality.ROUGH -> c.accuracyRough
    }
}

/**
 * The accuracy glyph alone. Shape carries the meaning, colour only reinforces it
 * (colour-blind safe): GOOD = filled circle ●, FAIR = half-filled circle ◐, ROUGH = empty ring ○.
 * Decorative: it has no semantics; wrap it in something that describes the quality.
 */
@Composable
fun AccuracyGlyph(quality: Quality, color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val r = (this.size.minDimension - stroke) / 2f
        when (quality) {
            Quality.GOOD -> drawCircle(color, radius = this.size.minDimension / 2f)
            Quality.FAIR -> {
                drawArc(
                    color = color,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(2 * r, 2 * r),
                )
                drawCircle(color, radius = r, style = Stroke(stroke))
            }
            Quality.ROUGH -> drawCircle(color, radius = r, style = Stroke(stroke))
        }
    }
}

/**
 * The ± / quality badge shown with every result: glyph + "Good" and optionally "± 0.5 cm".
 *
 * TalkBack reads it as one item, e.g. "Good accuracy, plus or minus 0.5 centimetres".
 *
 * @param tolerance visible tolerance text such as "± 0.5 cm"; null to show the quality only.
 * @param toleranceSpoken TalkBack text for the tolerance (see [spokenAmount]); defaults to [tolerance].
 * @param onDarkChrome true on AR/camera overlays: draws a graphite pill with light text.
 */
@Composable
fun AccuracyBadge(
    quality: Quality,
    modifier: Modifier = Modifier,
    tolerance: String? = null,
    toleranceSpoken: String? = tolerance,
    onDarkChrome: Boolean = false,
) {
    val color = accuracyColor(quality, onDarkChrome)
    val description = stringResource(quality.contentDescriptionRes()).let { q ->
        if (toleranceSpoken == null) q else stringResource(R.string.a11y_badge_with_tolerance, q, toleranceSpoken)
    }
    val textColor = if (onDarkChrome) SpaceViewTheme.colors.onArChrome else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = if (onDarkChrome) SpaceViewTheme.colors.arChrome else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (onDarkChrome) 10.dp else 0.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AccuracyGlyph(quality, color)
            Text(stringResource(quality.labelRes()), style = MaterialTheme.typography.labelLarge, color = color)
            if (tolerance != null) {
                Text(tolerance, style = MaterialTheme.typography.labelLarge, color = textColor)
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AccuracyBadgePreview() {
    SpaceViewTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccuracyBadge(Quality.GOOD, tolerance = "± 0.5 cm")
                AccuracyBadge(Quality.FAIR, tolerance = "± 2 cm")
                AccuracyBadge(Quality.ROUGH)
                AccuracyBadge(Quality.FAIR, tolerance = "± 1 cm", onDarkChrome = true)
            }
        }
    }
}
