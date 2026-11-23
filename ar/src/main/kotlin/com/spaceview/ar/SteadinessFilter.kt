package com.spaceview.ar

import com.spaceview.engine.Pose

/**
 * How steady the phone is: exponentially smoothed angular and linear speed of the camera from consecutive poses
 * (ARCHITECTURE-v2 §5.1). Extracted unchanged from [FrontCaptureGate] so the front-photo capture and the
 * hands-free [DwellTrigger] share one motion model; each applies its own limits ([isSteady]).
 *
 * - A frame with the same camera timestamp as the last one (SceneView calls back at display rate and repeats the
 *   camera image) carries no motion information and is ignored without touching the estimate (M3 review B1).
 * - A gap longer than [MAX_GAP_S] (or time going backwards) forgets the estimate: speeds are unknown until two close
 *   frames arrive again, and unknown is never steady.
 *
 * Pure Kotlin, no allocation per frame; single-threaded (main thread).
 */
class SteadinessFilter {
    private var lastTs = -1L
    private var lastPose: Pose? = null

    /** Smoothed angular speed (rad/s); NaN while unknown. */
    var angular = Double.NaN
        private set

    /** Smoothed linear speed (m/s); NaN while unknown. */
    var linear = Double.NaN
        private set

    /** Forget the motion history. */
    fun reset() {
        lastTs = -1L
        lastPose = null
        angular = Double.NaN
        linear = Double.NaN
    }

    /**
     * One camera frame. Returns false (and changes nothing) for a repeat of the last camera image
     * (dt ≤ [MIN_DT_S]); true otherwise.
     */
    fun update(timestampNs: Long, pose: Pose): Boolean {
        val prev = lastPose
        val dt = (timestampNs - lastTs) / 1e9
        if (prev != null && dt in 0.0..MIN_DT_S) return false
        lastTs = timestampNs
        lastPose = pose
        if (prev == null || dt <= 0 || dt > MAX_GAP_S) {
            angular = Double.NaN
            linear = Double.NaN
            return true
        }
        val w = FrontCaptureGate.rotationAngle(prev.rotation, pose.rotation) / dt
        val v = prev.translation.distanceTo(pose.translation) / dt
        angular = if (angular.isFinite()) angular + EMA_ALPHA * (w - angular) else w
        linear = if (linear.isFinite()) linear + EMA_ALPHA * (v - linear) else v
        return true
    }

    /** Both speeds are known and within the limits. */
    fun isSteady(maxAngularRadPerS: Double, maxLinearMPerS: Double): Boolean =
        angular.isFinite() && linear.isFinite() && angular <= maxAngularRadPerS && linear <= maxLinearMPerS

    companion object {
        /** Frames closer together than this are repeats of the same camera image. */
        const val MIN_DT_S = 1e-4

        /** Weight of the newest speed in the moving average. */
        const val EMA_ALPHA = 0.4

        /** A longer gap between frames forgets the estimate. */
        const val MAX_GAP_S = 0.5
    }
}
