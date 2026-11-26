package com.spaceview.engine.rect

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.CaptureCamera
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec2
import com.spaceview.engine.Vec3
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic test scenes for W9 (research §5.2): a rectangle lying on the world plane z = 0 (centred at the origin,
 * width along x, height along y), seen by a pinhole camera (f = 920 px, 1280 × 720, ARCore pixel convention) from
 * [distance] at [tiltDeg] from the normal, rolled by [rollDeg]. Rendered with 3 × 3 supersampling (exact edges), then
 * Gaussian blur and noise.
 */
class SyntheticScene(
    val widthM: Double = 0.297,
    val heightM: Double = 0.21,
    val distance: Double = 0.5,
    val tiltDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
    val rollDeg: Double = 8.0,
    val objectGrey: Double = 205.0,
    val backgroundGrey: Double = 90.0,
    val cornerRadiusM: Double = 0.0,
    val text: Boolean = true,
    val woodTexture: Boolean = true,
    /** A picture inside a frame: inner rectangle (w, h) in m, its grey. */
    val inner: Triple<Double, Double, Double>? = null,
    /** A dark "finger" blob over corner 0 (image space). */
    val finger: Boolean = false,
    /** Only background (no rectangle), with a straight table edge and a shadow. */
    val noRectangle: Boolean = false,
    val offsetX: Double = 0.0,
) {
    val intrinsics = CameraIntrinsics(920.0, 920.0, 640.0, 360.0)
    val camera: CaptureCamera = CaptureCamera(pose(), intrinsics, 1280, 720)

    /** True corners in pixels: top-left, top-right, bottom-right, bottom-left of the rectangle in world coordinates. */
    val trueCornersPx: List<Vec2> by lazy { worldCorners().map { project(it) } }

    fun worldCorners(): List<Vec3> {
        val hx = widthM / 2; val hy = heightM / 2
        return listOf(Vec3(-hx + offsetX, hy, 0.0), Vec3(hx + offsetX, hy, 0.0), Vec3(hx + offsetX, -hy, 0.0), Vec3(-hx + offsetX, -hy, 0.0))
    }

    fun project(p: Vec3): Vec2 {
        val local = camera.pose.rotation.inverse().rotate(p - camera.pose.translation)
        val z = -local.z
        return Vec2(intrinsics.cx + intrinsics.fx * local.x / z, intrinsics.cy - intrinsics.fy * local.y / z)
    }

    private fun pose(): Pose {
        val t = Math.toRadians(tiltDeg)
        val y = Math.toRadians(yawDeg)
        val c = Vec3(-sin(y) * sin(t) * distance, -cos(y) * sin(t) * distance, cos(t) * distance)
        val f = (Vec3.ZERO - c).normalized()
        val hint = if (abs(f.y) > 0.99) Vec3.UNIT_X else Vec3.UNIT_Y
        var r = (f cross hint).normalized()
        if (r.x < 0 && abs(f.y) <= 0.99) r = -r
        var u = (r cross f).normalized()
        val roll = Math.toRadians(rollDeg)
        val r2 = r * cos(roll) + u * sin(roll)
        val u2 = u * cos(roll) - r * sin(roll)
        r = r2; u = u2
        return Pose(c, fromAxes(r, u, -f))
    }

    private fun fromAxes(x: Vec3, y: Vec3, z: Vec3): Quaternion {
        val m00 = x.x; val m10 = x.y; val m20 = x.z
        val m01 = y.x; val m11 = y.y; val m21 = y.z
        val m02 = z.x; val m12 = z.y; val m22 = z.z
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            Quaternion(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            Quaternion((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
        }
    }

    /** The plane coordinates (x, y) seen at pixel (u, v), or null above the horizon. */
    private fun planePoint(u: Double, v: Double, m: DoubleArray, c: Vec3, out: DoubleArray): Boolean {
        val lx = (u - intrinsics.cx) / intrinsics.fx
        val ly = -(v - intrinsics.cy) / intrinsics.fy
        val dx = m[0] * lx + m[1] * ly - m[2]
        val dy = m[3] * lx + m[4] * ly - m[5]
        val dz = m[6] * lx + m[7] * ly - m[8]
        if (dz >= -1e-9) return false
        val t = -c.z / dz
        out[0] = c.x + t * dx
        out[1] = c.y + t * dy
        return true
    }

    private fun grey(x: Double, y: Double): Double {
        var g = backgroundGrey
        if (woodTexture) g += 14 * sin(2 * PI * x / 0.013 + 3 * sin(2 * PI * y / 0.11)) + 6 * sin(2 * PI * y / 0.047)
        if (noRectangle) {
            if (y > 0.08) g = 50.0 + 0.5 * (g - backgroundGrey) // the floor beyond a straight table edge
            if (x + 0.4 * y > 0.12) g -= 30 // a shadow wedge
            return g
        }
        val hx = widthM / 2; val hy = heightM / 2
        val px = x - offsetX
        if (insideRounded(px, y, hx, hy, cornerRadiusM)) {
            g = objectGrey
            val inn = inner
            if (inn != null && abs(px) <= inn.first / 2 && abs(y) <= inn.second / 2) g = inn.third
            if (text && inn == null && abs(px) < hx - 0.02 && abs(y) < hy - 0.02) {
                val row = Math.floorMod(((y + 1) / 0.012).toInt(), 1000)
                val inRow = ((y + 1) % 0.012) < 0.002
                val dash = ((px + 1) * 60 + row * 0.37) % 1.0
                if (inRow && dash < 0.7) g -= 70
            }
        }
        return g
    }

    private fun insideRounded(x: Double, y: Double, hx: Double, hy: Double, r: Double): Boolean {
        if (abs(x) > hx || abs(y) > hy) return false
        if (r <= 0) return true
        val cx = abs(x) - (hx - r)
        val cy = abs(y) - (hy - r)
        return !(cx > 0 && cy > 0 && cx * cx + cy * cy > r * r)
    }

    /** Renders the scene with Gaussian [blurSigma] (px) and noise [noiseSigma] (grey levels). */
    fun render(blurSigma: Double = 0.0, noiseSigma: Double = 2.0, seed: Long = 1): GrayImage {
        val w = 1280; val h = 720
        val q = camera.pose.rotation
        val ex = q.rotate(Vec3.UNIT_X); val ey = q.rotate(Vec3.UNIT_Y); val ez = q.rotate(Vec3.UNIT_Z)
        // World direction = ex·lx + ey·ly + ez·(-(-1))… stored so that d = m·(lx, ly) − ez.
        val m = doubleArrayOf(ex.x, ey.x, ez.x, ex.y, ey.y, ez.y, ex.z, ey.z, ez.z)
        val c = camera.pose.translation
        val img = FloatArray(w * h)
        val p = DoubleArray(2)
        val fp = trueCornersPx[0]
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            var n = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val u = x - 1.0 / 3 + sx / 3.0
                val v = y - 1.0 / 3 + sy / 3.0
                s += if (planePoint(u, v, m, c, p)) grey(p[0], p[1]) else 160.0
                n++
            }
            var g = s / n
            if (finger) {
                val dx = x - (fp.x + 6); val dy = y - (fp.y + 6)
                if (dx * dx / 400.0 + dy * dy / 2500.0 < 1) g = 35.0
            }
            img[y * w + x] = g.toFloat()
        }
        val blurred = if (blurSigma > 0) gaussian(img, w, h, blurSigma) else img
        val r = Random(seed)
        for (i in blurred.indices) blurred[i] = min(255.0, max(0.0, blurred[i] + noiseSigma * r.nextGaussian())).toFloat()
        return GrayImage(w, h, blurred)
    }

    private fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val rad = (3 * sigma).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * rad + 1) { exp(-((it - rad) * (it - rad)) / (2 * sigma * sigma)) }
        val ks = k.sum()
        for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in -rad..rad) s += k[i + rad] * src[y * w + (x + i).coerceIn(0, w - 1)]
            tmp[y * w + x] = s.toFloat()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in -rad..rad) s += k[i + rad] * tmp[(y + i).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s.toFloat()
        }
        return out
    }

    companion object {
        /** RMS and max distance from each true corner to the nearest detected one (px). */
        fun cornerErrors(truth: List<Vec2>, found: List<Vec2>): Pair<Double, Double> {
            var ss = 0.0
            var mx = 0.0
            for (t in truth) {
                val d = found.minOf { it.distanceTo(t) }
                ss += d * d
                mx = max(mx, d)
            }
            return sqrt(ss / truth.size) to mx
        }
    }
}
