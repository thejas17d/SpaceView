package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A ray `origin + t·direction`, t ≥ 0. [direction] is normalised on construction. */
class Ray(@JvmField val origin: Vec3, direction: Vec3) {
    @JvmField val direction: Vec3 = direction.normalized()

    /** Point at parameter [t] (metres along the ray). */
    fun pointAt(t: Double): Vec3 = origin + direction * t

    override fun toString(): String = "Ray($origin -> $direction)"

    companion object {
        /**
         * Ray through image pixel ([u], [v]) for a pinhole camera at [cameraPose] (ARCore convention:
         * camera looks along local −Z, +Y up, image v grows downwards).
         *
         * [intrinsics] and ([u], [v]) must be in the **same** image orientation. ARCore's
         * `getImageIntrinsics()` is in sensor (landscape) orientation while touch coordinates are in
         * display orientation: convert the touch point with `Frame.transformCoordinates2d` first.
         */
        fun fromPixel(cameraPose: Pose, intrinsics: CameraIntrinsics, u: Double, v: Double): Ray {
            val local = Vec3((u - intrinsics.cx) / intrinsics.fx, -(v - intrinsics.cy) / intrinsics.fy, -1.0)
            return Ray(cameraPose.translation, cameraPose.rotateVector(local))
        }
    }
}

/** Pinhole intrinsics in pixels (ARCore `CameraIntrinsics`: focal length and principal point). */
class CameraIntrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    init { require(fx > 0 && fy > 0) { "Focal lengths must be positive" } }
}

/**
 * Infinite plane `normal · p + d = 0` with a unit [normal].
 */
class Plane(normal: Vec3, @JvmField val d: Double) {
    @JvmField val normal: Vec3

    init {
        val l = normal.length
        require(l > 1e-12) { "Plane normal must be non-zero" }
        this.normal = normal / l
    }

    /** Closest point on the plane to the origin. */
    val point: Vec3 get() = normal * (-d)

    /** Signed distance of [p] from the plane (positive on the side the normal points to). */
    fun signedDistance(p: Vec3): Double = (normal dot p) + d

    /** Orthogonal projection of [p] onto the plane ("snap to plane"). */
    fun project(p: Vec3): Vec3 = p - normal * signedDistance(p)

    /** Snaps an uncertain point onto the plane; the normal component of its covariance is removed. */
    fun project(p: UncertainPoint): UncertainPoint =
        UncertainPoint(project(p.position), p.covariance.projectedOntoPlane(normal), p.includesLocalisation)

    /**
     * Ray–plane intersection. Returns null when the ray is (nearly) parallel to the plane
     * (|cos| < [minCosAngle], default ≈ 87° grazing limit) or the plane is behind the ray origin.
     * Grazing intersections amplify any plane error enormously, so they are refused rather than returned.
     */
    fun intersect(ray: Ray, minCosAngle: Double = 0.05): Vec3? {
        val t = intersectDistance(ray, minCosAngle) ?: return null
        return ray.pointAt(t)
    }

    /** Ray parameter (distance in metres) of the intersection, or null (see [intersect]). */
    fun intersectDistance(ray: Ray, minCosAngle: Double = 0.05): Double? {
        val denom = normal dot ray.direction
        if (abs(denom) < minCosAngle) return null
        val t = -signedDistance(ray.origin) / denom
        return if (t >= 0) t else null
    }

    /** Same plane with the normal flipped. */
    fun flipped(): Plane = Plane(-normal, -d)

    /** Same plane, normal oriented towards [viewpoint] (e.g. the camera), so "front" is the visible side. */
    fun facing(viewpoint: Vec3): Plane = if (signedDistance(viewpoint) >= 0) this else flipped()

    /** Angle between plane normal and world up, in degrees (0 = horizontal floor/table, 90 = wall). */
    fun tiltFromHorizontalDeg(up: Vec3 = Vec3.UP): Double {
        val c = abs(normal dot up.normalized()).coerceAtMost(1.0)
        return Math.toDegrees(kotlin.math.acos(c))
    }

    override fun toString(): String = "Plane(n=$normal, d=$d)"

