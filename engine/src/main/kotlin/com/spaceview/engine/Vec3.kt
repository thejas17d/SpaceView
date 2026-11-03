package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Immutable 3D vector in metres (world space unless stated otherwise).
 *
 * World convention follows ARCore: right-handed, **+Y is up** (gravity aligned), units are metres.
 * Kept as a tiny final class with double fields; all operations return new instances, no hidden state.
 */
class Vec3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    /** Dot product. */
    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    /** Cross product (right-handed). */
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** Squared Euclidean length. */
    val lengthSquared: Double get() = x * x + y * y + z * z

    /** Euclidean length. */
    val length: Double get() = sqrt(lengthSquared)

    /** Unit vector in the same direction. Throws if the vector is (numerically) zero. */
    fun normalized(): Vec3 {
        val l = length
        require(l > 1e-15) { "Cannot normalise a zero-length vector" }
        return Vec3(x / l, y / l, z / l)
    }

    /** Euclidean distance to [o]. */
    fun distanceTo(o: Vec3): Double {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** True when every component is finite (not NaN / infinite). */
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    /** Component-wise approximate equality. */
    fun approxEquals(o: Vec3, eps: Double = 1e-9): Boolean =
        abs(x - o.x) <= eps && abs(y - o.y) <= eps && abs(z - o.z) <= eps

    override fun equals(other: Any?): Boolean =
        other is Vec3 && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int = (x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()
    override fun toString(): String = "Vec3($x, $y, $z)"

    companion object {
        @JvmField val ZERO = Vec3(0.0, 0.0, 0.0)
        @JvmField val UNIT_X = Vec3(1.0, 0.0, 0.0)
        /** World up (ARCore convention). */
        @JvmField val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        @JvmField val UNIT_Z = Vec3(0.0, 0.0, 1.0)
        @JvmField val UP = UNIT_Y

        /** Linear interpolation between [a] and [b]. */
        fun lerp(a: Vec3, b: Vec3, t: Double) = Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    }
}

/** Immutable 2D point/vector, used for image (pixel) coordinates. */
class Vec2(@JvmField val x: Double, @JvmField val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)

    /** Euclidean length. */
    val length: Double get() = sqrt(x * x + y * y)

    /** Euclidean distance to [o]. */
    fun distanceTo(o: Vec2): Double = (this - o).length

    /** 2D cross product (z of the 3D cross product). */
    infix fun cross(o: Vec2): Double = x * o.y - y * o.x

    override fun equals(other: Any?): Boolean = other is Vec2 && x == other.x && y == other.y
    override fun hashCode(): Int = x.hashCode() * 31 + y.hashCode()
    override fun toString(): String = "Vec2($x, $y)"
}

/**
 * Unit quaternion (x, y, z, w) representing a rotation. Same component order as ARCore's `Pose`.
 * The constructor normalises its input so rotations stay orthonormal.
 */
class Quaternion(x: Double, y: Double, z: Double, w: Double) {
    @JvmField val x: Double
    @JvmField val y: Double
    @JvmField val z: Double
    @JvmField val w: Double

    init {
        val n = sqrt(x * x + y * y + z * z + w * w)
        require(n > 1e-12 && n.isFinite()) { "Invalid quaternion" }
        this.x = x / n; this.y = y / n; this.z = z / n; this.w = w / n
    }

    /** Hamilton product: applying the result rotates by [o] first, then by this. */
    operator fun times(o: Quaternion) = Quaternion(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** Inverse (= conjugate for a unit quaternion). */
    fun inverse() = Quaternion(-x, -y, -z, w)

    /** Rotates vector [v]. */
    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q×v) + 2 q×(q×v), q = (x,y,z)
        val tx = 2.0 * (y * v.z - z * v.y)
        val ty = 2.0 * (z * v.x - x * v.z)
        val tz = 2.0 * (x * v.y - y * v.x)
        return Vec3(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    override fun toString(): String = "Quaternion($x, $y, $z, $w)"

    companion object {
        @JvmField val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)

        /** Rotation of [angleRad] radians about [axis] (any non-zero length). */
        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quaternion {
            val a = axis.normalized()
            val s = kotlin.math.sin(angleRad / 2)
            return Quaternion(a.x * s, a.y * s, a.z * s, kotlin.math.cos(angleRad / 2))
        }
    }
}

/**
 * Rigid transform (rotation then translation), mirroring ARCore's `Pose`:
 * `world = rotation.rotate(local) + translation`.
 */
class Pose(@JvmField val translation: Vec3, @JvmField val rotation: Quaternion = Quaternion.IDENTITY) {

    /** Transforms a point from this pose's local frame to the parent (world) frame. */
    fun transformPoint(p: Vec3): Vec3 = rotation.rotate(p) + translation

