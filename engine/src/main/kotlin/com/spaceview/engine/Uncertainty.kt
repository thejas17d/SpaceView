package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A measured quantity with its 1σ standard uncertainty, in SI units (m, m², m³).
 *
 * The UI shows `value ± [bound95]` (≈95% coverage, k = 1.96). Display rounding never shows more
 * precision than that bound (see [Units]).
 */
class Measurement(val value: Double, val sigma: Double) {
    init {
        require(value.isFinite()) { "Measurement value must be finite" }
        require(sigma >= 0 && sigma.isFinite()) { "sigma must be finite and >= 0" }
    }

    /** Half-width of the ≈95% interval, the number shown after "±". */
    val bound95: Double get() = Uncertainty.K95 * sigma

    /** [bound95] relative to |value| (∞ for a zero value with non-zero sigma). */
    val relative95: Double
        get() = when {
            value != 0.0 -> bound95 / abs(value)
            sigma == 0.0 -> 0.0
            else -> Double.POSITIVE_INFINITY
        }

    /** Lower end of the 95% interval. */
    val low95: Double get() = value - bound95

    /** Upper end of the 95% interval. */
    val high95: Double get() = value + bound95

    /** Quality badge, treating this as a **length** (see [Quality.of]). */
    val quality: Quality get() = Quality.of(this)

    override fun equals(other: Any?): Boolean = other is Measurement && value == other.value && sigma == other.sigma
    override fun hashCode(): Int = value.hashCode() * 31 + sigma.hashCode()
    override fun toString(): String = "Measurement($value ± $bound95 (σ=$sigma))"

    companion object {
        /** A value with no uncertainty (manual entry "lock as exact", spec sheet). */
        fun exact(value: Double) = Measurement(value, 0.0)
    }
}

/**
 * Quality badge shown next to every number (UI: Good ● / Fair ◐ / Rough ○).
 *
 * Primarily driven by the relative 95% error. The absolute thresholds let small objects reach
 * GOOD/FAIR (an 8 cm card can never be within 1% on a phone), but only while the relative error stays
 * under [ABS_OVERRIDE_MAX_REL], so a 10 cm object at ±1.5 cm is not called "fair".
 * All thresholds are placeholders to tune from the bench-test protocol (PLAN §7).
 */
enum class Quality {
    GOOD, FAIR, ROUGH;

    companion object {
        /** GOOD if ±95 ≤ 1% of the value … */
        const val GOOD_REL = 0.01
        /** … or ±95 ≤ 5 mm. */
        const val GOOD_ABS = 0.005
        /** FAIR if ±95 ≤ 3% of the value … */
        const val FAIR_REL = 0.03
        /** … or ±95 ≤ 2 cm. */
        const val FAIR_ABS = 0.02
        /** Absolute thresholds apply only while the relative error is at most 10%. */
        const val ABS_OVERRIDE_MAX_REL = 0.10

        /** Badge for a length measurement. */
        fun of(m: Measurement): Quality = of(m.relative95, m.bound95)

        /** Badge from a relative and an absolute (m) 95% bound. */
        fun of(relative95: Double, absolute95: Double): Quality {
            val absOk = relative95 <= ABS_OVERRIDE_MAX_REL
            return when {
                relative95 <= GOOD_REL || (absOk && absolute95 <= GOOD_ABS) -> GOOD
                relative95 <= FAIR_REL || (absOk && absolute95 <= FAIR_ABS) -> FAIR
                else -> ROUGH
            }
        }

        /** The worse of several badges (ROUGH beats FAIR beats GOOD). */
        fun worst(vararg q: Quality): Quality = q.maxByOrNull { it.ordinal } ?: GOOD
    }
}

/**
 * Device accuracy tier: sets the prior for the distance-proportional VIO / depth scale error.
 *
 * @property scale95 relative scale error at ≈95% (e.g. 0.02 = "±2% of the length").
 * Values are engineering estimates from docs/research/measurement-engine.md §1.2/§1.4 and **must be tuned**
 * from the bench-test protocol per device tier.
 */
