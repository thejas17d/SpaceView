package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Height by a vertical measuring plane (docs/PLAN-v3-2D.md W5, decision U4).
 *
 * Once the base point of a height is set, the second point is where the crosshair ray meets a **vertical plane
 * through the base point that faces the camera**: its normal is the horizontal projection of the camera's forward
 * direction, re-taken every frame. Tilting the phone up or down slides the point along the vertical line above (or
 * below) the base instead of losing it when no surface is under the crosshair.
 *
 * The aim error grows as the ray grazes the plane: a pitch error δ moves the point vertically by range · δ / cos g,
 * where g is the angle between the ray and the plane normal (≈ the camera's pitch). Beyond [MAX_GRAZING_DEG] the
 * point is refused.
 */
object VerticalPlaneAim {
    /** Grazing angles beyond this are refused (the ± would explode). Tunable. */
    const val MAX_GRAZING_DEG = 75.0

    /** Result of [intersect]: a [Hit] or [Refused]. */
    sealed interface Outcome

    /**
     * @property point ray ∩ plane.
     * @property normal the plane's unit normal, horizontal, facing the camera.
     * @property range distance along the ray from the camera to [point].
     * @property cosGrazing |cos| of the angle between the ray and [normal] (1 = head-on).
     * @property verticalPerBaseOffset metres the point moves vertically per metre the base moves along [normal]
     *   (|d.y / (d · n)|): how the base's own depth error leaks into the height.
     */
    class Hit(
        val point: Vec3,
        val normal: Vec3,
        val range: Double,
        val cosGrazing: Double,
        val verticalPerBaseOffset: Double,
    ) : Outcome {
        /** Grazing angle (rad). */
        val grazingRad: Double get() = acos(cosGrazing.coerceIn(0.0, 1.0))
    }

    /** No point, because of [reason]. */
    data class Refused(val reason: Refusal) : Outcome

    /** Why there is no point. */
    enum class Refusal {
        /** The ray grazes the plane by more than [MAX_GRAZING_DEG] (phone pointed nearly straight up or down). */
        TOO_STEEP,

        /** The camera looks away from the base (the plane is behind it), or the input is degenerate. */
        NO_INTERSECTION,
    }

    /** The plane's normal: the horizontal part of the camera's [forward], normalised (null when looking straight up/down). */
    fun planeNormal(forward: Vec3): Vec3? {
        val h = Vec3(forward.x, 0.0, forward.z)
        val l = h.length
        return if (l < 1e-6) null else h / l
    }

    /**
     * Intersects the crosshair [ray] with the vertical plane through [base] facing a camera looking along [forward].
     * Returns the [Hit], or [Refused] with the reason.
     */
    fun intersect(base: Vec3, ray: Ray, forward: Vec3): Outcome {
        if (!base.isFinite || !ray.origin.isFinite || !ray.direction.isFinite || !forward.isFinite) return Refused(Refusal.NO_INTERSECTION)
        val n = planeNormal(forward) ?: return Refused(Refusal.TOO_STEEP)
        val dn = ray.direction dot n
        // Distance from the camera to the plane along the normal; the plane is in front when it is positive.
        val dist = (base - ray.origin) dot n
        if (!(dist > 0)) return Refused(Refusal.NO_INTERSECTION)
        if (dn <= 0) return Refused(Refusal.TOO_STEEP)
        if (dn < cosMax) return Refused(Refusal.TOO_STEEP)
        val t = dist / dn
        val p = ray.origin + ray.direction * t
        return Hit(p, -n, t, dn, abs(ray.direction.y / dn))
    }

    /**
     * 1σ of the point's height: aim error [aimSigmaRad] stretched by the grazing angle, and the base's σ along the plane
     * normal [baseSigmaAlongNormal] leaking into the height through the ray's slope.
     */
    fun verticalSigma(hit: Hit, aimSigmaRad: Double, baseSigmaAlongNormal: Double): Double {
        val aim = hit.range * aimSigmaRad / hit.cosGrazing
        val base = baseSigmaAlongNormal * hit.verticalPerBaseOffset
        return sqrt(aim * aim + base * base)
    }

    private val cosMax = kotlin.math.cos(Math.toRadians(MAX_GRAZING_DEG))
}
