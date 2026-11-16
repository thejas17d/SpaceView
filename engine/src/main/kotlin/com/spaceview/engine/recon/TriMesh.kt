package com.spaceview.engine.recon

/**
 * An indexed triangle mesh, exactly as defined in docs/ARCHITECTURE-v2.md §7.1.4 (contract K11). Flat arrays,
 * no per-vertex objects.
 *
 * - [positions]: x, y, z per vertex (metres), so `positions.size == 3 * vertexCount`.
 * - [colours]: per-vertex sRGB colour, either RGB (`3 * vertexCount` bytes), RGBA (`4 * vertexCount`) or empty
 *   (no colour). `GlbWriter.writeMesh` accepts all three.
 * - [indices]: three vertex indices per triangle, counter-clockwise seen from outside.
 *
 * Frame: meshes made by [MarchingCubes] are in the measured box's frame (see [OrientedBox]): origin at the centre
 * of the box base on the support plane, +Y up, so they are already `BOX_ORIGIN = BOTTOM_CENTER` (§7.1.8).
 *
 * Ownership: package `recon` belongs to Builder C. Builder A created this file in phase 0 with the exact §7.1.4
 * definition so that `GlbWriter.writeMesh` (docs/contracts/phase0.md) compiles before the recon code lands.
 * Builder C may add members, but must not change the three constructor properties. There is deliberately no
 * validating `init` block (A's writer tests build malformed meshes on purpose); recon functions call
 * [requireWellFormed] on their inputs instead.
 */
class TriMesh(val positions: FloatArray, val colours: ByteArray, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** True when there are no triangles. */
    val isEmpty: Boolean get() = indices.isEmpty()

    /** Colour bytes per vertex: 0 (no colour), 3 (RGB) or 4 (RGBA); -1 when [colours] matches none of them. */
    val colourStride: Int
        get() {
            val n = vertexCount
            return when {
                colours.isEmpty() -> 0
                colours.size == 3 * n -> 3
                colours.size == 4 * n -> 4
                else -> -1
            }
        }

    /**
     * Throws [IllegalArgumentException] unless the arrays are consistent: whole vertices and triangles, a valid
     * colour layout and every index in range. O(vertices + indices).
     */
    fun requireWellFormed() {
        require(positions.size % 3 == 0) { "positions must hold whole xyz triples" }
        require(indices.size % 3 == 0) { "indices must hold whole triangles" }
        require(colourStride >= 0) { "colours must be empty, RGB or RGBA per vertex, got ${colours.size} bytes" }
        val n = vertexCount
        for (i in indices) require(i in 0 until n) { "index $i out of range 0..${n - 1}" }
    }

    /**
     * Axis-aligned bounds of the vertices that triangles reference, as `[minX, minY, minZ, maxX, maxY, maxZ]`, or
     * null for an empty mesh. Unreferenced vertices are ignored so they cannot stretch the box.
     */
    fun bounds(): FloatArray? {
        if (indices.isEmpty()) return null
        val b = floatArrayOf(
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )
        for (i in indices) {
            val o = 3 * i
            for (a in 0 until 3) {
                val c = positions[o + a]
                if (c < b[a]) b[a] = c
                if (c > b[a + 3]) b[a + 3] = c
            }
        }
        return b
    }

    /**
     * The triangles whose [keep] flag is set, with unreferenced vertices dropped. Vertex and triangle order is
     * preserved, so the result is deterministic. Colours keep their layout (none, RGB or RGBA).
     */
    internal fun subset(keep: BooleanArray): TriMesh {
        val tris = triangleCount
        require(keep.size == tris)
        val stride = colourStride
        val remap = IntArray(vertexCount) { -1 }
        var keptTris = 0
        for (t in 0 until tris) if (keep[t]) {
            keptTris++
            for (c in 0 until 3) remap[indices[3 * t + c]] = 0
        }
        if (keptTris == 0) return EMPTY
        var nv = 0
        for (v in remap.indices) if (remap[v] == 0) remap[v] = nv++ else remap[v] = -1
        val pos = FloatArray(3 * nv)
        val col = ByteArray(stride * nv)
        for (v in remap.indices) {
            val r = remap[v]
            if (r < 0) continue
            System.arraycopy(positions, 3 * v, pos, 3 * r, 3)
            if (stride > 0) System.arraycopy(colours, stride * v, col, stride * r, stride)
        }
        val idx = IntArray(3 * keptTris)
        var o = 0
        for (t in 0 until tris) if (keep[t]) {
            for (c in 0 until 3) idx[o++] = remap[indices[3 * t + c]]
        }
        return TriMesh(pos, col, idx)
    }

    companion object {
        /** A mesh with no vertices and no triangles. */
        @JvmField val EMPTY = TriMesh(FloatArray(0), ByteArray(0), IntArray(0))
    }
}
