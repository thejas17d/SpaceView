package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rectangle (frame, TV, door, A4 sheet…) measured from 3–4 corners snapped to a plane.
 *
 * @property width mean of the two "horizontal" edges (corner 0→1 and 3→2).
 * @property height mean of the two "vertical" edges (corner 1→2 and 0→3).
 * @property area W × H with correlated-scale propagation.
 * @property diagonal √(W² + H²), for TVs/monitors.
 * @property corners the 4 snapped corners in outline order (the 4th is inferred as a parallelogram when
 *   only 3 were given). May differ from the input order, see [cornersReordered].
 * @property cornerAnglesDeg interior angle at each corner (3-corner input: only the middle one is meaningful, the others mirror it).
 * @property maxRightAngleDeviationDeg worst |angle − 90°|.
 * @property isSquare true if every corner is within [Shapes.SQUARENESS_TOLERANCE_DEG] of 90°.
 * @property diagonalMismatch |diag₀₂ − diag₁₃| (m); 0 for a perfect rectangle (and for 3-corner input).
 * @property planarityRms RMS distance of the input corners from the plane before snapping (m).
 * @property rawWidth / [rawHeight] uncalibrated lengths (feed [Calibrator.fromReference]); their σ
 *   includes corner localisation and the geometric-inconsistency term.
 * @property cornersReordered true when the corners were given in a crossing (Z / bow-tie) order, or with
 *   the right angle not in the middle (3 corners), and were put into outline order.
 * @property isConvex false for a concave quad even after reordering (a corner is badly wrong).
 * @property isGeometricallyConsistent false when the outline is not convex, the corners are more than
 *   [Shapes.MAX_PLANARITY_RMS] off the plane, the worst corner angle is beyond what corner noise explains
 *   (and beyond [Shapes.SQUARENESS_TOLERANCE_DEG]), or the supporting plane fit is ill-conditioned.
 *   The badge is then forced to [Quality.ROUGH].
 */
class RectangleMeasurement(
    val width: Measurement,
    val height: Measurement,
    val area: Measurement,
    val diagonal: Measurement,
    val corners: List<Vec3>,
    val cornerAnglesDeg: DoubleArray,
    val maxRightAngleDeviationDeg: Double,
    val isSquare: Boolean,
    val diagonalMismatch: Double,
    val planarityRms: Double,
    val plane: Plane,
    val rawWidth: RawLength,
    val rawHeight: RawLength,
    val cornersReordered: Boolean = false,
    val isConvex: Boolean = true,
    val isGeometricallyConsistent: Boolean = true,
) {
    /** Worst badge of width and height; ROUGH whenever the geometry is inconsistent. */
    val quality: Quality
        get() = if (!isGeometricallyConsistent) Quality.ROUGH else Quality.worst(width.quality, height.quality)
}

/**
 * Box W × H × D (appliance, cabinet, carton, or a wall-hung item with a preset depth).
 *
 * @property widthIsEstimate / [heightIsEstimate] / [depthIsEstimate] true for a dimension that was not
 *   measured (preset or typed). The UI must label it "estimate" / "low confidence" (research §3.3).
 */
class BoxMeasurement(
    val width: Measurement,
    val height: Measurement,
    val depth: Measurement,
    val volume: Measurement,
    val widthIsEstimate: Boolean = false,
    val heightIsEstimate: Boolean = false,
    val depthIsEstimate: Boolean = false,
) {
    /** True if any dimension is an unmeasured estimate. */
    val hasEstimates: Boolean get() = widthIsEstimate || heightIsEstimate || depthIsEstimate

    /**
     * Worst badge of the **measured** dimensions. Estimated (preset) dimensions are excluded: their σ is
     * whatever the preset says (often 0) and must not make the box look GOOD; label them instead.
     */
    val quality: Quality
        get() {
            val q = ArrayList<Quality>(3)
            if (!widthIsEstimate) q += width.quality
            if (!heightIsEstimate) q += height.quality
            if (!depthIsEstimate) q += depth.quality
            return Quality.worst(*q.toTypedArray())
        }
}

/** Shape measurements built on [UncertaintyModel]. */
object Shapes {
    /** Max |corner angle − 90°| still called square (research §6.3: ≤ 2°). Tunable. */
    const val SQUARENESS_TOLERANCE_DEG = 2.0

