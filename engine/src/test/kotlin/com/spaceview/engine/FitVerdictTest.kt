package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FitVerdictTest {
    private fun m(v: Double, pm95: Double) = Measurement(v, pm95 / Uncertainty.K95)

    @Test fun clearlyFits() {
        val r = FitVerdict.evaluate(m(1.00, 0.02), m(1.10, 0.02))
        assertEquals(Fit.FITS, r.fit)
        assertEquals(0.10, r.clearance.value, 1e-12)
        assertEquals(sqrt(2.0) * 0.02, r.clearance.bound95, 1e-12)
        assertFalse(r.rotated)
    }

    @Test fun clearlyTooBig() {
        val r = FitVerdict.evaluate(m(1.10, 0.02), m(1.00, 0.02))
        assertEquals(Fit.TOO_BIG, r.fit)
        assertEquals(-0.10, r.clearance.value, 1e-12)
    }

    @Test fun withinUncertaintyIsTight() {
        // 1 cm spare but ±2.8 cm combined: cannot promise.
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(1.00, 0.02), m(1.01, 0.02)).fit)
        // 1 cm too big, same uncertainty: also tight, not a hard "no".
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(1.01, 0.02), m(1.00, 0.02)).fit)
    }

    @Test fun exactEqualityIsTight() {
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(Measurement.exact(1.0), Measurement.exact(1.0)).fit)
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(1.0, 0.01), m(1.0, 0.01)).fit)
    }

    @Test fun zeroUncertaintyIsDecisive() {
        assertEquals(Fit.FITS, FitVerdict.evaluate(Measurement.exact(1.0), Measurement.exact(1.0001)).fit)
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(Measurement.exact(1.0001), Measurement.exact(1.0)).fit)
    }

    @Test fun boundaryOfTheBand() {
        // Just inside z·σc → TIGHT; just outside → FITS / TOO_BIG.
        val item = Measurement(1.0, 0.0)
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(item, Measurement(1.0 + 1.96 * 0.01 - 1e-9, 0.01)).fit)
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(item, Measurement(1.0 - 1.96 * 0.01 + 1e-9, 0.01)).fit)
        assertEquals(Fit.FITS, FitVerdict.evaluate(item, Measurement(1.0 + 1.96 * 0.01 + 1e-9, 0.01)).fit)
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(item, Measurement(1.0 - 1.96 * 0.01 - 1e-9, 0.01)).fit)
    }

    @Test fun minClearanceRequirement() {
        // A TV needing 5 cm around it: 5.5 cm spare with ±1 cm combined is not enough to call FITS.
        val p = FitPolicy(minClearance = 0.05)
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(1.00, 0.007), m(1.055, 0.007), p).fit)
        assertEquals(Fit.FITS, FitVerdict.evaluate(m(1.00, 0.007), m(1.10, 0.007), p).fit)
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(m(1.00, 0.007), m(1.02, 0.007), p).fit)
    }

    @Test fun worstAxisDecides() {
        val item = listOf(m(0.60, 0.01), m(0.90, 0.01), m(0.40, 0.01))
        val space = listOf(m(0.80, 0.01), m(0.905, 0.01), m(0.50, 0.01))
        val r = FitVerdict.evaluate(item, space)
        assertEquals(Fit.TIGHT, r.fit)
        assertEquals(1, r.limitingAxis.axis)
        assertEquals(listOf(Fit.FITS, Fit.TIGHT, Fit.FITS), r.axes.map { it.fit })
        val tooBig = FitVerdict.evaluate(item, listOf(m(0.80, 0.01), m(0.85, 0.01), m(0.50, 0.01)))
        assertEquals(Fit.TOO_BIG, tooBig.fit)
        assertEquals(1, tooBig.limitingAxis.axis)
        assertTrue(tooBig.clearance.value < 0)
    }

    @Test fun rotationFindsBestOrientation() {
        // Box 0.9 wide × 0.5 tall into a 0.6 wide × 1.0 tall niche: only fits on its side.
        val item = listOf(m(0.90, 0.01), m(0.50, 0.01))
        val space = listOf(m(0.60, 0.01), m(1.00, 0.01))
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(item, space).fit)
        val r = FitVerdict.evaluate(item, space, FitPolicy(allowRotation = true))
        assertEquals(Fit.FITS, r.fit)
        assertTrue(r.rotated)
        assertEquals(1, r.axes[0].itemAxis)
        assertEquals(0, r.axes[1].itemAxis)
        // 3-D: 6 permutations considered.
        val r3 = FitVerdict.evaluate(
            listOf(m(0.3, 0.005), m(0.2, 0.005), m(0.1, 0.005)),
            listOf(m(0.15, 0.005), m(0.25, 0.005), m(0.35, 0.005)),
            FitPolicy(allowRotation = true),
        )
        assertEquals(Fit.FITS, r3.fit)
        assertEquals(listOf(2, 1, 0), r3.axes.map { it.itemAxis })
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedDims() { FitVerdict.evaluate(listOf(m(1.0, 0.0)), listOf(m(1.0, 0.0), m(1.0, 0.0))) }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyDims() { FitVerdict.evaluate(List(4) { m(1.0, 0.0) }, List(4) { m(1.0, 0.0) }) }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDims() { FitVerdict.evaluate(m(-1.0, 0.0), m(1.0, 0.0)) }

    @Test(expected = IllegalArgumentException::class)
    fun badPolicy() { FitPolicy(z = -1.0) }
}
