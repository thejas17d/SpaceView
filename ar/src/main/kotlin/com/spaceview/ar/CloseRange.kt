package com.spaceview.ar

import com.spaceview.engine.Pose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/**
 * W10 close-range rules (docs/PLAN-v3.1-precision.md, research `close-range-and-rectangles.md` §2–§3). Pure; each
 * piece is wired into the AR sessions by the caller.
 */

/** What the range guide tells the user about the distance to the surface under the crosshair. */
enum class RangeAdvice {
    /** In the good band (or no range yet). */
    OK,

    /** 0.25–0.35 m: allowed, with "Move back a little" (research §3 rank 1). */
    MOVE_BACK_HINT,

    /** Under 0.25 m: no point may be placed ("Too close. Move back."). */
    TOO_CLOSE,

    /** The object (its projected outline or detected quad) does not fit in the view with a margin: step back. */
    STEP_BACK,
}

/**
 * Range guide and refusal (research C1, §3 rank 1; Apple's "0.5–3 m", Google's "0.5–5 m" advice). Below
 * [REFUSE_BELOW_M] depth is unsupported and blur and focus dominate, so nothing is committed; up to [HINT_BELOW_M] the
 * user is asked to move back. Rectangle and scan modes also need the whole object in view ([fitsView]).
 */
object RangeGuide {
    /** Hard minimum range (m). Tunable from the bench (research §6). */
    const val REFUSE_BELOW_M = 0.25

    /** Soft minimum range (m): "Move back a little". */
    const val HINT_BELOW_M = 0.35

    /** The projected object must stay this fraction of the view's size inside every edge. */
    const val FIT_MARGIN = 0.05

    /** Advice for the camera → surface range [rangeM] (NaN / ≤ 0: no range, OK). */
    fun advise(rangeM: Double): RangeAdvice = when {
        !(rangeM > 0) || !rangeM.isFinite() -> RangeAdvice.OK
        rangeM < REFUSE_BELOW_M -> RangeAdvice.TOO_CLOSE
        rangeM < HINT_BELOW_M -> RangeAdvice.MOVE_BACK_HINT
        else -> RangeAdvice.OK
    }

    /** True when a point at [rangeM] may be committed. */
    fun mayCommit(rangeM: Double): Boolean = advise(rangeM) != RangeAdvice.TOO_CLOSE

    /**
     * True when every projected point ([xs], [ys] in view pixels; NaN = behind the camera) lies inside a [viewW] ×
     * [viewH] view with [FIT_MARGIN] to spare.
     */
    fun fitsView(xs: DoubleArray, ys: DoubleArray, viewW: Int, viewH: Int): Boolean {
        if (viewW <= 0 || viewH <= 0) return false
        val mx = FIT_MARGIN * viewW
        val my = FIT_MARGIN * viewH
        for (i in xs.indices) {
            val x = xs[i]
            val y = ys[i]
            if (!x.isFinite() || !y.isFinite()) return false
            if (x < mx || x > viewW - mx || y < my || y > viewH - my) return false
        }
        return true
    }

    /** [advise] combined with the fit check: an object that does not fit is [RangeAdvice.STEP_BACK] (if not too close). */
    fun adviseForObject(rangeM: Double, fits: Boolean): RangeAdvice {
        val a = advise(rangeM)
        return if (a == RangeAdvice.TOO_CLOSE || fits) a else RangeAdvice.STEP_BACK
    }
}

/**
 * Camera optics of one frame, from ARCore `ImageMetadata` (research §2.1, C9–C11). Any key may be missing per device.
 *
 * @property exposureNs sensor exposure time, or null when the key is missing.
 * @property focalPx focal length (CPU-image pixels) of this very frame.
 * @property lensMoving the lens is moving (LENS_STATE MOVING) or autofocus is scanning.
 */
class OpticsSample(val exposureNs: Long?, val focalPx: Double, val lensMoving: Boolean)