    /** Corners further than this (RMS) from the plane force a ROUGH badge (bad depth / wrong plane). Tunable. */
    const val MAX_PLANARITY_RMS = 0.01

    /**
     * Lateral leak of an off-plane corner after orthogonal snapping, as a fraction of its off-plane
     * distance (δ·tan θ; 1.0 ≈ a 45° view). Applied as an in-plane σ of planarityRms × this. Tunable.
     */
    const val PLANARITY_LEAK = 1.0

    /** Angle deviations within this many expected-noise σ are attributed to corner noise. */
    const val ANGLE_NOISE_K = 3.0

    /**
     * Rectangle from 3 or 4 corners, in order around the outline: top-left, top-right, bottom-right
     * [, bottom-left]. Each corner is snapped (orthogonally projected) onto [plane] — typically the
     * RANSAC-fitted wall plane; if null, a least-squares plane through the corners is used.
     *
     * Prefer [rectangleFromRays] in the AR layer: projection leaves a lateral error of δ·sin θ for a
     * corner δ off the plane along its viewing ray, which ray–plane intersection removes (research §2.3).
     *
     * Each corner gets its localisation term ([UncertaintyModel.localise]) from [localisation] (one entry
     * per corner, null entries allowed) or the tier floor. Crossing (Z-order) input is reordered.
     * Width/height average the opposite edges; their random σ is the larger of the propagated corner
     * errors and the half-difference of the two opposite edges (catches a mis-snapped corner), plus a
     * geometric term from the right-angle and diagonal inconsistency beyond what corner noise explains,
     * plus the planarity leak.
     */
    fun rectangle(
        corners: List<UncertainPoint>,
        plane: Plane? = null,
        model: UncertaintyModel = UncertaintyModel(),
        extraRelSigma: Double = 0.0,
        localisation: List<LocalisationInput?>? = null,
    ): RectangleMeasurement {
        require(corners.size == 3 || corners.size == 4) { "Need 3 or 4 corners, got ${corners.size}" }
        require(localisation == null || localisation.size == corners.size) { "One localisation per corner" }
        val pl = plane ?: PlaneFitter.leastSquares(corners.map { it.position })?.plane
            ?: throw IllegalArgumentException("Corners are collinear")
        var ss = 0.0
        for (c in corners) { val r = pl.signedDistance(c.position); ss += r * r }
        val planarityRms = sqrt(ss / corners.size)
        val leak = Cov3.isotropic(planarityRms * PLANARITY_LEAK).projectedOntoPlane(pl.normal)
        val snapped = corners.mapIndexed { i, c ->
            val p = pl.project(model.localise(c, localisation?.get(i)))
            UncertainPoint(p.position, p.covariance + leak, true)
        }
        val range = localisation?.let { UncertaintyModel.meanRange(*it.toTypedArray()) }
        return build(snapped, pl, model, extraRelSigma, planarityRms, range, planeOk = true)
    }

    /**
     * Rectangle from 3 or 4 corner **rays** (e.g. `Ray.fromPixel` through the snapped image corners)
     * intersected with a fitted plane (research §2.3, §6.2). Ray–plane intersection has no lateral leak,
     * and the plane fit's own uncertainty is added automatically: the offset term as a relative error
     * σ_offset / h (h = perpendicular camera distance, MINOR-2) and the normal-tilt term per corner.
     * An ill-conditioned (strip-like) fit forces a ROUGH badge.
     *
     * Each corner's lateral localisation σ is pixelErrorPx × t / focalPx with t the intersected range
     * (from [localisation], whose rangeMeters is replaced by t), or the tier floor; it is mapped onto the
     * plane along the ray, so oblique views widen it by 1/cos θ.
     *
     * @return null if any ray misses the plane (parallel/grazing or behind the camera).
     */
    fun rectangleFromRays(
        rays: List<Ray>,
        planeFit: PlaneFit,
        model: UncertaintyModel = UncertaintyModel(),
        localisation: List<LocalisationInput?>? = null,
        extraRelSigma: Double = 0.0,
    ): RectangleMeasurement? = fromRays(rays, planeFit.plane, planeFit, model, localisation, extraRelSigma)

