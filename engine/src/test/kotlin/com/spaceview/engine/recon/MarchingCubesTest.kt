package com.spaceview.engine.recon

import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MarchingCubesTest {

    /** An n³ grid volume, 1 cm voxels, every grid point observed (weight 10) and outside (+0.5). */
    private fun grid(n: Int): TsdfVolume {
        val v = TsdfVolume(VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(n, n, n), 0.01, 0.04))
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) v.setVoxel(i, j, k, 0.5f, 10)
        return v
    }

    /** Connected components of the inside corners of [cfg] under cube-edge adjacency. */
    private fun insideComponents(cfg: Int): Int {
        val parent = IntArray(8) { it }
        fun find(x: Int): Int = if (parent[x] == x) x else find(parent[x])
        for (a in 0 until 8) for (bit in 0 until 3) {
            val b = a xor (1 shl bit)
            if ((cfg shr a) and 1 == 1 && (cfg shr b) and 1 == 1) parent[find(a)] = find(b)
        }
        return (0 until 8).count { (cfg shr it) and 1 == 1 && find(it) == it }
    }

    /**
     * All 256 cube configurations, each embedded as the centre cube of a 4³ grid whose other points are outside:
     * the surface around the inside corners must be closed, consistently oriented (every directed edge used once,
     * its reverse once), outward (signed volume > 0), and have Euler characteristic 2 per inside component
     * (inside corners joined only diagonally are kept separate by the table's rule).
     */
    @Test fun all256ConfigurationsGiveClosedOutwardSurfaces() {
        var loops = 0
        for (cfg in 0 until 256) {
            val v = grid(4)
            for (c in 0 until 8) if ((cfg shr c) and 1 == 1) v.setVoxel(1 + (c and 1), 1 + ((c shr 1) and 1), 1 + ((c shr 2) and 1), -0.5f, 10)
            val m = MarchingCubes.extract(v, 1)
            if (cfg == 0) { assertTrue(m.isEmpty); continue }
            m.requireWellFormed()
            assertTrue("cfg $cfg closed and oriented", MeshMetrics.isClosedOriented(m))
            assertTrue("cfg $cfg outward", MeshMetrics.signedVolume(m) > 0)
            assertEquals("cfg $cfg Euler", 2 * insideComponents(cfg), MeshMetrics.euler(m))
            val ops = McTable.LOOPS[cfg]
            var o = 0
            while (o < ops.size) { assertTrue(ops[o + 1] in 0 until ops[o]); loops++; o += 2 + ops[o] }
        }
        println("MC table: $loops loops across 256 cases, all triangulated by face-safe fans")
    }

    /** Random fields with an outside border: always a closed, consistently oriented, outward surface. */
    @Test fun randomFieldsAreWatertightAndConsistentlyOriented() {
        val rng = Random(42)
        repeat(40) { trial ->
            val n = 8
            val v = grid(n)
            for (k in 1 until n - 1) for (j in 1 until n - 1) for (i in 1 until n - 1) {
                val x = (rng.nextFloat() * 2 - 1).let { if (it == 0f) 0.1f else it } * 0.8f
                v.setVoxel(i, j, k, x, 10)
            }
            val m = MarchingCubes.extract(v, 1)
            if (m.isEmpty) return@repeat
            assertTrue("trial $trial: every edge shared by 2 triangles", MeshMetrics.edgeUse(m).values.all { it == 2 })
            assertTrue("trial $trial: oriented", MeshMetrics.isClosedOriented(m))
            assertTrue("trial $trial: outward", MeshMetrics.signedVolume(m) > 0)
            assertEquals("trial $trial: even Euler characteristic", 0, MeshMetrics.euler(m) % 2)
        }
    }

    @Test fun weldingSharesVerticesAndVerticesLieOnTheZeroCrossing() {
        val n = 12
        val v = grid(n)
        // Plane-like field: value = (z − 0.055) / 0.04 → surface at z = 5.5 cm.
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) v.setVoxel(i, j, k, ((k * 0.01 - 0.055) / 0.04).toFloat(), 10)
        val m = MarchingCubes.extract(v, 1)
        assertTrue(m.triangleCount > 0)
        val unique = HashSet<Triple<Float, Float, Float>>()
        for (i in 0 until m.vertexCount) {
            unique += Triple(m.positions[3 * i], m.positions[3 * i + 1], m.positions[3 * i + 2])
            assertEquals(0.055f, m.positions[3 * i + 2], 1e-5f)
        }
        assertEquals("no duplicate vertices", m.vertexCount, unique.size)
        assertTrue("shared: V < 3T", m.vertexCount < m.triangleCount)
        // Inside (negative) is z < 5.5 cm, so the outward normal is +z: check a triangle's winding.
        val a = MeshMetrics.vertex(m, m.indices[0]); val b = MeshMetrics.vertex(m, m.indices[1]); val c = MeshMetrics.vertex(m, m.indices[2])
        assertTrue(((b - a) cross (c - a)).z > 0)
    }

    @Test fun unobservedAndSaturatedCubesAreSkipped() {
        val v = grid(4)
        for (c in 0 until 8) if (c and 1 == 1) v.setVoxel(1 + (c and 1), 1 + ((c shr 1) and 1), 1 + ((c shr 2) and 1), -0.5f, 10)
        assertTrue(MarchingCubes.extract(v, 1).triangleCount > 0)
        assertTrue("weights below minWeight", MarchingCubes.extract(v, 11).isEmpty)
        // Saturated: the front band's +1 meeting the deepest value integration writes behind a surface
        // (−behindFraction = −0.5) is two truncated bands meeting, not a surface (review M4 MINOR 7).
        fun slab(behind: Float): TsdfVolume {
            val g = grid(4)
            for (k in 0 until 4) for (j in 0 until 4) for (i in 0 until 4) g.setVoxel(i, j, k, if (i < 2) behind else 1f, 10)
            return g
        }
        assertTrue(MarchingCubes.extract(slab(-0.5f), 1).isEmpty)
        assertTrue(MarchingCubes.extract(slab(-0.46f), 1).isEmpty)
        // A real crossing always has a corner well inside the band: −0.3 next to +1 is kept.
        assertTrue(MarchingCubes.extract(slab(-0.3f), 1).triangleCount > 0)
        // The threshold follows the volume's behindFraction: with a full band, −0.5 is no longer saturated.
        val full = TsdfVolume(VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(4, 4, 4), 0.01, 0.04), TsdfParams(behindFraction = 1.0))
        for (k in 0 until 4) for (j in 0 until 4) for (i in 0 until 4) full.setVoxel(i, j, k, if (i < 2) -0.5f else 1f, 10)
        assertTrue(MarchingCubes.extract(full, 1).triangleCount > 0)
        for (k in 0 until 4) for (j in 0 until 4) for (i in 0 until 4) full.setVoxel(i, j, k, if (i < 2) -1f else 1f, 10)
        assertTrue(MarchingCubes.extract(full, 1).isEmpty)
        // Unobserved grid: nothing.
        val e = TsdfVolume(VolumeSpec(Pose.IDENTITY, Vec3.ZERO, intArrayOf(3, 3, 3), 0.01, 0.04))
        assertTrue(MarchingCubes.extract(e, 1).isEmpty)
    }

    @Test fun vertexColoursInterpolateFusedColours() {
        val v = grid(4)
        for (k in 0 until 4) for (j in 0 until 4) for (i in 0 until 4) {
            val inside = i == 1 && j == 1 && k == 1
            v.setVoxel(i, j, k, if (inside) -0.5f else 0.5f, 10, if (inside) 0xFF0000 else 0x0000FF)
        }
        val m = MarchingCubes.extract(v, 1)
        // Midway (t = 0.5) between red and blue.
        for (i in 0 until m.vertexCount) {
            assertTrue((m.colours[3 * i].toInt() and 0xFF) in 127..128)
            assertEquals(0, m.colours[3 * i + 1].toInt() and 0xFF)
            assertTrue((m.colours[3 * i + 2].toInt() and 0xFF) in 127..128)
        }
        // One side coloured only, then neither.
        val half = grid(4)
        half.setVoxel(1, 1, 1, -0.5f, 10, 0x00FF00)
        val mh = MarchingCubes.extract(half, 1)
        for (i in 0 until mh.vertexCount) assertEquals(255, mh.colours[3 * i + 1].toInt() and 0xFF)
        val none = grid(4)
        none.setVoxel(1, 1, 1, -0.5f, 10)
        val mn = MarchingCubes.extract(none, 1)
        for (b in mn.colours) assertEquals(MarchingCubes.NO_COLOUR, b.toInt() and 0xFF)
        // setVoxel to the other side of a coloured voxel only.
        val other = grid(4)
        for (k in 0 until 4) for (j in 0 until 4) for (i in 0 until 4) other.setVoxel(i, j, k, 0.5f, 10, 0x0000FF)
        other.setVoxel(1, 1, 1, -0.5f, 10)
        val mo = MarchingCubes.extract(other, 1)
        for (i in 0 until mo.vertexCount) assertEquals(255, mo.colours[3 * i + 2].toInt() and 0xFF)
    }

    @Test fun invalidMinWeightThrowsAndTableHelpersValidate() {
        val v = grid(2)
        assertThrows { MarchingCubes.extract(v, 0) }
        assertThrows { MarchingCubes.extract(v, 128) }
        assertThrows { McTable.edgeOf(0, 3) }
        assertEquals(11, McTable.edgeOf(7, 3))
    }
}
