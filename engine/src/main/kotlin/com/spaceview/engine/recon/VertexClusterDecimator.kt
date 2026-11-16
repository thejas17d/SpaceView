package com.spaceview.engine.recon

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decimation by vertex clustering (§7.1.4 step 4, research §7.5): a uniform grid, one representative vertex per
 * occupied cell, triangles whose three vertices land in distinct cells survive (duplicates removed). The cell
 * size is chosen by bisection (in log space, [BISECTION_STEPS] steps) as the smallest tried size that yields
 * ≤ `targetTriangles`. O(n) per step, deterministic, no randomness.
 *
 * **Representative** (Lindstrom, "Out-of-core simplification of large polygonal models", SIGGRAPH 2000): the
 * point minimising the summed squared distance to the planes of the cell's triangles (Garland & Heckbert
 * quadrics, SIGGRAPH 1997), solved around the cell's vertex mean with a truncated pseudo-inverse (eigenvalues
 * below [QUADRIC_RANK_TOL] × the largest are ignored), then clamped to the bounding box of the cell's own
 * vertices. A plain vertex mean would pull every flat face inwards by up to half a cell; the quadric point stays
 * on flat faces and box edges, so overall dimensions are preserved, and the clamp means the output can never
 * stick out of the input's bounding box. Open-border edges add a perpendicular constraint plane
 * ([BOUNDARY_WEIGHT]) so the open bottom keeps its height. Written from the papers; no code copied.
 *
 * Output topology is not guaranteed manifold (clustering can pinch thin parts). The GLB material is
 * double-sided (§7.1.4), so a collapsed sheet still renders.
 */
object VertexClusterDecimator {
    /** Hard ceiling of the research (§7.5: ≤ 25k triangles). */
    const val MAX_TRIANGLES = 25_000

    /** §7.1.7 budget: `min(20_000, (MAX_SCAN_BYTES − thumbnail − 8 KB) / 20)`; 15k is the aim for headroom. */
    const val DEFAULT_TARGET_TRIANGLES = 20_000

    /** Bisection steps on the cell size (each halves the log-interval; 30 → better than 1e-6 relative). */
    const val BISECTION_STEPS = 20

    /** Relative eigenvalue cut-off of the quadric solve (higher = closer to the vertex mean on curved cells). */
    const val QUADRIC_RANK_TOL = 0.05

    /**
     * Weight of a border constraint plane per unit squared edge length (Garland & Heckbert 1997 §6: border edges
     * get a perpendicular plane with a large penalty). Without it the open bottom rim rose by half a cell (16 mm
     * at a 3 cm cell on the synthetic box).
     */
    const val BOUNDARY_WEIGHT = 10.0

    /**
     * [mesh] reduced to at most [targetTriangles] triangles (returned unchanged when it already fits). The
     * colour layout (none, RGB, RGBA) is preserved; colours are cell averages.
     */
    fun decimate(mesh: TriMesh, targetTriangles: Int): TriMesh = decimateWithCell(mesh, targetTriangles).mesh

    /** Cells per axis at the finest bisection bound; keeps a packed cell key under 39 bits. */
    private const val MAX_CELLS_PER_AXIS = 8000.0

    /** Vertex index bits in a packed (cell, vertex) sort key. */
    private const val VERTEX_BITS = 24

    /**
     * Result plus the chosen cell size (0 when no clustering was needed) and, for each input vertex, its output
     * vertex (-1 when its whole cell collapsed); [vertexMap] is null when the mesh was returned unchanged or empty.
     */
    internal class Result(val mesh: TriMesh, val cellM: Double, val vertexMap: IntArray?)

    internal fun decimateWithCell(mesh: TriMesh, targetTriangles: Int): Result {
        require(targetTriangles >= 0) { "targetTriangles must be ≥ 0" }
        mesh.requireWellFormed()
        require(mesh.vertexCount < (1 shl VERTEX_BITS)) { "at most ${(1 shl VERTEX_BITS) - 1} vertices" }
        if (mesh.triangleCount <= targetTriangles) return Result(mesh, 0.0, null)
        val bounds = mesh.bounds()!!
        val ext = max(bounds[3] - bounds[0], max(bounds[4] - bounds[1], bounds[5] - bounds[2])).toDouble()
        if (ext <= 0.0) return Result(TriMesh.EMPTY, 0.0, null) // every vertex coincides: no area to keep
        val grid = Grid(mesh, bounds)
        var lo = ext / MAX_CELLS_PER_AXIS
        var hi = ext * (1 + 1e-6) + 1e-9 // one cell holds everything: 0 triangles
        if (grid.count(lo) <= targetTriangles) hi = lo else {
            repeat(BISECTION_STEPS) {
                val mid = sqrt(lo * hi)
                if (grid.count(mid) <= targetTriangles) hi = mid else lo = mid
            }
        }
        grid.count(hi)
        return grid.build(hi)
    }

