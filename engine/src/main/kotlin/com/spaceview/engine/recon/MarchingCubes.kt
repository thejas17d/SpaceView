package com.spaceview.engine.recon

/**
 * Marching cubes (Lorensen & Cline, "Marching cubes: a high resolution 3D surface construction algorithm",
 * SIGGRAPH 1987) over a [TsdfVolume], with vertices welded through a per-edge index and per-vertex colour (§7.1.4).
 *
 * **Case table.** No published table is copied. [McTable] derives all 256 cases at class load from one rule:
 * on every cube face the iso-contour segments are fixed by that face's four corner signs alone, and on an
 * ambiguous face (two diagonal inside corners) the **inside corners are kept separate**. Two cubes that share a
 * face therefore always draw the same segments on it, so the surface has no cracks (the face-ambiguity hole of
 * the 15-case table cannot occur). The segments on a cube's six faces chain into closed loops, and each loop is
 * triangulated by a fan whose diagonals never lie on a cube face, so every interior mesh edge is shared by exactly
 * two triangles. (Such a fan exists for every loop of all 256 cases; table generation fails at class load if not.)
 *
 * **Orientation.** Inside = TSDF < 0. Triangles are counter-clockwise seen from outside (normals point towards
 * positive TSDF), matching glTF front faces.
 *
 * **What is skipped:** cubes with any corner weight < `minWeight` (unobserved space stays open, so a partially
 * seen object gives an open mesh) and cubes whose corners are all saturated: such a sign change is two truncated
 * bands meeting (e.g. a part thinner than the bands, carved from both sides), not a surface. "Saturated" follows the
 * asymmetric band of [TsdfParams.behindFraction]: tsdf ≥ [SATURATION] in front, tsdf ≤ −[SATURATION] × behindFraction
 * behind (integration never writes below −behindFraction). A real surface always has a corner within √3/2 voxel,
 * i.e. |tsdf| ≤ 0.29 at the 3-voxel minimum μ, far inside both thresholds.
 */
object MarchingCubes {
    /** Fraction of each band's full depth beyond which a corner counts as saturated (see class doc). Tunable. */
    const val SATURATION = 0.9f

    /** Weight threshold of §7.1.4 step 1 (drops one-frame noise). */
    const val DEFAULT_MIN_WEIGHT = 3

    /** Colour given to a vertex whose two grid points carry no fused colour (mid grey). */
    const val NO_COLOUR = 0x80

    /**
     * Extracts the zero iso-surface of [v] as an RGB-coloured [TriMesh] in the box frame. [minWeight] must be
     * 1..127. Two passes over the volume: the first counts vertices and indices, the second fills arrays of exactly
     * that size, so the only allocation is the output plus three z-slices of edge indices (review M4 MINOR 9: no
     * growth-and-copy buffers next to the 12.6 MB volume).
     */
    fun extract(v: TsdfVolume, minWeight: Int): TriMesh {
        require(minWeight in 1..TsdfParams.MAX_WEIGHT) { "minWeight must be 1..${TsdfParams.MAX_WEIGHT}" }
        val m = Marcher(v, minWeight)
        m.run()
        if (m.indexCount == 0) return TriMesh.EMPTY
        val pos = FloatArray(3 * m.vertexCount)
        val col = ByteArray(3 * m.vertexCount)
        val idx = IntArray(m.indexCount)
        m.run(pos, col, idx)
        return TriMesh(pos, col, idx)
    }

    /** One marching pass; with null outputs it only counts. Both passes visit cubes and edges in the same order. */
    private class Marcher(v: TsdfVolume, private val minWeight: Int) {
        private val spec = v.spec
        private val nx = spec.dims[0]; private val ny = spec.dims[1]; private val nz = spec.dims[2]
        private val slice = nx * ny
        private val tsdf = v.tsdf
        private val weight = v.weight
        private val rgb = v.rgb
        private val vox = spec.voxelM.toFloat()
        private val mnx = spec.minCorner.x.toFloat(); private val mny = spec.minCorner.y.toFloat(); private val mnz = spec.minCorner.z.toFloat()
        private val satFront = (SATURATION * TsdfVolume.SCALE).toInt()
        private val satBehind = (SATURATION * v.params.behindFraction * TsdfVolume.SCALE).toInt()
        private val cornerOff = IntArray(8) { c -> (c and 1) + nx * ((c shr 1) and 1) + slice * ((c shr 2) and 1) }
        // Edge-vertex caches: x/y edges of the current and next z-layer, z edges of the current layer.
        private var xyCur = IntArray(2 * slice)
        private var xyNext = IntArray(2 * slice)
        private val zCur = IntArray(slice)
        private val loopVerts = IntArray(12)
        private var pos: FloatArray? = null
        private var col: ByteArray? = null
        private var idx: IntArray? = null
        var vertexCount = 0
            private set
        var indexCount = 0
            private set

