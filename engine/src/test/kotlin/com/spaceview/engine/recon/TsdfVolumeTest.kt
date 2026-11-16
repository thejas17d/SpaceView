package com.spaceview.engine.recon

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

class VolumeSizingTest {
    private fun box(w: Double, h: Double, d: Double) = OrientedBox(Pose.IDENTITY, w, h, d)

    @Test fun cartonUsesOneCentimetreVoxelsAndSmallMemory() {
        val s = VolumeSizing.spec(box(0.6, 0.4, 0.4), VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!
        assertEquals(0.01, s.voxelM, 1e-12)
        assertEquals(listOf(81, 53, 61), s.dims.toList()) // 0.8 × 0.52 × 0.6 m, fence posts included
        assertEquals(0.04, s.truncM, 1e-12)
        assertTrue(s.minCorner.approxEquals(Vec3(-0.4, -0.02, -0.3), 1e-12))
        assertTrue(s.bytes < 2_000_000)
        assertEquals(0.4, s.maxCorner.x, 1e-9)
    }

    @Test fun sofaStaysWithinTheVoxelBudgetAndTwelvePointSixMegabytes() {
        val s = VolumeSizing.spec(box(2.2, 1.0, 0.9), VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!
        assertTrue("N = ${s.voxelCount}", s.voxelCount <= VolumeSizing.N_MAX)
        assertTrue(s.voxelM > 0.01 && s.voxelM < 0.013)
        assertTrue(s.bytes <= 12_582_912L) // 6 B × 2^21
        val v = TsdfVolume(s)
        assertEquals(s.bytes, v.bytes)
        assertEquals(6L * v.voxelCount, v.bytes)
        println("MEMORY sofa volume: voxel=%.2f cm, dims=%s, N=%d, %.2f MB".format(s.voxelM * 100, s.dims.toList(), s.voxelCount, s.bytes / 1e6))
    }

    @Test fun lowRamHalvesTheBudget() {
        val s = VolumeSizing.spec(box(2.2, 1.0, 0.9), VolumeSizing.DEFAULT_MARGIN_M, lowRam = true)!!
        assertTrue(s.voxelCount <= VolumeSizing.N_MAX_LOW_RAM)
        assertTrue(s.bytes <= 6_291_456L)
        // The largest allowed box at the largest margin still fits under 3 cm voxels.
        val big = VolumeSizing.spec(box(2.5, 2.5, 2.5), VolumeSizing.MAX_MARGIN_M, lowRam = true)!!
        assertTrue(big.voxelM <= VolumeSizing.MAX_VOXEL_M && big.voxelCount <= VolumeSizing.N_MAX_LOW_RAM)
        assertEquals(VolumeSizing.TRUNC_VOXELS * big.voxelM, big.truncM, 1e-12)
    }

    @Test fun tinyBoxClampsAtOneCentimetre() {
        val s = VolumeSizing.spec(box(0.01, 0.01, 0.01), 0.0, lowRam = false)!!
        assertEquals(VolumeSizing.MIN_VOXEL_M, s.voxelM, 0.0)
        assertEquals(listOf(2, 4, 2), s.dims.toList())
    }

    @Test fun sidesOverTwoPointFiveMetresAreRefused() {
        assertNull(VolumeSizing.spec(box(2.51, 1.0, 1.0), 0.1, false))
        assertNull(VolumeSizing.spec(box(1.0, 2.51, 1.0), 0.1, false))
        assertNull(VolumeSizing.spec(box(1.0, 1.0, 2.51), 0.1, false))
        assertNotNull(VolumeSizing.spec(box(2.5, 2.5, 2.5), 0.1, false))
    }

    @Test fun invalidInputsThrow() {
        assertThrows { VolumeSizing.spec(box(1.0, 1.0, 1.0), -0.01, false) }
        assertThrows { VolumeSizing.spec(box(1.0, 1.0, 1.0), 0.16, false) }
        assertThrows { VolumeSizing.spec(box(1.0, 1.0, 1.0), Double.NaN, false) }
        assertThrows { VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(2, 2), 0.01, 0.04) }
        assertThrows { VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(2, 1, 2), 0.01, 0.04) }
        assertThrows { VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(2, 2, 2), 0.0, 0.04) }
        assertThrows { VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(2, 2, 2), 0.01, 0.005) }
        assertThrows { VolumeSpec(Pose.IDENTITY, Vec3(Double.NaN, 0.0, 0.0), intArrayOf(2, 2, 2), 0.01, 0.04) }
        assertThrows { VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(2000, 2000, 2000), 0.01, 0.04) }
    }
}

