package com.spaceview.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HomographyTest {

    private fun assertClose(e: Vec2, a: Vec2, tol: Double) {
        assertTrue("expected $e but was $a", e.distanceTo(a) <= tol)
    }

    /** Projects world point [p] into a pinhole camera (ARCore conventions). */
    private fun project(pose: Pose, k: CameraIntrinsics, p: Vec3): Vec2 {
        val c = pose.inverse().transformPoint(p)
        return Vec2(k.cx + k.fx * c.x / -c.z, k.cy - k.fy * c.y / -c.z)
    }

    @Test fun mapsCornersExactly() {
        val src = listOf(Vec2(812.3, 401.7), Vec2(1630.2, 455.0), Vec2(1588.8, 1120.4), Vec2(760.1, 1090.9))
        val dst = listOf(Vec2(0.0, 0.0), Vec2(1024.0, 0.0), Vec2(1024.0, 683.0), Vec2(0.0, 683.0))
        val h = Homography.fromCorrespondences(src, dst)!!
        for (i in 0 until 4) assertClose(dst[i], h.map(src[i]), 1e-8)
        assertEquals(1.0, h.matrix[8], 1e-12)
        val inv = h.inverse()
        for (i in 0 until 4) assertClose(src[i], inv.map(dst[i]), 1e-8)
    }

    @Test fun randomQuadsMapExactly() {
        val rng = Random(4)
        repeat(200) {
            // Random convex-ish quad around an image centre.
            val cx = 500 + rng.nextDouble() * 1000; val cy = 400 + rng.nextDouble() * 400
            val src = listOf(
                Vec2(cx - 300 - rng.nextDouble() * 200, cy - 200 - rng.nextDouble() * 150),
                Vec2(cx + 300 + rng.nextDouble() * 200, cy - 200 - rng.nextDouble() * 150),
                Vec2(cx + 300 + rng.nextDouble() * 200, cy + 200 + rng.nextDouble() * 150),
                Vec2(cx - 300 - rng.nextDouble() * 200, cy + 200 + rng.nextDouble() * 150),
            )
            val h = Homography.rectify(src, 1024.0, 768.0)!!
            val dst = listOf(Vec2(0.0, 0.0), Vec2(1024.0, 0.0), Vec2(1024.0, 768.0), Vec2(0.0, 768.0))
            for (i in 0 until 4) assertClose(dst[i], h.map(src[i]), 1e-7)
        }
    }

    /** A frame photographed obliquely is rectified so that interior points land where they belong. */
    @Test fun rectifiesTruePerspective() {
        val k = CameraIntrinsics(1500.0, 1500.0, 960.0, 540.0)
        // Camera 1.2 m from the wall, rotated 25° to the side and tilted 8° down: strong perspective.
        val rot = Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(25.0)) * Quaternion.fromAxisAngle(Vec3.UNIT_X, Math.toRadians(-8.0))
        val pose = Pose(Vec3(0.55, 1.55, -0.2), rot)
        // 60 × 40 cm frame on the wall z = −1.4.
        val w = 0.6; val hgt = 0.4
        fun onFrame(u: Double, v: Double) = Vec3(-0.3 + u * w, 1.6 - v * hgt, -1.4)
        val img = listOf(onFrame(0.0, 0.0), onFrame(1.0, 0.0), onFrame(1.0, 1.0), onFrame(0.0, 1.0)).map { project(pose, k, it) }
        val (ow, oh) = Homography.outputSize(w, hgt, 1024)
        assertEquals(1024, ow)
        assertEquals(683, oh)
        val h = Homography.rectify(img, ow.toDouble(), oh.toDouble())!!
        for ((u, v) in listOf(0.25 to 0.7, 0.5 to 0.5, 0.9 to 0.1, 0.33 to 0.33)) {
            val mapped = h.map(project(pose, k, onFrame(u, v)))
            assertClose(Vec2(u * ow, v * oh), mapped, 1e-6)
        }
        // Inverse maps the output centre back to the projected frame centre (what a warp samples).
        assertClose(project(pose, k, onFrame(0.5, 0.5)), h.inverse().map(ow / 2.0, oh / 2.0), 1e-6)
    }

    @Test fun degenerateInputsReturnNull() {
        val collinear = listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0), Vec2(2.0, 2.0), Vec2(0.0, 5.0))
        val square = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))
        assertNull(Homography.fromCorrespondences(collinear, square))
        assertNull(Homography.fromCorrespondences(square, collinear))
        assertNull(Homography.fromCorrespondences(List(4) { Vec2(1.0, 1.0) }, square))
        // Self-intersecting ("bow-tie") corner order is refused by rectify.
        val bowTie = listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0))
        assertNull(Homography.rectify(bowTie, 10.0, 10.0))
        assertNull(Homography.rectify(square.take(3), 10.0, 10.0))
    }

    @Test fun convexity() {
        val ccw = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))
        assertTrue(Homography.isConvexQuad(ccw))
        assertTrue(Homography.isConvexQuad(ccw.reversed()))
        assertFalse(Homography.isConvexQuad(listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0), Vec2(0.5, 0.5), Vec2(0.0, 2.0))))
        assertFalse(Homography.isConvexQuad(ccw.take(3)))
        assertFalse(Homography.isConvexQuad(listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0), Vec2(0.0, 1.0))))
    }

    @Test fun outputSizeKeepsAspect() {
        assertEquals(1024 to 683, Homography.outputSize(0.60, 0.40))
        assertEquals(683 to 1024, Homography.outputSize(0.40, 0.60))
        assertEquals(512 to 512, Homography.outputSize(1.0, 1.0, 512))
        assertEquals(1024 to 1, Homography.outputSize(10.0, 0.001))
    }

    @Test fun identityAndFloatExport() {
        val sq = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))
        val h = Homography.fromCorrespondences(sq, sq)!!
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), h.matrix, 1e-12)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), h.toFloatArray(), 1e-6f)
        assertTrue(h.toString().startsWith("Homography"))
        // Pure translation/scale.
        val t = Homography(doubleArrayOf(2.0, 0.0, 5.0, 0.0, 3.0, -1.0, 0.0, 0.0, 1.0))
        assertClose(Vec2(7.0, 2.0), t.map(1.0, 1.0), 1e-12)
        assertClose(Vec2(1.0, 1.0), t.inverse().map(7.0, 2.0), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun singularInverse() { Homography(DoubleArray(9)).inverse() }

    @Test(expected = IllegalArgumentException::class)
    fun wrongSize() { Homography(DoubleArray(8)) }

    @Test fun linearSolver() {
        val a = arrayOf(doubleArrayOf(0.0, 2.0, 1.0), doubleArrayOf(1.0, -1.0, 0.0), doubleArrayOf(3.0, 0.0, -2.0))
        val x = LinearSolver.solve(a, doubleArrayOf(5.0, -1.0, -1.0))!!
        // Check A·x = b (first pivot needs a row swap).
        assertEquals(5.0, 2 * x[1] + x[2], 1e-12)
        assertEquals(-1.0, x[0] - x[1], 1e-12)
        assertEquals(-1.0, 3 * x[0] - 2 * x[2], 1e-12)
        assertNull(LinearSolver.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), doubleArrayOf(1.0, 2.0)))
        assertNull(LinearSolver.solve(arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)), doubleArrayOf(1.0, 2.0)))
    }
}
