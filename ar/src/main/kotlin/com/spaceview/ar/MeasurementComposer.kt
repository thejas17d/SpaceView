package com.spaceview.ar

import com.spaceview.engine.CalibrationResult
import com.spaceview.engine.Calibrator
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
 * @property focalPx focal length (px) of the image the user aimed in (GPU texture), for the
 *   localisation term (range / focal × pixel error).
 * @property pixelErrorPx aiming / placement error in those pixels.
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
    ): Reading? {
        val all = if (live != null && points.size < mode.requiredPoints) points + live else points
        val isLive = live != null && points.size < mode.requiredPoints
        if (all.size < 2) return null
        val extra = all.maxOf { it.planeBiasRel }
        return when (mode) {
            MeasureMode.DISTANCE -> lengthReading(mode, all[0], all[1], model, extra, isLive)
            MeasureMode.HEIGHT -> lengthReading(mode, all[0], all[1], model, extra, isLive)
            MeasureMode.RECTANGLE -> {
                if (all.size == 2) {
                    // Only the first edge so far: show it as the width.
                    lengthReading(mode, all[0], all[1], model, extra, isLive)
                } else {
                    rectangleReading(all.take(3), model, extra, isLive)
                }
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
    ): Reading {
        val locA = a.localisation
        val locB = b.localisation
        // Calibration applies only on its own plane and range (engine scope rules).
        val scoped = model.forScope(sharedPlane(listOf(a, b)), meanRange(a, b))
        val m = if (mode == MeasureMode.HEIGHT) {
            scoped.height(a.point, b.point, Vec3.UP, locA, locB, extra)
        } else {
            scoped.distance(a.point, b.point, locA, locB, extra)
        }.rounded()
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
            rawPrimary = Measurement(raw.value, raw.randomSigma).rounded(),
        )
    }

    private fun rectangleReading(
        corners: List<EndpointData>,
        model: UncertaintyModel,
        extra: Double,
        isLive: Boolean,
    ): Reading {
        val plane = sharedPlane(corners)
        // Shapes.rectangle (not rectangleFromRays): corners are averaged surface hits already on the
        // plane, and their estimate covariance must be kept. It reorders Z-order input and forces ROUGH
        // on inconsistent geometry. Image-corner rays arrive with edge snapping (M3).
        val rect = Shapes.rectangle(corners.map { it.point }, plane, model, extra, corners.map { it.localisation })
        val calibrated = model.forScope(rect.plane, corners.map { it.rangeM }.average()).calibration != null
        return Reading(
            mode = MeasureMode.RECTANGLE,
            primary = rect.width.rounded(),
            secondary = rect.height.rounded(),
            area = rect.area.rounded(),
            diagonal = rect.diagonal.rounded(),
            quality = rect.quality,
            isLive = isLive,
            calibrated = calibrated,
            rawPrimary = Measurement(rect.rawWidth.value, rect.rawWidth.randomSigma).rounded(),
            rawSecondary = Measurement(rect.rawHeight.value, rect.rawHeight.randomSigma).rounded(),
            maxRightAngleDeviationDeg = rect.maxRightAngleDeviationDeg,
            geometryConsistent = rect.isGeometricallyConsistent,
        )
    }

    private fun meanRange(a: EndpointData, b: EndpointData): Double? =
        ((a.rangeM + b.rangeM) / 2).takeIf { a.rangeM > 0 && b.rangeM > 0 && it.isFinite() }

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

    /** Rounds to 0.1 mm / 0.01 mm so equal-looking readings are value-equal (fewer UI updates). */
    private fun Measurement.rounded(): Measurement =
        Measurement(round(value, 1e-4), round(sigma, 1e-5).let { if (sigma > 0 && it == 0.0) 1e-5 else it })

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
