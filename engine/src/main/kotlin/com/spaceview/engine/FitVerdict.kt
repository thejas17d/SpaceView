package com.spaceview.engine

import kotlin.math.sqrt

/** "Does it fit?" verdict (UI: Fits ✓ / Tight ⚠ / Too big ✕). */
enum class Fit {
    /** Fits even at the pessimistic end of both uncertainties. */
    FITS,

    /** Within the margin of error: confirm with a tape. */
    TIGHT,

    /** Too big even at the optimistic end of both uncertainties. */
    TOO_BIG,
}

/**
 * Per-axis comparison.
 *
 * @property axis index into the space dimensions.
 * @property itemAxis index of the item dimension placed along this axis (differs when rotation is allowed).
 * @property clearance space − item (m) with combined σ; negative = item is larger.
 */
class AxisFit(val axis: Int, val itemAxis: Int, val clearance: Measurement, val fit: Fit)

/**
 * Overall verdict.
 *
 * @property fit worst axis verdict.
 * @property axes per-axis detail in space-axis order.
 * @property limitingAxis axis with the least clearance (relative to its uncertainty) — what the UI quotes:
 *   "Fits, 6 cm to spare" / "Too big by 4 cm".
 * @property rotated true if the best orientation swaps item dimensions.
 */
class FitResult(val fit: Fit, val axes: List<AxisFit>, val limitingAxis: AxisFit, val rotated: Boolean) {
    /** Clearance on the limiting axis (m), negative when too big. */
    val clearance: Measurement get() = limitingAxis.clearance
}

/**
 * Which orientations of the item [FitVerdict] may try. Dimensions are ordered W, H[, D] with H vertical.
 */
enum class FitRotation {
    /** As given. */
    NONE,

    /**
     * Turn about the vertical axis only: W and D may swap, H stays vertical (a fridge or TV is never
     * tipped over). For 1-D/2-D input there is no D, so nothing is permuted.
     */
    ABOUT_VERTICAL,

    /** Any permutation, including tipping onto a side (e.g. a frame portrait vs landscape in 2-D). */
    ANY,
}

/**
 * Fit verdict between an item and a space, accounting for uncertainty on both.
 *
 * For each axis, clearance c = space − item and σc = √(σs² + σi²) (treated as independent, which is the
 * conservative choice when they were measured separately). With z = [z] (1.96 ≈ 95%):
 * - FITS     if c − z·σc > [minClearance]
 * - TOO_BIG  if c + z·σc < [minClearance]
 * - TIGHT    otherwise (including exact equality with zero uncertainty).
 */
class FitPolicy(
    /** Coverage factor for the decision band. */
    val z: Double = Uncertainty.K95,
    /** Required gap (m), e.g. ventilation space around a TV or fridge. */
    val minClearance: Double = 0.0,
    /** Let the item be rotated to find the best fit; true means [FitRotation.ABOUT_VERTICAL]. */
    val allowRotation: Boolean = false,
    /** Orientations tried. Defaults from [allowRotation] (false → NONE, true → ABOUT_VERTICAL). */
    val rotation: FitRotation = if (allowRotation) FitRotation.ABOUT_VERTICAL else FitRotation.NONE,
) {
    init { require(z >= 0 && minClearance >= 0) }
}

/** Computes [FitResult]s. */
object FitVerdict {

    /**
     * Compares [item] dims with [space] dims (same order, e.g. W, H[, D]). Both lists must have the
     * same size (1–3), ordered W, H[, D]. With [FitPolicy.rotation] the allowed permutations of the item
     * dims are tried (ABOUT_VERTICAL: W↔D only; ANY: all) and the best one (best verdict, then largest
     * normalised clearance) is returned.
     */
    fun evaluate(item: List<Measurement>, space: List<Measurement>, policy: FitPolicy = FitPolicy()): FitResult {
        require(item.size == space.size) { "Item has ${item.size} dims but space has ${space.size}" }
        require(item.size in 1..3) { "Fit supports 1 to 3 dimensions" }
        require(item.all { it.value >= 0 } && space.all { it.value >= 0 }) { "Dimensions must be non-negative" }

        val identity = item.indices.toList()
        val perms = when (policy.rotation) {
            FitRotation.NONE -> listOf(identity)
            FitRotation.ABOUT_VERTICAL -> if (item.size == 3) listOf(identity, listOf(2, 1, 0)) else listOf(identity)
            FitRotation.ANY -> permutations(identity)
        }
        var best: FitResult? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (perm in perms) {
            val axes = space.indices.map { a -> axisFit(a, perm[a], item[perm[a]], space[a], policy) }
            val limiting = axes.minBy { score(it, policy) }
            val worst = axes.maxOf { it.fit.ordinal }
            val result = FitResult(Fit.entries[worst], axes, limiting, perm != identity)
            // Rank: verdict first, then the limiting axis' normalised margin.
            val s = -worst * 1e12 + score(limiting, policy)
            if (best == null || s > bestScore) { best = result; bestScore = s }
        }
        return best!!
    }

    /** Single-dimension convenience (e.g. a TV's width against a wall gap). */
    fun evaluate(item: Measurement, space: Measurement, policy: FitPolicy = FitPolicy()): FitResult =
        evaluate(listOf(item), listOf(space), policy)

    private fun axisFit(axis: Int, itemAxis: Int, item: Measurement, space: Measurement, p: FitPolicy): AxisFit {
        val c = space.value - item.value
        val s = sqrt(space.sigma * space.sigma + item.sigma * item.sigma)
        val fit = when {
            c - p.z * s > p.minClearance -> Fit.FITS
            c + p.z * s < p.minClearance -> Fit.TOO_BIG
            else -> Fit.TIGHT
        }
        return AxisFit(axis, itemAxis, Measurement(c, s), fit)
    }

    /** Pessimistic margin above the requirement (m); lower = more limiting. */
    private fun score(a: AxisFit, p: FitPolicy): Double =
        a.clearance.value - p.z * a.clearance.sigma - p.minClearance

    private fun permutations(xs: List<Int>): List<List<Int>> =
        if (xs.size <= 1) listOf(xs)
        else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }
}
