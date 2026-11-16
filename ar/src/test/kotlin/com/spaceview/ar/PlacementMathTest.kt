package com.spaceview.ar

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** §3.9 PlacementMathTest. */
class PlacementMathTest {
    private val sofa = PlacedItem(2.0, 0.85, 0.9, BoxOrigin.BOTTOM_CENTER)
    private val frame = PlacedItem(0.6, 0.4, 0.03, BoxOrigin.BACK_CENTER)

    private fun close(a: Vec3, b: Vec3, eps: Double = 1e-9) = assertTrue("$a vs $b", a.approxEquals(b, eps))

    @Test fun footprintFacesTheCamera() {
        val hit = Vec3(1.0, -1.4, -2.0)
        for (cam in listOf(Vec3(1.0, 0.0, 0.0), Vec3(3.0, 0.0, -2.0), Vec3(-1.0, 0.2, -4.0))) {
            val s = PlacementMath.horizontalSurfacePose(hit, cam)
            val front = s.rotateVector(Vec3.UNIT_Z)
            val toCam = Vec3(cam.x - hit.x, 0.0, cam.z - hit.z).normalized()
            close(front, toCam)
            close(s.rotateVector(Vec3.UNIT_Y), Vec3.UP)
        }
        // Camera straight above: front stays world +Z.
        close(PlacementMath.horizontalSurfacePose(hit, Vec3(1.0, 0.0, -2.0)).rotateVector(Vec3.UNIT_Z), Vec3.UNIT_Z)
    }

    @Test fun floorItemStandsOnTheSurface() {
        val s = PlacementMath.horizontalSurfacePose(Vec3(0.0, -1.4, -2.0), Vec3.ZERO)
        val m = PlacementMath.modelPose(s, sofa, SurfaceTarget.FLOOR, 0.0)
        // BOTTOM_CENTER origin sits exactly on the hit.
        close(m.translation, Vec3(0.0, -1.4, -2.0))
        // A BACK_CENTER item on a table: its bottom face on the table, its back at the touch point's back.
        val tv = PlacedItem(1.2, 0.7, 0.08, BoxOrigin.BACK_CENTER)
        val t = PlacementMath.modelPose(s, tv, SurfaceTarget.TABLE, 0.0)
        val bottomCentre = t.transformPoint(Vec3(0.0, -0.35, 0.04))
        close(bottomCentre, Vec3(0.0, -1.4, -2.0))
    }

    @Test fun backCentreItemIsFlushOnTheWall() {
        val wallN = Vec3(0.0, 0.0, 1.0)
        val hit = Vec3(0.3, 0.2, -2.5)
        val s = PlacementMath.wallSurfacePose(hit, wallN, Vec3.ZERO)!!
        val m = PlacementMath.modelPose(s, frame, SurfaceTarget.WALL, 0.0)
        close(m.translation, hit)
        // Every back-face corner lies on the wall plane (distance 0), the front 3 cm in front of it.
        for (sx in listOf(-0.3, 0.3)) for (sy in listOf(-0.2, 0.2)) {
            val back = m.transformPoint(Vec3(sx, sy, 0.0))
            assertEquals(0.0, (back - hit) dot wallN, 1e-12)
            val front = m.transformPoint(Vec3(sx, sy, 0.03))
            assertEquals(0.03, (front - hit) dot wallN, 1e-12)
        }
        // Upright: the item's +Y is world up.
        close(m.rotateVector(Vec3.UNIT_Y), Vec3.UP)
        // A BOTTOM_CENTER item hung on a wall is flush too.
        val box = PlacedItem(0.5, 0.3, 0.2, BoxOrigin.BOTTOM_CENTER)
        val b = PlacementMath.modelPose(s, box, SurfaceTarget.WALL, 0.0)
        assertEquals(0.0, (b.transformPoint(Vec3(0.0, 0.15, -0.1)) - hit) dot wallN, 1e-12)
        close(b.transformPoint(Vec3(0.0, 0.15, -0.1)), hit)
    }

    @Test fun wallNormalIsFlippedToFaceTheCamera() {
        val s = PlacementMath.wallSurfacePose(Vec3(0.0, 0.0, -2.0), Vec3(0.0, 0.0, -1.0), Vec3.ZERO)!!
        close(s.rotateVector(Vec3.UNIT_Z), Vec3.UNIT_Z)
        assertNull("a floor is not a wall", PlacementMath.wallSurfacePose(Vec3.ZERO, Vec3.UP, Vec3(0.0, 1.0, 1.0)))
    }

    @Test fun surfaceClassification() {
        assertTrue(PlacementMath.isHorizontal(Vec3(0.0, 1.0, 0.1)))
        assertFalse(PlacementMath.isHorizontal(Vec3(0.0, -1.0, 0.0)))
        assertTrue(PlacementMath.isVertical(Vec3(1.0, 0.1, 0.0)))
        assertFalse(PlacementMath.isVertical(Vec3(1.0, 1.0, 0.0)))
        assertTrue(PlacementMath.accepts(SurfaceTarget.WALL_FALLBACK, Vec3.UNIT_X))
        assertFalse(PlacementMath.accepts(SurfaceTarget.FLOOR, Vec3.UNIT_X))
    }