enum class DeviceTier(val scale95: Double) {
    /** ARCore phone with Depth API (depth-from-motion): ≈ ±1.5% at 95%. */
    DEPTH(0.015),

    /** ARCore VIO + plane hit-tests only: ≈ ±2% at 95% (the default). */
    NO_DEPTH(0.02),

    /** Hardware depth (ToF / LiDAR): ≈ ±0.8% at 95%. */
    DEPTH_SENSOR(0.008);

    /** 1σ relative scale error. */
    val scaleSigmaRel: Double get() = scale95 / Uncertainty.K95
}

/**
 * A raw (uncalibrated) length straight from AR geometry, with its **random** 1σ only
 * (endpoint standard errors, localisation). Systematic scale error is added by [UncertaintyModel].
 *
 * @property isMeasured false for user-entered / preset values: no scale correction or scale error applies.
 */
class RawLength(val value: Double, val randomSigma: Double, val isMeasured: Boolean = true) {
    init { require(value.isFinite() && randomSigma.isFinite() && randomSigma >= 0) }
    override fun toString(): String = "RawLength($value, σr=$randomSigma, measured=$isMeasured)"
}

/**
 * Turns raw AR geometry into [Measurement]s:
 *
 * σ² = (k·σ_random)² + (σ_scale,rel · value)² + (extraRel · value)²
 *
 * where k is the calibration scale factor (1 when uncalibrated) and σ_scale,rel is the device-tier VIO
 * scale error, or — once calibrated — the calibration's own relative uncertainty (which includes its
 * residual) combined with [calibratedScaleFloorRel] for what calibration cannot fix (drift, other planes).
 *
 * Immutable; use [withCalibration] to derive a calibrated model.
 */
class UncertaintyModel(
    val tier: DeviceTier = DeviceTier.NO_DEPTH,
    val calibration: ScaleCalibration? = null,
    /** Uncalibrated 1σ relative scale error; defaults to the tier prior. */
    val scaleSigmaRel: Double = tier.scaleSigmaRel,
    /** Residual 1σ relative scale error that remains after calibration (research: ~0.3–0.5%). */
    val calibratedScaleFloorRel: Double = DEFAULT_CALIBRATED_FLOOR_REL,
) {
    init { require(scaleSigmaRel >= 0 && calibratedScaleFloorRel >= 0) }

    /** Scale factor applied to raw lengths (1.0 when uncalibrated). */
    val scaleFactor: Double get() = calibration?.factor ?: 1.0

    /** 1σ relative scale error actually used. */
    val effectiveScaleSigmaRel: Double
        get() {
            val c = calibration ?: return scaleSigmaRel
            return sqrt(c.relSigma * c.relSigma + calibratedScaleFloorRel * calibratedScaleFloorRel)
        }

    /** Same model with [c] applied (null removes calibration). */
    fun withCalibration(c: ScaleCalibration?) = UncertaintyModel(tier, c, scaleSigmaRel, calibratedScaleFloorRel)

    /** Converts a raw length to a calibrated [Measurement]. [extraRelSigma] adds e.g. a plane-bias term. */
    fun length(raw: RawLength, extraRelSigma: Double = 0.0): Measurement = combine(listOf(raw), extraRelSigma)

    /**
     * Product of raw lengths (area for 2, volume for 3) with correct propagation: random parts add in
     * relative quadrature; the scale error is fully correlated, so it adds linearly (2s for area, 3s for volume).
     */
    fun product(raws: List<RawLength>, extraRelSigma: Double = 0.0): Measurement = combine(raws, extraRelSigma)

    private fun combine(raws: List<RawLength>, extraRelSigma: Double): Measurement {
        require(raws.isNotEmpty())
        var value = 1.0
        var relRandVar = 0.0
        var absRandVar = 0.0 // only used for single lengths (keeps σ meaningful at value = 0)
        var measuredDims = 0
        val k = scaleFactor
        for (r in raws) {
            val kk = if (r.isMeasured) k else 1.0
            val v = kk * r.value
            val s = kk * r.randomSigma
            value *= v
            if (r.isMeasured) measuredDims++
            if (raws.size == 1) absRandVar = s * s
            else relRandVar += if (v != 0.0) (s / v) * (s / v) else 0.0
        }
        val scaleRel = measuredDims * effectiveScaleSigmaRel
        val extraRel = measuredDims * extraRelSigma
        val randVar = if (raws.size == 1) absRandVar else relRandVar * value * value
        val v2 = value * value
        val variance = randVar + scaleRel * scaleRel * v2 + extraRel * extraRel * v2
        return Measurement(value, sqrt(variance))
    }

    /** Point-to-point distance. */
    fun distance(a: UncertainPoint, b: UncertainPoint): Measurement = length(Uncertainty.rawDistance(a, b))

    /** Vertical component of b − a (height), along [up]. */
    fun height(a: UncertainPoint, b: UncertainPoint, up: Vec3 = Vec3.UP): Measurement =
        length(Uncertainty.rawHeight(a, b, up))

    /** Horizontal component of b − a (floor distance), perpendicular to [up]. */
    fun horizontalDistance(a: UncertainPoint, b: UncertainPoint, up: Vec3 = Vec3.UP): Measurement =
        length(Uncertainty.rawHorizontal(a, b, up))

    companion object {
        /** Default residual relative scale σ after calibration (0.3%). */
        const val DEFAULT_CALIBRATED_FLOOR_REL = 0.003
    }
}

