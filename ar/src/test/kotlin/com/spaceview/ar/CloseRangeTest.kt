package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** W10: close-range guide, range-relative tolerances, blur / focus gate and scale warm-up. */
class CloseRangeTest {
    @Test
    fun rangeGuideRefusesUnder25cmHintsUnder35cm() {
        assertEquals(RangeAdvice.TOO_CLOSE, RangeGuide.advise(0.20))
        assertFalse(RangeGuide.mayCommit(0.20))
        assertEquals(RangeAdvice.MOVE_BACK_HINT, RangeGuide.advise(0.30))
        assertTrue(RangeGuide.mayCommit(0.30))
        assertEquals(RangeAdvice.OK, RangeGuide.advise(0.50))
        assertEquals(RangeAdvice.OK, RangeGuide.advise(Double.NaN))
    }

    @Test
    fun anObjectThatDoesNotFitTheViewAsksToStepBack() {
        val inside = RangeGuide.fitsView(doubleArrayOf(100.0, 900.0, 900.0, 100.0), doubleArrayOf(200.0, 200.0, 1800.0, 1800.0), 1080, 2400)
        assertTrue(inside)
        val past = RangeGuide.fitsView(doubleArrayOf(20.0, 900.0), doubleArrayOf(200.0, 200.0), 1080, 2400)
        assertFalse(past)
        assertFalse(RangeGuide.fitsView(doubleArrayOf(Double.NaN), doubleArrayOf(1.0), 1080, 2400))
        assertEquals(RangeAdvice.STEP_BACK, RangeGuide.adviseForObject(0.5, fits = false))
        assertEquals(RangeAdvice.TOO_CLOSE, RangeGuide.adviseForObject(0.2, fits = false))
        assertEquals(RangeAdvice.OK, RangeGuide.adviseForObject(0.5, fits = true))
    }

    @Test
    fun tolerancesShrinkAtArmsLengthAndStayTheSameInTheRoom() {
        // Dwell drift: 3 mm at 0.3 m (was 1 cm), unchanged from 1 m on.
        assertEquals(0.003, max(DwellTrigger.DRIFT_M, DwellTrigger.DRIFT_REL * 0.3), 1e-12)
        assertEquals(0.02, max(DwellTrigger.DRIFT_M, DwellTrigger.DRIFT_REL * 2.0), 1e-12)
        // A 10 mm thick object on a plane wins over the plane at 0.3 m (it lost to the 3 cm rule), and still loses at 2 m.
        assertTrue(FrameReaders.nonPlaneWins(0.30, 0.29))
        assertFalse(FrameReaders.nonPlaneWins(2.0, 1.99))
        // Surface agreement and the local fit's bands.
        assertEquals(0.009, SurfaceChooser.tolerance(0.3), 1e-12)
        assertEquals(0.06, SurfaceChooser.tolerance(2.0), 1e-12)
        val c = LocalSurfaceConfig()
        assertEquals(0.003, c.inlierThreshold(0.3), 1e-12)
        assertEquals(0.01, c.inlierThreshold(3.0), 1e-12)
        assertEquals(0.003, c.rmsLimit(0.3), 1e-12)
        assertEquals(0.008, c.rmsLimit(2.0), 1e-12)
    }

    @Test
    fun theStabiliserRestartsOnA4mmMoveAtArmsLength() {
        val s = SampleStabilizer()
        val p = Vec3(0.0, 0.0, -0.3)
        repeat(20) { s.add(p, 0.3, TrackingQuality.TRACKING) }
        assertTrue(s.estimate != null)
        s.add(p + Vec3(0.004, 0.0, 0.0), 0.3, TrackingQuality.TRACKING) // 4 mm: a different spot at 0.3 m (was within 8 mm)
        assertTrue(s.estimate == null || s.estimate!!.sampleCount < 3)
    }