    @Test fun eachNudgeStepIsOneCentimetreAlongTheSurface() {
        // Camera 1.4 m above the floor, looking down 30° towards −Z.
        val cam = Pose(Vec3(0.0, 0.0, 0.0), Quaternion.fromAxisAngle(Vec3.UNIT_X, -PI / 6))
        val floorRight = PlacementMath.nudgeDelta(SurfaceTarget.FLOOR, Vec3.UP, cam, 1, 0)
        close(floorRight, Vec3(0.01, 0.0, 0.0), 1e-12)
        val floorUp = PlacementMath.nudgeDelta(SurfaceTarget.FLOOR, Vec3.UP, cam, 0, 1)
        close(floorUp, Vec3(0.0, 0.0, -0.01), 1e-12) // away from the user, on the floor
        val wallUp = PlacementMath.nudgeDelta(SurfaceTarget.WALL, Vec3.UNIT_Z, cam, 0, -3)
        close(wallUp, Vec3(0.0, -0.03, 0.0), 1e-12)
        val wallRight = PlacementMath.nudgeDelta(SurfaceTarget.WALL, Vec3.UNIT_Z, cam, 2, 0)
        close(wallRight, Vec3(0.02, 0.0, 0.0), 1e-12)
        assertEquals(0.0, wallRight dot Vec3.UNIT_Z, 1e-15)
    }

    @Test fun rotate90FourTimesIsIdentity() {
        var spin = 0.0
        repeat(4) { spin = PlacementMath.rotate90(spin) }
        assertEquals(0.0, spin, 1e-12)
        val s = PlacementMath.horizontalSurfacePose(Vec3(0.0, -1.0, -2.0), Vec3.ZERO)
        val a = PlacementMath.modelPose(s, sofa, SurfaceTarget.FLOOR, 0.0)
        val b = PlacementMath.modelPose(s, sofa, SurfaceTarget.FLOOR, PlacementMath.rotate90(PlacementMath.rotate90(PlacementMath.rotate90(PlacementMath.rotate90(0.0)))))
        close(a.rotateVector(Vec3.UNIT_X), b.rotateVector(Vec3.UNIT_X), 1e-12)
        // A quarter turn on a wall rolls a frame portrait ↔ landscape.
        val w = PlacementMath.wallSurfacePose(Vec3.ZERO, Vec3.UNIT_Z, Vec3(0.0, 0.0, 2.0))!!
        val r = PlacementMath.modelPose(w, frame, SurfaceTarget.WALL, PlacementMath.rotate90(0.0))
        close(r.rotateVector(Vec3.UNIT_X), Vec3.UP)
        close(r.rotateVector(Vec3.UNIT_Z), Vec3.UNIT_Z)
        assertEquals(PI, PlacementMath.wrap(-PI), 1e-12)
        assertEquals(-PI / 2, PlacementMath.wrap(3 * PI / 2), 1e-12)
    }

    @Test fun scaleIsAlwaysOne() {
        // Every pose is rigid: distances between transformed points equal the item's own.
        val s = PlacementMath.wallSurfacePose(Vec3(1.0, 1.0, -3.0), Vec3(0.6, 0.0, 0.8), Vec3.ZERO)!!
        for (spin in listOf(0.0, 0.3, PI / 2, -2.0)) {
            val m = PlacementMath.modelPose(s, frame, SurfaceTarget.WALL, spin)
            assertEquals(0.6, m.transformPoint(Vec3(-0.3, 0.0, 0.0)).distanceTo(m.transformPoint(Vec3(0.3, 0.0, 0.0))), 1e-12)
            assertEquals(0.4, m.transformPoint(Vec3(0.0, -0.2, 0.0)).distanceTo(m.transformPoint(Vec3(0.0, 0.2, 0.0))), 1e-12)
            assertEquals(1.0, m.rotateVector(Vec3(0.0, 0.0, 1.0)).length, 1e-12)
        }
        assertNotNull(s)
    }

    @Test fun axesToQuaternionRoundTrips() {
        val q = Quaternion.fromAxisAngle(Vec3(0.3, -0.5, 0.8), 2.4)
        val back = PlacementMath.fromAxes(q.rotate(Vec3.UNIT_X), q.rotate(Vec3.UNIT_Y), q.rotate(Vec3.UNIT_Z))
        for (v in listOf(Vec3.UNIT_X, Vec3.UNIT_Y, Vec3(0.2, 0.7, -0.1))) close(back.rotate(v), q.rotate(v), 1e-12)
        // The other three branches of the conversion.
        for (axis in listOf(Vec3.UNIT_X, Vec3.UNIT_Y, Vec3.UNIT_Z)) {
            val p = Quaternion.fromAxisAngle(axis, PI)
            val b = PlacementMath.fromAxes(p.rotate(Vec3.UNIT_X), p.rotate(Vec3.UNIT_Y), p.rotate(Vec3.UNIT_Z))
            close(b.rotate(Vec3(0.1, 0.2, 0.3)), p.rotate(Vec3(0.1, 0.2, 0.3)), 1e-12)
        }
    }
}