    companion object {
        /** Plane through [point] with [normal]. */
        fun fromPointNormal(point: Vec3, normal: Vec3): Plane {
            val n = normal.normalized()
            return Plane(n, -(n dot point))
        }

        /** Plane through three points, or null if they are (nearly) collinear. */
        fun fromPoints(a: Vec3, b: Vec3, c: Vec3): Plane? {
            val n = (b - a) cross (c - a)
            val l = n.length
            val scale = max((b - a).lengthSquared, (c - a).lengthSquared)
            if (l <= 1e-9 * scale || l == 0.0) return null
            return fromPointNormal(a, n / l)
        }

        /**
         * Vertical wall plane through the floor–wall line [a]→[b] (UX "wall fallback": tap two points
         * where the wall meets the floor). Returns null if the points are closer than 1 cm horizontally.
         */
        fun verticalThroughLine(a: Vec3, b: Vec3, up: Vec3 = Vec3.UP): Plane? {
            val u = up.normalized()
            val along = (b - a) - u * ((b - a) dot u)
            if (along.length < 0.01) return null
            return fromPointNormal(a, (along cross u).normalized())
        }
    }
}

/**
 * Result of a plane fit.
 *
 * @property plane fitted plane (least squares on inliers).
 * @property rmsResidual RMS point-to-plane distance of inliers (m). Feeds the uncertainty model.
 * @property inlierCount points within the RANSAC threshold (all points for a plain least-squares fit).
 * @property inliers per-input-point inlier flags.
 * @property centroid mean of inliers (a good anchor point on the plane).
 * @property eigenvalues ascending eigenvalues λ₀ ≤ λ₁ ≤ λ₂ of the inliers' covariance (m²), or null when
 *   unknown. √λ₁ is the in-plane extent along the fit's weakest direction; it decides how well the
 *   normal is constrained (a 2 cm strip on a wall cannot fix the tilt about its long axis).
 */
class PlaneFit(
    val plane: Plane,
    val rmsResidual: Double,
    val inlierCount: Int,
    val inliers: BooleanArray,
    val centroid: Vec3,
    val eigenvalues: DoubleArray? = null,
) {
    /** Inlier share of the input points. */
    val inlierRatio: Double get() = if (inliers.isEmpty()) 0.0 else inlierCount.toDouble() / inliers.size

    /** In-plane RMS extent along the weakest in-plane direction, √λ₁ (m); +∞ when unknown. */
    val minorExtent: Double get() = eigenvalues?.let { sqrt(max(it[1], 0.0)) } ?: Double.POSITIVE_INFINITY

    /**
     * False for near-collinear point sets (strips, edges): the minor in-plane extent is below
     * [PlaneFitter.MIN_EXTENT_TO_RMS] × the RMS residual (equivalently λ₁/λ₀ < 25 for the default).
     * Such fits can be tilted by several degrees while reporting a small residual.
     */
    val isWellConditioned: Boolean get() = minorExtent >= PlaneFitter.MIN_EXTENT_TO_RMS * rmsResidual

    /**
     * 1σ angle (rad) of the normal about the fit's long in-plane axis: rms / (√λ₁ · √n). 0 when unknown
     * or exact. Propagates as an offset error proportional to the distance from [centroid].
     */
    val normalAngleSigma: Double
        get() {
            val e = minorExtent
            if (!e.isFinite() || rmsResidual == 0.0) return 0.0
            if (e <= 0.0) return Double.POSITIVE_INFINITY
            return rmsResidual / (e * sqrt(max(1, inlierCount).toDouble()))
        }

    /**
     * 1σ plane offset (m, along the normal) at [p]: the centroid offset SE combined with the tilt term
     * [normalAngleSigma] × in-plane distance of [p] from [centroid].
     */
    fun offsetSigmaAt(p: Vec3): Double {
        val d = plane.project(p) - plane.project(centroid)
        val tilt = normalAngleSigma * d.length
        val o = offsetStandardError
        return sqrt(o * o + tilt * tilt)
    }

    /**
     * Standard error of the plane offset along its normal (m): rms / √n. This is the random part only;
     * systematic depth bias is covered by the device-tier scale term in [UncertaintyModel].
     */
    val offsetStandardError: Double get() = rmsResidual / sqrt(max(1, inlierCount).toDouble())
}

