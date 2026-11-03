package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rectangle (frame, TV, door, A4 sheet…) measured from 3–4 corners snapped to a plane.
 *
 * @property width mean of the two "horizontal" edges (corner 0→1 and 3→2).
 * @property height mean of the two "vertical" edges (corner 1→2 and 0→3).
 * @property area W × H with correlated-scale propagation.
 * @property diagonal √(W² + H²), for TVs/monitors.
 * @property corners the 4 snapped corners (the 4th is inferred as a parallelogram when only 3 were given).
 * @property cornerAnglesDeg interior angle at each measured corner (3-corner input: only the middle one is meaningful, the others mirror it).
 * @property maxRightAngleDeviationDeg worst |angle − 90°|.
 * @property isSquare true if every corner is within [Shapes.SQUARENESS_TOLERANCE_DEG] of 90°.
 * @property diagonalMismatch |diag₀₂ − diag₁₃| (m); 0 for a perfect rectangle (and for 3-corner input).
 * @property planarityRms RMS distance of the input corners from the plane before snapping (m).
 * @property rawWidth / [rawHeight] uncalibrated lengths (feed [Calibrator.fromReference]).
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
) {
    /** Worst badge of width and height. */
    val quality: Quality get() = Quality.worst(width.quality, height.quality)
}

/** Box W × H × D (appliance, cabinet, carton, or a wall-hung item with a preset depth). */
class BoxMeasurement(
    val width: Measurement,
    val height: Measurement,
    val depth: Measurement,
    val volume: Measurement,
) {
    /** Worst badge of the measured dimensions (unmeasured preset dims have σ = 0 and count as GOOD). */
    val quality: Quality get() = Quality.worst(width.quality, height.quality, depth.quality)
}

/** Shape measurements built on [UncertaintyModel]. */
object Shapes {
    /** Max |corner angle − 90°| still called square (research §6.3: ≤ 2°). Tunable. */
    const val SQUARENESS_TOLERANCE_DEG = 2.0

    /**
     * Rectangle from 3 or 4 corners, in order around the outline: top-left, top-right, bottom-right
     * [, bottom-left]. Each corner is snapped (orthogonally projected) onto [plane] — typically the
     * RANSAC-fitted wall plane; if null, a least-squares plane through the corners is used.
     *
     * Width/height average the opposite edges; their random σ is the larger of the propagated corner
     * errors and the half-difference of the two opposite edges (catches a mis-snapped corner).
     */
    fun rectangle(
        corners: List<UncertainPoint>,
        plane: Plane? = null,
        model: UncertaintyModel = UncertaintyModel(),
        extraRelSigma: Double = 0.0,
    ): RectangleMeasurement {
        require(corners.size == 3 || corners.size == 4) { "Need 3 or 4 corners, got ${corners.size}" }
        val pl = plane ?: PlaneFitter.leastSquares(corners.map { it.position })?.plane
            ?: throw IllegalArgumentException("Corners are collinear")
        var ss = 0.0
        for (c in corners) { val r = pl.signedDistance(c.position); ss += r * r }
        val planarityRms = sqrt(ss / corners.size)
        val s = corners.map { pl.project(it) }

        val rawW: RawLength
        val rawH: RawLength
        val pts: List<Vec3>
        val angles: DoubleArray
        var diagMismatch = 0.0
        if (s.size == 4) {
            pts = s.map { it.position }
            rawW = averageEdges(Uncertainty.rawDistance(s[0], s[1]), Uncertainty.rawDistance(s[3], s[2]))
            rawH = averageEdges(Uncertainty.rawDistance(s[1], s[2]), Uncertainty.rawDistance(s[0], s[3]))
            angles = DoubleArray(4) { i -> angleDeg(pts[(i + 3) % 4], pts[i], pts[(i + 1) % 4]) }
            diagMismatch = abs(pts[0].distanceTo(pts[2]) - pts[1].distanceTo(pts[3]))
        } else {
            val p3 = s[0].position + s[2].position - s[1].position
            pts = listOf(s[0].position, s[1].position, s[2].position, p3)
            rawW = Uncertainty.rawDistance(s[0], s[1])
            rawH = Uncertainty.rawDistance(s[1], s[2])
            val a = angleDeg(pts[0], pts[1], pts[2])
            angles = doubleArrayOf(180 - a, a, 180 - a, a)
        }
        val maxDev = angles.maxOf { abs(it - 90.0) }

        val width = model.length(rawW, extraRelSigma)
        val height = model.length(rawH, extraRelSigma)
        val area = model.product(listOf(rawW, rawH), extraRelSigma)
        // Diagonal: random part propagated from W and H; scale applies once (it is a length).
        val dRaw = sqrt(rawW.value * rawW.value + rawH.value * rawH.value)
        val dRandVar = if (dRaw > 0) {
            (rawW.value * rawW.value * rawW.randomSigma * rawW.randomSigma +
                rawH.value * rawH.value * rawH.randomSigma * rawH.randomSigma) / (dRaw * dRaw)
        } else 0.0
        val diagonal = model.length(RawLength(dRaw, sqrt(dRandVar)), extraRelSigma)

        return RectangleMeasurement(
            width, height, area, diagonal, pts, angles, maxDev, maxDev <= SQUARENESS_TOLERANCE_DEG,
            diagMismatch, planarityRms, pl, rawW, rawH,
        )
    }

    /** Box from three raw dimensions (any may be a preset with `isMeasured = false`). */
    fun box(
        width: RawLength,
        height: RawLength,
        depth: RawLength,
        model: UncertaintyModel = UncertaintyModel(),
    ): BoxMeasurement = BoxMeasurement(
        model.length(width), model.length(height), model.length(depth),
        model.product(listOf(width, height, depth)),
    )

    /**
     * Free-standing box from points: [base0]→[base1] along the front bottom edge (width), [base1]→[base2]
     * along the side bottom edge (depth), [top] any point on the top face (height above the base).
     * Width/depth use horizontal components only; height is measured along [up] from the mean base level.
     */
    fun boxFromPoints(
        base0: UncertainPoint,
        base1: UncertainPoint,
        base2: UncertainPoint,
        top: UncertainPoint,
        model: UncertaintyModel = UncertaintyModel(),
        up: Vec3 = Vec3.UP,
    ): BoxMeasurement {
        val u = up.normalized()
        val w = Uncertainty.rawHorizontal(base0, base1, u)
        val d = Uncertainty.rawHorizontal(base1, base2, u)
        val baseLevel = ((base0.position dot u) + (base1.position dot u) + (base2.position dot u)) / 3
        val baseVar = (base0.covariance.along(u) + base1.covariance.along(u) + base2.covariance.along(u)) / 9
        val h = abs((top.position dot u) - baseLevel)
        val hVar = top.covariance.along(u) + baseVar
        return box(w, RawLength(h, sqrt(hVar.coerceAtLeast(0.0))), d, model)
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
}
