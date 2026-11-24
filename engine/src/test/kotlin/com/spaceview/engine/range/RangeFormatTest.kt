package com.spaceview.engine.range

import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeFormatTest {
    private fun m(value: Double, bound95: Double) = Measurement(value, bound95 / Uncertainty.K95)

    @Test
    fun neverMoreDigitsThanTheBoundSupports() {
        assertEquals("500 m ± 18 m", RangeFormat.format(m(503.27, 18.0), imperial = false).text)
        assertEquals("4.53 m ± 0.06 m", RangeFormat.format(m(4.5312, 0.055), imperial = false).text)
        assertEquals("120 m ± 13 m", RangeFormat.format(m(123.4, 12.2), imperial = false).text)
    }

    @Test
    fun imperialUsesDecimalFeet() {
        // 100 m ± 5 m = 328.08 ft ± 16.4 ft → ± rounds up to 17 ft, the value to tens of feet.
        val t = RangeFormat.format(m(100.0, 5.0), imperial = true)
        assertEquals("330 ft ± 17 ft", t.text)
        assertEquals(RangeFormat.FEET_SYMBOL, t.unitSymbol)
    }

    @Test
    fun theShownBoundIsNeverSmallerThanTheComputedOne() {
        for (b in listOf(0.011, 0.093, 1.04, 7.7, 18.0, 151.0)) {
            val t = RangeFormat.format(m(200.0, b), imperial = false)
            assertTrue("${t.plusMinus} >= $b", t.plusMinus.toDouble() >= b)
        }
    }
}
