package com.spaceview.app.scan

import com.spaceview.app.scan.imaging.RectifyMath
import com.spaceview.ar.CornerFit
import com.spaceview.ar.CpuImageCamera
import com.spaceview.ar.ImageProjection
import com.spaceview.ar.ScanComposer
import com.spaceview.ar.ScanShape
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Ray
import com.spaceview.engine.Vec2
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End to end across `:ar` and the rectifier (M3 review, missing test 5): a quartered face on a wall is
 * rendered into a synthetic CPU image from a landscape and a portrait sensor pose, its corners are
 * projected with [ImageProjection] in the order [ScanComposer] gives, and the rectified photo must come
 * out upright and not mirrored (red top-left, green top-right, blue bottom-left, white bottom-right).
 */
class FrontPhotoOrientationTest {
    private val intr = CameraIntrinsics(500.0, 500.0, 320.0, 240.0)
    private val camera = CpuImageCamera(intr, 640, 480)

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** Frame on the wall z = −1: x 0..0.6, y 1.0..1.4. */
    private fun colourAt(p: Vec3): Int {
        if (p.x < 0 || p.x > 0.6 || p.y < 1.0 || p.y > 1.4) return 0xFF000000.toInt()
        val left = p.x < 0.3
        val top = p.y > 1.2
        return when {
            top && left -> red
            top -> green
            left -> blue
            else -> white
        }
    }

    private fun render(pose: Pose): IntArray = IntArray(640 * 480) { i ->
        val u = i % 640 + 0.5
        val v = i / 640 + 0.5
        val ray = Ray.fromPixel(pose, intr, u, v)
        val t = (-1.0 - ray.origin.z) / ray.direction.z
        if (t <= 0) 0 else colourAt(ray.pointAt(t))
    }

    private fun checkUpright(pose: Pose) {
        val tapped = listOf(Vec3(0.0, 1.4, -1.0), Vec3(0.6, 1.4, -1.0), Vec3(0.6, 1.0, -1.0))
        val choice = ScanComposer.chooseFace(ScanShape.FLAT, tapped, pose.translation, pose.forward)!!
        val face = ScanComposer.face(ScanShape.FLAT, tapped, choice)!!
        val px = FloatArray(8)
        assertEquals(CornerFit.INSIDE, ImageProjection.fitCorners(face.corners, pose, camera, px))
        val corners = List(4) { Vec2(px[it * 2].toDouble(), px[it * 2 + 1].toDouble()) }
        val (out, w, h) = RectifyMath.rectify(render(pose), 640, 480, corners, 0.6 / 0.4, 300)!!
        assertEquals(1.5, w.toDouble() / h, 0.02)
        fun at(fx: Double, fy: Double) = out[(fy * h).toInt() * w + (fx * w).toInt()]
        assertEquals("top-left", red, at(0.25, 0.25))
        assertEquals("top-right", green, at(0.75, 0.25))
        assertEquals("bottom-left", blue, at(0.25, 0.75))
        assertEquals("bottom-right", white, at(0.75, 0.75))
    }

    @Test
    fun landscapeHoldIsUpright() = checkUpright(Pose(Vec3(0.3, 1.2, 0.0)))

    @Test
    fun portraitHoldIsUpright() = checkUpright(Pose(Vec3(0.3, 1.2, 0.0), Quaternion.fromAxisAngle(Vec3.UNIT_Z, -Math.PI / 2)))

    @Test
    fun obliqueViewIsUpright() =
        checkUpright(Pose(Vec3(0.6, 1.3, -0.1), Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(14.0))))
}
