package com.spaceview.app.measure

import com.spaceview.ar.AimModel
import com.spaceview.ar.EndpointData
import com.spaceview.ar.HitKind
import com.spaceview.ar.ImageIntrinsics
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasurementComposer
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Known-length calibration (the v1 replacement for card / A4): the user measures a straight span
 * they checked with a tape and types its length. Endpoints are built as MeasureSession's worker
 * builds them (VGA-class camera, 2 px aim error, 0.3 mm jitter) in an AR world whose scale is wrong.
 */
class KnownLengthCalibrationTest {

    private val camera = ImageIntrinsics(500.0, 640, 480)
    private val tier = UncertaintyModel(DeviceTier.NO_DEPTH)

    /** Wall z = −[wallDistance] in the true world, seen from a camera at (0, 1.2, 0). */
    private inner class ScaledWorld(val scale: Double, val wallDistance: Double, val rng: Random) {
        private val cam = Vec3(0.0, 1.2, 0.0) * scale

        /** The AR endpoint for true wall point (x, y): scaled, plus an in-plane aim error. */
        fun endpoint(x: Double, y: Double, normal: Vec3 = Vec3.UNIT_Z): EndpointData {
            val ar = Vec3(x, y, -wallDistance) * scale
            val range = cam.distanceTo(ar)
            val loc = AimModel.aim(range, camera, 3.75)!!
            val p = ar + Vec3(rng.nextGaussian(), rng.nextGaussian(), 0.0) * loc.sigmaMeters
            return EndpointData(
                UncertainPoint.of(p, 0.0003).withLocalisation(loc), normal, HitKind.PLANE, range,
                focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx,
            )
        }
    }

    private fun typed(text: String, unit: LengthUnit = LengthUnit.CM) =
        MeasureViewModel.parseKnownLength(text, unit) as KnownLengthEntry.Valid

    @Test
    fun twoMetreSpanWithThreePercentScaleErrorIsAcceptedAndCorrected() {
        val trials = 300
        var accepted = 0
        var covered = 0
        var sumErrBefore = 0.0
        var sumErrAfter = 0.0
        val entry = typed("200.0")
        repeat(trials) { t ->
            val w = ScaledWorld(1.03, 1.2, Random(1000L + t))
            val ends = listOf(w.endpoint(-1.0, 1.2), w.endpoint(1.0, 1.2))
            val outcome = MeasureViewModel.evaluateCalibration(null, MeasurementComposer.knownLengthCheck(entry.metres, entry.sigmaMetres, ends, tier))
            if (outcome !is CalibrationOutcome.Accepted) return@repeat
            accepted++
            assertNull(outcome.reference)
            val cal = outcome.calibration
            assertTrue(cal.plane != null && cal.rangeM != null && cal.anchor != null) // scoped like every calibration
            // A different 1.5 m span on the same wall, measured with the calibrated model.
            val span = listOf(w.endpoint(-0.75, 1.0), w.endpoint(0.75, 1.0))
            val before = MeasurementComposer.compose(MeasureMode.DISTANCE, span, null, tier)!!
            val after = MeasurementComposer.compose(MeasureMode.DISTANCE, span, null, tier.withCalibration(cal))!!
            assertTrue(after.calibrated)
            assertFalse(before.calibrated)
            sumErrBefore += abs(before.primary.value - 1.5)
            sumErrAfter += abs(after.primary.value - 1.5)
            if (abs(after.primary.value - 1.5) <= after.primary.bound95) covered++
        }
        assertTrue("accepted $accepted / $trials", accepted >= trials * 0.95)
        // The 4.5 cm scale error is removed (mean error now ~1 cm of aim noise and factor noise) …
        assertTrue("before ${sumErrBefore / accepted}", sumErrBefore / accepted > 0.04)
        assertTrue("after ${sumErrAfter / accepted}", sumErrAfter / accepted < 0.015)
        // … and the calibrated ± stays honest.
        assertTrue("coverage ${covered.toDouble() / accepted}", covered >= 0.95 * accepted)
    }

