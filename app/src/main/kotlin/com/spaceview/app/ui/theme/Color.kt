package com.spaceview.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// "Precision instrument" palette (docs/research/ui-ux.md, section 7): graphite and paper neutrals
// plus ONE high-visibility signal colour, used only for the reticle, the active measurement and
// the primary call to action. Status colours are deliberately desaturated.

internal val Graphite900 = Color(0xFF16181B)
internal val Graphite800 = Color(0xFF1E2124)
internal val Graphite750 = Color(0xFF23262A)
internal val Graphite700 = Color(0xFF292D31)
internal val Graphite650 = Color(0xFF30343A)
internal val Graphite600 = Color(0xFF3A3F45)
internal val Graphite500 = Color(0xFF4A4F55)
internal val Graphite400 = Color(0xFF6B7178)
internal val Graphite300 = Color(0xFFA9AEB4)

internal val Paper0 = Color(0xFFFFFFFF)
internal val Paper50 = Color(0xFFF6F5F1)
internal val Paper100 = Color(0xFFF0EEE9)
internal val Paper150 = Color(0xFFEAE8E2)
internal val Paper200 = Color(0xFFE3E1DA)
internal val Paper300 = Color(0xFFD8D5CC)
internal val Paper400 = Color(0xFF8A8F95)

/** Signal yellow: reticle, active measurement, primary CTA. Never used for body text on paper. */
internal val Signal = Color(0xFFFFD400)

/** Signal hue darkened to pass WCAG AA as text on paper (about 5.5:1). */
internal val SignalInk = Color(0xFF7A6000)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Graphite900,
    onPrimary = Paper50,
    primaryContainer = Paper200,
    onPrimaryContainer = Graphite900,
    secondary = Graphite600,
    onSecondary = Paper50,
    secondaryContainer = Paper150,
    onSecondaryContainer = Graphite900,
    tertiary = Graphite600,
    onTertiary = Paper50,
    tertiaryContainer = Paper150,
    onTertiaryContainer = Graphite900,
    background = Paper50,
    onBackground = Graphite900,
    surface = Paper50,
    onSurface = Graphite900,
    surfaceVariant = Paper150,
    onSurfaceVariant = Graphite500,
    surfaceTint = Color.Transparent,
    inverseSurface = Graphite900,
    inverseOnSurface = Paper50,
    inversePrimary = Signal,
    error = Color(0xFFA3483D),
    onError = Paper0,
    errorContainer = Color(0xFFF3DEDA),
    onErrorContainer = Color(0xFF4A1A14),
    outline = Paper400,
    outlineVariant = Paper300,
    scrim = Graphite900,
    surfaceBright = Paper0,
    surfaceDim = Paper200,
    surfaceContainerLowest = Paper0,
    surfaceContainerLow = Paper100,
    surfaceContainer = Paper150,
    surfaceContainerHigh = Paper200,
    surfaceContainerHighest = Paper300,
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Paper50,
    onPrimary = Graphite900,
    primaryContainer = Graphite650,
    onPrimaryContainer = Paper50,
    secondary = Graphite300,
    onSecondary = Graphite900,
    secondaryContainer = Graphite700,
    onSecondaryContainer = Paper50,
    tertiary = Graphite300,
    onTertiary = Graphite900,
    tertiaryContainer = Graphite700,
    onTertiaryContainer = Paper50,
    background = Graphite900,
    onBackground = Paper50,
    surface = Graphite900,
    onSurface = Paper50,
    surfaceVariant = Graphite700,
    onSurfaceVariant = Graphite300,
    surfaceTint = Color.Transparent,
    inverseSurface = Paper50,
    inverseOnSurface = Graphite900,
    inversePrimary = Graphite900,
    error = Color(0xFFE59A8F),
    onError = Graphite900,
    errorContainer = Color(0xFF5A2620),
    onErrorContainer = Color(0xFFF7DCD7),
    outline = Graphite400,
    outlineVariant = Graphite650,
    scrim = Color(0xFF000000),
    surfaceBright = Graphite650,
    surfaceDim = Graphite900,
    surfaceContainerLowest = Color(0xFF111316),
    surfaceContainerLow = Graphite800,
    surfaceContainer = Graphite750,
    surfaceContainerHigh = Graphite700,
    surfaceContainerHighest = Graphite650,
)

/**
 * Instrument-specific colours that Material's [ColorScheme] has no slot for.
 *
 * @property signal fill colour for the reticle, active measurement and primary CTA.
 * @property onSignal content drawn on [signal].
 * @property signalText the signal hue when it must be legible as text on the current surface.
 * @property accuracyGood accuracy badge colour for "Good".
 * @property accuracyFair accuracy badge colour for "Fair".
 * @property accuracyRough accuracy badge colour for "Rough".
 * @property hairline 1 dp borders and dimension lines on non-AR screens.
 * @property arChrome solid pill behind text drawn over the camera feed (always dark).
 * @property onArChrome content on [arChrome].
 */
@Immutable
data class InstrumentColors(
    val signal: Color,
    val onSignal: Color,
    val signalText: Color,
    val accuracyGood: Color,
    val accuracyFair: Color,
    val accuracyRough: Color,
    val hairline: Color,
    val arChrome: Color,
    val onArChrome: Color,
)

/** AR overlays are always "dark chrome": an ~82% opaque graphite pill, never a blur. */
private val ArChrome = Graphite900.copy(alpha = 0.82f)

internal val LightInstrumentColors = InstrumentColors(
    signal = Signal,
    onSignal = Graphite900,
    signalText = SignalInk,
    accuracyGood = Color(0xFF3F7A57),
    accuracyFair = Color(0xFF8A6A1F),
    accuracyRough = Color(0xFFA3483D),
    hairline = Paper300,
    arChrome = ArChrome,
    onArChrome = Paper50,
)

internal val DarkInstrumentColors = InstrumentColors(
    signal = Signal,
    onSignal = Graphite900,
    signalText = Signal,
    accuracyGood = Color(0xFF8CC3A0),
    accuracyFair = Color(0xFFE0BD6A),
    accuracyRough = Color(0xFFE59A8F),
    hairline = Graphite650,
    arChrome = ArChrome,
    onArChrome = Paper50,
)
