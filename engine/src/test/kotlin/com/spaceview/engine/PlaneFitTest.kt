package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PlaneFitTest {

    /** Points on a tilted wall-like plane through [origin] with normal [n], spread ±[half] m, Gaussian noise [sigma]. */
    private fun planePoints(rng: Random, origin: Vec3, n: Vec3, count: Int, half: Double, sigma: Double): List<Vec3> {
        val nn = n.normalized()
        val a = (if (abs(nn.x) < 0.9) Vec3.UNIT_X else Vec3.UNIT_Y).let { (it - nn * (it dot nn)).normalized() }
        val b = nn cross a
        return List(count) {
            origin + a * ((rng.nextDouble() * 2 - 1) * half) + b * ((rng.nextDouble() * 2 - 1) * half) +
                nn * (rng.nextGaussian() * sigma)
        }
    }

    private fun angleDeg(a: Vec3, b: Vec3) = Math.toDegrees(kotlin.math.acos(abs(a.normalized() dot b.normalized()).coerceAtMost(1.0)))

    @Test fun leastSquaresExactOnCleanPoints() {
        val rng = Random(1)
        val n = Vec3(0.1, 0.2, 1.0)
        val origin = Vec3(0.3, 1.2, -2.0)
        val pts = planePoints(rng, origin, n, 50, 0.5, 0.0)
        val fit = PlaneFitter.leastSquares(pts)!!
        assertTrue(angleDeg(fit.plane.normal, n) < 1e-6)
        assertEquals(0.0, fit.plane.signedDistance(origin), 1e-9)
        assertEquals(0.0, fit.rmsResidual, 1e-9)
        assertEquals(50, fit.inlierCount)
        assertEquals(1.0, fit.inlierRatio, 0.0)
    }

    @Test fun leastSquaresResidualMatchesNoise() {
        val rng = Random(2)
        val pts = planePoints(rng, Vec3(0.0, 1.0, -1.5), Vec3.UNIT_Z, 2000, 0.4, 0.003)
        val fit = PlaneFitter.leastSquares(pts)!!
        assertEquals(0.003, fit.rmsResidual, 0.0003)
        assertEquals(0.003 / kotlin.math.sqrt(2000.0), fit.offsetStandardError, 0.00001)
        assertEquals(0.0, fit.plane.signedDistance(Vec3(0.0, 1.0, -1.5)), 0.0005)
    }

    @Test fun degenerateInputs() {
        assertNull(PlaneFitter.leastSquares(listOf(Vec3.ZERO, Vec3.UNIT_X)))
        assertNull(PlaneFitter.leastSquares(List(10) { Vec3(it * 0.1, it * 0.2, 0.0) })) // collinear
        assertNull(PlaneFitter.leastSquares(List(10) { Vec3(1.0, 1.0, 1.0) })) // identical
        assertNull(PlaneFitter.ransac(listOf(Vec3.ZERO, Vec3.UNIT_X)))
        assertNull(PlaneFitter.ransac(List(20) { Vec3(it * 0.1, 0.0, 0.0) }))
    }

    @Test fun ransacSurvivesThirtyPercentOutliers() {
        val rng = Random(1234)
        val n = Vec3(0.05, -0.1, 1.0)
        val origin = Vec3(-0.2, 1.4, -1.8)
        val inliers = planePoints(rng, origin, n, 210, 0.6, 0.003)
        // 30% outliers: depth points on furniture / people 5–50 cm in front of the wall.
        val nn = n.normalized()
        val outliers = List(90) {
            origin + Vec3((rng.nextDouble() * 2 - 1) * 0.6, (rng.nextDouble() * 2 - 1) * 0.6, 0.0) +
                nn * (0.05 + rng.nextDouble() * 0.45)
        }
        val all = (inliers + outliers).shuffled(Random(99))
        val ls = PlaneFitter.leastSquares(all)!!
        val rs = PlaneFitter.ransac(all)!!
        // RANSAC is accurate; plain least squares is pulled ~8 cm off the wall by the outliers.
        assertTrue("ransac angle=${angleDeg(rs.plane.normal, n)}", angleDeg(rs.plane.normal, n) < 0.5)
        assertTrue(abs(rs.plane.signedDistance(origin)) < 0.001)
        assertTrue(rs.rmsResidual < 0.0045)
        assertTrue(rs.inlierCount >= 200)
        assertTrue(rs.inlierCount < 230)
        assertTrue(abs(ls.plane.signedDistance(origin)) > 0.03)
    }

    @Test fun ransacSurvivesFortyFivePercentOutliers() {
        val rng = Random(77)
        val n = Vec3.UNIT_Y // floor
        val origin = Vec3(0.0, 0.0, -1.0)
        val inliers = planePoints(rng, origin, n, 110, 1.0, 0.004)
        val outliers = List(90) { Vec3((rng.nextDouble() * 2 - 1), rng.nextDouble() * 1.5, -1 + (rng.nextDouble() * 2 - 1)) }
        val fit = PlaneFitter.ransac(inliers + outliers, RansacConfig(inlierThreshold = 0.012))!!
        assertTrue(angleDeg(fit.plane.normal, n) < 1.0)
        assertTrue(abs(fit.plane.signedDistance(origin)) < 0.002)
        assertTrue(fit.inliers.size == 200)
    }

    @Test fun ransacIsDeterministicForSeed() {
        val rng = Random(5)
        val pts = planePoints(rng, Vec3.ZERO, Vec3(1.0, 1.0, 1.0), 100, 0.5, 0.002) +
            List(30) { Vec3(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()) }
        val a = PlaneFitter.ransac(pts)!!
        val b = PlaneFitter.ransac(pts)!!
        assertEquals(a.plane.d, b.plane.d, 0.0)
        assertEquals(a.inlierCount, b.inlierCount)
    }

    @Test fun ransacReturnsNullWhenTooFewInliers() {
        val rng = Random(8)
        val scattered = List(40) { Vec3(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()) }
        assertNull(PlaneFitter.ransac(scattered, RansacConfig(inlierThreshold = 0.0005, minInliers = 20)))
    }

    @Test fun ransacOnPerfectPlaneStopsEarly() {
        val pts = planePoints(Random(3), Vec3.ZERO, Vec3.UNIT_Z, 30, 0.5, 0.0)
        val fit = PlaneFitter.ransac(pts)!!
        assertEquals(30, fit.inlierCount)
        assertEquals(0.0, fit.rmsResidual, 1e-12)
    }

    @Test fun tapsLandOnFittedWall() {
        // Camera 1.6 m from a wall; fit the wall from noisy depth points, then intersect tap rays with it.
        val rng = Random(21)
        val wallN = Vec3(0.0, 0.0, 1.0)
        val wallP = Vec3(0.0, 1.5, -1.6)
        val fit = PlaneFitter.ransac(planePoints(rng, wallP, wallN, 300, 0.8, 0.004))!!
        val cam = Pose(Vec3(0.1, 1.45, 0.0), Quaternion.fromAxisAngle(Vec3.UP, 0.05))
        val intr = CameraIntrinsics(1500.0, 1500.0, 960.0, 540.0)
        val trueWall = Plane.fromPointNormal(wallP, wallN)
        for ((u, v) in listOf(400.0 to 300.0, 1500.0 to 300.0, 1500.0 to 800.0, 400.0 to 800.0)) {
            val ray = Ray.fromPixel(cam, intr, u, v)
            val hit = fit.plane.intersect(ray)!!
            val truth = trueWall.intersect(ray)!!
            assertTrue("err=${hit.distanceTo(truth)}", hit.distanceTo(truth) < 0.002)
        }
        assertNotNull(fit.centroid)
        assertTrue(Uncertainty.planeBiasRel(fit, 1.6) < 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badRansacConfig() { RansacConfig(inlierThreshold = 0.0) }
}
