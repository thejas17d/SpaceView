package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.TrackingQuality
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/** ARCore camera tracking state, mirrored so the gate is testable without ARCore. */
enum class CameraTracking { TRACKING, PAUSED, STOPPED }

/** ARCore `TrackingFailureReason`, mirrored. */
enum class FailureReason { NONE, BAD_STATE, INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE }

/**
 * What the UI should tell the user about tracking (docs/research/ui-ux.md §2.3). Never shown with
 * jargon: the app maps each value to plain copy ("Move your phone more slowly").
 */
enum class TrackingStatus {
    /** Session starting / not yet tracking: show the coaching overlay. */
    INITIALIZING,

    /** Tracking normally. */
    GOOD,

    /** Moving too fast (ARCore said so, or our pose-delta gate did). */
    TOO_FAST,

    /** Too dark to track (or to measure reliably). */
    TOO_DARK,

    /** Plain / glossy surface without enough texture. */
    LOW_TEXTURE,

    /** Tracking was lost after it had worked: "Point back at where you were measuring". */
    LOST,

    /** Camera taken by another app, or ARCore is in a bad state. */
    CAMERA_PROBLEM,
}

/** Per-frame verdict of [TrackingGate]. */
class GateResult(
    val status: TrackingStatus,
    /** Quality to pass to [com.spaceview.engine.PointEstimator.add]. */
    val sampleQuality: TrackingQuality,
    /** Smoothed angular speed of the camera (rad/s). */
    val angularSpeed: Double,
    /** Smoothed linear speed of the camera (m/s). */
    val linearSpeed: Double,
    /** True while the scene is darker than [TrackingGateConfig.lowLightIntensity]. */
    val lowLight: Boolean,
)

/**
 * Tunables for [TrackingGate]. All thresholds are placeholders to tune from the bench test.
 *
 * @property sampleMaxAngular samples are rejected (LIMITED) above this angular speed: motion blur
 *   and rolling shutter bias the hit test (research §2.1: ~0.3 rad/s).
 * @property sampleMaxLinear same for translation speed (m/s).
 * @property tooFastAngular / [tooFastLinear] above these the UI says "Move more slowly";
 *   it clears below 60% of them (hysteresis, so the chip does not flicker).
 * @property warmUpNanos after tracking (re)starts, samples are held back this long: the map can
 *   still jump while ARCore settles or relocalises.
 * @property lowLightIntensity ARCore ambient pixel intensity (0..1) below which we warn.
 * @property emaAlpha weight of the newest speed measurement in the exponential moving average.
 */
class TrackingGateConfig(
    val sampleMaxAngular: Double = 0.35,
    val sampleMaxLinear: Double = 0.30,
    val tooFastAngular: Double = 0.9,
    val tooFastLinear: Double = 0.7,
    val warmUpNanos: Long = 500_000_000L,
    val lowLightIntensity: Float = 0.08f,
    val emaAlpha: Double = 0.35,
)

/**
 * Tracking-quality gate: combines ARCore's tracking state and failure reason with our own
 * too-fast-motion detection from camera pose deltas, and a warm-up after (re)gaining tracking.
 *
 * Pure Kotlin, not thread-safe: feed it from one thread, one call per frame, in timestamp order.
 */
class TrackingGate(private val config: TrackingGateConfig = TrackingGateConfig()) {
    private var prevPose: Pose? = null
    private var prevTimeNs = 0L
    private var angular = 0.0
    private var linear = 0.0
    private var trackingSinceNs = -1L
    private var everTracked = false
    private var tooFast = false

    /** True once the camera has tracked at least once in this session. */
    val hasTracked: Boolean get() = everTracked

    /** Clears all state (e.g. a new ARCore session). */
    fun reset() {
        prevPose = null
        prevTimeNs = 0L
        angular = 0.0
        linear = 0.0
        trackingSinceNs = -1L
        everTracked = false
        tooFast = false
    }

    /**
     * @param lightIntensity ARCore ambient pixel intensity, or NaN when no valid estimate.
     */
    fun update(
        timestampNs: Long,
        tracking: CameraTracking,
        reason: FailureReason,
        cameraPose: Pose,
        lightIntensity: Float,
    ): GateResult {
        val lowLight = !lightIntensity.isNaN() && lightIntensity < config.lowLightIntensity

        if (tracking != CameraTracking.TRACKING) {
            prevPose = null
            trackingSinceNs = -1L
            angular = 0.0
            linear = 0.0
            tooFast = false
            val status = when (reason) {
                FailureReason.INSUFFICIENT_LIGHT -> TrackingStatus.TOO_DARK
                FailureReason.EXCESSIVE_MOTION -> TrackingStatus.TOO_FAST
                FailureReason.INSUFFICIENT_FEATURES -> TrackingStatus.LOW_TEXTURE
                FailureReason.CAMERA_UNAVAILABLE, FailureReason.BAD_STATE -> TrackingStatus.CAMERA_PROBLEM
                FailureReason.NONE -> if (everTracked) TrackingStatus.LOST else TrackingStatus.INITIALIZING
            }
            // PAUSED and STOPPED both mean the world frame may jump on recovery: drop samples.
            return GateResult(status, TrackingQuality.NOT_TRACKING, 0.0, 0.0, lowLight)
        }

        if (trackingSinceNs < 0) trackingSinceNs = timestampNs
        everTracked = true
        updateSpeeds(timestampNs, cameraPose)

        tooFast = if (tooFast) {
            angular > config.tooFastAngular * HYSTERESIS || linear > config.tooFastLinear * HYSTERESIS
        } else {
            angular > config.tooFastAngular || linear > config.tooFastLinear
        }
        val warmingUp = timestampNs - trackingSinceNs < config.warmUpNanos
        val moving = angular > config.sampleMaxAngular || linear > config.sampleMaxLinear
        val quality = if (warmingUp || moving) TrackingQuality.LIMITED else TrackingQuality.TRACKING
        val status = when {
            tooFast -> TrackingStatus.TOO_FAST
            lowLight -> TrackingStatus.TOO_DARK
            else -> TrackingStatus.GOOD
        }
        return GateResult(status, quality, angular, linear, lowLight)
    }

    private fun updateSpeeds(t: Long, pose: Pose) {
        val prev = prevPose
        val dt = (t - prevTimeNs) / 1e9
        prevPose = pose
        prevTimeNs = t
        if (prev == null || dt <= 1e-4 || dt > MAX_DT_S) return
        val q1 = prev.rotation
        val q2 = pose.rotation
        val dot = abs(q1.x * q2.x + q1.y * q2.y + q1.z * q2.z + q1.w * q2.w)
        val angle = 2.0 * acos(min(1.0, dot))
        val w = angle / dt
        val v = prev.translation.distanceTo(pose.translation) / dt
        val a = config.emaAlpha
        angular = a * w + (1 - a) * angular
        linear = a * v + (1 - a) * linear
    }

    private companion object {
        const val HYSTERESIS = 0.6
        /** A longer gap between frames (app paused, dropped frames) does not give a usable speed. */
        const val MAX_DT_S = 0.5
    }
}
