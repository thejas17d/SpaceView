package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-002 / decision D6 (R1): placement combines the item's and the space's ± linearly. */
class FitVerdictLinearTest {
    /** A measured value from its stored 95% bound, as placement does (σ = bound95 / 1.96). */
    private fun m(v: Double, pm95: Double) = Measurement(v, pm95 / Uncertainty.K95)

    private val linear = FitPolicy(combination = UncertaintyCombination.LINEAR)
    private val rss = FitPolicy(combination = UncertaintyCombination.RSS)

    @Test fun r1SofaRegression() {
        // §1.2: sofa 200 ± 1.5 cm, gap 203 ± 2 cm, 3 cm clearance.
        val sofa = m(2.00, 0.015)
        val gap = m(2.03, 0.020)
        assertEquals("RSS band 2.5 cm < 3 cm", Fit.FITS, FitVerdict.evaluate(sofa, gap, rss).fit)
        assertEquals("the default is linear", Fit.TIGHT, FitVerdict.evaluate(sofa, gap).fit)
        val r = FitVerdict.evaluate(sofa, gap, linear)
        assertEquals("linear band 3.5 cm > 3 cm", Fit.TIGHT, r.fit)
        assertEquals(0.035, r.clearance.bound95, 1e-12)
        assertEquals(0.03, r.clearance.value, 1e-12)
    }

    @Test fun defaultIsLinear() {
        assertEquals(UncertaintyCombination.LINEAR, FitPolicy().combination)
        assertEquals(UncertaintyCombination.LINEAR, FitPolicy(allowRotation = true, minClearance = 0.01).combination)
    }

    @Test fun allThreeBoundariesAtPointOneMillimetre() {
        val bi = 0.012; val bs = 0.018; val band = bi + bs
        val eps = 1e-4
        fun fit(clearance: Double) = FitVerdict.evaluate(m(1.0, bi), m(1.0 + clearance, bs), linear).fit
        assertEquals(Fit.FITS, fit(band + eps))
        assertEquals(Fit.TIGHT, fit(band - eps))
        assertEquals(Fit.TIGHT, fit(0.0))
        assertEquals(Fit.TIGHT, fit(-band + eps))
        assertEquals(Fit.TOO_BIG, fit(-band - eps))
    }

