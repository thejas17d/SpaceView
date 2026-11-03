package com.spaceview.ar

import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class ViewProjectionTest {

    /** Camera at [eye] looking down −Z (identity rotation), symmetric perspective. */
    private fun camera(eye: Vec3, w: Int = 1000, h: Int = 2000, fovYDeg: Double = 60.0): ViewProjection {
        val view = FloatArray(16).also {
            it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
            it[12] = (-eye.x).toFloat(); it[13] = (-eye.y).toFloat(); it[14] = (-eye.z).toFloat()
        }
        val near = 0.05; val far = 30.0
        val f = 1.0 / tan(Math.toRadians(fovYDeg) / 2)
        val aspect = w.toDouble() / h
        val proj = FloatArray(16).also {
            it[0] = (f / aspect).toFloat(); it[5] = f.toFloat()
            it[10] = ((far + near) / (near - far)).toFloat(); it[11] = -1f
            it[14] = (2 * far * near / (near - far)).toFloat()
        }
        return ViewProjection(view, proj, w, h)
    }

    @Test
    fun pointStraightAheadProjectsToCentre() {
        val vp = camera(Vec3(0.0, 1.5, 0.0))
        val s = vp.project(Vec3(0.0, 1.5, -2.0))!!
        assertEquals(500f, s.x, 0.01f)
        assertEquals(1000f, s.y, 0.01f)
    }

    @Test
    fun pointBehindCameraIsNotProjected() {
        val vp = camera(Vec3.ZERO)
        assertNull(vp.project(Vec3(0.0, 0.0, 1.0)))
    }

    @Test
    fun centreRayPointsForwardFromEye() {
        val eye = Vec3(0.3, 1.2, -0.4)
        val ray = camera(eye).centreRay()!!
        assertTrue(ray.direction.approxEquals(Vec3(0.0, 0.0, -1.0), 1e-6))
        // Origin is on the near plane, on the optical axis.
        assertEquals(eye.x, ray.origin.x, 1e-6)
        assertEquals(eye.y, ray.origin.y, 1e-6)
    }

    @Test
    fun rayThroughProjectedPixelHitsThePoint() {
        val vp = camera(Vec3(0.1, 1.0, 0.0))
        val p = Vec3(0.4, 0.7, -1.7)
        val s = vp.project(p)!!
        val ray = vp.rayThrough(s.x.toDouble(), s.y.toDouble())!!
        val t = (p - ray.origin) dot ray.direction
        assertTrue("ray misses by ${ray.pointAt(t).distanceTo(p)}", ray.pointAt(t).distanceTo(p) < 1e-4)
    }

    @Test
    fun segmentCrossingBehindCameraIsClipped() {
        val vp = camera(Vec3.ZERO)
        val seg = vp.projectSegment(Vec3(0.0, 0.0, -2.0), Vec3(0.5, 0.0, 2.0))
        assertNotNull(seg)
        assertNull(vp.projectSegment(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 2.0)))
    }

    @Test
    fun invertRoundTrips() {
        val m = doubleArrayOf(2.0, 0.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, 4.0, 0.0, 1.0, 2.0, 3.0, 1.0)
        val inv = Mat4.invert(m)!!
        val id = DoubleArray(16)
        Mat4.multiply(m, inv, id)
        for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1.0 else 0.0, id[i], 1e-12)
    }
}
