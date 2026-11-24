package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class VerticalPlaneAimTest {
    private val camera = Vec3(0.0, 1.5, 0.0)

    /** Base of a door 3 m straight ahead, on the floor. */
    private val base = Vec3(0.0, 0.0, -3.0)

    /** Forward direction pitched up by [pitchDeg] (yawed by [yawDeg] to the left). */
    private fun forward(pitchDeg: Double, yawDeg: Double = 0.0): Vec3 {
        val p = Math.toRadians(pitchDeg)
        val y = Math.toRadians(yawDeg)
        return Vec3(-sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    @Test
    fun tiltingUp60DegreesKeepsThePointOnTheVerticalAboveTheBase() {
        val f = forward(60.0)
        val hit = VerticalPlaneAim.intersect(base, Ray(camera, f), f) as VerticalPlaneAim.Hit
        assertEquals(1.5 + 3.0 * tan(Math.toRadians(60.0)), hit.point.y, 1e-9)
        assertEquals(0.0, hit.point.x, 1e-9)
        assertEquals(-3.0, hit.point.z, 1e-9)
        assertEquals(Math.toRadians(60.0), hit.grazingRad, 1e-9)
        assertTrue(hit.normal.approxEquals(Vec3(0.0, 0.0, 1.0), 1e-12))
    }

    @Test
    fun tiltingDownReachesTheFloorBelowTheCamera() {
        val f = forward(-26.565)
        val hit = VerticalPlaneAim.intersect(base, Ray(camera, f), f) as VerticalPlaneAim.Hit
        assertEquals(0.0, hit.point.y, 1e-3)
    }

    @Test
    fun yawedViewStillMeasuresTheHeightOfTheAimedPoint() {
        // Looking 20° to the left at 30° up: the plane turns to face the camera, the point keeps the ray's height.
        val f = forward(30.0, 20.0)
        val hit = VerticalPlaneAim.intersect(base, Ray(camera, f), f) as VerticalPlaneAim.Hit
        val n = VerticalPlaneAim.planeNormal(f)!!
        assertEquals(0.0, (hit.point - base) dot n, 1e-9)
        assertEquals(1.5 + hit.range * sin(Math.toRadians(30.0)), hit.point.y, 1e-9)
    }

    @Test
    fun beyond75DegreesIsRefusedAsTooSteep() {
        val f = forward(80.0)
        val r = VerticalPlaneAim.intersect(base, Ray(camera, f), f)
        assertEquals(VerticalPlaneAim.Refused(VerticalPlaneAim.Refusal.TOO_STEEP), r)
        val up = Vec3.UP
        assertEquals(VerticalPlaneAim.Refused(VerticalPlaneAim.Refusal.TOO_STEEP), VerticalPlaneAim.intersect(base, Ray(camera, up), up))
        assertNull(VerticalPlaneAim.planeNormal(up))
    }

    @Test
    fun lookingAwayFromTheBaseHasNoIntersection() {
        val f = forward(10.0, 180.0)
        assertEquals(
            VerticalPlaneAim.Refused(VerticalPlaneAim.Refusal.NO_INTERSECTION),
            VerticalPlaneAim.intersect(base, Ray(camera, f), f),
        )
        val bad = Vec3(Double.NaN, 0.0, 0.0)
        assertEquals(
            VerticalPlaneAim.Refused(VerticalPlaneAim.Refusal.NO_INTERSECTION),
            VerticalPlaneAim.intersect(bad, Ray(camera, f), f),
        )
    }

    @Test
    fun aCentreRayOffTheForwardAxisGrazingAwayIsRefused() {
        // Forward is level but the ray points sideways past 90° from it: dn <= 0.
        val f = forward(0.0)
        val r = VerticalPlaneAim.intersect(base, Ray(camera, Vec3(1.0, 0.0, 0.01)), f)
        assertEquals(VerticalPlaneAim.Refused(VerticalPlaneAim.Refusal.TOO_STEEP), r)
    }

    @Test
    fun sigmaGrowsAsTheRayGrazes() {
        val aim = 0.004
        val level = VerticalPlaneAim.intersect(base, Ray(camera, forward(0.0)), forward(0.0)) as VerticalPlaneAim.Hit
        val steep = VerticalPlaneAim.intersect(base, Ray(camera, forward(60.0)), forward(60.0)) as VerticalPlaneAim.Hit
        val sLevel = VerticalPlaneAim.verticalSigma(level, aim, 0.01)
        val sSteep = VerticalPlaneAim.verticalSigma(steep, aim, 0.01)
        assertEquals(3.0 * aim, sLevel, 1e-9) // head-on: range × aim, the base offset does not leak
        // At 60°: range 6 m, ÷ cos 60° → 12 m × aim, plus 1 cm × tan 60° from the base.
        val expected = kotlin.math.sqrt((6.0 * aim / 0.5) * (6.0 * aim / 0.5) + (0.01 * tan(Math.toRadians(60.0))) * (0.01 * tan(Math.toRadians(60.0))))
        assertEquals(expected, sSteep, 1e-9)
        assertTrue(sSteep > 3 * sLevel)
    }
}
