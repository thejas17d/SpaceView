package com.spaceview.engine.range

import com.spaceview.engine.Uncertainty
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One sight of a 2-sight range: where the camera was and where the crosshair pointed, in ARCore's world frame.
 *
 * @property aimSigmaRad 1σ angular error of [direction] per axis: the aim model's error plus the jitter of the
 *   averaged hold.
 * @property timeS capture time (s, any monotonic clock shared by both sights).
 */
class Sight(val origin: Vec3, direction: Vec3, val aimSigmaRad: Double, val timeS: Double) {
    val direction: Vec3 = direction.normalized()

    init {
        require(origin.isFinite && this.direction.isFinite && aimSigmaRad.isFinite() && aimSigmaRad >= 0 && timeS.isFinite())
    }
}

/**
 * Closest approach of two rays.
 *
 * @property s / [t] distances along ray 1 / ray 2 to their closest points.
 * @property point midpoint of the two closest points (the triangulated target).
 * @property miss distance between the two closest points.
 * @property angleRad angle between the two ray directions (the parallax at the target).
 */
class ClosestApproach(val point: Vec3, val s: Double, val t: Double, val miss: Double, val angleRad: Double)

/**
 * A 2-sight solution with its diagnostics.
 *
 * @property outcome what the app shows.
 * @property point the triangulated target (null when the rays are parallel or input is invalid).
 * @property expectedMissSigmaM σ of the miss distance predicted by the error model.
 */
class Triangulation(
    val outcome: RangeOutcome,
    val point: Vec3?,
    val angleRad: Double,
    val missM: Double,
    val expectedMissSigmaM: Double,
    val baselineM: Double,
)

/**
 * 2-sight triangulation (docs/PLAN-v3-2D.md W4 method 3). The target is the closest approach of the two sight rays;
 * the distance is the line of sight from the **second** sight (where the user now stands).
 *
 * ± (1σ, then [RangeUncertainty.inverseLaw] for the 1/parallax shape): a numerical Jacobian over the independent
 * error sources, each perturbed by ±1σ (central differences, so moderate non-linearity is captured):
 * - sight 1 direction, two perpendicular axes, its aim σ;
 * - sight 2 direction, two axes, its aim σ ⊕ the relative attitude drift between the sights;
 * - sight 1 position relative to sight 2, three axes, the VIO baseline σ (≈1% of the path walked + drift).
 * The measured miss distance is a consistency check: far beyond its predicted σ the sights are refused (aimed at
 * different things); moderately beyond it the ± is inflated in proportion.
 */
object RayTriangulation {
    /** Closest approach of rays (o1, d1) and (o2, d2) (directions need not be unit); null when parallel. */
    fun closestApproach(o1: Vec3, d1In: Vec3, o2: Vec3, d2In: Vec3): ClosestApproach? {
        val d1 = d1In.normalized()
        val d2 = d2In.normalized()
        val b = d1 dot d2
        val denom = 1 - b * b
        if (!(denom > 1e-14)) return null
        val w0 = o1 - o2
        val d = d1 dot w0
        val e = d2 dot w0
        val s = (b * e - d) / denom
        val t = (e - b * d) / denom
        val q1 = o1 + d1 * s
        val q2 = o2 + d2 * t
        val angle = acos(b.coerceIn(-1.0, 1.0))
        return ClosestApproach((q1 + q2) / 2.0, s, t, q1.distanceTo(q2), angle)
    }

