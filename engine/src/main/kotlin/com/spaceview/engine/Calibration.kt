package com.spaceview.engine

import kotlin.math.abs
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
 */
class ScaleCalibration(
    val factor: Double,
    val sigma: Double,
    val residual: Double = 0.0,
    val reference: ReferenceObject? = null,
) {
    init { require(factor > 0 && factor.isFinite() && sigma >= 0 && sigma.isFinite()) }

    /** Relative 1σ of the factor. */
    val relSigma: Double get() = sigma / factor

    /** Applies the factor to a raw length (m). */
    fun apply(rawLength: Double): Double = rawLength * factor

    override fun toString(): String = "ScaleCalibration(k=$factor ± $sigma, residual=$residual, ref=$reference)"
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

        /** Factor uncertainty above [Calibrator.MAX_REL_SIGMA]: reference too small/far to help. */
        TOO_NOISY,
    }
}

/** Computes scale calibration from known references. */
object Calibrator {
    /** Smallest plausible factor; outside 0.85–1.15 tracking is broken, so we refuse rather than "fix" it. */
    const val MIN_FACTOR = 0.85
    const val MAX_FACTOR = 1.15

    /** Calibration whose relative 1σ exceeds 1% is not worth applying (the VIO prior is ~1%). */
    const val MAX_REL_SIGMA = 0.01

    /** Reduced χ² above which observations are called inconsistent (≈ p < 0.001 for 1 dof is 10.8). */
    const val MAX_REDUCED_CHI2 = 10.8

    /**
     * Calibration from a measured reference rectangle. The two measured sides may be given in any order;
     * the longer one is matched to the reference's long side.
     */
    fun fromReference(ref: ReferenceObject, sideA: RawLength, sideB: RawLength): CalibrationResult {
        val (long, short) = if (sideA.value >= sideB.value) sideA to sideB else sideB to sideA
        return calibrate(
            listOf(
                KnownLength(ref.longSide, ref.toleranceSigma, long),
                KnownLength(ref.shortSide, ref.toleranceSigma, short),
            ),
            ref,
        )
    }

    /** Calibration from a measured reference rectangle result (uses its raw width/height). */
    fun fromReference(ref: ReferenceObject, rect: RectangleMeasurement): CalibrationResult =
        fromReference(ref, rect.rawWidth, rect.rawHeight)

    /**
     * Weighted calibration from any known lengths: kᵢ = trueᵢ / measuredᵢ, weights 1/σᵢ², with the
     * factor σ inflated by √(χ²/(n−1)) when observations scatter more than their stated errors.
     */
    fun calibrate(observations: List<KnownLength>, reference: ReferenceObject? = null): CalibrationResult {
        if (observations.isEmpty()) return CalibrationResult.Rejected(CalibrationResult.Reason.INVALID_INPUT, Double.NaN)
        val ks = DoubleArray(observations.size)
        val sig = DoubleArray(observations.size)
        for ((i, o) in observations.withIndex()) {
            val m = o.measured.value
            if (!(m > 0) || !o.measured.isMeasured) {
                return CalibrationResult.Rejected(CalibrationResult.Reason.INVALID_INPUT, Double.NaN)
            }
            val k = o.trueValue / m
            val rel2 = (o.trueSigma / o.trueValue).let { it * it } + (o.measured.randomSigma / m).let { it * it }
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
        if (k < MIN_FACTOR || k > MAX_FACTOR) {
            return CalibrationResult.Rejected(CalibrationResult.Reason.FACTOR_OUT_OF_RANGE, k)
        }
        if (dof > 0) {
            val reduced = chi2 / dof
            if (reduced > MAX_REDUCED_CHI2) return CalibrationResult.Rejected(CalibrationResult.Reason.INCONSISTENT, k)
            if (reduced > 1) sigmaK *= sqrt(reduced)
        }
        if (sigmaK / k > MAX_REL_SIGMA) return CalibrationResult.Rejected(CalibrationResult.Reason.TOO_NOISY, k)
        return CalibrationResult.Accepted(ScaleCalibration(k, sigmaK, residual, reference))
    }
}
