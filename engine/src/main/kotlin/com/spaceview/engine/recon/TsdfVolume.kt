package com.spaceview.engine.recon

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tunables of [TsdfVolume.integrate]. Defaults are the starting values of research §7.5 / ARCHITECTURE-v2 §7.1.3;
 * tune them on the bench (§7.1.10).
 *
 * @property minConfidence samples below this ARCore confidence (0–255) are dropped (tune 100–200).
 * @property minDepthM / [maxDepthM] usable depth range; error grows ~quadratically with distance.
 * @property weightUnit integer weight of a confidence-255, source-factor-1 sample at 1 m. The per-sample weight is
 *   `round(weightUnit · conf/255 · sampleWeight / z²)`, clamped to 1..[maxSampleWeight]. With 2, a typical sample
 *   weighs 1–2, so the extraction threshold of 3 drops one-frame noise (§7.1.4).
 * @property maxSampleWeight cap per sample (64), so later, better views can still win against the 127 ceiling.
 * @property colourMaxAngleDeg colour is fused only when the view ray is within this angle of the surface normal.
 * @property behindFraction the ray walk ends at d + behindFraction·μ, not d + μ. A full band behind the surface
 *   leaks through convex edges: rays that hit the top face near an edge carry "inside" values out through the side
 *   face and inflate the object (measured on the synthetic box: +12 mm on D with 1.0). The front band stays μ.
 */
class TsdfParams(
    val minConfidence: Int = 128,
    val minDepthM: Double = 0.35,
    val maxDepthM: Double = 2.0,
    val weightUnit: Double = 2.0,
    val maxSampleWeight: Int = 64,
    val colourMaxAngleDeg: Double = 60.0,
    val behindFraction: Double = 0.5,
) {
    init {
        require(minConfidence in 0..255) { "minConfidence must be 0..255" }
        require(minDepthM > 0 && maxDepthM > minDepthM && maxDepthM.isFinite()) { "need 0 < minDepthM < maxDepthM" }
        require(weightUnit.isFinite() && weightUnit > 0) { "weightUnit must be positive" }
        require(maxSampleWeight in 1..MAX_WEIGHT) { "maxSampleWeight must be 1..$MAX_WEIGHT" }
        require(colourMaxAngleDeg > 0 && colourMaxAngleDeg <= 90) { "colourMaxAngleDeg must be in (0, 90]" }
        require(behindFraction > 0 && behindFraction <= 1) { "behindFraction must be in (0, 1]" }
    }

    companion object {
        /** Weights saturate here (7 bits; the 8th bit of the weight byte flags "colour set"). */
        const val MAX_WEIGHT = 127
    }
}

/**
 * Dense, bounded truncated-signed-distance volume (§7.1.4, ADR-013) with confidence-weighted, ray-based
 * (Chisel-style) integration and a running vertex-colour average.
 *
 * **Memory:** structure-of-arrays, allocated once here and never during [integrate]:
 * `ShortArray` TSDF (sdf/μ scaled to ±32767), `ByteArray` weight (low 7 bits; bit 7 = colour set) and
 * `ByteArray` RGB (3 per voxel): 6 B per voxel, so ≤ 12.6 MB at [VolumeSizing.N_MAX].
 *
 * **Sign:** positive in front of the surface (free space, towards the camera), negative behind it (inside).
 *
 * **Integration** (per sample with confidence ≥ threshold and depth within range):
 * 1. back-project, drop the sample if its surface point is outside the volume (AABB pre-test, removes background);
 * 2. walk the ray from depth d − μ to d + [TsdfParams.behindFraction]·μ in half-voxel steps, visiting each grid
 *    point once per ray;
 * 3. projective SDF of the grid point = d − (its z-depth); `tsdf = (W·tsdf + w·sdf)/(W + w)`, `W ≤ 127`;
 * 4. colour: running average near the surface (|sdf| ≤ 2 v), only when the view angle to the surface, from the
 *    depth image's own normal, is below [TsdfParams.colourMaxAngleDeg].
 *
 * This is written from the published method descriptions only: Curless & Levoy, "A volumetric method for building
 * complex models from range images" (SIGGRAPH 1996) for the weighted running average, and Klingensmith et al.,
 * "CHISEL: real time large scale 3D reconstruction onboard a mobile device" (RSS 2015) for ray-based integration.
 * No code was taken from any implementation.
 *
 * Not thread-safe: integrate on one worker and extract after capture (§7.1.9).
 */
