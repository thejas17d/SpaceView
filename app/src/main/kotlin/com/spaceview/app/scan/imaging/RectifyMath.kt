package com.spaceview.app.scan.imaging

import com.spaceview.engine.Homography
import com.spaceview.engine.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin maths behind [FrontPhotoRectifier], on packed ARGB `IntArray`s (no Android types, so it is
 * JVM-unit-tested).
 *
 * Pixel convention: pixel (i, j) covers [i, i + 1) × [j, j + 1); its centre is (i + 0.5, j + 0.5). Corner
 * coordinates are in that continuous image space (what a tap / projected point gives).
 */
object RectifyMath {

    /** Where to warp from and to: the planned output size and the (possibly pre-shrunk) source window. */
    class Plan(
        val outWidth: Int,
        val outHeight: Int,
        /** Integer box-filter factors along source x and y applied to the window before warping (1 = none). */
        val prefilterX: Int,
        val prefilterY: Int,
        /** Source window (pixels) that contains the quad, clamped to the image. */
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
    ) {
        /** The larger of the two prefilter factors. */
        val prefilter: Int get() = max(prefilterX, prefilterY)
    }

    /**
     * Plans a rectification of the quad [corners] (any order the engine accepts: clockwise or
     * counter-clockwise on screen, starting at the top-left) to a W:H = [aspectWOverH] image.
     *
     * The output long side is the quad's longest edge (no upscaling beyond the detail the photo has),
     * clamped to [[MIN_LONG_SIDE], [maxLongSide]]. Where the warp would shrink the source by 2:1 or more along
     * source x or y, the source is box-filtered by an integer factor along **that axis** first, so the bilinear
     * warp does not alias, while a direction that is not minified (a strongly foreshortened face) keeps its detail.
     */
    fun plan(imageWidth: Int, imageHeight: Int, corners: List<Vec2>, aspectWOverH: Double, maxLongSide: Int): Plan {
        require(imageWidth > 0 && imageHeight > 0) { "Empty source image" }
        require(corners.size == 4) { "Need 4 corners" }
        require(aspectWOverH.isFinite() && aspectWOverH > 0) { "Aspect must be > 0" }
        require(maxLongSide >= MIN_LONG_SIDE) { "maxLongSide must be >= $MIN_LONG_SIDE" }
        var longestEdge = 0.0
        for (i in 0 until 4) longestEdge = max(longestEdge, corners[i].distanceTo(corners[(i + 1) % 4]))
        val longSide = ceil(longestEdge).toInt().coerceIn(MIN_LONG_SIDE, maxLongSide)
        val (w, h) = Homography.outputSize(aspectWOverH, 1.0, longSide)
        // Per source axis: the largest source step per output pixel; leaves the bilinear warp below 2:1.
        val (stepX, stepY) = sourceSteps(corners, w, h) ?: (longestEdge / longSide).let { it to it }
        val prefilterX = if (stepX >= 2.0) floor(stepX).toInt() else 1
        val prefilterY = if (stepY >= 2.0) floor(stepY).toInt() else 1

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (c in corners) {
            minX = min(minX, c.x); minY = min(minY, c.y); maxX = max(maxX, c.x); maxY = max(maxY, c.y)
        }
        // One pixel of margin for the bilinear neighbours; aligned to the prefilter blocks.
        var left = (floor(minX).toInt() - 1).coerceIn(0, imageWidth - 1)
        var top = (floor(minY).toInt() - 1).coerceIn(0, imageHeight - 1)
        left -= left % prefilterX
        top -= top % prefilterY
        val right = (ceil(maxX).toInt() + 1).coerceIn(left + 1, imageWidth)
        val bottom = (ceil(maxY).toInt() + 1).coerceIn(top + 1, imageHeight)
        return Plan(w, h, prefilterX, prefilterY, left, top, right - left, bottom - top)
    }

