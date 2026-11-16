package com.spaceview.engine.recon

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * §7.1.11 engine rows, synthetic scenes: TSDF fusion → marching cubes → segmentation → extents, against analytic
 * ground truth rendered by [SyntheticRenderer]. Every scene is seeded, so results are deterministic. Each test
 * prints an `ACCURACY` line with the measured numbers.
 */
class ReconSceneTest {

    // ---- scenes --------------------------------------------------------------------------------------

    private val r = 0.25
    private val sphereCentre = Vec3(0.3, 0.8, -0.2)
    /** Measured box around the floating sphere (base under it, yawed so the box frame is not the world frame). */
    private val sphereBox = OrientedBox(Pose(sphereCentre - Vec3(0.0, r, 0.0), Quaternion.fromAxisAngle(Vec3.UP, 0.35)), 2 * r, 2 * r, 2 * r)
    private val sphereScene = listOf<Shape>(Shape.Sphere(sphereCentre, r, 0xC83228))
    private val spherePoses = SyntheticRenderer.ring(sphereCentre, 1.0, listOf(-60.0, -30.0, 0.0, 30.0, 60.0), SyntheticRenderer.azimuths(8)) +
        SyntheticRenderer.ring(sphereCentre, 1.0, listOf(-85.0, 85.0), SyntheticRenderer.azimuths(2))

    private fun boxOnFloor(yawRad: Double = 0.5, w: Double = 0.6, h: Double = 0.4, d: Double = 0.4) =
        OrientedBox(Pose(Vec3(0.2, 0.0, -0.1), Quaternion.fromAxisAngle(Vec3.UP, yawRad)), w, h, d)

    private fun boxScene(box: OrientedBox, scale: Double = 1.0): List<Shape> =
        listOf(Shape.Box(OrientedBox(box.pose, box.width * scale, box.height * scale, box.depth * scale), 0x2850C8), Shape.Floor(0.0, 0x808080))

    private fun boxPoses(box: OrientedBox, azFrom: Double = 0.0, azTo: Double = 360.0, n: Int = 12, elevations: List<Double> = listOf(15.0, 40.0, 65.0)) =
        SyntheticRenderer.ring(box.pose.transformPoint(Vec3(0.0, box.height / 2, 0.0)), 1.2, elevations, SyntheticRenderer.azimuths(n, azFrom, azTo))

    private val noisy = DepthNoiseSim(relSigma = 0.01, holeFraction = 0.10, flyingPixels = true, lowConfFraction = 0.05)

    private fun volumeFor(box: OrientedBox) = TsdfVolume(VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!)