        fun run(pos: FloatArray? = null, col: ByteArray? = null, idx: IntArray? = null) {
            this.pos = pos; this.col = col; this.idx = idx
            vertexCount = 0; indexCount = 0
            java.util.Arrays.fill(xyCur, -1); java.util.Arrays.fill(xyNext, -1); java.util.Arrays.fill(zCur, -1)
            for (k in 0 until nz - 1) {
                for (j in 0 until ny - 1) {
                    for (i in 0 until nx - 1) cube(i, j, k)
                }
                val tmp = xyCur; xyCur = xyNext; xyNext = tmp
                java.util.Arrays.fill(xyNext, -1)
                java.util.Arrays.fill(zCur, -1)
            }
        }

        private fun cube(i: Int, j: Int, k: Int) {
            val base = i + nx * j + slice * k
            var cfg = 0
            var allSaturated = true
            for (c in 0 until 8) {
                val g = base + cornerOff[c]
                if ((weight[g].toInt() and 0x7F) < minWeight) return
                val tv = tsdf[g].toInt()
                if (tv < 0) cfg = cfg or (1 shl c)
                if (tv < satFront && tv > -satBehind) allSaturated = false
            }
            if (cfg == 0 || cfg == 255 || allSaturated) return
            val ops = McTable.LOOPS[cfg]
            val out = idx
            var o = 0
            while (o < ops.size) {
                val len = ops[o]
                val start = ops[o + 1]
                for (q in 0 until len) loopVerts[q] = edgeVertex(i, j, k, ops[o + 2 + q])
                val s = loopVerts[start]
                for (q in 1 until len - 1) {
                    if (out != null) {
                        out[indexCount] = s
                        out[indexCount + 1] = loopVerts[(start + q) % len]
                        out[indexCount + 2] = loopVerts[(start + q + 1) % len]
                    }
                    indexCount += 3
                }
                o += 2 + len
            }
        }

        private fun edgeVertex(i: Int, j: Int, k: Int, e: Int): Int {
            val bc = McTable.EDGE_BASE[e]
            val axis = McTable.EDGE_AXIS[e]
            val gi = i + (bc and 1); val gj = j + ((bc shr 1) and 1); val gk = k + ((bc shr 2) and 1)
            val cache: IntArray
            val slot: Int
            if (axis == 2) { cache = zCur; slot = gi + nx * gj } else {
                cache = if (gk == k) xyCur else xyNext
                slot = 2 * (gi + nx * gj) + axis
            }
            val existing = cache[slot]
            if (existing >= 0) return existing
            val id = vertexCount++
            cache[slot] = id
            val p = pos ?: return id
            val c = col!!
            val a = gi + nx * gj + slice * gk
            val b = a + when (axis) { 0 -> 1; 1 -> nx; else -> slice }
            val va = tsdf[a].toFloat(); val vb = tsdf[b].toFloat()
            val t = va / (va - vb) // opposite signs on a crossing edge, so the denominator is non-zero
            var px = mnx + vox * gi; var py = mny + vox * gj; var pz = mnz + vox * gk
            when (axis) { 0 -> px += vox * t; 1 -> py += vox * t; else -> pz += vox * t }
            p[3 * id] = px; p[3 * id + 1] = py; p[3 * id + 2] = pz
            val ca = (weight[a].toInt() and 0x80) != 0
            val cbSet = (weight[b].toInt() and 0x80) != 0
            for (ch in 0 until 3) {
                val x = rgb[3 * a + ch].toInt() and 0xFF
                val y = rgb[3 * b + ch].toInt() and 0xFF
                c[3 * id + ch] = when {
                    ca && cbSet -> (x + (y - x) * t + 0.5f).toInt()
                    ca -> x
                    cbSet -> y
                    else -> NO_COLOUR
                }.toByte()
            }
            return id
        }
    }
}

/**
 * The 256-case marching-cubes table, generated (see [MarchingCubes]). Corner c sits at
 * `((c and 1), (c shr 1) and 1, (c shr 2) and 1)`. Edges 0–3 run along x, 4–7 along y, 8–11 along z.
 *
 * [LOOPS]`[cfg]` is a flat list of loops, each `len, fanStart, e0 … e(len−1)`: the loop's edge vertices in order,
 * triangulated as a fan from position `fanStart`.
 */
internal object McTable {
    /** Lower corner of each edge. */
    val EDGE_BASE = intArrayOf(0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3)

