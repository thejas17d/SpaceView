package com.spaceview.ar

import com.spaceview.engine.BoxMeasurement
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GlbReader
import com.spaceview.engine.Measurement
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import com.spaceview.engine.recon.DepthFrame
import com.spaceview.engine.recon.DepthSource
import com.spaceview.engine.recon.MeshBadge
import com.spaceview.engine.recon.OrientedBox
import com.spaceview.engine.recon.TsdfVolume
import com.spaceview.engine.recon.VolumeSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The 3D-scan box and processing pipeline (M4-P1 gate §8 D2): a yawed 60 × 40 × 40 cm carton on the floor, outlined
 * from BOX-mode points, fused from rendered depth, then processed in the `ReconMemoryTest` order.
 */
class ShapeScanPipelineTest {
    private val yaw = 0.6
    private val centre = Vec3(0.3, -1.2, -1.5) // the floor is at y = −1.2 (ARCore's origin is the start pose)
    private val w = 0.6; private val h = 0.4; private val d = 0.4
    private val rot = Quaternion.fromAxisAngle(Vec3.UP, yaw)
    private fun corner(x: Double, y: Double, z: Double) = centre + rot.rotate(Vec3(x, y, z))

    @Test fun boxFromPointsIsTheMeasuredBoxInTheWorld() {
        // Base corners in BOX-mode order (front-left, front-right, back-right) and a top point off-centre.
        val b0 = corner(-w / 2, 0.0, d / 2); val b1 = corner(w / 2, 0.0, d / 2); val b2 = corner(w / 2, 0.0, -d / 2)
        val box = ShapeScanBox.fromPoints(b0, b1, b2, corner(0.1, h, 0.05))!!
        assertEquals(w, box.width, 1e-9); assertEquals(h, box.height, 1e-9); assertEquals(d, box.depth, 1e-9)
        assertTrue(box.pose.translation.approxEquals(centre, 1e-9))
        // +X along the width edge, +Y up; every corner inside, a point 2 cm outside a face is not.
        assertTrue(box.pose.rotateVector(Vec3.UNIT_X).approxEquals(rot.rotate(Vec3.UNIT_X), 1e-9))
        assertTrue(box.pose.rotateVector(Vec3.UNIT_Y).approxEquals(Vec3.UP, 1e-9))
        for (p in listOf(b0, b1, b2, corner(-w / 2, h, -d / 2))) assertTrue(box.contains(p.x, p.y, p.z, 1e-6))
        val out = corner(0.0, h / 2, d / 2 + 0.02)
        assertTrue(!box.contains(out.x, out.y, out.z))
        // Corners in the other winding (depth edge towards −Z of the width edge) give the same box.
        val mirrored = ShapeScanBox.fromPoints(corner(w / 2, 0.0, -d / 2), corner(-w / 2, 0.0, -d / 2), corner(-w / 2, 0.0, d / 2), corner(0.0, h, 0.0))!!
        assertTrue(mirrored.pose.translation.approxEquals(centre, 1e-9))
        assertEquals(d, mirrored.depth, 1e-9)
        // Degenerate outlines.
        assertNull(ShapeScanBox.fromPoints(b0, b0, b2, corner(0.0, h, 0.0)))
        assertNull(ShapeScanBox.fromPoints(b0, b1, b1, corner(0.0, h, 0.0)))
        assertNull(ShapeScanBox.fromPoints(b0, b1, b2, corner(0.0, 0.01, 0.0)))
    }

