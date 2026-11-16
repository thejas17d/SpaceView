package com.spaceview.app.place

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.Fit
import com.spaceview.engine.UnitPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.4 step 5 / §3.9: each verdict × {measured, typed}, rotation rules, and the copy's rounding. */
class PlaceVerdictTest {
    private fun gap(m: Double, pm95: Double) = SpaceReading(SpaceKind.GAP, m, pm95 / 1.96)
    private fun area(a: Double, b: Double, pm95: Double, firstHorizontal: Boolean = true) =
        SpaceReading(SpaceKind.AREA, a, pm95 / 1.96, b, pm95 / 1.96, firstHorizontal)

    private val sofaW = Dimension.measured(2.00, 0.015)
    private val sofaH = Dimension.measured(0.85, 0.01)
    private val sofaD = Dimension.measured(0.90, 0.01)

    private fun sofa(space: SpaceReading) = PlaceVerdict.evaluate(ObjectKind.BOX, sofaW, sofaH, sofaD, onWall = false, space)!!

    @Test fun threeVerdictsWithMeasuredSides() {
        // Linear band: 1.5 + 2 = 3.5 cm.
        assertEquals(Fit.TIGHT, sofa(gap(2.03, 0.02)).fit) // the R1 example
        val fits = sofa(gap(2.0351, 0.02))
        assertEquals(Fit.FITS, fits.fit)
        assertEquals(0.035, fits.bandM, 1e-9)
        assertEquals(FitAxis.WIDTH, fits.limitingAxis)
        assertTrue(fits.heightNotChecked)
        assertTrue(fits.widthOnly)
        assertFalse(fits.usedTypedSize)
        assertEquals(Fit.TOO_BIG, sofa(gap(1.9649, 0.02)).fit)
        assertEquals(Fit.TIGHT, sofa(gap(1.9651, 0.02)).fit)
    }

    @Test fun threeVerdictsWithTypedSides() {
        val typed = Dimension.typed(2.0)
        fun v(space: SpaceReading) = PlaceVerdict.evaluate(ObjectKind.BOX, typed, sofaH, sofaD, false, space)!!
        // Only the space's ± counts (2 cm).
        assertEquals(Fit.FITS, v(gap(2.021, 0.02)).fit)
        assertEquals(Fit.TIGHT, v(gap(2.019, 0.02)).fit)
        assertEquals(Fit.TOO_BIG, v(gap(1.979, 0.02)).fit)
        assertTrue(v(gap(2.021, 0.02)).usedTypedSize)
        // A preset depth counts as not measured when it is compared.
        val r = PlaceVerdict.evaluate(ObjectKind.FRAME, Dimension.measured(0.6, 0.01), Dimension.measured(0.4, 0.01), Dimension.preset(0.03), true, area(0.7, 0.5, 0.01))!!
        assertFalse("depth is not compared on a wall", r.usedTypedSize)
        assertFalse("a wall rectangle checks the height", r.heightNotChecked)
    }

    @Test fun framesTurnTvsDoNot() {
        val w = Dimension.measured(0.9, 0.005); val h = Dimension.measured(0.6, 0.005); val d = Dimension.preset(0.03)
        // Wall space 0.65 wide × 1.0 tall.
        val frame = PlaceVerdict.evaluate(ObjectKind.FRAME, w, h, d, true, area(0.65, 1.0, 0.01))!!
        assertEquals(Fit.FITS, frame.fit)
        assertTrue(frame.rotated)
        val tv = PlaceVerdict.evaluate(ObjectKind.TV, w, h, d, true, area(0.65, 1.0, 0.01))!!
        assertEquals(Fit.TOO_BIG, tv.fit)
        assertEquals(FitAxis.WIDTH, tv.limitingAxis)
        // The rectangle was measured starting with its vertical side: reordered before comparing.
        val tv2 = PlaceVerdict.evaluate(ObjectKind.TV, w, h, d, true, area(1.0, 0.65, 0.01, firstHorizontal = false))!!
        assertEquals(Fit.TOO_BIG, tv2.fit)
        // First side vertical: 1.0 is the width, 0.7 the height, so the TV fits upright.
        val tvFits = PlaceVerdict.evaluate(ObjectKind.TV, w, h, d, true, area(0.7, 1.0, 0.01, firstHorizontal = false))!!
        assertEquals(Fit.FITS, tvFits.fit)
        assertFalse(tvFits.rotated)
    }

