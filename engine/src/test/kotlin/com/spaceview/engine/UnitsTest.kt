package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.abs

class UnitsTest {

    /** Measurement whose displayed ±95 is exactly [pm95] metres. */
    private fun m(value: Double, pm95: Double) = Measurement(value, pm95 / Uncertainty.K95)

    private fun inch(x: Double) = x * Units.METRES_PER_INCH

    @Test fun conversionsRoundTrip() {
        for (u in LengthUnit.entries) assertEquals(1.2345, Units.toMetres(Units.fromMetres(1.2345, u), u), 1e-12)
        assertEquals(100.0, Units.fromMetres(1.0, LengthUnit.CM), 1e-12)
        assertEquals(1000.0, Units.fromMetres(1.0, LengthUnit.MM), 1e-12)
        assertEquals(12.0, Units.fromMetres(Units.METRES_PER_FOOT, LengthUnit.FEET_INCHES), 1e-12)
        assertEquals(1.0, Units.fromMetres(0.0254, LengthUnit.INCH_FRACTION), 1e-12)
        assertTrue(LengthUnit.FEET_INCHES.isImperial)
        assertFalse(LengthUnit.CM.isImperial)
    }

    @Test fun metricPrecisionFollowsPlusMinus() {
        assertEquals("60 cm ± 1.4", Units.format(m(0.6034, 0.014), LengthUnit.CM).text) // ±1.4 → whole cm
        assertEquals("45.5 cm ± 0.5", Units.format(m(0.4552, 0.005), LengthUnit.CM).text)
        assertEquals("350 cm ± 12", Units.format(m(3.47, 0.12), LengthUnit.CM).text)
        assertEquals("61 cm ± 2", Units.format(m(0.6123, 0.023), LengthUnit.CM).text)
        assertEquals("45.0 cm", Units.format(Measurement.exact(0.45), LengthUnit.CM).text) // exact → finest step, no ±
        assertEquals("3.46 m ± 0.07", Units.format(m(3.456, 0.07), LengthUnit.M).text)
        assertEquals("86 mm ± 2", Units.format(m(0.0856, 0.0016), LengthUnit.MM).text)
        // Never finer than 1 mm, even with a tiny ±.
        assertEquals("8.6 cm ± 0.1", Units.format(m(0.0856, 0.0002), LengthUnit.CM).text)
    }

    @Test fun formattedParts() {
        val f = Units.format(m(0.4552, 0.005), LengthUnit.CM)
        assertEquals("45.5", f.value)
        assertEquals("cm", f.unitSymbol)
        assertEquals("0.5", f.plusMinus)
        assertEquals(0.1, f.step, 1e-12)
        assertEquals(f.text, f.toString())
    }

    @Test fun fractionalInches() {
        // 0.6 m = 23.62 in.
        assertEquals("23 1/2 in ± 1/4", Units.format(m(0.6, inch(0.3)), LengthUnit.INCH_FRACTION).text)
        assertEquals("23 5/8 in ± 1/8", Units.format(m(0.6, inch(0.1)), LengthUnit.INCH_FRACTION).text)
        assertEquals("24 in ± 1", Units.format(m(0.6, inch(1.3)), LengthUnit.INCH_FRACTION).text)
        assertEquals("23 1/2 in ± 1/2", Units.format(m(0.6, inch(0.6)), LengthUnit.INCH_FRACTION).text)
        // ± smaller than 1/16 still shows at least one step.
        assertEquals("1/16", Units.format(m(0.6, inch(0.01)), LengthUnit.INCH_FRACTION).plusMinus)
    }

    @Test fun feetAndInches() {
        assertEquals("5′ 8″ ± 1″", Units.format(m(1.73, inch(1.2)), LengthUnit.FEET_INCHES).text)
        assertEquals("5′ 8 1/2″ ± 1/2″", Units.format(m(1.74, inch(0.6)), LengthUnit.FEET_INCHES).text)
        assertEquals("12′ 0″", Units.format(Measurement.exact(Units.METRES_PER_FOOT * 12), LengthUnit.FEET_INCHES).text)
        assertEquals("7″ ± 1″", Units.format(m(inch(7.2), inch(1.1)), LengthUnit.FEET_INCHES).text)
        // 11.97 in rounds up to a full foot, not 0′ 12″.
        assertEquals("1′ 0″ ± 1″", Units.format(m(inch(11.97), inch(1.0)), LengthUnit.FEET_INCHES).text)
    }

