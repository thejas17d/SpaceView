package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 2-D projective transform (3×3, row-major, normalised so m[8] = 1 where possible).
 * Maps (x, y) → ((m0 x + m1 y + m2) / w, (m3 x + m4 y + m5) / w), w = m6 x + m7 y + m8.
 *
 * Used for perspective rectification: the Android layer warps the photo so the front face of a
 * frame/TV becomes a straight-on texture with the measured W:H aspect ratio.
 */
class Homography(m: DoubleArray) {
    /** Row-major 3×3 matrix (copy). */
    val matrix: DoubleArray = m.copyOf()

    init { require(m.size == 9 && m.all { it.isFinite() }) { "Homography needs 9 finite values" } }

    /** Maps a point. Points on the line at infinity (w = 0) map to infinite coordinates. */
    fun map(p: Vec2): Vec2 = map(p.x, p.y)

    /** Maps (x, y). */
    fun map(x: Double, y: Double): Vec2 {
        val m = matrix
        val w = m[6] * x + m[7] * y + m[8]
        return Vec2((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** Inverse transform (e.g. output-pixel → source-pixel for inverse-mapping warps). */
    fun inverse(): Homography {
        val a = matrix
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[2] * a[7] - a[1] * a[8]
        val c02 = a[1] * a[5] - a[2] * a[4]
        val c10 = a[5] * a[6] - a[3] * a[8]
        val c11 = a[0] * a[8] - a[2] * a[6]
        val c12 = a[2] * a[3] - a[0] * a[5]
        val c20 = a[3] * a[7] - a[4] * a[6]
        val c21 = a[1] * a[6] - a[0] * a[7]
        val c22 = a[0] * a[4] - a[1] * a[3]
        val det = a[0] * c00 + a[1] * c10 + a[2] * c20
        require(abs(det) > 1e-300) { "Homography is singular" }
        return Homography(doubleArrayOf(c00, c01, c02, c10, c11, c12, c20, c21, c22).normalisedBy(c22, det))
    }

    /** Row-major floats, e.g. for `android.graphics.Matrix.setValues`. */
    fun toFloatArray(): FloatArray = FloatArray(9) { matrix[it].toFloat() }

    override fun toString(): String = "Homography(${matrix.joinToString()})"

    companion object {
        /**
         * Homography mapping the 4 [src] points exactly onto the 4 [dst] points (DLT with Hartley
         * normalisation, 8×8 linear solve with partial pivoting). Returns null if any 3 points of
         * either set are (nearly) collinear.
         */
        fun fromCorrespondences(src: List<Vec2>, dst: List<Vec2>): Homography? {
            require(src.size == 4 && dst.size == 4) { "Need exactly 4 correspondences" }
            if (hasCollinearTriple(src) || hasCollinearTriple(dst)) return null
            val ts = normaliser(src)
            val td = normaliser(dst)
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val s = apply(ts, src[i]); val d = apply(td, dst[i])
                val x = s.x; val y = s.y; val u = d.x; val v = d.y
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u); b[2 * i] = u
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v); b[2 * i + 1] = v
            }
            val h = LinearSolver.solve(a, b) ?: return null
            val hn = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
            // H = Td⁻¹ · Hn · Ts
            val full = mul(mul(inverseSimilarity(td), hn), ts)
            val scale = if (abs(full[8]) > 1e-12) full[8] else 1.0
            return Homography(DoubleArray(9) { full[it] / scale })
        }

        /**
         * Rectification homography from 4 image corners (top-left, top-right, bottom-right, bottom-left)
         * to an output image of [outWidth] × [outHeight] pixels. Null for a degenerate or non-convex quad.
         * The warp usually needs the [inverse] (output → source) for sampling.
         */
        fun rectify(imageCorners: List<Vec2>, outWidth: Double, outHeight: Double): Homography? {
            require(outWidth > 0 && outHeight > 0)
            if (imageCorners.size != 4 || !isConvexQuad(imageCorners)) return null
            val dst = listOf(Vec2(0.0, 0.0), Vec2(outWidth, 0.0), Vec2(outWidth, outHeight), Vec2(0.0, outHeight))
            return fromCorrespondences(imageCorners, dst)
        }

        /**
         * Output texture size (px) with the measured W:H aspect ratio and the long side = [longSidePx].
         * Each side is at least 1 px.
         */
        fun outputSize(widthM: Double, heightM: Double, longSidePx: Int = 1024): Pair<Int, Int> {
            require(widthM > 0 && heightM > 0 && longSidePx > 0)
            return if (widthM >= heightM) {
                longSidePx to max(1, (longSidePx * heightM / widthM).roundToInt())
            } else {
                max(1, (longSidePx * widthM / heightM).roundToInt()) to longSidePx
            }
        }

        /** True if the 4 points, in order, form a strictly convex quadrilateral (either winding). */
        fun isConvexQuad(p: List<Vec2>): Boolean {
            if (p.size != 4) return false
            var sign = 0
            for (i in 0 until 4) {
                val c = (p[(i + 1) % 4] - p[i]) cross (p[(i + 2) % 4] - p[(i + 1) % 4])
                if (c == 0.0) return false
                val s = if (c > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

        private fun hasCollinearTriple(p: List<Vec2>): Boolean {
            var scale = 0.0
            for (i in 0 until 4) for (j in i + 1 until 4) scale = max(scale, p[i].distanceTo(p[j]))
            if (scale == 0.0) return true
            for (i in 0 until 4) for (j in i + 1 until 4) for (k in j + 1 until 4) {
                val area2 = abs((p[j] - p[i]) cross (p[k] - p[i]))
                if (area2 <= 1e-9 * scale * scale) return true
            }
            return false
        }

        /** Similarity [s, 0, -s·cx; 0, s, -s·cy; 0, 0, 1] moving the centroid to 0 and mean distance to √2. */
        private fun normaliser(p: List<Vec2>): DoubleArray {
            val cx = p.sumOf { it.x } / p.size
            val cy = p.sumOf { it.y } / p.size
            val meanDist = p.sumOf { sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy)) } / p.size
            val s = if (meanDist > 0) sqrt(2.0) / meanDist else 1.0
            return doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
        }

        private fun inverseSimilarity(t: DoubleArray): DoubleArray {
            val s = t[0]
            return doubleArrayOf(1 / s, 0.0, -t[2] / s, 0.0, 1 / s, -t[5] / s, 0.0, 0.0, 1.0)
        }

        private fun apply(t: DoubleArray, p: Vec2) = Vec2(t[0] * p.x + t[2], t[4] * p.y + t[5])

        private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
            val r = idx / 3; val c = idx % 3
            a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
        }

        private fun DoubleArray.normalisedBy(c22: Double, det: Double): DoubleArray {
            // Adjugate / det is the inverse; any non-zero scale is the same homography, so divide by
            // the bottom-right entry when it is usable for a tidy m[8] = 1.
            val s = if (abs(c22) > 1e-12 * abs(det)) c22 else det
            return DoubleArray(9) { this[it] / s }
        }
    }
}

/** Dense linear solver (Gaussian elimination with partial pivoting). */
object LinearSolver {
    /**
     * Solves A·x = b for square A (not modified). Returns null if A is singular to working precision.
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        require(a.size == n && a.all { it.size == n }) { "A must be n×n matching b" }
        val m = Array(n) { a[it].copyOf() }
        val x = b.copyOf()
        var maxAbs = 0.0
        for (row in m) for (v in row) maxAbs = max(maxAbs, abs(v))
        if (maxAbs == 0.0) return null
        val tol = 1e-13 * maxAbs
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) <= tol) return null
            if (piv != col) {
                val tr = m[piv]; m[piv] = m[col]; m[col] = tr
                val tb = x[piv]; x[piv] = x[col]; x[col] = tb
            }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                x[r] -= f * x[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = x[r]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }
}