    @Test fun typedSideIsExact() {
        // A typed item width has σ = 0: only the space's ± counts.
        val typed = Measurement.exact(0.80)
        val space = m(0.83, 0.02)
        assertEquals(Fit.FITS, FitVerdict.evaluate(typed, space, linear).fit) // 3 cm spare > 2 cm
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(0.80, 0.015), space, linear).fit) // measured: 3.5 cm band
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(typed, m(0.77, 0.02), linear).fit)
    }

    @Test fun framePortraitOrLandscape() {
        // A 0.9 × 0.6 frame into a 0.65 wide × 1.0 tall wall space: only portrait fits.
        val frame = listOf(m(0.90, 0.005), m(0.60, 0.005))
        val wall = listOf(m(0.65, 0.01), m(1.00, 0.01))
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(frame, wall, linear).fit)
        val r = FitVerdict.evaluate(frame, wall, FitPolicy(rotation = FitRotation.ANY, combination = UncertaintyCombination.LINEAR))
        assertEquals(Fit.FITS, r.fit)
        assertTrue(r.rotated)
        assertEquals(listOf(1, 0), r.axes.map { it.itemAxis })
    }

    @Test fun floorItemTurnsAboutVertical() {
        // W × H × D = 1.2 × 0.8 × 0.5 into a niche 0.6 wide × 1.0 tall × 1.3 deep.
        val item = listOf(m(1.2, 0.01), m(0.8, 0.01), m(0.5, 0.01))
        val niche = listOf(m(0.6, 0.01), m(1.0, 0.01), m(1.3, 0.01))
        val p = FitPolicy(rotation = FitRotation.ABOUT_VERTICAL, combination = UncertaintyCombination.LINEAR)
        val r = FitVerdict.evaluate(item, niche, p)
        assertEquals(Fit.FITS, r.fit)
        assertEquals(listOf(2, 1, 0), r.axes.map { it.itemAxis })
        assertFalse(FitVerdict.evaluate(item, niche, linear).fit == Fit.FITS)
    }

    @Test fun minClearanceWithLinearBand() {
        val p = FitPolicy(minClearance = 0.05, combination = UncertaintyCombination.LINEAR)
        // Band 2 cm: FITS needs more than 7 cm spare, TOO_BIG less than 3 cm.
        assertEquals(Fit.FITS, FitVerdict.evaluate(m(1.0, 0.01), m(1.0701, 0.01), p).fit)
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(1.0, 0.01), m(1.0699, 0.01), p).fit)
        assertEquals(Fit.TIGHT, FitVerdict.evaluate(m(1.0, 0.01), m(1.0301, 0.01), p).fit)
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(m(1.0, 0.01), m(1.0299, 0.01), p).fit)
    }

    @Test fun linearIsNeverNarrowerThanRss() {
        val rnd = java.util.Random(7)
        repeat(500) {
            val item = m(0.5 + rnd.nextDouble(), rnd.nextDouble() * 0.05)
            val space = m(0.5 + rnd.nextDouble(), rnd.nextDouble() * 0.05)
            val l = FitVerdict.evaluate(item, space, linear)
            val r = FitVerdict.evaluate(item, space, rss)
            assertTrue(l.clearance.sigma >= r.clearance.sigma - 1e-15)
            // Linear only ever turns FITS / TOO_BIG into TIGHT, never the other way round.
            if (l.fit != Fit.TIGHT) assertEquals(r.fit, l.fit)
        }
    }

    // ---- M4 review MAJOR 1: the quoted axis shares the overall verdict ----------------------------------

    @Test fun tooBigQuotesTheAxisThatIsTooBigNotAWideTightOne() {
        // TV 120 × 70 cm ± 0.5; wall space 115 ± 1 wide, 70 ± 8 tall (a long edge measured from far away).
        val tv = listOf(m(1.20, 0.005), m(0.70, 0.005))
        val wall = listOf(m(1.15, 0.01), m(0.70, 0.08))
        val r = FitVerdict.evaluate(tv, wall)
        assertEquals(Fit.TOO_BIG, r.fit)
        assertEquals(listOf(Fit.TOO_BIG, Fit.TIGHT), r.axes.map { it.fit })
        // The TIGHT height has the lower c − band (−8.5 cm vs −6.5 cm) but is not what the card may quote.
        assertEquals(0, r.limitingAxis.axis)
        assertEquals(-0.05, r.clearance.value, 1e-12)
    }

    @Test fun tooBigQuotesTheLargestOvershoot() {
        val item = listOf(m(1.20, 0.005), m(0.90, 0.005))
        val space = listOf(m(1.15, 0.005), m(0.80, 0.005))
        val r = FitVerdict.evaluate(item, space)
        assertEquals(Fit.TOO_BIG, r.fit)
        assertEquals(1, r.limitingAxis.axis) // 10 cm over beats 5 cm over
    }

    @Test fun fitsQuotesTheSmallestSpareEvenWhenAnotherAxisHasTheLowerMargin() {
        // W: 3 cm spare, band 0.5 cm (margin 2.5). H: 8 cm spare, band 6 cm (margin 2): both FITS.
        val item = listOf(m(1.00, 0.0025), m(0.50, 0.03))
        val space = listOf(m(1.03, 0.0025), m(0.58, 0.03))
        val r = FitVerdict.evaluate(item, space)
        assertEquals(Fit.FITS, r.fit)
        assertEquals(listOf(Fit.FITS, Fit.FITS), r.axes.map { it.fit })
        assertEquals(0, r.limitingAxis.axis)
        assertEquals(0.03, r.clearance.value, 1e-12)
    }

    @Test fun tightQuotesTheTightAxisNotAFittingOne() {
        val item = listOf(m(1.00, 0.005), m(0.50, 0.005))
        val space = listOf(m(1.01, 0.01), m(0.60, 0.005))
        val r = FitVerdict.evaluate(item, space)
        assertEquals(Fit.TIGHT, r.fit)
        assertEquals(0, r.limitingAxis.axis)
    }
}
