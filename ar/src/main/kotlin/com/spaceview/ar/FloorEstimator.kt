package com.spaceview.ar

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A robust floor height from the horizontal surface hits of one session (M4-P1 gate N1). Pure and allocation-free
 * per hit (fixed arrays), main thread only.
 *
 * The old rule took the single lowest horizontal hit, so one mirrored feature point on a polished vitrified or marble
 * floor (or under a glass table) a few cm below the real floor became "the floor" for the rest of the session, and
 * every genuine wall–floor tap was then rejected. Here hits vote into [BIN_M] height bins, and the floor is:
 * - the **lowest bin with a hit on a tracked ARCore plane**, whenever any bin has one (ARCore fits planes to many
 *   feature points, so mirrored features never make one; T-D0 review MINOR 3), **unless** a lower point-only bin has
 *   *strong* support (T-D0 gate G2: a glossy floor ARCore never turns into a plane, under a textured table it does):
 *   at least [STRONG_VOTES] hits, a 2-D spread (the minor axis of the hits' x–z scatter has a standard deviation of
 *   at least [STRONG_MINOR_STD_M]: a floor patch is an area, a mirrored edge is a line, a reflection is a spot), and
 *   hits over at least [STRONG_SPAN_NS] of camera time (seen consistently, not one burst), and an area at least
 *   [AREA_RATIO] × the plane's (a reflection in a *tracked* glossy floor is smaller than that floor; MINOR 15); else
 * - with no plane at all, the lowest bin with at least [MIN_POINT_VOTES] point / depth hits spread over at least
 *   [MIN_SPREAD_M] across the floor (a floor is wide; a stray point is one spot).
 *
 * The height is the mean of the hits in that bin and, when it also qualifies, the bin above, so a floor straddling a
 * bin edge is not split. A tap the user confirms as the floor (the wall–floor line) is used only while no bin qualifies.
 */
class FloorEstimator {
    private val votes = IntArray(BINS)
    private val planeVotes = IntArray(BINS)
    private val ySum = DoubleArray(BINS)
    private val minX = DoubleArray(BINS)
    private val maxX = DoubleArray(BINS)
    private val minZ = DoubleArray(BINS)
    private val maxZ = DoubleArray(BINS)

    // Per bin, for the G2 "strong support" test: x–z scatter moments (relative to the session's first hit, for
    // precision) and the camera-time span of the bin's hits.
    private var refX = 0.0
    private var refZ = 0.0
    private val sx = DoubleArray(BINS)
    private val sz = DoubleArray(BINS)
    private val sxx = DoubleArray(BINS)
    private val szz = DoubleArray(BINS)
    private val sxz = DoubleArray(BINS)
    private val firstNs = LongArray(BINS)
    private val lastNs = LongArray(BINS)
    private var reference = Double.NaN
    private var lowestUsed = BINS
    private var confirmed = Double.NaN

    /** Cached result; recomputed only after a vote. */
    private var cached = Double.NaN
    private var dirty = false

    /**
     * One horizontal hit at ([x], [y], [z]) in camera frame [timestampNs]; [onPlane] when it lies on a tracked ARCore
     * plane.
     */
    fun add(x: Double, y: Double, z: Double, onPlane: Boolean, timestampNs: Long) {
        if (!(x.isFinite() && y.isFinite() && z.isFinite())) return
        if (reference.isNaN()) {
            reference = y
            refX = x
            refZ = z
        }
        val b = floor((y - reference) / BIN_M).toInt() + BINS / 2
        if (b < 0 || b >= BINS) return // more than ±5 m from the first hit: not this room's floor
        if (votes[b] == 0) {
            minX[b] = x; maxX[b] = x; minZ[b] = z; maxZ[b] = z
            sx[b] = 0.0; sz[b] = 0.0; sxx[b] = 0.0; szz[b] = 0.0; sxz[b] = 0.0
            firstNs[b] = timestampNs; lastNs[b] = timestampNs
        } else {
            if (x < minX[b]) minX[b] = x
            if (x > maxX[b]) maxX[b] = x
            if (z < minZ[b]) minZ[b] = z
            if (z > maxZ[b]) maxZ[b] = z
        }
        val dx = x - refX
        val dz = z - refZ
        sx[b] += dx; sz[b] += dz; sxx[b] += dx * dx; szz[b] += dz * dz; sxz[b] += dx * dz
        if (timestampNs < firstNs[b]) firstNs[b] = timestampNs
        if (timestampNs > lastNs[b]) lastNs[b] = timestampNs
        votes[b]++
        if (onPlane) planeVotes[b]++
        ySum[b] += y
        if (b < lowestUsed) lowestUsed = b
        dirty = true
    }

    /** A floor height the user confirmed (both wall–floor taps): used only while no bin qualifies. */
    fun confirm(y: Double) {
        if (y.isFinite()) confirmed = y
    }

    /** The floor height (world y), or null while nothing qualifies and nothing was confirmed. */
    fun floorY(): Double? {
        if (dirty) {
            cached = estimate()
            dirty = false
        }
        return when {
            !cached.isNaN() -> cached
            !confirmed.isNaN() -> confirmed
            else -> null
        }
    }

    /** Forgets everything (a new ARCore session has a new world frame). */
    fun reset() {
        votes.fill(0); planeVotes.fill(0); ySum.fill(0.0)
        reference = Double.NaN
        lowestUsed = BINS
        confirmed = Double.NaN
        cached = Double.NaN
        dirty = false
    }

    private fun qualifies(b: Int): Boolean {
        if (planeVotes[b] > 0) return true
        if (votes[b] < MIN_POINT_VOTES) return false
        val dx = maxX[b] - minX[b]
        val dz = maxZ[b] - minZ[b]
        return dx * dx + dz * dz >= MIN_SPREAD_M * MIN_SPREAD_M
    }

