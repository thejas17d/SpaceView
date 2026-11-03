package com.spaceview.ar

import com.spaceview.engine.LocalisationInput
import com.spaceview.engine.PointEstimate
import com.spaceview.engine.PointEstimator
import com.spaceview.engine.PointEstimatorConfig
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.Vec3
import kotlin.math.max

/**
 * Feeds per-frame reticle samples into `:engine`'s [PointEstimator] and decides when the user has
 * *moved* the reticle (start a new estimate) versus is *holding it still* (keep averaging).
 *
 * Averaging is only valid while the reticle stays on the same spot: if the user sweeps across a
 * wall, a 1 s window would average a smear. So when a sample lands more than
 * max([moveAbsM], [moveRel] × range) from the current reference (the running estimate, or the
 * first sample of the window), the window restarts. A few consecutive frames without any surface
 * under the reticle also restart it.
 *
 * Not thread-safe; owned by the measurement worker.
 */
class SampleStabilizer(
    estimatorConfig: PointEstimatorConfig = PointEstimatorConfig(),
    private val moveAbsM: Double = 0.008,
    private val moveRel: Double = 0.008,
    private val maxMisses: Int = 5,
) {
    private val estimator = PointEstimator(estimatorConfig)
    private var reference: Vec3? = null
    private var misses = 0
    private var cached: PointEstimate? = null

    /** Latest robust estimate, or null until enough steady samples were collected. */
    val estimate: PointEstimate? get() = cached

    /** 0..1 progress towards the first estimate (drives the reticle's stability ring). */
    val progress: Double get() = estimator.progress

    /** True when an estimate exists and the engine calls it stable. */
    val isLocked: Boolean get() = cached?.isStable == true

    fun reset() {
        estimator.reset()
        reference = null
        misses = 0
        cached = null
    }

    /**
     * Adds one frame's candidate point ([position] null = no surface under the reticle) seen from
     * [rangeM] metres. [localisation] (aim error from range and focal length) is folded into the
     * estimate by the engine, which then marks the point as localised.
     */
    fun add(position: Vec3?, rangeM: Double, quality: TrackingQuality, localisation: LocalisationInput? = null) {
        if (position == null) {
            if (++misses >= maxMisses) reset()
            return
        }
        misses = 0
        val ref = reference
        val threshold = max(moveAbsM, moveRel * rangeM)
        if (ref != null && ref.distanceTo(position) > threshold) {
            estimator.reset()
            cached = null
            reference = null
        }
        val result = estimator.add(position, quality)
        if (quality == TrackingQuality.NOT_TRACKING) {
            reference = null
            cached = null
            return
        }
        if (result == com.spaceview.engine.SampleResult.ACCEPTED && reference == null) reference = position
        cached = estimator.estimate(localisation)
        cached?.let { reference = it.position }
    }
}
