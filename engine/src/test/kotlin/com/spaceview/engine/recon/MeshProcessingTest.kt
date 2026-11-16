package com.spaceview.engine.recon

import com.spaceview.engine.BoxMeasurement
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/** Hand-built meshes for the unit tests. */
internal object Meshes {
    /** Axis-aligned closed box mesh (12 triangles, outward) from [min] to [max], one colour. */
    fun box(min: Vec3, max: Vec3, rgb: Int = 0x808080, stride: Int = 3): TriMesh {
        val p = FloatArray(24)
        for (c in 0 until 8) {
            p[3 * c] = (if (c and 1 == 0) min.x else max.x).toFloat()
            p[3 * c + 1] = (if (c and 2 == 0) min.y else max.y).toFloat()
            p[3 * c + 2] = (if (c and 4 == 0) min.z else max.z).toFloat()
        }
        val idx = intArrayOf(
            0, 4, 6, 0, 6, 2, 1, 3, 7, 1, 7, 5, // x faces
            0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, // y faces
            0, 2, 3, 0, 3, 1, 4, 5, 7, 4, 7, 6, // z faces
        )
        val col = ByteArray(stride * 8) { i -> when (i % stride) { 0 -> (rgb shr 16).toByte(); 1 -> (rgb shr 8).toByte(); 2 -> rgb.toByte(); else -> 0xFF.toByte() } }
        return TriMesh(p, col, idx)
    }

    fun merge(a: TriMesh, b: TriMesh): TriMesh {
        val off = a.vertexCount
        return TriMesh(a.positions + b.positions, a.colours + b.colours, a.indices + b.indices.map { it + off })
    }

    /** Fused, segmented mesh of a box on a floor (the ReconSceneTest pipeline), for decimation tests. */
    fun fusedBox(w: Double, h: Double, d: Double, n: Int = 12, yaw: Double = 0.4): Pair<TriMesh, TsdfVolume> {
        val box = OrientedBox(Pose(Vec3(0.0, 0.0, 0.0), Quaternion.fromAxisAngle(Vec3.UP, yaw)), w, h, d)
        val vol = TsdfVolume(VolumeSizing.spec(box, 0.1, false)!!)
        val scene = listOf(Shape.Box(box, 0x3060A0), Shape.Floor(0.0, 0x808080))
        val dist = max(1.2, 0.75 * max(w, d))
        SyntheticRenderer.fuse(vol, scene, SyntheticRenderer.ring(Vec3(0.0, h / 2, 0.0), dist, listOf(15.0, 40.0, 65.0), SyntheticRenderer.azimuths(n)))
        var m = MarchingCubes.extract(vol, 3)
        m = MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), max(0.01, vol.spec.voxelM))
        return MeshSegmenter.keepLargestComponent(m, insideBox = OrientedBox.local(w, h, d)) to vol
    }
}

