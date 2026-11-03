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

/** Per-endpoint aim/localisation error inputs from the AR layer. */
data class LocalisationInput(
    val rangeMeters: Double,       // camera-to-point distance
    val focalLengthPx: Double,     // camera intrinsics focal length in pixels
    val pixelErrorPx: Double = 2.0 // user aim/tap + edge error in pixels (tunable)
) {
    init {
        require(rangeMeters.isFinite() && rangeMeters >= 0) { "rangeMeters must be finite and >= 0" }
        require(focalLengthPx.isFinite() && focalLengthPx > 0) { "focalLengthPx must be finite and > 0" }
        require(pixelErrorPx.isFinite() && pixelErrorPx >= 0) { "pixelErrorPx must be finite and >= 0" }
    }

    /** 1σ localisation error of the endpoint (m), isotropic: pixels × range / focal. */
    val sigmaMeters: Double get() = pixelErrorPx * rangeMeters / focalLengthPx
}

/**
 * Device accuracy tier: sets the prior for the distance-proportional VIO / depth scale error, and the
 * conservative absolute floor used when the AR layer supplies no [LocalisationInput].
 *
 * @property scale95 relative scale error at ≈95% (e.g. 0.02 = "±2% of the length").
 * @property absFloor95 smallest ±95 a point-to-point length may show when neither endpoint carries
 *   localisation information (research: "never better than ±1 cm" on non-LiDAR Android). It is applied
 *   as an isotropic per-endpoint σ of [unlocalisedEndpointSigma], added in quadrature to the jitter
 *   standard error, so the ± can never collapse to frame jitter alone.
 * Values are engineering estimates from docs/research/measurement-engine.md §1.2/§1.4 and **must be tuned**
 * from the bench-test protocol per device tier.
 */
enum class DeviceTier(val scale95: Double, val absFloor95: Double) {
    /** ARCore phone with Depth API (depth-from-motion): ≈ ±1.5% at 95%, floor ±1 cm. */
    DEPTH(0.015, 0.010),

    /** ARCore VIO + plane hit-tests only: ≈ ±2% at 95% (the default), floor ±1 cm. */
    NO_DEPTH(0.02, 0.010),

    /** Hardware depth (ToF / LiDAR): ≈ ±0.8% at 95%, floor ±5 mm. */
    DEPTH_SENSOR(0.008, 0.005);

    /** 1σ relative scale error. */
    val scaleSigmaRel: Double get() = scale95 / Uncertainty.K95

    /**
     * 1σ isotropic localisation error assumed for an endpoint that has no [LocalisationInput]
     * (two such endpoints give a length σ of [absFloor95] / K95).
     */
    val unlocalisedEndpointSigma: Double get() = absFloor95 / (Uncertainty.K95 * sqrt(2.0))
}

/**
 * A raw (uncalibrated) length straight from AR geometry, with its **random** 1σ only
 * (endpoint standard errors, localisation). Systematic scale error is added by [UncertaintyModel].
 *
 * @property isMeasured false for user-entered / preset values: no scale correction or scale error applies.
 * @property unlocalisedEndpoints how many of the endpoints behind this length carried no localisation
 *   term (only frame jitter). [UncertaintyModel] adds the tier's [DeviceTier.unlocalisedEndpointSigma]
 *   in quadrature for each. [Uncertainty.rawDistance] and friends set it from
 *   [UncertainPoint.includesLocalisation]; a hand-made RawLength defaults to 0 (its σ is taken as complete).
 */
