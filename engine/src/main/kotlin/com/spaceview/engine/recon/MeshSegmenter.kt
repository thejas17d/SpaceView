package com.spaceview.engine.recon

import com.spaceview.engine.Plane
import kotlin.math.sqrt

/**
 * Mesh clean-up after marching cubes (§7.1.4 steps 2–3) and the Review crop. All operations are O(triangles),
 * keep vertex and triangle order (deterministic), drop unreferenced vertices, and preserve the colour layout.
 * Every input is checked with [TriMesh.requireWellFormed].
 */
object MeshSegmenter {

    /**
     * Floor/support removal: clips the mesh to the half-space at least [cutM] above [plane]. Triangles wholly above
     * are kept, wholly below are dropped, and straddling ones are cut exactly at the plane (Sutherland–Hodgman
     * polygon clipping, 1974), with the new vertices welded per edge so the cut stays connected and colours
     * interpolated. The open bottom rim is therefore a clean line at `plane + cutM`, not a voxel-jagged one.
     * [plane] is in the mesh's frame with its normal pointing **up**, towards the object; for a [MarchingCubes]
     * mesh the support plane is `Plane(Vec3.UP, 0.0)` and the §7.1.4 cut is `max(1 cm, voxel)`. Afterwards no
     * vertex is below `plane + cutM` (up to float rounding).
     */
    fun removeBelow(mesh: TriMesh, plane: Plane, cutM: Double): TriMesh {
        require(cutM.isFinite()) { "cutM must be finite" }
        mesh.requireWellFormed()
        if (mesh.isEmpty) return TriMesh.EMPTY
        val n = plane.normal
        val p = mesh.positions
        val stride = mesh.colourStride
        val col = mesh.colours
        val ix = mesh.indices
        val tris = mesh.triangleCount
        val dist = DoubleArray(mesh.vertexCount) { v -> n.x * p[3 * v] + n.y * p[3 * v + 1] + n.z * p[3 * v + 2] + plane.d - cutM }
        // Pass 1: number the output vertices (kept originals and cut points, in first-use order) and count indices,
        // so the output arrays are allocated once at their exact size (review M4 MINOR 9).
        val remap = IntArray(mesh.vertexCount) { -1 }
        val cutVertex = LongIntMap(64) // grows with the rim; most triangles are never cut
        var nv = 0
        var ni = 0
        for (t in 0 until tris) {
            var m = 0
            for (e in 0 until 3) {
                val a = ix[3 * t + e]; val b = ix[3 * t + (e + 1) % 3]
                val aIn = dist[a] >= 0; val bIn = dist[b] >= 0
                if (aIn) { if (remap[a] < 0) remap[a] = nv++; m++ }
                if (aIn != bIn) {
                    val key = edgeKey(a, b)
                    if (cutVertex.get(key) < 0) cutVertex.put(key, nv++)
                    m++
                }
            }
            if (m >= 3) ni += 3 * (m - 2)
        }
        if (ni == 0) return TriMesh.EMPTY
        val outPos = FloatArray(3 * nv)
        val outCol = ByteArray(stride * nv)
        val outIdx = IntArray(ni)
        for (v in remap.indices) {
            val r = remap[v]
            if (r < 0) continue
            System.arraycopy(p, 3 * v, outPos, 3 * r, 3)
            if (stride > 0) System.arraycopy(col, stride * v, outCol, stride * r, stride)
        }
        // Pass 2: cut points (written once per edge; rewriting is idempotent) and the clipped polygons as fans.
        val poly = IntArray(4)
        var o = 0
        for (t in 0 until tris) {
            var m = 0
            for (e in 0 until 3) {
                val a = ix[3 * t + e]; val b = ix[3 * t + (e + 1) % 3]
                val aIn = dist[a] >= 0; val bIn = dist[b] >= 0
                if (aIn) poly[m++] = remap[a]
                if (aIn != bIn) {
                    val above = if (aIn) a else b
                    val below = if (aIn) b else a
                    val id = cutVertex.get(edgeKey(a, b))
                    val f = dist[above] / (dist[above] - dist[below])
                    for (ax in 0 until 3) outPos[3 * id + ax] = (p[3 * above + ax] + (p[3 * below + ax] - p[3 * above + ax]) * f).toFloat()
                    for (c in 0 until stride) {
                        val ca = col[stride * above + c].toInt() and 0xFF
                        val cb = col[stride * below + c].toInt() and 0xFF
                        outCol[stride * id + c] = (ca + (cb - ca) * f + 0.5).toInt().toByte()
                    }
                    poly[m++] = id
                }
            }
            for (q in 1 until m - 1) { outIdx[o++] = poly[0]; outIdx[o++] = poly[q]; outIdx[o++] = poly[q + 1] }
        }
        return TriMesh(outPos, outCol, outIdx)
    }

