package com.spaceview.ar

import com.spaceview.engine.CalibrationResult
import com.spaceview.engine.Calibrator
import com.spaceview.engine.KnownLength
import com.spaceview.engine.LocalisationInput
import com.spaceview.engine.Measurement
import com.spaceview.engine.Plane
import com.spaceview.engine.Quality
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.Shapes
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import kotlin.math.roundToLong

/** Measure modes. [requiredPoints] endpoints complete a measurement. */
enum class MeasureMode(val requiredPoints: Int) {
    /** Point-to-point distance. */
    DISTANCE(2),

    /** Vertical distance between two points (floor → top of a cabinet, table height…). */
    HEIGHT(2),

    /** Rectangle from three corners (top-left, top-right, bottom-right): W × H and area. */
    RECTANGLE(3),

    /**
     * Box-shaped object (scan only, not offered on the Measure screen): three base corners on the floor
     * or table (front-left, front-right, back-right: W along the first edge, D along the second), then a
     * point on the top (H, vertical, above the base). The front face is W × H.
     */
    BOX(4),
}

/** Where an endpoint's 3-D position came from (ordered roughly by trust). */
enum class HitKind {
    /** ARCore plane, hit inside its polygon. */
    PLANE,

    /** Our RANSAC plane fitted to raw depth / feature points near the reticle. */
    FITTED_SURFACE,

    /** ARCore depth-point hit (Depth API). */
    DEPTH_POINT,

    /** ARCore feature point with an estimated surface normal. */
    FEATURE_POINT,

    /** Moved by the user on a frozen frame (ray–plane intersection on the point's surface). */
    ADJUSTED,
}

/**
 * One committed (or the live) endpoint.
 *
 * @property point position with the covariance of its estimate plus aiming error.
 * @property normal unit surface normal at the point, if known (for freeze-frame adjustment and tips).
 * @property rangeM camera → point distance when the point was taken.
 * @property planeBiasRel relative length error from a fitted plane's offset (0 for ARCore planes).
 * @property spreadRms per-sample scatter of the estimate (m); large values mean a shaky hold.
 * @property incidenceCos |cos| between the view ray and the surface normal (1 = head-on), if known.
 * @property focalPx focal length in **camera-image** pixels of the image the error is defined in
 *   ([AimModel]: VGA-equivalent for live aim, the still's own for a freeze-frame adjustment), for the
 *   localisation term (range / focal × pixel error). Never view pixels (M2 review B1).
 * @property pixelErrorPx aiming / placement error in those same pixels.
 */
class EndpointData(
    val point: UncertainPoint,
    val normal: Vec3?,
    val kind: HitKind,
    val rangeM: Double,
    val planeBiasRel: Double = 0.0,
    val spreadRms: Double = 0.0,
    val incidenceCos: Double? = null,
    val focalPx: Double = 0.0,
    val pixelErrorPx: Double = 0.0,
) {
    /** Copy at a new position (anchor refinement) keeping the uncertainty, its localisation flag and metadata. */
    fun movedTo(position: Vec3) =
        EndpointData(point.movedTo(position), normal, kind, rangeM, planeBiasRel, spreadRms, incidenceCos, focalPx, pixelErrorPx)

    /** The engine's localisation input for this endpoint, when range and focal length are known. */
    val localisation: LocalisationInput?
        get() = if (focalPx > 0 && rangeM > 0 && rangeM.isFinite()) LocalisationInput(rangeM, focalPx, pixelErrorPx) else null
}

/** Result of measuring a reference card / sheet, for the calibration screen. */
class CalibrationCheck(
    val result: CalibrationResult,
    val maxRightAngleDeviationDeg: Double,
    val geometricallyConsistent: Boolean,
    /** False when a known-length span's two ends are not on one flat surface (no plane to scope it to). */
    val onOneSurface: Boolean = true,
)