    @Test
    fun oneMetreSpanIsJudgedHonestly() {
        // 1 m from 0.6–1.5 m away: the floor (σ 5.1 mm) and aim error make ≈0.6–0.9% per span, which is
        // not clearly better than the 1% prior. Whatever the verdict, an accepted factor must be right.
        val entry = typed("100.0")
        for (d in listOf(0.6, 0.9, 1.2, 1.5)) repeat(25) { t ->
            val w = ScaledWorld(0.98, d, Random(7L * t + (d * 100).toLong()))
            val check = MeasurementComposer.knownLengthCheck(entry.metres, entry.sigmaMetres, listOf(w.endpoint(-0.5, 1.2), w.endpoint(0.5, 1.2)), tier)
            when (val o = MeasureViewModel.evaluateCalibration(null, check)) {
                is CalibrationOutcome.Accepted -> {
                    val c = o.calibration
                    assertTrue("d=$d k=${c.factor}", abs(c.factor - 1 / 0.98) <= 3 * c.sigma + 1e-9)
                }
                is CalibrationOutcome.Rejected -> assertEquals(CalibrationRejection.TOO_NOISY, o.reason)
            }
        }
        // The typical case (1.2 m away) is too uncertain on a non-LiDAR phone.
        val w = ScaledWorld(1.0, 1.2, Random(1))
        val typical = MeasurementComposer.knownLengthCheck(entry.metres, entry.sigmaMetres, listOf(w.endpoint(-0.5, 1.2), w.endpoint(0.5, 1.2)), tier)
        assertEquals(CalibrationOutcome.Rejected(CalibrationRejection.TOO_NOISY), MeasureViewModel.evaluateCalibration(null, typical))
    }

    @Test
    fun endsOnTwoSurfacesAreRefused() {
        // A 1.2 m opening measured jamb to jamb: normals face each other, no single surface to scope to.
        val w = ScaledWorld(1.0, 1.2, Random(2))
        val ends = listOf(w.endpoint(-0.6, 1.0, Vec3.UNIT_X), w.endpoint(0.6, 1.0, -Vec3.UNIT_X))
        val e = typed("120.0")
        assertEquals(
            CalibrationOutcome.Rejected(CalibrationRejection.NOT_ONE_SURFACE),
            MeasureViewModel.evaluateCalibration(null, MeasurementComposer.knownLengthCheck(e.metres, e.sigmaMetres, ends, tier)),
        )
        assertNull(MeasurementComposer.knownLengthCheck(2.0, 0.001, ends.take(1), tier))
    }

    @Test
    fun typedLengthParsing() {
        assertEquals(LengthUnit.CM, MeasureViewModel.knownLengthUnit(UnitPreference.AUTO))
        assertEquals(LengthUnit.CM, MeasureViewModel.knownLengthUnit(UnitPreference.METRIC))
        assertEquals(LengthUnit.INCH_DECIMAL, MeasureViewModel.knownLengthUnit(UnitPreference.IMPERIAL))
        val whole = typed("200")
        assertEquals(2.0, whole.metres, 1e-12)
        // "200" cm could be 199.5–200.5: the typed resolution is part of the σ.
        assertEquals(sqrt(0.001 * 0.001 + 0.01 * 0.01 / 12), whole.sigmaMetres, 1e-12)
        assertTrue(typed("200.4").sigmaMetres < whole.sigmaMetres)
        assertEquals(2.004, typed(" 200,4 ").metres, 1e-12)
        val inches = typed("78.74", LengthUnit.INCH_DECIMAL)
        assertEquals(78.74 * 0.0254, inches.metres, 1e-12)
        assertEquals(1.0, typed("100").metres, 1e-12)
        assertEquals(5.0, typed("500").metres, 1e-12)
        for (bad in listOf("99.9", "500.1", "30")) assertEquals(KnownLengthEntry.OutOfRange, MeasureViewModel.parseKnownLength(bad, LengthUnit.CM))
        assertEquals(KnownLengthEntry.OutOfRange, MeasureViewModel.parseKnownLength("39.3", LengthUnit.INCH_DECIMAL))
        for (bad in listOf("", " ", "abc", "1.2.3", "-200", "2e2", ".", "200 cm")) {
            assertEquals(bad, KnownLengthEntry.Invalid, MeasureViewModel.parseKnownLength(bad, LengthUnit.CM))
        }
    }

    @Test
    fun smallReferencesAreHiddenInV1() {
        assertFalse(MeasureViewModel.CALIBRATION_SMALL_REFERENCES_ENABLED)
    }
}
