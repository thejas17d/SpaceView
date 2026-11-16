package com.spaceview.engine.recon

import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * Microbenchmark-style timing of the whole on-device pipeline on a sofa-size volume (2.2 × 0.9 × 1.0 m box,
 * 10 cm margin: the full 2^21-voxel budget). **Informational only:** desktop-JVM numbers, not phone numbers; the
 * phone budget (≤ 15 ms per integrated frame, ≤ 5 s processing) is checked by the device spike (§7.1.10).
 * Prints `TIMING` lines; asserts only correctness, never speed.
 */
class ReconBenchmarkTest {
    @Test fun sofaPipelineTiming() {
        val box = OrientedBox(Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UP, 0.3)), 2.2, 0.9, 1.0)
        val spec = VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!
        val vol = TsdfVolume(spec)
        val scene = listOf(Shape.Box(box, 0x6A4A3A), Shape.Floor(0.0, 0x909090))
        val poses = SyntheticRenderer.ring(Vec3(0.0, 0.45, 0.0), 1.65, listOf(15.0, 40.0, 65.0), SyntheticRenderer.azimuths(16))
        val noise = DepthNoiseSim(relSigma = 0.01, holeFraction = 0.1, flyingPixels = true)
        // Pre-render so only integration is timed.
        val rng = java.util.Random(9)
        val frames = poses.map { p -> SyntheticRenderer.render(scene, p, noise, rng, DepthFrame(SyntheticRenderer.W, SyntheticRenderer.H), YuvFrame(2 * SyntheticRenderer.W, 2 * SyntheticRenderer.H)) }
        // Warm-up (JIT) on a scratch volume of the same size, then time each frame on the real one.
        val warm = TsdfVolume(spec)
        repeat(3) { frames.forEach { (d, c) -> warm.integrate(d, c, 1f) } }
        val perFrame = DoubleArray(frames.size)
        for ((i, f) in frames.withIndex()) {
            val t0 = System.nanoTime()
            vol.integrate(f.first, f.second, 1f)
            perFrame[i] = (System.nanoTime() - t0) / 1e6
        }
        perFrame.sort()
        val updatesPerFrame = vol.voxelUpdates.toDouble() / frames.size
        val nsPerUpdate = perFrame.sum() * 1e6 / vol.voxelUpdates
        println("TIMING integrate (desktop JVM, 160×120 + colour, %d frames): median %.2f ms, p90 %.2f ms, max %.2f ms; %.0f voxel updates/frame, %.1f ns/update"
            .format(frames.size, perFrame[frames.size / 2], perFrame[(frames.size * 9) / 10], perFrame.last(), updatesPerFrame, nsPerUpdate))

        var t = System.nanoTime()
        val raw = MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT)
        val tMc = (System.nanoTime() - t) / 1e6
        t = System.nanoTime()
        val seg = MeshSegmenter.keepLargestComponent(MeshSegmenter.removeBelow(raw, Plane(Vec3.UP, 0.0), max(0.01, spec.voxelM)), insideBox = OrientedBox.local(2.2, 0.9, 1.0))
        val tSeg = (System.nanoTime() - t) / 1e6
        t = System.nanoTime()
        val dec = VertexClusterDecimator.decimate(seg, VertexClusterDecimator.DEFAULT_TARGET_TRIANGLES)
        val tDec = (System.nanoTime() - t) / 1e6
        val meshBytes = raw.positions.size * 4L + raw.colours.size + raw.indices.size * 4L
        println("TIMING processing: marching cubes %.0f ms (%d tris, %.1f MB mesh arrays), segment %.0f ms, decimate %.0f ms → %d tris; total %.0f ms"
            .format(tMc, raw.triangleCount, meshBytes / 1e6, tSeg, tDec, dec.triangleCount, tMc + tSeg + tDec))
        println("MEMORY sofa TSDF: %d voxels × 6 B = %.2f MB (budget 12.58 MB), voxel %.2f cm, μ %.1f cm"
            .format(vol.voxelCount, vol.bytes / 1e6, spec.voxelM * 100, spec.truncM * 100))
        assertTrue(dec.triangleCount in 1..VertexClusterDecimator.DEFAULT_TARGET_TRIANGLES)
        assertTrue(vol.bytes <= 12_582_912L)
    }
}