    /**
     * Clustering state reused across bisection steps. Memory (review M4 MINOR 9): one `LongArray(max(V, 3T))`
     * scratch, reused for the edge keys (once), the packed (cell, vertex) keys and the triangle keys, which are
     * **sorted** rather than hashed; the cluster ids; and a small growable map for the final at most target
     * triangles. About 24 T + 4 V bytes plus the output, instead of the earlier ~21 MB of hash tables on the sofa.
     */
    private class Grid(val mesh: TriMesh, val b: FloatArray) {
        val nv = mesh.vertexCount
        val tris = mesh.triangleCount
        val cluster = IntArray(nv)
        var clusters = 0
        private var lastCount = 0
        private val scratch = LongArray(max(nv, 3 * tris))
        private val eigM = DoubleArray(9)
        private val eigV = DoubleArray(9)

        /** Sorted keys of the open-border edges (edges used by exactly one triangle). */
        private val boundary: LongArray

        init {
            val ix = mesh.indices
            var m = 0
            for (t in 0 until tris) for (e in 0 until 3) scratch[m++] = edgeKey(ix[3 * t + e], ix[3 * t + (e + 1) % 3])
            ReconMath.sortLongs(scratch, 0, m)
            var nb = 0
            forEachRun(m) { _, len -> if (len == 1) nb++ }
            boundary = LongArray(nb)
            nb = 0
            forEachRun(m) { start, len -> if (len == 1) boundary[nb++] = scratch[start] }
        }

        /** Calls [f] with (start, length) of each run of equal values in scratch[0, m). */
        private inline fun forEachRun(m: Int, f: (Int, Int) -> Unit) {
            var i = 0
            while (i < m) {
                var j = i + 1
                while (j < m && scratch[j] == scratch[i]) j++
                f(i, j - i)
                i = j
            }
        }

        fun isBoundary(a: Int, b: Int): Boolean = java.util.Arrays.binarySearch(boundary, edgeKey(a, b)) >= 0

        /** Assigns cells for [cell] and returns the surviving (non-degenerate, de-duplicated) triangle count. */
        fun count(cell: Double): Int {
            val inv = 1.0 / cell
            val gx = floor((b[3] - b[0]) * inv).toLong() + 1
            val gy = floor((b[4] - b[1]) * inv).toLong() + 1
            val p = mesh.positions
            for (v in 0 until nv) {
                val cx = floor((p[3 * v] - b[0]) * inv).toLong()
                val cy = floor((p[3 * v + 1] - b[1]) * inv).toLong()
                val cz = floor((p[3 * v + 2] - b[2]) * inv).toLong()
                scratch[v] = ((cx + gx * (cy + gy * cz)) shl VERTEX_BITS) or v.toLong()
            }
            ReconMath.sortLongs(scratch, 0, nv)
            clusters = 0
            var prev = -1L
            for (i in 0 until nv) {
                val e = scratch[i]
                val k = e ushr VERTEX_BITS
                if (k != prev) { clusters++; prev = k }
                cluster[(e and ((1L shl VERTEX_BITS) - 1)).toInt()] = clusters - 1
            }
            val dedupe = clusters < (1 shl 21)
            val ix = mesh.indices
            var m = 0
            for (t in 0 until tris) {
                val a = cluster[ix[3 * t]]; val bb = cluster[ix[3 * t + 1]]; val c = cluster[ix[3 * t + 2]]
                if (a == bb || bb == c || a == c) continue
                if (dedupe) scratch[m] = triKey(a, bb, c)
                m++
            }
            if (!dedupe) { lastCount = m; return m } // over-counts duplicates: conservative (only ever coarser)
            ReconMath.sortLongs(scratch, 0, m)
            var distinct = 0
            forEachRun(m) { _, _ -> distinct++ }
            lastCount = distinct
            return distinct
        }

        private fun triKey(a: Int, b: Int, c: Int): Long {
            val lo = minOf(a, minOf(b, c)); val hi = maxOf(a, maxOf(b, c)); val mid = a + b + c - lo - hi
            return (lo.toLong() shl 42) or (mid.toLong() shl 21) or hi.toLong()
        }

        private fun edgeKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

