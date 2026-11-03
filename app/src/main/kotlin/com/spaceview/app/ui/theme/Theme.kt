package com.spaceview.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Flat surfaces with small radii (8-12 dp max): a tool, not a toy. */
internal val SpaceViewShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private val LocalInstrumentColors = staticCompositionLocalOf { LightInstrumentColors }
private val LocalInstrumentTypography = staticCompositionLocalOf { DefaultInstrumentTypography }

/**
 * SpaceView's theme. Non-AR screens follow the system light/dark setting (paper spec sheet vs
 * graphite). Dynamic colour is intentionally off: the instrument palette is part of the brand.
 */
@Composable
fun SpaceViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalInstrumentColors provides if (darkTheme) DarkInstrumentColors else LightInstrumentColors,
        LocalInstrumentTypography provides DefaultInstrumentTypography,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = SpaceViewTypography,
            shapes = SpaceViewShapes,
            content = content,
        )
    }
}

/** Accessors for the instrument tokens, mirroring [MaterialTheme]. */
object SpaceViewTheme {
    val colors: InstrumentColors
        @Composable
        @ReadOnlyComposable
        get() = LocalInstrumentColors.current

    val typography: InstrumentTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalInstrumentTypography.current
}
