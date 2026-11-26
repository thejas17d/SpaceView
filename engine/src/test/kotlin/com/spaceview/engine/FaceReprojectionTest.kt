package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** PLAN v3.1 W8: corner pixels → rays → the face plane → metres (never pixel ratios). */
class FaceReprojectionTest {
    private val intr = CameraIntrinsics(500.0, 500.0, 320.0, 240.0)

    /** A 0.6 × 0.4 m frame on the wall z = 0, centred at (0, 1.2, 0); TL, TR, BR, BL seen from +Z. */
    private val frame = listOf(Vec3(-0.3, 1.4, 0.0), Vec3(0.3, 1.4, 0.0), Vec3(0.3, 1.0, 0.0), Vec3(-0.3, 1.0, 0.0))
    private val wall = FaceReprojection.planeOf(frame, Vec3.UNIT_Z)!!

    private fun frontal(z: Double = 1.5) = CaptureCamera(Pose(Vec3(0.0, 1.2, z)), intr, 640, 480)

    /** A camera 1.6 m away, 35° to the right of the frame's axis, turned to look at its centre. */
    private fun oblique(): CaptureCamera {
        val yaw = Math.toRadians(35.0)
        val pos = Vec3(1.6 * kotlin.math.sin(yaw), 1.3, 1.6 * kotlin.math.cos(yaw))
        return CaptureCamera(Pose(pos, Quaternion.fromAxisAngle(Vec3.UP, yaw)), intr, 640, 480)
    }

    private fun pixels(cam: CaptureCamera, pts: List<Vec3> = frame): DoubleArray {
        val out = DoubleArray(8)
        pts.forEachIndexed { k, p -> FaceReprojection.project(p, cam)!!.copyInto(out, 2 * k) }
        return out
    }

    @Test fun reprojectionRoundTripIsExact() {
        for (cam in listOf(frontal(), frontal(0.6), oblique())) {
            val px = pixels(cam)
            val face = FaceReprojection.measure(px, cam, wall, 1.0, 0.002, DeviceTier.NO_DEPTH)!!
            assertEquals(0.6, face.width.value, 1e-9)
            assertEquals(0.4, face.height.value, 1e-9)
            for (k in 0 until 4) assertTrue("corner $k", face.corners[k].approxEquals(frame[k], 1e-9))
            // Lift of a single pixel lands on the wall, and projecting it back gives the same pixel.
            val p = FaceReprojection.lift(px[2], px[3], cam, wall)!!
            val back = FaceReprojection.project(p, cam)!!
            assertEquals(px[2], back[0], 1e-9); assertEquals(px[3], back[1], 1e-9)
        }
    }

    @Test fun twoPixelDragChangesTheWidthByTheGeometricAmount() {
        val cam = frontal() // 1.5 m away, f = 500 px: 1 px = 3 mm on the wall
        val px = pixels(cam)
        val dragged = px.copyOf().also { it[2] += 2.0; it[4] += 2.0 } // the right side, 2 px to the right
        val a = FaceReprojection.measure(px, cam, wall, 1.0, 0.002, DeviceTier.NO_DEPTH)!!
        val b = FaceReprojection.measure(dragged, cam, wall, 1.0, 0.002, DeviceTier.NO_DEPTH)!!
        assertEquals(2 * 1.5 / 500, b.width.value - a.width.value, 1e-9)
        assertEquals("height untouched", a.height.value, b.height.value, 1e-9)
        // Closer, the same 2 px is less: 0.6 m → 2.4 mm.
        val near = frontal(0.6)
        val pn = pixels(near)
        val dn = pn.copyOf().also { it[2] += 2.0; it[4] += 2.0 }
        val wn = FaceReprojection.measure(dn, near, wall, 1.0, 0.002, DeviceTier.NO_DEPTH)!!.width.value
        assertEquals(2 * 0.6 / 500, wn - 0.6, 1e-9)
    }

    @Test fun plusMinusHasEveryTerm() {
        val cam = frontal()
        val px = pixels(cam)
        val tier = DeviceTier.NO_DEPTH
        val face = FaceReprojection.measure(px, cam, wall, 1.0, 0.002, tier)!!
        // Every corner is t = √(0.3² + 0.2² + 1.5²) away and seen at cos = 1.5 / t: σ = 1 px / f × t / cos.
        val t = sqrt(0.3 * 0.3 + 0.2 * 0.2 + 1.5 * 1.5)
        val corner = 1.0 / 500 * t / (1.5 / t)
        val expected = sqrt(corner * corner + (0.6 * 0.002 / t).let { it * it } + (0.6 * tier.scaleSigmaRel).let { it * it } + tier.floorLengthSigma * tier.floorLengthSigma)
        assertEquals(expected, face.width.sigma, 1e-12)
        assertEquals(t, face.meanRangeM, 1e-9)
        // Never better than the floor; an oblique view widens the corner term.
        val zero = FaceReprojection.measure(px, cam, wall, 0.0, 0.0, tier)!!
        assertTrue(zero.width.sigma >= tier.floorLengthSigma)
        val obl = oblique()
        val o = FaceReprojection.measure(pixels(obl), obl, wall, 1.0, 0.002, tier)!!
        assertTrue("oblique corners are less certain", o.width.sigma > FaceReprojection.measure(pixels(frontal(1.6)), frontal(1.6), wall, 1.0, 0.002, tier)!!.width.sigma)
    }

    @Test fun missesAndDegenerateQuadsAreRefused() {
        val cam = frontal()
        val px = pixels(cam)
        // A plane behind the camera.
        val behind = Plane.fromPointNormal(Vec3(0.0, 0.0, 3.0), Vec3.UNIT_Z)
        assertNull(FaceReprojection.measure(px, cam, behind, 1.0, 0.0, DeviceTier.NO_DEPTH))
        // A plane seen edge-on (the floor from eye level, towards the horizon row).
        val floor = Plane.fromPointNormal(Vec3.ZERO, Vec3.UP)
        assertNull(FaceReprojection.lift(320.0, 240.0, cam, floor))
        // All four corners on one pixel.
        assertNull(FaceReprojection.measure(DoubleArray(8) { if (it % 2 == 0) 320.0 else 240.0 }, cam, wall, 1.0, 0.0, DeviceTier.NO_DEPTH))
        assertNull(FaceReprojection.measure(px.copyOf().also { it[0] = Double.NaN }, cam, wall, 1.0, 0.0, DeviceTier.NO_DEPTH))
        assertNull("behind the camera", FaceReprojection.project(Vec3(0.0, 1.2, 2.0), cam))
    }

    @Test fun planeOfFacesTheViewer() {
        val p = FaceReprojection.planeOf(frame, Vec3.UNIT_Z)!!
        assertTrue(p.normal.approxEquals(Vec3.UNIT_Z, 1e-12))
        assertTrue(FaceReprojection.planeOf(frame, -Vec3.UNIT_Z)!!.normal.approxEquals(-Vec3.UNIT_Z, 1e-12))
        assertEquals(0.0, FaceReprojection.planeResidual(frame, p), 1e-12)
        assertNull(FaceReprojection.planeOf(List(4) { Vec3.ZERO }))
    }
}