class TsdfVolume(val spec: VolumeSpec, val params: TsdfParams = TsdfParams()) {
    private val nx = spec.dims[0]
    private val ny = spec.dims[1]
    private val nz = spec.dims[2]

    /** Number of voxels. */
    val voxelCount: Int = nx * ny * nz

    internal val tsdf = ShortArray(voxelCount)
    internal val weight = ByteArray(voxelCount)
    internal val rgb = ByteArray(3 * voxelCount)

    /** Bytes held by the voxel arrays (6 per voxel). */
    val bytes: Long get() = 2L * tsdf.size + weight.size + rgb.size

    // box → world rotation (row-major) and translation, fixed for the volume's life.
    private val b = ReconMath.rotation(spec.boxToWorld.rotation)
    private val btx = spec.boxToWorld.translation.x
    private val bty = spec.boxToWorld.translation.y
    private val btz = spec.boxToWorld.translation.z
    private val cosColourMax2: Double = Math.cos(Math.toRadians(params.colourMaxAngleDeg)).let { it * it }

    /** Frames passed to [integrate] since construction or [reset]. */
    var framesIntegrated: Int = 0
        private set

    /** Depth samples that passed every gate and were fused. */
    var samplesIntegrated: Long = 0L
        private set

    /** Voxel updates performed (the cost driver: ≈ samples × 2μ/v). */
    var voxelUpdates: Long = 0L
        private set

    private var observedVoxels = 0
    private var rangeSum = 0.0
    private var sampleWeightSum = 0L

    /** Clears all fused data and statistics; keeps the arrays (no allocation), e.g. for "Start again". */
    fun reset() {
        java.util.Arrays.fill(tsdf, 0.toShort())
        java.util.Arrays.fill(weight, 0.toByte())
        java.util.Arrays.fill(rgb, 0.toByte())
        framesIntegrated = 0; samplesIntegrated = 0; voxelUpdates = 0
        observedVoxels = 0; rangeSum = 0.0; sampleWeightSum = 0
    }

