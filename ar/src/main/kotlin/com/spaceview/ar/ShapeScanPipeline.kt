package com.spaceview.ar

import com.spaceview.engine.BoxMeasurement
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.MeshExtras
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import com.spaceview.engine.recon.CrossCheck
import com.spaceview.engine.recon.DepthNoise
import com.spaceview.engine.recon.DepthSource
import com.spaceview.engine.recon.Extent3
import com.spaceview.engine.recon.MarchingCubes
import com.spaceview.engine.recon.MeshExtent
import com.spaceview.engine.recon.MeshSegmenter
import com.spaceview.engine.recon.OrientedBox
import com.spaceview.engine.recon.TriMesh
import com.spaceview.engine.recon.TsdfVolume
import com.spaceview.engine.recon.VertexClusterDecimator
import kotlin.math.max

/** The measured box of a 3D scan in the ARCore world, from the BOX-mode points (§7.1.3 step 1). */
object ShapeScanBox {
    /** The box's top must be at least this far above its base (as `MeasurementComposer.MIN_BOX_HEIGHT_M`). */
    const val MIN_HEIGHT_M = 0.02

    /**
     * The world box from BOX mode's committed points: [base0]→[base1] the front bottom edge (width, local +X),
     * [base1]→[base2] the side bottom edge (depth), [top] any point on the top face. +Y is world up; +Z = X × Y, so the
     * frame is right-handed and the base corners may lie on either side. Width and depth are horizontal; depth is
     * measured perpendicular to the width edge, so a slightly skewed base still gives a rectangular box. The origin is
     * the centre of the base (the mid-point of the base diagonal) at the mean base height.
     *
     * Null for degenerate input (a width edge under 1 cm, no depth, or a top not above the base).
     */
    fun fromPoints(base0: Vec3, base1: Vec3, base2: Vec3, top: Vec3): OrientedBox? {
        val e = Vec3(base1.x - base0.x, 0.0, base1.z - base0.z)
        val w = e.length
        if (w < 0.01) return null
        val x = e / w
        val z = x cross Vec3.UP
        val d = kotlin.math.abs((base2 - base1) dot z)
        if (d < 0.01) return null
        val baseY = (base0.y + base1.y + base2.y) / 3
        val h = top.y - baseY
        if (h < MIN_HEIGHT_M) return null
        val c = (base0 + base2) / 2.0
        return OrientedBox(Pose(Vec3(c.x, baseY, c.z), PlacementMath.fromAxes(x, Vec3.UP, z)), w, h, d)
    }
}

/** One processing stage's cost. [pssKb] is the probe's reading right after the stage (0 without a probe). */
class StageCost(val name: String, val nanos: Long, val pssKb: Long, val trianglesOut: Int)

/** The result of [ShapeScanPipeline.process]. [glb] is null when the GLB could not be written ([glbError]). */
class ShapeScanResult(
    val mesh: TriMesh,
    val glb: ByteArray?,
    val glbError: String?,
    val extent: Extent3,
    val check: CrossCheck,
    val noise: DepthNoise,
    val coverage: Double,
    val voxelM: Double,
    val stages: List<StageCost>,
)

/** Holds the TSDF so processing can drop the only reference straight after marching cubes (§7.1.6 memory). */
class VolumeHolder(var volume: TsdfVolume?)

/**
 * After-capture processing of a 3D scan, in **exactly** the order of `ReconMemoryTest` (M4-P1 gate §8 D2):
 * 1. `DepthNoise` (and the `mesh_info` numbers) from the volume;
 * 2. marching cubes, then release the volume;
 * 3. floor clip at the support plane + max(1 cm, v);
 * 4. `keepLargestComponent(insideBox = the measured box)`;
 * 5. decimation to [TARGET_TRIANGLES] (15k, §7.1.7);
 * 6. `MeshExtent.estimate` and `crossCheck` against the measured box;
 * 7. `GlbWriter.writeMesh(keepInputOrigin = true)` (BOTTOM_CENTER = the measured base centre, unlit, MESH_DEVICE).
 *
 * Pure engine calls on the caller's thread (a worker, never main). The caller passes the box size the volume was built
 * with ([width] × [height] × [depth], the [OrientedBox] used for `VolumeSizing.spec`) and the measured box for the
 * cross-check. [pss] (optional) is read after each stage, outside its timing.
 */
