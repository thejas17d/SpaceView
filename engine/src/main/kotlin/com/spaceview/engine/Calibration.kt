package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Reference objects of known size for scale calibration.
 *
 * @property longSide true long side (m)
 * @property shortSide true short side (m)
 * @property toleranceSigma 1σ manufacturing tolerance of each side (m). ID-1 per ISO/IEC 7810
 *   (85.47–85.72 × 53.92–54.03 mm, so ~0.1 mm); A4 per ISO 216 (typical office paper is within ~0.5 mm;
 *   the standard allows ±2 mm — tune if field data shows cheap paper is worse).
 */
enum class ReferenceObject(val longSide: Double, val shortSide: Double, val toleranceSigma: Double) {
    /** ISO/IEC 7810 ID-1 card (bank card, PVC Aadhaar, driving licence): 85.60 × 53.98 mm. */
    ID1_CARD(0.08560, 0.05398, 0.0001),

    /** ISO 216 A4 sheet: 297 × 210 mm. Prefer it beyond ~50 cm (a card is too few pixels there). */
    A4(0.297, 0.210, 0.0005),
}

/** One calibration observation: a length with known true value, and what AR measured for it (raw). */
class KnownLength(val trueValue: Double, val trueSigma: Double, val measured: RawLength) {
    init { require(trueValue > 0 && trueSigma >= 0) }
}

/**
 * Scale correction: calibrated length = raw length × [factor].
 *
 * @property factor true / measured (weighted over observations).
 * @property sigma 1σ of [factor], including the residual between observations (Birge-ratio inflation).
 * @property residual largest |kᵢ − factor| among individual observations (diagnostic).
 * @property reference the reference object used, if any.
 * @property plane plane the reference lay on, if known. The factor corrects scale for that plane
 *   (research §2.9); lengths on other planes are measured uncalibrated ([appliesTo]).
 * @property anchor a point on [plane] where the reference was (e.g. its centroid), used to compare
 *   plane offsets locally. Defaults to [Plane.point] when null.
 * @property rangeM camera-to-reference distance (m) at calibration, if known. Depth bias is
 *   distance-dependent (δZ/Z), so the factor is only used within ±[RANGE_TOLERANCE_REL] of it.
 */
class ScaleCalibration(
    val factor: Double,
    val sigma: Double,
    val residual: Double = 0.0,
    val reference: ReferenceObject? = null,
    val plane: Plane? = null,
    val anchor: Vec3? = null,
    val rangeM: Double? = null,
) {
    init {
        require(factor > 0 && factor.isFinite() && sigma >= 0 && sigma.isFinite())
        require(rangeM == null || (rangeM > 0 && rangeM.isFinite()))
    }

    /**
     * True if this calibration may be applied to a measurement on [targetPlane] at [targetRangeM].
     * Unknown values (null on either side) do not exclude. Planes must agree within
     * [MAX_PLANE_ANGLE_DEG] and [MAX_PLANE_OFFSET_M] at [anchor]; ranges within ±[RANGE_TOLERANCE_REL].
     */
    fun appliesTo(targetPlane: Plane?, targetRangeM: Double?): Boolean {
        val r = rangeM
        if (r != null && targetRangeM != null) {
            if (targetRangeM < r * (1 - RANGE_TOLERANCE_REL) || targetRangeM > r * (1 + RANGE_TOLERANCE_REL)) return false
        }
        val p = plane
        if (p != null && targetPlane != null) {
            if (abs(p.normal dot targetPlane.normal) < cos(Math.toRadians(MAX_PLANE_ANGLE_DEG))) return false
            val at = anchor ?: p.point
            if (abs(targetPlane.signedDistance(at)) > MAX_PLANE_OFFSET_M) return false
        }
        return true
    }

    /** Relative 1σ of the factor. */
    val relSigma: Double get() = sigma / factor

    /** Applies the factor to a raw length (m). */
    fun apply(rawLength: Double): Double = rawLength * factor

    override fun toString(): String =
        "ScaleCalibration(k=$factor ± $sigma, residual=$residual, ref=$reference, range=$rangeM)"

    companion object {
        /** Planes whose normals differ by more than this are "other planes" (tunable). */
        const val MAX_PLANE_ANGLE_DEG = 10.0

        /** Planes further apart than this at the anchor are "other planes" (tunable). */
        const val MAX_PLANE_OFFSET_M = 0.05

        /** The factor applies within ±50% of the calibration range (tunable). */
        const val RANGE_TOLERANCE_REL = 0.5
    }
}

/** Outcome of a calibration attempt. */
sealed class CalibrationResult {
    /** The calibration is plausible and precise enough to use. */
    class Accepted(val calibration: ScaleCalibration) : CalibrationResult()

    /** Rejected; [factor] is the computed value (NaN if it could not be computed). */
    class Rejected(val reason: Reason, val factor: Double) : CalibrationResult()

    /** Why a calibration was rejected (drives the user message). */
    enum class Reason {
        /** Measured length was zero / invalid. */
        INVALID_INPUT,

        /** Factor outside [Calibrator.MIN_FACTOR]..[Calibrator.MAX_FACTOR]: tracking problem or wrong reference. */
        FACTOR_OUT_OF_RANGE,

        /** Sides disagree beyond their uncertainty (skewed outline, wrong object, bad corner). */
        INCONSISTENT,

        /**
         * Factor uncertainty above [Calibrator.MAX_REL_SIGMA], or the calibrated scale σ (with its floor)
         * would not be below [Calibrator.MIN_IMPROVEMENT] × the uncalibrated prior: reference too small/far to help.
         */
        TOO_NOISY,
    }
}