    @Test fun fractionAndFeetTextHelpers() {
        assertEquals("0", Units.fractionText(0.0))
        assertEquals("1/2", Units.fractionText(0.5))
        assertEquals("-1 1/2", Units.fractionText(-1.5))
        assertEquals("3 3/16", Units.fractionText(3.1875))
        assertEquals("-1′ 2″", Units.feetInchesText(-14.0))
        assertEquals("0″", Units.feetInchesText(0.0))
        assertEquals("2′ 0″", Units.feetInchesText(24.0))
    }

    @Test fun secondaryValue() {
        assertEquals("24 in", Units.formatValueOnly(0.61, 0.005, LengthUnit.INCH_FRACTION))
        assertEquals("3.66 m", Units.formatValueOnly(3.66, 0.03, LengthUnit.M))
        assertEquals("5′ 8″", Units.formatValueOnly(1.73, 0.03, LengthUnit.FEET_INCHES))
    }

    @Test fun contextDefaults() {
        assertEquals(LengthUnit.FEET_INCHES to LengthUnit.M, Units.defaultUnits(MeasureContext.ROOM))
        assertEquals(LengthUnit.M to LengthUnit.FEET_INCHES, Units.defaultUnits(MeasureContext.ROOM, UnitPreference.METRIC))
        assertEquals(LengthUnit.FEET_INCHES to LengthUnit.M, Units.defaultUnits(MeasureContext.ROOM, UnitPreference.IMPERIAL))
        assertEquals(LengthUnit.CM to LengthUnit.INCH_FRACTION, Units.defaultUnits(MeasureContext.OBJECT))
        assertEquals(LengthUnit.INCH_FRACTION to LengthUnit.CM, Units.defaultUnits(MeasureContext.OBJECT, UnitPreference.IMPERIAL))
        assertEquals(LengthUnit.INCH_FRACTION to LengthUnit.CM, Units.defaultUnits(MeasureContext.FRAME))
        assertEquals(LengthUnit.CM to LengthUnit.INCH_FRACTION, Units.defaultUnits(MeasureContext.FRAME, UnitPreference.METRIC))
        assertEquals(LengthUnit.INCH_DECIMAL to LengthUnit.CM, Units.defaultUnits(MeasureContext.TV))
        assertEquals(LengthUnit.CM to LengthUnit.INCH_DECIMAL, Units.defaultUnits(MeasureContext.TV, UnitPreference.METRIC))
        assertEquals(LengthUnit.CM to LengthUnit.INCH_FRACTION, Units.defaultUnits(MeasureContext.SMALL_OBJECT))
    }

    @Test fun formatForContext() {
        assertEquals("61.0 cm ± 0.5 (24 in)", Units.formatForContext(m(0.61, 0.005), MeasureContext.OBJECT).text)
        assertEquals("12′ 0″ ± 1″ (3.66 m)", Units.formatForContext(m(3.66, 0.03), MeasureContext.ROOM).text)
        // 43-inch TV diagonal (109.2 cm).
        assertEquals("43.0 in ± 0.8 (109 cm)", Units.formatForContext(m(inch(43.0), 0.02), MeasureContext.TV).text)
        assertEquals("18 in ± 1/4 (45.7 cm)", Units.formatForContext(m(inch(18.0), 0.007), MeasureContext.FRAME).text)
        // Small object, card-calibrated close range: ± ≤ 2 mm switches to millimetres.
        val small = Units.formatForContext(m(0.0856, 0.0016), MeasureContext.SMALL_OBJECT)
        assertEquals(LengthUnit.MM, small.primary.unit)
        assertEquals("86 mm ± 2 (3 3/8 in)", small.text)
        // … but not with a bigger ±.
        assertEquals(LengthUnit.CM, Units.formatForContext(m(0.0856, 0.004), MeasureContext.SMALL_OBJECT).primary.unit)
        // Imperial small object keeps inches primary and mm secondary.
        val smallImp = Units.formatForContext(m(0.0856, 0.0016), MeasureContext.SMALL_OBJECT, UnitPreference.IMPERIAL)
        assertEquals(LengthUnit.INCH_FRACTION, smallImp.primary.unit)
        assertTrue(smallImp.secondary!!.endsWith("mm"))
        assertEquals(small.text, small.toString())
        val bare = ContextFormatted(Units.format(Measurement.exact(1.0), LengthUnit.M), null)
        assertEquals("1.000 m", bare.text)
    }

