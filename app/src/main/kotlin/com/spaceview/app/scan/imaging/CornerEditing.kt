package com.spaceview.app.scan.imaging

import com.spaceview.engine.Vec2
import com.spaceview.engine.rect.CornerRefiner
import com.spaceview.engine.rect.GrayImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The capture photo is in the camera **sensor** orientation; the corner editor (PLAN v3.1 W8) shows it turned by
 * [turns] quarter turns clockwise so the object's top is up. Pure maps between the two pixel frames (continuous
 * coordinates: 0..width, 0..height). JVM-tested.
 */
class QuarterTurns(val turns: Int, val sensorWidth: Int, val sensorHeight: Int) {
    init {
        require(turns in 0..3 && sensorWidth > 0 && sensorHeight > 0)
    }

    /** Size of the turned (display) image. */
    val displayWidth: Int get() = if (turns % 2 == 0) sensorWidth else sensorHeight
    val displayHeight: Int get() = if (turns % 2 == 0) sensorHeight else sensorWidth

    /** Sensor pixel → display pixel. */
    fun toDisplay(u: Float, v: Float): Pair<Float, Float> = when (turns) {
        0 -> u to v
        1 -> (sensorHeight - v) to u
        2 -> (sensorWidth - u) to (sensorHeight - v)
        else -> v to (sensorWidth - u)
    }

    /** Display pixel → sensor pixel (inverse of [toDisplay]). */
    fun toSensor(x: Float, y: Float): Pair<Float, Float> = when (turns) {
        0 -> x to y
        1 -> y to (sensorHeight - x)
        2 -> (sensorWidth - x) to (sensorHeight - y)
        else -> (sensorWidth - y) to x
    }

    /** All four corners (x, y pairs) to display pixels. */
    fun cornersToDisplay(c: FloatArray): FloatArray = FloatArray(8).also { out ->
        for (k in 0 until 4) toDisplay(c[2 * k], c[2 * k + 1]).let { (x, y) -> out[2 * k] = x; out[2 * k + 1] = y }
    }

    /** All four corners (x, y pairs) to sensor pixels. */
    fun cornersToSensor(c: FloatArray): FloatArray = FloatArray(8).also { out ->
        for (k in 0 until 4) toSensor(c[2 * k], c[2 * k + 1]).let { (u, v) -> out[2 * k] = u; out[2 * k + 1] = v }
    }

    companion object {
        /**
         * The turns that put the face's top edge (corners 0–1, as seen from the front) at the top of the screen: the
         * one whose turned "up" vector (bottom edge midpoint → top edge midpoint) points most nearly up the display.
         */
        fun upright(cornersPx: FloatArray, sensorWidth: Int, sensorHeight: Int): QuarterTurns {
            val ux = (cornersPx[0] + cornersPx[2] - cornersPx[4] - cornersPx[6]) / 2
            val uy = (cornersPx[1] + cornersPx[3] - cornersPx[5] - cornersPx[7]) / 2
            // Display "up" is −y. A clockwise quarter turn maps a vector (dx, dy) to (−dy, dx).
            val upness = floatArrayOf(-uy, -ux, uy, ux)
            val best = upness.indices.maxBy { upness[it] }
            return QuarterTurns(best, sensorWidth, sensorHeight)
        }
    }
}

/** Where a snapped corner landed (photo pixels, centre-at-integer like `GrayImage`) and how sure that is. */
class SnappedCorner(val u: Float, val v: Float, val sigmaPx: Double, val subPixel: Boolean)

/**
 * Optional snapping of a dragged corner (PLAN v3.1 W8): the corner moves onto the photo's real corner nearby.
 * - **First choice: W9's sub-pixel [CornerRefiner]** (`engine/rect`): the two sides running from the corner towards its
 *   neighbours are each fitted to the image edge, and the corner is their intersection. Sub-pixel, and it works on
 *   rounded corners. Its σ is the fit's, kept no smaller than [MIN_SIGMA_PX].
 * - **Fallback (the refiner finds no edge): Shi–Tomasi**, the strongest clear corner feature within a few pixels
 *   (whole pixels; σ = 1 px), and only when it clearly is a corner.
 * Built once per editor session from the frozen photo's luminance ([luma], grey levels). Pure (JVM-tested).
 */