/** Computes scale calibration from known references. */
object Calibrator {
    /**
     * Plausible factor band, exclusive (research §2.9: apply only if 0.9 < s < 1.1). A 10%+ scale
     * error means tracking is broken, so we refuse rather than "fix" it.
     */
    const val MIN_FACTOR = 0.9
    const val MAX_FACTOR = 1.1

    /** Calibration whose relative 1σ exceeds 1% is not worth applying (the VIO prior is ~1%). */
    const val MAX_REL_SIGMA = 0.01

    /**
     * Accept only if √(relSigma² + floor²) < this × the model's uncalibrated scale σ, so "precision
     * mode" is always clearly tighter than not calibrating (MINOR-4; tunable).
     */
    const val MIN_IMPROVEMENT = 0.7

    /** Reduced χ² above which observations are called inconsistent (≈ p < 0.001 for 1 dof is 10.8). */
    const val MAX_REDUCED_CHI2 = 10.8

    /**
     * Calibration from a measured reference rectangle. The two measured sides may be given in any order;
     * the longer one is matched to the reference's long side.
     */
    fun fromReference(
        ref: ReferenceObject,
        sideA: RawLength,
        sideB: RawLength,
        model: UncertaintyModel = UncertaintyModel(),
        plane: Plane? = null,
        anchor: Vec3? = null,
        rangeM: Double? = null,
    ): CalibrationResult {
        val (long, short) = if (sideA.value >= sideB.value) sideA to sideB else sideB to sideA
        return calibrate(
            listOf(
                KnownLength(ref.longSide, ref.toleranceSigma, long),
                KnownLength(ref.shortSide, ref.toleranceSigma, short),
            ),
            ref, model, plane, anchor, rangeM,
        )
    }

    /**
     * Calibration from a measured reference rectangle (its raw width/height, whose σ includes corner
     * localisation). The result is scoped to the rectangle's plane and centroid; pass [rangeM]
     * (camera-to-reference distance) to scope it by distance too.
     */
    fun fromReference(
        ref: ReferenceObject,
        rect: RectangleMeasurement,
        model: UncertaintyModel = UncertaintyModel(),
        rangeM: Double? = null,
    ): CalibrationResult {
        val centroid = rect.corners.fold(Vec3.ZERO) { acc, p -> acc + p } / rect.corners.size.toDouble()
        return fromReference(ref, rect.rawWidth, rect.rawHeight, model, rect.plane, centroid, rangeM)
    }

    /**
     * Weighted calibration from any known lengths: kᵢ = trueᵢ / measuredᵢ, weights 1/σᵢ², with the
     * factor σ inflated by √(χ²/(n−1)) when observations scatter more than their stated errors.
     */
    fun calibrate(
        observations: List<KnownLength>,
        reference: ReferenceObject? = null,
        model: UncertaintyModel = UncertaintyModel(),
        plane: Plane? = null,
        anchor: Vec3? = null,
        rangeM: Double? = null,
    ): CalibrationResult {
        if (observations.isEmpty()) return CalibrationResult.Rejected(CalibrationResult.Reason.INVALID_INPUT, Double.NaN)
        val ks = DoubleArray(observations.size)
        val sig = DoubleArray(observations.size)
        for ((i, o) in observations.withIndex()) {
            val m = o.measured.value
            if (!(m > 0) || !o.measured.isMeasured) {
                return CalibrationResult.Rejected(CalibrationResult.Reason.INVALID_INPUT, Double.NaN)
            }
            val k = o.trueValue / m
            // Endpoints without localisation get the tier floor, exactly as in UncertaintyModel (MAJOR-1).
            val floor = model.tier.unlocalisedEndpointSigma
            val measVar = o.measured.randomSigma * o.measured.randomSigma + o.measured.unlocalisedEndpoints * floor * floor
            val rel2 = (o.trueSigma / o.trueValue).let { it * it } + measVar / (m * m)
            ks[i] = k
            // Guard against zero stated error so weights stay finite.
            sig[i] = (k * sqrt(rel2)).coerceAtLeast(k * 1e-6)
        }
        var sw = 0.0
        var swk = 0.0
        for (i in ks.indices) { val w = 1 / (sig[i] * sig[i]); sw += w; swk += w * ks[i] }
        val k = swk / sw
        var sigmaK = 1 / sqrt(sw)
        var chi2 = 0.0
        var residual = 0.0
        for (i in ks.indices) {
            val r = ks[i] - k
            chi2 += r * r / (sig[i] * sig[i])
            residual = maxOf(residual, abs(r))
        }
        val dof = ks.size - 1
        if (!(k > MIN_FACTOR && k < MAX_FACTOR)) {
            return CalibrationResult.Rejected(CalibrationResult.Reason.FACTOR_OUT_OF_RANGE, k)
        }
        if (dof > 0) {
            val reduced = chi2 / dof
            if (reduced > MAX_REDUCED_CHI2) return CalibrationResult.Rejected(CalibrationResult.Reason.INCONSISTENT, k)
            if (reduced > 1) sigmaK *= sqrt(reduced)
        }
        val rel = sigmaK / k
        if (rel > MAX_REL_SIGMA) return CalibrationResult.Rejected(CalibrationResult.Reason.TOO_NOISY, k)
        // MINOR-4: never accept a "precision mode" whose effective σ is not clearly tighter than the prior.
        val effective = sqrt(rel * rel + model.calibratedScaleFloorRel * model.calibratedScaleFloorRel)
        if (!(effective < MIN_IMPROVEMENT * model.scaleSigmaRel)) {
            return CalibrationResult.Rejected(CalibrationResult.Reason.TOO_NOISY, k)
        }
        return CalibrationResult.Accepted(ScaleCalibration(k, sigmaK, residual, reference, plane, anchor, rangeM))
    }
}