    /** §7.1.4 steps 1–3 for an object on the floor, with the measured box as the inside test (production order). */
    private fun objectMesh(vol: TsdfVolume, floor: Boolean, box: OrientedBox? = null): TriMesh {
        var m = MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT)
        if (floor) m = MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), max(0.01, vol.spec.voxelM))
        return MeshSegmenter.keepLargestComponent(m, insideBox = box?.let { OrientedBox.local(it.width, it.height, it.depth) })
    }

    private fun sphereErrors(m: TriMesh): DoubleArray {
        val c = Vec3(0.0, r, 0.0) // sphere centre in the box frame
        var sum = 0.0
        for (i in 0 until m.vertexCount) sum += abs(MeshMetrics.vertex(m, i).distanceTo(c) - r)
        return doubleArrayOf(sum / m.vertexCount, MeshMetrics.fitSphere(m).second)
    }

    // ---- sphere --------------------------------------------------------------------------------------

    @Test fun sphereNoiseFreeIsAccurateWatertightAndOutward() {
        val vol = volumeFor(sphereBox)
        SyntheticRenderer.fuse(vol, sphereScene, spherePoses)
        val m = objectMesh(vol, floor = false, box = sphereBox)
        val v = vol.spec.voxelM
        val (meanErr, radius) = sphereErrors(m).let { it[0] to it[1] }
        val trueVol = 4.0 / 3 * PI * r * r * r
        val vol3 = MeshMetrics.signedVolume(m)
        println("ACCURACY sphere noise-free: v=%.4f m, tris=%d, mean|d−r|=%.2f mm, fitted r=%.2f mm (true 250), volume %+.2f%%, euler=%d"
            .format(v, m.triangleCount, meanErr * 1000, radius * 1000, 100 * (vol3 / trueVol - 1), MeshMetrics.euler(m)))
        assertTrue("mean |d−r| $meanErr ≤ v/2", meanErr <= 0.5 * v)
        assertEquals("fitted radius within v/2", r, radius, v / 2)
        assertTrue("watertight and consistently oriented", MeshMetrics.isClosedOriented(m))
        assertTrue("every edge shared by exactly 2 triangles", MeshMetrics.edgeUse(m).values.all { it == 2 })
        assertEquals("closed sphere: V − E + F = 2", 2, MeshMetrics.euler(m))
        assertTrue("outward (signed volume > 0)", vol3 > 0)
        assertEquals("volume within 3%", trueVol, vol3, 0.03 * trueVol)
        // Colour: the sphere is red; vertex colours come from the fused YUV.
        var red = 0
        for (i in 0 until m.vertexCount) {
            val rr = m.colours[3 * i].toInt() and 0xFF; val gg = m.colours[3 * i + 1].toInt() and 0xFF
            if (rr > 150 && gg < 90) red++
        }
        assertTrue("most vertices red: $red / ${m.vertexCount}", red > 0.9 * m.vertexCount)
    }

    @Test fun sphereWithNoiseHolesAndFlyingPixels() {
        val vol = volumeFor(sphereBox)
        SyntheticRenderer.fuse(vol, sphereScene, spherePoses, noisy, seed = 7)
        val m = objectMesh(vol, floor = false, box = sphereBox)
        val v = vol.spec.voxelM
        val (meanErr, radius) = sphereErrors(m).let { it[0] to it[1] }
        println("ACCURACY sphere noisy (1%% range, 10%% holes, flying px): mean|d−r|=%.2f mm, fitted r=%.2f mm, tris=%d, boundary edges=%d"
            .format(meanErr * 1000, radius * 1000, m.triangleCount, MeshMetrics.boundaryEdges(m)))
        assertTrue("mean |d−r| $meanErr ≤ 1 v", meanErr <= v)
        assertEquals("fitted radius within v/2", r, radius, v / 2)
    }

    // ---- box on a floor ------------------------------------------------------------------------------

    @Test fun boxOnFloorNoiseFreeExtentsWithinOneVoxel() {
        val box = boxOnFloor()
        val vol = volumeFor(box)
        SyntheticRenderer.fuse(vol, boxScene(box), boxPoses(box))
        val m = objectMesh(vol, floor = true, box = box)
        val v = vol.spec.voxelM
        val e = MeshExtent.estimate(m, OrientedBox.local(box.width, box.height, box.depth), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH)))
        println("ACCURACY box noise-free: W=%.1f H=%.1f D=%.1f mm (true 600/400/400), ±95 W=%.1f mm, tris=%d, observed=%.1f%%"
            .format(e.width.value * 1000, e.height.value * 1000, e.depth.value * 1000, e.width.bound95 * 1000, m.triangleCount, 100 * vol.observedFraction()))
        assertEquals(0.6, e.width.value, v)
        assertEquals(0.4, e.height.value, v)
        assertEquals(0.4, e.depth.value, v)
        // Floor removal: no vertex below plane + cut, and the floor slab is gone (the mesh spans the box only).
        val cut = max(0.01, v)
        for (i in 0 until m.vertexCount) assertTrue(m.positions[3 * i + 1] >= cut - 1e-6)
        val b = m.bounds()!!
        assertTrue("no floor beyond the box footprint", b[0] > -0.6 / 2 - 2 * v && b[3] < 0.6 / 2 + 2 * v)
    }

    @Test fun boxWithNoiseStaysWithinItsReportedBound() {
        val box = boxOnFloor()
        val vol = volumeFor(box)
        SyntheticRenderer.fuse(vol, boxScene(box), boxPoses(box), noisy, seed = 11)
        val m = objectMesh(vol, floor = true, box = box)
        val e = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH)))
        println("ACCURACY box noisy: W=%.1f±%.1f H=%.1f±%.1f D=%.1f±%.1f mm"
            .format(e.width.value * 1000, e.width.bound95 * 1000, e.height.value * 1000, e.height.bound95 * 1000, e.depth.value * 1000, e.depth.bound95 * 1000))
        assertEquals(0.6, e.width.value, 2 * vol.spec.voxelM)
        assertEquals(0.4, e.height.value, 2 * vol.spec.voxelM)
        assertEquals(0.4, e.depth.value, 2 * vol.spec.voxelM)
    }

    /**
     * §7.1.11: with noise, the reported ± covers the truth in ≥ 95% of 200 seeded trials (per axis), **and is not
     * absurdly wide** (review M4 MINOR 10): per axis, the mean ±95 must be ≤ 4 × the 95th-percentile error.
     *
     * Each trial draws a random yaw, a uniform VIO scale error within the DEPTH tier's ±1.5% (the scene is rendered
     * that much too large or small), 1% range noise with holes, low-confidence pixels and flying pixels, and 1 px
     * silhouette fattening (about 1 cm per side at 1.2 m, the RAW source's inflation term), so every term of the ±
     * has a counterpart in the simulated error.
     */
    @Test fun reportedBoundCoversTruthIn95PercentOf200TrialsAndIsNotOverWide() {
        val trials = 200
        val rng = Random(2026)
        val model = UncertaintyModel(DeviceTier.DEPTH)
        val sim = DepthNoiseSim(relSigma = 0.01, holeFraction = 0.10, flyingPixels = true, lowConfFraction = 0.05, fattenPx = 1)
        val inside = IntArray(3)
        val errs = Array(3) { DoubleArray(trials) }
        val meanBound = DoubleArray(3)
        val t0 = System.nanoTime()
        repeat(trials) { trial ->
            val box = boxOnFloor(yawRad = rng.nextDouble() * 2 * PI)
            val scale = 1 + (rng.nextDouble() * 2 - 1) * DeviceTier.DEPTH.scale95
            val vol = volumeFor(box)
            SyntheticRenderer.fuse(vol, boxScene(box, scale), boxPoses(box, n = 8, elevations = listOf(20.0, 50.0)), sim, seed = 1000L + trial, colour = false)
            val m = objectMesh(vol, floor = true, box = box)
            val e = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), DepthNoise.from(vol, DepthSource.RAW, model))
            val got = arrayOf(e.width, e.height, e.depth)
            val truth = doubleArrayOf(0.6, 0.4, 0.4)
            for (a in 0 until 3) {
                val err = abs(got[a].value - truth[a])
                errs[a][trial] = err
                if (err <= got[a].bound95) inside[a]++
                meanBound[a] += got[a].bound95 / trials
            }
        }
        val sec = (System.nanoTime() - t0) / 1e9
        val p95 = DoubleArray(3) { a -> errs[a].sorted()[(0.95 * (trials - 1)).toInt()] }
        val names = listOf("W", "H", "D")
        println("COVERAGE box 200 trials (%.1f s): ".format(sec) + (0 until 3).joinToString("; ") { a ->
            "%s covered %.3f, max err %.1f mm, p95 err %.1f mm, mean ±95 %.1f mm (%.2f × p95)"
                .format(names[a], inside[a] / trials.toDouble(), errs[a].max() * 1000, p95[a] * 1000, meanBound[a] * 1000, meanBound[a] / p95[a])
        })
        for (a in 0 until 3) {
            assertTrue("${names[a]} coverage ${inside[a]} / $trials", inside[a] >= 0.95 * trials)
            assertTrue("${names[a]} mean ±95 ${meanBound[a]} > 4 × p95 error ${p95[a]}", meanBound[a] <= 4 * p95[a])
        }
    }

    /** Review M4 MINOR 8: a wall 5 cm behind the object is inside the 10 cm margin and must not be kept. */
    @Test fun wallPatchBehindTheObjectIsDropped() {
        val box = boxOnFloor(yawRad = 0.0) // back face at world z = −0.3
        val wall = Shape.Box(OrientedBox(Pose(Vec3(0.2, 0.0, -0.4)), 3.0, 1.5, 0.1), 0xD0D0C0) // front at z = −0.35
        val vol = volumeFor(box)
        SyntheticRenderer.fuse(vol, boxScene(box) + wall, boxPoses(box, azFrom = -90.0, azTo = 90.0, n = 9))
        val v = vol.spec.voxelM
        val clipped = MeshSegmenter.removeBelow(MarchingCubes.extract(vol, 3), Plane(Vec3.UP, 0.0), max(0.01, v))
        fun minZ(m: TriMesh) = (0 until m.vertexCount).minOf { m.positions[3 * it + 2] }
        val areaOnly = MeshSegmenter.keepLargestComponent(clipped)
        assertTrue("the area rule alone keeps the wall (z down to ${minZ(areaOnly)})", minZ(areaOnly) < -0.24f)
        val m = MeshSegmenter.keepLargestComponent(clipped, insideBox = OrientedBox.local(0.6, 0.4, 0.4))
        val e = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH)))
        println("ACCURACY box 5 cm from a wall: area rule only D extends to z = %.1f cm; with the box rule W=%.1f H=%.1f D=%.1f mm"
            .format(minZ(areaOnly) * 100, e.width.value * 1000, e.height.value * 1000, e.depth.value * 1000))
        assertTrue("wall dropped", minZ(m) > -0.2f - 2 * v)
        assertEquals(0.4, e.depth.value, 2 * v)
        assertEquals(0.6, e.width.value, 2 * v)
    }

    /** A chair (seat, back, four 4 cm legs) in front of a wall: the legs survive, the wall goes. */
    @Test fun chairLegsSurviveAndWallGoes() {
        val base = Pose(Vec3(0.0, 0.0, 0.0))
        fun part(x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) =
            Shape.Box(OrientedBox(Pose(Vec3(x, y, z)), w, h, d), 0x8B5A2B)
        val legs = listOf(-1.0, 1.0).flatMap { sx -> listOf(-1.0, 1.0).map { sz -> part(sx * 0.2, 0.0, sz * 0.2, 0.04, 0.42, 0.04) } }
        val chair = legs + part(0.0, 0.42, 0.0, 0.45, 0.05, 0.45) + part(0.0, 0.47, -0.205, 0.45, 0.45, 0.04)
        val wall = Shape.Box(OrientedBox(Pose(Vec3(0.0, 0.0, -0.35)), 3.0, 1.5, 0.1), 0xD0D0C0) // front at z = −0.30
        val box = OrientedBox(base, 0.45, 0.92, 0.45)
        val vol = volumeFor(box)
        val poses = SyntheticRenderer.ring(Vec3(0.0, 0.46, 0.0), 1.3, listOf(10.0, 35.0, 60.0), SyntheticRenderer.azimuths(9, -90.0, 90.0))
        SyntheticRenderer.fuse(vol, chair + wall + Shape.Floor(0.0, 0x808080), poses)
        val m = objectMesh(vol, floor = true, box = box)
        // Each leg keeps geometry in its lower half (y 0.05–0.2 m) near its corner.
        for (sx in listOf(-1.0, 1.0)) for (sz in listOf(-1.0, 1.0)) {
            val n = (0 until m.vertexCount).count {
                val x = m.positions[3 * it]; val y = m.positions[3 * it + 1]; val z = m.positions[3 * it + 2]
                y in 0.05f..0.2f && abs(x - sx * 0.2) < 0.05 && abs(z - sz * 0.2) < 0.05
            }
            assertTrue("leg ($sx, $sz) has $n vertices", n >= 10)
        }
        val minZ = (0 until m.vertexCount).minOf { m.positions[3 * it + 2] }
        assertTrue("wall dropped (min z $minZ)", minZ > -0.27f)
    }

    @Test fun partialCoverageGivesAnOpenMeshThatIsNeverInflated() {
        val box = boxOnFloor()
        val vol = volumeFor(box)
        // Only the front half: azimuths −60°..60° around the box centre (the back face is never seen).
        SyntheticRenderer.fuse(vol, boxScene(box), boxPoses(box, azFrom = -60.0, azTo = 60.0, n = 7))
        val m = objectMesh(vol, floor = true, box = box)
        val v = vol.spec.voxelM
        val e = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH)))
        val boundary = MeshMetrics.boundaryEdges(m)
        println("ACCURACY box front-half only: W=%.1f H=%.1f D=%.1f mm, boundary edges=%d, observed=%.1f%%"
            .format(e.width.value * 1000, e.height.value * 1000, e.depth.value * 1000, boundary, 100 * vol.observedFraction()))
        assertTrue("open mesh where unseen", boundary > 0)
        assertTrue(m.triangleCount > 0)
        assertEquals(0.4, e.height.value, v)
        assertTrue("width never inflated", e.width.value <= 0.6 + v)
        assertTrue("depth never inflated", e.depth.value <= 0.4 + v)
    }

    @Test fun floatingFragmentIsDroppedAndObjectKept() {
        val box = boxOnFloor()
        val cubeCentre = box.pose.transformPoint(Vec3(0.37, 0.2, 0.0)) // 5.5 cm beside the box, inside the margin
        val cube = Shape.Box(OrientedBox(Pose(cubeCentre - Vec3(0.0, 0.015, 0.0), box.pose.rotation), 0.03, 0.03, 0.03), 0xFFFFFF)
        val vol = volumeFor(box)
        SyntheticRenderer.fuse(vol, boxScene(box) + cube, boxPoses(box))
        val raw = MeshSegmenter.removeBelow(MarchingCubes.extract(vol, 3), Plane(Vec3.UP, 0.0), 0.01)
        fun maxX(m: TriMesh) = (0 until m.vertexCount).maxOf { m.positions[3 * it] }
        assertTrue("the fragment was reconstructed (x up to ${maxX(raw)})", maxX(raw) > 0.34f)
        val kept = MeshSegmenter.keepLargestComponent(raw)
        assertTrue("fragment dropped", maxX(kept) < 0.32f)
        assertTrue("object kept", kept.triangleCount > 0.9 * raw.triangleCount)
    }

    @Test fun cropRemovesTrianglesOutsideAndShrinksExtents() {
        val box = boxOnFloor()
        val vol = volumeFor(box)
        SyntheticRenderer.fuse(vol, boxScene(box), boxPoses(box))
        val m = objectMesh(vol, floor = true, box = box)
        val cropBox = OrientedBox.local(0.6, 0.4, 0.4).trimmed(left = 0.0, right = 0.1, bottom = 0.0, top = 0.0, back = 0.0, front = 0.0)
        val cropped = MeshSegmenter.crop(m, OrientedBox.local(0.8, 0.6, 0.6).trimmed(0.1, 0.2, 0.0, 0.0, 0.0, 0.0))
        for (i in 0 until cropped.vertexCount) assertTrue(cropped.positions[3 * i] <= 0.2f + 1e-6f)
        val noise = DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH))
        val e = MeshExtent.estimate(cropped, cropBox, noise)
        assertEquals("width shrinks to the crop", 0.5, e.width.value, vol.spec.voxelM)
        assertTrue(cropped.triangleCount < m.triangleCount)
    }

    @Test fun emptyAndDegenerateInputDoNotCrash() {
        val vol = volumeFor(boxOnFloor())
        // Nothing fused.
        val m = MarchingCubes.extract(vol, 3)
        assertTrue(m.isEmpty)
        assertTrue(MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), 0.01).isEmpty)
        assertTrue(MeshSegmenter.keepLargestComponent(m).isEmpty)
        assertTrue(VertexClusterDecimator.decimate(m, 100).isEmpty)
        val e = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel()))
        assertTrue(e.isEmpty)
        assertEquals(0.0, vol.observedFraction(), 0.0)
        // All depth outside the volume (camera looking away): nothing integrates.
        val frame = DepthFrame(SyntheticRenderer.W, SyntheticRenderer.H)
        SyntheticRenderer.render(boxScene(boxOnFloor()), SyntheticRenderer.lookAt(Vec3(5.0, 1.0, 5.0), Vec3(10.0, 0.0, 10.0)), frame = frame, colour = null)
        vol.integrate(frame, null, 1f)
        assertEquals(0L, vol.samplesIntegrated)
        assertEquals(1, vol.framesIntegrated)
    }
}