    @Test
    fun blurAtArmsLengthLimitsTheSample() {
        // Research §5.1 item 3: t = 33 ms, f = 920 px, v = 0.1 m/s, Z = 0.3 m ⇒ ≈ 10 px ⇒ LIMITED.
        val optics = OpticsSample(33_000_000L, 920.0, lensMoving = false)
        assertEquals(10.1, BlurGate.blurPx(optics, 0.0, 0.1, 0.3), 0.05)
        assertFalse(BlurGate.usable(optics, 0.0, 0.1, 0.3))
        assertTrue(BlurGate.usable(optics, 0.0, 0.02, 3.0))
        // A missing exposure key uses 1/30 s.
        val noKey = OpticsSample(null, 920.0, lensMoving = false)
        assertEquals(BlurGate.blurPx(OpticsSample(BlurGate.FALLBACK_EXPOSURE_NS, 920.0, false), 0.1, 0.0, 1.0), BlurGate.blurPx(noKey, 0.1, 0.0, 1.0), 0.0)
        // A moving lens is never usable.
        assertFalse(BlurGate.usable(OpticsSample(1_000_000L, 920.0, lensMoving = true), 0.0, 0.0, 1.0))
    }

    @Test
    fun theTrackingGateLimitsBlurredAndRefocusingFrames() {
        val gate = TrackingGate()
        var t = 0L
        fun frame(z: Double, optics: OpticsSample?): GateResult {
            t += 33_333_333L
            // A slow 0.1 m/s slide.
            return gate.update(t, CameraTracking.TRACKING, FailureReason.NONE, Pose(Vec3(t / 1e9 * 0.1, 0.0, 0.0)), 0.5f, optics, z)
        }
        repeat(40) { frame(2.0, OpticsSample(33_000_000L, 920.0, false)) }
        assertEquals(TrackingQuality.TRACKING, frame(2.0, OpticsSample(33_000_000L, 920.0, false)).sampleQuality)
        val close = frame(0.3, OpticsSample(33_000_000L, 920.0, false))
        assertEquals(TrackingQuality.LIMITED, close.sampleQuality)
        assertTrue(close.blurPx > 5)
        assertEquals(TrackingQuality.LIMITED, frame(2.0, OpticsSample(33_000_000L, 920.0, true)).sampleQuality)
        // Without optics the gate behaves as before.
        assertEquals(TrackingQuality.TRACKING, frame(0.3, null).sampleQuality)
    }

    @Test
    fun noCommitBeforeTheScaleWarmUpSweep() {
        val w = ScaleWarmUp()
        // Standing still and turning only: not ready.
        repeat(30) { i -> w.add(Pose(Vec3.ZERO, yaw(i * 0.5))) }
        assertFalse(w.ready)
        // 0.15 m of sideways motion: still not ready.
        var x = 0.0
        repeat(15) { x += 0.01; w.add(Pose(Vec3(x, 0.0, 0.0), yaw(15.0))) }
        assertFalse(w.ready)
        assertTrue(w.progress < 1.0)
        repeat(10) { x += 0.01; w.add(Pose(Vec3(x, 0.0, 0.0), yaw(15.0))) }
        assertTrue(w.ready)
        // A relocalisation jump does not count as motion.
        w.reset()
        w.add(Pose(Vec3.ZERO, yaw(0.0)))
        w.add(Pose(Vec3(1.0, 0.0, 0.0), yaw(20.0)))
        assertFalse(w.ready)
    }

    private fun yaw(deg: Double): Quaternion {
        val h = Math.toRadians(deg) / 2
        return Quaternion(0.0, sin(h), 0.0, cos(h))
    }

    @Test
    fun aDetectedRectangleObeysTheWarmUpAndTheMinimumRange() {
        // Gate V1.
        assertFalse(DetectedRectangleRules.mayCommit(warmUpDone = false, cornerRangesM = listOf(0.5, 0.5, 0.5, 0.5)))
        assertFalse(DetectedRectangleRules.mayCommit(true, listOf(0.5, 0.5, 0.22, 0.5)))
        assertTrue(DetectedRectangleRules.mayCommit(true, listOf(0.5, 0.5, 0.5, 0.5)))
        assertFalse(DetectedRectangleRules.maySearch(warmingUp = true, crosshairRangeM = 0.5))
        assertFalse(DetectedRectangleRules.maySearch(false, 0.20))
        assertTrue(DetectedRectangleRules.maySearch(false, 0.5))
    }

