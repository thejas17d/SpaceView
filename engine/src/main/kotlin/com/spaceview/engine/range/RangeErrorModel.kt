package com.spaceview.engine.range

import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import kotlin.math.sqrt

/**
 * 1σ error inputs of the rangefinder that do not come from the aim model. Every default is a placeholder to tune
 * from the Pixel 10a device test (TESTING "Distance"); the aim error itself comes from the AR layer's `AimModel`.
 *
 * @property attitudeSigmaRad error of ARCore's gravity direction (camera pitch / roll) per sight.
 * @property floorHeightSigmaM error of the floor height under the camera (FloorEstimator bins + plane noise).
 * @property scaleSigmaRel relative VIO scale error of AR lengths (camera height, baseline): the device tier's prior.
 * @property levelGroundSigmaRad Ground only: slope of a "level" floor / paved ground along the line of sight. The
 *   badge "assumes level ground" says that a sloped ground is outside this ±.
 * @property baselineRelSigma 2-sight: VIO position error per axis as a fraction of the path walked between sights.
 * @property baselineDriftM 2-sight: fixed VIO position error per axis between two sights (m).
 * @property baselineDriftPerS 2-sight: VIO position drift per axis per second between sights (m/s).
 * @property headingSigmaRad 2-sight: relative attitude error (mostly yaw) between the two sights.
 * @property headingDriftPerS 2-sight: relative attitude drift per second between the sights (rad/s).
 * @property sigmaMargin linearisation margin on every rangefinder σ (≥ 1).
 */
class RangeErrorModel(
    val attitudeSigmaRad: Double = Math.toRadians(0.10),
    val floorHeightSigmaM: Double = 0.02,
    val scaleSigmaRel: Double = 0.01,
    val levelGroundSigmaRad: Double = Math.toRadians(0.15),
    val baselineRelSigma: Double = 0.01,
    val baselineDriftM: Double = 0.01,
    val baselineDriftPerS: Double = 0.002,
    val headingSigmaRad: Double = Math.toRadians(0.10),
    val headingDriftPerS: Double = Math.toRadians(0.005),
    val sigmaMargin: Double = 1.05,
) {
    init {
        val all = listOf(
            attitudeSigmaRad, floorHeightSigmaM, scaleSigmaRel, levelGroundSigmaRad, baselineRelSigma,
            baselineDriftM, baselineDriftPerS, headingSigmaRad, headingDriftPerS,
        )
        require(all.all { it >= 0 && it.isFinite() }) { "error terms must be finite and >= 0" }
        require(sigmaMargin >= 1.0 && sigmaMargin.isFinite()) { "sigmaMargin must be >= 1" }
    }

    /** 2-sight: per-axis σ of the position of sight 1 relative to sight 2 (m). */
    fun baselineSigma(pathLengthM: Double, elapsedS: Double): Double {
        val rel = baselineRelSigma * pathLengthM.coerceAtLeast(0.0)
        val drift = baselineDriftM + baselineDriftPerS * elapsedS.coerceAtLeast(0.0)
        return sqrt(rel * rel + drift * drift)
    }

    /** 2-sight: relative attitude σ between the sights after [elapsedS] seconds (rad). */
    fun headingSigma(elapsedS: Double): Double = headingSigmaRad + headingDriftPerS * elapsedS.coerceAtLeast(0.0)
}

/** Shared uncertainty helpers of the rangefinder. */
object RangeUncertainty {
    /**
     * Conservative symmetric 95% bound for a quantity that goes as 1 / (an angle): a distance from a depression angle
     * (Ground) or from a parallax angle (2-sight). With a linearised relative σ [relSigma], the angle's 95% interval
     * maps to [value / (1 + a), value / (1 − a)] with a = k·relSigma·margin; the larger (upper) half-width,
     * value · a / (1 − a), is used on both sides, so the skewed tail is covered. Returns the [Measurement] whose
     * bound95 is that half-width, or null when a ≥ [RangeLimits.MAX_INVERSE_K_REL] (no meaningful interval).
     */
    fun inverseLaw(value: Double, relSigma: Double, margin: Double = 1.0): Measurement? {
        if (!(value > 0) || !value.isFinite() || !(relSigma >= 0) || !relSigma.isFinite()) return null
        val a = Uncertainty.K95 * relSigma * margin
        if (a >= RangeLimits.MAX_INVERSE_K_REL) return null
        val half = value * a / (1 - a)
        return Measurement(value, half / Uncertainty.K95)
    }
}