    /** Rotates a direction from local to world (no translation). */
    fun rotateVector(v: Vec3): Vec3 = rotation.rotate(v)

    /** Inverse transform. */
    fun inverse(): Pose {
        val inv = rotation.inverse()
        return Pose(-inv.rotate(translation), inv)
    }

    /** Composition: `(this * o).transformPoint(p) == this.transformPoint(o.transformPoint(p))`. */
    operator fun times(o: Pose): Pose = Pose(transformPoint(o.translation), rotation * o.rotation)

    /** Camera viewing direction in world space (ARCore cameras look down their local −Z axis). */
    val forward: Vec3 get() = rotation.rotate(Vec3(0.0, 0.0, -1.0))

    override fun toString(): String = "Pose($translation, $rotation)"

    companion object {
        @JvmField val IDENTITY = Pose(Vec3.ZERO, Quaternion.IDENTITY)
    }
}

/**
 * Symmetric 3×3 covariance matrix (m²). Used to carry per-endpoint uncertainty so it can be
 * projected onto the exact direction of a measurement instead of using a crude scalar.
 */
class Cov3(
    @JvmField val xx: Double, @JvmField val xy: Double, @JvmField val xz: Double,
    @JvmField val yy: Double, @JvmField val yz: Double, @JvmField val zz: Double,
) {
    operator fun plus(o: Cov3) = Cov3(xx + o.xx, xy + o.xy, xz + o.xz, yy + o.yy, yz + o.yz, zz + o.zz)
    operator fun times(s: Double) = Cov3(xx * s, xy * s, xz * s, yy * s, yz * s, zz * s)

    /** Variance along unit direction [u]: uᵀ C u. */
    fun along(u: Vec3): Double =
        u.x * u.x * xx + u.y * u.y * yy + u.z * u.z * zz +
            2.0 * (u.x * u.y * xy + u.x * u.z * xz + u.y * u.z * yz)

    /** Trace (total variance). */
    val trace: Double get() = xx + yy + zz

    /** C·v. */
    fun multiply(v: Vec3) = Vec3(
        xx * v.x + xy * v.y + xz * v.z,
        xy * v.x + yy * v.y + yz * v.z,
        xz * v.x + yz * v.y + zz * v.z,
    )

    /**
     * Covariance after orthogonal projection onto the plane with unit normal [n]:
     * P C P with P = I − n nᵀ. Snapping a point onto a fitted plane removes its normal-direction noise.
     */
    fun projectedOntoPlane(n: Vec3): Cov3 {
        // Columns of P
        val p0 = Vec3(1 - n.x * n.x, -n.x * n.y, -n.x * n.z)
        val p1 = Vec3(-n.y * n.x, 1 - n.y * n.y, -n.y * n.z)
        val p2 = Vec3(-n.z * n.x, -n.z * n.y, 1 - n.z * n.z)
        val c0 = multiply(p0); val c1 = multiply(p1); val c2 = multiply(p2) // C P columns
        // (P C P)_ij = p_i · (C p_j)
        return Cov3(p0 dot c0, p0 dot c1, p0 dot c2, p1 dot c1, p1 dot c2, p2 dot c2)
    }

    override fun toString(): String = "Cov3(xx=$xx, xy=$xy, xz=$xz, yy=$yy, yz=$yz, zz=$zz)"

    companion object {
        @JvmField val ZERO = Cov3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        /** Isotropic covariance with standard deviation [sigma] (m) on every axis. */
        fun isotropic(sigma: Double): Cov3 {
            val v = sigma * sigma
            return Cov3(v, 0.0, 0.0, v, 0.0, v)
        }
    }
}

/**
 * A 3D point with its covariance (uncertainty of the point estimate, not of individual samples).
 */
class UncertainPoint(@JvmField val position: Vec3, @JvmField val covariance: Cov3 = Cov3.ZERO) {

    /** Standard deviation of this point along unit direction [u] (m). */
    fun sigmaAlong(u: Vec3): Double = sqrt(covariance.along(u).coerceAtLeast(0.0))

    /**
     * Adds an independent isotropic error of [sigma] metres, e.g. tap/edge-snap localisation
     * (≈ pixels × range / focalPx, see [Uncertainty.pixelLocalisationSigma]).
     */
    fun withExtraSigma(sigma: Double): UncertainPoint = UncertainPoint(position, covariance + Cov3.isotropic(sigma))

    override fun toString(): String = "UncertainPoint($position, $covariance)"

    companion object {
        /** Point with an isotropic standard deviation of [sigma] metres. */
        fun of(position: Vec3, sigma: Double = 0.0) = UncertainPoint(position, Cov3.isotropic(sigma))
    }
}
