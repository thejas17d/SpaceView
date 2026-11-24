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
 *   used as before; otherwise the crosshair ray ∩ the first **vertical side face** of the measured prism it meets (all
 *   four base corners, including the implicit c0 + c2 − c1), within that face's width (review MAJOR 4). For a box this
 *   point is exact: aiming at a top edge lands on that edge, whichever face it belongs to. No face → no point.
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

    /**
     * The top point of a height whose base is [base], or why there is none. Besides the base's own σ, the leak term
     * carries [BASE_OFFSET_SIGMA_M]: the aimed top is never exactly above the base (review MINOR 4), and an offset d
     * along the plane normal moves the height by d · |d.y / (d · n)|.
     */
    fun top(base: Vec3, baseSigmaAlong: (Vec3) -> Double, ray: Ray, forward: Vec3): Top =
        when (val r = VerticalPlaneAim.intersect(base, ray, forward)) {
            is VerticalPlaneAim.Hit -> {
                val s = baseSigmaAlong(r.normal)
                val leak = kotlin.math.sqrt(s * s + BASE_OFFSET_SIGMA_M * BASE_OFFSET_SIGMA_M) * r.verticalPerBaseOffset
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
     * The top of a box over the three committed [base] corners: [surface] when it is the box's top face, else the first
     * vertical side face of the prism the ray meets ([sideFace]).
     */
    fun boxTop(base: List<EndpointData>, surface: ChosenPoint?, ray: Ray): Top {
        if (base.size < 3) return Top.None
        if (surface != null && isOnTopFace(base.map { it.point.position }, surface)) return Top.Point(surface)
        return sideFace(base, ray)
    }

    /**
     * The crosshair [ray] ∩ the nearest vertical side face of the prism over the base parallelogram c0, c1, c2,
     * c3 = c0 + c2 − c1 that it meets in front of the camera, within the face's width ± [FACE_MARGIN_M]. The aim error
     * is stretched by 1 / cos(grazing) and the corners' σ across the face leaks into the height through the ray's
     * slope. Refused beyond [VerticalPlaneAim.MAX_GRAZING_DEG] ([Top.TooSteep]); no face met → [Top.None].
     */
    fun sideFace(base: List<EndpointData>, ray: Ray): Top {
        if (base.size < 3) return Top.None
        val c = base.take(3).map { it.point.position }
        val corners = listOf(c[0], c[1], c[2], c[0] + c[2] - c[1])
        var bestT = Double.MAX_VALUE
        var bestN: Vec3? = null
        var bestDn = 0.0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val e = Vec3(b.x - a.x, 0.0, b.z - a.z)
            val len = e.length
            if (len < 1e-6) continue
            val n = Vec3(e.z, 0.0, -e.x) / len // horizontal, perpendicular to the edge
            val dn = ray.direction dot n
            if (abs(dn) < 1e-9) continue
            val t = ((a - ray.origin) dot n) / dn
            if (!(t > 0) || t >= bestT) continue
            val p = ray.pointAt(t)
            val u = ((p.x - a.x) * e.x + (p.z - a.z) * e.z) / len
            if (u < -FACE_MARGIN_M || u > len + FACE_MARGIN_M) continue
            bestT = t
            bestN = n
            bestDn = dn
        }
        val n = bestN ?: return Top.None
        val cos = abs(bestDn)
        if (cos < COS_MAX_GRAZING) return Top.TooSteep
        val facing = if (bestDn > 0) -n else n // towards the camera
        val sigma = base.take(3).maxOf { it.point.sigmaAlong(n) }
        val leak = sigma * abs(ray.direction.y / bestDn)
        return Top.Point(
            ChosenPoint(
                position = ray.pointAt(bestT), normal = facing, kind = HitKind.VERTICAL_PLANE, range = bestT,
                planeBiasRel = 0.0, aimInflation = 1.0 / cos, extraSigmaM = leak,
            ),
        )
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

    private val COS_MAX_GRAZING = kotlin.math.cos(Math.toRadians(VerticalPlaneAim.MAX_GRAZING_DEG))

    /** A box side face counts as hit up to this far (m) beyond its corners (aiming exactly at a vertical edge). Tunable. */
    const val FACE_MARGIN_M = 0.03

    /**
     * 1σ horizontal offset between a height's base and the point aimed at above it (m): the base is never exactly below
     * the top (review MINOR 4). Tunable; the hint asks for the base "exactly below the top".
     */
    const val BASE_OFFSET_SIGMA_M = 0.02

    /** Footprint margin: this fraction of each edge … */
    const val FOOTPRINT_MARGIN_REL = 0.10

    /** … or this many metres, whichever is larger. */
    const val FOOTPRINT_MARGIN_M = 0.05
}
