package com.spaceview.engine.rect

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Plane
import com.spaceview.engine.Vec2
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** W9: temporal lock and fusion of a detected rectangle. */
class QuadLockTest {
    private val table = Plane.fromPointNormal(Vec3.ZERO, Vec3.UNIT_Z)

    private fun jitter(c: List<Vec2>, r: Random, px: Double) = c.map { Vec2(it.x + px * r.nextGaussian(), it.y + px * r.nextGaussian()) }

    @Test
    fun locksAfterEightSteadyFramesAndFusesWithAnHonestBound() {
        val scene = SyntheticScene(tiltDeg = 20.0)
        val lock = QuadLock(DeviceTier.DEPTH)
        val r = Random(5)
        repeat(QuadLock.STABLE_FRAMES - 1) { assertNull(lock.offer(jitter(scene.trueCornersPx, r, 0.3), 0.3, scene.camera, table, 0.0025)) }
        assertTrue(lock.progress > 0.8)
        val locked = lock.offer(jitter(scene.trueCornersPx, r, 0.3), 0.3, scene.camera, table, 0.0025)
        assertNotNull(locked)
        repeat(20) { lock.offer(jitter(scene.trueCornersPx, r, 0.3), 0.3, scene.camera, table, 0.0025) }
        val face = lock.locked!!.face
        val sides = listOf(face.width, face.height).sortedBy { it.value }
        assertEquals(0.21, sides[0].value, 0.0015)
        assertEquals(0.297, sides[1].value, 0.0015)
        assertTrue(kotlin.math.abs(sides[1].value - 0.297) <= sides[1].bound95)
        assertTrue(lock.locked!!.frames > 10)
        // A single wild frame after the lock is skipped, not a reset.
        lock.offer(scene.trueCornersPx.map { Vec2(it.x + 40, it.y) }, 0.3, scene.camera, table, 0.0025)
        assertEquals(QuadLock.Reject.MOVED, lock.lastReject)
        assertNotNull(lock.locked)
    }

    @Test
    fun aSteepViewNeverLocks() {
        val scene = SyntheticScene(tiltDeg = 55.0)
        val lock = QuadLock()
        repeat(20) { lock.offer(scene.trueCornersPx, 0.3, scene.camera, table, 0.0025) }
        assertNull(lock.locked)
        assertEquals(QuadLock.Reject.TOO_STEEP, lock.lastReject)
    }

    @Test
    fun aNonRectangleNeverLocks() {
        val scene = SyntheticScene()
        val c = scene.trueCornersPx
        val skewed = listOf(c[0], Vec2(c[1].x + 40, c[1].y), c[2], c[3]) // a trapezoid on the table
        val lock = QuadLock()
        repeat(20) { lock.offer(skewed, 0.3, scene.camera, table, 0.0025) }
        assertNull(lock.locked)
        assertEquals(QuadLock.Reject.NOT_RECTANGLE, lock.lastReject)
    }

    @Test
    fun aDriftingQuadRestartsTheCount() {
        val scene = SyntheticScene()
        val lock = QuadLock()
        repeat(20) { i -> lock.offer(scene.trueCornersPx.map { Vec2(it.x + 3.0 * i, it.y) }, 0.3, scene.camera, table, 0.0025) }
        assertNull(lock.locked)
    }

    @Test
    fun aRayMissingThePlaneIsRejected() {
        val scene = SyntheticScene()
        val lock = QuadLock()
        val wall = Plane.fromPointNormal(Vec3(0.0, 5.0, 0.0), Vec3.UNIT_Y) // behind the camera's view
        lock.offer(scene.trueCornersPx, 0.3, scene.camera, wall, 0.0025)
        assertEquals(QuadLock.Reject.NOT_ON_PLANE, lock.lastReject)
    }
}
