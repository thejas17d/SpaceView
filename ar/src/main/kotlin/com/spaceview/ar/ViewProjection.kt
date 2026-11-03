package com.spaceview.ar

import com.spaceview.engine.Ray
import com.spaceview.engine.Vec3

/**
 * World ↔ view-pixel mapping for one AR frame, built from ARCore's column-major 4×4 view and
 * projection matrices (`Camera.getViewMatrix` / `Camera.getProjectionMatrix`).
 *
 * Pure Kotlin (no `android.opengl.Matrix`) so it is unit-tested on the JVM. Immutable: a snapshot
 * of one frame, safe to hand to another thread or keep for a frozen frame.
 *
 * View pixels: origin top-left, x right, y down, in the size of the AR view ([widthPx] × [heightPx]).
 */
class ViewProjection(
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    /** projection × view, column-major. */
    private val vp = DoubleArray(16)
    private val inv: DoubleArray?

    init {
        require(viewMatrix.size >= 16 && projectionMatrix.size >= 16)
        require(widthPx > 0 && heightPx > 0)
        Mat4.multiply(projectionMatrix.toDoubles(), viewMatrix.toDoubles(), vp)
        inv = Mat4.invert(vp)
    }

    /** Clip-space coordinates (x, y, z, w) of world point [p]. */
    fun toClip(p: Vec3, out: DoubleArray = DoubleArray(4)): DoubleArray {
        for (r in 0 until 4) {
            out[r] = vp[r] * p.x + vp[4 + r] * p.y + vp[8 + r] * p.z + vp[12 + r]
        }
        return out
    }

    /** View pixel of world point [p], or null when it is behind the camera. */
    fun project(p: Vec3): ScreenPoint? {
        val c = toClip(p)
        if (c[3] <= MIN_W) return null
        return clipToScreen(c[0] / c[3], c[1] / c[3])
    }

    /**
     * Projects segment [a]→[b], clipping it against the near side of the camera so a line that
     * passes behind the user is still drawn correctly. Null when the whole segment is behind.
     */
    fun projectSegment(a: Vec3, b: Vec3): Pair<ScreenPoint, ScreenPoint>? {
        val ca = toClip(a)
        val cb = toClip(b)
        val wa = ca[3]
        val wb = cb[3]
        if (wa <= MIN_W && wb <= MIN_W) return null
        if (wa <= MIN_W || wb <= MIN_W) {
            // Move the point behind the camera along the segment to w = MIN_W.
            val t = (MIN_W - wa) / (wb - wa)
            val clipped = DoubleArray(4) { ca[it] + (cb[it] - ca[it]) * t }
            if (wa <= MIN_W) System.arraycopy(clipped, 0, ca, 0, 4) else System.arraycopy(clipped, 0, cb, 0, 4)
        }
        return clipToScreen(ca[0] / ca[3], ca[1] / ca[3]) to clipToScreen(cb[0] / cb[3], cb[1] / cb[3])
    }

    /** World-space ray through view pixel ([xPx], [yPx]), or null if the matrices are singular. */
    fun rayThrough(xPx: Double, yPx: Double): Ray? {
        val m = inv ?: return null
        val nx = 2.0 * xPx / widthPx - 1.0
        val ny = 1.0 - 2.0 * yPx / heightPx
        val near = unproject(m, nx, ny, -1.0) ?: return null
        val far = unproject(m, nx, ny, 1.0) ?: return null
        val dir = far - near
        if (dir.length < 1e-9) return null
        return Ray(near, dir)
    }

    /** Ray through the centre of the view (where the reticle is). */
    fun centreRay(): Ray? = rayThrough(widthPx / 2.0, heightPx / 2.0)

    private fun clipToScreen(nx: Double, ny: Double) =
        ScreenPoint(((nx + 1.0) * 0.5 * widthPx).toFloat(), ((1.0 - ny) * 0.5 * heightPx).toFloat())

    private fun unproject(m: DoubleArray, x: Double, y: Double, z: Double): Vec3? {
        val ox = m[0] * x + m[4] * y + m[8] * z + m[12]
        val oy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val oz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val ow = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (kotlin.math.abs(ow) < 1e-12) return null
        return Vec3(ox / ow, oy / ow, oz / ow)
    }

    private fun FloatArray.toDoubles() = DoubleArray(16) { this[it].toDouble() }

    private companion object {
        /** Points with clip w below this are treated as behind the camera (≈ 1 cm in front). */
        const val MIN_W = 0.01
    }
}

/** A point in view pixels. */
data class ScreenPoint(val x: Float, val y: Float)

/** Column-major 4×4 helpers (same layout as OpenGL / ARCore). */
internal object Mat4 {
    /** out = a × b. [out] must not alias [a] or [b]. */
    fun multiply(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
    }

    /** Inverse by cofactor expansion, or null when singular. */
    fun invert(m: DoubleArray): DoubleArray? {
        val inv = DoubleArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (kotlin.math.abs(det) < 1e-18 || !det.isFinite()) return null
        val s = 1.0 / det
        for (i in 0 until 16) inv[i] *= s
        return inv
    }
}
