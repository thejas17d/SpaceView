package com.spaceview.engine.range

import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality

/** How a rangefinder distance was obtained (shown as a badge). */
enum class RangeMethod {
    /** An AR surface hit under the crosshair (plane / depth / feature point), up to [RangeLimits.NEAR_MAX_M]. */
    NEAR,

    /** Camera height above the tracked floor and the crosshair's depression angle (assumes level ground). */
    GROUND,

    /** Two sights from two places, intersected in ARCore's world frame. */
    TWO_SIGHT,
}

/** Why no distance is shown. Each maps to one plain-language message in the app. */
enum class RangeRejection {
    /** The ± would be too large to be useful (Ground beyond its limit, 2-sight baseline too short). */
    TOO_UNCERTAIN,

    /** The two sight lines are nearly parallel (intersection angle below [RangeLimits.MIN_INTERSECTION_DEG]). */
    NEAR_PARALLEL,

    /** The sight lines meet behind the phone (aimed at different things). */
    BEHIND_CAMERA,

    /** The two sight lines miss each other by far more than their ± allows (aimed at different things). */
    INCONSISTENT_SIGHTS,

    /** AR tracking was lost between the two sights: the two positions do not share a frame any more. */
    TRACKING_LOST,

    /** No floor is known, or the camera is not above it (Ground). */
    NO_FLOOR,

    /** The crosshair is at or above the horizon (Ground needs a point on the ground). */
    ABOVE_HORIZON,

    /** Non-finite or degenerate input. */
    INVALID_INPUT,
}

/**
 * A rangefinder distance: line of sight from the phone to the aimed point, with its 1σ in [distance] (the UI shows
 * value ± [Measurement.bound95]).
 */
class RangeReading(val distance: Measurement, val method: RangeMethod) {
    /** True for [RangeMethod.GROUND]: the ± is valid only on level ground (badge "assumes level ground"). */
    val assumesLevelGround: Boolean get() = method == RangeMethod.GROUND

    /** Good / Fair / Rough from the relative and absolute 95% bound (same thresholds as every length). */
    val quality: Quality get() = Quality.of(distance)

    override fun equals(other: Any?): Boolean = other is RangeReading && distance == other.distance && method == other.method
    override fun hashCode(): Int = distance.hashCode() * 31 + method.hashCode()
    override fun toString(): String = "RangeReading($method, $distance)"
}

/** Result of one rangefinder computation: a number, "out of range", or a refusal with its reason. */
sealed interface RangeOutcome {
    data class Ok(val reading: RangeReading) : RangeOutcome

    /** Beyond [RangeLimits.MAX_RANGE_M]: never shown as a number. */
    data object OutOfRange : RangeOutcome

    data class Rejected(val reason: RangeRejection) : RangeOutcome
}

/** Hard limits and gates of the rangefinder (docs/PLAN-v3-2D.md W4). Tunables are marked. */
object RangeLimits {
    /** Product limit: beyond this the app shows "out of range", never a number. */
    const val MAX_RANGE_M = 500.0

    /** AR hits further than this are not trusted as Near readings (tunable from the device test). */
    const val NEAR_MAX_M = 8.0

    /** Ground is shown only while its ±95 is at most this fraction of the distance (tunable). */
    const val GROUND_MAX_REL95 = 0.10

    /** Ground needs the camera at least this far above the floor. */
    const val MIN_CAMERA_HEIGHT_M = 0.3

    /** Ground needs the crosshair at least this far below the horizon (degrees). */
    const val MIN_DEPRESSION_DEG = 0.25

    /** 2-sight: rays meeting at less than this angle are refused as near-parallel. */
    const val MIN_INTERSECTION_DEG = 0.5

    /** 2-sight: a miss distance beyond this many expected-miss σ is refused as inconsistent (tunable). */
    const val MISS_REJECT_K = 3.5

    /** 2-sight: a miss beyond this many expected-miss σ inflates the ± in proportion (tunable). */
    const val MISS_INFLATE_K = 2.0

    /** 2-sight guidance: the baseline the app asks for aims at this ±95 / distance (tunable). */
    const val TARGET_REL95 = 0.10

    /** 2-sight guidance: never ask for a shorter walk than this (m). */
    const val MIN_BASELINE_M = 1.0

    /** 2-sight guidance: never ask for a longer walk than this (m). */
    const val MAX_BASELINE_M = 30.0

    /**
     * Beyond this, k·σ/value of an inverse-law quantity (d ∝ 1/angle) makes the 95% interval meaningless (its upper
     * end would be more than 2× the value): refused as [RangeRejection.TOO_UNCERTAIN].
     */
    const val MAX_INVERSE_K_REL = 0.5
}