/** Uncertainty helpers and constants. */
object Uncertainty {
    /** Coverage factor for the displayed ≈95% bound. */
    const val K95 = 1.96

    /** Raw distance between two uncertain points; variance = projection of both covariances on the line. */
    fun rawDistance(a: UncertainPoint, b: UncertainPoint): RawLength {
        val delta = b.position - a.position
        val d = delta.length
        val variance = if (d > 1e-12) {
            val u = delta / d
            a.covariance.along(u) + b.covariance.along(u)
        } else {
            (a.covariance.trace + b.covariance.trace) / 3.0
        }
        return RawLength(d, sqrt(variance.coerceAtLeast(0.0)))
    }

    /** Raw |(b − a)·up| (height). */
    fun rawHeight(a: UncertainPoint, b: UncertainPoint, up: Vec3 = Vec3.UP): RawLength {
        val u = up.normalized()
        val h = abs((b.position - a.position) dot u)
        val variance = a.covariance.along(u) + b.covariance.along(u)
        return RawLength(h, sqrt(variance.coerceAtLeast(0.0)))
    }

    /** Raw length of the horizontal (perpendicular to [up]) component of b − a. */
    fun rawHorizontal(a: UncertainPoint, b: UncertainPoint, up: Vec3 = Vec3.UP): RawLength {
        val u = up.normalized()
        val delta = b.position - a.position
        val horiz = delta - u * (delta dot u)
        val d = horiz.length
        val variance = if (d > 1e-12) {
            val dir = horiz / d
            a.covariance.along(dir) + b.covariance.along(dir)
        } else {
            (a.covariance.trace + b.covariance.trace) / 3.0
        }
        return RawLength(d, sqrt(variance.coerceAtLeast(0.0)))
    }

    /**
     * Endpoint localisation σ (m) for an error of [pixels] image pixels at [rangeM] metres with focal
     * length [focalPx]: one pixel spans range/focal metres (≈2 mm at 1 m on a VGA stream, f≈500 px).
     */
    fun pixelLocalisationSigma(rangeM: Double, focalPx: Double, pixels: Double = 1.0): Double {
        require(rangeM >= 0 && focalPx > 0 && pixels >= 0)
        return pixels * rangeM / focalPx
    }

    /**
     * Relative length error caused by plane-offset uncertainty when points are found by ray–plane
     * intersection at [viewingDistanceM]: a plane δ too far scales lengths by (1 + δ/Z).
     */
    fun planeBiasRel(fit: PlaneFit, viewingDistanceM: Double): Double {
        require(viewingDistanceM > 0)
        return fit.offsetStandardError / viewingDistanceM
    }
}