/**
 * A measurement result. Values are SI (m, m²). Value-equal snapshots compare equal so a StateFlow
 * does not re-emit identical readings.
 *
 * @property primary distance / height / rectangle width.
 * @property secondary rectangle height.
 * @property rawPrimary / [rawSecondary] uncalibrated raw lengths with their random σ (what
 *   calibration needs), carried as `Measurement(value, randomSigma)`.
 * @property isLive true while the last point is still the moving reticle (not committed).
 * @property geometryConsistent false when the engine found the rectangle's corners inconsistent
 *   (not convex, off-plane, or not square beyond noise); the badge is then ROUGH and the UI says so.
 * @property boxHeight [MeasureMode.BOX] only, once the top point is there: the vertical height above
 *   the base. [primary] is then the width (first base edge), [secondary] the depth (second base edge).
 */
data class Reading(
    val mode: MeasureMode,
    val primary: Measurement,
    val secondary: Measurement? = null,
    val area: Measurement? = null,
    val diagonal: Measurement? = null,
    val quality: Quality,
    val isLive: Boolean,
    val calibrated: Boolean,
    val rawPrimary: Measurement,
    val rawSecondary: Measurement? = null,
    val maxRightAngleDeviationDeg: Double? = null,
    val geometryConsistent: Boolean = true,
    val boxHeight: Measurement? = null,
)

/** One suggestion shown under a result (UI maps it to copy). Ordered by usual payoff. */
enum class ImprovementTip { MOVE_CLOSER, ADD_LIGHT, HOLD_STILL, FACE_SURFACE, AIM_AT_TEXTURE, CALIBRATE, MEASURE_AGAIN }

/**
 * Turns endpoints into [Reading]s using `:engine` only (no maths duplicated here).
 * Pure and stateless; safe to call from any thread.
 */
object MeasurementComposer {

    /**
     * Reading for [mode] from committed [points] plus an optional [live] reticle point.
     * Null while there are not enough points for even a partial reading.
     */
    fun compose(
        mode: MeasureMode,
        points: List<EndpointData>,
        live: EndpointData?,
        model: UncertaintyModel,
    ): Reading? = compose(mode, points, live, model, rounding = true)

    /**
     * As [compose]; with [rounding] false the engine's measurements are returned exactly (tests use it
     * to check that rounding never shrinks a ±, M2 gate R1).
     */
    internal fun compose(
        mode: MeasureMode,
        points: List<EndpointData>,
        live: EndpointData?,
        model: UncertaintyModel,
        rounding: Boolean,
    ): Reading? {
        val all = if (live != null && points.size < mode.requiredPoints) points + live else points
        val isLive = live != null && points.size < mode.requiredPoints
        if (all.size < 2) return null
        val extra = all.maxOf { it.planeBiasRel }
        return when (mode) {
            MeasureMode.DISTANCE -> lengthReading(mode, all[0], all[1], model, extra, isLive, rounding)
            MeasureMode.HEIGHT -> lengthReading(mode, all[0], all[1], model, extra, isLive, rounding)
            MeasureMode.RECTANGLE -> {
                if (all.size == 2) {
                    // Only the first edge so far: show it as the width.
                    lengthReading(mode, all[0], all[1], model, extra, isLive, rounding)
                } else {
                    rectangleReading(all.take(3), model, extra, isLive, rounding)
                }
            }
            MeasureMode.BOX -> when (all.size) {
                2 -> lengthReading(mode, all[0], all[1], model, extra, isLive, rounding)
                3 -> rectangleReading(all, model, extra, isLive, rounding, mode)
                else -> boxReading(all.take(4), model, extra, isLive, rounding)
            }
        }
    }

    /**
     * Calibration from three committed corners of a reference card / sheet ([model] must be
     * uncalibrated). The calibration is scoped to the reference's plane and camera range by the engine.
     */
    fun calibrationCheck(ref: ReferenceObject, corners: List<EndpointData>, model: UncertaintyModel): CalibrationCheck? {
        if (corners.size < 3) return null
        val c = corners.take(3)
        val rect = runCatching {
            Shapes.rectangle(c.map { it.point }, sharedPlane(c), model, c.maxOf { it.planeBiasRel }, c.map { it.localisation })
        }.getOrNull() ?: return null
        val range = c.map { it.rangeM }.average().takeIf { it > 0 && it.isFinite() }
        val result = Calibrator.fromReference(ref, rect, model, range)
        return CalibrationCheck(result, rect.maxRightAngleDeviationDeg, rect.isGeometricallyConsistent)
    }

