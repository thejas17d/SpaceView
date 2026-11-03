package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Regression tests for docs/reviews/M2-review.md, engine side (B1 floor, M2 calibration scope). */
class M2ReviewEngineTest {

    private val card = 0.0856

    // ---- B1: the tier floor is always in the ± ------------------------------------------------------

    @Test fun floorIsAlwaysAddedEvenForLocalisedEndpoints() {
        for (tier in DeviceTier.entries) {
            val model = UncertaintyModel(tier)
            // Absurdly precise localisation (1e6 px focal) and zero jitter: only the floor is left.
            val l = LocalisationInput(0.3, 1e6, 0.5)
            val d = model.distance(UncertainPoint(Vec3.ZERO), UncertainPoint(Vec3(card, 0.0, 0.0)), l, l)
            assertTrue("$tier: ${d.bound95}", d.bound95 >= tier.absFloor95 * (1 - 1e-12))
            // Localisation widens the ±, it never replaces the floor.
            val coarse = LocalisationInput(0.3, 500.0, 2.0)
            val wider = model.distance(UncertainPoint(Vec3.ZERO), UncertainPoint(Vec3(card, 0.0, 0.0)), coarse, coarse)
            assertTrue(wider.sigma > d.sigma)
            val scale = tier.scaleSigmaRel * card
            assertEquals(
                sqrt(2 * coarse.sigmaMeters * coarse.sigmaMeters + tier.floorLengthSigma * tier.floorLengthSigma + scale * scale),
                wider.sigma, 1e-12,
            )
        }
    }

    /**
     * The review's probe: a bank card at 30 cm showed "86 mm ± 2" (GOOD) with a view-pixel focal.
     * With any focal length, on NO_DEPTH, it can never show better than about ±1 cm.
     */
    @Test fun bankCardAt30cmNeverBetterThanAboutOneCentimetre() {
        val model = UncertaintyModel(DeviceTier.NO_DEPTH)
        for (focal in listOf(500.0, 2000.0, 2700.0, 1e5)) {
            val l = LocalisationInput(0.3, focal, 2.0)
            // 0.3 mm jitter standard error per endpoint, as the M2 probe used.
            val a = UncertainPoint.of(Vec3(0.0, 0.0, -0.3), 0.0003)
            val b = UncertainPoint.of(Vec3(card, 0.0, -0.3), 0.0003)
            val m = model.distance(a, b, l, l)
            assertTrue("f=$focal ±${m.bound95}", m.bound95 >= 0.0099)
            val shown = Units.formatForContext(m, MeasureContext.SMALL_OBJECT).primary
            assertEquals(LengthUnit.CM, shown.unit) // never the mm display reserved for ± ≤ 2 mm
            assertTrue("f=$focal ${shown.text}", shown.plusMinus!!.toDouble() >= 1.0)
            assertEquals(Quality.ROUGH, m.quality) // ±1 cm on 8.6 cm is not GOOD
        }
    }

    @Test fun floorOverrideAndUnmeasuredLengths() {
        val m = UncertaintyModel(DeviceTier.NO_DEPTH, absFloor95 = 0.004)
        assertEquals(0.004 / Uncertainty.K95, m.floorLengthSigma, 1e-15)
        assertEquals(0.004 / (Uncertainty.K95 * sqrt(2.0)), m.unlocalisedEndpointSigma, 1e-15)
        assertEquals(DeviceTier.NO_DEPTH.absFloor95, UncertaintyModel(DeviceTier.NO_DEPTH).absFloor95, 0.0)
        assertEquals(DeviceTier.NO_DEPTH.floorLengthSigma, UncertaintyModel().floorLengthSigma, 0.0)
        // Typed lengths carry only their own σ.
        assertEquals(0.002 * 0.002, m.randomVariance(RawLength(1.0, 0.002, isMeasured = false)), 1e-18)
        try {
            UncertaintyModel(absFloor95 = -1.0)
            throw AssertionError("negative floor accepted")
        } catch (_: IllegalArgumentException) {}
    }

    // ---- M2: calibration only when every point is on the calibrated plane -------------------------

    /** Table top at y = 0.75; calibration made on it, 0.5 m away. */
    private val table = Plane.fromPointNormal(Vec3(0.0, 0.75, 0.0), Vec3.UP)
    private val cal = ScaleCalibration(1.03, 0.001, plane = table, anchor = Vec3(0.0, 0.75, -0.5), rangeM = 0.5)
    private val calibrated = UncertaintyModel(DeviceTier.NO_DEPTH).withCalibration(cal)

    @Test fun floorToTableHeightIsNotCalibrated() {
        val floorPt = Vec3(0.0, 0.0, -0.6)
        val tablePt = Vec3(0.0, 0.75, -0.6)
        assertFalse(cal.appliesToPoints(listOf(floorPt, tablePt), listOf(Vec3.UP, Vec3.UP), 0.6))
        assertNull(calibrated.forPoints(listOf(floorPt, tablePt), listOf(Vec3.UP, Vec3.UP), 0.6).calibration)
        // The old path treated "different surfaces" as "plane unknown" and applied the factor.
        assertNotNull(calibrated.forScope(null, 0.6).calibration)
    }

    @Test fun floorToWallDistanceIsNotCalibrated() {
        val floorPt = Vec3(0.2, 0.0, -1.0)
        val wallPt = Vec3(0.2, 1.0, -1.5)
        assertNull(calibrated.forPoints(listOf(floorPt, wallPt), listOf(Vec3.UP, Vec3.UNIT_Z), 0.6).calibration)
    }

    @Test fun pointsOnTheCalibratedPlaneAreCalibrated() {
        val a = Vec3(-0.2, 0.752, -0.5)
        val b = Vec3(0.3, 0.749, -0.6)
        assertSame(calibrated, calibrated.forPoints(listOf(a, b), listOf(Vec3.UP, null), 0.55))
        // Normals unknown: position alone decides.
        assertTrue(cal.appliesToPoints(listOf(a, b)))
        // A tilted normal (a feature on a box side) or a point 6 cm above the table excludes it.
        assertFalse(cal.appliesToPoints(listOf(a, b), listOf(Vec3.UP, Vec3.UNIT_X), 0.55))
        assertFalse(cal.appliesToPoints(listOf(a, b + Vec3(0.0, 0.06, 0.0)), null, 0.55))
        // Range scope still applies.
        assertFalse(cal.appliesToPoints(listOf(a, b), null, 1.2))
        // Degenerate input.
        assertFalse(cal.appliesToPoints(emptyList()))
        assertFalse(cal.appliesToPoints(listOf(a), listOf(Vec3.ZERO)))
        // Without a stored plane only the range scope is checked.
        assertTrue(ScaleCalibration(1.0, 0.001, rangeM = 0.5).appliesToPoints(listOf(Vec3.ZERO, Vec3.UNIT_X), null, 0.6))
        // Uncalibrated models are returned unchanged.
        val plain = UncertaintyModel()
        assertSame(plain, plain.forPoints(listOf(a, b)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneNormalPerPoint() {
        cal.appliesToPoints(listOf(Vec3.ZERO, Vec3.UNIT_X), listOf(Vec3.UP))
    }
}