class MeshSegmenterTest {
    private val big = Meshes.box(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0), 0xFF0000)
    private val small = Meshes.box(Vec3(2.0, 0.0, 0.0), Vec3(2.1, 0.1, 0.1), 0x00FF00)

    @Test fun removeBelowClipsExactlyAtTheCut() {
        val m = Meshes.merge(big, small)
        val cut = MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), 0.05)
        for (i in 0 until cut.vertexCount) assertTrue(cut.positions[3 * i + 1] >= 0.05f - 1e-6f)
        // Area: big top 1 + 4 sides × 0.95, small top 0.01 + 4 sides × 0.1 × 0.05.
        var area = 0.0
        for (t in 0 until cut.triangleCount) area += MeshSegmenter.triangleArea(cut.positions, cut.indices[3 * t], cut.indices[3 * t + 1], cut.indices[3 * t + 2])
        assertEquals(1.0 + 4 * 0.95 + 0.01 + 4 * 0.005, area, 1e-5)
        // The new rim is welded (shared cut vertices: every rim vertex is used by ≥ 2 triangles) and sits on the cut.
        val boundary = MeshMetrics.edgeUse(cut).filterValues { it == 1 }.keys
        assertEquals("8 rim edges per box side ring", 16, boundary.size)
        for (k in boundary) {
            assertEquals(0.05f, cut.positions[3 * (k ushr 32).toInt() + 1], 1e-6f)
            assertEquals(0.05f, cut.positions[3 * (k and 0xFFFFFFFFL).toInt() + 1], 1e-6f)
        }
        assertTrue("outward orientation kept", MeshMetrics.signedVolume(cut) > 0)
        assertEquals(0xFF.toByte(), cut.colours[0])
        assertTrue(MeshSegmenter.removeBelow(m, Plane(Vec3.UP, -2.0), 0.0).isEmpty)
        assertEquals(m.triangleCount, MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), 0.0).triangleCount)
        assertThrows { MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), Double.NaN) }
        // Colours interpolate along a cut edge, in every layout.
        val grad = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), byteArrayOf(0, 0, 0, 0, -56, -56, -56, -56, -56, -56, -56, -56), intArrayOf(0, 1, 2))
        val g = MeshSegmenter.removeBelow(grad, Plane(Vec3.UP, 0.0), 0.5)
        assertEquals(4, g.colourStride)
        assertEquals(100, g.colours[4 * (g.vertexCount - 1)].toInt() and 0xFF)
        assertTrue(MeshSegmenter.removeBelow(TriMesh(grad.positions, ByteArray(0), grad.indices), Plane(Vec3.UP, 0.0), 0.5).colours.isEmpty())
    }

    @Test fun keepLargestComponentByAreaWithKeepFraction() {
        val m = Meshes.merge(small, big) // the small one first: order must not decide
        val kept = MeshSegmenter.keepLargestComponent(m)
        assertEquals(12, kept.triangleCount)
        assertEquals(0xFF.toByte(), kept.colours[0]) // red = big
        // keepFraction 0 keeps everything; a medium piece above 5% survives the default.
        assertEquals(24, MeshSegmenter.keepLargestComponent(m, 0.0).triangleCount)
        val medium = Meshes.box(Vec3(3.0, 0.0, 0.0), Vec3(3.5, 0.5, 0.5))
        assertEquals(24, MeshSegmenter.keepLargestComponent(Meshes.merge(big, medium)).triangleCount)
        assertEquals(12, MeshSegmenter.keepLargestComponent(Meshes.merge(big, medium), 1.0).triangleCount)
        assertThrows { MeshSegmenter.keepLargestComponent(m, 1.5) }
    }

    @Test fun insideBoxDropsPiecesOutsideTheMeasuredBoxEvenWhenLarger() {
        val measured = OrientedBox(Pose(Vec3(0.5, 0.0, 0.5)), 1.0, 1.0, 1.0) // exactly the big box
        val wall = Meshes.box(Vec3(1.2, 0.0, -1.0), Vec3(1.25, 2.0, 2.0), 0x0000FF) // larger than the object
        val m = Meshes.merge(big, wall)
        // Area alone keeps the wall (it is the largest piece).
        assertEquals(0x00.toByte(), MeshSegmenter.keepLargestComponent(m, 1.0).colours[0])
        val kept = MeshSegmenter.keepLargestComponent(m, insideBox = measured)
        assertEquals(12, kept.triangleCount)
        assertEquals(0xFF.toByte(), kept.colours[0])
        // A piece mostly outside (only its first 13 cm of 60 cm inside the grown box) goes too; one mostly inside stays.
        val straddle = Meshes.box(Vec3(0.9, 0.0, 0.0), Vec3(1.5, 0.5, 0.5))
        assertEquals(12, MeshSegmenter.keepLargestComponent(Meshes.merge(big, straddle), insideBox = measured).triangleCount)
        val mostlyIn = Meshes.box(Vec3(0.2, 0.2, 0.2), Vec3(1.1, 0.8, 0.8))
        assertEquals(24, MeshSegmenter.keepLargestComponent(Meshes.merge(big, mostlyIn), 0.0, measured).triangleCount)
        // Nothing eligible: empty, never the wall.
        assertTrue(MeshSegmenter.keepLargestComponent(wall, insideBox = measured).isEmpty)
        // Degenerate (zero-area) pieces use triangle counts for the inside share too.
        val dots = TriMesh(FloatArray(9) { 0.5f }, ByteArray(0), intArrayOf(0, 1, 2))
        assertEquals(1, MeshSegmenter.keepLargestComponent(dots, insideBox = measured).triangleCount)
    }

    @Test fun degenerateTrianglesFallBackToTriangleCounts() {
        // Two zero-area components: 2 triangles vs 1 triangle.
        val p = FloatArray(3 * 6) // all vertices at the origin
        val m = TriMesh(p, ByteArray(0), intArrayOf(0, 1, 2, 0, 2, 1, 3, 4, 5))
        val kept = MeshSegmenter.keepLargestComponent(m, 1.0)
        assertEquals(2, kept.triangleCount)
        assertEquals(0, kept.colours.size)
    }

    @Test fun cropKeepsTrianglesFullyInsideAnOrientedBox() {
        val m = Meshes.merge(big, small)
        val crop = MeshSegmenter.crop(m, OrientedBox(Pose(Vec3(2.05, -0.5, 0.05)), 0.5, 1.0, 0.5))
        assertEquals(12, crop.triangleCount)
        assertEquals(0x00.toByte(), crop.colours[0])
        // Rotated crop box containing nothing.
        val none = MeshSegmenter.crop(m, OrientedBox(Pose(Vec3(10.0, 0.0, 0.0), Quaternion.fromAxisAngle(Vec3.UP, 0.7)), 1.0, 1.0, 1.0))
        assertTrue(none.isEmpty)
        assertTrue(MeshSegmenter.crop(TriMesh.EMPTY, OrientedBox.local(1.0, 1.0, 1.0)).isEmpty)
    }

    @Test fun malformedMeshesAreRejected() {
        assertThrows { MeshSegmenter.crop(TriMesh(FloatArray(4), ByteArray(0), IntArray(0)), OrientedBox.local(1.0, 1.0, 1.0)) }
        assertThrows { MeshSegmenter.keepLargestComponent(TriMesh(FloatArray(9), ByteArray(0), intArrayOf(0, 1))) }
        assertThrows { MeshSegmenter.keepLargestComponent(TriMesh(FloatArray(9), ByteArray(5), intArrayOf(0, 1, 2))) }
        assertThrows { MeshSegmenter.keepLargestComponent(TriMesh(FloatArray(9), ByteArray(0), intArrayOf(0, 1, 3))) }
    }
}