/** RANSAC tunables. */
class RansacConfig(
    /** Inlier distance threshold (m). Depth / feature points on a wall are typically within ~1 cm. */
    val inlierThreshold: Double = 0.01,
    /** Hard cap on iterations. */
    val maxIterations: Int = 500,
    /** Desired probability of drawing at least one all-inlier sample (drives adaptive stopping). */
    val confidence: Double = 0.999,
    /** Minimum points that must agree for a fit to be returned. */
    val minInliers: Int = 10,
    /** Seed for the deterministic RNG (fixed so results are reproducible). */
    val seed: Long = 0x5EED,
    /** Return null instead of an ill-conditioned (strip-like) fit, see [PlaneFit.isWellConditioned]. */
    val rejectIllConditioned: Boolean = true,
) {
    init {
        require(inlierThreshold > 0 && maxIterations > 0 && minInliers >= 3)
        require(confidence > 0 && confidence < 1)
    }
}

/** Plane fitting: least squares (PCA) and RANSAC (MSAC scoring) + least-squares refinement. */
object PlaneFitter {

    /**
     * Total-least-squares plane through [points] (smallest-eigenvalue eigenvector of the covariance).
     * Returns null for fewer than 3 points or a degenerate (collinear) set. Near-collinear sets are
     * returned but flagged: check [PlaneFit.isWellConditioned].
     */
    fun leastSquares(points: List<Vec3>): PlaneFit? {
        val mask = BooleanArray(points.size) { true }
        return fitMasked(points, mask)
    }

    /**
     * Minimum ratio of the in-plane minor extent √λ₁ to the RMS residual for a well-conditioned fit
     * (5 ⇔ λ₁/λ₀ ≥ 25). Tunable from bench data.
     */
    const val MIN_EXTENT_TO_RMS = 5.0

    /**
     * Robust fit: RANSAC with MSAC scoring, then least-squares refit on inliers, iterated until the
     * inlier set stops changing (max 3 rounds). Survives a large share of outliers (tested at 30%+).
     * Ill-conditioned (strip-like) results are rejected when [RansacConfig.rejectIllConditioned].
     *
     * Cost is up to [RansacConfig.maxIterations] × N point tests plus small allocations: with 1–2k
     * depth points that is several milliseconds, so run it off the GL/render thread (e.g.
     * `Dispatchers.Default`), not per frame.
     */
    fun ransac(points: List<Vec3>, config: RansacConfig = RansacConfig()): PlaneFit? {
        val fit = ransacUnchecked(points, config) ?: return null
        return if (config.rejectIllConditioned && !fit.isWellConditioned) null else fit
    }

    private fun ransacUnchecked(points: List<Vec3>, config: RansacConfig): PlaneFit? {
        val n = points.size
        if (n < 3) return null
        val rng = java.util.Random(config.seed)
        val t = config.inlierThreshold
        val t2 = t * t

        var bestPlane: Plane? = null
        var bestCost = Double.MAX_VALUE
        var iterations = config.maxIterations
        var it = 0
        while (it < iterations) {
            it++
            val i0 = rng.nextInt(n)
            var i1 = rng.nextInt(n)
            var i2 = rng.nextInt(n)
            if (n >= 3) {
                var guard = 0
                while ((i1 == i0) && guard++ < 16) i1 = rng.nextInt(n)
                guard = 0
                while ((i2 == i0 || i2 == i1) && guard++ < 16) i2 = rng.nextInt(n)
            }
            if (i0 == i1 || i1 == i2 || i0 == i2) continue
            val candidate = Plane.fromPoints(points[i0], points[i1], points[i2]) ?: continue
            var cost = 0.0
            var inl = 0
            for (p in points) {
                val r = candidate.signedDistance(p)
                val r2 = r * r
                if (r2 < t2) { cost += r2; inl++ } else cost += t2
            }
            if (cost < bestCost) {
                bestCost = cost
                bestPlane = candidate
                // Adaptive iteration count from the current inlier ratio.
                val w = inl.toDouble() / n
                val w3 = w * w * w
                if (w3 >= 1.0) {
                    iterations = it
                } else if (w3 > 0) {
                    val needed = ceil(ln(1 - config.confidence) / ln(1 - w3)).toInt()
                    iterations = min(config.maxIterations, max(it, needed))
                }
            }
        }
        var plane = bestPlane ?: return null

        var mask = inlierMask(points, plane, t)
        if (mask.count { it } < config.minInliers) return null
        var fit: PlaneFit? = null
        repeat(3) {
            val f = fitMasked(points, mask) ?: return fit
            fit = f
            plane = f.plane
            val next = inlierMask(points, plane, t)
            if (next.count { it } < config.minInliers || next.contentEquals(mask)) return f
            mask = next
        }
        return fit
    }

