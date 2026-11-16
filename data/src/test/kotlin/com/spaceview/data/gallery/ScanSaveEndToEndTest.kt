package com.spaceview.data.gallery

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GlbReader
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.MeshExtras
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Quality
import com.spaceview.engine.Quaternion
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import com.spaceview.engine.recon.DepthFrame
import com.spaceview.engine.recon.DepthNoise
import com.spaceview.engine.recon.DepthSource
import com.spaceview.engine.recon.MarchingCubes
import com.spaceview.engine.recon.MeshExtent
import com.spaceview.engine.recon.MeshSegmenter
import com.spaceview.engine.recon.OrientedBox
import com.spaceview.engine.recon.TsdfVolume
import com.spaceview.engine.recon.VertexClusterDecimator
import com.spaceview.engine.recon.VolumeSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M4 review MAJOR 2, end to end: real recon (TSDF fusion of rendered depth → marching cubes → floor cut → largest
 * component → `MeshExtent`) → `GlbWriter.writeMesh(keepInputOrigin = true)` → `saveMesh` → `modelForPlacement`.
 * A 60 × 40 × 40 cm carton on the floor must save, keep its measured box as the size, store the §7.1.5 extents (with
 * ±) apart from the GLB's own box, and place standing on the floor at its measured footprint.
 */
class ScanSaveEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val w = 0.6; private val h = 0.4; private val d = 0.4

    @Test fun reconScanSavesAndPlacesAtItsMeasuredFootprint() = runTest {
        // ---- recon (engine, pure JVM) ----
        val box = OrientedBox(Pose(Vec3.ZERO), w, h, d)
        val vol = TsdfVolume(VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!)
        val frame = DepthFrame(160, 120)
        for (el in listOf(15.0, 40.0, 65.0)) for (k in 0 until 12) {
            render(frame, ring(Vec3(0.0, h / 2, 0.0), 1.2, el, 30.0 * k))
            vol.integrate(frame, null, 1.0f)
        }
        val cut = max(0.01, vol.spec.voxelM)
        var mesh = MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT)
        mesh = MeshSegmenter.removeBelow(mesh, Plane(Vec3.UP, 0.0), cut)
        mesh = MeshSegmenter.keepLargestComponent(mesh)
        // §7.1.7: decimate to the budget-derived target before writing (the fused carton is ~30k triangles).
        mesh = VertexClusterDecimator.decimate(mesh, 15_000)
        val extent = MeshExtent.estimate(mesh, OrientedBox.local(w, h, d), DepthNoise.from(vol, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH)))

        // ---- GLB: the scan stays in the measured box's frame ----
        val glb = GlbWriter.writeMesh(mesh, BoxOrigin.BOTTOM_CENTER, unlit = true, MeshExtras(ModelKind.MESH_DEVICE), keepInputOrigin = true)
        val info = GlbReader.readInfo(glb)!!
        // §7.1.7 / ADR-014: at the 15k-triangle target the mesh GLB leaves room for the thumbnail in the 0.5 MB item.
        assertTrue("mesh GLB ${glb.size} B, ${mesh.triangleCount} triangles", glb.size <= 400_000 && mesh.triangleCount <= 15_000)
        assertTrue("the GLB box and the §7.1.5 extents differ (floor cut)", info.heightM < extent.height.value - 0.005)

        // ---- save (K10) ----
        val dao = FakeGalleryDao()
        val repo = GalleryRepository(
            dao, File(tmp.root, "files"), JvmImageCodec, clock = { 1_000L }, io = Dispatchers.IO,
            transactions = FakeTransactions(dao), cacheDir = File(tmp.root, "cache"),
        )
        val id = repo.saveMesh(
            MeshDraft(
                kind = ObjectKind.BOX,
                width = Dimension.measured(0.601, 0.008), height = Dimension.measured(0.402, 0.008), depth = Dimension.measured(0.399, 0.008),
                quality = Quality.GOOD, calibrated = false, deviceTier = DeviceTier.DEPTH,
                glb = glb,
                glbExtents = GlbExtents(info.widthM, info.heightM, info.depthM),
                meshExtents = MeshExtents(
                    extent.width.value, extent.height.value, extent.depth.value,
                    extent.width.bound95, extent.height.bound95, extent.depth.bound95,
                ),
                meshInfo = "{\"voxelM\":${vol.spec.voxelM}}", thumbnailSource = null, label = "Carton", arcoreVersion = "1.57.0",
            ),
        )
        val item = repo.get(id)!!
        assertEquals(0.601, item.width.meters, 0.0) // the size is the measured box
        assertEquals(extent.height.value, item.meshExtents!!.heightM, 0.0) // §7.1.5 extents in the columns
        assertEquals(extent.height.bound95, item.meshExtents.heightBound95, 0.0)
        assertEquals(info.heightM, item.glbExtents!!.heightM, 0.0)

        // ---- place (K4) ----
        val p = repo.modelForPlacement(id)!!
        assertFalse(p.meshFallback)
        assertEquals(item.modelFile, p.file)
        assertEquals(BoxOrigin.BOTTOM_CENTER, p.origin)
        assertEquals(0.601, p.width.meters, 0.0) // the verdict uses the measured box
        // Placement height and footprint: the BOTTOM_CENTER origin goes on the floor hit, so the scan's bottom is the
        // floor cut above the floor (not dropped, not lifted) and its footprint is centred on the measured box.
        val (min, max) = positionBounds(p.file.readBytes())
        val v = vol.spec.voxelM
        // Never below the floor cut (the decimator only moves vertices inside their cell's vertex box).
        assertTrue("bottom ${min[1]} vs cut $cut", min[1] >= cut - 1e-4 && min[1] <= cut + v)
        assertEquals(h, max[1], v)
        assertEquals(0.0, (min[0] + max[0]) / 2, v)
        assertEquals(0.0, (min[2] + max[2]) / 2, v)
        assertEquals(w, max[0] - min[0], 2 * v)
        assertEquals(d, max[2] - min[2], 2 * v)
        println("E2E scan: ${glb.size} B, ${mesh.triangleCount} tris; GLB %.1f × %.1f × %.1f mm, extents %.1f × %.1f × %.1f mm ± %.1f, bottom %.1f mm".format(
            info.widthM * 1000, info.heightM * 1000, info.depthM * 1000,
            extent.width.value * 1000, extent.height.value * 1000, extent.depth.value * 1000, extent.width.bound95 * 1000, min[1] * 1000,
        ))
    }

    // ---- a minimal noise-free depth renderer: an axis-aligned box standing on the floor (y = 0) ----

    private val k = CameraIntrinsics(125.0, 125.0, 79.5, 59.5)

    private fun render(frame: DepthFrame, pose: Pose) {
        frame.intrinsics = k
        frame.cameraToWorld = pose
        val o = pose.translation
        for (row in 0 until frame.height) for (col in 0 until frame.width) {
            val dir = pose.rotateVector(Vec3((col - k.cx) / k.fx, -(row - k.cy) / k.fy, -1.0))
            val t = minOf(hitBox(o, dir), if (dir.y < 0) -o.y / dir.y else Double.POSITIVE_INFINITY)
            val mm = if (t.isFinite() && t > 0) (t * 1000).roundToInt().coerceIn(0, 65535) else 0
            frame.depthMm[row * frame.width + col] = mm.toShort()
            frame.confidence[row * frame.width + col] = (if (mm == 0) 0 else 255).toByte()
        }
    }

    /** Ray parameter of the box hit (slab method), +∞ when missed. Box: x ∈ ±w/2, y ∈ [0, h], z ∈ ±d/2. */
    private fun hitBox(o: Vec3, dir: Vec3): Double {
        var t0 = 0.0; var t1 = Double.POSITIVE_INFINITY
        val lo = doubleArrayOf(-w / 2, 0.0, -d / 2); val hi = doubleArrayOf(w / 2, h, d / 2)
        val oo = doubleArrayOf(o.x, o.y, o.z); val dd = doubleArrayOf(dir.x, dir.y, dir.z)
        for (a in 0 until 3) {
            if (kotlin.math.abs(dd[a]) < 1e-12) {
                if (oo[a] < lo[a] || oo[a] > hi[a]) return Double.POSITIVE_INFINITY
                continue
            }
            var ta = (lo[a] - oo[a]) / dd[a]; var tb = (hi[a] - oo[a]) / dd[a]
            if (ta > tb) { val x = ta; ta = tb; tb = x }
            t0 = max(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return Double.POSITIVE_INFINITY
        }
        return t0
    }

    /** A camera on a ring around [target] looking at it (ARCore convention: looks along local −Z, +Y up). */
    private fun ring(target: Vec3, dist: Double, elDeg: Double, azDeg: Double): Pose {
        val e = Math.toRadians(elDeg); val a = Math.toRadians(azDeg)
        val eye = target + Vec3(dist * cos(e) * sin(a), dist * sin(e), dist * cos(e) * cos(a))
        val back = (eye - target).normalized()
        val right = (Vec3.UP cross back).normalized()
        val up = back cross right
        return Pose(eye, fromColumns(right, up, back))
    }

    private fun fromColumns(x: Vec3, y: Vec3, z: Vec3): Quaternion {
        val tr = x.x + y.y + z.z
        return when {
            tr > 0 -> { val s = sqrt(tr + 1) * 2; Quaternion((y.z - z.y) / s, (z.x - x.z) / s, (x.y - y.x) / s, s / 4) }
            x.x > y.y && x.x > z.z -> { val s = sqrt(1 + x.x - y.y - z.z) * 2; Quaternion(s / 4, (y.x + x.y) / s, (z.x + x.z) / s, (y.z - z.y) / s) }
            y.y > z.z -> { val s = sqrt(1 + y.y - x.x - z.z) * 2; Quaternion((y.x + x.y) / s, s / 4, (z.y + y.z) / s, (z.x - x.z) / s) }
            else -> { val s = sqrt(1 + z.z - x.x - y.y) * 2; Quaternion((z.x + x.z) / s, (z.y + y.z) / s, s / 4, (x.y - y.x) / s) }
        }
    }

    /** POSITION accessor min / max from the GLB's JSON chunk. */
    private fun positionBounds(glb: ByteArray): Pair<DoubleArray, DoubleArray> {
        val jsonLen = (glb[12].toInt() and 0xFF) or ((glb[13].toInt() and 0xFF) shl 8) or ((glb[14].toInt() and 0xFF) shl 16)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8)
        val m = Regex("\"type\":\"VEC3\",\"min\":\\[([^\\]]+)],\"max\":\\[([^\\]]+)]").find(json)!!
        fun nums(s: String) = s.split(',').map { it.toDouble() }.toDoubleArray()
        return nums(m.groupValues[1]) to nums(m.groupValues[2])
    }
}