    /** As [rectangleFromRays] for a plane without fit statistics (e.g. an ARCore plane): no plane term. */
    fun rectangleFromRays(
        rays: List<Ray>,
        plane: Plane,
        model: UncertaintyModel = UncertaintyModel(),
        localisation: List<LocalisationInput?>? = null,
        extraRelSigma: Double = 0.0,
    ): RectangleMeasurement? = fromRays(rays, plane, null, model, localisation, extraRelSigma)

    private fun fromRays(
        rays: List<Ray>,
        plane: Plane,
        fit: PlaneFit?,
        model: UncertaintyModel,
        localisation: List<LocalisationInput?>?,
        extraRelSigma: Double,
    ): RectangleMeasurement? {
        require(rays.size == 3 || rays.size == 4) { "Need 3 or 4 corner rays, got ${rays.size}" }
        require(localisation == null || localisation.size == rays.size) { "One localisation per corner" }
        val n = plane.normal
        val pts = ArrayList<UncertainPoint>(rays.size)
        var ranges = 0.0
        var biasRel = 0.0
        for ((i, ray) in rays.withIndex()) {
            val t = plane.intersectDistance(ray) ?: return null
            val p = ray.pointAt(t)
            ranges += t
            val d = ray.direction
            val cosT = abs(n dot d)
            val loc = localisation?.get(i)
            val sigma = loc?.copy(rangeMeters = t)?.sigmaMeters ?: model.tier.unlocalisedEndpointSigma
            // Lateral (⟂ ray) isotropic error, moved along the ray onto the plane: m = u − d (n·u)/(n·d).
            val u1 = anyPerpendicular(d)
            val u2 = d cross u1
            val nd = n dot d
            val m1 = u1 - d * ((n dot u1) / nd)
            val m2 = u2 - d * ((n dot u2) / nd)
            var cov = outer(m1, sigma * sigma) + outer(m2, sigma * sigma)
            if (fit != null) {
                // Normal-tilt uncertainty: offset error grows with the distance from the fit's centroid
                // and moves the intersection along the ray by δ / cos θ.
                val tilt = fit.normalAngleSigma * (plane.project(p) - plane.project(fit.centroid)).length / cosT
                if (tilt > 0) cov = cov + outer(d, tilt * tilt)
                biasRel = max(biasRel, Uncertainty.planeBiasRel(fit, ray.origin))
            }
            pts += UncertainPoint(p, cov, true)
        }
        val extra = sqrt(extraRelSigma * extraRelSigma + biasRel * biasRel)
        return build(pts, plane, model, extra, 0.0, ranges / rays.size, planeOk = fit?.isWellConditioned ?: true)
    }