class VertexClusterDecimatorTest {
    private val fused by lazy { Meshes.fusedBox(0.6, 0.4, 0.4) }

    private fun bbox(m: TriMesh) = m.bounds()!!

    /**
     * Two-sided vertex Hausdorff bound, checked exhaustively and guaranteed by construction (review M4 MINOR 10):
     * every input vertex whose cell survives lies within one cell diagonal of its output vertex, and every output
     * vertex lies within one cell diagonal of an input vertex of its cell (it is clamped to their bounding box).
     * Vertices whose whole cell collapsed have no bound; their share is reported and must stay small.
     */
    @Test fun reachesTargetPreservesBoundingBoxWithin5mmAndHausdorffWithinOneCell() {
        val (m, _) = fused
        val target = 2000
        val r = VertexClusterDecimator.decimateWithCell(m, target)
        val d = r.mesh
        assertTrue("count ${d.triangleCount} ≤ $target", d.triangleCount <= target)
        assertTrue("not over-decimated", d.triangleCount > target / 4)
        val b0 = bbox(m); val b1 = bbox(d)
        var worst = 0.0
        for (a in 0 until 6) worst = max(worst, Math.abs((b0[a] - b1[a]).toDouble()))
        val map = r.vertexMap!!
        val bound = r.cellM * sqrt(3.0) + 1e-5
        val nearest = DoubleArray(d.vertexCount) { Double.POSITIVE_INFINITY }
        var vanished = 0
        var worstIn = 0.0
        for (v in 0 until m.vertexCount) {
            val o = map[v]
            if (o < 0) { vanished++; continue }
            val dist = MeshMetrics.vertex(m, v).distanceTo(MeshMetrics.vertex(d, o))
            worstIn = max(worstIn, dist)
            nearest[o] = minOf(nearest[o], dist)
        }
        println("DECIMATE box %d → %d tris, cell %.1f mm, bbox change %.2f mm (per bound: %s); vertex Hausdorff %.1f mm ≤ %.1f mm; %.2f%% of vertices in collapsed cells"
            .format(m.triangleCount, d.triangleCount, r.cellM * 1000, worst * 1000, (0 until 6).joinToString { "%.2f".format((b1[it] - b0[it]) * 1000) },
                max(worstIn, nearest.max()) * 1000, bound * 1000, 100.0 * vanished / m.vertexCount))
        assertTrue("bounding box within 5 mm (was $worst)", worst <= 0.005)
        // Output never sticks out of the input box (representatives are clamped to their cell's vertices).
        for (a in 0 until 3) { assertTrue(b1[a] >= b0[a] - 1e-6f); assertTrue(b1[a + 3] <= b0[a + 3] + 1e-6f) }
        assertTrue("input → output ${worstIn}", worstIn <= bound)
        for (o in 0 until d.vertexCount) assertTrue("output vertex $o → input ${nearest[o]}", nearest[o] <= bound)
        assertTrue("collapsed cells hold $vanished of ${m.vertexCount} vertices", vanished <= m.vertexCount / 100)
        // Extents (MeshExtent) preserved within one cell.
        val noise = DepthNoise(DepthSource.RAW, 1.0, 10, 0.01, 0.0, 0.0)
        val e0 = MeshExtent.estimate(m, OrientedBox.local(0.6, 0.4, 0.4), noise)
        val e1 = MeshExtent.estimate(d, OrientedBox.local(0.6, 0.4, 0.4), noise)
        assertEquals(e0.width.value, e1.width.value, r.cellM)
        assertEquals(e0.height.value, e1.height.value, r.cellM)
        assertEquals(e0.depth.value, e1.depth.value, r.cellM)
    }

