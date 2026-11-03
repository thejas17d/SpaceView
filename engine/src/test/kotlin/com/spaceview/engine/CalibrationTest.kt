package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class CalibrationTest {

    private fun accepted(r: CalibrationResult): ScaleCalibration {
        assertTrue("expected Accepted but was $r ${(r as? CalibrationResult.Rejected)?.reason}", r is CalibrationResult.Accepted)
        return (r as CalibrationResult.Accepted).calibration
    }

    private fun rejected(r: CalibrationResult): CalibrationResult.Reason {
        assertTrue("expected Rejected", r is CalibrationResult.Rejected)
        return (r as CalibrationResult.Rejected).reason
    }

    @Test fun referenceDimensions() {
        assertEquals(0.0856, ReferenceObject.ID1_CARD.longSide, 0.0)
        assertEquals(0.05398, ReferenceObject.ID1_CARD.shortSide, 0.0)
        assertEquals(0.297, ReferenceObject.A4.longSide, 0.0)
        assertEquals(0.210, ReferenceObject.A4.shortSide, 0.0)
    }

    @Test fun exactMeasurementGivesUnitFactor() {
        val c = accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297, 0.001), RawLength(0.210, 0.001)))
        assertEquals(1.0, c.factor, 1e-12)
        assertEquals(0.0, c.residual, 1e-12)
        assertSame(ReferenceObject.A4, c.reference)
        assertEquals(0.297, c.apply(0.297), 1e-12)
        assertTrue(c.toString().contains("ScaleCalibration"))
    }

    @Test fun sideOrderDoesNotMatter() {
        val a = accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.306, 0.001), RawLength(0.2163, 0.001)))
        val b = accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.2163, 0.001), RawLength(0.306, 0.001)))
        assertEquals(a.factor, b.factor, 0.0)
    }

    /** Injected +3% VIO scale error is measured on an A4 sheet, then removed from a 1.2 m object. */
    @Test fun calibrationFixesInjectedThreePercentScaleError() {
        val rng = Random(303)
        val scale = 1.03
        // 0.5 mm per corner: A4 at ~50 cm on a 1080p stream (1 px ≈ 0.33 mm) after averaging + edge snap.
        val noise = 0.0005
        // The wall as AR sees it (also scaled).
        val wall = Plane.fromPointNormal(Vec3(0.0, 0.0, -1.0 * scale), Vec3.UNIT_Z)
        // Localisation: 1.5 px at 0.5 m on a 1500 px focal length = 0.5 mm, matching the injected noise.
        val local = LocalisationInput(0.5, 1500.0, 1.5)
        fun corner(p: Vec3) = UncertainPoint(p * scale + Vec3(rng.nextGaussian(), rng.nextGaussian(), 0.0) * noise)
        // A4 in landscape on the wall.
        val a4 = listOf(Vec3(0.0, 1.0, -1.0), Vec3(0.297, 1.0, -1.0), Vec3(0.297, 0.79, -1.0), Vec3(0.0, 0.79, -1.0))
        val rect = Shapes.rectangle(a4.map { corner(it) }, wall, localisation = List(4) { local })
        val cal = accepted(Calibrator.fromReference(ReferenceObject.A4, rect))
        assertEquals(1 / scale, cal.factor, 0.006) // ~3σ
        assertTrue(cal.relSigma < 0.005)

        val uncal = UncertaintyModel(DeviceTier.NO_DEPTH)
        val calibrated = uncal.withCalibration(cal)
        val a = UncertainPoint(Vec3(-0.5, 1.2, -1.0) * scale)
        val b = UncertainPoint(Vec3(0.7, 1.2, -1.0) * scale)
        val before = uncal.distance(a, b, local, local)
        val after = calibrated.distance(a, b, local, local)
        assertEquals(1.2 * 1.03, before.value, 1e-9) // 3.6 cm too long
        assertTrue("after=${after.value}", abs(after.value - 1.2) < 0.008) // was 36 mm off, now < 8 mm
        assertTrue(abs(after.value - 1.2) <= after.bound95)
        assertTrue(after.sigma < before.sigma) // calibrated ± is tighter than the 2% prior
    }

    @Test fun cardCalibrationAtCloseRange() {
        // Card measured at ~30 cm with 0.3 mm corner precision: factor accepted.
        val c = accepted(Calibrator.fromReference(ReferenceObject.ID1_CARD, RawLength(0.0874, 0.0003), RawLength(0.0551, 0.0003)))
        assertEquals(0.0856 / 0.0874, c.factor, 0.002)
        // The same card measured far away (3 mm error) is too noisy to help.
        assertEquals(
            CalibrationResult.Reason.TOO_NOISY,
            rejected(Calibrator.fromReference(ReferenceObject.ID1_CARD, RawLength(0.0874, 0.003), RawLength(0.0551, 0.003))),
        )
    }

    @Test fun rejectsAbsurdFactors() {
        val low = Calibrator.fromReference(ReferenceObject.A4, RawLength(0.36, 0.001), RawLength(0.255, 0.001))
        assertEquals(CalibrationResult.Reason.FACTOR_OUT_OF_RANGE, rejected(low))
        assertEquals(0.297 / 0.36, (low as CalibrationResult.Rejected).factor, 0.01)
        val high = Calibrator.fromReference(ReferenceObject.A4, RawLength(0.25, 0.001), RawLength(0.177, 0.001))
        assertEquals(CalibrationResult.Reason.FACTOR_OUT_OF_RANGE, rejected(high))
        // Just inside the 0.9-1.1 band is accepted (research 2.9) ...
        accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297 / 1.09, 0.0005), RawLength(0.210 / 1.09, 0.0005)))
        accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297 / 0.91, 0.0005), RawLength(0.210 / 0.91, 0.0005)))
        // ... but 1.12 (accepted by the old 0.85-1.15 band) means broken tracking (MINOR-5).
        val k112 = Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297 / 1.12, 0.0005), RawLength(0.210 / 1.12, 0.0005))
        assertEquals(CalibrationResult.Reason.FACTOR_OUT_OF_RANGE, rejected(k112))
        assertEquals(1.12, (k112 as CalibrationResult.Rejected).factor, 1e-9)
        // The band edges themselves are excluded.
        assertEquals(
            CalibrationResult.Reason.FACTOR_OUT_OF_RANGE,
            rejected(Calibrator.calibrate(listOf(KnownLength(1.1, 0.0, RawLength(1.0, 0.0005))))),
        )
    }

    @Test fun rejectsInconsistentSides() {
        // Long side says k = 1.00, short side says k = 0.93 with 0.5 mm precision: not the same object/scale.
        val r = Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297, 0.0005), RawLength(0.2258, 0.0005))
        assertEquals(CalibrationResult.Reason.INCONSISTENT, rejected(r))
    }

    @Test fun residualInflatesSigma() {
        // Sides disagree by more than their stated errors but not absurdly: accepted with inflated σ.
        val tight = accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297, 0.001), RawLength(0.210, 0.001)))
        val spread = accepted(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.297, 0.001), RawLength(0.2125, 0.001)))
        assertTrue(spread.sigma > tight.sigma)
        assertTrue(spread.residual > 0.004)
    }

    @Test fun invalidInputs() {
        assertEquals(CalibrationResult.Reason.INVALID_INPUT, rejected(Calibrator.calibrate(emptyList())))
        assertEquals(
            CalibrationResult.Reason.INVALID_INPUT,
            rejected(Calibrator.fromReference(ReferenceObject.A4, RawLength(0.0, 0.001), RawLength(0.21, 0.001))),
        )
        assertEquals(
            CalibrationResult.Reason.INVALID_INPUT,
            rejected(Calibrator.calibrate(listOf(KnownLength(1.0, 0.0, RawLength(1.0, 0.0, isMeasured = false))))),
        )
    }

    @Test fun singleKnownLength() {
        // A door width typed from a tape (±1 mm) as the reference.
        val c = accepted(Calibrator.calibrate(listOf(KnownLength(0.900, 0.001, RawLength(0.918, 0.002)))))
        assertEquals(0.900 / 0.918, c.factor, 1e-12)
        // Zero stated errors still produce a finite calibration.
        val z = accepted(Calibrator.calibrate(listOf(KnownLength(1.0, 0.0, RawLength(1.0, 0.0)))))
        assertTrue(z.sigma > 0 && z.sigma.isFinite())
    }
}