    /** Shared rectangle maths on corners that already lie on [pl] and include localisation. */
    private fun build(
        input: List<UncertainPoint>,
        pl: Plane,
        model: UncertaintyModel,
        extraRelSigma: Double,
        planarityRms: Double,
        rangeM: Double?,
        planeOk: Boolean,
    ): RectangleMeasurement {
        val e1 = anyPerpendicular(pl.normal)
        val e2 = pl.normal cross e1
        val uv = input.map { Vec2(it.position dot e1, it.position dot e2) }
        val order: List<Int>
        var convex = true
        if (input.size == 4) {
            if (isConvex(uv, listOf(0, 1, 2, 3))) {
                order = listOf(0, 1, 2, 3)
            } else {
                order = outlineOrder(uv)
                convex = isConvex(uv, order)
            }
        } else {
            order = threeCornerOrder(input.map { it.position })
        }
        val s = order.map { input[it] }
        val reordered = order != input.indices.toList()

        val rawW0: RawLength
        val rawH0: RawLength
        val pts: List<Vec3>
        val angles: DoubleArray
        var diagMismatch = 0.0
        if (s.size == 4) {
            pts = s.map { it.position }
            rawW0 = averageEdges(Uncertainty.rawDistance(s[0], s[1]), Uncertainty.rawDistance(s[3], s[2]))
            rawH0 = averageEdges(Uncertainty.rawDistance(s[1], s[2]), Uncertainty.rawDistance(s[0], s[3]))
            angles = DoubleArray(4) { i -> angleDeg(pts[(i + 3) % 4], pts[i], pts[(i + 1) % 4]) }
            diagMismatch = abs(pts[0].distanceTo(pts[2]) - pts[1].distanceTo(pts[3]))
        } else {
            val p3 = s[0].position + s[2].position - s[1].position
            pts = listOf(s[0].position, s[1].position, s[2].position, p3)
            rawW0 = Uncertainty.rawDistance(s[0], s[1])
            rawH0 = Uncertainty.rawDistance(s[1], s[2])
            val a = angleDeg(pts[0], pts[1], pts[2])
            angles = doubleArrayOf(180 - a, a, 180 - a, a)
        }
        val maxDev = angles.maxOf { abs(it - 90.0) }

        // Geometric consistency (MAJOR-3): inconsistency beyond what corner noise explains feeds the σ.
        val w = rawW0.value
        val h = rawH0.value
        var cornerVar = 0.0
        for (c in s) cornerVar += (c.covariance.along(e1) + c.covariance.along(e2)) / 2
        val sigmaCorner = sqrt(cornerVar / s.size)
        val sigmaAngle = if (w > 0 && h > 0) sigmaCorner * sqrt(2.0) * sqrt(1 / (w * w) + 1 / (h * h)) else 0.0
        val excessAngle = max(0.0, Math.toRadians(maxDev) - 2 * sigmaAngle)
        val d = sqrt(w * w + h * h)
        val geomAngle = if (d > 0) excessAngle * w * h / d else 0.0
        val geomDiag = max(0.0, diagMismatch - 4 * sigmaCorner) / 2
        val geom = max(geomAngle, geomDiag)
        val rawW = RawLength(w, sqrt(rawW0.randomSigma * rawW0.randomSigma + geom * geom))
        val rawH = RawLength(h, sqrt(rawH0.randomSigma * rawH0.randomSigma + geom * geom))

        val angleTolDeg = max(SQUARENESS_TOLERANCE_DEG, ANGLE_NOISE_K * Math.toDegrees(sigmaAngle))
        val consistent = planeOk && convex && planarityRms <= MAX_PLANARITY_RMS && maxDev <= angleTolDeg

        val m = model.forScope(pl, rangeM)
        val width = m.length(rawW, extraRelSigma)
        val height = m.length(rawH, extraRelSigma)
        val area = m.product(listOf(rawW, rawH), extraRelSigma)
        // Diagonal: random part propagated from W and H; scale applies once (it is a length).
        val dRandVar = if (d > 0) {
            (w * w * rawW.randomSigma * rawW.randomSigma + h * h * rawH.randomSigma * rawH.randomSigma) / (d * d)
        } else 0.0
        val diagonal = m.length(RawLength(d, sqrt(dRandVar)), extraRelSigma)

        return RectangleMeasurement(
            width, height, area, diagonal, pts, angles, maxDev, maxDev <= SQUARENESS_TOLERANCE_DEG,
            diagMismatch, planarityRms, pl, rawW, rawH, reordered, convex, consistent,
        )
    }

    /** Box from three raw dimensions (any may be a preset with `isMeasured = false`, flagged as an estimate). */
    fun box(
        width: RawLength,
        height: RawLength,
        depth: RawLength,
        model: UncertaintyModel = UncertaintyModel(),
    ): BoxMeasurement = BoxMeasurement(
        model.length(width), model.length(height), model.length(depth),
        model.product(listOf(width, height, depth)),
        !width.isMeasured, !height.isMeasured, !depth.isMeasured,
    )

