package com.spaceview.engine

import com.spaceview.engine.recon.DepthNoise
import com.spaceview.engine.recon.DepthSource
import com.spaceview.engine.recon.MarchingCubes
import com.spaceview.engine.recon.MeshExtent
import com.spaceview.engine.recon.MeshSegmenter
import com.spaceview.engine.recon.OrientedBox
import com.spaceview.engine.recon.Shape
import com.spaceview.engine.recon.SyntheticRenderer
import com.spaceview.engine.recon.TsdfVolume
import com.spaceview.engine.recon.VolumeSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * M4 review MAJOR 2, engine half: the real recon pipeline (fusion → marching cubes → floor cut → largest component
 * → `MeshExtent`) into `GlbWriter.writeMesh`. The GLB's own bounding box (K8 extras) and the §7.1.5 extents are
 * different numbers, and `keepInputOrigin` keeps the scan standing where it was measured.
 */
class ScanToGlbTest {
    private val box = OrientedBox(Pose(Vec3(0.2, 0.0, -0.1), Quaternion.fromAxisAngle(Vec3.UP, 0.5)), 0.6, 0.4, 0.4)

    private val fused by lazy {
        val vol = TsdfVolume(VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!)
        val scene = listOf(Shape.Box(box, 0x2850C8), Shape.Floor(0.0, 0x808080))
        val poses = SyntheticRenderer.ring(box.pose.transformPoint(Vec3(0.0, 0.2, 0.0)), 1.2, listOf(15.0, 40.0, 65.0), SyntheticRenderer.azimuths(12))
        SyntheticRenderer.fuse(vol, scene, poses)
        var m = MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT)
        val cut = max(0.01, vol.spec.voxelM)
        m = MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), cut)
        m = MeshSegmenter.keepLargestComponent(m)
        val extent = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH)))
        Triple(m, extent, Pair(cut, vol.spec.voxelM))
    }

    @Test fun glbBoxAndMeasuredExtentsDifferAndBothAreReported() {
        val (mesh, extent, cv) = fused
        val (cut, v) = cv
        val glb = GlbWriter.writeMesh(mesh, BoxOrigin.BOTTOM_CENTER, unlit = true, keepInputOrigin = true)
        val info = GlbReader.readInfo(glb)!!
        // §7.1.5 height runs from the support plane; the GLB's own box starts at the floor cut.
        assertEquals(0.4, extent.height.value, v)
        assertEquals(extent.height.value - cut, info.heightM, v)
        assertTrue("GLB H ${info.heightM} vs extent H ${extent.height.value}", abs(info.heightM - extent.height.value) > 1e-4)
        assertEquals(0.6, info.widthM, 2 * v)
        assertEquals(0.4, info.depthM, 2 * v)
        println("SCAN→GLB: GLB %.1f × %.1f × %.1f mm, extents %.1f × %.1f × %.1f mm (cut %.1f mm)".format(
            info.widthM * 1000, info.heightM * 1000, info.depthM * 1000,
            extent.width.value * 1000, extent.height.value * 1000, extent.depth.value * 1000, cut * 1000,
        ))
    }

    @Test fun keepInputOriginStandsTheScanAtItsMeasuredFootprint() {
        val (mesh, _, cv) = fused
        val (cut, v) = cv
        val kept = GlbTestKit.parse(GlbWriter.writeMesh(mesh, keepInputOrigin = true)).floats(0)
        // Origin = centre of the measured box base: the scan's bottom is the floor cut above it, never below.
        val minY = (1 until kept.size step 3).minOf { kept[it] }
        assertEquals(cut, minY.toDouble(), 1e-4)
        // Footprint centred on the measured box (not on the mesh's own box).
        val midX = ((0 until kept.size step 3).minOf { kept[it] } + (0 until kept.size step 3).maxOf { kept[it] }) / 2.0
        val midZ = ((2 until kept.size step 3).minOf { kept[it] } + (2 until kept.size step 3).maxOf { kept[it] }) / 2.0
        assertEquals(0.0, midX, v)
        assertEquals(0.0, midZ, v)
        // The default (re-centred) writer drops it onto its own bottom instead.
        val moved = GlbTestKit.parse(GlbWriter.writeMesh(mesh)).floats(0)
        assertEquals(0f, (1 until moved.size step 3).minOf { moved[it] }, 1e-6f)
    }
}
