package com.spaceview.engine.recon

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One analytic primitive in world space. [hit] returns the ray parameter t (> 0) or +∞. */
sealed class Shape(val rgb: Int) {
    abstract fun hit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double

    class Sphere(val c: Vec3, val r: Double, rgb: Int) : Shape(rgb) {
        override fun hit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
            val px = ox - c.x; val py = oy - c.y; val pz = oz - c.z
            val a = dx * dx + dy * dy + dz * dz
            val b = px * dx + py * dy + pz * dz
            val cc = px * px + py * py + pz * pz - r * r
            val disc = b * b - a * cc
            if (disc < 0) return Double.POSITIVE_INFINITY
            val t = (-b - sqrt(disc)) / a
            return if (t > 0) t else Double.POSITIVE_INFINITY
        }
    }

    /** Box in [OrientedBox] convention (base-centred), placed by [box].pose. */
    class Box(val box: OrientedBox, rgb: Int) : Shape(rgb) {
        private val inv = box.pose.inverse()
        override fun hit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
            val o = inv.transformPoint(Vec3(ox, oy, oz)); val d = inv.rotateVector(Vec3(dx, dy, dz))
            var t0 = 0.0; var t1 = Double.POSITIVE_INFINITY
            val lo = doubleArrayOf(-box.width / 2, 0.0, -box.depth / 2)
            val hi = doubleArrayOf(box.width / 2, box.height, box.depth / 2)
            val oo = doubleArrayOf(o.x, o.y, o.z); val dd = doubleArrayOf(d.x, d.y, d.z)
            for (a in 0 until 3) {
                if (abs(dd[a]) < 1e-15) { if (oo[a] < lo[a] || oo[a] > hi[a]) return Double.POSITIVE_INFINITY; continue }
                var ta = (lo[a] - oo[a]) / dd[a]; var tb = (hi[a] - oo[a]) / dd[a]
                if (ta > tb) { val s = ta; ta = tb; tb = s }
                t0 = max(t0, ta); t1 = min(t1, tb)
                if (t0 > t1) return Double.POSITIVE_INFINITY
            }
            return if (t0 > 0) t0 else Double.POSITIVE_INFINITY
        }
    }

    /** Horizontal floor y = [y]. */
    class Floor(val y: Double, rgb: Int) : Shape(rgb) {
        override fun hit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
            if (dy >= 0) return Double.POSITIVE_INFINITY
            val t = (y - oy) / dy
            return if (t > 0) t else Double.POSITIVE_INFINITY
        }
    }
}

/** Noise model of the §7.1.11 synthetic scenes. */
class DepthNoiseSim(
    val relSigma: Double = 0.0,       // Gaussian depth noise, fraction of range (1% in the spec)
    val holeFraction: Double = 0.0,   // pixels dropped (depth 0, confidence 0)
    val flyingPixels: Boolean = false, // pixels at depth edges take a value between foreground and background
    val lowConfFraction: Double = 0.0, // pixels given a confidence below the 128 gate
    val fattenPx: Int = 0,             // foreground dilated over the background by this many pixels (stereo fattening)
)

/**
 * Ray-casts analytic scenes into 160×120 depth (+ confidence) and a 320×240 YUV colour image (§7.1.11
 * `SyntheticDepthRenderer`). Deterministic for a given [Random].
 */
object SyntheticRenderer {
    const val W = 160
    const val H = 120
    val K = CameraIntrinsics(125.0, 125.0, 79.5, 59.5)

