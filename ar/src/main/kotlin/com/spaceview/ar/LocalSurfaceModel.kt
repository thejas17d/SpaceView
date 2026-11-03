package com.spaceview.ar

import com.spaceview.engine.PlaneFit
import com.spaceview.engine.PlaneFitter
import com.spaceview.engine.RansacConfig
import com.spaceview.engine.Ray
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.max

/**
 * A surface fitted by `:engine`'s RANSAC [PlaneFitter] to the raw 3-D points (ARCore feature points
 * and, when enabled, raw depth) around the reticle. Used where ARCore has no plane, e.g. plain
 * walls and the face of a frame or a TV (docs/research/measurement-engine.md §2 item 3).
 *
 * @property fit the plane fit (residual feeds the uncertainty).
 * @property pointCount points the fit used as input.
 */
class LocalSurface(val fit: PlaneFit, val pointCount: Int, val timestampNs: Long)

/** Result of intersecting the reticle ray with a [LocalSurface]. */
class SurfaceIntersection(
    val position: Vec3,
    /** Unit normal of the fitted plane, facing the camera. */
    val normal: Vec3,
    /** Distance along the ray (m). */
    val range: Double,
    /** Relative length error from the plane-offset uncertainty ([Uncertainty.planeBiasRel]). */
    val planeBiasRel: Double,
)

/**
 * Tunables for [LocalSurfaceModel]. Thresholds are placeholders for the bench test.
 *
 * @property maxPoints ring-buffer size. Points far from the reticle are filtered out before buffering
 *   (see [LocalSurfaceModel.addPoints]), so the buffer holds what the fit can use (M2 review minor 6).
 * @property maxAgeNanos points older than this are ignored (the map refines; old points drift).
 * @property coneRadiusMin / [coneRadiusRel] a point is "near the reticle" when its perpendicular
 *   distance from the reticle ray is below max(min, rel × distance along the ray).
 * @property minPoints points needed near the reticle before fitting.
 * @property maxRms fits with a larger RMS residual are not trusted (bumpy or two surfaces).
 * @property minInlierRatio fits whose inlier share is lower are not trusted.
 * @property minCosIncidence the reticle ray must hit the plane at least this steeply (cos of the
 *   angle to the normal; 0.26 ≈ 75°): grazing views amplify any plane error.
 * @property refitIntervalNanos at most one RANSAC fit per this interval.
 * @property bufferConeFactor points are buffered only within this multiple of the cone radius around the
 *   reticle ray of the frame they arrive with (the reticle moves, so the buffer cone is wider).
 * @property depthBandMin / [depthBandRel] with a raw hit distance h, only points whose distance along the
 *   ray is within max(min, rel × h) of h are fitted (M2 review M4: never fit a deeper layer, e.g. the floor
 *   around a box, and let it replace the nearer hit).
 */
class LocalSurfaceConfig(
    val maxPoints: Int = 2048,
    val maxAgeNanos: Long = 3_000_000_000L,
    val coneRadiusMin: Double = 0.04,
    val coneRadiusRel: Double = 0.07,
    val minPoints: Int = 24,
    val maxRms: Double = 0.008,
    val minInlierRatio: Double = 0.6,
    val minCosIncidence: Double = 0.26,
    val refitIntervalNanos: Long = 150_000_000L,
    val ransac: RansacConfig = RansacConfig(inlierThreshold = 0.01, maxIterations = 200, minInliers = 16),
    val bufferConeFactor: Double = 3.0,
    val depthBandMin: Double = 0.04,
    val depthBandRel: Double = 0.05,
)

/**
 * Keeps recent world-space points and fits a local plane around the reticle ray on demand.
 * Not thread-safe; owned by the measurement worker.
 */
class LocalSurfaceModel(private val config: LocalSurfaceConfig = LocalSurfaceConfig()) {
    private val xs = DoubleArray(config.maxPoints)
    private val ys = DoubleArray(config.maxPoints)
    private val zs = DoubleArray(config.maxPoints)
    private val ts = LongArray(config.maxPoints)
    private var next = 0
    private var size = 0
    private var lastFitNs = Long.MIN_VALUE
    private var current: LocalSurface? = null
    private var lastHint: Double? = null
    private val near = ArrayList<Vec3>(256)

    /** The latest accepted fit, if any. */
    val surface: LocalSurface? get() = current

    fun clear() {
        next = 0
        size = 0
        current = null
        lastFitNs = Long.MIN_VALUE
        lastHint = null
    }

