package com.spaceview.engine.recon

import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.max

/**
 * A dense TSDF grid in the measured box's frame (§7.1.4).
 *
 * Grid point (i, j, k) sits at `minCorner + voxelM · (i, j, k)` in the box frame ([OrientedBox] local), for
 * `0 ≤ i < dims[0]`, `0 ≤ j < dims[1]`, `0 ≤ k < dims[2]`; x varies fastest in memory. [boxToWorld] maps the box
 * frame to the ARCore world. [truncM] is the TSDF truncation distance μ.
 */
class VolumeSpec(val boxToWorld: Pose, val minCorner: Vec3, val dims: IntArray, val voxelM: Double, val truncM: Double) {
    init {
        require(dims.size == 3 && dims.all { it >= 2 }) { "dims must be 3 axes of ≥ 2 grid points" }
        require(voxelM.isFinite() && voxelM > 0) { "voxelM must be positive" }
        require(truncM.isFinite() && truncM >= voxelM) { "truncM must be ≥ voxelM" }
        require(minCorner.isFinite) { "minCorner must be finite" }
        require(dims[0].toLong() * dims[1] * dims[2] <= Int.MAX_VALUE / 3) { "volume too large" }
    }

    /** Number of grid points (voxels). */
    val voxelCount: Int get() = dims[0] * dims[1] * dims[2]

    /** Bytes of the dense arrays: [VolumeSizing.BYTES_PER_VOXEL] per voxel. */
    val bytes: Long get() = VolumeSizing.BYTES_PER_VOXEL.toLong() * voxelCount

    /** Upper corner of the grid (box frame). */
    val maxCorner: Vec3
        get() = Vec3(
            minCorner.x + voxelM * (dims[0] - 1), minCorner.y + voxelM * (dims[1] - 1), minCorner.z + voxelM * (dims[2] - 1),
        )
}

/**
 * Sizes the bounded volume from the measured box (§7.1.4): box + [marginM] on the four sides and the top, plus
 * [BELOW_FLOOR_M] below the support plane; voxel = clamp(∛(V / N_MAX), 1 cm, 3 cm).
 */
object VolumeSizing {
    /** Voxel budget: 2,097,152 voxels × 6 B = 12.6 MB. */
    const val N_MAX = 1 shl 21

    /** Budget when `memoryClass < 192 MB` or `isLowRamDevice`: 6.3 MB. */
    const val N_MAX_LOW_RAM = 1 shl 20

    /** Largest box side accepted (objects ≤ 2.5 m per side, §7.1.1). */
    const val MAX_SIDE_M = 2.5

    /** Smallest voxel: below 1 cm adds memory but no detail, depth noise is cm-level (research §7.5). */
    const val MIN_VOXEL_M = 0.01

    /** Largest voxel. */
    const val MAX_VOXEL_M = 0.03

    /** Volume extends this far below the support plane so the floor's zero crossing is inside it. */
    const val BELOW_FLOOR_M = 0.02

    /** Default crop margin (research §7.5). */
    const val DEFAULT_MARGIN_M = 0.10

    /** Largest margin the crop step allows (§7.1.3: 0–15 cm). */
    const val MAX_MARGIN_M = 0.15

    /** Truncation μ = max(TRUNC_MIN_M, TRUNC_VOXELS · v) (research §7.5: ≥ 4 cm, above depth noise at ~1 m). */
    const val TRUNC_MIN_M = 0.04

    /** See [TRUNC_MIN_M]. */
    const val TRUNC_VOXELS = 3.0

    /** 2 B TSDF + 1 B weight + 3 B RGB. */
    const val BYTES_PER_VOXEL = 6

    /**
     * The volume for [box] (its pose in world), or null if any box side exceeds [MAX_SIDE_M] or the volume cannot
     * fit the voxel budget at [MAX_VOXEL_M]. [marginM] must be within 0..[MAX_MARGIN_M].
     */
    fun spec(box: OrientedBox, marginM: Double, lowRam: Boolean): VolumeSpec? {
        require(marginM.isFinite() && marginM >= 0 && marginM <= MAX_MARGIN_M) { "marginM must be within 0..$MAX_MARGIN_M" }
        if (box.width > MAX_SIDE_M || box.height > MAX_SIDE_M || box.depth > MAX_SIDE_M) return null
        val nMax = if (lowRam) N_MAX_LOW_RAM else N_MAX
        val ex = box.width + 2 * marginM
        val ey = box.height + marginM + BELOW_FLOOR_M
        val ez = box.depth + 2 * marginM
        var voxel = cbrt(ex * ey * ez / nMax).coerceIn(MIN_VOXEL_M, MAX_VOXEL_M)
        // ceil and the +1 fence post can push the count just over the budget: grow the voxel by 0.5% until it fits.
        while (true) {
            val nx = ceil(ex / voxel - 1e-9).toInt() + 1
            val ny = ceil(ey / voxel - 1e-9).toInt() + 1
            val nz = ceil(ez / voxel - 1e-9).toInt() + 1
            if (nx.toLong() * ny * nz <= nMax) {
                val min = Vec3(-ex / 2, -BELOW_FLOOR_M, -ez / 2)
                return VolumeSpec(box.pose, min, intArrayOf(nx, ny, nz), voxel, max(TRUNC_MIN_M, TRUNC_VOXELS * voxel))
            }
            voxel *= 1.005
            if (voxel > MAX_VOXEL_M) return null
        }
    }
}