    /** Axis of each edge (0 = x, 1 = y, 2 = z). */
    val EDGE_AXIS = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2)

    /** Faces as cyclic corner lists, with their outward normals. */
    private val FACES = arrayOf(
        intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5), // x = 0, x = 1
        intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), // y = 0, y = 1
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), // z = 0, z = 1
    )
    private val FACE_NORMAL = arrayOf(
        intArrayOf(-1, 0, 0), intArrayOf(1, 0, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, 0, -1), intArrayOf(0, 0, 1),
    )

    /** Edge between two corners that differ in one bit. */
    fun edgeOf(a: Int, b: Int): Int {
        require(Integer.bitCount(a xor b) == 1) { "corners $a and $b do not share an edge" }
        val lo = minOf(a, b)
        val axis = Integer.numberOfTrailingZeros(a xor b)
        return (0 until 12).first { EDGE_BASE[it] == lo && EDGE_AXIS[it] == axis }
    }

    /** Bitmask of the (two) faces that contain edge e. */
    val EDGE_FACES = IntArray(12) { e ->
        val a = EDGE_BASE[e]; val b = a or (1 shl EDGE_AXIS[e])
        var m = 0
        for (f in 0 until 6) if (a in FACES[f] && b in FACES[f]) m = m or (1 shl f)
        m
    }

    val LOOPS: Array<IntArray> = Array(256) { build(it) }

    private fun corner(c: Int) = intArrayOf(c and 1, (c shr 1) and 1, (c shr 2) and 1)

    /** Twice the edge midpoint (integer coordinates). */
    private fun mid2(e: Int): IntArray {
        val a = corner(EDGE_BASE[e]); val b = corner(EDGE_BASE[e] or (1 shl EDGE_AXIS[e]))
        return IntArray(3) { a[it] + b[it] }
    }

    private fun build(cfg: Int): IntArray {
        fun inside(c: Int) = (cfg shr c) and 1 == 1
        val next = IntArray(12) { -1 }
        val incoming = IntArray(12)
        for (f in 0 until 6) {
            val q = FACES[f]
            val segs = ArrayList<IntArray>(2)
            val crossings = (0 until 4).filter { inside(q[it]) != inside(q[(it + 1) % 4]) }
            when (crossings.size) {
                0 -> {}
                2 -> segs += intArrayOf(edgeOf(q[crossings[0]], q[(crossings[0] + 1) % 4]), edgeOf(q[crossings[1]], q[(crossings[1] + 1) % 4]))
                4 -> for (i in 0 until 4) if (inside(q[i])) {
                    // Ambiguous face: cut each inside corner off on its own.
                    segs += intArrayOf(edgeOf(q[(i + 3) % 4], q[i]), edgeOf(q[i], q[(i + 1) % 4]))
                }
                else -> error("odd crossing count")
            }
            for (s in segs) {
                var p = s[0]; var r = s[1]
                // Orient p→r so that (r − p) × g · n_face > 0, where g runs from the inside to the outside corner of
                // p's edge. Then the loops run counter-clockwise seen from outside (normals towards positive TSDF).
                val a = EDGE_BASE[p]; val bb = a or (1 shl EDGE_AXIS[p])
                val (ci, co) = if (inside(a)) a to bb else bb to a
                val g = IntArray(3) { corner(co)[it] - corner(ci)[it] }
                val mp = mid2(p); val mr = mid2(r)
                val t = IntArray(3) { mr[it] - mp[it] }
                val n = FACE_NORMAL[f]
                val cx = t[1] * g[2] - t[2] * g[1]
                val cy = t[2] * g[0] - t[0] * g[2]
                val cz = t[0] * g[1] - t[1] * g[0]
                val sgn = cx * n[0] + cy * n[1] + cz * n[2]
                check(sgn != 0)
                if (sgn < 0) { val tmp = p; p = r; r = tmp }
                check(next[p] == -1) { "edge $p leaves twice in case $cfg" }
                next[p] = r
                incoming[r]++
            }
        }
        val out = ArrayList<Int>()
        val seen = BooleanArray(12)
        for (e0 in 0 until 12) {
            if (next[e0] < 0 || seen[e0]) continue
            check(incoming[e0] == 1)
            val loop = ArrayList<Int>()
            var e = e0
            while (!seen[e]) { seen[e] = true; loop += e; e = next[e]; check(e >= 0) }
            check(e == e0) { "open contour in case $cfg" }
            out += loop.size
            out += fanStart(loop)
            out += loop
        }
        return out.toIntArray()
    }

    /**
     * First fan start whose diagonals never join two vertices on a common face (a diagonal on a face could also be
     * drawn by the neighbouring cube, making that edge non-manifold).
     */
    private fun fanStart(loop: List<Int>): Int {
        val k = loop.size
        for (s in 0 until k) {
            var ok = true
            for (j in 2 until k - 1) {
                if (EDGE_FACES[loop[s]] and EDGE_FACES[loop[(s + j) % k]] != 0) { ok = false; break }
            }
            if (ok) return s
        }
        error("no face-safe fan for loop $loop")
    }
}
