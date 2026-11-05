package com.spaceview.ar

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Measurement
import com.spaceview.engine.Pose
import com.spaceview.engine.Quality
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontCaptureControllerTest {
    private val camera = CpuImageCamera(CameraIntrinsics(500.0, 500.0, 320.0, 240.0), 640, 480)
    private val pose = Pose(Vec3(0.3, 1.2, 0.0)) // looking down −z at the wall
    private val frameNs = 33_000_000L
    private val out = FloatArray(8)

    /** A 60 × 40 cm frame on a wall 1 m in front of the camera. */
    private val points = listOf(Vec3(0.0, 1.4, -1.0), Vec3(0.6, 1.4, -1.0), Vec3(0.6, 1.0, -1.0))

    private fun outline(): ScanOutline {
        val choice = ScanComposer.chooseFace(ScanShape.FLAT, points, pose.translation, pose.forward)!!
        val face = ScanComposer.face(ScanShape.FLAT, points, choice)!!
        val m = Measurement(0.6, 0.005)
        return ScanOutline(ScanShape.FLAT, m, Measurement(0.4, 0.005), null, null, Quality.FAIR, false, true, face, choice)
    }

    private fun run(c: FrontCaptureController, repeats: Int, anchored: () -> Boolean = { true }, maxNs: Long = 3_000_000_000L): Long {
        var t = 1L
        while (t < maxNs) {
            repeat(repeats) {
                if (c.onFrame(t, true, anchored(), pose, { points }, camera, out)) return t
            }
            t += frameNs
        }
        return -1
    }

    @Test
    fun capturesOnRepeatedFramesAt60And120Hz() {
        for (repeats in listOf(1, 2, 4)) {
            val c = FrontCaptureController()
            assertTrue(c.arm(outline(), pointsAnchored = true))
            val at = run(c, repeats)
            assertTrue("$repeats× repeats: captured at $at", at in FrontCaptureGate.HOLD_NS..FrontCaptureGate.HOLD_NS + 4 * frameNs)
            assertEquals(CaptureHint.HOLDING, c.status!!.hint)
            c.onCaptured()
            assertEquals(CaptureHint.CAPTURED, c.status!!.hint)
            assertFalse(c.isArmed)
        }
    }

    /** M3 review M2: after a pause the points are unanchored; the capture never arms or fires. */
    @Test
    fun refusesUnanchoredPoints() {
        val c = FrontCaptureController()
        assertFalse(c.arm(outline(), pointsAnchored = false))
        assertEquals(CaptureHint.REMEASURE, c.status!!.hint)
        assertFalse(c.isArmed)
        assertEquals(-1, run(c, 2))
    }

    @Test
    fun losingAnchorsWhileArmedStopsTheCapture() {
        val c = FrontCaptureController()
        assertTrue(c.arm(outline(), pointsAnchored = true))
        var frames = 0
        val at = run(c, 1, anchored = { ++frames < 5 })
        assertEquals(-1, at)
        assertEquals(CaptureHint.REMEASURE, c.status!!.hint)
        assertFalse(c.isArmed)
    }

    @Test
    fun generationChangesOnEveryArmDisarmAndCapture() {
        val c = FrontCaptureController()
        val g0 = c.generation
        c.arm(outline(), true)
        val g1 = c.generation
        assertNotEquals(g0, g1)
        val captured = c.onCaptured()
        assertNotEquals(g1, captured)
        assertEquals(captured, c.generation)
        // A Back / Undo after the shot invalidates a photo still being converted.
        c.disarm()
        assertNotEquals(captured, c.generation)
        assertNull(c.status)
    }

    /** The face photographed is the one in the outline, whatever the camera does meanwhile. */
    @Test
    fun photographsTheOutlinesFace() {
        val c = FrontCaptureController()
        val o = outline()
        c.arm(o, true)
        val face = c.faceNow(points)!!
        for (i in 0 until 4) assertTrue(o.face.corners[i].approxEquals(face.corners[i], 1e-9))
    }
}