    @Test fun areas() {
        assertEquals("12.3 m² ± 0.4", Units.formatArea(m(12.3456, 0.4), AreaUnit.M2))
        assertEquals("133 sq ft ± 4", Units.formatArea(m(12.3456, 0.4), AreaUnit.SQ_FT))
        assertEquals("12.35 m²", Units.formatArea(Measurement.exact(12.3456), AreaUnit.M2))
        assertEquals("2400 cm² ± 30", Units.formatArea(m(0.24, 0.003), AreaUnit.CM2))
        assertEquals(AreaUnit.SQ_FT, Units.defaultAreaUnit(MeasureContext.ROOM))
        assertEquals(AreaUnit.M2, Units.defaultAreaUnit(MeasureContext.ROOM, UnitPreference.METRIC))
        assertEquals(AreaUnit.SQ_IN, Units.defaultAreaUnit(MeasureContext.FRAME, UnitPreference.IMPERIAL))
        assertEquals(AreaUnit.CM2, Units.defaultAreaUnit(MeasureContext.FRAME))
        assertEquals(AreaUnit.M2, Units.defaultAreaUnit(MeasureContext.OBJECT))
    }

    @Test fun localeIndependent() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("45.5 cm ± 0.5", Units.format(m(0.4552, 0.005), LengthUnit.CM).text)
            Locale.setDefault(Locale.forLanguageTag("hi-IN-u-nu-deva"))
            assertEquals("45.5 cm ± 0.5", Units.format(m(0.4552, 0.005), LengthUnit.CM).text)
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test fun negativeValuesFormat() {
        assertEquals("-4 cm ± 3", Units.format(m(-0.04, 0.027), LengthUnit.CM).text)
        assertEquals("0 cm ± 3", Units.format(m(-0.001, 0.027), LengthUnit.CM).text) // no "-0"
    }

    /** Property: displayed value is within half a step of the truth and the step is never finer than the ±'s leading digit. */
    @Test fun roundingNeverExceedsPlusMinusPrecision() {
        val rng = Random(31)
        repeat(5000) {
            val v = rng.nextDouble() * 5
            val pm = 0.0005 + rng.nextDouble() * 0.2
            for (unit in listOf(LengthUnit.CM, LengthUnit.MM, LengthUnit.M)) {
                val f = Units.format(m(v, pm), unit)
                val pmU = Units.fromMetres(pm, unit)
                val shown = f.value.toDouble()
                assertTrue("$unit $v $pm -> ${f.text}", abs(shown - Units.fromMetres(v, unit)) <= f.step / 2 + 1e-9)
                val atMin = abs(f.step - Units.minStep(unit)) < 1e-12
                assertTrue("step ${f.step} too fine for ±$pmU", atMin || f.step * 10 > pmU)
                assertTrue("step ${f.step} coarser than ±$pmU", atMin || f.step <= pmU * (1 + 1e-9))
            }
            val fi = Units.format(m(v, pm), LengthUnit.INCH_FRACTION)
            val pmIn = pm / Units.METRES_PER_INCH
            assertTrue(fi.step <= maxOf(pmIn, 1.0 / 16) + 1e-12)
        }
    }

    @Test fun stepHelpers() {
        assertEquals(0.1, Units.stepFor(0.0, LengthUnit.CM), 0.0)
        assertEquals(1.0 / 16, Units.stepFor(-1.0, LengthUnit.FEET_INCHES), 0.0)
        assertEquals(1.0, Units.decimalLeadingPlace(1.0), 0.0)
        assertEquals(0.1, Units.decimalLeadingPlace(0.5), 1e-15)
        assertEquals(10.0, Units.decimalLeadingPlace(12.0), 1e-12)
        assertEquals(1.0, Units.stepFor(5.0, LengthUnit.INCH_FRACTION), 0.0)
    }
}
