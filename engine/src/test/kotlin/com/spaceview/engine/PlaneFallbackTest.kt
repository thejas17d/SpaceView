package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** M4 wall fallback: `Plane.verticalThroughFloorLine` (§3.9). */
class PlaneFallbackTest {
    private val floor = Plane.fromPointNormal(Vec3(0.0, -1.4, 0.0), Vec3.UP)
    private val camera = Vec3(0.3, 0.0, 1.0)

    @Test fun normalIsHorizontalAndFacesTheCamera() {
        // Wall along X at z = -2 (taps slightly noisy in height).
        val p = Plane.verticalThroughFloorLine(Vec3(-0.5, -1.39, -2.0), Vec3(0.6, -1.41, -2.0), camera, floor)
        assertNotNull(p)
        p!!
        assertEquals(0.0, p.normal.y, 1e-12)
        assertTrue(p.signedDistance(camera) > 0)
        assertEquals(1.0, p.normal.z, 1e-9)
        assertEquals(0.0, p.signedDistance(Vec3(0.0, 1.0, -2.0)), 1e-9)
        // The other tap order and a camera on the other side flip consistently.
        val q = Plane.verticalThroughFloorLine(Vec3(0.6, -1.4, -2.0), Vec3(-0.5, -1.4, -2.0), Vec3(0.0, 0.0, -5.0), floor)!!
        assertEquals(-1.0, q.normal.z, 1e-9)
        assertTrue(q.signedDistance(Vec3(0.0, 0.0, -5.0)) > 0)
    }

    @Test fun diagonalWall() {
        val p = Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, -2.0), Vec3(1.0, -1.4, -3.0), Vec3.ZERO, floor)!!
        assertEquals(0.0, p.normal.y, 1e-12)
        assertEquals(0.0, p.normal dot Vec3(1.0, 0.0, -1.0).normalized(), 1e-12)
        assertTrue(p.signedDistance(Vec3.ZERO) > 0)
    }

    @Test fun tooShortLineIsRejected() {
        assertNull(Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, -2.0), Vec3(0.29, -1.4, -2.0), camera, floor))
        assertNotNull(Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, -2.0), Vec3(0.31, -1.4, -2.0), camera, floor))
        // Length counts only along the floor: a vertical offset does not make a line long enough.
        assertNull(Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, -2.0), Vec3(0.2, -1.38, -2.0), camera, floor))
    }

    @Test fun pointsOffTheFloorAreRejected() {
        assertNull(Plane.verticalThroughFloorLine(Vec3(0.0, -1.3, -2.0), Vec3(1.0, -1.4, -2.0), camera, floor))
        assertNull(Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, -2.0), Vec3(1.0, -0.6, -2.0), camera, floor))
    }

    @Test fun tiltedFloorOrNonFiniteInputIsRejected() {
        val ramp = Plane.fromPointNormal(Vec3(0.0, -1.4, 0.0), Vec3(0.0, 1.0, 0.3))
        assertTrue(ramp.tiltFromHorizontalDeg() > 10)
        assertNull(Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, 0.0), Vec3(1.0, -1.4, 0.0), camera, ramp))
        assertNull(Plane.verticalThroughFloorLine(Vec3(Double.NaN, -1.4, 0.0), Vec3(1.0, -1.4, 0.0), camera, floor))
        // A downward-facing floor normal still gives a vertical wall.
        val down = floor.flipped()
        val p = Plane.verticalThroughFloorLine(Vec3(0.0, -1.4, -2.0), Vec3(1.0, -1.4, -2.0), camera, down)!!
        assertTrue(abs(p.normal.y) < 1e-12 && p.signedDistance(camera) > 0)
    }
}