class RawLength(
    val value: Double,
    val randomSigma: Double,
    val isMeasured: Boolean = true,
    val unlocalisedEndpoints: Int = 0,
) {
    init { require(value.isFinite() && randomSigma.isFinite() && randomSigma >= 0 && unlocalisedEndpoints >= 0) }
    override fun toString(): String =
        "RawLength($value, σr=$randomSigma, measured=$isMeasured, unlocalised=$unlocalisedEndpoints)"
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
 * σ_random always contains endpoint localisation (MAJOR-1): from a [LocalisationInput] when the caller
 * supplies one, otherwise the tier's conservative [DeviceTier.unlocalisedEndpointSigma] per endpoint
 * (see [RawLength.unlocalisedEndpoints], [localise]). A jitter-only standard error is never the whole ±.
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

    /**
     * This model if its calibration (if any) is valid for a measurement on [plane] at [rangeM], otherwise
     * the same model without calibration (research §2.9: the factor corrects scale "for that plane and
     * session"; depth bias depends on plane and distance). Unknown plane/range (null) does not exclude.
     */
    fun forScope(plane: Plane?, rangeM: Double?): UncertaintyModel {
        val c = calibration ?: return this
        return if (c.appliesTo(plane, rangeM)) this else withCalibration(null)
    }

    /**
     * Adds the endpoint localisation term to [p]: [localisation]'s σ when given, otherwise the tier's
     * conservative [DeviceTier.unlocalisedEndpointSigma]. Points that already include localisation are
     * returned unchanged (no double counting).
     */
    fun localise(p: UncertainPoint, localisation: LocalisationInput? = null): UncertainPoint = when {
        p.includesLocalisation -> p
        localisation != null -> p.withLocalisation(localisation)
        else -> p.withLocalisationSigma(tier.unlocalisedEndpointSigma)
    }

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
        val floorVar = tier.unlocalisedEndpointSigma * tier.unlocalisedEndpointSigma
        for (r in raws) {
            val kk = if (r.isMeasured) k else 1.0
            val v = kk * r.value
            // Endpoints with jitter only get the tier's conservative localisation floor (MAJOR-1).
            val rs2 = r.randomSigma * r.randomSigma + if (r.isMeasured) r.unlocalisedEndpoints * floorVar else 0.0
            val s = kk * sqrt(rs2)
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

    /**
     * Point-to-point distance. Each endpoint gets its localisation term ([localise]): from
     * [localisationA]/[localisationB] when given, else the tier floor. With both ranges known, a
     * calibration outside its range scope is not applied ([forScope]).
     */
    fun distance(
        a: UncertainPoint,
        b: UncertainPoint,
        localisationA: LocalisationInput? = null,
        localisationB: LocalisationInput? = null,
        extraRelSigma: Double = 0.0,
    ): Measurement = forScope(null, meanRange(localisationA, localisationB))
        .length(Uncertainty.rawDistance(localise(a, localisationA), localise(b, localisationB)), extraRelSigma)

    /** Vertical component of b − a (height), along [up]. Localisation as in [distance]. */
    fun height(
        a: UncertainPoint,
        b: UncertainPoint,
        up: Vec3 = Vec3.UP,
        localisationA: LocalisationInput? = null,
        localisationB: LocalisationInput? = null,
        extraRelSigma: Double = 0.0,
    ): Measurement = forScope(null, meanRange(localisationA, localisationB))
        .length(Uncertainty.rawHeight(localise(a, localisationA), localise(b, localisationB), up), extraRelSigma)

    /** Horizontal component of b − a (floor distance), perpendicular to [up]. Localisation as in [distance]. */
    fun horizontalDistance(
        a: UncertainPoint,
        b: UncertainPoint,
        up: Vec3 = Vec3.UP,
        localisationA: LocalisationInput? = null,
        localisationB: LocalisationInput? = null,
        extraRelSigma: Double = 0.0,
    ): Measurement = forScope(null, meanRange(localisationA, localisationB))
        .length(Uncertainty.rawHorizontal(localise(a, localisationA), localise(b, localisationB), up), extraRelSigma)

    companion object {
        /** Default residual relative scale σ after calibration (0.3%). */
        const val DEFAULT_CALIBRATED_FLOOR_REL = 0.003

        /** Mean camera range of the given localisations, or null if none carries one. */
        internal fun meanRange(vararg locs: LocalisationInput?): Double? {
            val r = locs.filterNotNull().map { it.rangeMeters }
            return if (r.isEmpty()) null else r.average()
        }
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
        return RawLength(d, sqrt(variance.coerceAtLeast(0.0)), unlocalisedEndpoints = unlocalised(a, b))
    }

    /** Raw |(b − a)·up| (height). */
    fun rawHeight(a: UncertainPoint, b: UncertainPoint, up: Vec3 = Vec3.UP): RawLength {
        val u = up.normalized()
        val h = abs((b.position - a.position) dot u)
        val variance = a.covariance.along(u) + b.covariance.along(u)
        return RawLength(h, sqrt(variance.coerceAtLeast(0.0)), unlocalisedEndpoints = unlocalised(a, b))
    }

    private fun unlocalised(a: UncertainPoint, b: UncertainPoint): Int =
        (if (a.includesLocalisation) 0 else 1) + (if (b.includesLocalisation) 0 else 1)

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
        return RawLength(d, sqrt(variance.coerceAtLeast(0.0)), unlocalisedEndpoints = unlocalised(a, b))
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
     * intersection: a plane δ too far scales lengths by (1 + δ/h).
     *
     * [viewingDistanceM] must be the **perpendicular** camera-to-plane distance h, not the range along
     * the ray (h = range·cos θ); passing the range under-states the term by cos θ. Prefer the overload
     * taking the camera position, which computes h itself.
     */
    fun planeBiasRel(fit: PlaneFit, viewingDistanceM: Double): Double {
        require(viewingDistanceM > 0)
        return fit.offsetStandardError / viewingDistanceM
    }

    /**
     * Relative length error from the fitted plane's offset uncertainty for a camera at [cameraPosition]:
     * σ_offset / h with h = |signed distance of the camera from the plane| (perpendicular). When [at] is
     * given, the offset σ there includes the normal-tilt term ([PlaneFit.offsetSigmaAt]), which grows
     * with the distance from the fit's centroid.
     */
    fun planeBiasRel(fit: PlaneFit, cameraPosition: Vec3, at: Vec3? = null): Double {
        val h = abs(fit.plane.signedDistance(cameraPosition))
        require(h > 1e-6) { "Camera lies on the plane" }
        val offset = if (at == null) fit.offsetStandardError else fit.offsetSigmaAt(at)
        return offset / h
    }
}