    private fun inlierMask(points: List<Vec3>, plane: Plane, t: Double) =
        BooleanArray(points.size) { abs(plane.signedDistance(points[it])) < t }

    private fun fitMasked(points: List<Vec3>, mask: BooleanArray): PlaneFit? {
        var m = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in points.indices) if (mask[i]) { val p = points[i]; sx += p.x; sy += p.y; sz += p.z; m++ }
        if (m < 3) return null
        val c = Vec3(sx / m, sy / m, sz / m)
        var xx = 0.0; var xy = 0.0; var xz = 0.0; var yy = 0.0; var yz = 0.0; var zz = 0.0
        for (i in points.indices) if (mask[i]) {
            val dx = points[i].x - c.x; val dy = points[i].y - c.y; val dz = points[i].z - c.z
            xx += dx * dx; xy += dx * dy; xz += dx * dz; yy += dy * dy; yz += dy * dz; zz += dz * dz
        }
        val eig = SymmetricEigen3.decompose(xx / m, xy / m, xz / m, yy / m, yz / m, zz / m)
        // Degenerate if the middle eigenvalue (in-plane spread along the 2nd axis) is ~0: points are collinear.
        if (eig.values[1] <= 1e-12 * max(eig.values[2], 1e-30)) return null
        val normal = eig.vectors[0]
        val plane = Plane.fromPointNormal(c, normal)
        var ss = 0.0
        for (i in points.indices) if (mask[i]) { val r = plane.signedDistance(points[i]); ss += r * r }
        return PlaneFit(plane, sqrt(ss / m), m, mask.copyOf(), c, eig.values.copyOf())
    }
}

/**
 * Eigen-decomposition of a symmetric 3×3 matrix by cyclic Jacobi rotations (robust, ~10 sweeps max).
 * [values] are ascending; [vectors] are the matching unit eigenvectors.
 */
class SymmetricEigen3 private constructor(val values: DoubleArray, val vectors: Array<Vec3>) {
    companion object {
        /** Decomposes the symmetric matrix [[xx,xy,xz],[xy,yy,yz],[xz,yz,zz]]. */
        fun decompose(xx: Double, xy: Double, xz: Double, yy: Double, yz: Double, zz: Double): SymmetricEigen3 {
            val a = arrayOf(doubleArrayOf(xx, xy, xz), doubleArrayOf(xy, yy, yz), doubleArrayOf(xz, yz, zz))
            val v = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            for (sweep in 0 until 50) {
                val off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2]
                val diag = a[0][0] * a[0][0] + a[1][1] * a[1][1] + a[2][2] * a[2][2]
                if (off <= 1e-30 * max(diag, 1e-300) || off == 0.0) break
                for (p in 0 until 2) for (q in p + 1 until 3) {
                    val apq = a[p][q]
                    if (apq == 0.0) continue
                    val theta = (a[q][q] - a[p][p]) / (2 * apq)
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val s = t * c
                    for (k in 0 until 3) { // A = A·J
                        val akp = a[k][p]; val akq = a[k][q]
                        a[k][p] = c * akp - s * akq
                        a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until 3) { // A = Jᵀ·A
                        val apk = a[p][k]; val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk
                        a[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until 3) { // V = V·J
                        val vkp = v[k][p]; val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
            val order = (0 until 3).sortedBy { a[it][it] }
            val values = DoubleArray(3) { a[order[it]][order[it]] }
            val vectors = Array(3) { val j = order[it]; Vec3(v[0][j], v[1][j], v[2][j]).normalized() }
            return SymmetricEigen3(values, vectors)
        }
    }
}
