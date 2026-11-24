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
 * 2. [alphaBounds] + [crop]: crop to the subject's bounding box, which then maps to the measured W × H.
 * 3. [downscale]: alpha-weighted box filter (premultiplied average), so transparent pixels never darken the edge.
 * 4. [bleedEdges]: transparent pixels take the colour of their nearest opaque neighbours (and the rest the mean
 *    colour), so a renderer's bilinear filter and mipmaps never pull a dark or background colour into the edge.
 */
object CutoutPixels {
    /** Confidence below this is background (alpha 0). */
    const val MASK_LOW = 0.35f

    /** Confidence above this is the object (alpha 255). Between the two, alpha ramps linearly. */
    const val MASK_HIGH = 0.65f

    /** An alpha at or above this counts as part of the subject for its bounding box. */
    const val BOUNDS_ALPHA = 128

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

    /** The bounding box `[x0, y0, x1, y1)` of pixels with alpha ≥ [minAlpha]; null when there are none. */
    fun alphaBounds(image: ArgbImage, minAlpha: Int = BOUNDS_ALPHA): IntArray? {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until image.height) {
            val row = y * image.width
            for (x in 0 until image.width) {
                if ((image.argb[row + x] ushr 24) >= minAlpha) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    y1 = y
                }
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    /** The pixels inside `[x0, y0, x1, y1)`. */
    fun crop(image: ArgbImage, rect: IntArray): ArgbImage {
        val (x0, y0, x1, y1) = rect
        require(x0 in 0 until x1 && y0 in 0 until y1 && x1 <= image.width && y1 <= image.height) { "Bad crop ${rect.toList()}" }
        val w = x1 - x0; val h = y1 - y0
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(image.argb, (y0 + y) * image.width + x0, out, y * w, w)
        return ArgbImage(w, h, out)
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
        var frontier = (0 until px.size).filter { !coloured[it] }
        repeat(passes) {
            if (frontier.isEmpty()) return@repeat
            val updates = ArrayList<IntArray>()
            val remaining = ArrayList<Int>()
            for (i in frontier) {
                val x = i % w; val y = i / w
                var n = 0; var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (!coloured[j]) continue
                    val q = px[j]
                    sr += (q shr 16) and 0xFF; sg += (q shr 8) and 0xFF; sb += q and 0xFF; n++
                }
                if (n > 0) updates += intArrayOf(i, ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)) else remaining += i
            }
            // Apply after the scan, so one ring grows per pass (no directional smear).
            for ((i, rgb) in updates) { px[i] = rgb; coloured[i] = true } // alpha stays 0
            frontier = remaining
        }
        for (i in frontier) px[i] = mean
        return ArgbImage(w, h, px)
    }
}
