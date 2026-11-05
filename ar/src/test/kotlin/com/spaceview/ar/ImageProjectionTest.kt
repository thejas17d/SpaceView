package com.spaceview.ar

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Ray
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ImageProjectionTest {
    private val intr = CameraIntrinsics(500.0, 502.0, 321.0, 239.0)
    private val cam = CpuImageCamera(intr, 640, 480)
    private val out = FloatArray(8)

    private fun project(p: Vec3, pose: Pose): Pair<Double, Double>? =
        if (ImageProjection.project(p, pose, intr, out)) out[0].toDouble() to out[1].toDouble() else null

    @Test
    fun pointOnTheViewAxisLandsOnThePrincipalPoint() {
        val (u, v) = project(Vec3(0.0, 0.0, -2.0), Pose(Vec3.ZERO))!!
        assertEquals(321.0, u, 1e-3)
        assertEquals(239.0, v, 1e-3)
    }

    @Test
    fun rightIsPlusUAndUpIsMinusV() {
        val (u, _) = project(Vec3(0.2, 0.0, -2.0), Pose(Vec3.ZERO))!!
        assertEquals(321.0 + 500.0 * 0.1, u, 1e-3)
        val (_, v) = project(Vec3(0.0, 0.2, -2.0), Pose(Vec3.ZERO))!!
        assertEquals(239.0 - 502.0 * 0.1, v, 1e-3)
    }

    @Test
    fun pointsBehindTheCameraAreRejected() {
        assertTrue(project(Vec3(0.0, 0.0, 1.0), Pose(Vec3.ZERO)) == null)
        assertTrue(project(Vec3(0.0, 0.0, -0.01), Pose(Vec3.ZERO)) == null)
    }

    /** Inverse of the engine's Ray.fromPixel for random poses (any rotation, so any display orientation). */
    @Test
    fun roundTripsWithRayFromPixel() {
        val rng = Random(7)
        repeat(200) {
            val axis = Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian())
            val pose = Pose(Vec3(rng.nextDouble(), 1 + rng.nextDouble(), rng.nextDouble()), Quaternion.fromAxisAngle(axis, rng.nextDouble() * 6.28))
            val u = rng.nextDouble() * 640
            val v = rng.nextDouble() * 480
            val p = Ray.fromPixel(pose, intr, u, v).pointAt(0.3 + rng.nextDouble() * 3)
            val (pu, pv) = project(p, pose)!!
            assertEquals(u, pu, 1e-2)
            assertEquals(v, pv, 1e-2)
        }
    }

    /** Same convention as the raw-depth back-projection that FrameReaders' depth patch uses. */
    @Test
    fun matchesTheDepthPatchBackProjection() {
        val pose = Pose(Vec3(0.3, 1.4, -0.2), Quaternion.fromAxisAngle(Vec3(0.2, 1.0, 0.1), 0.7))
        val patch = DepthPatch(
            width = 1, height = 1, x0 = 400, y0 = 100, depthMm = shortArrayOf(1500), confidence = byteArrayOf(255.toByte()),
            fx = intr.fx, fy = intr.fy, cx = intr.cx, cy = intr.cy, sensorPose = pose,
        )
        val world = FloatArray(3)
        assertEquals(1, patch.toWorldPoints(world))
        val (u, v) = project(Vec3(world[0].toDouble(), world[1].toDouble(), world[2].toDouble()), pose)!!
        assertEquals(400.5, u, 0.05)
        assertEquals(100.5, v, 0.05)
    }

    /**
     * Phone held in portrait: the sensor's image +X then points down in the world (roll −90° about the
     * view axis), so something above the reticle appears to the left of centre in the landscape CPU image.
     */
    @Test
    fun portraitHoldMapsWorldUpToMinusU() {
        val portrait = Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UNIT_Z, -Math.PI / 2))
        val (u, v) = project(Vec3(0.0, 0.2, -2.0), portrait)!!
        assertEquals(321.0 - 500.0 * 0.1, u, 1e-3)
        assertEquals(239.0, v, 1e-3)
        // Upside-down landscape (roll 180°): world up is +v.
        val flipped = Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UNIT_Z, Math.PI))
        val (_, v2) = project(Vec3(0.0, 0.2, -2.0), flipped)!!
        assertEquals(239.0 + 502.0 * 0.1, v2, 1e-3)
    }

    private fun face(distance: Double, w: Double = 0.6, h: Double = 0.4) = listOf(
        Vec3(-w / 2, h / 2, -distance), Vec3(w / 2, h / 2, -distance), Vec3(w / 2, -h / 2, -distance), Vec3(-w / 2, -h / 2, -distance),
    )

    @Test
    fun fitCornersClassifiesTheFace() {
        val pose = Pose(Vec3.ZERO)
        assertEquals(CornerFit.INSIDE, ImageProjection.fitCorners(face(1.0), pose, cam, out))
        // Corners come back in the given order: top-left is up-left in the image.
        assertTrue(out[0] < out[2] && out[1] < out[5])
        assertEquals(CornerFit.OUTSIDE, ImageProjection.fitCorners(face(0.3), pose, cam, out))
        assertEquals(CornerFit.TOO_SMALL, ImageProjection.fitCorners(face(5.0), pose, cam, out))
        assertEquals(CornerFit.BEHIND, ImageProjection.fitCorners(face(-1.0), pose, cam, out))
        // Turned 90° away: off to the side, partly behind.
        val turned = Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UP, Math.PI / 2))
        assertFalse(ImageProjection.fitCorners(face(1.0), turned, cam, out) == CornerFit.INSIDE)
    }

    @Test
    fun scaledCameraProjectsProportionally() {
        val half = cam.scaledTo(320, 240)
        assertEquals(320, half.widthPx)
        assertEquals(250.0, half.intrinsics.fx, 1e-9)
        val p = Vec3(0.1, -0.05, -1.2)
        ImageProjection.project(p, Pose(Vec3.ZERO), intr, out, 0)
        ImageProjection.project(p, Pose(Vec3.ZERO), half.intrinsics, out, 2)
        assertEquals(out[0] / 2, out[2], 1e-3f)
        assertEquals(out[1] / 2, out[3], 1e-3f)
        assertTrue(cam.scaledTo(640, 480) === cam)
    }
}
