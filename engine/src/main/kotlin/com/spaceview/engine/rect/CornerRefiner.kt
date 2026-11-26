package com.spaceview.engine.rect

import com.spaceview.engine.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An image line through [point] with unit [direction] (a refined side of a quad).
 *
 * @property rmsPx RMS distance of the edge samples from the line (px).
 * @property samples edge samples the line was fitted to.
 */
class EdgeLine(val point: Vec2, val direction: Vec2, val rmsPx: Double, val samples: Int) {
    /** Unit normal (direction rotated by +90°). */
    val normal: Vec2 get() = Vec2(-direction.y, direction.x)

    /** Signed distance of [p] from the line (px). */
    fun distance(p: Vec2): Double = (p.x - point.x) * normal.x + (p.y - point.y) * normal.y

    /** 1σ position of the line near its ends (px): the fitted line's endpoint uncertainty, at least [MIN_SIGMA_PX]. */
    val endSigmaPx: Double get() = max(MIN_SIGMA_PX, rmsPx * sqrt(4.0 / max(samples, 1)))

    /** Intersection with [o], or null when (nearly) parallel. */
    fun intersect(o: EdgeLine): Vec2? {
        val den = direction cross o.direction
        if (abs(den) < 1e-6) return null
        val t = ((o.point - point) cross o.direction) / den
        return point + direction * t
    }

    companion object {
        /** Floor of a refined line's σ (px): sampling, interpolation and lens residuals. */
        const val MIN_SIGMA_PX = 0.15
    }
}

/**
 * A sub-pixel corner: the intersection of its two refined sides.
 *
 * @property sigmaPx 1σ position error of the corner (px), from the two lines' fits and the angle between them.
 */
class RefinedCorner(val point: Vec2, val sigmaPx: Double)

/**
 * **Sub-pixel corner refiner** (W9; shared with W8's "Adjust corners" snap). Corners are not found as blobs of
 * intensity (Shi–Tomasi gives whole pixels and fails on rounded corners): each **side** is refined instead.
 * - Along the side, skipping [END_SKIP] of its length at each end (rounded corners, fingers), the image is sampled
 *   across the edge, ± [searchPx] around the approximate line;
 * - the gradient-magnitude peak of each profile is located to sub-pixel precision by a parabola through its 3 top
 *   samples (Devernay-style);
 * - a line is fitted to those edge points with Tukey re-weighting (robust to text and glare touching the edge);
 * - the corner is the intersection of its two sides' lines (for a rounded corner: the virtual corner, which is what
 *   W × H means).
 *
 * Pure; thread-safe (no state). Typical accuracy on a sharp straight edge: 0.1–0.3 px.
 */
object CornerRefiner {
    /** Fraction of a side ignored at each end. */
    const val END_SKIP = 0.08

    /** Default half-width (px) of the search across the approximate edge. */
    const val SEARCH_PX = 4.0

    /** A profile whose gradient peak is below this (grey levels per px) has no edge. */
    const val MIN_EDGE_STRENGTH = 3.0

    /** Most edge samples per side. */
    const val MAX_SAMPLES = 64

    /** Fewest edge samples for a side line. */
    const val MIN_SAMPLES = 8

    /**
     * Refines the side from [a] to [b] (approximate, within [searchPx] of the true edge). Null when too few edge
     * samples are found (no edge there).
     */
    fun refineSide(img: GrayImage, a: Vec2, b: Vec2, searchPx: Double = SEARCH_PX): EdgeLine? {
        val d = b - a
        val len = d.length
        if (len < 4.0) return null
        val dir = d * (1.0 / len)
        val nrm = Vec2(-dir.y, dir.x)
        val usable = len * (1 - 2 * END_SKIP)
        // Enough samples for a 0.1 px line; more only costs time (W9 budget).
        val n = (usable / 3.0).toInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        var k = 0
        val steps = (2 * searchPx).toInt()
        val prof = DoubleArray(steps + 3)
        for (i in 0 until n) {
            val s = len * END_SKIP + usable * (i + 0.5) / n
            val px = a.x + dir.x * s
            val py = a.y + dir.y * s
            // Intensity profile across the edge at 1 px steps, t = -searchPx - 1 … +searchPx + 1.
            for (j in prof.indices) {
                val t = -searchPx - 1 + j
                prof[j] = img.sample(px + nrm.x * t, py + nrm.y * t)
            }
            var best = -1
            var bestG = 0.0
            for (j in 1 until prof.size - 1) {
                val g = abs(prof[j + 1] - prof[j - 1]) / 2
                if (g > bestG) { bestG = g; best = j }
            }
            if (best < 2 || best > prof.size - 3 || bestG < MIN_EDGE_STRENGTH) continue
            val gm = abs(prof[best] - prof[best - 2]) / 2
            val gp = abs(prof[best + 2] - prof[best]) / 2
            val den = gm - 2 * bestG + gp
            val off = if (den < 0) (0.5 * (gm - gp) / den).coerceIn(-0.5, 0.5) else 0.0
            val t = -searchPx - 1 + best + off
            xs[k] = px + nrm.x * t
            ys[k] = py + nrm.y * t
            k++
        }
        if (k < MIN_SAMPLES) return null
        return robustLine(xs, ys, k)
    }