    /**
     * Solves a 2-sight range.
     *
     * @param pathLengthM the path the phone travelled between the sights (≥ the straight baseline).
     * @param trackingLostBetween AR tracking was lost at any time between the sights.
     * @param maxRangeM the product limit (tests may lift it to check coverage at the limit itself).
     */
    fun solve(
        sight1: Sight,
        sight2: Sight,
        pathLengthM: Double,
        trackingLostBetween: Boolean,
        model: RangeErrorModel = RangeErrorModel(),
        maxRangeM: Double = RangeLimits.MAX_RANGE_M,
    ): Triangulation {
        val baseline = sight1.origin.distanceTo(sight2.origin)
        if (trackingLostBetween) return Triangulation(RangeOutcome.Rejected(RangeRejection.TRACKING_LOST), null, 0.0, 0.0, 0.0, baseline)
        if (!pathLengthM.isFinite()) return Triangulation(RangeOutcome.Rejected(RangeRejection.INVALID_INPUT), null, 0.0, 0.0, 0.0, baseline)
        val c = closestApproach(sight1.origin, sight1.direction, sight2.origin, sight2.direction)
            ?: return Triangulation(RangeOutcome.Rejected(RangeRejection.NEAR_PARALLEL), null, 0.0, 0.0, 0.0, baseline)
        fun result(o: RangeOutcome, expected: Double = 0.0) = Triangulation(o, c.point, c.angleRad, c.miss, expected, baseline)

        if (c.s <= 0 || c.t <= 0) return result(RangeOutcome.Rejected(RangeRejection.BEHIND_CAMERA))
        if (c.angleRad < Math.toRadians(RangeLimits.MIN_INTERSECTION_DEG)) return result(RangeOutcome.Rejected(RangeRejection.NEAR_PARALLEL))

        val elapsed = abs(sight2.timeS - sight1.timeS)
        val sigma1 = sight1.aimSigmaRad
        val heading = model.headingSigma(elapsed)
        val sigma2 = sqrt(sight2.aimSigmaRad * sight2.aimSigmaRad + heading * heading)
        val sigmaB = model.baselineSigma(max(pathLengthM, baseline), elapsed)

        // Consistency: the miss is (mostly) the component of the errors along the common normal of the two rays.
        val expectedMiss = sqrt((c.s * sigma1) * (c.s * sigma1) + (c.t * sigma2) * (c.t * sigma2) + sigmaB * sigmaB)
        if (c.miss > RangeLimits.MISS_REJECT_K * expectedMiss) {
            return result(RangeOutcome.Rejected(RangeRejection.INCONSISTENT_SIGHTS), expectedMiss)
        }

        val distance = c.point.distanceTo(sight2.origin)
        val sigmaLin = linearSigma(sight1, sight2, sigma1, sigma2, sigmaB)
            ?: return result(RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN), expectedMiss)
        val inflation = max(1.0, c.miss / (RangeLimits.MISS_INFLATE_K * expectedMiss))
        // The VIO scale error scales the baseline and so the distance (fully correlated, relative).
        val rel = sqrt((sigmaLin / distance) * (sigmaLin / distance) + model.scaleSigmaRel * model.scaleSigmaRel) * inflation
        if (distance > maxRangeM) return result(RangeOutcome.OutOfRange, expectedMiss)
        val m = RangeUncertainty.inverseLaw(distance, rel, model.sigmaMargin)
            ?: return result(RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN), expectedMiss)
        return result(RangeOutcome.Ok(RangeReading(m, RangeMethod.TWO_SIGHT)), expectedMiss)
    }

    /**
     * Sideways distance walked from [sight1]: the component of (position − sight 1 origin) perpendicular to sight 1's
     * ray. Only this part of the walk creates parallax.
     */
    fun perpendicularBaseline(sight1: Sight, position: Vec3): Double {
        val v = position - sight1.origin
        val along = sight1.direction * (v dot sight1.direction)
        return (v - along).length
    }

    /**
     * Sideways walk the app asks for so that a target about [distanceM] away gets a ±95 of about [targetRel95]:
     * rel95 ≈ k · σ_parallax · d / B, with σ_parallax the two sights' aim errors (and heading drift) in quadrature.
     * Clamped to [RangeLimits.MIN_BASELINE_M]..[RangeLimits.MAX_BASELINE_M]; a far target therefore ends with a larger
     * ± than the target (honestly shown).
     */
    fun requiredBaseline(
        distanceM: Double,
        aimSigmaRad: Double,
        model: RangeErrorModel = RangeErrorModel(),
        targetRel95: Double = RangeLimits.TARGET_REL95,
        expectedWalkS: Double = 20.0,
    ): Double {
        if (!(distanceM > 0) || !distanceM.isFinite()) return RangeLimits.MIN_BASELINE_M
        val heading = model.headingSigma(expectedWalkS)
        val parallax = sqrt(2 * aimSigmaRad * aimSigmaRad + heading * heading)
        val b = Uncertainty.K95 * model.sigmaMargin * parallax * distanceM / targetRel95
        return b.coerceIn(RangeLimits.MIN_BASELINE_M, RangeLimits.MAX_BASELINE_M)
    }

    /** σ of the distance from sight 2 by central differences over the 7 independent error sources (null if degenerate). */
    private fun linearSigma(s1: Sight, s2: Sight, sigma1: Double, sigma2: Double, sigmaB: Double): Double? {
        val (u1, v1) = perpendicularAxes(s1.direction)
        val (u2, v2) = perpendicularAxes(s2.direction)
        fun dist(o1: Vec3, d1: Vec3, d2: Vec3): Double? =
            closestApproach(o1, d1, s2.origin, d2)?.point?.distanceTo(s2.origin)

        var variance = 0.0
        fun term(plus: Double?, minus: Double?): Boolean {
            if (plus == null || minus == null || !plus.isFinite() || !minus.isFinite()) return false
            val g = (plus - minus) / 2
            variance += g * g
            return true
        }
        val o1 = s1.origin
        val d1 = s1.direction
        val d2 = s2.direction
        val ok = term(dist(o1, d1 + u1 * sigma1, d2), dist(o1, d1 - u1 * sigma1, d2)) &&
            term(dist(o1, d1 + v1 * sigma1, d2), dist(o1, d1 - v1 * sigma1, d2)) &&
            term(dist(o1, d1, d2 + u2 * sigma2), dist(o1, d1, d2 - u2 * sigma2)) &&
            term(dist(o1, d1, d2 + v2 * sigma2), dist(o1, d1, d2 - v2 * sigma2)) &&
            term(dist(o1 + Vec3.UNIT_X * sigmaB, d1, d2), dist(o1 - Vec3.UNIT_X * sigmaB, d1, d2)) &&
            term(dist(o1 + Vec3.UNIT_Y * sigmaB, d1, d2), dist(o1 - Vec3.UNIT_Y * sigmaB, d1, d2)) &&
            term(dist(o1 + Vec3.UNIT_Z * sigmaB, d1, d2), dist(o1 - Vec3.UNIT_Z * sigmaB, d1, d2))
        return if (ok) sqrt(variance) else null
    }

    /** Two unit vectors perpendicular to unit [d] and to each other (the first horizontal unless d is vertical). */
    internal fun perpendicularAxes(d: Vec3): Pair<Vec3, Vec3> {
        var u = d cross Vec3.UP
        if (u.length < 1e-6) u = d cross Vec3.UNIT_X
        u = u.normalized()
        val v = (u cross d).normalized()
        return u to v
    }
}
