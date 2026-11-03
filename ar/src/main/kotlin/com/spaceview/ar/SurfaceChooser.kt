package com.spaceview.ar

import com.spaceview.engine.Ray
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.max

/** One frame's raw hit under the reticle, copied out of ARCore (no trackable). */
internal class HitSample(val position: Vec3, val normal: Vec3?, val kind: HitKind, val distance: Double)

/** The surface point chosen for the reticle in one frame. */
internal class ChosenPoint(
    val position: Vec3,
    val normal: Vec3?,
    val kind: HitKind,
    val range: Double,
    val planeBiasRel: Double,
)

/**
 * Picks the reticle point from the raw hit and the locally fitted surface (worker thread).
 *
 * Priority: an ARCore plane hit (already preferred only when no depth / feature hit is clearly nearer,
 * see [FrameReaders.centreHit]); otherwise our RANSAC surface, but **only when it agrees with the raw
 * hit's depth** within max([CONSISTENT_ABS_M], [CONSISTENT_REL] × range). The fit refines a hit, it
 * never replaces it with a different depth layer (M2 review M4: a box top over a textured floor must
 * not land on the floor 40 cm behind it). Without any raw hit the fit is used on its own (plain walls).
 */
internal object SurfaceChooser {
    /** Fitted and raw ranges may differ by this much (absolute part). Placeholder to tune. */
    const val CONSISTENT_ABS_M = 0.02

    /** … or by this fraction of the range, whichever is larger. */
    const val CONSISTENT_REL = 0.03

    fun tolerance(rangeM: Double): Double = max(CONSISTENT_ABS_M, CONSISTENT_REL * rangeM)

    fun choose(hit: HitSample?, surface: LocalSurfaceModel, ray: Ray, cameraPosition: Vec3, nowNs: Long): ChosenPoint? {
        if (hit != null && hit.kind == HitKind.PLANE) {
            return ChosenPoint(hit.position, hit.normal, HitKind.PLANE, hit.distance, 0.0)
        }
        surface.update(ray, nowNs, hit?.distance)
        val fitted = surface.intersect(ray, cameraPosition)
        if (fitted != null && (hit == null || abs(fitted.range - hit.distance) <= tolerance(hit.distance))) {
            return ChosenPoint(fitted.position, fitted.normal, HitKind.FITTED_SURFACE, fitted.range, fitted.planeBiasRel)
        }
        return hit?.let { ChosenPoint(it.position, it.normal, it.kind, it.distance, 0.0) }
    }
}
