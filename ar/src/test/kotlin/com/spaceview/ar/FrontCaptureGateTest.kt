package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontCaptureGateTest {
    /** Face on a wall at z = −1, facing the camera at the origin. */
    private val normal = Vec3(0.0, 0.0, 1.0)
    private val frameNs = 33_000_000L

    private fun yaw(deg: Double) = Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(deg)))

    @Test
    fun angleToFace() {
        assertEquals(0.0, FrontCaptureGate.angleToFaceDeg(Vec3(0.0, 0.0, -1.0), normal), 1e-9)
        assertEquals(20.0, FrontCaptureGate.angleToFaceDeg(yaw(20.0).forward, normal), 1e-6)
        assertEquals(180.0, FrontCaptureGate.angleToFaceDeg(Vec3(0.0, 0.0, 1.0), normal), 1e-9)
        // A tilted (pitched) phone counts the same as a turned one.
        val pitched = Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UNIT_X, Math.toRadians(-12.0)))
        assertEquals(12.0, FrontCaptureGate.angleToFaceDeg(pitched.forward, normal), 1e-6)
    }

    @Test
    fun capturesAfterHoldingSteadyAndParallel() {
        val gate = FrontCaptureGate()
        var t = 0L
        val first = gate.update(t, true, yaw(5.0), normal, CornerFit.INSIDE)
        assertEquals(CaptureHint.HOLD_STILL, first.status.hint) // no motion history yet
        var captured = -1L
        while (t < 2_000_000_000L && captured < 0) {
            t += frameNs
            val d = gate.update(t, true, yaw(5.0), normal, CornerFit.INSIDE)
            if (d.capture) captured = t else assertTrue(d.status.hint == CaptureHint.HOLDING)
        }
        assertTrue("captured at $captured", captured >= FrontCaptureGate.HOLD_NS)
        assertTrue(captured <= FrontCaptureGate.HOLD_NS + 3 * frameNs)
    }

    @Test
    fun tiltBeyondTheLimitNeverCaptures() {
        val gate = FrontCaptureGate()
        var t = 0L
        repeat(60) {
            val d = gate.update(t, true, yaw(FrontCaptureGate.MAX_ANGLE_DEG + 3), normal, CornerFit.INSIDE)
            assertFalse(d.capture)
            if (it > 0) assertEquals(CaptureHint.TILT, d.status.hint)
            t += frameNs
        }
        assertEquals(15, gate.update(t, true, yaw(17.0), normal, CornerFit.INSIDE).status.angleDeg)
    }

    @Test
    fun movingPhoneMustHoldStill() {
        val gate = FrontCaptureGate()
        var t = 0L
        repeat(60) { i ->
            // 0.3 m/s sideways.
            val d = gate.update(t, true, Pose(Vec3(0.01 * i, 0.0, 0.0)), normal, CornerFit.INSIDE)
            assertFalse(d.capture)
            assertEquals(CaptureHint.HOLD_STILL, d.status.hint)
            t += frameNs
        }
    }

    @Test
    fun hintPriority() {
        val gate = FrontCaptureGate()
        val p = yaw(0.0)
        assertEquals(CaptureHint.NOT_TRACKING, gate.update(0, false, p, normal, CornerFit.OUTSIDE).status.hint)
        assertEquals(CaptureHint.FACE_OBJECT, gate.update(frameNs, true, p, normal, CornerFit.BEHIND).status.hint)
        assertEquals(CaptureHint.FACE_OBJECT, gate.update(2 * frameNs, true, yaw(80.0), normal, CornerFit.INSIDE).status.hint)
        assertEquals(CaptureHint.STEP_BACK, gate.update(3 * frameNs, true, p, normal, CornerFit.OUTSIDE).status.hint)
        assertEquals(CaptureHint.STEP_CLOSER, gate.update(4 * frameNs, true, p, normal, CornerFit.TOO_SMALL).status.hint)
    }

    @Test
    fun interruptionRestartsTheHold() {
        val gate = FrontCaptureGate()
        var t = 0L
        gate.update(t, true, yaw(0.0), normal, CornerFit.INSIDE)
        repeat(12) { t += frameNs; assertFalse(gate.update(t, true, yaw(0.0), normal, CornerFit.INSIDE).capture) }
        t += frameNs
        assertEquals(CaptureHint.STEP_BACK, gate.update(t, true, yaw(0.0), normal, CornerFit.OUTSIDE).status.hint)
        val restart = t + frameNs
        var captured = -1L
        while (captured < 0) {
            t += frameNs
            if (gate.update(t, true, yaw(0.0), normal, CornerFit.INSIDE).capture) captured = t
        }
        assertTrue(captured - restart >= FrontCaptureGate.HOLD_NS)
    }

    @Test
    fun statusIsCoarseSoItRarelyChanges() {
        val gate = FrontCaptureGate()
        var t = 0L
        val seen = HashSet<FrontCaptureStatus>()
        repeat(15) {
            seen += gate.update(t, true, yaw(4.0 + it * 0.05), normal, CornerFit.INSIDE).status
            t += frameNs
        }
        // HOLD_STILL once, then HOLDING with progress 0, 0.25, 0.5, 0.75 (angle stays in the 0–5° bucket).
        assertTrue("${seen.size} distinct statuses", seen.size <= 5)
    }

    /**
     * M3 review B1: SceneView calls back at display rate, so a 30 fps camera image arrives 2× (60 Hz) or
     * 4× (120 Hz) with the same timestamp. Repeats must neither reset the motion estimate nor the hold.
     */
    @Test
    fun repeatedCameraFramesStillCapture() {
        for (repeats in listOf(2, 4)) {
            val gate = FrontCaptureGate()
            var t = 0L
            var captured = -1L
            var hints = HashSet<CaptureHint>()
            while (t < 3_000_000_000L && captured < 0) {
                repeat(repeats) {
                    val d = gate.update(t, true, yaw(5.0), normal, CornerFit.INSIDE)
                    hints += d.status.hint
                    if (d.capture && captured < 0) captured = t
                }
                t += frameNs
            }
            assertTrue("${repeats}x: captured at $captured", captured in FrontCaptureGate.HOLD_NS..FrontCaptureGate.HOLD_NS + 3 * frameNs)
            // Only the first camera frame (no motion history yet) is "hold still": no flicker.
            assertEquals(setOf(CaptureHint.HOLD_STILL, CaptureHint.HOLDING), hints)
        }
    }

    @Test
    fun boxFacesAllowAWiderAngle() {
        assertEquals(FrontCaptureGate.MAX_ANGLE_DEG, FrontCaptureGate.maxAngleFor(ScanShape.FLAT), 0.0)
        assertTrue(FrontCaptureGate.maxAngleFor(ScanShape.BOX) > FrontCaptureGate.MAX_ANGLE_DEG)
        val gate = FrontCaptureGate(maxAngleDeg = FrontCaptureGate.maxAngleFor(ScanShape.BOX))
        gate.update(0, true, yaw(20.0), normal, CornerFit.INSIDE)
        assertEquals(CaptureHint.HOLDING, gate.update(frameNs, true, yaw(20.0), normal, CornerFit.INSIDE).status.hint)
    }
}
