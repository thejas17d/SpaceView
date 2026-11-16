package com.spaceview.engine.recon

import com.spaceview.engine.BoxMeasurement
import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The mesh's own W × H × D along the box axes with a 95% ± (§7.1.5). **Never the item's dimensions**: those stay
 * the measured box. This is the "3D shape (approximate, from depth)" line and the cross-check input.
 * [vertexCount] is the number of vertices used; 0 means an empty mesh (all values 0, see [isEmpty]).
 */
class Extent3(val width: Measurement, val height: Measurement, val depth: Measurement, val vertexCount: Int) {
    /** True when the mesh had no triangles; such an extent always fails [MeshExtent.crossCheck]. */
    val isEmpty: Boolean get() = vertexCount == 0

    override fun toString(): String = "Extent3(w=$width, h=$height, d=$depth, n=$vertexCount)"
}

/** Mesh-shape badge (§7.1.5): the mesh is always approximate; ROUGH when it disagrees with the measured box. */
enum class MeshBadge { APPROXIMATE, ROUGH }

/**
 * Mesh extents compared with the measured box. Differences are mesh − box (m); a positive value means the mesh
 * reads larger. [tolerance] per axis is `max(3 cm, 3% of the box value, combined ±95)`.
 */
class CrossCheck(
    val diffW: Double, val diffH: Double, val diffD: Double,
    val tolW: Double, val tolH: Double, val tolD: Double,
    val meshEmpty: Boolean,
) {
    /** True when every axis is within tolerance (and the mesh is not empty). */
    val agrees: Boolean get() = !meshEmpty && abs(diffW) <= tolW && abs(diffH) <= tolH && abs(diffD) <= tolD

    /** [MeshBadge.ROUGH] when the cross-check fails; the UI then shows the "differ by X cm" message (§7.1.5). */
    val badge: MeshBadge get() = if (agrees) MeshBadge.APPROXIMATE else MeshBadge.ROUGH

    /** The axis with the largest excess over its tolerance: 0 = width, 1 = height, 2 = depth. */
    val worstAxis: Int
        get() {
            val e = doubleArrayOf(abs(diffW) - tolW, abs(diffH) - tolH, abs(diffD) - tolD)
            return if (e[0] >= e[1] && e[0] >= e[2]) 0 else if (e[1] >= e[2]) 1 else 2
        }
}

/** Honest mesh extents (§7.1.5). */
object MeshExtent {
    /** Robust low / high percentiles of the vertex coordinates, so stray vertices cannot stretch the extents. */
    const val LOW_PERCENTILE = 0.005

    /** See [LOW_PERCENTILE]. */
    const val HIGH_PERCENTILE = 0.995

    /** n_eff = min(observations, 10): consecutive frames are correlated (§7.1.5). */
    const val N_EFF_CAP = 10

    /** Cross-check floor: 3 cm (research §7.6). */
    const val CROSS_CHECK_MIN_M = 0.03

    /** Cross-check relative floor: 3% (research §7.6). */
    const val CROSS_CHECK_MIN_REL = 0.03