    /**
     * Largest |∂x/∂u|, |∂x/∂v| and |∂y/∂u|, |∂y/∂v| of the output → source mapping over the output corners (the
     * extremes of a projective map's derivatives lie on the boundary): source pixels per output pixel along each
     * source axis. Null for a degenerate quad.
     */
    private fun sourceSteps(corners: List<Vec2>, outWidth: Int, outHeight: Int): Pair<Double, Double>? {
        val forward = Homography.rectify(corners, outWidth.toDouble(), outHeight.toDouble()) ?: return null
        val m = runCatching { forward.inverse() }.getOrNull()?.matrix ?: return null
        var sx = 0.0
        var sy = 0.0
        for ((u, v) in listOf(0.0 to 0.0, outWidth.toDouble() to 0.0, 0.0 to outHeight.toDouble(), outWidth.toDouble() to outHeight.toDouble())) {
            val wq = m[6] * u + m[7] * v + m[8]
            if (wq == 0.0 || !wq.isFinite()) return null
            val x = (m[0] * u + m[1] * v + m[2]) / wq
            val y = (m[3] * u + m[4] * v + m[5]) / wq
            sx = max(sx, max(abs((m[0] - x * m[6]) / wq), abs((m[1] - x * m[7]) / wq)))
            sy = max(sy, max(abs((m[3] - y * m[6]) / wq), abs((m[4] - y * m[7]) / wq)))
        }
        return if (sx.isFinite() && sy.isFinite()) sx to sy else null
    }

    /**
     * Homography from output pixels to **window** pixels (the cropped, prefiltered source), or null when the
     * quad is degenerate / not convex.
     */
    fun outputToWindow(plan: Plan, corners: List<Vec2>): Homography? {
        val sx = plan.prefilterX.toDouble()
        val sy = plan.prefilterY.toDouble()
        val local = corners.map { Vec2((it.x - plan.cropLeft) / sx, (it.y - plan.cropTop) / sy) }
        val forward = Homography.rectify(local, plan.outWidth.toDouble(), plan.outHeight.toDouble()) ?: return null
        return runCatching { forward.inverse() }.getOrNull()
    }

    /**
     * Inverse-mapped bilinear warp: for each output pixel centre, maps through [outToSrc] (row-major 3×3)
     * into [src] (continuous pixel space) and samples bilinearly; points outside are clamped to the edge.
     * The projective division is computed incrementally along each row (one divide per pixel).
     */
    fun warp(src: IntArray, srcWidth: Int, srcHeight: Int, outToSrc: DoubleArray, outWidth: Int, outHeight: Int): IntArray {
        require(src.size >= srcWidth * srcHeight && outToSrc.size == 9)
        val out = IntArray(outWidth * outHeight)
        val m = outToSrc
        val maxX = (srcWidth - 1).toDouble()
        val maxY = (srcHeight - 1).toDouble()
        for (j in 0 until outHeight) {
            val y = j + 0.5
            // Values at x = 0.5, stepped by the x coefficients.
            var nx = m[0] * 0.5 + m[1] * y + m[2]
            var ny = m[3] * 0.5 + m[4] * y + m[5]
            var nw = m[6] * 0.5 + m[7] * y + m[8]
            val row = j * outWidth
            for (i in 0 until outWidth) {
                val inv = 1.0 / nw
                // Continuous → pixel-index space (centres at integers).
                var sx = nx * inv - 0.5
                var sy = ny * inv - 0.5
                if (!(sx >= 0.0)) sx = 0.0 else if (sx > maxX) sx = maxX
                if (!(sy >= 0.0)) sy = 0.0 else if (sy > maxY) sy = maxY
                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = if (x0 + 1 < srcWidth) x0 + 1 else x0
                val y1 = if (y0 + 1 < srcHeight) y0 + 1 else y0
                // 8-bit fixed-point weights.
                val ax = ((sx - x0) * 256.0 + 0.5).toInt()
                val ay = ((sy - y0) * 256.0 + 0.5).toInt()
                out[row + i] = bilerp(src[y0 * srcWidth + x0], src[y0 * srcWidth + x1], src[y1 * srcWidth + x0], src[y1 * srcWidth + x1], ax, ay)
                nx += m[0]; ny += m[3]; nw += m[6]
            }
        }
        return out
    }

    /** Integer box-filter downscale by [factor] (partial blocks at the right/bottom edge are averaged too). */
    fun downsampleBox(src: IntArray, width: Int, height: Int, factor: Int): Triple<IntArray, Int, Int> =
        downsampleBox(src, width, height, factor, factor)