internal fun assertThrows(block: () -> Unit) {
    try { block() } catch (e: IllegalArgumentException) { return } catch (e: IllegalStateException) { return }
    throw AssertionError("expected an exception")
}

class TsdfVolumeTest {
    /** 0.4 m cube volume (no margin) at the world origin; grid point (20, 20, 20) is the box centre-ish. */
    private fun volume(params: TsdfParams = TsdfParams()) =
        TsdfVolume(VolumeSizing.spec(OrientedBox(Pose.IDENTITY, 0.4, 0.4, 0.4), 0.0, false)!!, params)

    /** 1×1 depth frame whose single pixel looks straight down −Z from (0, 0.2, camZ). */
    private fun pixel(depthM: Double, conf: Int = 255, camZ: Double = 0.8): DepthFrame {
        val f = DepthFrame(1, 1)
        f.intrinsics = CameraIntrinsics(100.0, 100.0, 0.0, 0.0)
        f.cameraToWorld = Pose(Vec3(0.0, 0.2, camZ))
        f.depthMm[0] = (depthM * 1000).toInt().toShort()
        f.confidence[0] = conf.toByte()
        return f
    }

    // Grid: minCorner (−0.2, −0.02, −0.2), 1 cm. Point (0, 0.2, z) ↔ (20, 22, 20 + z/0.01).
    private fun k(z: Double) = Math.round((z + 0.2) / 0.01).toInt()

    @Test fun oneRaySetsTheRightSignEitherSideOfTheSurface() {
        val v = volume()
        v.integrate(pixel(0.8), null, 1f) // surface at z = 0
        assertEquals(0f, v.valueAt(20, 22, k(0.0)), 1e-4f)
        assertEquals("2 cm in front", 0.5f, v.valueAt(20, 22, k(0.02)), 1e-3f)
        assertEquals("4 cm in front saturates", 1f, v.valueAt(20, 22, k(0.04)), 1e-3f)
        assertEquals("1 cm behind", -0.25f, v.valueAt(20, 22, k(-0.01)), 1e-3f)
        assertEquals("beyond the behind band: untouched", 0, v.weightAt(20, 22, k(-0.03)))
        assertTrue("nothing below −behindFraction", (0 until v.voxelCount).all { v.tsdf[it] >= -TsdfVolume.SCALE / 2 - 1 })
        assertEquals("beyond the front band: untouched", 0, v.weightAt(20, 22, k(0.06)))
        assertEquals("weight of a conf-255 sample at 0.8 m = round(2/0.64)", 3, v.weightAt(20, 22, k(0.0)))
        assertEquals(1, v.framesIntegrated)
        assertEquals(1L, v.samplesIntegrated)
        // +4..−2 cm (the −2 cm point sits exactly on the band edge): each grid point at most once per ray.
        assertTrue(v.voxelUpdates in 6L..7L)
        assertEquals(0.8, v.meanRangeM(), 1e-9)
        assertTrue(v.observedFraction() > 0)
    }

    @Test fun weightsSaturateAndLaterViewsStillMoveTheValue() {
        val v = volume()
        repeat(100) { v.integrate(pixel(0.8), null, 1f) }
        assertEquals(TsdfParams.MAX_WEIGHT, v.weightAt(20, 22, k(0.0)))
        // A shifted surface (1 cm nearer) still moves the saturated voxel.
        v.integrate(pixel(0.79), null, 1f)
        assertTrue(v.valueAt(20, 22, k(0.0)) < -0.001f)
        assertEquals(TsdfParams.MAX_WEIGHT, v.weightAt(20, 22, k(0.0)))
    }