    /** Sofa-size meshes at two yaws and three budget targets: bounding box within 5 mm, margin printed. */
    @Test fun sofaSizeMeshDecimatesWithinBudgetAndIsDeterministic() {
        var worstAll = 0.0
        for (yaw in listOf(0.4, 1.1)) {
            val (m, vol) = Meshes.fusedBox(2.2, 0.9, 1.0, n = 16, yaw = yaw)
            assertTrue(m.triangleCount > VertexClusterDecimator.MAX_TRIANGLES)
            for (target in listOf(15_000, VertexClusterDecimator.DEFAULT_TARGET_TRIANGLES, VertexClusterDecimator.MAX_TRIANGLES)) {
                val t0 = System.nanoTime()
                val r = VertexClusterDecimator.decimateWithCell(m, target)
                val ms = (System.nanoTime() - t0) / 1e6
                val d1 = r.mesh
                assertTrue(d1.triangleCount <= target)
                val b0 = bbox(m); val b1 = bbox(d1)
                val worst = (0 until 6).maxOf { Math.abs((b0[it] - b1[it]).toDouble()) }
                worstAll = max(worstAll, worst)
                println("DECIMATE sofa yaw %.1f: %d → %d tris (target %d, cell %.1f mm, voxel %.2f cm) in %.0f ms; bbox change %.2f mm"
                    .format(yaw, m.triangleCount, d1.triangleCount, target, r.cellM * 1000, vol.spec.voxelM * 100, ms, worst * 1000))
                assertTrue("bbox change $worst > 5 mm", worst <= 0.005)
                if (target == VertexClusterDecimator.DEFAULT_TARGET_TRIANGLES) {
                    val d2 = VertexClusterDecimator.decimate(m, target)
                    assertArrayEquals(d1.positions, d2.positions, 0f)
                    assertArrayEquals(d1.indices, d2.indices)
                    assertArrayEquals(d1.colours, d2.colours)
                }
            }
        }
        println("DECIMATE sofa worst bbox change %.2f mm (margin %.2f mm to the 5 mm limit)".format(worstAll * 1000, 5 - worstAll * 1000))
    }