    /** Integer box-filter downscale by [factorX] × [factorY] (partial blocks at the right/bottom edge are averaged too). */
    fun downsampleBox(src: IntArray, width: Int, height: Int, factorX: Int, factorY: Int): Triple<IntArray, Int, Int> {
        require(factorX >= 1 && factorY >= 1)
        if (factorX == 1 && factorY == 1) return Triple(src, width, height)
        val w = (width + factorX - 1) / factorX
        val h = (height + factorY - 1) / factorY
        val out = IntArray(w * h)
        val a = IntArray(w); val r = IntArray(w); val g = IntArray(w); val b = IntArray(w); val n = IntArray(w)
        for (oy in 0 until h) {
            a.fill(0); r.fill(0); g.fill(0); b.fill(0); n.fill(0)
            val yEnd = min(height, (oy + 1) * factorY)
            for (y in oy * factorY until yEnd) {
                val rowOff = y * width
                for (x in 0 until width) {
                    val p = src[rowOff + x]
                    val o = x / factorX
                    a[o] += p ushr 24; r[o] += (p shr 16) and 0xFF; g[o] += (p shr 8) and 0xFF; b[o] += p and 0xFF; n[o]++
                }
            }
            val outRow = oy * w
            for (o in 0 until w) {
                val c = n[o]; val half = c / 2
                out[outRow + o] = (((a[o] + half) / c) shl 24) or (((r[o] + half) / c) shl 16) or (((g[o] + half) / c) shl 8) or ((b[o] + half) / c)
            }
        }
        return Triple(out, w, h)
    }

    /** Full pipeline on a pixel buffer: plan → crop → prefilter → warp. Null for a degenerate quad. */
    fun rectify(
        pixels: IntArray,
        width: Int,
        height: Int,
        corners: List<Vec2>,
        aspectWOverH: Double,
        maxLongSide: Int,
    ): Triple<IntArray, Int, Int>? {
        val plan = plan(width, height, corners, aspectWOverH, maxLongSide)
        val crop = IntArray(plan.cropWidth * plan.cropHeight)
        for (y in 0 until plan.cropHeight) {
            System.arraycopy(pixels, (plan.cropTop + y) * width + plan.cropLeft, crop, y * plan.cropWidth, plan.cropWidth)
        }
        return warpPlanned(crop, plan, corners)
    }

    /** Prefilter + warp of an already-cropped window (the Android side fills the window with getPixels). */
    fun warpPlanned(window: IntArray, plan: Plan, corners: List<Vec2>): Triple<IntArray, Int, Int>? {
        val h = outputToWindow(plan, corners) ?: return null
        val (src, sw, sh) = downsampleBox(window, plan.cropWidth, plan.cropHeight, plan.prefilterX, plan.prefilterY)
        return Triple(warp(src, sw, sh, h.matrix, plan.outWidth, plan.outHeight), plan.outWidth, plan.outHeight)
    }

    /** Bilinear blend of 4 ARGB pixels with weights [ax], [ay] in 0..256 (fixed point, rounded; unrolled for speed). */
    internal fun bilerp(p00: Int, p10: Int, p01: Int, p11: Int, ax: Int, ay: Int): Int {
        val bx = 256 - ax
        val by = 256 - ay
        val w00 = bx * by
        val w10 = ax * by
        val w01 = bx * ay
        val w11 = ax * ay // the four weights sum to 65536
        val a = ((p00 ushr 24) * w00 + (p10 ushr 24) * w10 + (p01 ushr 24) * w01 + (p11 ushr 24) * w11 + 32768) ushr 16
        val r = (((p00 shr 16) and 0xFF) * w00 + ((p10 shr 16) and 0xFF) * w10 +
            ((p01 shr 16) and 0xFF) * w01 + ((p11 shr 16) and 0xFF) * w11 + 32768) ushr 16
        val g = (((p00 shr 8) and 0xFF) * w00 + ((p10 shr 8) and 0xFF) * w10 +
            ((p01 shr 8) and 0xFF) * w01 + ((p11 shr 8) and 0xFF) * w11 + 32768) ushr 16
        val b = ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 + (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11 + 32768) ushr 16
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Smallest output long side (px). */
    const val MIN_LONG_SIDE = 16
}
