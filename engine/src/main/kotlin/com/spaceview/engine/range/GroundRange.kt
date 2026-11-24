package com.spaceview.engine.range

import com.spaceview.engine.Measurement
import com.spaceview.engine.Vec3
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * "Ground" range: the aimed point lies on the (level) floor the camera stands on. With the camera [h] above the floor
 * and the crosshair ray [θ] below the horizon, the line-of-sight distance is d = h / sin θ.
 *
 * Error propagation (1σ, linearised, then the inverse-law bound of [RangeUncertainty.inverseLaw]):
 * (σd / d)² = (σh / h)² + cot²θ · (σaim² + σattitude² + σslope²), with σh² = σfloor² + (scale · h)².
 * The cot θ factor is why Ground only works fairly close: at h = 1.4 m and 0.3° of combined angle error, ±95 reaches
 * 10% near 13 m. Beyond [RangeLimits.GROUND_MAX_REL95] the method is refused and the app offers 2-sight.
 */
object GroundRange {
    /**
     * An ungated Ground solution.
     *
     * @property distance line-of-sight distance with its (inverse-law, margin-included) σ.
     * @property horizontalM horizontal distance to the point (h / tan θ).
     * @property heightSigmaM σ of the camera height above the floor (also the vertical σ of the ground point).
     */
    class Fix(
        val distance: Measurement,
        val horizontalM: Double,
        val depressionRad: Double,
        val cameraHeightM: Double,
        val heightSigmaM: Double,
    ) {
        /** ±95 / distance. */
        val rel95: Double get() = distance.relative95

        /** Within the Ground limit ([RangeLimits.GROUND_MAX_REL95]). */
        val usable: Boolean get() = rel95 <= RangeLimits.GROUND_MAX_REL95
    }

    /** Angle of [direction] below the horizon (rad; negative when it points up). World +Y is up. */
    fun depression(direction: Vec3): Double {
        val l = direction.length
        if (!(l > 0)) return 0.0
        return asin((-direction.y / l).coerceIn(-1.0, 1.0))
    }

    /** Line of sight from a camera [cameraHeightM] above level ground along a ray [depressionRad] below the horizon. */
    fun lineOfSight(cameraHeightM: Double, depressionRad: Double): Double = cameraHeightM / sin(depressionRad)

    /**
     * The solution with its ± (no gate on the ±), or null when the geometry is invalid (camera below
     * [RangeLimits.MIN_CAMERA_HEIGHT_M], ray not below [RangeLimits.MIN_DEPRESSION_DEG]) or the ± is unbounded.
     *
     * @param aimSigmaRad 1σ aim error of the crosshair (per axis), already including the hold jitter.
     */
    fun fix(cameraHeightM: Double, depressionRad: Double, aimSigmaRad: Double, model: RangeErrorModel = RangeErrorModel()): Fix? {
        if (!valid(cameraHeightM, depressionRad, aimSigmaRad)) return null
        if (cameraHeightM < RangeLimits.MIN_CAMERA_HEIGHT_M || depressionRad < Math.toRadians(RangeLimits.MIN_DEPRESSION_DEG)) return null
        val d = lineOfSight(cameraHeightM, depressionRad)
        val sh = heightSigma(cameraHeightM, model)
        val cot = cos(depressionRad) / sin(depressionRad)
        val angleVar = aimSigmaRad * aimSigmaRad + model.attitudeSigmaRad * model.attitudeSigmaRad +
            model.levelGroundSigmaRad * model.levelGroundSigmaRad
        val rel = sqrt((sh / cameraHeightM) * (sh / cameraHeightM) + cot * cot * angleVar)
        val m = RangeUncertainty.inverseLaw(d, rel, model.sigmaMargin) ?: return null
        return Fix(m, cameraHeightM / tan(depressionRad), depressionRad, cameraHeightM, sh)
    }

    /**
     * Gated Ground reading: [RangeOutcome.Ok] only within [RangeLimits.GROUND_MAX_REL95] and [RangeLimits.MAX_RANGE_M].
     */
    fun solve(cameraHeightM: Double, depressionRad: Double, aimSigmaRad: Double, model: RangeErrorModel = RangeErrorModel()): RangeOutcome {
        if (!valid(cameraHeightM, depressionRad, aimSigmaRad)) return RangeOutcome.Rejected(RangeRejection.INVALID_INPUT)
        if (cameraHeightM < RangeLimits.MIN_CAMERA_HEIGHT_M) return RangeOutcome.Rejected(RangeRejection.NO_FLOOR)
        if (depressionRad < Math.toRadians(RangeLimits.MIN_DEPRESSION_DEG)) return RangeOutcome.Rejected(RangeRejection.ABOVE_HORIZON)
        if (lineOfSight(cameraHeightM, depressionRad) > RangeLimits.MAX_RANGE_M) return RangeOutcome.OutOfRange
        val f = fix(cameraHeightM, depressionRad, aimSigmaRad, model) ?: return RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN)
        if (!f.usable) return RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN)
        return RangeOutcome.Ok(RangeReading(f.distance, RangeMethod.GROUND))
    }

    /** σ of the camera height above the floor: floor-estimate noise plus VIO scale. */
    fun heightSigma(cameraHeightM: Double, model: RangeErrorModel): Double {
        val s = model.scaleSigmaRel * cameraHeightM
        return sqrt(model.floorHeightSigmaM * model.floorHeightSigmaM + s * s)
    }

    private fun valid(h: Double, theta: Double, aim: Double) =
        h.isFinite() && theta.isFinite() && aim.isFinite() && aim >= 0
}