    @Test fun floorItemsSwapWidthAndDepth() {
        // A floor rectangle 0.95 × 2.1: the sofa (2.0 × 0.9 footprint) fits only turned.
        val r = sofa(area(0.95, 2.1, 0.01))
        assertEquals(Fit.FITS, r.fit)
        assertTrue(r.rotated)
        // M4 review MINOR 3: a floor rectangle compares W × D only, and says the height was not checked.
        assertTrue(r.heightNotChecked)
        assertFalse(r.widthOnly)
        val depthLimited = sofa(area(2.1, 0.905, 0.01))
        assertEquals(Fit.TIGHT, depthLimited.fit)
        assertEquals(FitAxis.DEPTH, depthLimited.limitingAxis)
        assertTrue(PlaceVerdict.evaluate(ObjectKind.FURNITURE, sofaW, sofaH, sofaD, false, area(2.1, 1.0, 0.01))!!.boundingBox)
    }

    @Test fun invalidSpaceGivesNoVerdict() {
        assertNull(PlaceVerdict.evaluate(ObjectKind.BOX, sofaW, sofaH, sofaD, false, SpaceReading(SpaceKind.GAP, 0.0, 0.01)))
        assertNull(PlaceVerdict.evaluate(ObjectKind.BOX, sofaW, sofaH, sofaD, false, SpaceReading(SpaceKind.AREA, 1.0, 0.01)))
        assertNull(PlaceVerdict.evaluate(ObjectKind.BOX, sofaW, sofaH, sofaD, false, SpaceReading(SpaceKind.GAP, Double.NaN, 0.01)))
    }

    @Test fun amountsRoundTheCautiousWay() {
        assertEquals(PlaceVerdict.Amount(6, false), PlaceVerdict.amount(0.0699, UnitPreference.METRIC, roundUp = false))
        assertEquals(PlaceVerdict.Amount(4, false), PlaceVerdict.amount(-0.0301, UnitPreference.AUTO, roundUp = true))
        assertEquals(PlaceVerdict.Amount(5, false), PlaceVerdict.amount(0.05, UnitPreference.METRIC, roundUp = true))
        assertEquals(PlaceVerdict.Amount(3, true), PlaceVerdict.amount(0.0762, UnitPreference.IMPERIAL, roundUp = false))
        assertEquals(PlaceVerdict.Amount(2, true), PlaceVerdict.amount(0.0761, UnitPreference.IMPERIAL, roundUp = false))
        assertEquals(PlaceVerdict.Amount(3, true), PlaceVerdict.amount(0.0510, UnitPreference.IMPERIAL, roundUp = true))
    }

    @Test fun mixedAxesQuoteTheAxisThatDecides() {
        // M4 review MAJOR 1 at the card level: TV 120 × 70 ± 0.5 into 115 ± 1 wide × 70 ± 8 tall.
        val tv = PlaceVerdict.evaluate(
            ObjectKind.TV, Dimension.measured(1.20, 0.005), Dimension.measured(0.70, 0.005), Dimension.preset(0.08), true,
            SpaceReading(SpaceKind.AREA, 1.15, 0.01 / 1.96, 0.70, 0.08 / 1.96),
        )!!
        assertEquals(Fit.TOO_BIG, tv.fit)
        assertEquals(FitAxis.WIDTH, tv.limitingAxis)
        assertEquals(PlaceVerdict.Amount(5, false), PlaceVerdict.amount(tv.clearanceM, UnitPreference.METRIC, roundUp = true))
        // Fits: the card quotes the smallest spare (3 cm on the width), not the 8 cm height with its wide band.
        val frame = PlaceVerdict.evaluate(
            ObjectKind.TV, Dimension.measured(1.00, 0.0025), Dimension.measured(0.50, 0.03), Dimension.preset(0.03), true,
            SpaceReading(SpaceKind.AREA, 1.03, 0.0025 / 1.96, 0.58, 0.03 / 1.96),
        )!!
        assertEquals(Fit.FITS, frame.fit)
        assertEquals(FitAxis.WIDTH, frame.limitingAxis)
        assertEquals(PlaceVerdict.Amount(3, false), PlaceVerdict.amount(frame.clearanceM, UnitPreference.METRIC, roundUp = false))
    }
}