    /**
     * Calibration from a straight span the user measured with a tape: the first two [ends] are the
     * span's endpoints, [trueValue] ± [trueSigma] (m) the typed tape length ([model] must be
     * uncalibrated). Same acceptance rules as the card / A4 path ([Calibrator]); the result is scoped
     * to the surface both ends lie on, at their mean camera range. Ends on different surfaces (no
     * shared plane) cannot be scoped and are refused with [CalibrationCheck.onOneSurface] = false.
     * Null until two ends are placed.
     */
    fun knownLengthCheck(trueValue: Double, trueSigma: Double, ends: List<EndpointData>, model: UncertaintyModel): CalibrationCheck? {
        if (ends.size < 2) return null
        val e = ends.take(2)
        val plane = sharedPlane(e)
            ?: return CalibrationCheck(CalibrationResult.Rejected(CalibrationResult.Reason.INVALID_INPUT, Double.NaN), 0.0, true, onOneSurface = false)
        val raw = Uncertainty.rawDistance(model.localise(e[0].point, e[0].localisation), model.localise(e[1].point, e[1].localisation))
        val anchor = (e[0].point.position + e[1].point.position) / 2.0
        val range = e.map { it.rangeM }.average().takeIf { it > 0 && it.isFinite() && e.all { p -> p.rangeM > 0 } }
        val result = runCatching {
            Calibrator.calibrate(listOf(KnownLength(trueValue, trueSigma, raw)), null, model, plane, anchor, range)
        }.getOrElse { CalibrationResult.Rejected(CalibrationResult.Reason.INVALID_INPUT, Double.NaN) }
        return CalibrationCheck(result, 0.0, true)
    }

    /**
     * Corners for drawing the rectangle outline (3 given → 4th inferred as a parallelogram).
     */
    fun rectangleOutline(corners: List<Vec3>): List<Vec3> {
        if (corners.size < 3) return corners
        val p3 = corners[0] + corners[2] - corners[1]
        return listOf(corners[0], corners[1], corners[2], p3)
    }

    /**
     * One improvement tip for a finished reading, or null when nothing obvious would help
     * (UI research §4.1 item 6: name the single biggest cause, Moasure-style).
     */
    fun tipFor(reading: Reading, points: List<EndpointData>, lowLightSeen: Boolean): ImprovementTip? {
        if (reading.quality == Quality.GOOD) return null
        val maxRange = points.maxOfOrNull { it.rangeM } ?: 0.0
        return when {
            maxRange > FAR_RANGE_M -> ImprovementTip.MOVE_CLOSER
            lowLightSeen -> ImprovementTip.ADD_LIGHT
            points.any { it.spreadRms > SHAKY_SPREAD_M } -> ImprovementTip.HOLD_STILL
            points.any { (it.incidenceCos ?: 1.0) < OBLIQUE_COS } -> ImprovementTip.FACE_SURFACE
            points.any { it.kind == HitKind.FEATURE_POINT || it.kind == HitKind.DEPTH_POINT } -> ImprovementTip.AIM_AT_TEXTURE
            !reading.calibrated -> ImprovementTip.CALIBRATE
            else -> ImprovementTip.MEASURE_AGAIN
        }
    }