    @Test fun weightFollowsConfidenceRangeAndSourceAndIsCapped() {
        val v = volume()
        v.integrate(pixel(0.8, conf = 128), null, 0.5f) // 2 × 0.5 × 0.5 / 0.64 = 0.78 → 1 (min 1)
        assertEquals(1, v.weightAt(20, 22, k(0.0)))
        val close = volume(TsdfParams(minDepthM = 0.05, maxSampleWeight = 64))
        close.integrate(pixel(0.1, camZ = 0.1), null, 1f) // 2 / 0.01 = 200 → capped at 64
        assertEquals(64, close.weightAt(20, 22, k(0.0)))
    }

    @Test fun gatesDropBadSamples() {
        val v = volume()
        v.integrate(pixel(0.8, conf = 127), null, 1f)  // below confidence 128
        v.integrate(pixel(0.0), null, 1f)              // no data
        v.integrate(pixel(0.3, camZ = 0.3), null, 1f)  // nearer than 0.35 m
        v.integrate(pixel(2.1, camZ = 2.1), null, 1f)  // further than 2.0 m
        assertEquals(0L, v.samplesIntegrated)
        assertEquals(4, v.framesIntegrated)
        assertEquals(0.0, v.observedFraction(), 0.0)
        assertEquals(0.0, v.meanRangeM(), 0.0)
        assertEquals(0, v.surfaceObservations())
    }

    @Test fun aabbPreTestDropsSamplesOutsideTheVolume() {
        val v = volume()
        v.integrate(pixel(1.5), null, 1f) // surface at z = −0.7: outside the 0.4 m volume
        assertEquals(0L, v.samplesIntegrated)
        assertEquals(0L, v.voxelUpdates)
        // Grazing case: the surface point is inside, the band leaves the grid — out-of-grid steps are skipped.
        v.integrate(pixel(0.6), null, 1f) // surface at z = 0.2, the volume's face
        assertEquals(1L, v.samplesIntegrated)
        assertTrue(v.voxelUpdates in 1..4)
    }

    @Test fun boxFrameAndCameraRotationAreApplied() {
        // Volume yawed 90° and shifted; camera looks along world −X at the volume centre.
        val yaw = Quaternion.fromAxisAngle(Vec3.UP, Math.PI / 2)
        val spec = VolumeSizing.spec(OrientedBox(Pose(Vec3(1.0, 0.0, 2.0), yaw), 0.4, 0.4, 0.4), 0.0, false)!!
        val v = TsdfVolume(spec)
        val f = DepthFrame(1, 1)
        f.intrinsics = CameraIntrinsics(100.0, 100.0, 0.0, 0.0)
        f.cameraToWorld = Pose(Vec3(1.8, 0.2, 2.0), Quaternion.fromAxisAngle(Vec3.UP, Math.PI / 2)) // looks along −X
        f.depthMm[0] = 800; f.confidence[0] = 255.toByte()
        v.integrate(f, null, 1f)
        // World (1, 0.2, 2) is the box centre column; world −X is box-local +Z (yaw 90°: local +Z → world +X).
        assertEquals(0f, v.valueAt(20, 22, 20), 1e-3f)
        assertEquals(0.5f, v.valueAt(20, 22, 22), 1e-3f)  // 2 cm towards the camera (world +X = local +Z)
        assertEquals(-0.25f, v.valueAt(20, 22, 19), 1e-3f)
    }

    @Test fun colourIsFusedOnlyWhereTheSurfaceFacesTheCamera() {
        val spec = VolumeSizing.spec(OrientedBox(Pose(Vec3(0.0, -0.2, 0.0)), 0.4, 0.4, 0.4), 0.0, false)!!
        val v = TsdfVolume(spec)
        // A wall whose front face is the plane z = 0 (world).
        val scene = listOf<Shape>(Shape.Box(OrientedBox(Pose(Vec3(0.0, -0.5, -0.05)), 3.0, 1.0, 0.1), 0xFF0000))
        SyntheticRenderer.fuse(v, scene, listOf(SyntheticRenderer.lookAt(Vec3(0.0, 0.0, 0.8), Vec3(0.0, 0.0, 0.0))))
        // Head-on: the voxel on the front face (z = 0, world) is red.
        val c = v.colourAt(20, 20, 20)
        assertTrue("colour set", c >= 0)
        assertTrue("red: ${Integer.toHexString(c)}", (c shr 16) > 200 && ((c shr 8) and 0xFF) < 60)
        assertEquals(-1, v.colourAt(0, 0, 0))
        // Grazing (75°): geometry fuses, colour does not.
        val g = TsdfVolume(spec)
        val eye = Vec3(0.0, 0.0, 0.0) + Vec3(Math.sin(Math.toRadians(75.0)), 0.0, Math.cos(Math.toRadians(75.0))) * 0.8
        SyntheticRenderer.fuse(g, scene, listOf(SyntheticRenderer.lookAt(eye, Vec3.ZERO)))
        assertTrue(g.samplesIntegrated > 0)
        var coloured = 0
        for (i in 0 until g.voxelCount) if (g.weight[i].toInt() and 0x80 != 0) coloured++
        assertEquals(0, coloured)
    }