    fun render(
        scene: List<Shape>, pose: Pose, noise: DepthNoiseSim = DepthNoiseSim(), rng: Random = Random(0),
        frame: DepthFrame = DepthFrame(W, H), colour: YuvFrame? = YuvFrame(2 * W, 2 * H),
    ): Pair<DepthFrame, YuvFrame?> {
        val w = frame.width; val h = frame.height
        val k = CameraIntrinsics(K.fx * w / W, K.fy * h / H, (w - 1) / 2.0, (h - 1) / 2.0)
        frame.intrinsics = k
        frame.cameraToWorld = pose
        val o = pose.translation
        val depth = DoubleArray(w * h)
        val col = IntArray(w * h)
        for (v in 0 until h) for (u in 0 until w) {
            val dir = pose.rotateVector(Vec3((u - k.cx) / k.fx, -(v - k.cy) / k.fy, -1.0))
            var best = Double.POSITIVE_INFINITY; var c = 0
            for (s in scene) {
                val t = s.hit(o.x, o.y, o.z, dir.x, dir.y, dir.z)
                if (t < best) { best = t; c = s.rgb }
            }
            depth[v * w + u] = best // t is z-depth because the camera-frame direction has z = −1
            col[v * w + u] = c
        }
        val out = depth.copyOf()
        if (noise.fattenPx > 0) {
            // Silhouette inflation of real depth (research §7.6): a background pixel within fattenPx of a nearer
            // surface (≥ 5 cm nearer) takes that surface's depth.
            val k = noise.fattenPx
            for (v in 0 until h) for (u in 0 until w) {
                var best = depth[v * w + u]
                for (dv in -k..k) for (du in -k..k) {
                    val vv = v + dv; val uu = u + du
                    if (vv < 0 || vv >= h || uu < 0 || uu >= w) continue
                    val n = depth[vv * w + uu]
                    if (n < best - 0.05) best = n
                }
                out[v * w + u] = best
            }
        }
        if (noise.flyingPixels) {
            for (v in 1 until h - 1) for (u in 1 until w - 1) {
                val i = v * w + u
                val d = depth[i]
                if (!d.isFinite()) continue
                val nb = doubleArrayOf(depth[i - 1], depth[i + 1], depth[i - w], depth[i + w]).maxOrNull()!!
                if (nb - d > 0.05 && rng.nextDouble() < 0.5) {
                    val far = if (nb.isFinite()) nb else d + 0.5
                    out[i] = d + (far - d) * rng.nextDouble()
                }
            }
        }
        for (i in 0 until w * h) {
            var d = out[i]
            var conf = 255
            if (noise.relSigma > 0 && d.isFinite()) d *= 1 + noise.relSigma * rng.nextGaussian()
            if (noise.lowConfFraction > 0 && rng.nextDouble() < noise.lowConfFraction) conf = 60
            else if (noise.relSigma > 0) conf = 140 + rng.nextInt(116)
            if (noise.holeFraction > 0 && rng.nextDouble() < noise.holeFraction) { d = 0.0; conf = 0 }
            val mm = if (d.isFinite() && d > 0) (d * 1000).roundToInt().coerceIn(0, 65535) else 0
            frame.depthMm[i] = mm.toShort()
            frame.confidence[i] = (if (mm == 0) 0 else conf).toByte()
        }
        if (colour != null) {
            for (v in 0 until colour.height) for (u in 0 until colour.width) {
                val c = col[(v * h / colour.height) * w + (u * w / colour.width)]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                colour.y[v * colour.width + u] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte()
                if (u % 2 == 0 && v % 2 == 0) {
                    val ci = (v / 2) * (colour.width / 2) + u / 2
                    colour.u[ci] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte()
                    colour.v[ci] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return frame to colour
    }

    /** Camera at [eye] looking at [target] (ARCore convention: looks along local −Z, +Y up). */
    fun lookAt(eye: Vec3, target: Vec3): Pose {
        val back = (eye - target).normalized()
        val right = (Vec3.UP cross back).normalized()
        val up = back cross right
        return Pose(eye, quaternionFromColumns(right, up, back))
    }

    /** Quaternion of the rotation matrix with columns [x], [y], [z] (Shepperd's method, 1978). */
    fun quaternionFromColumns(x: Vec3, y: Vec3, z: Vec3): Quaternion {
        val m00 = x.x; val m10 = x.y; val m20 = x.z
        val m01 = y.x; val m11 = y.y; val m21 = y.z
        val m02 = z.x; val m12 = z.y; val m22 = z.z
        val tr = m00 + m11 + m22
        return when {
            tr > 0 -> { val s = sqrt(tr + 1) * 2; Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, s / 4) }
            m00 > m11 && m00 > m22 -> { val s = sqrt(1 + m00 - m11 - m22) * 2; Quaternion(s / 4, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s) }
            m11 > m22 -> { val s = sqrt(1 + m11 - m00 - m22) * 2; Quaternion((m01 + m10) / s, s / 4, (m12 + m21) / s, (m02 - m20) / s) }
            else -> { val s = sqrt(1 + m22 - m00 - m11) * 2; Quaternion((m02 + m20) / s, (m12 + m21) / s, s / 4, (m10 - m01) / s) }
        }
    }

    /**
     * Cameras on rings around [target]: one per (elevation, azimuth) at [distance]. Azimuth 0 looks from +Z.
     */
    fun ring(target: Vec3, distance: Double, elevationsDeg: List<Double>, azimuthsDeg: List<Double>): List<Pose> =
        elevationsDeg.flatMap { el ->
            azimuthsDeg.map { az ->
                val e = Math.toRadians(el); val a = Math.toRadians(az)
                val eye = target + Vec3(distance * cos(e) * sin(a), distance * sin(e), distance * cos(e) * cos(a))
                lookAt(eye, target)
            }
        }

    fun azimuths(n: Int, from: Double = 0.0, to: Double = 360.0): List<Double> =
        if (to - from >= 360.0) List(n) { from + it * 360.0 / n } else List(n) { from + it * (to - from) / (n - 1) }

    /** Fuses [poses] of [scene] into [volume]. */
    fun fuse(volume: TsdfVolume, scene: List<Shape>, poses: List<Pose>, noise: DepthNoiseSim = DepthNoiseSim(), seed: Long = 1, colour: Boolean = true) {
        val rng = Random(seed)
        val frame = DepthFrame(W, H)
        val yuv = if (colour) YuvFrame(2 * W, 2 * H) else null
        for (p in poses) {
            render(scene, p, noise, rng, frame, yuv)
            volume.integrate(frame, yuv, 1.0f)
        }
    }
}

/** Mesh measurements used by the tests. */
object MeshMetrics {
    /** Signed volume (positive when triangles are counter-clockwise seen from outside). */
    fun signedVolume(m: TriMesh): Double {
        var s = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val a = 3 * m.indices[3 * t]; val b = 3 * m.indices[3 * t + 1]; val c = 3 * m.indices[3 * t + 2]
            s += p[a].toDouble() * (p[b + 1].toDouble() * p[c + 2] - p[b + 2].toDouble() * p[c + 1]) -
                p[a + 1].toDouble() * (p[b].toDouble() * p[c + 2] - p[b + 2].toDouble() * p[c]) +
                p[a + 2].toDouble() * (p[b].toDouble() * p[c + 1] - p[b + 1].toDouble() * p[c])
        }
        return s / 6
    }

    /** Undirected edge → number of incident triangles. */
    fun edgeUse(m: TriMesh): Map<Long, Int> {
        val e = HashMap<Long, Int>()
        for (t in 0 until m.triangleCount) for (c in 0 until 3) {
            val a = m.indices[3 * t + c]; val b = m.indices[3 * t + (c + 1) % 3]
            val key = min(a, b).toLong() shl 32 or max(a, b).toLong()
            e[key] = (e[key] ?: 0) + 1
        }
        return e
    }

    /** True when every directed edge appears once and its reverse once (closed, consistently oriented). */
    fun isClosedOriented(m: TriMesh): Boolean {
        val d = HashMap<Long, Int>()
        for (t in 0 until m.triangleCount) for (c in 0 until 3) {
            val a = m.indices[3 * t + c].toLong(); val b = m.indices[3 * t + (c + 1) % 3].toLong()
            val key = a shl 32 or b
            d[key] = (d[key] ?: 0) + 1
        }
        return d.all { (k, n) -> n == 1 && d[(k and 0xFFFFFFFFL) shl 32 or (k ushr 32)] == 1 }
    }

    /** V − E + F over referenced vertices. */
    fun euler(m: TriMesh): Int {
        val used = HashSet<Int>(); m.indices.forEach { used += it }
        return used.size - edgeUse(m).size + m.triangleCount
    }

    fun boundaryEdges(m: TriMesh): Int = edgeUse(m).values.count { it == 1 }

    fun vertex(m: TriMesh, i: Int) = Vec3(m.positions[3 * i].toDouble(), m.positions[3 * i + 1].toDouble(), m.positions[3 * i + 2].toDouble())

    /** Linear least-squares sphere fit (x² + y² + z² = 2ax + 2by + 2cz + k): returns centre and radius. */
    fun fitSphere(m: TriMesh): Pair<Vec3, Double> {
        val a = Array(4) { DoubleArray(4) }; val r = DoubleArray(4)
        for (v in 0 until m.vertexCount) {
            val x = m.positions[3 * v].toDouble(); val y = m.positions[3 * v + 1].toDouble(); val z = m.positions[3 * v + 2].toDouble()
            val row = doubleArrayOf(2 * x, 2 * y, 2 * z, 1.0); val rhs = x * x + y * y + z * z
            for (i in 0 until 4) { r[i] += row[i] * rhs; for (j in 0 until 4) a[i][j] += row[i] * row[j] }
        }
        // Gaussian elimination with partial pivoting.
        for (c in 0 until 4) {
            val piv = (c until 4).maxByOrNull { abs(a[it][c]) }!!
            val t = a[c]; a[c] = a[piv]; a[piv] = t; val tr = r[c]; r[c] = r[piv]; r[piv] = tr
            for (i in c + 1 until 4) { val f = a[i][c] / a[c][c]; for (j in c until 4) a[i][j] -= f * a[c][j]; r[i] -= f * r[c] }
        }
        val s = DoubleArray(4)
        for (i in 3 downTo 0) { var acc = r[i]; for (j in i + 1 until 4) acc -= a[i][j] * s[j]; s[i] = acc / a[i][i] }
        val c = Vec3(s[0], s[1], s[2])
        return c to sqrt(s[3] + c.lengthSquared)
    }
}