    @Test fun smallInputsColourLayoutsAndDegenerateCases() {
        val box = Meshes.box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0), 0x102030, stride = 4)
        assertSame(box, VertexClusterDecimator.decimate(box, 12))
        assertTrue(VertexClusterDecimator.decimate(box, 0).isEmpty)
        // A cube in 12 triangles to ≤ 11: everything merges. RGBA layout is preserved on non-empty output.
        val two = Meshes.merge(box, Meshes.box(Vec3(3.0, 0.0, 0.0), Vec3(4.0, 1.0, 1.0), 0x102030, stride = 4))
        val d = VertexClusterDecimator.decimate(two, 23)
        assertTrue(d.triangleCount <= 23)
        if (!d.isEmpty) { assertEquals(4, d.colourStride); assertEquals(0x10.toByte(), d.colours[0]) }
        // No colour.
        val bare = TriMesh(box.positions, ByteArray(0), box.indices)
        val db = VertexClusterDecimator.decimate(Meshes.merge(bare, TriMesh(two.positions.copyOfRange(24, 48), ByteArray(0), box.indices)), 20)
        assertEquals(0, db.colours.size)
        // All vertices coincide → nothing to keep.
        assertTrue(VertexClusterDecimator.decimate(TriMesh(FloatArray(9), ByteArray(0), intArrayOf(0, 1, 2, 0, 2, 1)), 1).isEmpty)
        assertThrows { VertexClusterDecimator.decimate(box, -1) }
    }

    @Test fun longIntMapStoresAndClears() {
        val m = LongIntMap(1) // grows on demand
        for (i in 0 until 50) m.put(i * 1_000_003L, i)
        for (i in 0 until 50) assertEquals(i, m.get(i * 1_000_003L))
        assertEquals(-1, m.get(7L))
        assertEquals(50, m.size)
        m.put(0L, 7) // overwrite keeps the size
        assertEquals(7, m.get(0L)); assertEquals(50, m.size)
        m.clear()
        assertEquals(-1, m.get(0L)); assertEquals(0, m.size)
    }
}

class MeshExtentTest {
    private val noise = DepthNoise(DepthSource.RAW, meanRangeM = 1.0, observations = 20, voxelM = 0.01, scaleSigmaRel = 0.0, absFloor95M = 0.0)

    /** A dense point cloud on a box surface plus [outliers] far-away vertices, as degenerate triangles. */
    private fun cloud(outliers: Int): TriMesh {
        val rng = Random(5)
        val n = 5000
        val p = FloatArray(3 * (n + outliers))
        for (i in 0 until n) {
            val face = rng.nextInt(5)
            var x = rng.nextDouble() * 0.6 - 0.3; var y = rng.nextDouble() * 0.4; var z = rng.nextDouble() * 0.4 - 0.2
            when (face) { 0 -> x = -0.3; 1 -> x = 0.3; 2 -> z = -0.2; 3 -> z = 0.2; else -> y = 0.4 }
            p[3 * i] = x.toFloat(); p[3 * i + 1] = y.toFloat(); p[3 * i + 2] = z.toFloat()
        }
        for (i in n until n + outliers) { p[3 * i] = 5f; p[3 * i + 1] = 5f; p[3 * i + 2] = -5f }
        val total = n + outliers
        val idx = IntArray(3 * (total / 3)) { it }
        return TriMesh(p, ByteArray(0), idx)
    }

    @Test fun percentilesIgnore02PercentOutliers() {
        val e = MeshExtent.estimate(cloud(10), OrientedBox.local(0.6, 0.4, 0.4), noise) // 10 / 5010 = 0.2%
        assertEquals(0.6, e.width.value, 0.002)
        assertEquals(0.4, e.height.value, 0.002)
        assertEquals(0.4, e.depth.value, 0.002)
    }

    @Test fun boundFollowsTheSection715Formula() {
        val e = MeshExtent.estimate(cloud(0), OrientedBox.local(0.6, 0.4, 0.4), noise)
        // σ_face² = (0.015·1²)²/10 + 0.01²/12; σ_ext = √(2σ_face²); ±95 = 1.96 σ_ext + 2 × 1 cm inflation.
        val face2 = 0.015 * 0.015 / 10 + 0.0001 / 12
        assertEquals(1.96 * sqrt(2 * face2) + 0.02, e.width.bound95, 1e-9)
        assertEquals(1.96 * sqrt(2 * face2), e.height.bound95, 1e-9) // no inflation on H (research §7.6)
        // The scale term acts on the length (rel · L); the tier floor applies last.
        val withScale = DepthNoise(DepthSource.SMOOTHED, 0.5, 0, 0.02, 0.01, 0.5)
        val f = MeshExtent.estimate(cloud(0), OrientedBox.local(0.6, 0.4, 0.4), withScale)
        assertEquals("floor wins", 0.5, f.width.bound95, 1e-9)
        val g = MeshExtent.estimate(cloud(0), OrientedBox.local(0.6, 0.4, 0.4), DepthNoise(DepthSource.HARDWARE, 0.5, 3, 0.02, 0.01, 0.0))
        val sd = 0.005 * 0.25
        val expect = 1.96 * sqrt(2 * (sd * sd / 3 + 0.0004 / 12) + (0.01 * g.width.value).let { it * it }) + 0.01
        assertEquals(expect, g.width.bound95, 1e-9)
    }