    @Test fun repeatedColourIsAveragedAndResetClearsEverything() {
        val spec = VolumeSizing.spec(OrientedBox(Pose(Vec3(0.0, -0.2, 0.0)), 0.4, 0.4, 0.4), 0.0, false)!!
        val v = TsdfVolume(spec)
        val pose = SyntheticRenderer.lookAt(Vec3(0.0, 0.0, 0.8), Vec3.ZERO)
        val wall = OrientedBox(Pose(Vec3(0.0, -0.5, -0.05)), 3.0, 1.0, 0.1)
        SyntheticRenderer.fuse(v, listOf(Shape.Box(wall, 0xFF0000)), listOf(pose))
        SyntheticRenderer.fuse(v, listOf(Shape.Box(wall, 0x0000FF)), listOf(pose))
        val c = v.colourAt(20, 20, 20)
        assertTrue("mixed red/blue: ${Integer.toHexString(c)}", (c shr 16) in 60..200 && (c and 0xFF) in 60..200)
        assertTrue(v.surfaceObservations() >= 1)
        v.reset()
        assertEquals(0, v.framesIntegrated)
        assertEquals(0L, v.samplesIntegrated)
        assertEquals(0L, v.voxelUpdates)
        assertEquals(0.0, v.observedFraction(), 0.0)
        assertEquals(0, v.weightAt(20, 20, 20))
        assertEquals(-1, v.colourAt(20, 20, 20))
    }

    @Test fun invalidArgumentsThrow() {
        val v = volume()
        assertThrows { v.integrate(pixel(0.8), null, 0f) }
        assertThrows { v.integrate(pixel(0.8), null, Float.NaN) }
        assertThrows { v.valueAt(-1, 0, 0) }
        assertThrows { v.weightAt(0, 43, 0) }
        assertThrows { v.colourAt(0, 0, 41) }
        assertThrows { TsdfParams(minConfidence = 256) }
        assertThrows { TsdfParams(minDepthM = 2.0, maxDepthM = 1.0) }
        assertThrows { TsdfParams(weightUnit = 0.0) }
        assertThrows { TsdfParams(maxSampleWeight = 128) }
        assertThrows { TsdfParams(colourMaxAngleDeg = 0.0) }
        assertThrows { TsdfParams(behindFraction = 0.0) }
    }

    /**
     * "No per-frame allocations in the integration loop": the current thread's allocated bytes across 20
     * integrations of a full 160×120 frame stay near zero (HotSpot's per-thread allocation counter).
     */
    @Test fun integrateDoesNotAllocate() {
        val box = OrientedBox(Pose.IDENTITY, 0.6, 0.4, 0.4)
        val v = TsdfVolume(VolumeSizing.spec(box, 0.1, false)!!)
        val scene = listOf<Shape>(Shape.Box(box, 0x808080), Shape.Floor(0.0, 0x404040))
        val (frame, yuv) = SyntheticRenderer.render(scene, SyntheticRenderer.lookAt(Vec3(0.0, 0.8, 1.0), Vec3(0.0, 0.2, 0.0)))
        repeat(30) { v.integrate(frame, yuv, 1f) } // warm-up / JIT
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = mx.getThreadAllocatedBytes(id)
        repeat(20) { v.integrate(frame, yuv, 1f) }
        val allocated = mx.getThreadAllocatedBytes(id) - before
        println("ALLOC integrate ×20 (160×120 + colour): $allocated bytes")
        assertTrue("allocated $allocated bytes", allocated < 4096)
    }
}
