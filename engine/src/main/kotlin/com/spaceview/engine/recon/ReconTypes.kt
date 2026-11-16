package com.spaceview.engine.recon

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3

// Supporting types that ARCHITECTURE-v2 §7.1.4 names but does not define (OrientedBox, DepthFrame, YuvFrame,
// DepthNoise). Everything here is plain JVM so :ar can fill it and the JVM tests can synthesise it.

/**
 * The measured box (BOX mode, §7.1.3) as a rigid frame plus its size.
 *
 * Local frame: origin at the **centre of the base** (on the support plane), +X along [width], +Y up along [height],
 * +Z along [depth]. The box occupies `[-w/2, w/2] × [0, h] × [-d/2, d/2]`. [pose] maps local → parent.
 *
 * - For [VolumeSizing.spec] the parent is the ARCore world, so `pose` is the box's world pose.
 * - For the mesh functions ([MeshSegmenter.crop], [MeshExtent.estimate]) the parent is the **mesh's own frame**.
 *   Meshes from [MarchingCubes] are already in the box frame, so pass [local] (identity pose) or a trimmed copy.
 */
class OrientedBox(val pose: Pose, val width: Double, val height: Double, val depth: Double) {
    init {
        require(width.isFinite() && height.isFinite() && depth.isFinite()) { "box sides must be finite" }
        require(width > 0 && height > 0 && depth > 0) { "box sides must be positive" }
    }

    // parent → local: local = Rᵀ (p − t). Row-major Rᵀ, cached so contains() does not allocate.
    private val r = ReconMath.rotation(pose.rotation)
    private val tx = pose.translation.x
    private val ty = pose.translation.y
    private val tz = pose.translation.z

    /** True when parent-frame point ([x], [y], [z]) lies inside the box, with a [tolM] tolerance on every face. */
    fun contains(x: Double, y: Double, z: Double, tolM: Double = 1e-6): Boolean {
        val dx = x - tx; val dy = y - ty; val dz = z - tz
        val lx = r[0] * dx + r[3] * dy + r[6] * dz
        val ly = r[1] * dx + r[4] * dy + r[7] * dz
        val lz = r[2] * dx + r[5] * dy + r[8] * dz
        return lx >= -width / 2 - tolM && lx <= width / 2 + tolM &&
            ly >= -tolM && ly <= height + tolM &&
            lz >= -depth / 2 - tolM && lz <= depth / 2 + tolM
    }

    /** Parent-frame point to box-local coordinates, written to [out] (size ≥ 3). */
    fun toLocal(x: Double, y: Double, z: Double, out: DoubleArray) {
        val dx = x - tx; val dy = y - ty; val dz = z - tz
        out[0] = r[0] * dx + r[3] * dy + r[6] * dz
        out[1] = r[1] * dx + r[4] * dy + r[7] * dz
        out[2] = r[2] * dx + r[5] * dy + r[8] * dz
    }

    /** A copy with each local face moved inwards by the given trims (m); the Review crop sliders (§7.1.4). */
    fun trimmed(left: Double, right: Double, bottom: Double, top: Double, back: Double, front: Double): OrientedBox {
        val w = width - left - right
        val h = height - bottom - top
        val d = depth - back - front
        val c = pose.transformPoint(Vec3((left - right) / 2, bottom, (back - front) / 2))
        return OrientedBox(Pose(c, pose.rotation), w, h, d)
    }

    override fun toString(): String = "OrientedBox($pose, w=$width, h=$height, d=$depth)"

    companion object {
        /** The box in its own frame (identity pose): the frame [MarchingCubes] meshes are in. */
        fun local(width: Double, height: Double, depth: Double) = OrientedBox(Pose.IDENTITY, width, height, depth)
    }
}

