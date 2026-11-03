package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class GeometryTest {
    private val eps = 1e-12

    private fun assertVec(expected: Vec3, actual: Vec3, tol: Double = 1e-9) {
        assertTrue("expected $expected but was $actual", expected.approxEquals(actual, tol))
    }

    @Test fun vectorArithmetic() {
        val a = Vec3(1.0, 2.0, 3.0)
        val b = Vec3(-4.0, 5.0, 0.5)
        assertVec(Vec3(-3.0, 7.0, 3.5), a + b)
        assertVec(Vec3(5.0, -3.0, 2.5), a - b)
        assertVec(Vec3(2.0, 4.0, 6.0), a * 2.0)
        assertVec(Vec3(0.5, 1.0, 1.5), a / 2.0)
        assertVec(Vec3(-1.0, -2.0, -3.0), -a)
        assertEquals(-4.0 + 10.0 + 1.5, a dot b, eps)
        val c = a cross b
        assertEquals(0.0, c dot a, 1e-12)
        assertEquals(0.0, c dot b, 1e-12)
        assertVec(Vec3.UNIT_Z, Vec3.UNIT_X cross Vec3.UNIT_Y)
        assertEquals(14.0, a.lengthSquared, eps)
        assertEquals(sqrt(14.0), a.length, eps)
        assertEquals(1.0, a.normalized().length, eps)
        assertEquals((a - b).length, a.distanceTo(b), eps)
        assertVec(Vec3(-1.5, 3.5, 1.75), Vec3.lerp(a, b, 0.5))
        assertTrue(a.isFinite)
        assertFalse(Vec3(Double.NaN, 0.0, 0.0).isFinite)
        assertEquals(Vec3(1.0, 2.0, 3.0), a)
        assertEquals(Vec3(1.0, 2.0, 3.0).hashCode(), a.hashCode())
        assertNotEquals(a, b)
        assertTrue(a.toString().contains("1.0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeZeroThrows() { Vec3.ZERO.normalized() }

    @Test fun vec2() {
        val a = Vec2(3.0, 4.0)
        assertEquals(5.0, a.length, eps)
        assertEquals(Vec2(4.0, 6.0), a + Vec2(1.0, 2.0))
        assertEquals(Vec2(2.0, 2.0), a - Vec2(1.0, 2.0))
        assertEquals(Vec2(6.0, 8.0), a * 2.0)
        assertEquals(5.0, Vec2(0.0, 0.0).distanceTo(a), eps)
        assertEquals(1.0, Vec2(1.0, 0.0) cross Vec2(0.0, 1.0), eps)
        assertEquals(Vec2(3.0, 4.0).hashCode(), a.hashCode())
        assertTrue(a.toString().startsWith("Vec2"))
    }

    @Test fun quaternionRotation() {
        val q = Quaternion.fromAxisAngle(Vec3.UNIT_Y, PI / 2)
        // +90° about Y maps +X to −Z.
        assertVec(Vec3(0.0, 0.0, -1.0), q.rotate(Vec3.UNIT_X))
        assertVec(Vec3.UNIT_X, q.inverse().rotate(q.rotate(Vec3.UNIT_X)))
        val q2 = Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 3)
        val v = Vec3(0.3, -1.2, 2.0)
        assertVec(q.rotate(q2.rotate(v)), (q * q2).rotate(v))
        assertEquals(v.length, (q * q2).rotate(v).length, 1e-12)
        // Constructor normalises.
        val n = Quaternion(0.0, 0.0, 0.0, 2.0)
        assertEquals(1.0, n.w, eps)
        assertTrue(n.toString().startsWith("Quaternion"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroQuaternionThrows() { Quaternion(0.0, 0.0, 0.0, 0.0) }

    @Test fun poseTransforms() {
        val pose = Pose(Vec3(1.0, 2.0, 3.0), Quaternion.fromAxisAngle(Vec3(1.0, 1.0, 0.0), 0.7))
        val p = Vec3(0.4, -0.5, 0.6)
        val world = pose.transformPoint(p)
        assertVec(p, pose.inverse().transformPoint(world))
        val other = Pose(Vec3(-1.0, 0.0, 0.5), Quaternion.fromAxisAngle(Vec3.UNIT_Z, 1.1))
        assertVec(pose.transformPoint(other.transformPoint(p)), (pose * other).transformPoint(p))
        assertVec(Vec3(0.0, 0.0, -1.0), Pose.IDENTITY.forward)
        assertVec(pose.rotateVector(Vec3(0.0, 0.0, -1.0)), pose.forward)
        assertTrue(pose.toString().startsWith("Pose"))
    }

    @Test fun covarianceAlongAndProjection() {
        val c = Cov3(4.0, 1.0, 0.5, 9.0, -1.0, 16.0)
        assertEquals(4.0, c.along(Vec3.UNIT_X), eps)
        assertEquals(9.0, c.along(Vec3.UNIT_Y), eps)
        val d = Vec3(1.0, 1.0, 0.0).normalized()
        assertEquals((4.0 + 9.0 + 2.0) / 2, c.along(d), 1e-12)
        assertEquals(29.0, c.trace, eps)
        assertEquals(58.0, (c + c).trace, eps)
        assertEquals(14.5, (c * 0.5).trace, eps)
        // Projection onto the XY plane removes all Z variance.
        val p = c.projectedOntoPlane(Vec3.UNIT_Z)
        assertEquals(0.0, p.zz, eps)
        assertEquals(0.0, p.along(Vec3.UNIT_Z), eps)
        assertEquals(4.0, p.xx, eps)
        assertEquals(1.0, p.xy, eps)
        assertVec(Vec3(4.0 + 1.0, 1.0 + 9.0, 0.5 - 1.0), c.multiply(Vec3(1.0, 1.0, 0.0)))
        assertEquals(3 * 0.04, Cov3.isotropic(0.2).trace, 1e-12)
        assertTrue(c.toString().startsWith("Cov3"))
    }

    @Test fun uncertainPoint() {
        val p = UncertainPoint.of(Vec3.ZERO, 0.003)
        assertEquals(0.003, p.sigmaAlong(Vec3.UNIT_X), 1e-15)
        val q = p.withExtraSigma(0.004)
        assertEquals(0.005, q.sigmaAlong(Vec3.UNIT_Y), 1e-12)
        assertTrue(q.toString().contains("UncertainPoint"))
    }

    @Test fun planeBasics() {
        val plane = Plane.fromPointNormal(Vec3(0.0, 0.0, -2.0), Vec3(0.0, 0.0, 3.0))
        assertEquals(1.0, plane.normal.length, eps)
        assertEquals(1.0, plane.signedDistance(Vec3(5.0, 1.0, -1.0)), eps)
        assertVec(Vec3(5.0, 1.0, -2.0), plane.project(Vec3(5.0, 1.0, -1.0)))
        assertVec(Vec3(0.0, 0.0, -2.0), plane.point)
        assertEquals(-1.0, plane.flipped().signedDistance(Vec3(5.0, 1.0, -1.0)), eps)
        val facing = plane.facing(Vec3(0.0, 0.0, -10.0))
        assertTrue(facing.signedDistance(Vec3(0.0, 0.0, -10.0)) > 0)
        assertEquals(90.0, plane.tiltFromHorizontalDeg(), 1e-9)
        assertEquals(0.0, Plane(Vec3.UP, 0.0).tiltFromHorizontalDeg(), 1e-9)
        assertTrue(plane.toString().startsWith("Plane"))
    }

    @Test fun planeProjectUncertainPoint() {
        val wall = Plane.fromPointNormal(Vec3.ZERO, Vec3.UNIT_Z)
        val p = UncertainPoint(Vec3(1.0, 2.0, 0.05), Cov3.isotropic(0.01))
        val s = wall.project(p)
        assertVec(Vec3(1.0, 2.0, 0.0), s.position)
        assertEquals(0.0, s.covariance.along(Vec3.UNIT_Z), 1e-15)
        assertEquals(1e-4, s.covariance.along(Vec3.UNIT_X), 1e-15)
    }

    @Test fun planeFromPointsAndDegenerate() {
        val pl = Plane.fromPoints(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 1.0), Vec3(0.0, 1.0, 1.0))
        assertNotNull(pl)
        assertEquals(0.0, pl!!.signedDistance(Vec3(3.0, -7.0, 1.0)), eps)
        assertNull(Plane.fromPoints(Vec3.ZERO, Vec3(1.0, 1.0, 1.0), Vec3(2.0, 2.0, 2.0)))
        assertNull(Plane.fromPoints(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO))
    }

    @Test fun verticalWallThroughFloorLine() {
        val wall = Plane.verticalThroughLine(Vec3(0.0, 0.0, -2.0), Vec3(3.0, 0.0, -2.0))!!
        assertEquals(90.0, wall.tiltFromHorizontalDeg(), 1e-9)
        assertEquals(0.0, wall.signedDistance(Vec3(1.0, 2.5, -2.0)), 1e-12)
        // Slightly non-level taps still give a vertical wall.
        val w2 = Plane.verticalThroughLine(Vec3(0.0, 0.01, -2.0), Vec3(3.0, -0.02, -2.0))!!
        assertEquals(90.0, w2.tiltFromHorizontalDeg(), 1e-9)
        assertNull(Plane.verticalThroughLine(Vec3.ZERO, Vec3(0.001, 1.0, 0.0)))
    }

    @Test fun rayPlaneIntersection() {
        val wall = Plane.fromPointNormal(Vec3(0.0, 0.0, -2.0), Vec3.UNIT_Z)
        val ray = Ray(Vec3(0.0, 1.5, 0.0), Vec3(0.3, -0.1, -1.0))
        val hit = wall.intersect(ray)!!
        assertEquals(0.0, wall.signedDistance(hit), 1e-12)
        // Hit lies on the ray.
        val t = wall.intersectDistance(ray)!!
        assertVec(ray.pointAt(t), hit)
        // Parallel ray → null; plane behind → null.
        assertNull(wall.intersect(Ray(Vec3.ZERO, Vec3.UNIT_X)))
        assertNull(wall.intersect(Ray(Vec3.ZERO, Vec3.UNIT_Z)))
        // Grazing (≈88°) refused by default, accepted with a laxer limit.
        val grazing = Ray(Vec3.ZERO, Vec3(1.0, 0.0, -0.03))
        assertNull(wall.intersect(grazing))
        assertNotNull(wall.intersect(grazing, minCosAngle = 0.01))
        assertTrue(ray.toString().startsWith("Ray"))
    }

    @Test fun rayFromPixelHitsProjectedPoint() {
        val intr = CameraIntrinsics(500.0, 500.0, 320.0, 240.0)
        val pose = Pose(Vec3(0.2, 1.4, 0.1), Quaternion.fromAxisAngle(Vec3(0.2, 1.0, 0.1), 0.4))
        val world = pose.transformPoint(Vec3(0.3, -0.2, -1.7))
        // Project the camera-space point with the pinhole model (−Z forward, v down).
        val cam = pose.inverse().transformPoint(world)
        val u = intr.cx + intr.fx * cam.x / -cam.z
        val v = intr.cy - intr.fy * cam.y / -cam.z
        val ray = Ray.fromPixel(pose, intr, u, v)
        val toPoint = (world - ray.origin).normalized()
        assertVec(toPoint, ray.direction, 1e-12)
        // Centre pixel looks straight ahead.
        assertVec(pose.forward, Ray.fromPixel(pose, intr, 320.0, 240.0).direction, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badIntrinsics() { CameraIntrinsics(0.0, 1.0, 0.0, 0.0) }

    @Test fun symmetricEigen() {
        // Rotated diagonal matrix diag(1, 4, 9).
        val q = Quaternion.fromAxisAngle(Vec3(1.0, 2.0, 3.0), 0.9)
        val e = arrayOf(q.rotate(Vec3.UNIT_X), q.rotate(Vec3.UNIT_Y), q.rotate(Vec3.UNIT_Z))
        val lam = doubleArrayOf(1.0, 4.0, 9.0)
        fun m(i: Int, j: Int): Double {
            var s = 0.0
            for (k in 0 until 3) {
                val a = doubleArrayOf(e[k].x, e[k].y, e[k].z)
                s += lam[k] * a[i] * a[j]
            }
            return s
        }
        val eig = SymmetricEigen3.decompose(m(0, 0), m(0, 1), m(0, 2), m(1, 1), m(1, 2), m(2, 2))
        for (k in 0 until 3) {
            assertEquals(lam[k], eig.values[k], 1e-10)
            assertEquals(1.0, kotlin.math.abs(eig.vectors[k] dot e[k]), 1e-10)
        }
        // Already diagonal input converges immediately.
        val d = SymmetricEigen3.decompose(3.0, 0.0, 0.0, 1.0, 0.0, 2.0)
        assertEquals(1.0, d.values[0], eps)
        assertEquals(3.0, d.values[2], eps)
    }
}
