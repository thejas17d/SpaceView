package com.spaceview.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OpenType features for every number SpaceView shows: tabular (fixed-width) lining figures, so a
 * live reading does not jitter sideways while its digits change.
 */
private const val TABULAR_FIGURES = "tnum, lnum"

private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

/**
 * Material type scale on the system grotesk (Roboto / OEM sans; no bundled font, to keep the APK
 * small) with tabular figures everywhere.
 */
internal val SpaceViewTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.tabular(),
        displayMedium = base.displayMedium.tabular(),
        displaySmall = base.displaySmall.tabular(),
        headlineLarge = base.headlineLarge.tabular(),
        headlineMedium = base.headlineMedium.tabular(),
        headlineSmall = base.headlineSmall.tabular(),
        titleLarge = base.titleLarge.tabular(),
        titleMedium = base.titleMedium.tabular(),
        titleSmall = base.titleSmall.tabular(),
        bodyLarge = base.bodyLarge.tabular(),
        bodyMedium = base.bodyMedium.tabular(),
        bodySmall = base.bodySmall.tabular(),
        labelLarge = base.labelLarge.tabular(),
        labelMedium = base.labelMedium.tabular(),
        labelSmall = base.labelSmall.tabular(),
    )
}

/**
 * Instrument-specific text styles.
 *
 * @property readout the live measurement value.
 * @property tolerance the "± x" next to a readout: same figures, lighter and smaller.
 * @property spec spec-sheet lines (dimensions on gallery cards) in monospace.
 * @property caption small all-caps labels above readouts.
 */
@Immutable
data class InstrumentTypography(
    val readout: TextStyle,
    val tolerance: TextStyle,
    val spec: TextStyle,
    val caption: TextStyle,
)

internal val DefaultInstrumentTypography = InstrumentTypography(
    readout = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    tolerance = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    spec = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)
