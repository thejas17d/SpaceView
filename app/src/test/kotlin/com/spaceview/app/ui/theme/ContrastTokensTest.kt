package com.spaceview.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * M5 accessibility (PLAN §9: contrast ≥ 4.5:1, both themes): every text-on-surface pair the screens actually use,
 * computed with the WCAG 2.x relative-luminance formula from the theme tokens themselves.
 */
class ContrastTokensTest {
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun ratio(fg: Color, bg: Color): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun pairs(s: ColorScheme, i: InstrumentColors): List<Triple<String, Color, Color>> = listOf(
        Triple("onBackground/background", s.onBackground, s.background),
        Triple("onSurface/surface", s.onSurface, s.surface),
        Triple("onSurfaceVariant/background", s.onSurfaceVariant, s.background),
        Triple("onSurface/card", s.onSurface, s.surfaceContainerLowest),
        Triple("onSurfaceVariant/card", s.onSurfaceVariant, s.surfaceContainerLowest),
        Triple("error/card", s.error, s.surfaceContainerLowest),
        Triple("error/background", s.error, s.background),
        Triple("primary/background (text buttons)", s.primary, s.background),
        Triple("onPrimary/primary (filled buttons)", s.onPrimary, s.primary),
        Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
        Triple("signalText/background", i.signalText, s.background),
        Triple("onSignal/signal (primary CTA)", i.onSignal, i.signal),
        Triple("accuracyGood/card", i.accuracyGood, s.surfaceContainerLowest),
        Triple("accuracyFair/card", i.accuracyFair, s.surfaceContainerLowest),
        Triple("accuracyRough/card", i.accuracyRough, s.surfaceContainerLowest),
        Triple("accuracyGood/background", i.accuracyGood, s.background),
        Triple("accuracyFair/background", i.accuracyFair, s.background),
        Triple("accuracyRough/background", i.accuracyRough, s.background),
        // AR chrome is translucent: check it over the worst camera frame (pure white).
        Triple("onArChrome/arChrome over white", i.onArChrome, i.arChrome.compositeOver(Color.White)),
    )

    private fun check(name: String, s: ColorScheme, i: InstrumentColors) {
        val failures = pairs(s, i).map { (label, fg, bg) -> label to ratio(fg, bg) }.filter { it.second < 4.5 }
        assertTrue("$name theme below 4.5:1: ${failures.joinToString { "${it.first}=%.2f".format(it.second) }}", failures.isEmpty())
    }

    @Test fun lightThemeTextContrast() = check("Light", LightColorScheme, LightInstrumentColors)

    @Test fun darkThemeTextContrast() = check("Dark", DarkColorScheme, DarkInstrumentColors)
}