    private fun lengthReading(
        mode: MeasureMode,
        a: EndpointData,
        b: EndpointData,
        model: UncertaintyModel,
        extra: Double,
        isLive: Boolean,
        rounding: Boolean,
    ): Reading {
        val locA = a.localisation
        val locB = b.localisation
        // Calibration applies only when *both* points lie on the calibrated plane, at a similar range
        // (M2 review M2: points on different surfaces are not "plane unknown").
        val scoped = scopedModel(model, listOf(a, b))
        val m = if (mode == MeasureMode.HEIGHT) {
            scoped.height(a.point, b.point, Vec3.UP, locA, locB, extra)
        } else {
            scoped.distance(a.point, b.point, locA, locB, extra)
        }.rounded(rounding)
        val raw = if (mode == MeasureMode.HEIGHT) {
            Uncertainty.rawHeight(scoped.localise(a.point, locA), scoped.localise(b.point, locB))
        } else {
            Uncertainty.rawDistance(scoped.localise(a.point, locA), scoped.localise(b.point, locB))
        }
        return Reading(
            mode = mode,
            primary = m,
            quality = m.quality,
            isLive = isLive,
            calibrated = scoped.calibration != null,
            rawPrimary = Measurement(raw.value, raw.randomSigma).rounded(rounding),
        )
    }

    private fun rectangleReading(
        corners: List<EndpointData>,
        model: UncertaintyModel,
        extra: Double,
        isLive: Boolean,
        rounding: Boolean,
        mode: MeasureMode = MeasureMode.RECTANGLE,
    ): Reading {
        val plane = sharedPlane(corners)
        // Calibration only when every corner lies on the calibrated plane (M2 review M2).
        val scoped = scopedModel(model, corners)
        // Shapes.rectangle (not rectangleFromRays): corners are averaged surface hits already on the
        // plane, and their estimate covariance must be kept. It reorders Z-order input and forces ROUGH
        // on inconsistent geometry. Image-corner rays arrive with edge snapping (M3).
        val rect = Shapes.rectangle(corners.map { it.point }, plane, scoped, extra, corners.map { it.localisation })
        // Shapes applies its own plane/range scope on top; the flag says what was actually applied.
        // Same range Shapes uses for its scope: mean over the corners that carry localisation.
        val range = corners.mapNotNull { it.localisation?.rangeMeters }.takeIf { it.isNotEmpty() }?.average()
        val calibrated = scoped.forScope(rect.plane, range).calibration != null
        return Reading(
            mode = mode,
            primary = rect.width.rounded(rounding),
            secondary = rect.height.rounded(rounding),
            area = rect.area.rounded(rounding),
            diagonal = rect.diagonal.rounded(rounding),
            quality = rect.quality,
            isLive = isLive,
            calibrated = calibrated,
            rawPrimary = Measurement(rect.rawWidth.value, rect.rawWidth.randomSigma).rounded(rounding),
            rawSecondary = Measurement(rect.rawHeight.value, rect.rawHeight.randomSigma).rounded(rounding),
            maxRightAngleDeviationDeg = rect.maxRightAngleDeviationDeg,
            geometryConsistent = rect.isGeometricallyConsistent,
        )
    }

