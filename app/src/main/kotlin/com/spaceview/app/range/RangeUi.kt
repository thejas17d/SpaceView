package com.spaceview.app.range

import androidx.annotation.StringRes
import com.spaceview.app.R
import com.spaceview.ar.RangeIssue
import com.spaceview.ar.SightFailure
import com.spaceview.engine.range.RangeFormat
import com.spaceview.engine.range.RangeMethod
import com.spaceview.engine.range.RangeReading
import com.spaceview.engine.range.RangeRejection
import com.spaceview.engine.range.RangeText

/** Pure mappings of the Distance screen (JVM-tested). */
internal object RangeUi {
    @StringRes
    fun methodRes(m: RangeMethod): Int = when (m) {
        RangeMethod.NEAR -> R.string.range_method_near
        RangeMethod.GROUND -> R.string.range_method_ground
        RangeMethod.TWO_SIGHT -> R.string.range_method_two_sight
    }

    @StringRes
    fun issueRes(i: RangeIssue): Int = when (i) {
        RangeIssue.HOLD_STEADY -> R.string.range_issue_hold_steady
        RangeIssue.NEED_FLOOR -> R.string.range_issue_need_floor
        RangeIssue.ABOVE_HORIZON -> R.string.range_issue_above_horizon
        RangeIssue.GROUND_TOO_FAR -> R.string.range_issue_ground_too_far
        RangeIssue.OFF_GROUND -> R.string.range_issue_off_ground
        RangeIssue.OUT_OF_RANGE -> R.string.range_out_of_range
    }

    @StringRes
    fun rejectionRes(r: RangeRejection): Int = when (r) {
        RangeRejection.TOO_UNCERTAIN -> R.string.range_reject_too_uncertain
        RangeRejection.NEAR_PARALLEL -> R.string.range_reject_near_parallel
        RangeRejection.BEHIND_CAMERA -> R.string.range_reject_behind
        RangeRejection.INCONSISTENT_SIGHTS -> R.string.range_reject_inconsistent
        RangeRejection.TRACKING_LOST -> R.string.range_reject_tracking_lost
        RangeRejection.NO_FLOOR, RangeRejection.ABOVE_HORIZON, RangeRejection.INVALID_INPUT -> R.string.range_reject_invalid
    }

    @StringRes
    fun sightFailureRes(f: SightFailure): Int = when (f) {
        SightFailure.NOT_STEADY -> R.string.range_sight_not_steady
        SightFailure.NOT_TRACKING -> R.string.range_sight_not_tracking
    }

    /** A 2-sight refusal can be fixed by retaking sight 2 (sight 1 still valid); tracking loss needs a fresh start. */
    fun canRetake(r: RangeRejection): Boolean = r != RangeRejection.TRACKING_LOST && r != RangeRejection.INVALID_INPUT

    /** The reading's text, never with more digits than its ± supports. */
    fun text(r: RangeReading, imperial: Boolean): RangeText = RangeFormat.format(r.distance, imperial)

    /** A plain length (walk guidance) at 0.5 m / 1 ft resolution, e.g. "12.5 m" or "41 ft". */
    fun walkText(metres: Double, imperial: Boolean): String = if (imperial) {
        "${Math.round(metres / com.spaceview.engine.Units.METRES_PER_FOOT)} ${RangeFormat.FEET_SYMBOL}"
    } else {
        val half = Math.round(metres * 2) / 2.0
        if (half % 1.0 == 0.0) "${half.toLong()} m" else "$half m"
    }
}