    @Test fun rotatedFrameAndMeshBelowThePlane() {
        val q = Quaternion.fromAxisAngle(Vec3.UP, 0.6)
        val frame = OrientedBox(Pose(Vec3(1.0, -0.1, 2.0), q), 0.6, 0.4, 0.4)
        val src = cloud(0)
        val p = FloatArray(src.positions.size)
        for (i in 0 until src.vertexCount) {
            val w = frame.pose.transformPoint(MeshMetrics.vertex(src, i))
            p[3 * i] = w.x.toFloat(); p[3 * i + 1] = w.y.toFloat(); p[3 * i + 2] = w.z.toFloat()
        }
        val e = MeshExtent.estimate(TriMesh(p, ByteArray(0), src.indices), frame, noise)
        assertEquals(0.6, e.width.value, 0.003)
        assertEquals(0.4, e.depth.value, 0.003)
        // Frame raised 5 cm: the mesh now reaches below its base, so H uses the mesh's own low percentile.
        val raised = OrientedBox(Pose(Vec3(1.0, -0.05, 2.0), q), 0.6, 0.4, 0.4)
        assertEquals(0.4, MeshExtent.estimate(TriMesh(p, ByteArray(0), src.indices), raised, noise).height.value, 0.003)
    }

    @Test fun crossCheckAgreesWithinToleranceAndFlagsRough() {
        val e = MeshExtent.estimate(cloud(0), OrientedBox.local(0.6, 0.4, 0.4), noise)
        fun box(w: Double, h: Double, d: Double) = BoxMeasurement(Measurement(w, 0.005), Measurement(h, 0.005), Measurement(d, 0.005), Measurement(w * h * d, 0.0))
        val ok = MeshExtent.crossCheck(e, box(0.61, 0.39, 0.41))
        assertTrue(ok.agrees)
        assertEquals(MeshBadge.APPROXIMATE, ok.badge)
        assertEquals(0.6 - 0.61, ok.diffW, 0.003)
        assertTrue(ok.tolW >= MeshExtent.CROSS_CHECK_MIN_M)
        val bad = MeshExtent.crossCheck(e, box(0.6, 0.4, 0.55))
        assertFalse(bad.agrees)
        assertEquals(MeshBadge.ROUGH, bad.badge)
        assertEquals(2, bad.worstAxis)
        assertEquals(1, MeshExtent.crossCheck(e, box(0.6, 0.6, 0.4)).worstAxis)
        assertEquals(0, MeshExtent.crossCheck(e, box(0.9, 0.4, 0.4)).worstAxis)
        // Large objects use 3%: 2 m ± 3% = 6 cm tolerance.
        val sofa = BoxMeasurement(Measurement(2.0, 0.0), Measurement(0.4, 0.0), Measurement(0.4, 0.0), Measurement(0.3, 0.0))
        val big = Extent3(Measurement(2.055, 0.0), e.height, e.depth, 100)
        assertTrue(MeshExtent.crossCheck(big, sofa).agrees)
        // An empty mesh never agrees.
        val empty = MeshExtent.estimate(TriMesh.EMPTY, OrientedBox.local(1.0, 1.0, 1.0), noise)
        assertTrue(empty.isEmpty)
        assertFalse(MeshExtent.crossCheck(empty, box(0.0001, 0.0001, 0.0001)).agrees)
        assertTrue(empty.toString().contains("n=0"))
    }

    @Test fun percentileEdges() {
        assertEquals(3.0, MeshExtent.percentile(doubleArrayOf(3.0), 0.5), 0.0)
        assertEquals(2.0, MeshExtent.percentile(doubleArrayOf(1.0, 2.0), 1.0), 0.0)
        assertEquals(1.5, MeshExtent.percentile(doubleArrayOf(1.0, 2.0), 0.5), 1e-12)
    }
}