    /**
     * Refines a whole quad given as 4 approximate corners in order (either winding). Each side is refined and adjacent
     * sides are intersected. Null when any side has no edge or two sides are parallel.
     */
    fun refineQuad(img: GrayImage, corners: List<Vec2>, searchPx: Double = SEARCH_PX): List<RefinedCorner>? {
        require(corners.size == 4) { "A quad has 4 corners" }
        val sides = (0 until 4).map { i -> refineSide(img, corners[i], corners[(i + 1) % 4], searchPx) ?: return null }
        return (0 until 4).map { i ->
            val prev = sides[(i + 3) % 4]
            val next = sides[i]
            corner(prev, next) ?: return null
        }
    }

    /**
     * Refines one corner [corner] whose two sides run towards its neighbouring corners [prev] and [next] (e.g. W8's
     * dragged corner: its neighbours give the side directions). Only the parts of the two sides nearest the corner,
     * up to [sideLengthPx] long, are used, so the other corners may be rough. Null when either side has no edge.
     */
    fun refineCorner(
        img: GrayImage,
        corner: Vec2,
        prev: Vec2,
        next: Vec2,
        searchPx: Double = SEARCH_PX,
        sideLengthPx: Double = 120.0,
    ): RefinedCorner? {
        fun towards(o: Vec2): Vec2 {
            val d = o - corner
            val l = d.length
            return if (l <= sideLengthPx) o else corner + d * (sideLengthPx / l)
        }
        val a = refineSide(img, towards(prev), corner, searchPx) ?: return null
        val b = refineSide(img, corner, towards(next), searchPx) ?: return null
        return corner(a, b)
    }

    /** Corner of two refined sides with its σ: the lines' end σ in quadrature, divided by sin of their angle. */
    fun corner(a: EdgeLine, b: EdgeLine): RefinedCorner? {
        val p = a.intersect(b) ?: return null
        val s = abs(a.direction cross b.direction).coerceAtLeast(0.2)
        val sigma = sqrt(a.endSigmaPx * a.endSigmaPx + b.endSigmaPx * b.endSigmaPx) / s
        return RefinedCorner(p, sigma)
    }

    /** Total-least-squares line through the first [n] points, re-weighted 4 times with Tukey's biweight. */
    internal fun robustLine(xs: DoubleArray, ys: DoubleArray, n: Int): EdgeLine? {
        val w = DoubleArray(n) { 1.0 }
        var line: EdgeLine? = null
        repeat(4) {
            val l = weightedLine(xs, ys, w, n) ?: return line
            line = l
            val r = DoubleArray(n) { i -> abs(l.distance(Vec2(xs[i], ys[i]))) }
            val mad = median(r, n) * 1.4826
            val c = 4.685 * max(mad, 0.05)
            for (i in 0 until n) {
                val u = r[i] / c
                w[i] = if (u >= 1) 0.0 else (1 - u * u) * (1 - u * u)
            }
        }
        val l = line ?: return null
        var ss = 0.0
        var sw = 0.0
        for (i in 0 until n) {
            val dd = l.distance(Vec2(xs[i], ys[i]))
            ss += w[i] * dd * dd
            sw += w[i]
        }
        if (sw < MIN_SAMPLES / 2.0) return null
        return EdgeLine(l.point, l.direction, sqrt(ss / sw), sw.toInt())
    }

    private fun weightedLine(xs: DoubleArray, ys: DoubleArray, w: DoubleArray, n: Int): EdgeLine? {
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sw += w[i]; sx += w[i] * xs[i]; sy += w[i] * ys[i] }
        if (sw < 2) return null
        val mx = sx / sw
        val my = sy / sw
        var cxx = 0.0; var cxy = 0.0; var cyy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            cxx += w[i] * dx * dx; cxy += w[i] * dx * dy; cyy += w[i] * dy * dy
        }
        // Principal direction of the 2×2 scatter.
        val angle = 0.5 * kotlin.math.atan2(2 * cxy, cxx - cyy)
        return EdgeLine(Vec2(mx, my), Vec2(kotlin.math.cos(angle), kotlin.math.sin(angle)), 0.0, n)
    }

    private fun median(v: DoubleArray, n: Int): Double {
        val s = v.copyOf(n)
        s.sort()
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Distance between corresponding corners, max over the 4 (px). */
    fun maxShift(a: List<Vec2>, b: List<Vec2>): Double {
        var m = 0.0
        for (i in 0 until min(a.size, b.size)) m = max(m, a[i].distanceTo(b[i]))
        return m
    }
}