    /**
     * Fuses one depth frame. [colour] (optional) must cover the depth image's field of view; [sampleWeight] is the
     * depth source factor ([DepthSource.sampleWeight]: 1 for raw, 0.5 for smoothed). Allocation-free.
     */
    fun integrate(depth: DepthFrame, colour: YuvFrame?, sampleWeight: Float) {
        require(sampleWeight > 0f && sampleWeight.isFinite()) { "sampleWeight must be positive" }
        framesIntegrated++
        val p = params
        val k = depth.intrinsics
        val fxInv = 1.0 / k.fx
        val fyInv = 1.0 / k.fy
        val cx = k.cx
        val cy = k.cy

        // Camera → world rotation from the pose quaternion (row-major, inlined to stay allocation-free).
        val q = depth.cameraToWorld.rotation
        val qx = q.x; val qy = q.y; val qz = q.z; val qw = q.w
        val w00 = 1 - 2 * (qy * qy + qz * qz); val w01 = 2 * (qx * qy - qz * qw); val w02 = 2 * (qx * qz + qy * qw)
        val w10 = 2 * (qx * qy + qz * qw); val w11 = 1 - 2 * (qx * qx + qz * qz); val w12 = 2 * (qy * qz - qx * qw)
        val w20 = 2 * (qx * qz - qy * qw); val w21 = 2 * (qy * qz + qx * qw); val w22 = 1 - 2 * (qx * qx + qy * qy)
        // Camera axes in the box frame: c_j = R_wbᵀ · (column j of R_wc).
        val c0x = b[0] * w00 + b[3] * w10 + b[6] * w20
        val c0y = b[1] * w00 + b[4] * w10 + b[7] * w20
        val c0z = b[2] * w00 + b[5] * w10 + b[8] * w20
        val c1x = b[0] * w01 + b[3] * w11 + b[6] * w21
        val c1y = b[1] * w01 + b[4] * w11 + b[7] * w21
        val c1z = b[2] * w01 + b[5] * w11 + b[8] * w21
        val c2x = b[0] * w02 + b[3] * w12 + b[6] * w22
        val c2y = b[1] * w02 + b[4] * w12 + b[7] * w22
        val c2z = b[2] * w02 + b[5] * w12 + b[8] * w22
        // Camera centre in the box frame: o = R_wbᵀ (t_wc − t_wb).
        val t = depth.cameraToWorld.translation
        val dtx = t.x - btx; val dty = t.y - bty; val dtz = t.z - btz
        val ox = b[0] * dtx + b[3] * dty + b[6] * dtz
        val oy = b[1] * dtx + b[4] * dty + b[7] * dtz
        val oz = b[2] * dtx + b[5] * dty + b[8] * dtz

        val v = spec.voxelM
        val invV = 1.0 / v
        val mn = spec.minCorner
        // Grid coordinates of the camera centre, and z-depth of grid point (i,j,k) = f0 + fi·i + fj·j + fk·k.
        val g0x = (ox - mn.x) * invV; val g0y = (oy - mn.y) * invV; val g0z = (oz - mn.z) * invV
        val f0 = -(c2x * (mn.x - ox) + c2y * (mn.y - oy) + c2z * (mn.z - oz))
        val fi = -c2x * v; val fj = -c2y * v; val fk = -c2z * v
        // AABB of the grid in the box frame, padded by half a voxel (the nearest-grid-point cells).
        val loX = mn.x - v / 2; val hiX = mn.x + v * (nx - 0.5)
        val loY = mn.y - v / 2; val hiY = mn.y + v * (ny - 0.5)
        val loZ = mn.z - v / 2; val hiZ = mn.z + v * (nz - 0.5)
        val mu = spec.truncM
        val invMu = 1.0 / mu
        val muBehind = mu * p.behindFraction
        val colourBand = 2 * v
        val sliceN = nx * ny

        val w = depth.width
        val h = depth.height
        val dm = depth.depthMm
        val cf = depth.confidence
        val minRaw = (p.minDepthM * 1000.0).toInt()
        val maxRaw = (p.maxDepthM * 1000.0).toInt()
        val weightScale = p.weightUnit * sampleWeight / 255.0

        for (vp in 0 until h) {
            val yp = -(vp - cy) * fyInv
            val row = vp * w
            for (up in 0 until w) {
                val pix = row + up
                val raw = dm[pix].toInt() and 0xFFFF
                if (raw == 0 || raw < minRaw || raw > maxRaw) continue
                val conf = cf[pix].toInt() and 0xFF
                if (conf < p.minConfidence) continue
                val d = raw * 0.001
                val xp = (up - cx) * fxInv
                // Ray direction in the box frame, scaled so that one unit of t = one metre of z-depth.
                val dx = xp * c0x + yp * c1x - c2x
                val dy = xp * c0y + yp * c1y - c2y
                val dz = xp * c0z + yp * c1z - c2z
                val sx = ox + d * dx; val sy = oy + d * dy; val sz = oz + d * dz
                if (sx < loX || sx > hiX || sy < loY || sy > hiY || sz < loZ || sz > hiZ) continue

                var sw = (weightScale * conf / (d * d) + 0.5).toInt()
                if (sw < 1) sw = 1 else if (sw > p.maxSampleWeight) sw = p.maxSampleWeight

                var cr = -1; var cg = 0; var cb = 0
                if (colour != null && up > 0 && up < w - 1 && vp > 0 && vp < h - 1 &&
                    viewAngleOk(dm, pix, w, xp, yp, fxInv, fyInv)
                ) {
                    val cw = colour.width
                    val uc = ((2 * up + 1) * cw) / (2 * w)
                    val vc = ((2 * vp + 1) * colour.height) / (2 * h)
                    val yy = colour.y[vc * cw + uc].toInt() and 0xFF
                    val ci = (vc / 2) * (cw / 2) + uc / 2
                    val uu = (colour.u[ci].toInt() and 0xFF) - 128
                    val vv = (colour.v[ci].toInt() and 0xFF) - 128
                    // Full-range BT.601 YCbCr → RGB (ITU-R BT.601 / JFIF), 16.16 fixed point.
                    cr = clamp255(yy + ((91881 * vv) shr 16))
                    cg = clamp255(yy - ((22554 * uu + 46802 * vv) shr 16))
                    cb = clamp255(yy + ((116130 * uu) shr 16))
                }

                val len = sqrt(dx * dx + dy * dy + dz * dz)
                val dt = 0.5 * v / len
                val steps = ceil((mu + muBehind) / dt).toInt()
                val gdx = dx * invV; val gdy = dy * invV; val gdz = dz * invV
                var last = -1
                for (s in 0..steps) {
                    val tt = d - mu + s * dt
                    val gx = g0x + tt * gdx
                    if (gx < -0.5 || gx >= nx - 0.5) continue
                    val gy = g0y + tt * gdy
                    if (gy < -0.5 || gy >= ny - 0.5) continue
                    val gz = g0z + tt * gdz
                    if (gz < -0.5 || gz >= nz - 0.5) continue
                    val i = (gx + 0.5).toInt(); val j = (gy + 0.5).toInt(); val kk = (gz + 0.5).toInt()
                    val vi = i + nx * j + sliceN * kk
                    if (vi == last) continue
                    last = vi
                    val sdf = d - (f0 + fi * i + fj * j + fk * kk)
                    if (sdf < -muBehind) continue
                    val sn = if (sdf >= mu) SCALE else (sdf * invMu * SCALE).roundToInt()
                    val wb = weight[vi].toInt()
                    val ow = wb and 0x7F
                    var flag = wb and 0x80
                    if (ow == 0) {
                        tsdf[vi] = sn.toShort()
                        observedVoxels++
                    } else {
                        tsdf[vi] = ((ow * tsdf[vi] + sw * sn).toDouble() / (ow + sw)).roundToInt().toShort()
                    }
                    if (cr >= 0 && abs(sdf) <= colourBand) {
                        val c = 3 * vi
                        if (flag == 0) {
                            rgb[c] = cr.toByte(); rgb[c + 1] = cg.toByte(); rgb[c + 2] = cb.toByte()
                            flag = 0x80
                        } else {
                            val tw = ow + sw
                            rgb[c] = ((ow * (rgb[c].toInt() and 0xFF) + sw * cr + tw / 2) / tw).toByte()
                            rgb[c + 1] = ((ow * (rgb[c + 1].toInt() and 0xFF) + sw * cg + tw / 2) / tw).toByte()
                            rgb[c + 2] = ((ow * (rgb[c + 2].toInt() and 0xFF) + sw * cb + tw / 2) / tw).toByte()
                        }
                    }
                    var nw = ow + sw
                    if (nw > TsdfParams.MAX_WEIGHT) nw = TsdfParams.MAX_WEIGHT
                    weight[vi] = (nw or flag).toByte()
                    voxelUpdates++
                }
                samplesIntegrated++
                rangeSum += d
                sampleWeightSum += sw
            }
        }
    }