class ReconTypesTest {
    @Test fun triMeshHelpers() {
        val m = Meshes.box(Vec3(-1.0, 0.0, 2.0), Vec3(1.0, 3.0, 4.0))
        assertArrayEquals(floatArrayOf(-1f, 0f, 2f, 1f, 3f, 4f), m.bounds(), 0f)
        assertNull(TriMesh.EMPTY.bounds())
        assertEquals(3, m.colourStride)
        assertEquals(4, Meshes.box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0), stride = 4).colourStride)
        assertEquals(0, TriMesh(m.positions, ByteArray(0), m.indices).colourStride)
        assertEquals(-1, TriMesh(m.positions, ByteArray(5), m.indices).colourStride)
        assertTrue(TriMesh.EMPTY.isEmpty)
        assertThrows { TriMesh(FloatArray(9), ByteArray(0), intArrayOf(0, 1, -1)).requireWellFormed() }
        assertTrue(m.subset(BooleanArray(12)).isEmpty)
    }

    @Test fun orientedBoxContainsLocalAndTrim() {
        val b = OrientedBox(Pose(Vec3(1.0, 0.0, 0.0), Quaternion.fromAxisAngle(Vec3.UP, Math.PI / 2)), 2.0, 1.0, 0.5)
        // Local +X (width) is world −Z after a +90° yaw.
        assertTrue(b.contains(1.0, 0.5, -0.99))
        assertFalse(b.contains(1.0, 0.5, -1.01))
        assertTrue(b.contains(1.24, 0.5, 0.0))
        assertFalse(b.contains(1.26, 0.5, 0.0))
        assertFalse(b.contains(1.0, -0.01, 0.0))
        val out = DoubleArray(3)
        b.toLocal(1.0, 0.2, -0.5, out)
        assertArrayEquals(doubleArrayOf(0.5, 0.2, 0.0), out, 1e-12)
        val t = OrientedBox.local(1.0, 1.0, 1.0).trimmed(0.1, 0.2, 0.05, 0.15, 0.3, 0.0)
        assertEquals(0.7, t.width, 1e-12); assertEquals(0.8, t.height, 1e-12); assertEquals(0.7, t.depth, 1e-12)
        assertTrue(t.contains(-0.39, 0.06, -0.19)); assertFalse(t.contains(-0.41, 0.5, 0.0)); assertFalse(t.contains(0.0, 0.5, -0.21))
        assertTrue(t.toString().startsWith("OrientedBox"))
        assertThrows { OrientedBox.local(0.0, 1.0, 1.0) }
        assertThrows { OrientedBox.local(1.0, Double.NaN, 1.0) }
        assertThrows { OrientedBox.local(1.0, 1.0, 1.0).trimmed(0.6, 0.6, 0.0, 0.0, 0.0, 0.0) }
    }

    @Test fun sortLongsMatchesTheJdkSortOnHardInputs() {
        val rng = Random(17)
        val cases = listOf(
            LongArray(5000) { rng.nextLong() and Long.MAX_VALUE },          // random
            LongArray(5000) { it.toLong() },                                  // sorted
            LongArray(5000) { (5000 - it).toLong() },                         // reversed
            LongArray(5000) { 7L },                                           // all equal
            LongArray(5000) { (it % 3).toLong() },                            // few distinct
            LongArray(5000) { if (it % 2 == 0) it.toLong() else (5000 - it).toLong() }, // organ-pipe-ish
            LongArray(0), LongArray(1) { 3L }, LongArray(23) { rng.nextInt(10).toLong() },
        )
        for (c in cases) {
            val mine = c.copyOf(); val jdk = c.copyOf()
            ReconMath.sortLongs(mine, 0, mine.size); java.util.Arrays.sort(jdk)
            assertArrayEquals(jdk, mine)
        }
        // Sub-range only; and the heapsort fallback (depth limit 0) sorts too.
        val a = longArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        ReconMath.sortLongs(a, 2, 8)
        assertArrayEquals(longArrayOf(9, 8, 2, 3, 4, 5, 6, 7, 1, 0), a)
        val big = LongArray(300) { (it * 7919L) % 301 }
        val ref = big.copyOf().also { java.util.Arrays.sort(it) }
        ReconMath.introsort(big, 0, big.size, 0)
        assertArrayEquals(ref, big)
    }

    @Test fun symmetricEigenDiagonalises() {
        val m = DoubleArray(9); val v = DoubleArray(9)
        val a = doubleArrayOf(4.0, 1.0, -2.0, 3.0, 0.5, 2.0) // a00 a01 a02 a11 a12 a22
        ReconMath.symmetricEigen(a[0], a[1], a[2], a[3], a[4], a[5], m, v)
        val full = arrayOf(doubleArrayOf(a[0], a[1], a[2]), doubleArrayOf(a[1], a[3], a[4]), doubleArrayOf(a[2], a[4], a[5]))
        for (i in 0 until 3) {
            val l = m[4 * i]
            val e = doubleArrayOf(v[i], v[3 + i], v[6 + i])
            for (r in 0 until 3) assertEquals(l * e[r], full[r][0] * e[0] + full[r][1] * e[1] + full[r][2] * e[2], 1e-9)
            assertEquals(1.0, sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]), 1e-12)
        }
        assertEquals(a[0] + a[3] + a[5], m[0] + m[4] + m[8], 1e-9) // trace preserved
        // Already diagonal: returns at once with identity vectors.
        ReconMath.symmetricEigen(1.0, 0.0, 0.0, 2.0, 0.0, 3.0, m, v)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), v, 0.0)
    }

    @Test fun rotationMatrixMatchesQuaternionRotate() {
        val q = Quaternion(0.3, -0.5, 0.2, 0.8)
        val r = ReconMath.rotation(q)
        val v = Vec3(0.7, -1.2, 2.5)
        val e = q.rotate(v)
        assertEquals(e.x, r[0] * v.x + r[1] * v.y + r[2] * v.z, 1e-12)
        assertEquals(e.y, r[3] * v.x + r[4] * v.y + r[5] * v.z, 1e-12)
        assertEquals(e.z, r[6] * v.x + r[7] * v.y + r[8] * v.z, 1e-12)
    }

    @Test fun framesValidateTheirLayout() {
        val f = DepthFrame(4, 2)
        assertEquals(8, f.depthMm.size)
        f.intrinsics = CameraIntrinsics(2.0, 2.0, 1.5, 0.5); f.timestampNs = 5
        assertEquals(5L, f.timestampNs)
        assertThrows { DepthFrame(0, 2) }
        assertThrows { DepthFrame(2, 2, ShortArray(3)) }
        assertThrows { DepthFrame(2, 2, ShortArray(4), ByteArray(3)) }
        assertEquals(1, YuvFrame(2, 2).u.size)
        assertThrows { YuvFrame(3, 2) }
        assertThrows { YuvFrame(2, 2, ByteArray(3)) }
        assertThrows { YuvFrame(2, 2, ByteArray(4), ByteArray(2)) }
    }

    @Test fun depthNoiseValidatesAndDerivesFromAVolume() {
        assertEquals(0.015 * 4, DepthNoise(DepthSource.RAW, 2.0, 1, 0.01, 0.0, 0.0).sigmaDepthM, 1e-12)
        assertThrows { DepthNoise(DepthSource.RAW, -1.0, 1, 0.01, 0.0, 0.0) }
        assertThrows { DepthNoise(DepthSource.RAW, 1.0, -1, 0.01, 0.0, 0.0) }
        assertThrows { DepthNoise(DepthSource.RAW, 1.0, 1, 0.0, 0.0, 0.0) }
        assertThrows { DepthNoise(DepthSource.RAW, 1.0, 1, 0.01, -0.1, 0.0) }
        assertThrows { DepthNoise(DepthSource.RAW, 1.0, 1, 0.01, 0.0, Double.NaN) }
        val (_, vol) = Meshes.fusedBox(0.3, 0.3, 0.3, n = 6)
        val model = UncertaintyModel(DeviceTier.DEPTH)
        val n = DepthNoise.from(vol, DepthSource.SMOOTHED, model)
        assertEquals(vol.meanRangeM(), n.meanRangeM, 0.0)
        assertEquals(model.effectiveScaleSigmaRel, n.scaleSigmaRel, 0.0)
        assertEquals(DeviceTier.DEPTH.absFloor95, n.absFloor95M, 0.0)
        assertTrue(n.observations >= 1)
        assertEquals(0.5f, DepthSource.SMOOTHED.sampleWeight)
    }
}