    /**
     * Adds points given as packed x, y, z triplets (world, metres). With [ray] (the reticle ray of the
     * same frame) only points within [LocalSurfaceConfig.bufferConeFactor] × the cone radius are kept,
     * so a large cloud does not flush the points near the reticle out of the buffer.
     */
    fun addPoints(xyz: FloatArray, count: Int, timestampNs: Long, ray: Ray? = null) {
        val ox = ray?.origin?.x ?: 0.0; val oy = ray?.origin?.y ?: 0.0; val oz = ray?.origin?.z ?: 0.0
        val dx = ray?.direction?.x ?: 0.0; val dy = ray?.direction?.y ?: 0.0; val dz = ray?.direction?.z ?: 0.0
        for (i in 0 until count) {
            val x = xyz[i * 3].toDouble()
            val y = xyz[i * 3 + 1].toDouble()
            val z = xyz[i * 3 + 2].toDouble()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            if (ray != null) {
                val rx = x - ox; val ry = y - oy; val rz = z - oz
                val t = rx * dx + ry * dy + rz * dz
                if (t < MIN_RANGE) continue
                val px = rx - dx * t; val py = ry - dy * t; val pz = rz - dz * t
                val r = config.bufferConeFactor * max(config.coneRadiusMin, config.coneRadiusRel * t)
                if (px * px + py * py + pz * pz > r * r) continue
            }
            xs[next] = x; ys[next] = y; zs[next] = z; ts[next] = timestampNs
            next = (next + 1) % config.maxPoints
            if (size < config.maxPoints) size++
        }
    }

    /**
     * Refits the plane around [ray] when the refit interval has passed. Returns the current surface
     * (possibly unchanged), or null when the points near the ray do not form a trustworthy plane.
     *
     * [rangeHint] is the distance of the raw (depth / feature) hit along the ray, when there is one:
     * only points in a depth band around it are fitted, so a deeper layer inside the cone (the floor
     * around a box top) cannot win the RANSAC vote (M2 review M4).
     */
    fun update(ray: Ray, nowNs: Long, rangeHint: Double? = null): LocalSurface? {
        val hint = rangeHint?.takeIf { it > 0 && it.isFinite() }
        val band = hint?.let { max(config.depthBandMin, config.depthBandRel * it) }
        val prev = lastHint
        // A hint that jumped to another depth layer forces a refit even inside the refit interval.
        val hintMoved = (hint == null) != (prev == null) || (hint != null && prev != null && kotlin.math.abs(hint - prev) > band!! / 2)
        if (lastFitNs != Long.MIN_VALUE && nowNs - lastFitNs < config.refitIntervalNanos && !hintMoved) return current
        lastFitNs = nowNs
        lastHint = hint
        near.clear()
        val o = ray.origin
        val d = ray.direction
        for (i in 0 until size) {
            if (nowNs - ts[i] > config.maxAgeNanos) continue
            val rx = xs[i] - o.x; val ry = ys[i] - o.y; val rz = zs[i] - o.z
            val t = rx * d.x + ry * d.y + rz * d.z
            if (t < MIN_RANGE) continue
            if (hint != null && kotlin.math.abs(t - hint) > band!!) continue
            val px = rx - d.x * t; val py = ry - d.y * t; val pz = rz - d.z * t
            val r = max(config.coneRadiusMin, config.coneRadiusRel * t)
            if (px * px + py * py + pz * pz <= r * r) near += Vec3(xs[i], ys[i], zs[i])
        }
        if (near.size < config.minPoints) {
            current = null
            return null
        }
        val fit = PlaneFitter.ransac(near, config.ransac)
        current = if (fit != null && fit.rmsResidual <= config.maxRms && fit.inlierRatio >= config.minInlierRatio) {
            LocalSurface(fit, near.size, nowNs)
        } else {
            null
        }
        return current
    }

    /**
     * Intersects [ray] with the current surface, or null when there is none / the view is grazing.
     * [cameraPosition] gives the perpendicular camera distance for the plane-bias term.
     */
    fun intersect(ray: Ray, cameraPosition: Vec3): SurfaceIntersection? {
        val s = current ?: return null
        val plane = s.fit.plane.facing(ray.origin)
        val cos = abs(plane.normal dot ray.direction)
        if (cos < config.minCosIncidence) return null
        val t = plane.intersectDistance(ray, config.minCosIncidence) ?: return null
        val p = ray.pointAt(t)
        // The fit only describes the surface near its points; refuse far extrapolation.
        if (p.distanceTo(s.fit.centroid) > max(0.15, 0.15 * t)) return null
        val bias = runCatching { Uncertainty.planeBiasRel(s.fit, cameraPosition, p) }.getOrNull() ?: return null
        return SurfaceIntersection(p, plane.normal, t, bias)
    }

    private companion object {
        const val MIN_RANGE = 0.1
    }
}
