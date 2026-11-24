package com.spaceview.ar

import com.spaceview.engine.Cov3
import com.spaceview.engine.Ray
import com.spaceview.engine.Vec3
import com.spaceview.engine.VerticalPlaneAim
import com.spaceview.engine.range.GroundRange
import com.spaceview.engine.range.RangeErrorModel
import kotlin.math.abs
import kotlin.math.max

/**
 * Where the second point of a height (and the top of a scanned box) comes from (docs/PLAN-v3-2D.md W5). Pure; used
 * by the measuring worker.
 *
 * - **Top of a height:** the crosshair ray ∩ the vertical plane through the base that faces the camera
 *   ([VerticalPlaneAim]). Tilting the phone slides the point along the vertical above the base instead of losing it.
 *   The aim error is stretched by 1 / cos(grazing) and the base's depth error leaks in through the ray's slope; beyond
 *   [VerticalPlaneAim.MAX_GRAZING_DEG] the point is refused ([Top.TooSteep]).
 * - **Top of a box:** a real surface hit on the box's top face (horizontal, above the base, inside its footprint) is
 *   used as before; otherwise the vertical plane through the base corner the crosshair is aimed above (a wardrobe
 *   taller than the eye, a box whose top cannot be seen).
 * - **Far base of a height:** with no surface hit, the crosshair ray ∩ the tracked floor (Ground range), only while the
 *   Ground ±95 stays within [com.spaceview.engine.range.RangeLimits.GROUND_MAX_REL95].
 */
internal object HeightAim {
    /** Result of aiming the top of a height. */
    sealed interface Top {
        class Point(val chosen: ChosenPoint) : Top
        data object TooSteep : Top
        data object None : Top
    }

    /** The top point of a height whose base is [base], or why there is none. */
    fun top(base: Vec3, baseSigmaAlong: (Vec3) -> Double, ray: Ray, forward: Vec3): Top =
        when (val r = VerticalPlaneAim.intersect(base, ray, forward)) {
            is VerticalPlaneAim.Hit -> {
                val leak = baseSigmaAlong(r.normal) * r.verticalPerBaseOffset
                Top.Point(
                    ChosenPoint(
                        position = r.point, normal = r.normal, kind = HitKind.VERTICAL_PLANE, range = r.range,
                        planeBiasRel = 0.0, aimInflation = 1.0 / r.cosGrazing, extraSigmaM = leak,
                    ),
                )
            }
            is VerticalPlaneAim.Refused -> if (r.reason == VerticalPlaneAim.Refusal.TOO_STEEP) Top.TooSteep else Top.None
        }

    /** [top] for a committed base endpoint. */
    fun top(base: EndpointData, ray: Ray, forward: Vec3): Top =
        top(base.point.position, { n -> base.point.sigmaAlong(n) }, ray, forward)

    /**
     * The top of a box over the three committed [base] corners: [surface] when it is the box's top face, else the
     * vertical plane through the corner the crosshair is aimed above.
     */
    fun boxTop(base: List<EndpointData>, surface: ChosenPoint?, ray: Ray, forward: Vec3): Top {
        if (base.size < 3) return Top.None
        if (surface != null && isOnTopFace(base.map { it.point.position }, surface)) return Top.Point(surface)
        val corner = cornerUnderRay(base.map { it.point.position }, ray) ?: return Top.None
        return top(base[corner], ray, forward)
    }

    /** A hit is on the box's top face when its surface is horizontal, it is above the base and inside its footprint. */
    fun isOnTopFace(corners: List<Vec3>, p: ChosenPoint): Boolean {
        val n = p.normal ?: return false
        if (abs(n dot Vec3.UP) < TOP_FACE_COS) return false
        val baseY = corners.minOf { it.y }
        if (p.position.y - baseY < MeasurementComposer.MIN_BOX_HEIGHT_M) return false
        // Footprint: the parallelogram c0, c1, c2, c0 + c2 − c1 seen from above, with a margin.
        val o = corners[1]
        val e1 = Vec3(corners[0].x - o.x, 0.0, corners[0].z - o.z)
        val e2 = Vec3(corners[2].x - o.x, 0.0, corners[2].z - o.z)
        val q = Vec3(p.position.x - o.x, 0.0, p.position.z - o.z)
        val det = e1.x * e2.z - e1.z * e2.x
        if (abs(det) < 1e-9) return false
        val a = (q.x * e2.z - q.z * e2.x) / det
        val b = (e1.x * q.z - e1.z * q.x) / det
        val ma = max(FOOTPRINT_MARGIN_REL, FOOTPRINT_MARGIN_M / max(e1.length, 1e-6))
        val mb = max(FOOTPRINT_MARGIN_REL, FOOTPRINT_MARGIN_M / max(e2.length, 1e-6))
        return a >= -ma && a <= 1 + ma && b >= -mb && b <= 1 + mb
    }

    /** Index of the corner whose vertical line passes closest (seen from above) to the crosshair [ray], in front of it. */
    fun cornerUnderRay(corners: List<Vec3>, ray: Ray): Int? {
        val dx = ray.direction.x
        val dz = ray.direction.z
        val l = kotlin.math.sqrt(dx * dx + dz * dz)
        if (l < 1e-6) return null
        var best: Int? = null
        var bestD = Double.MAX_VALUE
        for (i in corners.indices) {
            val vx = corners[i].x - ray.origin.x
            val vz = corners[i].z - ray.origin.z
            val along = (vx * dx + vz * dz) / l
            if (along <= 0) continue
            val off = abs(vx * dz - vz * dx) / l
            if (off < bestD) { bestD = off; best = i }
        }
        return best
    }

    /**
     * A far height base on the floor (Ground range), or null when the floor is unknown, the ray does not point down
     * enough, or the Ground ± is beyond its limit. Its covariance is the Ground σ along the ray plus the floor's σ
     * vertically: the base height itself is known to the floor's precision.
     */
    fun groundBase(ray: Ray, floorY: Double, aimSigmaRad: Double, scaleSigmaRel: Double): ChosenPoint? {
        if (!floorY.isFinite()) return null
        val model = RangeErrorModel(scaleSigmaRel = scaleSigmaRel)
        val fix = GroundRange.fix(ray.origin.y - floorY, GroundRange.depression(ray.direction), aimSigmaRad, model) ?: return null
        if (!fix.usable) return null
        val d = ray.direction
        val along = fix.distance.sigma
        val v = fix.heightSigmaM
        val cov = outer(d, along * along) + outer(Vec3.UP, v * v)
        return ChosenPoint(
            position = ray.pointAt(fix.distance.value), normal = Vec3.UP, kind = HitKind.GROUND,
            range = fix.distance.value, planeBiasRel = 0.0, extraCovariance = cov,
        )
    }

    private fun outer(u: Vec3, s2: Double) =
        Cov3(u.x * u.x * s2, u.x * u.y * s2, u.x * u.z * s2, u.y * u.y * s2, u.y * u.z * s2, u.z * u.z * s2)

    /** A top-face hit's normal must be within ~20° of vertical. */
    private val TOP_FACE_COS = kotlin.math.cos(Math.toRadians(20.0))

    /** Footprint margin: this fraction of each edge … */
    const val FOOTPRINT_MARGIN_REL = 0.10

    /** … or this many metres, whichever is larger. */
    const val FOOTPRINT_MARGIN_M = 0.05
}