    private fun edgeKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    /**
     * Keeps the object: the largest connected component by surface area, plus any other component whose area is at
     * least [keepFraction] of it (a chair whose legs fuse as separate pieces keeps them; floaters go). Connectivity
     * is union-find over shared vertices.
     *
     * With [insideBox] (the measured box in the mesh's frame, e.g. `OrientedBox.local(w, h, d)`), components that
     * lie **mostly outside the box** are dropped first: a component is eligible only if at least
     * [MIN_INSIDE_FRACTION] of its area has triangle centroids inside the box grown by [BOX_TOLERANCE_M]. This is
     * how wall patches and neighbouring furniture inside the capture margin go (review M4 MINOR 8): a wall 5 cm
     * behind a sofa is ~30% of the sofa's area, so the area rule alone keeps it, and a wall could even out-size a
     * small object. Pass the box in production; without it only the area rule applies.
     *
     * Limits: a wall closer to the object than the TSDF bands (≈ 6 cm) fuses into the same component and must be
     * trimmed with the Review crop. Returns [TriMesh.EMPTY] when no component is eligible. `keepFraction = 1` keeps
     * only the largest (and exact ties). When every triangle is degenerate (zero area), triangle counts stand in
     * for area.
     */
    fun keepLargestComponent(mesh: TriMesh, keepFraction: Double = 0.05, insideBox: OrientedBox? = null): TriMesh {
        require(keepFraction in 0.0..1.0) { "keepFraction must be within 0..1" }
        mesh.requireWellFormed()
        if (mesh.isEmpty) return TriMesh.EMPTY
        val nv = mesh.vertexCount
        val parent = IntArray(nv) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb }
        }
        val ix = mesh.indices
        val p = mesh.positions
        val tris = mesh.triangleCount
        for (t in 0 until tris) { union(ix[3 * t], ix[3 * t + 1]); union(ix[3 * t], ix[3 * t + 2]) }
        val triArea = DoubleArray(tris) { triangleArea(p, ix[3 * it], ix[3 * it + 1], ix[3 * it + 2]) }
        val useArea = triArea.any { it > 0 }
        val size = DoubleArray(nv)
        val inside = DoubleArray(nv)
        for (t in 0 until tris) {
            val r = find(ix[3 * t])
            val w = if (useArea) triArea[t] else 1.0
            size[r] += w
            if (insideBox != null) {
                val a = 3 * ix[3 * t]; val b = 3 * ix[3 * t + 1]; val c = 3 * ix[3 * t + 2]
                val cx = (p[a].toDouble() + p[b] + p[c]) / 3
                val cy = (p[a + 1].toDouble() + p[b + 1] + p[c + 1]) / 3
                val cz = (p[a + 2].toDouble() + p[b + 2] + p[c + 2]) / 3
                if (insideBox.contains(cx, cy, cz, BOX_TOLERANCE_M)) inside[r] += w
            }
        }
        fun eligible(r: Int) = size[r] > 0 && (insideBox == null || inside[r] >= MIN_INSIDE_FRACTION * size[r])
        var largest = 0.0
        for (r in 0 until nv) if (parent[r] == r && eligible(r) && size[r] > largest) largest = size[r]
        if (largest == 0.0) return TriMesh.EMPTY
        val threshold = keepFraction * largest
        return mesh.subset(BooleanArray(tris) { t -> val r = find(ix[3 * t]); eligible(r) && size[r] >= threshold })
    }

    /** A component must have at least this share of its area inside the measured box to be kept (with a box). */
    const val MIN_INSIDE_FRACTION = 0.5

    /** The measured box is grown by this much for the inside test (box ± and depth inflation; research §7.6). */
    const val BOX_TOLERANCE_M = 0.03

    /**
     * The user crop at Review: keeps only triangles whose three vertices are inside [box] (in the mesh's frame,
     * e.g. `OrientedBox.local(w, h, d).trimmed(…)`), so the mesh extents never exceed the crop.
     */
    fun crop(mesh: TriMesh, box: OrientedBox): TriMesh {
        mesh.requireWellFormed()
        if (mesh.isEmpty) return TriMesh.EMPTY
        val p = mesh.positions
        val inside = BooleanArray(mesh.vertexCount) { v -> box.contains(p[3 * v].toDouble(), p[3 * v + 1].toDouble(), p[3 * v + 2].toDouble()) }
        val ix = mesh.indices
        return mesh.subset(BooleanArray(mesh.triangleCount) { t -> inside[ix[3 * t]] && inside[ix[3 * t + 1]] && inside[ix[3 * t + 2]] })
    }

    internal fun triangleArea(p: FloatArray, a: Int, b: Int, c: Int): Double {
        val ux = (p[3 * b] - p[3 * a]).toDouble(); val uy = (p[3 * b + 1] - p[3 * a + 1]).toDouble(); val uz = (p[3 * b + 2] - p[3 * a + 2]).toDouble()
        val vx = (p[3 * c] - p[3 * a]).toDouble(); val vy = (p[3 * c + 1] - p[3 * a + 1]).toDouble(); val vz = (p[3 * c + 2] - p[3 * a + 2]).toDouble()
        val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
        return 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
    }
}