object ShapeScanPipeline {
    /** §7.1.7: 15k triangles ≈ 300 KB, room for the thumbnail in the 0.5 MB item. */
    const val TARGET_TRIANGLES = 15_000

    /** The stage names, in order. */
    val STAGES = listOf("noise", "marching_cubes", "floor_clip", "components", "decimate", "extent", "glb")

    fun process(
        holder: VolumeHolder,
        source: DepthSource,
        model: UncertaintyModel,
        width: Double,
        height: Double,
        depth: Double,
        measured: BoxMeasurement,
        targetTriangles: Int = TARGET_TRIANGLES,
        pss: (() -> Long)? = null,
    ): ShapeScanResult {
        val stages = ArrayList<StageCost>(STAGES.size)
        fun stage(name: String, t0: Long, triangles: Int) {
            val dt = System.nanoTime() - t0
            stages += StageCost(name, dt, pss?.invoke() ?: 0L, triangles)
        }
        // 1-2 in a helper, so no local in this frame references the volume after it is released (a debuggable runtime
        // keeps locals alive to the end of the method; T-D0 review MAJOR 2).
        val ex = noiseAndMesh(holder, source, model, ::stage)
        val noise = ex.noise
        val coverage = ex.coverage
        val voxelM = ex.voxelM
        var m = ex.mesh
        var t0: Long

        // 3. Floor clip (the mesh is in the box frame: the support plane is y = 0).
        t0 = System.nanoTime()
        m = MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), max(0.01, voxelM))
        stage("floor_clip", t0, m.triangleCount)

        // 4. Largest component, inside the measured box.
        val local = OrientedBox.local(width, height, depth)
        t0 = System.nanoTime()
        m = MeshSegmenter.keepLargestComponent(m, insideBox = local)
        stage("components", t0, m.triangleCount)

        // 5. Decimate to the budget.
        t0 = System.nanoTime()
        if (m.triangleCount > 0) m = VertexClusterDecimator.decimate(m, targetTriangles)
        stage("decimate", t0, m.triangleCount)

        // 6. Honest extents and the cross-check (an empty mesh never agrees).
        t0 = System.nanoTime()
        val extent = MeshExtent.estimate(m, local, noise)
        val check = MeshExtent.crossCheck(extent, measured)
        stage("extent", t0, m.triangleCount)

        // 7. The GLB, kept in the measured box's frame.
        t0 = System.nanoTime()
        var glbError: String? = null
        val glb = if (m.triangleCount == 0) {
            glbError = "empty mesh"
            null
        } else {
            try {
                GlbWriter.writeMesh(m, BoxOrigin.BOTTOM_CENTER, unlit = true, extrasFor(source, voxelM, coverage), keepInputOrigin = true)
            } catch (e: IllegalArgumentException) {
                glbError = e.message ?: "writeMesh failed"
                null
            }
        }
        stage("glb", t0, m.triangleCount)
        return ShapeScanResult(m, glb, glbError, extent, check, noise, coverage, voxelM, stages)
    }

    private class Extracted(val noise: DepthNoise, val coverage: Double, val voxelM: Double, val mesh: TriMesh)

    /**
     * Steps 1 and 2: everything still needed from the volume first, then marching cubes, then drop the only reference
     * to the volume (up to 12.6 MB) before anything else runs. The `marching_cubes` reading is taken with the volume
     * still alive (its peak, as in `ReconMemoryTest`).
     */
    private fun noiseAndMesh(holder: VolumeHolder, source: DepthSource, model: UncertaintyModel, stage: (String, Long, Int) -> Unit): Extracted {
        val vol = requireNotNull(holder.volume) { "no volume" }
        var t0 = System.nanoTime()
        val noise = DepthNoise.from(vol, source, model)
        val coverage = vol.observedFraction()
        val voxelM = vol.spec.voxelM
        stage("noise", t0, 0)
        t0 = System.nanoTime()
        val m = MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT)
        stage("marching_cubes", t0, m.triangleCount)
        holder.volume = null
        return Extracted(noise, coverage, voxelM, m)
    }

    /** `mesh_info`-style diagnostics in the GLB extras (numbers only, §7.1.3: the source is recorded). */
    fun extrasFor(source: DepthSource, voxelM: Double, coverage: Double) = MeshExtras(
        ModelKind.MESH_DEVICE,
        values = mapOf("voxelM" to voxelM, "coverage" to coverage, "depthSource" to source.ordinal.toDouble()),
    )
}
