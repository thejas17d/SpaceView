package com.spaceview.engine

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picture as plain pixels: [width] × [height], row-major `0xAARRGGBB` with **straight** (not premultiplied) alpha.
 * The cutout pipeline (PLAN v3 W2) works on these so no colour is lost under transparent pixels.
 */
class ArgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "Bad image: $width x $height with ${argb.size} pixels" }
    }

    /** True when every pixel is fully opaque (no background was removed). */
    val isOpaque: Boolean get() = argb.all { (it ushr 24) == 0xFF }
}

/** How sure a subject mask is (ML Kit subject segmentation's foreground confidence, 0..1 per pixel). */
data class MaskStats(
    /** Share of pixels that are foreground (confidence ≥ 0.5). */
    val coverage: Double,
    /** Share of the foreground-ish pixels (confidence ≥ 0.2) whose confidence is between 0.2 and 0.8. */
    val uncertainShare: Double,
)

/**
 * Pure pixel steps of the background-free cutout (PLAN v3 W2), JVM-tested:
 * 1. [applyMask]: the segmentation confidence becomes the alpha channel (a soft ramp, so edges are anti-aliased).
 * 2. [visibleExtent]: a trust check only. The cutout is never cropped: the rectified photo already is the measured
 *    W × H, so cropping it to the subject and mapping the crop back onto W × H would stretch the object (PLAN v3 review
 *    MAJOR 5).
 * 3. [downscale]: alpha-weighted box filter (premultiplied average), so transparent pixels never darken the edge.
 * 4. [bleedEdges]: transparent pixels take the colour of their nearest opaque neighbours (and the rest the mean
 *    colour), so a renderer's bilinear filter and mipmaps never pull a dark or background colour into the edge.
 */
object CutoutPixels {
    /** Confidence below this is background (alpha 0). */
    const val MASK_LOW = 0.35f

    /** Confidence above this is the object (alpha 255). Between the two, alpha ramps linearly. */
    const val MASK_HIGH = 0.65f

    /**
     * Share of the visible pixels [visibleExtent] trims from each end of each axis, so isolated specks (a stray mask
     * pixel near an edge) cannot stretch the extent (PLAN v3 gate G5).
     */
    const val EXTENT_TRIM = 0.01

    /** A mask covering less than this of the photo is not trusted (the object was not found). */
    const val MIN_COVERAGE = 0.05

    /** A mask with more than this share of uncertain pixels is not trusted (a confused segmentation). */
    const val MAX_UNCERTAIN_SHARE = 0.35

    /** Alpha from confidence: 0 below [MASK_LOW], 255 above [MASK_HIGH], linear in between. */
    fun alphaOf(confidence: Float): Int = when {
        confidence.isNaN() || confidence <= MASK_LOW -> 0
        confidence >= MASK_HIGH -> 255
        else -> ((confidence - MASK_LOW) / (MASK_HIGH - MASK_LOW) * 255f).roundToInt().coerceIn(0, 255)
    }

    /** [image] (opaque) with its alpha replaced by [confidence] (one value per pixel, row-major). */
    fun applyMask(image: ArgbImage, confidence: FloatArray): ArgbImage {
        require(confidence.size == image.argb.size) { "Mask has ${confidence.size} values for ${image.argb.size} pixels" }
        val out = IntArray(image.argb.size) { i -> (alphaOf(confidence[i]) shl 24) or (image.argb[i] and 0xFFFFFF) }
        return ArgbImage(image.width, image.height, out)
    }

    fun stats(confidence: FloatArray): MaskStats {
        if (confidence.isEmpty()) return MaskStats(0.0, 1.0)
        var fg = 0; var any = 0; var unsure = 0
        for (c in confidence) {
            if (c.isNaN()) continue
            if (c >= 0.5f) fg++
            if (c >= 0.2f) {
                any++
                if (c < 0.8f) unsure++
            }
        }
        return MaskStats(fg.toDouble() / confidence.size, if (any == 0) 1.0 else unsure.toDouble() / any)
    }

    /** True when the mask can be used: enough of the object was found and the segmentation is not confused. */
    fun trusted(stats: MaskStats): Boolean = stats.coverage >= MIN_COVERAGE && stats.uncertainShare <= MAX_UNCERTAIN_SHARE