    /**
     * Extents of [mesh] along the axes of [frame] (in the mesh's frame; for a [MarchingCubes] mesh use
     * `OrientedBox.local(w, h, d)`), with [noise] giving the ±.
     *
     * - **W, D:** the 0.5%–99.5% percentile spread of the referenced vertices' coordinates.
     * - **H:** from the support plane (the frame's y = 0) to the 99.5% percentile, because the floor cut removes the
     *   bottom centimetre of the mesh; if the mesh reaches below the plane, from its own 0.5% percentile.
     * - **±95:** σ_face² = σ_depth(r̄)²/n_eff + v²/12 (random face position, §7.1.5); the extent combines two faces
     *   (for H: the top and the support plane) and the fully correlated scale term:
     *   σ_ext² = 2·σ_face² + (rel_scale · L)².
     *   The scale term acts on the **length**: depth-from-motion is triangulated from the VIO poses, so a VIO scale
     *   error scales the whole fused model. (Review M4 MINOR 10 revision of C5: the earlier max(L, √2·r̄) made the
     *   ± of small objects 3–6× their synthetic error; an independent depth-scale bias is not covered and is a
     *   bench item, §7.1.10.) Then ±95 = 1.96·σ_ext + the inflation bias on **W and D only** (2 sides each;
     *   research §7.6: silhouettes read large, the height is the best dimension), floored at the tier's ±95 floor.
     *   Rounding up for display is the UI's existing rule.
     */
    fun estimate(mesh: TriMesh, frame: OrientedBox, noise: DepthNoise): Extent3 {
        mesh.requireWellFormed()
        if (mesh.isEmpty) {
            val z = Measurement(0.0, 0.0)
            return Extent3(z, z, z, 0)
        }
        val used = BooleanArray(mesh.vertexCount)
        for (i in mesh.indices) used[i] = true
        val n = used.count { it }
        val xs = DoubleArray(n); val ys = DoubleArray(n); val zs = DoubleArray(n)
        val l = DoubleArray(3)
        var m = 0
        val p = mesh.positions
        for (v in used.indices) if (used[v]) {
            frame.toLocal(p[3 * v].toDouble(), p[3 * v + 1].toDouble(), p[3 * v + 2].toDouble(), l)
            xs[m] = l[0]; ys[m] = l[1]; zs[m] = l[2]; m++
        }
        xs.sort(); ys.sort(); zs.sort()
        val w = percentile(xs, HIGH_PERCENTILE) - percentile(xs, LOW_PERCENTILE)
        val d = percentile(zs, HIGH_PERCENTILE) - percentile(zs, LOW_PERCENTILE)
        val h = percentile(ys, HIGH_PERCENTILE) - minOf(0.0, percentile(ys, LOW_PERCENTILE))
        val infl = noise.source.inflationPerSideM
        return Extent3(measure(w, noise, 2 * infl), measure(h, noise, 0.0), measure(d, noise, 2 * infl), n)
    }

    private fun measure(length: Double, noise: DepthNoise, bias: Double): Measurement {
        val nEff = noise.observations.coerceIn(1, N_EFF_CAP)
        val sd = noise.sigmaDepthM
        val v = noise.voxelM
        val face2 = sd * sd / nEff + v * v / 12
        val scale = noise.scaleSigmaRel * length
        val sigma = sqrt(2 * face2 + scale * scale)
        val bound95 = max(Uncertainty.K95 * sigma + bias, noise.absFloor95M)
        return Measurement(length, bound95 / Uncertainty.K95)
    }

    /** Linear-interpolated percentile of an ascending array. */
    internal fun percentile(sorted: DoubleArray, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val i = pos.toInt()
        if (i >= sorted.size - 1) return sorted[sorted.size - 1]
        val f = pos - i
        return sorted[i] + (sorted[i + 1] - sorted[i]) * f
    }

    /**
     * §7.1.5 cross-check of the mesh [extent] against the [measured] box (W, H, D in the same axes). Tolerance per
     * axis = max([CROSS_CHECK_MIN_M], [CROSS_CHECK_MIN_REL] × box value, combined ±95). The combined ± is the
     * root-sum-square of the two ±95s: the errors are independent, and RSS gives the smaller tolerance, so the
     * check warns rather than stays quiet when in doubt (cf. review R1 on linear vs RSS). Neither size is ever
     * rescaled to the other.
     */
    fun crossCheck(extent: Extent3, measured: BoxMeasurement): CrossCheck {
        fun tol(mesh: Measurement, box: Measurement): Double {
            val combined = sqrt(mesh.bound95 * mesh.bound95 + box.bound95 * box.bound95)
            return max(CROSS_CHECK_MIN_M, max(CROSS_CHECK_MIN_REL * abs(box.value), combined))
        }
        return CrossCheck(
            extent.width.value - measured.width.value,
            extent.height.value - measured.height.value,
            extent.depth.value - measured.depth.value,
            tol(extent.width, measured.width), tol(extent.height, measured.height), tol(extent.depth, measured.depth),
            extent.isEmpty,
        )
    }
}
