package com.spaceview.ar

import kotlin.math.floor

/**
 * A robust floor height from the horizontal surface hits of one session (M4-P1 gate N1). Pure and allocation-free
 * per hit (fixed arrays), main thread only.
 *
 * The old rule took the single lowest horizontal hit, so one mirrored feature point on a polished vitrified or marble
 * floor (or under a glass table) a few cm below the real floor became "the floor" for the rest of the session, and
 * every genuine wall–floor tap was then rejected. Here hits vote into [BIN_M] height bins, and the floor is:
 * - the **lowest bin with a hit on a tracked ARCore plane**, whenever any bin has one (ARCore fits planes to many
 *   feature points, so mirrored features never make one; T-D0 review MINOR 3), else
 * - the lowest bin with at least [MIN_POINT_VOTES] point / depth hits spread over at least [MIN_SPREAD_M] across the
 *   floor (a floor is wide; a stray point is one spot).
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
    private var reference = Double.NaN
    private var lowestUsed = BINS
    private var confirmed = Double.NaN

    /** Cached result; recomputed only after a vote. */
    private var cached = Double.NaN
    private var dirty = false

    /** One horizontal hit at ([x], [y], [z]); [onPlane] when it lies on a tracked ARCore plane. */
    fun add(x: Double, y: Double, z: Double, onPlane: Boolean) {
        if (!(x.isFinite() && y.isFinite() && z.isFinite())) return
        if (reference.isNaN()) reference = y
        val b = floor((y - reference) / BIN_M).toInt() + BINS / 2
        if (b < 0 || b >= BINS) return // more than ±5 m from the first hit: not this room's floor
        if (votes[b] == 0) {
            minX[b] = x; maxX[b] = x; minZ[b] = z; maxZ[b] = z
        } else {
            if (x < minX[b]) minX[b] = x
            if (x > maxX[b]) maxX[b] = x
            if (z < minZ[b]) minZ[b] = z
            if (z > maxZ[b]) maxZ[b] = z
        }
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

    private fun estimate(): Double {
        // A tracked ARCore plane wins over point-only evidence wherever it is (T-D0 review MINOR 3): a spread line of
        // mirrored features below a glossy floor can qualify as points, never as a plane.
        var b = lowestBin { planeVotes[it] > 0 }
        if (b < 0) b = lowestBin { qualifies(it) }
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
    }
}
