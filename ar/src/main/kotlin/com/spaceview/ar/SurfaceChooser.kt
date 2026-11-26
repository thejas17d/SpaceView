package com.spaceview.ar

import com.spaceview.engine.Ray
import com.spaceview.engine.SupportPlane
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.max

/** One frame's raw hit under the reticle, copied out of ARCore (no trackable). */
internal class HitSample(val position: Vec3, val normal: Vec3?, val kind: HitKind, val distance: Double)

/**
 * The surface point chosen for the reticle in one frame.
 *
 * @property aimInflation multiplies the aim pixel error of this point (≥ 1): the vertical measuring plane's grazing
 *   stretch 1 / cos g (W5).
 * @property extraSigmaM an extra isotropic localisation σ (m) added in quadrature to the aim term (W5: the base's own
 *   depth error leaking into the height).
 * @property extraCovariance [HitKind.GROUND] only: the point's full covariance from the Ground range (anisotropic:
 *   long along the ray, the floor's σ vertically). The aim term is then already included and no localisation is added.
 */
internal class ChosenPoint(
    val position: Vec3,
    val normal: Vec3?,
    val kind: HitKind,
    val range: Double,
    val planeBiasRel: Double,
    val aimInflation: Double = 1.0,
    val extraSigmaM: Double = 0.0,
    val extraCovariance: com.spaceview.engine.Cov3? = null,
) {
    /** [base] (the plain aim localisation at this point's range) with [aimInflation] and [extraSigmaM] applied. */
    fun localisation(base: com.spaceview.engine.LocalisationInput?): com.spaceview.engine.LocalisationInput? {
        if (base == null || extraCovariance != null) return null
        if (aimInflation == 1.0 && extraSigmaM == 0.0) return base
        val px = base.pixelErrorPx * aimInflation
        val extraPx = extraSigmaM * base.focalLengthPx / base.rangeMeters
        return base.copy(pixelErrorPx = kotlin.math.sqrt(px * px + extraPx * extraPx))
    }
}

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
    const val CONSISTENT_ABS_M = 0.006 // W10 (research C7): was 2 cm; 3 % of range rules from 0.2 m

    /** … or by this fraction of the range, whichever is larger. */
    const val CONSISTENT_REL = 0.03

    fun tolerance(rangeM: Double): Double = max(CONSISTENT_ABS_M, CONSISTENT_REL * rangeM)

    fun choose(
        hit: HitSample?,
        surface: LocalSurfaceModel,
        ray: Ray,
        cameraPosition: Vec3,
        nowNs: Long,
        extendedPlane: HitSample? = null,
    ): ChosenPoint? {
        // W10 (research C5): an ARCore plane is not exact either: it carries max(1 mm, 0.5 % of range) of plane σ.
        if (hit != null && hit.kind == HitKind.PLANE) {
            return ChosenPoint(hit.position, hit.normal, HitKind.PLANE, hit.distance, SupportPlane.biasRel(hit.distance))
        }
        // W10 (research C6, ARKit "existing plane, infinite"): outside the plane's polygon, the plane is used only when a
        // depth / feature hit agrees with it closely, i.e. the object under the crosshair is a thin sheet on it.
        if (extendedPlane != null && hit != null &&
            abs(extendedPlane.distance - hit.distance) <= SupportPlane.agreeTolerance(hit.distance)
        ) {
            return ChosenPoint(
                extendedPlane.position, extendedPlane.normal, HitKind.PLANE, extendedPlane.distance, SupportPlane.biasRel(extendedPlane.distance),
            )
        }
        surface.update(ray, nowNs, hit?.distance)
        val fitted = surface.intersect(ray, cameraPosition)
        if (fitted != null && (hit == null || abs(fitted.range - hit.distance) <= tolerance(hit.distance))) {
            return ChosenPoint(fitted.position, fitted.normal, HitKind.FITTED_SURFACE, fitted.range, fitted.planeBiasRel)
        }
        return hit?.let { ChosenPoint(it.position, it.normal, it.kind, it.distance, 0.0) }
    }
}
