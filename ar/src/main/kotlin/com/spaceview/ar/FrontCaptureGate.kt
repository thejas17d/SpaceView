package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/** What the user should do for the front photo (one hint at a time, in this priority). */
enum class CaptureHint {
    /** Camera tracking is not good: move slowly / add light. */
    NOT_TRACKING,

    /** A corner is behind the camera or the face cannot be projected: turn towards the object. */
    FACE_OBJECT,

    /** Not straight-on: the phone is more than [FrontCaptureGate.MAX_ANGLE_DEG] off the face normal. */
    TILT,

    /** Part of the face is outside the photo. */
    STEP_BACK,

    /** The face is too small in the photo. */
    STEP_CLOSER,

    /** Everything is fine except the phone is moving. */
    HOLD_STILL,

    /** All conditions hold; [FrontCaptureStatus.progress] fills up until the photo is taken. */
    HOLDING,

    /** The photo was taken and is being prepared (set by the session, never by the gate). */
    CAPTURED,

    /**
     * The outline's points lost their anchors (the app was paused): their positions can no longer be
     * trusted in the camera's world, so no photo is taken. Measure again or skip the photo.
     */
    REMEASURE,
}

/**
 * Throttle-friendly status of the front-photo capture: value-equal snapshots compare equal, so the
 * StateFlow only re-emits when the hint, the rounded angle or the progress step changes.
 *
 * @property angleDeg angle between the camera's view axis and the face normal, rounded to 5°.
 * @property progress 0..1 in steps of 0.25 while [hint] is [CaptureHint.HOLDING].
 */
data class FrontCaptureStatus(val hint: CaptureHint, val angleDeg: Int = 0, val progress: Float = 0f)

/**
 * Decides when to auto-capture the front photo (UI research §3.3 A.4): tracking is good, the phone is
 * roughly parallel to the face (view axis within [MAX_ANGLE_DEG] of the face normal), the whole face is
 * in the picture, and the phone has been steady (angular and linear speed under the limits) for
 * [HOLD_NS]. Pure Kotlin and allocation-light, main-thread only (fed once per frame while armed).
 */