class CornerSnap(private val width: Int, private val height: Int, private val luma: FloatArray) {
    init {
        require(width > 0 && height > 0 && luma.size == width * height)
    }

    private val gray = GrayImage(width, height, luma)

    /**
     * The snapped position of corner ([u], [v]) whose sides run towards [prev] and [next] (the neighbouring corners, in
     * order), or null when there is nothing clear to snap to (the corner stays where the finger left it).
     */
    fun snap(u: Float, v: Float, prev: Pair<Float, Float>, next: Pair<Float, Float>, radius: Int = SNAP_RADIUS_PX): SnappedCorner? {
        val corner = Vec2(u.toDouble(), v.toDouble())
        val refined = runCatching {
            CornerRefiner.refineCorner(
                gray, corner, Vec2(prev.first.toDouble(), prev.second.toDouble()), Vec2(next.first.toDouble(), next.second.toDouble()),
                searchPx = radius.toDouble(),
            )
        }.getOrNull()
        if (refined != null && refined.point.x.isFinite() && refined.point.y.isFinite() &&
            hypot(refined.point.x - u, refined.point.y - v) <= radius * MAX_JUMP_FACTOR
        ) {
            return SnappedCorner(refined.point.x.toFloat(), refined.point.y.toFloat(), max(MIN_SIGMA_PX, refined.sigmaPx), subPixel = true)
        }
        return shiTomasi(u, v, radius)
    }

    /** Fallback: the strongest clear Shi–Tomasi corner within [radius] pixels, or null. */
    internal fun shiTomasi(u: Float, v: Float, radius: Int = SNAP_RADIUS_PX): SnappedCorner? {
        val cx = u.roundToInt(); val cy = v.roundToInt()
        var best = 0.0; var bx = -1; var by = -1
        for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius) {
            if (x < 3 || y < 3 || x >= width - 3 || y >= height - 3) continue
            val r = response(x, y)
            if (r > best) { best = r; bx = x; by = y }
        }
        if (bx < 0 || best < MIN_RESPONSE) return null
        return SnappedCorner(bx.toFloat(), by.toFloat(), FALLBACK_SIGMA_PX, subPixel = false)
    }

    /** Smaller eigenvalue of the 5 × 5 structure tensor of central-difference gradients at ([x], [y]). */
    internal fun response(x: Int, y: Int): Double {
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (j in y - 2..y + 2) for (i in x - 2..x + 2) {
            val gx = (l(i + 1, j) - l(i - 1, j)) / 2.0
            val gy = (l(i, j + 1) - l(i, j - 1)) / 2.0
            sxx += gx * gx; syy += gy * gy; sxy += gx * gy
        }
        val tr = (sxx + syy) / 25
        val det = (sxx * syy - sxy * sxy) / (25.0 * 25.0)
        val disc = kotlin.math.sqrt(max(0.0, tr * tr / 4 - det))
        return tr / 2 - disc
    }

    private fun l(x: Int, y: Int): Float = luma[min(height - 1, max(0, y)) * width + min(width - 1, max(0, x))]

    companion object {
        /** Snapping looks this far (photo pixels) around the finger. */
        const val SNAP_RADIUS_PX = 6

        /** A refined corner further than this × the radius from the finger is a different corner: not taken. */
        const val MAX_JUMP_FACTOR = 1.5

        /** The snapped corner's σ is never below this (px): the image itself is sampled at whole pixels. */
        const val MIN_SIGMA_PX = 0.5

        /** σ of a Shi–Tomasi fallback snap (whole pixels). */
        const val FALLBACK_SIGMA_PX = 1.0

        /**
         * A clear corner: the weaker gradient direction still carries at least this mean squared gradient (grey
         * levels²; a step corner of contrast C gives about 0.06 C², so this is ≈ a 26-level corner). Below it, flat
         * areas and straight edges (whose weaker direction is ~0) never pull the corner.
         */
        const val MIN_RESPONSE = 40.0

        /** Rec. 601 luminance (grey levels) of ARGB pixels. */
        fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
            val p = argb[i]
            (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f)
        }

        /** True when two corner sets differ by more than [epsPx] anywhere. */
        fun differ(a: FloatArray, b: FloatArray, epsPx: Float = 0.01f): Boolean = a.indices.any { abs(a[it] - b[it]) > epsPx }
    }
}
