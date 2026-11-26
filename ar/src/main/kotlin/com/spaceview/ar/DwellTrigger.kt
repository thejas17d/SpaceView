package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3
import kotlin.math.max

/**
 * Hands-free point drop (Smart Measure SM1a, ARCHITECTURE-v2 §5.1): decides when to drop a point because the user
 * has held the reticle **steady and locked** on a surface for [holdNs]. Pure Kotlin and JVM-tested; the AR wiring is
 * [HandsFreeDropper]. Main thread only, no allocation per frame.
 *
 * A hold counts only while **all** of these are true on every new camera frame:
 * - camera tracking is good ([update]'s `trackingOk`); a loss also forgets the motion history;
 * - the session accepts a point now (not frozen, not complete, no "+" pending: `target` is null otherwise) and the
 *   point under the reticle is **locked** (the existing `CommitCandidate.locked`, full-quality tracking only);
 * - the phone is steady ([SteadinessFilter] with [maxAngularRadPerS] / [maxLinearMPerS]);
 * - the target is at least [minSpacingM] from every committed point (never the same spot as a point already there);
 * - the target stays within [driftM] (or [driftRel] × range) of where the hold started: jitter beyond it restarts
 *   the hold from the new spot;
 * - it is not inside a hold-off: [cooldownNs] after a drop, after an Undo / Clear, and after a session (re)start.
 *
 * After a drop the trigger also **re-arms only once the target has moved [minSpacingM] away from the drop spot**, so
 * holding still after a drop (or after a refused drop, or after undoing it) never drops again on the same spot.
 *
 * Repeated camera timestamps (SceneView calls back at display rate, M3 review B1) neither advance nor reset anything.
 */