    /**
     * The extent `[x0, y0, x1, y1)` of the visible pixels (alpha ≥ 1, so faint thin parts such as chair legs count
     * fully), robust to specks: along each axis it spans from the [trim] to the 1 − [trim] quantile of the visible
     * pixels' positions, so a few stray pixels near an edge (a fraction ≤ [trim] of the visible ones) never widen it.
     * Null when nothing is visible. Used only to check that a mask fills the measured outline.
     */
    fun visibleExtent(image: ArgbImage, trim: Double = EXTENT_TRIM): IntArray? {
        require(trim in 0.0..0.25)
        val cols = IntArray(image.width)
        val rows = IntArray(image.height)
        var total = 0L
        for (y in 0 until image.height) {
            val row = y * image.width
            for (x in 0 until image.width) {
                if ((image.argb[row + x] ushr 24) != 0) {
                    cols[x]++; rows[y]++; total++
                }
            }
        }
        if (total == 0L) return null
        val (x0, x1) = quantileSpan(cols, total, trim)
        val (y0, y1) = quantileSpan(rows, total, trim)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** `[first, last + 1)` of the histogram bins holding the [trim] .. 1 − [trim] quantiles of its [total] count. */
    private fun quantileSpan(hist: IntArray, total: Long, trim: Double): Pair<Int, Int> {
        val skip = (total * trim).toLong() // counts allowed to fall outside on each end
        var acc = 0L
        var lo = 0
        while (lo < hist.size - 1 && acc + hist[lo] <= skip) { acc += hist[lo]; lo++ }
        acc = 0L
        var hi = hist.size - 1
        while (hi > lo && acc + hist[hi] <= skip) { acc += hist[hi]; hi-- }
        return lo to hi + 1
    }

    /**
     * A copy whose long side is at most [maxLongSide] (the image itself when already small enough). Each output pixel
     * is the alpha-weighted mean of the source pixels it covers (premultiplied average, then un-premultiplied).
     */
    fun downscale(image: ArgbImage, maxLongSide: Int): ArgbImage {
        require(maxLongSide > 0)
        val long = max(image.width, image.height)
        if (long <= maxLongSide) return image
        val s = maxLongSide.toDouble() / long
        val dw = max(1, (image.width * s).roundToInt())
        val dh = max(1, (image.height * s).roundToInt())
        val sw = image.width; val sh = image.height
        val src = image.argb
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val ya = y * sh / dh
            val yb = max(ya + 1, (y + 1) * sh / dh)
            for (x in 0 until dw) {
                val xa = x * sw / dw
                val xb = max(xa + 1, (x + 1) * sw / dw)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in ya until yb) for (xx in xa until xb) {
                    val p = src[yy * sw + xx]
                    val pa = (p ushr 24).toLong()
                    a += pa
                    r += pa * ((p shr 16) and 0xFF); g += pa * ((p shr 8) and 0xFF); b += pa * (p and 0xFF)
                    n++
                }
                out[y * dw + x] = if (a == 0L) {
                    0
                } else {
                    val oa = ((a + n / 2) / n).toInt().coerceIn(0, 255)
                    (oa shl 24) or (((r + a / 2) / a).toInt() shl 16) or (((g + a / 2) / a).toInt() shl 8) or ((b + a / 2) / a).toInt()
                }
            }
        }
        return ArgbImage(dw, dh, out)
    }

    /**
     * A copy where every fully transparent pixel carries a colour: the mean of its coloured 8-neighbours, grown
     * outwards for [passes] rings; pixels further away get the mean colour of the visible pixels (good for mipmaps and
     * for PNG compression). Alpha is unchanged. An opaque image is returned as it is.
     */
    fun bleedEdges(image: ArgbImage, passes: Int = 8): ArgbImage {
        if (image.isOpaque) return image
        val w = image.width; val h = image.height
        val px = image.argb.copyOf()
        val coloured = BooleanArray(px.size) { (px[it] ushr 24) != 0 }
        // Mean colour of the visible pixels, alpha-weighted.
        var a = 0L; var r = 0L; var g = 0L; var b = 0L
        for (p in px) {
            val pa = (p ushr 24).toLong()
            if (pa == 0L) continue
            a += pa; r += pa * ((p shr 16) and 0xFF); g += pa * ((p shr 8) and 0xFF); b += pa * (p and 0xFF)
        }
        val mean = if (a == 0L) 0x808080 else (((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt())
        // Frontier of still-uncoloured pixels as a plain IntArray (no boxing: a 1024² cutout has ~500k of them).
        var frontier = IntArray(px.size)
        var n = 0
        for (k in px.indices) if (!coloured[k]) frontier[n++] = k
        val updIdx = IntArray(n)
        val updRgb = IntArray(n)
        repeat(passes) {
            if (n == 0) return@repeat
            var u = 0
            var remaining = 0
            for (f in 0 until n) {
                val i = frontier[f]
                val x = i % w; val y = i / w
                var c = 0; var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if ((dx == 0 && dy == 0) || nx < 0 || nx >= w) continue
                        val j = ny * w + nx
                        if (!coloured[j]) continue
                        val q = px[j]
                        sr += (q shr 16) and 0xFF; sg += (q shr 8) and 0xFF; sb += q and 0xFF; c++
                    }
                }
                if (c > 0) {
                    updIdx[u] = i; updRgb[u] = ((sr / c) shl 16) or ((sg / c) shl 8) or (sb / c); u++
                } else {
                    frontier[remaining++] = i // compacts in place: remaining <= f
                }
            }
            // Apply after the scan, so one ring grows per pass (no directional smear). Alpha stays 0.
            for (k in 0 until u) { px[updIdx[k]] = updRgb[k]; coloured[updIdx[k]] = true }
            n = remaining
        }
        for (f in 0 until n) px[frontier[f]] = mean
        return ArgbImage(w, h, px)
    }
}
