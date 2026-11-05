package com.spaceview.ar

import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Shapes
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/** How an object is outlined in AR (the scan type picker maps object kinds onto these). */
enum class ScanShape(val mode: MeasureMode) {
    /** Flat on a wall (frame, mirror, poster, TV): front rectangle from three corners; depth is typed. */
    FLAT(MeasureMode.RECTANGLE),

    /** Box-shaped (appliance, cabinet, carton, anything else): three base corners + a top point. */
    BOX(MeasureMode.BOX),
}

/**
 * The object's front face in world space.
 *
 * @property corners top-left, top-right, bottom-right, bottom-left **as seen from the front** (so a
 *   photo projected with them is upright and never mirrored).
 * @property normal unit normal out of the face, towards the side it is viewed from.
 */
class FrontFace(val corners: List<Vec3>, val normal: Vec3) {
    init {
        require(corners.size == 4)
    }

    val centre: Vec3 get() = (corners[0] + corners[1] + corners[2] + corners[3]) / 4.0
}

/**
 * Which face is the front and which way is up, decided **once** per outline (M3 review M1) and then
 * reused for the outline values, the Outline card, the Review and the photo capture.
 *
 * @property edge [ScanShape.BOX]: base outline edge k (corner k → k+1) whose vertical face is the front;
 *   even k runs along the reading's width. Always 0 for [ScanShape.FLAT].
 * @property up [ScanShape.FLAT]: the face's "up" (world up, or "away from the user" for a horizontal face).
 * @property towards which side of the face the user is on (a unit normal direction).
 */
class FaceChoice(val edge: Int, val up: Vec3, val towards: Vec3)

/**
 * Result of the outline step of a scan: the measured sizes of the object with their 95 % bounds, the
 * badge, and the front face for the photo. Values come from the same [Reading] the user saw.
 *
 * @property width horizontal size of the front face.
 * @property height vertical size of the front face.
 * @property depth box only (measured along the base); null for [ScanShape.FLAT] (depth is typed later).
 * @property diagonal front-face diagonal, [ScanShape.FLAT] only (TVs and monitors are sold by it).
 * @property geometryConsistent false when the corners were inconsistent (the badge is then ROUGH).
 * @property choice the front-face decision; the photo capture must use exactly this one.
 */
class ScanOutline(
    val shape: ScanShape,
    val width: Measurement,
    val height: Measurement,
    val depth: Measurement?,
    val diagonal: Measurement?,
    val quality: Quality,
    val calibrated: Boolean,
    val geometryConsistent: Boolean,
    val face: FrontFace,
    val choice: FaceChoice,
)

/**
 * Turns a finished scan [Reading] and its committed endpoints into a [ScanOutline], and builds the
 * front face for the photo capture. Pure (engine maths only); safe on any thread.
 */
object ScanComposer {

    /**
     * Decides the front face from where the user stands: for a box, the vertical face the camera is
     * most squarely in front of (largest cosine between the face's outward normal and the direction
     * from the face's centre line to the camera), so standing near one end of a long face still picks
     * that face; for a flat object, the side the camera is on and the face's up direction.
     */
    fun chooseFace(shape: ScanShape, points: List<Vec3>, cameraPosition: Vec3, cameraForward: Vec3): FaceChoice? = runCatching {
        when (shape) {
            ScanShape.FLAT -> {
                val rect = Shapes.rectangle(points.take(3).map { UncertainPoint.of(it) })
                val n0 = rect.plane.normal
                val n = if ((cameraPosition - rect.corners[0]) dot n0 >= 0) n0 else -n0
                FaceChoice(0, faceUp(n, cameraForward), n)
            }
            ScanShape.BOX -> {
                val edge = frontEdge(points, cameraPosition)
                val base = baseOutline(points)
                FaceChoice(edge, Vec3.UP, outwardNormal(base, edge, cameraForward))
            }
        }
    }.getOrNull()

    /**
     * Outline of a finished [reading] ([MeasureMode.RECTANGLE] for [ScanShape.FLAT],
     * [MeasureMode.BOX] with its top point for [ScanShape.BOX]) with the front face fixed by [choice].
     * Null when the reading is not a finished one of that shape or the geometry is degenerate.
     */
    fun outline(shape: ScanShape, reading: Reading, points: List<Vec3>, choice: FaceChoice): ScanOutline? {
        if (reading.mode != shape.mode || reading.isLive || points.size < shape.mode.requiredPoints) return null
        return runCatching {
            val face = face(shape, points, choice) ?: return null
            when (shape) {
                ScanShape.FLAT -> {
                    val secondary = reading.secondary ?: return null
                    // The reading's width is the first outline edge; the object's width is the horizontal one.
                    val (w, h) = if (firstEdgeHorizontal(points, choice)) reading.primary to secondary else secondary to reading.primary
                    ScanOutline(shape, w, h, null, reading.diagonal, reading.quality, reading.calibrated, reading.geometryConsistent, face, choice)
                }
                ScanShape.BOX -> {
                    val depth = reading.secondary ?: return null
                    val height = reading.boxHeight ?: return null
                    // Front edge along the first base edge → W = primary; along the second → swap W and D.
                    val (w, d) = if (choice.edge % 2 == 0) reading.primary to depth else depth to reading.primary
                    ScanOutline(shape, w, height, d, null, reading.quality, reading.calibrated, reading.geometryConsistent, face, choice)
                }
            }
        }.getOrNull()
    }