        private fun addPlane(q: DoubleArray, o: Int, w: Double, nx: Double, ny: Double, nz: Double, d: Double) {
            q[o] += w * nx * nx; q[o + 1] += w * nx * ny; q[o + 2] += w * nx * nz
            q[o + 3] += w * ny * ny; q[o + 4] += w * ny * nz; q[o + 5] += w * nz * nz
            q[o + 6] += w * d * nx; q[o + 7] += w * d * ny; q[o + 8] += w * d * nz
        }

        /** Builds the output for the last [count] call (cell size [cellM]). */
        fun build(cellM: Double): Result {
            val c = clusters
            val p = mesh.positions
            val ix = mesh.indices
            // Surviving triangles: distinct cluster triples, first occurrence in triangle order (keeps its winding).
            val keepTri = BooleanArray(tris)
            val dedupe = c < (1 shl 21)
            val seen = LongIntMap(lastCount) // the last count is exactly the number of keys it will hold
            for (t in 0 until tris) {
                val a = cluster[ix[3 * t]]; val bb = cluster[ix[3 * t + 1]]; val cc = cluster[ix[3 * t + 2]]
                if (a == bb || bb == cc || a == cc) continue
                if (dedupe) {
                    val key = triKey(a, bb, cc)
                    if (seen.get(key) >= 0) continue
                    seen.put(key, t)
                }
                keepTri[t] = true
            }
            val q = DoubleArray(9 * c) // a00 a01 a02 a11 a12 a22 b0 b1 b2
            val sum = DoubleArray(3 * c)
            val n = IntArray(c)
            val box = DoubleArray(6 * c)
            for (k in 0 until c) { for (a in 0 until 3) { box[6 * k + a] = Double.POSITIVE_INFINITY; box[6 * k + 3 + a] = Double.NEGATIVE_INFINITY } }
            for (v in 0 until nv) {
                val k = cluster[v]
                n[k]++
                for (a in 0 until 3) {
                    val x = p[3 * v + a].toDouble()
                    sum[3 * k + a] += x
                    if (x < box[6 * k + a]) box[6 * k + a] = x
                    if (x > box[6 * k + 3 + a]) box[6 * k + 3 + a] = x
                }
            }
            for (t in 0 until tris) {
                val i0 = ix[3 * t]; val i1 = ix[3 * t + 1]; val i2 = ix[3 * t + 2]
                val ux = (p[3 * i1] - p[3 * i0]).toDouble(); val uy = (p[3 * i1 + 1] - p[3 * i0 + 1]).toDouble(); val uz = (p[3 * i1 + 2] - p[3 * i0 + 2]).toDouble()
                val vx = (p[3 * i2] - p[3 * i0]).toDouble(); val vy = (p[3 * i2 + 1] - p[3 * i0 + 1]).toDouble(); val vz = (p[3 * i2 + 2] - p[3 * i0 + 2]).toDouble()
                var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len == 0.0) continue
                val area = 0.5 * len
                nx /= len; ny /= len; nz /= len
                val d = -(nx * p[3 * i0] + ny * p[3 * i0 + 1] + nz * p[3 * i0 + 2])
                for (corner in 0 until 3) addPlane(q, 9 * cluster[ix[3 * t + corner]], area, nx, ny, nz, d)
                // Open-border edges (the unseen bottom, holes) get a constraint plane through the edge, perpendicular
                // to the face, so border cells keep their border instead of sliding to the vertex mean.
                for (e in 0 until 3) {
                    val a = ix[3 * t + e]; val b = ix[3 * t + (e + 1) % 3]
                    if (!isBoundary(a, b)) continue
                    val ex = (p[3 * b] - p[3 * a]).toDouble(); val ey = (p[3 * b + 1] - p[3 * a + 1]).toDouble(); val ez = (p[3 * b + 2] - p[3 * a + 2]).toDouble()
                    var mx = ey * nz - ez * ny; var my = ez * nx - ex * nz; var mz = ex * ny - ey * nx
                    val ml = sqrt(mx * mx + my * my + mz * mz)
                    if (ml == 0.0) continue
                    mx /= ml; my /= ml; mz /= ml
                    val md = -(mx * p[3 * a] + my * p[3 * a + 1] + mz * p[3 * a + 2])
                    val wgt = BOUNDARY_WEIGHT * (ex * ex + ey * ey + ez * ez)
                    addPlane(q, 9 * cluster[a], wgt, mx, my, mz, md)
                    addPlane(q, 9 * cluster[b], wgt, mx, my, mz, md)
                }
            }
            // Output vertices: clusters used by surviving triangles, in first-use order.
            val outId = IntArray(c) { -1 }
            var outV = 0
            val idx = IntArray(3 * keepTri.count { it })
            var o = 0
            for (t in 0 until tris) if (keepTri[t]) {
                for (corner in 0 until 3) {
                    val k = cluster[ix[3 * t + corner]]
                    if (outId[k] < 0) outId[k] = outV++
                    idx[o++] = outId[k]
                }
            }
            val stride = mesh.colourStride
            val pos = FloatArray(3 * outV)
            val col = ByteArray(stride * outV)
            val colSum = IntArray(stride * c)
            if (stride > 0) for (v in 0 until nv) for (ch in 0 until stride) colSum[stride * cluster[v] + ch] += mesh.colours[stride * v + ch].toInt() and 0xFF
            val rep = DoubleArray(3)
            for (k in 0 until c) {
                val id = outId[k]
                if (id < 0) continue
                representative(k, q, sum, n[k], box, rep)
                for (a in 0 until 3) pos[3 * id + a] = rep[a].toFloat()
                for (ch in 0 until stride) col[stride * id + ch] = (colSum[stride * k + ch] / n[k]).toByte()
            }
            return Result(TriMesh(pos, col, idx), cellM, IntArray(nv) { outId[cluster[it]] })
        }

