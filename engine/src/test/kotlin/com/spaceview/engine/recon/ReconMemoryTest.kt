package com.spaceview.engine.recon

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.max

/**
 * Peak extra memory of the processing pipeline on a noisy sofa-size scan (review M4 MINOR 9; spike criterion
 * §7.1.10 item 3: peak extra PSS ≤ 40 MB).
 *
 * Method: the bytes a stage **allocates** (HotSpot's per-thread allocation counter) bound the extra memory it can
 * hold live at any instant. Peak ≤ max over stages of (long-lived inputs still referenced + bytes allocated by the
 * stage). Long-lived inputs are the TSDF volume (until it is released) and the stage's input mesh.
 * Deterministic: the same inputs allocate the same arrays.
 */
class ReconMemoryTest {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private inline fun <T> allocated(block: () -> T): Pair<T, Long> {
        val id = Thread.currentThread().threadId()
        val before = mx.getThreadAllocatedBytes(id)
        val r = block()
        return r to (mx.getThreadAllocatedBytes(id) - before)
    }

    private fun bytes(m: TriMesh) = 4L * m.positions.size + m.colours.size + 4L * m.indices.size

    @Test fun pipelinePeakStaysWellUnderTheSpikeLimit() {
        val box = OrientedBox(Pose(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UP, 0.3)), 2.2, 0.9, 1.0)
        val vol = TsdfVolume(VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!)
        val scene = listOf(Shape.Box(box, 0x6A4A3A), Shape.Floor(0.0, 0x909090))
        val poses = SyntheticRenderer.ring(Vec3(0.0, 0.45, 0.0), 1.65, listOf(15.0, 40.0, 65.0), SyntheticRenderer.azimuths(16))
        SyntheticRenderer.fuse(vol, scene, poses, DepthNoiseSim(relSigma = 0.01, holeFraction = 0.1, flyingPixels = true), seed = 21)
        // §7.1.4 order: noise terms first, so the volume can be released straight after marching cubes.
        val noise = DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH))
        val volBytes = vol.bytes
        val (raw, aMc) = allocated { MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT) }
        val cut = max(0.01, vol.spec.voxelM)
        val (clipped, aCut) = allocated { MeshSegmenter.removeBelow(raw, Plane(Vec3.UP, 0.0), cut) }
        val local = OrientedBox.local(box.width, box.height, box.depth)
        val (seg, aSeg) = allocated { MeshSegmenter.keepLargestComponent(clipped, insideBox = local) }
        val (dec, aDec) = allocated { VertexClusterDecimator.decimate(seg, VertexClusterDecimator.DEFAULT_TARGET_TRIANGLES) }
        val (_, aExt) = allocated { MeshExtent.estimate(dec, local, noise) }

        val stages = listOf(
            "marching cubes" to (volBytes + aMc),
            "floor clip" to (bytes(raw) + aCut),
            "components" to (bytes(clipped) + aSeg),
            "decimate" to (bytes(seg) + aDec),
            "extent" to (bytes(dec) + aExt),
        )
        val peak = stages.maxOf { it.second }
        // The same allocations if the caller keeps the volume until the end (the order the review flagged).
        val peakVolumeKept = volBytes + stages.drop(1).maxOf { it.second }
        println("MEMORY pipeline (sofa, %d raw tris, %d after segmentation): ".format(raw.triangleCount, seg.triangleCount) +
            stages.joinToString { "%s %.1f MB".format(it.first, it.second / 1e6) } +
            "; decimator alone allocates %.1f MB; PEAK %.1f MB (volume released after meshing), %.1f MB if kept to the end"
                .format(aDec / 1e6, peak / 1e6, peakVolumeKept / 1e6))
        assertTrue("decimator allocates ${aDec / 1e6} MB", aDec <= 10_000_000)
        assertTrue("peak ${peak / 1e6} MB", peak <= 25_000_000)
        assertTrue("peak with the volume kept ${peakVolumeKept / 1e6} MB", peakVolumeKept <= 25_000_000)
    }
}