/**
 * One depth image with its camera, as copied out by `DepthFrameTap` (§7.1.3) into a pooled slot. The arrays are
 * allocated once and refilled; [intrinsics], [cameraToWorld] and [timestampNs] are updated per frame.
 *
 * Conventions (the same as `Ray.fromPixel`):
 * - [depthMm]: unsigned 16-bit **millimetres along the optical axis** (z-depth), row-major, 0 = no data. This is
 *   ARCore's `acquireRawDepthImage16Bits()` / `acquireDepthImage16Bits()` layout.
 * - [confidence]: unsigned 0–255 per pixel (ARCore raw-depth confidence). For smoothed depth, which has no
 *   confidence image, fill 255 and pass a lower `sampleWeight` to [TsdfVolume.integrate].
 * - [intrinsics]: pinhole intrinsics **at the depth resolution**, in the depth image's (sensor) orientation.
 * - [cameraToWorld]: the physical camera pose (`Camera.getPose()`, **not** the display-oriented pose). The camera
 *   looks along local −Z, +Y is up in the image, and image rows grow downwards.
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray = ShortArray(width * height),
    val confidence: ByteArray = ByteArray(width * height),
) {
    init {
        require(width > 0 && height > 0) { "depth image must be non-empty" }
        require(depthMm.size == width * height && confidence.size == width * height) { "array sizes must be width × height" }
    }

    var intrinsics: CameraIntrinsics = CameraIntrinsics(width.toDouble(), width.toDouble(), width / 2.0, height / 2.0)
    var cameraToWorld: Pose = Pose.IDENTITY
    var timestampNs: Long = 0L
}

/**
 * A decimated colour image in planar YUV 4:2:0 (full-range BT.601, as from the camera's YUV_420_888), covering
 * the **same field of view** as the depth image (ARCore aligns depth to the CPU image), typically at 2× the depth
 * resolution (§7.1.3). [y] is `width × height`; [u] and [v] are `(width/2) × (height/2)`.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray = ByteArray(width * height),
    val u: ByteArray = ByteArray((width / 2) * (height / 2)),
    val v: ByteArray = ByteArray((width / 2) * (height / 2)),
) {
    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) { "YUV 4:2:0 needs even sides ≥ 2" }
        require(y.size == width * height) { "y must be width × height" }
        require(u.size == (width / 2) * (height / 2) && v.size == u.size) { "u and v must be (width/2) × (height/2)" }
    }
}

/**
 * Where the depth came from (§7.1.3 depth mode), with the per-source constants. All values are engineering
 * estimates from docs/research/3d-capture.md §7.5–7.6 and **must be tuned** on the bench (§7.1.10).
 *
 * @property sigmaAt1mM 1σ single-frame depth noise at 1 m; it grows with range² (research §7.4).
 * @property inflationPerSideM silhouette inflation per side of fused depth (research §7.6: 1–3 cm for
 *   depth-from-motion). Added to the mesh ± (§7.1.5), never "corrected".
 * @property sampleWeight the source factor passed to [TsdfVolume.integrate] (smoothed depth counts half).
 */
enum class DepthSource(val sigmaAt1mM: Double, val inflationPerSideM: Double, val sampleWeight: Float) {
    /** `RAW_DEPTH_ONLY` depth-from-motion with confidence. */
    RAW(0.015, 0.010, 1.0f),

    /** `AUTOMATIC` smoothed depth (no confidence; edges smeared). */
    SMOOTHED(0.020, 0.020, 0.5f),

    /** Hardware ToF depth. */
    HARDWARE(0.005, 0.005, 1.0f),
}

/**
 * The noise terms [MeshExtent.estimate] needs for the mesh ± (§7.1.5). Build it with [from] after fusion.
 *
 * @property meanRangeM r̄, the mean camera range of the fused samples.
 * @property observations typical number of fused samples per surface voxel (n before the n_eff cap).
 * @property voxelM the volume's voxel size v.
 * @property scaleSigmaRel 1σ relative scale error (the tier's VIO/depth scale term from [UncertaintyModel]).
 * @property absFloor95M the tier's absolute ±95 floor; no extent ± is ever smaller.
 */
class DepthNoise(
    val source: DepthSource,
    val meanRangeM: Double,
    val observations: Int,
    val voxelM: Double,
    val scaleSigmaRel: Double,
    val absFloor95M: Double,
) {
    init {
        require(meanRangeM.isFinite() && meanRangeM >= 0) { "meanRangeM must be finite and ≥ 0" }
        require(observations >= 0) { "observations must be ≥ 0" }
        require(voxelM.isFinite() && voxelM > 0) { "voxelM must be positive" }
        require(scaleSigmaRel.isFinite() && scaleSigmaRel >= 0) { "scaleSigmaRel must be ≥ 0" }
        require(absFloor95M.isFinite() && absFloor95M >= 0) { "absFloor95M must be ≥ 0" }
    }

    /** σ_depth(r̄) = σ(1 m) · r̄². */
    val sigmaDepthM: Double get() = source.sigmaAt1mM * meanRangeM * meanRangeM

    companion object {
        /** Noise terms of a finished fusion in [volume], from depth [source], scaled by the device [model]. */
        fun from(volume: TsdfVolume, source: DepthSource, model: UncertaintyModel): DepthNoise = DepthNoise(
            source, volume.meanRangeM(), volume.surfaceObservations(), volume.spec.voxelM,
            model.effectiveScaleSigmaRel, model.absFloor95,
        )
    }
}

