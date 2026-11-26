package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * W10 plane-first lifting for a flat object lying on (or hung against) a support surface (research
 * `close-range-and-rectangles.md` §3 rank 2, C4–C6; ARKit's raycast to an "existing plane, infinite").
 *
 * The object's corners are measured on a plane, not on sparse hits. Which plane:
 * - the support plane itself, when the depth / feature points on the object agree with it (median offset within
 *   max([AGREE_ABS_M], [AGREE_REL] × range)): a thin sheet;
 * - the support plane shifted by the object's **thickness** (the median offset), when there are at least
 *   [MIN_POINTS] points and their spread (MAD) is under [MAX_MAD_M]: a padded menu, a book;
 * - otherwise no plane: the points disagree (a box, clutter), and the caller must not lift onto it.
 *
 * Every lift carries a plane σ of max([PLANE_SIGMA_ABS_M], [PLANE_SIGMA_REL] × range) (ARCore planes used to carry
 * none), plus the thickness estimate's own standard error when shifted.
 */
object SupportPlane {
    enum class Kind {
        /** Not enough points on the object to check: the support plane is used as is, with its σ. */
        NO_EVIDENCE,

        /** The points agree with the support plane. */
        AGREES,

        /** The plane is shifted by the object's thickness. */
        SHIFTED,

        /** The points do not lie on one parallel plane: do not lift onto it. */
        DISAGREES,
    }

    /**
     * @property plane the plane to lift onto (null when [kind] is [Kind.DISAGREES]).
     * @property thicknessM median offset of the object's points from the support plane (towards the camera > 0).
     * @property sigmaM 1σ of the plane's position along its normal.
     */
    class Result(val kind: Kind, val plane: Plane?, val thicknessM: Double, val sigmaM: Double) {
        /** The plane σ as a relative length error at [rangeM] (`planeBiasRel` of the measuring code). */
        fun biasRel(rangeM: Double): Double = if (rangeM > 0) sigmaM / rangeM else 0.0
    }

    /** Plane σ at [rangeM] (m). */
    fun planeSigma(rangeM: Double): Double = max(PLANE_SIGMA_ABS_M, PLANE_SIGMA_REL * rangeM.coerceAtLeast(0.0))

    /** Relative length error of a plane at [rangeM] (`planeBiasRel` for an ARCore plane hit). */
    fun biasRel(rangeM: Double): Double = if (rangeM > 0 && rangeM.isFinite()) planeSigma(rangeM) / rangeM else 0.0

    /** Agreement band at [rangeM] (m). */
    fun agreeTolerance(rangeM: Double): Double = max(AGREE_ABS_M, AGREE_REL * rangeM.coerceAtLeast(0.0))

    /**
     * Resolves the plane for an object whose depth / feature points are [objectPoints], lying on [support], seen from
     * [cameraPosition] at about [rangeM].
     */
    fun resolve(support: Plane, objectPoints: List<Vec3>, cameraPosition: Vec3, rangeM: Double): Result {
        val facing = support.facing(cameraPosition)
        val base = planeSigma(rangeM)
        val offsets = objectPoints.filter { it.isFinite }.map { facing.signedDistance(it) }
        if (offsets.size < MIN_POINTS) return Result(Kind.NO_EVIDENCE, facing, 0.0, base)
        val med = median(offsets)
        val mad = 1.4826 * median(offsets.map { abs(it - med) })
        if (abs(med) <= agreeTolerance(rangeM)) return Result(Kind.AGREES, facing, med, base)
        if (mad < MAX_MAD_M) {
            // Standard error of a median ≈ 1.25 σ / √n.
            val se = 1.25 * mad / sqrt(offsets.size.toDouble())
            val shifted = Plane.fromPointNormal(facing.point + facing.normal * med, facing.normal)
            return Result(Kind.SHIFTED, shifted, med, sqrt(base * base + se * se))
        }
        return Result(Kind.DISAGREES, null, med, base)
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Points within this (or [AGREE_REL] × range) of the support plane agree with it (research: 2 mm / 0.5 %). */
    const val AGREE_ABS_M = 0.002
    const val AGREE_REL = 0.005

    /** A thickness shift needs this many points … */
    const val MIN_POINTS = 30

    /** … whose spread (MAD) is below this (m). */
    const val MAX_MAD_M = 0.002

    /** Plane σ: max(1 mm, 0.5 % of range). Tunable on the bench. */
    const val PLANE_SIGMA_ABS_M = 0.001
    const val PLANE_SIGMA_REL = 0.005
}