    /** The front face from the current endpoint [points] (anchors refine them) with the fixed [choice]. */
    fun face(shape: ScanShape, points: List<Vec3>, choice: FaceChoice): FrontFace? = runCatching {
        when (shape) {
            ScanShape.FLAT -> {
                val rect = Shapes.rectangle(points.take(3).map { UncertainPoint.of(it) })
                val n0 = rect.plane.normal
                val n = if ((n0 dot choice.towards) >= 0) n0 else -n0
                val up = (choice.up - n * (choice.up dot n)).normalized()
                FrontFace(canonicalOrder(rect.corners, n, up), n)
            }
            ScanShape.BOX -> boxFace(points, choice)
        }
    }.getOrNull()

    /**
     * Index k (0..3) of the base outline edge (corner k → k+1) whose outward face the camera is most
     * squarely in front of (horizontally). Even k runs along the width edges.
     */
    fun frontEdge(points: List<Vec3>, cameraPosition: Vec3): Int {
        val base = baseOutline(points)
        val centroid = (base[0] + base[1] + base[2] + base[3]) / 4.0
        var best = 0
        var bestScore = -Double.MAX_VALUE
        for (k in 0 until 4) {
            val a = base[k]
            val b = base[(k + 1) % 4]
            val mid = (a + b) / 2.0
            val outward = horizontal(mid - centroid)
            val toCamera = horizontal(cameraPosition - mid)
            if (outward.length < 1e-9 || toCamera.length < 1e-9) continue
            val score = (outward.normalized() dot toCamera.normalized())
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        return best
    }

    // ---- flat --------------------------------------------------------------------------------------

    private fun firstEdgeHorizontal(points: List<Vec3>, choice: FaceChoice): Boolean {
        val rect = Shapes.rectangle(points.take(3).map { UncertainPoint.of(it) })
        val q = rect.corners
        val n = rect.plane.normal.let { if ((it dot choice.towards) >= 0) it else -it }
        val up = (choice.up - n * (choice.up dot n)).normalized()
        val right = up cross n
        val e01 = q[1] - q[0]
        return abs(e01 dot right) >= abs(e01 dot up)
    }

    // ---- box ---------------------------------------------------------------------------------------

    private fun baseOutline(points: List<Vec3>): List<Vec3> =
        Shapes.rectangle(points.take(3).map { UncertainPoint.of(it) }).corners

    private fun outwardNormal(base: List<Vec3>, edge: Int, cameraForward: Vec3): Vec3 {
        val a = base[edge % 4]
        val b = base[(edge + 1) % 4]
        val centroid = (base[0] + base[1] + base[2] + base[3]) / 4.0
        val outward = horizontal((a + b) / 2.0 - centroid)
        return if (outward.length > 1e-6) outward.normalized() else horizontal(-cameraForward).normalized()
    }

    private fun boxFace(points: List<Vec3>, choice: FaceChoice): FrontFace {
        require(points.size >= 4)
        val base = baseOutline(points)
        val a = base[choice.edge % 4]
        val b = base[(choice.edge + 1) % 4]
        val n = outwardNormal(base, choice.edge, -choice.towards)
        // Lifted from the same base reference H is measured from (M3 review m8).
        val top = points[3]
        val foot = points[MeasurementComposer.boxFoot(points.take(3), top)]
        val lift = Vec3.UP * ((top - foot) dot Vec3.UP)
        return FrontFace(canonicalOrder(listOf(a, b, b + lift, a + lift), n, Vec3.UP), n)
    }

    // ---- shared ------------------------------------------------------------------------------------

    /**
     * "Up" within a face with normal [n]: world up projected onto the face; for a (nearly) horizontal
     * face, the direction away from the viewer ([cameraForward] projected onto it).
     */
    private fun faceUp(n: Vec3, cameraForward: Vec3): Vec3 {
        val up = Vec3.UP - n * (Vec3.UP dot n)
        if (up.length > HORIZONTAL_FACE_UP) return up.normalized()
        val away = cameraForward - n * (cameraForward dot n)
        return if (away.length > 1e-6) away.normalized() else (Vec3.UNIT_Z cross n).normalized()
    }

    /**
     * [corners] (any order around the outline) as top-left, top-right, bottom-right, bottom-left seen
     * from the side [n] points to, with [up] the face's up direction.
     */
    internal fun canonicalOrder(corners: List<Vec3>, n: Vec3, up: Vec3): List<Vec3> {
        val right = up cross n
        val c = (corners[0] + corners[1] + corners[2] + corners[3]) / 4.0
        val r = DoubleArray(4) { (corners[it] - c) dot right }
        val u = DoubleArray(4) { (corners[it] - c) dot up }
        // Clockwise as seen by the viewer = decreasing angle in the (right, up) plane.
        val byAngle = (0 until 4).sortedByDescending { atan2(u[it], r[it]) }
        val tl = (0 until 4).maxBy { u[it] - r[it] }
        val start = byAngle.indexOf(tl)
        return List(4) { corners[byAngle[(start + it) % 4]] }
    }

    private fun horizontal(v: Vec3): Vec3 = v - Vec3.UP * (v dot Vec3.UP)

    /** Below this, world up has (almost) no component in the face: it is horizontal. */
    private const val HORIZONTAL_FACE_UP = 0.35
}