/** Small allocation-conscious helpers shared by the recon code. */
internal object ReconMath {
    /**
     * Row-major 3×3 rotation matrix of [q] (v' = R v), the same rotation as [Quaternion.rotate]. Standard
     * quaternion-to-matrix identity (e.g. Shoemake, "Animating rotation with quaternion curves", 1985).
     */
    fun rotation(q: Quaternion, out: DoubleArray = DoubleArray(9)): DoubleArray {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y - z * w); out[2] = 2 * (x * z + y * w)
        out[3] = 2 * (x * y + z * w); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z - x * w)
        out[6] = 2 * (x * z - y * w); out[7] = 2 * (y * z + x * w); out[8] = 1 - 2 * (x * x + y * y)
        return out
    }

    /**
     * Eigen-decomposition of the symmetric 3×3 matrix [[a00,a01,a02],[a01,a11,a12],[a02,a12,a22]] by cyclic Jacobi
     * rotations (Golub & Van Loan, "Matrix Computations", §8.5), **allocation-free**: [m] (9) is overwritten with
     * the diagonalised matrix, so eigenvalue i is `m[4 * i]`, and [vec] (9) receives the eigenvectors as columns
     * (eigenvector i = `vec[i], vec[3 + i], vec[6 + i]`). Unordered. The decimator calls it once per cell.
     */
    fun symmetricEigen(a00: Double, a01: Double, a02: Double, a11: Double, a12: Double, a22: Double, m: DoubleArray, vec: DoubleArray) {
        m[0] = a00; m[1] = a01; m[2] = a02; m[3] = a01; m[4] = a11; m[5] = a12; m[6] = a02; m[7] = a12; m[8] = a22
        for (i in 0 until 9) vec[i] = if (i % 4 == 0) 1.0 else 0.0
        for (sweep in 0 until 50) {
            val off = m[1] * m[1] + m[2] * m[2] + m[5] * m[5]
            val diag = m[0] * m[0] + m[4] * m[4] + m[8] * m[8]
            if (off <= 1e-30 * diag || off == 0.0) return
            for (pq in 0 until 3) {
                val p = if (pq == 2) 1 else 0
                val q = if (pq == 0) 1 else 2
                val apq = m[3 * p + q]
                if (apq == 0.0) continue
                val theta = (m[3 * q + q] - m[3 * p + p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (Math.abs(theta) + Math.sqrt(theta * theta + 1))
                val c = 1 / Math.sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until 3) { // M = M·J, V = V·J
                    val mkp = m[3 * k + p]; val mkq = m[3 * k + q]
                    m[3 * k + p] = c * mkp - s * mkq; m[3 * k + q] = s * mkp + c * mkq
                    val vkp = vec[3 * k + p]; val vkq = vec[3 * k + q]
                    vec[3 * k + p] = c * vkp - s * vkq; vec[3 * k + q] = s * vkp + c * vkq
                }
                for (k in 0 until 3) { // M = Jᵀ·M
                    val mpk = m[3 * p + k]; val mqk = m[3 * q + k]
                    m[3 * p + k] = c * mpk - s * mqk; m[3 * q + k] = s * mpk + c * mqk
                }
            }
        }
    }

    /**
     * Sorts `a[from, to)` ascending **without allocating** (java.util.Arrays.sort(long[]) allocates a merge buffer
     * when its input has sorted runs, as the decimator's spatially coherent keys do). Introsort (Musser, "Introspective
     * sorting and selection algorithms", 1997): median-of-three quicksort, insertion sort below 24 elements, and
     * heapsort past 2·log2(n) levels, so it is O(n log n) worst case. Recursion is on the smaller part only.
     */
    fun sortLongs(a: LongArray, from: Int, to: Int) {
        var depth = 0
        var n = to - from
        while (n > 1) { depth += 2; n = n shr 1 }
        introsort(a, from, to, depth)
    }

    internal fun introsort(a: LongArray, from: Int, to: Int, depthLimit: Int) {
        var lo = from
        var hi = to
        var depth = depthLimit
        while (hi - lo > 24) {
            if (depth-- == 0) { heapsort(a, lo, hi); return }
            val mid = (lo + hi) ushr 1
            // Median of three into a[mid], then Hoare partition around its value.
            if (a[mid] < a[lo]) swap(a, mid, lo)
            if (a[hi - 1] < a[lo]) swap(a, hi - 1, lo)
            if (a[hi - 1] < a[mid]) swap(a, hi - 1, mid)
            val pivot = a[mid]
            var i = lo
            var j = hi - 1
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) { swap(a, i, j); i++; j-- }
            }
            // [lo, j] ≤ pivot ≤ [i, hi): recurse into the smaller part, loop on the larger.
            if (j + 1 - lo < hi - i) { introsort(a, lo, j + 1, depth); lo = i } else { introsort(a, i, hi, depth); hi = j + 1 }
        }
        for (k in lo + 1 until hi) { // insertion sort
            val x = a[k]
            var m = k - 1
            while (m >= lo && a[m] > x) { a[m + 1] = a[m]; m-- }
            a[m + 1] = x
        }
    }

    private fun heapsort(a: LongArray, lo: Int, hi: Int) {
        val n = hi - lo
        for (i in n / 2 - 1 downTo 0) siftDown(a, lo, i, n)
        for (end in n - 1 downTo 1) { swap(a, lo, lo + end); siftDown(a, lo, 0, end) }
    }

    private fun siftDown(a: LongArray, lo: Int, start: Int, n: Int) {
        var root = start
        while (true) {
            var child = 2 * root + 1
            if (child >= n) return
            if (child + 1 < n && a[lo + child + 1] > a[lo + child]) child++
            if (a[lo + root] >= a[lo + child]) return
            swap(a, lo + root, lo + child)
            root = child
        }
    }

    private fun swap(a: LongArray, i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }
}