        private fun representative(k: Int, q: DoubleArray, sum: DoubleArray, n: Int, box: DoubleArray, out: DoubleArray) {
            val mx = sum[3 * k] / n; val my = sum[3 * k + 1] / n; val mz = sum[3 * k + 2] / n
            out[0] = mx; out[1] = my; out[2] = mz
            val o = 9 * k
            val a00 = q[o]; val a01 = q[o + 1]; val a02 = q[o + 2]; val a11 = q[o + 3]; val a12 = q[o + 4]; val a22 = q[o + 5]
            if (a00 + a11 + a22 > 0.0) {
                // Residual r = −(A m + b); y = Σ (e·r / λ) e over the well-conditioned eigen-directions.
                val rx = -(a00 * mx + a01 * my + a02 * mz + q[o + 6])
                val ry = -(a01 * mx + a11 * my + a12 * mz + q[o + 7])
                val rz = -(a02 * mx + a12 * my + a22 * mz + q[o + 8])
                ReconMath.symmetricEigen(a00, a01, a02, a11, a12, a22, eigM, eigV)
                val lMax = maxOf(eigM[0], maxOf(eigM[4], eigM[8]))
                for (i in 0 until 3) {
                    val l = eigM[4 * i]
                    if (l <= QUADRIC_RANK_TOL * lMax) continue
                    val ex = eigV[i]; val ey = eigV[3 + i]; val ez = eigV[6 + i]
                    val s = (ex * rx + ey * ry + ez * rz) / l
                    out[0] += s * ex; out[1] += s * ey; out[2] += s * ez
                }
            }
            for (a in 0 until 3) out[a] = out[a].coerceIn(box[6 * k + a], box[6 * k + 3 + a])
        }
    }
}

/**
 * Open-addressing Long to Int map for non-negative keys (no boxing), load factor at most 0.5; it doubles when
 * full, so callers can start small. [get] returns -1 when absent.
 */
internal class LongIntMap(expected: Int) {
    private var mask: Int
    private var keys: LongArray
    private var vals: IntArray

    /** Number of keys stored. */
    var size = 0
        private set

    init {
        var cap = 16
        while (cap < 2 * expected + 2) cap = cap shl 1
        mask = cap - 1
        keys = LongArray(cap) { EMPTY }
        vals = IntArray(cap)
    }

    private fun slot(key: Long): Int {
        // Fibonacci hashing (Knuth, TAOCP vol. 3 section 6.4) then linear probing.
        var i = ((key * -0x61c8864680b583ebL) ushr 32).toInt() and mask
        while (keys[i] != EMPTY && keys[i] != key) i = (i + 1) and mask
        return i
    }

    fun clear() { java.util.Arrays.fill(keys, EMPTY); size = 0 }

    fun get(key: Long): Int { val i = slot(key); return if (keys[i] == key) vals[i] else -1 }

    fun put(key: Long, value: Int) {
        var i = slot(key)
        if (keys[i] != key) {
            if (2 * (size + 1) > keys.size) { grow(); i = slot(key) }
            keys[i] = key
            size++
        }
        vals[i] = value
    }

    private fun grow() {
        val oldK = keys; val oldV = vals
        keys = LongArray(oldK.size * 2) { EMPTY }
        vals = IntArray(oldK.size * 2)
        mask = keys.size - 1
        for (j in oldK.indices) if (oldK[j] != EMPTY) { val i = slot(oldK[j]); keys[i] = oldK[j]; vals[i] = oldV[j] }
    }

    private companion object { const val EMPTY = Long.MIN_VALUE }
}
