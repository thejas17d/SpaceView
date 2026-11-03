package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Ray
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StabilizerAndSurfaceTest {

    @Test
    fun holdingStillLocksNearTruePoint() {
        val s = SampleStabilizer()
        val rng = Random(1)
        val truth = Vec3(0.2, 0.0, -1.0)
        repeat(30) {
            s.add(truth + Vec3(rng.nextGaussian() * 0.001, rng.nextGaussian() * 0.001, rng.nextGaussian() * 0.001), 1.0, TrackingQuality.TRACKING)
        }
        assertTrue(s.isLocked)
        assertTrue(s.estimate!!.position.distanceTo(truth) < 0.001)
    }

    @Test
    fun movingTheReticleRestartsTheWindow() {
        val s = SampleStabilizer()
        repeat(20) { s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.TRACKING) }
        assertNotNull(s.estimate)
        s.add(Vec3(0.05, 0.0, -1.0), 1.0, TrackingQuality.TRACKING) // 5 cm jump
        assertNull(s.estimate)
        assertTrue(s.progress < 0.2)
    }

    @Test
    fun lostTrackingClears() {
        val s = SampleStabilizer()
        repeat(20) { s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.TRACKING) }
        s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.NOT_TRACKING)
        assertNull(s.estimate)
        assertEquals(0.0, s.progress, 0.0)
    }

    @Test
    fun fitsWallFromNoisyPointsAndIntersectsRay() {
        val model = LocalSurfaceModel()
        val rng = Random(7)
        // Wall z = -2 with 2 mm noise, plus 20% outliers.
        val pts = FloatArray(3 * 300)
        for (i in 0 until 300) {
            val x = rng.nextDouble() * 0.4 - 0.2
            val y = 1.0 + rng.nextDouble() * 0.4 - 0.2
            val z = if (i % 5 == 0) -2.0 + rng.nextDouble() * 0.5 else -2.0 + rng.nextGaussian() * 0.002
            pts[i * 3] = x.toFloat(); pts[i * 3 + 1] = y.toFloat(); pts[i * 3 + 2] = z.toFloat()
        }
        model.addPoints(pts, 300, 0L)
        val ray = Ray(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0))
        assertNotNull(model.update(ray, 1L))
        val hit = model.intersect(ray, ray.origin)!!
        assertEquals(-2.0, hit.position.z, 0.003)
        assertTrue(hit.planeBiasRel < 0.005)
    }

    @Test
    fun noSurfaceWithoutEnoughPoints() {
        val model = LocalSurfaceModel()
        model.addPoints(floatArrayOf(0f, 1f, -2f, 0.01f, 1f, -2f), 2, 0L)
        assertNull(model.update(Ray(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0)), 1L))
    }

    @Test
    fun grazingViewIsRefused() {
        val model = LocalSurfaceModel()
        val pts = FloatArray(3 * 100)
        // Floor y = 0 around (0, 0, -2).
        for (i in 0 until 100) { pts[i * 3] = (i % 10) * 0.02f - 0.1f; pts[i * 3 + 1] = 0f; pts[i * 3 + 2] = -2f + (i / 10) * 0.02f - 0.1f }
        model.addPoints(pts, 100, 0L)
        // Nearly horizontal ray from 5 cm above the floor.
        val ray = Ray(Vec3(0.0, 0.05, 0.0), Vec3(0.0, -0.025, -1.0))
        model.update(ray, 1L)
        assertNull(model.intersect(ray, ray.origin))
    }

    @Test
    fun depthPatchBackProjectsAlongOpticalAxis() {
        // 3×3 patch centred on the principal point, 1.5 m deep, full confidence.
        val patch = DepthPatch(
            width = 3, height = 3, x0 = 79, y0 = 59,
            depthMm = ShortArray(9) { 1500 }, confidence = ByteArray(9) { 255.toByte() },
            fx = 120.0, fy = 120.0, cx = 80.5, cy = 60.5, sensorPose = Pose(Vec3(0.0, 1.0, 0.0)),
        )
        val out = FloatArray(27)
        assertEquals(9, patch.toWorldPoints(out))
        // Centre pixel (80, 60) → (0, 1, -1.5).
        assertEquals(0f, out[4 * 3], 1e-6f)
        assertEquals(1f, out[4 * 3 + 1], 1e-6f)
        assertEquals(-1.5f, out[4 * 3 + 2], 1e-6f)
        // Low confidence is dropped.
        val weak = DepthPatch(3, 3, 79, 59, ShortArray(9) { 1500 }, ByteArray(9) { 50 }, 120.0, 120.0, 80.5, 60.5, Pose.IDENTITY)
        assertFalse(weak.toWorldPoints(out) > 0)
    }
}