class DwellTrigger(
    private val holdNs: Long = HOLD_NS,
    private val minSpacingM: Double = MIN_SPACING_M,
    private val cooldownNs: Long = COOLDOWN_NS,
    private val maxAngularRadPerS: Double = STEADY_MAX_ANGULAR_RAD_S,
    private val maxLinearMPerS: Double = STEADY_MAX_LINEAR_M_S,
    private val driftM: Double = DRIFT_M,
    private val driftRel: Double = DRIFT_REL,
) {
    private val motion = SteadinessFilter()
    private var lastTs = Long.MIN_VALUE
    private var holdStartNs = NONE
    private var holdOrigin: Vec3? = null
    private var blockedUntilNs = NONE
    private var pendingHoldOffNs = 0L
    private var rearmFrom: Vec3? = null
    private var rearmAtNextTarget = false

    /** 0..1 progress of the current hold (0 when not holding). */
    var progress = 0f
        private set

    /**
     * Forget the hold and the motion, and hold off for [cooldownNs] from the next frame (session resumed or created,
     * point removed by Undo / Clear). The re-arm spot is kept, so undoing a dropped point does not drop it again.
     * With [rearmAtNextTarget] (a freeze just ended) the next target also becomes the re-arm spot: the reticle still
     * aims where the point was before the user corrected it, so nothing drops there until the user aims elsewhere.
     */
    fun holdOff(rearmAtNextTarget: Boolean = false) {
        if (rearmAtNextTarget) this.rearmAtNextTarget = true
        cancelHold()
        motion.reset()
        lastTs = Long.MIN_VALUE
        blockedUntilNs = NONE
        pendingHoldOffNs = cooldownNs
    }

    /** Forget everything, including the re-arm spot (a new ARCore session: a new world frame). */
    fun reset() {
        holdOff()
        rearmFrom = null
        rearmAtNextTarget = false
    }

    /**
     * One `onSessionUpdated` call. Returns true when a point should be dropped **now** (at most once per hold).
     *
     * @param trackingOk the camera is tracking (and the session is live).
     * @param cameraPose the camera pose of this frame (null when unknown).
     * @param target the locked point under the reticle, or null when there is none or the session does not accept a
     *   point now (frozen, complete, "+" pending).
     * @param rangeM camera → target distance (for the relative drift limit).
     * @param nearestCommittedM distance from [target] to the nearest committed point (∞ when there is none).
     */
    fun update(
        timestampNs: Long,
        trackingOk: Boolean,
        cameraPose: Pose?,
        target: Vec3?,
        rangeM: Double,
        nearestCommittedM: Double,
    ): Boolean {
        if (lastTs != Long.MIN_VALUE) {
            val dt = timestampNs - lastTs
            if (dt in 0..MIN_DT_NS) return false // the same camera image again: neither advance nor reset
            if (dt < 0) holdOff() // the camera clock restarted (a new session): start over, with the hold-off
        }
        lastTs = timestampNs
        if (pendingHoldOffNs > 0) {
            blockedUntilNs = timestampNs + pendingHoldOffNs
            pendingHoldOffNs = 0
        }
        if (!trackingOk || cameraPose == null) {
            motion.reset()
            cancelHold()
            return false
        }
        motion.update(timestampNs, cameraPose)
        if (target != null && rearmAtNextTarget) {
            rearmFrom = target
            rearmAtNextTarget = false
        }
        if (target == null || timestampNs < blockedUntilNs) {
            cancelHold()
            return false
        }
        val rearm = rearmFrom
        if (rearm != null) {
            if (target.distanceTo(rearm) < minSpacingM) {
                cancelHold()
                return false
            }
            rearmFrom = null
        }
        if (nearestCommittedM < minSpacingM || !motion.isSteady(maxAngularRadPerS, maxLinearMPerS)) {
            cancelHold()
            return false
        }
        val origin = holdOrigin
        if (origin == null || target.distanceTo(origin) > max(driftM, driftRel * rangeM)) {
            // Start (or, after jitter beyond the drift limit, restart) the hold at this spot.
            holdStartNs = timestampNs
            holdOrigin = target
            progress = 0f
            return false
        }
        val held = timestampNs - holdStartNs
        if (held < holdNs) {
            progress = (held.toDouble() / holdNs).toFloat()
            return false
        }
        // Fire: one drop per hold, then a cool-down and a re-arm distance.
        cancelHold()
        rearmFrom = target
        blockedUntilNs = timestampNs + cooldownNs
        return true
    }

    private fun cancelHold() {
        holdStartNs = NONE
        holdOrigin = null
        progress = 0f
    }

    companion object {
        /**
         * How long the reticle must stay locked and steady before a point drops (on top of the ~0.5 s the estimator
         * needs to lock). Tunable on the device (§5.6 device checklist): 0.6–0.8 s.
         */
        const val HOLD_NS = 700_000_000L

        /** A drop needs this distance from every committed point, and re-arms after moving this far. Tunable. */
        const val MIN_SPACING_M = 0.03

        /** No drop for this long after a drop, an Undo / Clear, or a session (re)start. */
        const val COOLDOWN_NS = 1_000_000_000L

        /**
         * Steady for a dwell: smoothed angular speed ≤ ≈ 11°/s. Looser than the front photo's 0.10 rad/s on purpose:
         * the point itself is already averaged and locked by the estimator, and a shaky hand must still be able to
         * drop (the user's complaint). Below `TrackingGateConfig.sampleMaxAngular` (0.35), so every averaged sample
         * is a valid one. Tunable on the device.
         */
        const val STEADY_MAX_ANGULAR_RAD_S = 0.20

        /** Steady for a dwell: smoothed linear speed ≤ 10 cm/s. Tunable on the device. */
        const val STEADY_MAX_LINEAR_M_S = 0.10

        /** The target may wander this far (or [DRIFT_REL] × range) from where the hold started. */
        const val DRIFT_M = 0.003 // W10 (research C2): was 1 cm at any range; 1 % of range still rules from 0.3 m

        /** Relative drift limit (1% of the range: 3 cm at 3 m, where the aim error is already larger). */
        const val DRIFT_REL = 0.01

        private const val MIN_DT_NS = 100_000L // 1e-4 s, as SteadinessFilter.MIN_DT_S
        private const val NONE = Long.MIN_VALUE
    }
}