    @Test fun pipelineRunsInTheReconMemoryTestOrderAndReleasesTheVolume() {
        val box = ShapeScanBox.fromPoints(corner(-w / 2, 0.0, d / 2), corner(w / 2, 0.0, d / 2), corner(w / 2, 0.0, -d / 2), corner(0.0, h, 0.0))!!
        val vol = TsdfVolume(VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!)
        val frame = DepthFrame(160, 120)
        for (el in listOf(15.0, 40.0, 65.0)) for (k in 0 until 12) {
            render(frame, ring(corner(0.0, h / 2, 0.0), 1.2, el, 30.0 * k))
            vol.integrate(frame, null, DepthSource.RAW.sampleWeight)
        }
        val measured = BoxMeasurement(Measurement(w, 0.004), Measurement(h, 0.004), Measurement(d, 0.004), Measurement(w * h * d, 0.001))
        val holder = VolumeHolder(vol)
        var probes = 0
        val r = ShapeScanPipeline.process(holder, DepthSource.RAW, UncertaintyModel(DeviceTier.DEPTH), box.width, box.height, box.depth, measured, pss = { probes++; 1000L + probes })
        assertNull("the volume is released after marching cubes", holder.volume)
        assertEquals(ShapeScanPipeline.STAGES, r.stages.map { it.name })
        assertEquals(7, probes)
        assertEquals(1002L, r.stages[1].pssKb)
        assertTrue(r.stages.all { it.nanos >= 0 })
        assertTrue("decimated to the budget: ${r.mesh.triangleCount}", r.mesh.triangleCount in 1..ShapeScanPipeline.TARGET_TRIANGLES)
        assertTrue(r.stages[1].trianglesOut >= r.stages[3].trianglesOut)
        val v = r.voxelM
        assertEquals(w, r.extent.width.value, 2 * v)
        assertEquals(h, r.extent.height.value, 2 * v)
        assertEquals(d, r.extent.depth.value, 2 * v)
        assertTrue(r.check.agrees)
        assertEquals(MeshBadge.APPROXIMATE, r.check.badge)
        assertTrue(r.coverage > 0)
        // The GLB: a MESH_DEVICE kept in the box frame, declaring its own bounding-box centre (N3).
        assertNotNull(r.glb)
        val info = GlbReader.readInfo(r.glb!!)!!
        assertNull(r.glbError)
        assertEquals(ModelKind.MESH_DEVICE, info.modelKind)
        assertEquals(BoxOrigin.BOTTOM_CENTER, info.origin)
        val c = info.centre!!
        assertTrue("centre lifted by the floor cut: ${c.y}", c.y > info.heightM / 2)
        assertEquals(0.0, c.x, v); assertEquals(0.0, c.z, v)
        assertTrue("GLB ${r.glb!!.size} B", r.glb!!.size <= 400_000)
    }

    @Test fun anEmptyScanReportsInsteadOfThrowing() {
        val box = OrientedBox(Pose(Vec3(0.0, -1.2, -1.5)), 0.6, 0.4, 0.4)
        val vol = TsdfVolume(VolumeSizing.spec(box, VolumeSizing.DEFAULT_MARGIN_M, lowRam = false)!!)
        val measured = BoxMeasurement(Measurement(0.6, 0.004), Measurement(0.4, 0.004), Measurement(0.4, 0.004), Measurement(0.096, 0.001))
        val r = ShapeScanPipeline.process(VolumeHolder(vol), DepthSource.SMOOTHED, UncertaintyModel(DeviceTier.DEPTH), 0.6, 0.4, 0.4, measured)
        assertEquals(0, r.mesh.triangleCount)
        assertNull(r.glb)
        assertEquals("empty mesh", r.glbError)
        assertTrue(!r.check.agrees)
        assertEquals(MeshBadge.ROUGH, r.check.badge)
    }

    // ---- a minimal noise-free depth renderer: the yawed box on the floor ----

    private val k = CameraIntrinsics(125.0, 125.0, 79.5, 59.5)
    private val floorY = centre.y

    private fun render(frame: DepthFrame, pose: Pose) {
        frame.intrinsics = k
        frame.cameraToWorld = pose
        val o = pose.translation
        val inv = rot.inverse()
        val ol = inv.rotate(o - centre)
        for (row in 0 until frame.height) for (col in 0 until frame.width) {
            val dir = pose.rotateVector(Vec3((col - k.cx) / k.fx, -(row - k.cy) / k.fy, -1.0))
            val t = minOf(hitBox(ol, inv.rotate(dir)), if (dir.y < 0) (floorY - o.y) / dir.y else Double.POSITIVE_INFINITY)
            val mm = if (t.isFinite() && t > 0) (t * 1000).roundToInt().coerceIn(0, 65535) else 0
            frame.depthMm[row * frame.width + col] = mm.toShort()
            frame.confidence[row * frame.width + col] = (if (mm == 0) 0 else 255).toByte()
        }
    }

    /** Slab-method hit of the box in its local frame (x ∈ ±w/2, y ∈ [0, h], z ∈ ±d/2); +∞ when missed. */
    private fun hitBox(o: Vec3, dir: Vec3): Double {
        var t0 = 0.0; var t1 = Double.POSITIVE_INFINITY
        val lo = doubleArrayOf(-w / 2, 0.0, -d / 2); val hi = doubleArrayOf(w / 2, h, d / 2)
        val oo = doubleArrayOf(o.x, o.y, o.z); val dd = doubleArrayOf(dir.x, dir.y, dir.z)
        for (a in 0 until 3) {
            if (abs(dd[a]) < 1e-12) {
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
        return Pose(eye, PlacementMath.fromAxes(right, up, back))
    }
}