    /**
     * Free-standing box from points: [base0]→[base1] along the front bottom edge (width), [base1]→[base2]
     * along the side bottom edge (depth), [top] any point on the top face (height above the base).
     * Width/depth use horizontal components only; height is measured along [up] from the mean base level.
     * [localisation] holds one optional entry per point in the order base0, base1, base2, top.
     */
    fun boxFromPoints(
        base0: UncertainPoint,
        base1: UncertainPoint,
        base2: UncertainPoint,
        top: UncertainPoint,
        model: UncertaintyModel = UncertaintyModel(),
        up: Vec3 = Vec3.UP,
        localisation: List<LocalisationInput?>? = null,
    ): BoxMeasurement {
        require(localisation == null || localisation.size == 4) { "One localisation per point (4)" }
        val b0 = model.localise(base0, localisation?.get(0))
        val b1 = model.localise(base1, localisation?.get(1))
        val b2 = model.localise(base2, localisation?.get(2))
        val tp = model.localise(top, localisation?.get(3))
        val u = up.normalized()
        val w = Uncertainty.rawHorizontal(b0, b1, u)
        val d = Uncertainty.rawHorizontal(b1, b2, u)
        val baseLevel = ((b0.position dot u) + (b1.position dot u) + (b2.position dot u)) / 3
        val baseVar = (b0.covariance.along(u) + b1.covariance.along(u) + b2.covariance.along(u)) / 9
        val h = abs((tp.position dot u) - baseLevel)
        val hVar = tp.covariance.along(u) + baseVar
        val range = localisation?.let { UncertaintyModel.meanRange(*it.toTypedArray()) }
        return box(w, RawLength(h, sqrt(hVar.coerceAtLeast(0.0))), d, model.forScope(null, range))
    }

    /** Mean of two opposite edges with a σ that is never below their half-difference. */
    private fun averageEdges(e1: RawLength, e2: RawLength): RawLength {
        val mean = (e1.value + e2.value) / 2
        val propagated = (e1.randomSigma * e1.randomSigma + e2.randomSigma * e2.randomSigma) / 4
        val half = (e1.value - e2.value) / 2
        return RawLength(mean, sqrt(max(propagated, half * half)))
    }

    /** Interior angle at [b] between b→a and b→c, degrees. */
    private fun angleDeg(a: Vec3, b: Vec3, c: Vec3): Double {
        val u = a - b
        val v = c - b
        val den = u.length * v.length
        if (den == 0.0) return 0.0
        return Math.toDegrees(acos(((u dot v) / den).coerceIn(-1.0, 1.0)))
    }

    /** True if the points visited in [order] form a strictly convex polygon (either winding). */
    private fun isConvex(p: List<Vec2>, order: List<Int>): Boolean {
        var sign = 0
        val k = order.size
        for (i in 0 until k) {
            val a = p[order[i]]; val b = p[order[(i + 1) % k]]; val c = p[order[(i + 2) % k]]
            val cr = (b - a) cross (c - b)
            if (cr == 0.0) return false
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Outline order of 4 points by angle around their centroid, starting at input corner 0 and going
     * towards whichever neighbour came earlier in the input (so a Z-order TL, TR, BL, BR becomes
     * TL, TR, BR, BL and width stays the first edge the user tapped).
     */
    private fun outlineOrder(p: List<Vec2>): List<Int> {
        val cx = p.sumOf { it.x } / p.size
        val cy = p.sumOf { it.y } / p.size
        val sorted = p.indices.sortedBy { atan2(p[it].y - cy, p[it].x - cx) }
        val start = sorted.indexOf(0)
        val fwd = List(4) { sorted[(start + it) % 4] }
        val back = List(4) { sorted[(start - it + 4) % 4] }
        return if (fwd[1] <= back[1]) fwd else back
    }

    /**
     * For 3 corners, put the corner whose angle is closest to 90° in the middle (the user may have tapped
     * TL, TR, BL); the other two keep their input order.
     */
    private fun threeCornerOrder(p: List<Vec3>): List<Int> {
        var best = 1
        var bestDev = Double.MAX_VALUE
        for (i in 0 until 3) {
            val dev = abs(angleDeg(p[(i + 2) % 3], p[i], p[(i + 1) % 3]) - 90.0)
            if (dev < bestDev - 1e-9 || (abs(dev - bestDev) <= 1e-9 && i == 1)) { best = i; bestDev = dev }
        }
        val others = (0 until 3).filter { it != best }
        return listOf(others[0], best, others[1])
    }

    /** Some unit vector perpendicular to unit [n]. */
    private fun anyPerpendicular(n: Vec3): Vec3 {
        val helper = if (abs(n.x) < 0.9) Vec3.UNIT_X else Vec3.UNIT_Y
        return (n cross helper).normalized()
    }

    /** s²·v vᵀ. */
    private fun outer(v: Vec3, s2: Double) =
        Cov3(s2 * v.x * v.x, s2 * v.x * v.y, s2 * v.x * v.z, s2 * v.y * v.y, s2 * v.y * v.z, s2 * v.z * v.z)
}