class FrontCaptureGate(
    private val maxAngleDeg: Double = MAX_ANGLE_DEG,
    private val maxAngularRadPerS: Double = STEADY_MAX_ANGULAR_RAD_S,
    private val maxLinearMPerS: Double = STEADY_MAX_LINEAR_M_S,
    private val holdNs: Long = HOLD_NS,
) {
    private var lastTs = -1L
    private var lastPose: Pose? = null
    private var angular = Double.NaN
    private var linear = Double.NaN
    private var holdingSinceNs = -1L

    /** Forget the motion history (call when the capture is (re)armed). */
    fun reset() {
        lastTs = -1L
        lastPose = null
        angular = Double.NaN
        linear = Double.NaN
        holdingSinceNs = -1L
    }

    /**
     * One frame. [sensorPose] is the camera pose (its −Z is the view axis), [faceNormal] the unit
     * normal out of the face towards the viewer, [fit] where the projected face corners fall.
     * Returns the status and whether to capture **this** frame ([Decision.capture]).
     */
    fun update(timestampNs: Long, tracking: Boolean, sensorPose: Pose, faceNormal: Vec3, fit: CornerFit): Decision {
        updateMotion(timestampNs, sensorPose)
        val angle = angleToFaceDeg(sensorPose.forward, faceNormal)
        val hint = when {
            !tracking -> CaptureHint.NOT_TRACKING
            fit == CornerFit.BEHIND || angle > FACING_LIMIT_DEG -> CaptureHint.FACE_OBJECT
            angle > maxAngleDeg -> CaptureHint.TILT
            fit == CornerFit.OUTSIDE -> CaptureHint.STEP_BACK
            fit == CornerFit.TOO_SMALL -> CaptureHint.STEP_CLOSER
            !isSteady() -> CaptureHint.HOLD_STILL
            else -> CaptureHint.HOLDING
        }
        if (hint != CaptureHint.HOLDING) {
            holdingSinceNs = -1L
            return Decision(FrontCaptureStatus(hint, roundAngle(angle)), capture = false)
        }
        if (holdingSinceNs < 0) holdingSinceNs = timestampNs
        val held = timestampNs - holdingSinceNs
        val progress = min(1.0, held.toDouble() / holdNs)
        val step = (progress * 4).toInt() / 4f
        return Decision(FrontCaptureStatus(hint, roundAngle(angle), step), capture = held >= holdNs)
    }

    /** Result of one [update]. */
    class Decision(val status: FrontCaptureStatus, val capture: Boolean)

    private fun isSteady(): Boolean =
        angular.isFinite() && linear.isFinite() && angular <= maxAngularRadPerS && linear <= maxLinearMPerS

    private fun updateMotion(t: Long, pose: Pose) {
        val prev = lastPose
        val dt = (t - lastTs) / 1e9
        // SceneView calls back at display rate; with LATEST_CAMERA_IMAGE a camera frame is delivered
        // again with the same timestamp. Such a repeat carries no motion information: ignore it and keep
        // the estimate and the hold (M3 review B1; same rule as TrackingGate).
        if (prev != null && dt in 0.0..MIN_DT_S) return
        lastTs = t
        lastPose = pose
        if (prev == null || dt <= 0 || dt > MAX_GAP_S) {
            angular = Double.NaN
            linear = Double.NaN
            return
        }
        val w = rotationAngle(prev.rotation, pose.rotation) / dt
        val v = prev.translation.distanceTo(pose.translation) / dt
        angular = if (angular.isFinite()) angular + EMA_ALPHA * (w - angular) else w
        linear = if (linear.isFinite()) linear + EMA_ALPHA * (v - linear) else v
    }

    companion object {
        /** Auto-capture only within this angle between the view axis and the face normal. Tunable. */
        const val MAX_ANGLE_DEG = 15.0

        /**
         * Box faces may be photographed more obliquely (a low carton would otherwise need crouching); the
         * homography corrects perspective and [CornerFit.TOO_SMALL] guards resolution (M3 review m13). Tunable.
         */
        const val MAX_ANGLE_DEG_BOX = 25.0

        /** The parallel limit for [shape]. */
        fun maxAngleFor(shape: ScanShape): Double = if (shape == ScanShape.BOX) MAX_ANGLE_DEG_BOX else MAX_ANGLE_DEG

        /** Frames closer together than this are repeats of the same camera image. */
        private const val MIN_DT_S = 1e-4

        /** Beyond this the user is not facing the object at all. */
        const val FACING_LIMIT_DEG = 70.0

        /** Steady: smoothed angular speed below this (≈ 6°/s). Tunable. */
        const val STEADY_MAX_ANGULAR_RAD_S = 0.10

        /** Steady: smoothed linear speed below this. Tunable. */
        const val STEADY_MAX_LINEAR_M_S = 0.06

        /** All conditions must hold this long before the photo is taken. Tunable. */
        const val HOLD_NS = 600_000_000L

        private const val EMA_ALPHA = 0.4
        private const val MAX_GAP_S = 0.5

        /**
         * Angle (degrees) between the camera's view direction [forward] and the direction into the face
         * (−[faceNormal]): 0 = looking straight at it.
         */
        fun angleToFaceDeg(forward: Vec3, faceNormal: Vec3): Double {
            val c = -(forward dot faceNormal) / sqrt(forward.lengthSquared * faceNormal.lengthSquared)
            return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
        }

        /** Rotation angle (radians) from [a] to [b]. */
        fun rotationAngle(a: Quaternion, b: Quaternion): Double {
            val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceAtMost(1.0)
            return 2 * acos(d)
        }

        private fun roundAngle(deg: Double): Int = ((deg / 5).toInt() * 5)
    }
}