    /**
     * True when the surface at pixel [pix] faces the camera within the colour angle. The normal comes from central
     * differences of the back-projected neighbours; a missing neighbour or a depth edge rejects colour there.
     */
    private fun viewAngleOk(dm: ShortArray, pix: Int, w: Int, xp: Double, yp: Double, fxInv: Double, fyInv: Double): Boolean {
        val dl = (dm[pix - 1].toInt() and 0xFFFF) * 0.001
        val dr = (dm[pix + 1].toInt() and 0xFFFF) * 0.001
        val dtp = (dm[pix - w].toInt() and 0xFFFF) * 0.001
        val dbt = (dm[pix + w].toInt() and 0xFFFF) * 0.001
        if (dl == 0.0 || dr == 0.0 || dtp == 0.0 || dbt == 0.0) return false
        // P(u, v) = d · (x', y', −1); u+1 → x' + 1/fx; v+1 → y' − 1/fy.
        val ux = dr * (xp + fxInv) - dl * (xp - fxInv); val uy = (dr - dl) * yp; val uz = -(dr - dl)
        val vx = (dbt - dtp) * xp; val vy = dbt * (yp - fyInv) - dtp * (yp + fyInv); val vz = -(dbt - dtp)
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val dot = nx * xp + ny * yp - nz
        val n2 = nx * nx + ny * ny + nz * nz
        val r2 = xp * xp + yp * yp + 1.0
        return n2 > 0 && dot * dot >= cosColourMax2 * n2 * r2
    }