    // Scratch results of [scatter] (no allocation per estimate).
    private var scN = 0
    private var scMinorVar = 0.0
    private var scDet = 0.0
    private var scSpanNs = 0L

    /** x–z scatter of bins [from]..[to] (votes, minor-axis variance, covariance determinant, time span). */
    private fun scatter(from: Int, to: Int) {
        var n = 0
        var x = 0.0; var z = 0.0; var xx = 0.0; var zz = 0.0; var xz = 0.0
        var t0 = Long.MAX_VALUE
        var t1 = Long.MIN_VALUE
        for (k in from..to) {
            if (votes[k] == 0) continue
            n += votes[k]
            x += sx[k]; z += sz[k]; xx += sxx[k]; zz += szz[k]; xz += sxz[k]
            if (firstNs[k] < t0) t0 = firstNs[k]
            if (lastNs[k] > t1) t1 = lastNs[k]
        }
        scN = n
        if (n == 0) {
            scMinorVar = 0.0; scDet = 0.0; scSpanNs = 0L
            return
        }
        val mx = x / n
        val mz = z / n
        val cxx = xx / n - mx * mx
        val czz = zz / n - mz * mz
        val cxz = xz / n - mx * mz
        // Smaller eigenvalue of the 2×2 covariance [[cxx, cxz], [cxz, czz]], and its determinant (area²).
        val half = (cxx + czz) / 2
        val d = (cxx - czz) / 2
        scMinorVar = half - sqrt(d * d + cxz * cxz)
        scDet = maxOf(0.0, cxx * czz - cxz * cxz)
        scSpanNs = t1 - t0
    }

    /**
     * G2 with the review's MINOR 15 guard: point-only evidence below the lowest plane wins only when it is strong
     * (many hits, over time, spread over an *area*: the minor axis of the x–z scatter, so a mirrored edge or a spot
     * never passes) **and** it covers a larger area than the plane above it (scatter determinant ≥
     * [AREA_RATIO]² × the plane's).
     *
     * Why the area test: a mirror image is only seen *inside* the reflecting surface. When ARCore tracks a glossy floor,
     * the reflection of a table or bed in it covers less floor than the floor plane the user has aimed over, so it loses;
     * in G2 (an untracked glossy floor under a tracked table) the floor seen around the table is wider than the table
     * top, so it wins. Height alone cannot separate the two (a coffee table is 0.45 m, its reflection in a tracked floor
     * 0.45 m below it), which is why a "within 0.3 m of the plane" rule was rejected.
     */
    private fun strong(b: Int, plane: Int): Boolean {
        if (planeVotes[b] > 0) return false
        // With the point-only bin above it (below the plane): a floor straddling a bin edge is not split in two.
        val top = if (b + 1 < plane && planeVotes[b + 1] == 0) b + 1 else b
        scatter(b, top)
        if (scN < STRONG_VOTES || scSpanNs < STRONG_SPAN_NS) return false
        if (scMinorVar < STRONG_MINOR_STD_M * STRONG_MINOR_STD_M) return false
        val pointDet = scDet
        scatter(plane, plane)
        return pointDet >= AREA_RATIO * AREA_RATIO * scDet
    }

    private fun estimate(): Double {
        // A tracked ARCore plane wins over point-only evidence below it (T-D0 review MINOR 3: a spread line of mirrored
        // features below a glossy floor can qualify as points, never as a plane), unless that evidence is strong (G2:
        // the real floor is glossy and untracked, and the plane is a table).
        val plane = lowestBin { planeVotes[it] > 0 }
        val b = if (plane >= 0) {
            val below = lowestBin { it < plane && strong(it, plane) }
            if (below >= 0) below else plane
        } else {
            lowestBin { qualifies(it) }
        }
        if (b < 0) return Double.NaN
        var n = 0
        var sum = 0.0
        // The bin and the one above when it is part of the same surface (a floor straddling a bin edge). The bin
        // below never counts, so a stray point there cannot pull the height down.
        for (k in b..minOf(BINS - 1, b + 1)) {
            if (k != b && (votes[k] == 0 || !qualifies(k))) continue
            n += votes[k]
            sum += ySum[k]
        }
        return sum / n
    }

    private inline fun lowestBin(accept: (Int) -> Boolean): Int {
        for (b in lowestUsed until BINS) if (votes[b] > 0 && accept(b)) return b
        return -1
    }

    companion object {
        /** Height bin (m): finer than the 3 cm "on the floor" tolerance of the wall–floor line. */
        const val BIN_M = 0.02

        /** Bins: ±5.12 m around the first hit. */
        const val BINS = 512

        /** Point or depth hits a bin needs (without a plane hit) to count as a surface. */
        const val MIN_POINT_VOTES = 5

        /** Horizontal spread (m) those hits need: a floor is wide, a reflection or a stray point is one spot. */
        const val MIN_SPREAD_M = 0.15

        /** G2: hits a point-only bin needs to beat a plane above it. Tunable (device item 22). */
        const val STRONG_VOTES = 30

        /**
         * G2: standard deviation (m) of the hits along the *minor* axis of their x–z scatter. A uniform 0.5 m-wide
         * band has ≈ 0.14 m; a mirrored edge (a line) a cm or two. Tunable (device item 22).
         */
        const val STRONG_MINOR_STD_M = 0.10

        /** G2: the hits must span this much camera time (seen while looking around, not one burst). */
        const val STRONG_SPAN_NS = 2_000_000_000L

        /**
         * MINOR 15: the point-only "floor" must cover at least this many times the plane's area (as the square root of
         * the x–z scatter determinant). Tunable (device item 22).
         */
        const val AREA_RATIO = 1.5
    }
}