/**
 * Motion-blur and focus gate (research §3 rank 7). Blur in pixels ≈ t_exp · f · (ω + v / Z): the linear part grows as
 * 1/Z, so it dominates up close. Frames over the limit, or taken while the lens moves, are not averaged.
 */
object BlurGate {
    /** Exposure assumed when the metadata has none (1/30 s, indoors). */
    const val FALLBACK_EXPOSURE_NS = 33_333_333L

    /** Reticle samples are averaged only up to this blur (px). Tunable. */
    const val MAX_BLUR_SAMPLE_PX = 2.5

    /** Rectangle detection uses only frames up to this blur (px). Tunable. */
    const val MAX_BLUR_DETECT_PX = 1.0

    /** Blur (px) for angular speed [angularRadS], linear speed [linearMS] at range [rangeM]. */
    fun blurPx(optics: OpticsSample, angularRadS: Double, linearMS: Double, rangeM: Double): Double {
        val t = (optics.exposureNs ?: FALLBACK_EXPOSURE_NS) / 1e9
        val z = if (rangeM > 0 && rangeM.isFinite()) rangeM else Double.POSITIVE_INFINITY
        return t * optics.focalPx * (angularRadS + linearMS / z)
    }

    /** True when a sample may be averaged: lens settled and blur within [maxPx]. */
    fun usable(optics: OpticsSample, angularRadS: Double, linearMS: Double, rangeM: Double, maxPx: Double = MAX_BLUR_SAMPLE_PX): Boolean =
        !optics.lensMoving && blurPx(optics, angularRadS, linearMS, rangeM) <= maxPx
}

/**
 * Scale warm-up (research C12, §3 rank 8): visual-inertial scale needs translation, not time. Before the first commit of
 * a session (or after a tracking reset) the camera must have travelled [MIN_PATH_M] in total and turned [MIN_TURN_DEG].
 * One [add] per distinct tracked frame; [reset] on loss of tracking or a new session.
 */
class ScaleWarmUp {
    private var last: Pose? = null
    private var first: Pose? = null
    private var path = 0.0
    private var maxTurn = 0.0

    /** Cumulative camera path (m) since the last reset. */
    val pathM: Double get() = path

    /** Largest rotation (deg) from the first pose since the last reset. */
    val turnDeg: Double get() = maxTurn

    /** True once both the path and the turn are enough. */
    val ready: Boolean get() = path >= MIN_PATH_M && maxTurn >= MIN_TURN_DEG

    /** 0..1 progress for a coaching ring (the weaker of the two conditions). */
    val progress: Double get() = min(min(1.0, path / MIN_PATH_M), min(1.0, maxTurn / MIN_TURN_DEG))

    fun add(pose: Pose) {
        if (!pose.translation.isFinite) return
        val l = last
        if (l != null) {
            val step = l.translation.distanceTo(pose.translation)
            // A jump this large in one frame is a relocalisation, not motion: it does not count.
            if (step < MAX_STEP_M) path += step
        }
        if (first == null) first = pose
        maxTurn = maxOf(maxTurn, angleDeg(first!!, pose))
        last = pose
    }

    fun reset() {
        last = null
        first = null
        path = 0.0
        maxTurn = 0.0
    }

    private fun angleDeg(a: Pose, b: Pose): Double {
        val q1 = a.rotation
        val q2 = b.rotation
        val dot = abs(q1.x * q2.x + q1.y * q2.y + q1.z * q2.z + q1.w * q2.w).coerceAtMost(1.0)
        return Math.toDegrees(2 * acos(dot))
    }

    companion object {
        /** Cumulative camera translation needed before the first commit (m). Tunable. */
        const val MIN_PATH_M = 0.20

        /** Rotation change needed before the first commit (degrees). Tunable. */
        const val MIN_TURN_DEG = 10.0

        private const val MAX_STEP_M = 0.25
    }
}