    /** Fraction of voxels observed at least once (diagnostic for `mesh_info`). */
    fun observedFraction(): Double = observedVoxels.toDouble() / voxelCount

    /** Mean camera z-depth of the fused samples (m), 0 before any sample. */
    fun meanRangeM(): Double = if (samplesIntegrated == 0L) 0.0 else rangeSum / samplesIntegrated

    /**
     * Typical number of fused samples per near-surface voxel: the median accumulated weight of voxels with
     * |tsdf| ≤ μ/4, divided by the mean sample weight. It counts rays, not independent frames (adjacent pixels
     * of one frame can hit the same voxel), so it is an upper bound; [MeshExtent] caps it at 10 (§7.1.5).
     * 0 before any sample. Saturated weights (127) make it a lower bound for long scans; both ends hit the cap.
     */
    fun surfaceObservations(): Int {
        if (samplesIntegrated == 0L) return 0
        val hist = IntArray(TsdfParams.MAX_WEIGHT + 1)
        var count = 0
        val band = SCALE / 4
        for (i in 0 until voxelCount) {
            val wv = weight[i].toInt() and 0x7F
            if (wv == 0) continue
            val t = tsdf[i].toInt()
            if (t > band || t < -band) continue
            hist[wv]++; count++
        }
        if (count == 0) return 0
        var acc = 0
        var median = 0
        for (wv in 1..TsdfParams.MAX_WEIGHT) {
            acc += hist[wv]
            if (2 * acc >= count) { median = wv; break }
        }
        val meanW = sampleWeightSum.toDouble() / samplesIntegrated
        return maxOf(1, (median / meanW).roundToInt())
    }

    /** Normalised TSDF (−1..1) at grid point (i, j, k). */
    fun valueAt(i: Int, j: Int, k: Int): Float = tsdf[index(i, j, k)] / SCALE.toFloat()

    /** Accumulated weight (0..127) at grid point (i, j, k). */
    fun weightAt(i: Int, j: Int, k: Int): Int = weight[index(i, j, k)].toInt() and 0x7F

    /** Fused colour at (i, j, k) as 0xRRGGBB, or -1 if no colour was fused there. */
    fun colourAt(i: Int, j: Int, k: Int): Int {
        val vi = index(i, j, k)
        if (weight[vi].toInt() and 0x80 == 0) return -1
        val c = 3 * vi
        return ((rgb[c].toInt() and 0xFF) shl 16) or ((rgb[c + 1].toInt() and 0xFF) shl 8) or (rgb[c + 2].toInt() and 0xFF)
    }

    /**
     * Test/fixture hook: writes a voxel directly ([value] in −1..1, [w] in 0..127, [rgb] 0xRRGGBB or -1 for none).
     * Used by the marching-cubes table tests; capture code never calls it.
     */
    internal fun setVoxel(i: Int, j: Int, k: Int, value: Float, w: Int, rgb: Int = -1) {
        val vi = index(i, j, k)
        tsdf[vi] = (value.coerceIn(-1f, 1f) * SCALE).roundToInt().toShort()
        var flag = 0
        if (rgb >= 0) {
            this.rgb[3 * vi] = (rgb shr 16).toByte(); this.rgb[3 * vi + 1] = (rgb shr 8).toByte(); this.rgb[3 * vi + 2] = rgb.toByte()
            flag = 0x80
        }
        weight[vi] = (w.coerceIn(0, TsdfParams.MAX_WEIGHT) or flag).toByte()
    }

    private fun index(i: Int, j: Int, k: Int): Int {
        require(i in 0 until nx && j in 0 until ny && k in 0 until nz) { "grid index out of range" }
        return i + nx * (j + ny * k)
    }

    internal companion object {
        /** TSDF fixed-point scale: ±1 (±μ) ↔ ±32767. */
        const val SCALE = 32767

        private fun clamp255(x: Int): Int = if (x < 0) 0 else if (x > 255) 255 else x
    }
}