    /**
     * Box: W × D from the three base corners (the rectangle path, with its geometry checks), H from the
     * top point to the base corner nearest to it horizontally (the height path, with its own scope: the
     * top is never on the base plane, so a calibration of the base surface does not apply to H).
     * The badge is the worse of the two; "calibrated" only if both are.
     */
    private fun boxReading(
        points: List<EndpointData>,
        model: UncertaintyModel,
        extra: Double,
        isLive: Boolean,
        rounding: Boolean,
    ): Reading {
        val base = rectangleReading(points.take(3), model, extra, isLive, rounding, MeasureMode.BOX)
        val top = points[3]
        val foot = points[boxFoot(points.take(3).map { it.point.position }, top.point.position)]
        val h = lengthReading(MeasureMode.HEIGHT, foot, top, model, extra, isLive, rounding)
        return base.copy(
            area = null,
            diagonal = null,
            boxHeight = h.primary,
            quality = Quality.worst(base.quality, h.quality),
            calibrated = base.calibrated && h.calibrated,
        )
    }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val d = b - a
        val v = d - Vec3.UP * (d dot Vec3.UP)
        return v.length
    }

    /**
     * The one base reference of a box (M3 review m8): index (0..2) of the **tapped** base corner
     * horizontally nearest to the [top] point. H is measured from it, the overlay draws H from it and the
     * photographed face is lifted from its level.
     */
    fun boxFoot(base: List<Vec3>, top: Vec3): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in 0 until minOf(3, base.size)) {
            val d = horizontalDistance(base[i], top)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * True when a box's top point is not clearly above its base (under [MIN_BOX_HEIGHT_M] above the foot,
     * or below it): almost always a tap on the floor next to the object (M3 review m7).
     */
    fun boxTopTooLow(base: List<Vec3>, top: Vec3): Boolean {
        if (base.size < 3) return false
        val foot = base[boxFoot(base, top)]
        return ((top - foot) dot Vec3.UP) < MIN_BOX_HEIGHT_M
    }

    /** A box's top must be at least this far above its base. Tunable. */
    const val MIN_BOX_HEIGHT_M = 0.02

    /** [model] if its calibration applies to all of [points] (positions, normals, mean range), else uncalibrated. */
    internal fun scopedModel(model: UncertaintyModel, points: List<EndpointData>): UncertaintyModel {
        if (model.calibration == null) return model
        val range = points.map { it.rangeM }.average().takeIf { r -> r > 0 && r.isFinite() && points.all { it.rangeM > 0 } }
        return model.forPoints(points.map { it.point.position }, points.map { it.normal }, range)
    }

    /**
     * The surface shared by [points]: through their centroid with their mean normal, when every point
     * has a known normal, the normals agree within ~10° and every point lies within
     * [SAME_PLANE_OFFSET_M] of it. Otherwise null (different surfaces, e.g. floor and table top).
     */
    private fun sharedPlane(points: List<EndpointData>): Plane? {
        val normals = points.map { it.normal ?: return null }
        val ref = normals[0]
        var sum = Vec3.ZERO
        for (n in normals) {
            val aligned = if ((n dot ref) < 0) -n else n
            if ((aligned dot ref) < SAME_PLANE_COS) return null
            sum += aligned
        }
        if (sum.length < 1e-9) return null
        val centroid = points.fold(Vec3.ZERO) { acc, c -> acc + c.point.position } / points.size.toDouble()
        val plane = Plane.fromPointNormal(centroid, sum.normalized())
        if (points.any { kotlin.math.abs(plane.signedDistance(it.point.position)) > SAME_PLANE_OFFSET_M }) return null
        return plane
    }

    /**
     * Value to the nearest 0.1 mm and σ **up** to the next 0.01 mm, so equal-looking readings are
     * value-equal (fewer UI updates) and the ± shown is never below the engine's (M2 gate R1).
     */
    private fun Measurement.rounded(on: Boolean): Measurement {
        if (!on) return this
        val s = if (sigma > 0) kotlin.math.ceil(sigma / SIGMA_STEP).coerceAtLeast(1.0) * SIGMA_STEP else 0.0
        return Measurement(round(value, 1e-4), s)
    }

    private const val SIGMA_STEP = 1e-5

    private fun round(v: Double, step: Double) = (v / step).roundToLong() * step

    /** Beyond this, "move closer" is the best advice (research §2 item 7). */
    const val FAR_RANGE_M = 1.5

    /** Per-sample scatter above this suggests a shaky hold. */
    const val SHAKY_SPREAD_M = 0.006

    /** cos 60°: viewing a surface more obliquely than this inflates depth bias. */
    const val OBLIQUE_COS = 0.5

    private val SAME_PLANE_COS = kotlin.math.cos(Math.toRadians(10.0))

    /** Points further than this from a common plane are on different surfaces. */
    const val SAME_PLANE_OFFSET_M = 0.02

    /** Minimum value accepted as a measurement (avoids "0 cm" from a double tap on one spot). */
    const val MIN_LENGTH_M = 0.005

    /** True when a committed set of points is degenerate (two endpoints on the same spot). */
    fun isDegenerate(points: List<EndpointData>): Boolean {
        for (i in points.indices) for (j in i + 1 until points.size) {
            if (points[i].point.position.distanceTo(points[j].point.position) < MIN_LENGTH_M) return true
        }
        return false
    }
}