    @Test
    fun theDwellWaitsForTheFinderThenComesBack() {
        // Gate V2.
        val p = FinderPriority()
        assertTrue(p.dwellSuspended(0, finderActive = true, points = 0))
        assertTrue(p.dwellSuspended(FinderPriority.FALLBACK_MS - 1, true, 0))
        assertFalse(p.dwellSuspended(FinderPriority.FALLBACK_MS, true, 0))
        // N1: while the finder can't search (warm-up / too close) the head start does not run down.
        val q = FinderPriority()
        assertTrue(q.dwellSuspended(0, true, 0, canSearch = false))
        assertTrue(q.dwellSuspended(FinderPriority.FALLBACK_MS * 2, true, 0, canSearch = false))
        assertTrue(q.dwellSuspended(FinderPriority.FALLBACK_MS * 2 + 1, true, 0, canSearch = true))
        assertTrue(q.dwellSuspended(FinderPriority.FALLBACK_MS * 3, true, 0, canSearch = true))
        assertFalse(q.dwellSuspended(FinderPriority.FALLBACK_MS * 3 + 1, true, 0, canSearch = true))
        assertTrue(p.timedOut(FinderPriority.FALLBACK_MS))
        // A point placed by "+" (or the finder) ends the priority; a new search starts a new head start.
        assertFalse(p.dwellSuspended(10_000, true, 1))
        assertTrue(p.dwellSuspended(10_001, true, 0))
        assertFalse(p.dwellSuspended(10_002, finderActive = false, points = 0))
    }

    @Test
    fun onlyPlaneOrSnappedNormalsAreLiftedOnto() {
        // Gate V4: a noisy point normal is used only when it is nearly horizontal or vertical, then snapped exactly.
        assertEquals(Vec3.UP, RectangleFinder.snapNormal(HitKind.DEPTH_POINT, Vec3(0.05, 1.0, 0.0).normalized()))
        val wall = RectangleFinder.snapNormal(HitKind.FEATURE_POINT, Vec3(0.1, 0.05, 1.0).normalized())!!
        assertEquals(0.0, wall.y, 0.0)
        assertEquals(null, RectangleFinder.snapNormal(HitKind.DEPTH_POINT, Vec3(0.0, 0.7, 0.7).normalized()))
        val tilted = Vec3(0.0, 0.7, 0.7).normalized()
        assertEquals(tilted, RectangleFinder.snapNormal(HitKind.PLANE, tilted))
    }

    @Test
    fun aCentimetrePaddedMenuWinsAtArmsLength() {
        // Gate v5: 1.5 % of range below 1 m.
        assertTrue(FrameReaders.nonPlaneWins(0.5, 0.49))   // 10 mm thick at 0.5 m
        assertTrue(FrameReaders.nonPlaneWins(0.6, 0.589))  // 11 mm at 0.6 m
        assertFalse(FrameReaders.nonPlaneWins(0.5, 0.495)) // 5 mm: measured on the table plane (bias covered by the ±)
        assertFalse(FrameReaders.nonPlaneWins(1.5, 1.47))  // room scale unchanged (3 %)
    }

    @Test
    fun theSampleBlurLimitOnlyAppliesUpClose() {
        // Gate v7: turning at 0.15 rad/s at 2 m stays TRACKING (blur ≈ 4.6 px would have been LIMITED).
        val gate = TrackingGate()
        var t = 0L
        var r: GateResult? = null
        repeat(40) { i ->
            t += 33_333_333L
            val h = 0.15 * t / 1e9 / 2
            r = gate.update(t, CameraTracking.TRACKING, FailureReason.NONE, Pose(Vec3.ZERO, Quaternion(0.0, kotlin.math.sin(h), 0.0, kotlin.math.cos(h))), 0.5f, OpticsSample(33_000_000L, 920.0, false), 2.0)
        }
        assertEquals(TrackingQuality.TRACKING, r!!.sampleQuality)
        assertTrue(r!!.blurPx > BlurGate.MAX_BLUR_SAMPLE_PX)
    }
}
