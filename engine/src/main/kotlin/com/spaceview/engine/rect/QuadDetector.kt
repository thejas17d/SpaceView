package com.spaceview.engine.rect

import com.spaceview.engine.Vec2
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A detected quad in full-resolution image pixels.
 *
 * @property corners 4 sub-pixel corners, in order around the quad (winding as found).
 * @property sigmaPx per-corner 1σ (px) from the side fits.
 * @property support worst side's edge support (0..1) at search resolution.
 * @property areaPx area in px².
 */
class DetectedQuad(val corners: List<Vec2>, val sigmaPx: List<Double>, val support: Double, val areaPx: Double)

/** Tunables of [QuadDetector] (research `close-range-and-rectangles.md` §4.3; Apple `VNDetectRectanglesRequest` defaults). */
class QuadDetectorConfig(
    /** Gradient-angle tolerance of a line-support region (deg). */
    val angleToleranceDeg: Double = 22.5,
    /** Shortest segment kept, as a fraction of the image diagonal. */
    val minSegmentFrac: Double = 0.04,
    /** Segments considered for quads (longest first). */
    val maxSegments: Int = 36,
    /** Opposite sides may differ in direction by up to this (deg) in the image. */
    val maxOppositeDeg: Double = 30.0,
    /** Every image-space corner angle must be within 90° ± this. */
    val maxCornerDevDeg: Double = 45.0,
    /** The quad's shortest side must be at least this fraction of the image's short side (Apple minimumSize 0.2). */
    val minSizeFrac: Double = 0.2,
    /** Short / long side ratio at least this (Apple minimumAspectRatio 0.1). */
    val minAspect: Double = 0.1,
    /** Each side's edge support (fraction of its length with an aligned edge) must reach this. */
    val minSupport: Double = 0.6,
    /** Gradient magnitude (Sobel, grey levels × 4) below this is never an edge. */
    val minGradient: Double = 24.0,
    /** Candidates returned at most. */
    val maxResults: Int = 8,
)

/**
 * W9 **classical rectangle detector**, pure Kotlin (research §4 option A; no native code, ~0 MB). Pipeline:
 * 1. at quarter resolution (≤ 10 Hz budget): 3×3 Sobel, non-maximum suppression, an adaptive threshold (80th percentile, ≥ [minGradient]);
 * 2. line-support regions grown by gradient angle (LSD idea, implemented from the paper), fitted by PCA, merged when
 *    collinear;
 * 3. quads from two pairs of roughly opposite segments with opposite gradient polarity (an object on a background),
 *    corners by line intersection; kept when convex, inside the image, corner angles 90° ± 45°, large enough and with
 *    edge support on all 4 sides;
 * 4. corners refined at half, then full resolution to sub-pixel by [CornerRefiner].
 *
 * Returned candidates are sorted **outermost first** (largest area): the caller lifts them to 3-D and keeps the first
 * one that is a metric rectangle (`QuadLift`), so a frame's outer border wins over its inner picture border.
 */
class QuadDetector(private val config: QuadDetectorConfig = QuadDetectorConfig()) {

    /** Detects quads in [full] (e.g. the 1280 × 720 CPU-image luma). */
    fun detect(full: GrayImage): List<DetectedQuad> = detect(full, null)

    /**
     * As [detect]; with [accept], a candidate's coarse corners (full-resolution pixels) must pass it before the costly
     * sub-pixel refinement, and the search stops at the first refined candidate (the caller's metric check, W9 budget).
     */
    fun detect(full: GrayImage, accept: ((List<Vec2>) -> Boolean)?): List<DetectedQuad> {
        // Search at quarter resolution (320 × 180 for a 1280 × 720 frame): 1/16 of the pixels. Quarter pixel i is
        // centred on full 4i + 1.5; half pixel i on full 2i + 0.5.
        val half = full.downsample2()
        val img = half.downsample2()
        val w = img.width
        val h = img.height
        val edges = Edges.compute(img, config.minGradient)
        val diag = hypot(w.toDouble(), h.toDouble())
        val segs = Segments.find(edges, config.angleToleranceDeg, config.minSegmentFrac * diag)
            .sortedByDescending { it.length }.take(config.maxSegments)
        val quads = buildQuads(segs, edges, w, h)
        val out = ArrayList<DetectedQuad>()
        for (q in quads) {
            val coarse = q.corners.map { Vec2(4 * it.x + 1.5, 4 * it.y + 1.5) }
            if (accept != null && !accept(coarse)) continue
            // Two-step refinement: at half resolution (± 4 half px = ± 8 full px), then at full resolution.
            val halfCorners = coarse.map { Vec2((it.x - 0.5) / 2, (it.y - 0.5) / 2) }
            val mid = CornerRefiner.refineQuad(half, halfCorners) ?: continue
            val approx = mid.map { Vec2(2 * it.point.x + 0.5, 2 * it.point.y + 0.5) }
            val refined = CornerRefiner.refineQuad(full, approx, FULL_SEARCH_PX) ?: continue
            val pts = refined.map { it.point }
            if (CornerRefiner.maxShift(pts, coarse) > MAX_REFINE_SHIFT_PX) continue
            if (!insideImage(pts, full.width, full.height)) continue
            if (out.any { CornerRefiner.maxShift(it.corners, pts) < 3.0 || CornerRefiner.maxShift(it.corners, pts.reversed()) < 3.0 }) continue
            out += DetectedQuad(pts, refined.map { it.sigmaPx }, q.support, area(pts))
            if (accept != null || out.size >= config.maxResults) break
        }
        return out.sortedByDescending { it.areaPx }
    }

    private class Line(val p: Vec2, val d: Vec2, val seg: Segments.Segment)

    private class Candidate(val corners: List<Vec2>, val support: Double, val area: Double)

    private fun buildQuads(segs: List<Segments.Segment>, edges: Edges, w: Int, h: Int): List<Candidate> {
        val lines = segs.map { Line(it.centre, it.direction, it) }
        val cosOpp = cos(Math.toRadians(config.maxOppositeDeg))
        // Opposite pairs: similar direction (either sense), opposite gradient polarity, apart.
        val pairs = ArrayList<IntArray>()
        val minSide = config.minSizeFrac * min(w, h)
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]
            val b = lines[j]
            if (abs(a.d.x * b.d.x + a.d.y * b.d.y) < cosOpp) continue
            if (a.seg.gradient.x * b.seg.gradient.x + a.seg.gradient.y * b.seg.gradient.y >= 0) continue
            val sep = abs((b.p.x - a.p.x) * -a.d.y + (b.p.y - a.p.y) * a.d.x)
            if (sep < minSide) continue
            pairs += intArrayOf(i, j)
        }
        val minCos = cos(Math.toRadians(90.0 - config.maxCornerDevDeg))
        val out = ArrayList<Candidate>()
        for (pi in pairs.indices) for (qi in pi + 1 until pairs.size) {
            val p = pairs[pi]
            val q = pairs[qi]
            if (p[0] == q[0] || p[0] == q[1] || p[1] == q[0] || p[1] == q[1]) continue
            val p1 = lines[p[0]]; val p2 = lines[p[1]]; val q1 = lines[q[0]]; val q2 = lines[q[1]]
            // The two families must cross at a real angle.
            if (abs(p1.d.x * q1.d.x + p1.d.y * q1.d.y) > minCos) continue
            val c = listOf(cross(p1, q1) ?: continue, cross(p1, q2) ?: continue, cross(p2, q2) ?: continue, cross(p2, q1) ?: continue)
            if (!insideImage(c, w, h)) continue
            if (!convexWithAngles(c, config.maxCornerDevDeg)) continue
            val sides = (0 until 4).map { c[it].distanceTo(c[(it + 1) % 4]) }
            val shortest = sides.min()
            if (shortest < minSide || shortest / sides.max() < config.minAspect) continue
            // Each segment must lie on its side (not on the line's extension far away).
            if (!onSide(p1.seg, c[0], c[1]) || !onSide(q2.seg, c[1], c[2]) || !onSide(p2.seg, c[2], c[3]) || !onSide(q1.seg, c[3], c[0])) continue
            var worst = 1.0
            val polarity = listOf(p1.seg.gradient, q2.seg.gradient, p2.seg.gradient, q1.seg.gradient)
            for (k in 0 until 4) {
                worst = min(worst, edges.support(c[k], c[(k + 1) % 4], config.angleToleranceDeg, polarity[k]))
                if (worst < config.minSupport) break
            }
            if (worst < config.minSupport) continue
            out += Candidate(c, worst, area(c))
        }
        // Outermost first; near-duplicates (same corners within 2 px) keep the better-supported one.
        out.sortByDescending { it.area }
        val kept = ArrayList<Candidate>()
        for (c in out) {
            val dup = kept.indexOfFirst { CornerRefiner.maxShift(sortedCorners(it.corners), sortedCorners(c.corners)) < 2.0 }
            if (dup < 0) kept += c else if (c.support > kept[dup].support) kept[dup] = c
            if (kept.size >= config.maxResults * 3) break
        }
        return kept
    }

    private fun onSide(s: Segments.Segment, a: Vec2, b: Vec2): Boolean {
        val d = b - a
        val l = d.length
        if (l < 1e-6) return false
        val u = Vec2(d.x / l, d.y / l)
        val t0 = (s.a.x - a.x) * u.x + (s.a.y - a.y) * u.y
        val t1 = (s.b.x - a.x) * u.x + (s.b.y - a.y) * u.y
        val lo = max(0.0, min(t0, t1))
        val hi = min(l, max(t0, t1))
        return hi - lo >= 0.5 * s.length
    }

    private fun cross(a: Line, b: Line): Vec2? {
        val den = a.d cross b.d
        if (abs(den) < 1e-6) return null
        val t = ((b.p - a.p) cross b.d) / den
        return a.p + a.d * t
    }

    companion object {
        /** Full-resolution search half-width after the half-resolution step (px). */
        const val FULL_SEARCH_PX = 3.0

        /** A refined corner may move at most this far from its coarse position (px), else the candidate is dropped. */
        const val MAX_REFINE_SHIFT_PX = 12.0

        internal fun insideImage(c: List<Vec2>, w: Int, h: Int): Boolean =
            c.all { it.x >= 0 && it.y >= 0 && it.x <= w - 1 && it.y <= h - 1 }

        internal fun area(c: List<Vec2>): Double {
            var s = 0.0
            for (i in c.indices) s += c[i] cross c[(i + 1) % c.size]
            return abs(s) / 2
        }

        /** Convex, and every interior angle within 90° ± [devDeg]. */
        internal fun convexWithAngles(c: List<Vec2>, devDeg: Double): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val a = c[(i + 3) % 4]; val b = c[i]; val d = c[(i + 1) % 4]
                val u = a - b; val v = d - b
                val cr = (b - a) cross (d - b)
                val s = if (cr > 0) 1 else if (cr < 0) -1 else 0
                if (s == 0 || (sign != 0 && s != sign)) return false
                sign = s
                val ang = Math.toDegrees(acos(((u.x * v.x + u.y * v.y) / (u.length * v.length)).coerceIn(-1.0, 1.0)))
                if (abs(ang - 90.0) > devDeg) return false
            }
            return true
        }

        private fun sortedCorners(c: List<Vec2>) = c.sortedWith(compareBy({ it.x + it.y }, { it.x }))
    }
}

/** Sobel gradients with non-maximum suppression (search resolution). */
internal class Edges(val width: Int, val height: Int, val gx: FloatArray, val gy: FloatArray, val mag: FloatArray, val isEdge: BooleanArray, val low: Double, val edgeOrder: IntArray) {

    /**
     * Fraction of the side a→b with an edge pixel (± 2 px across it) whose gradient is perpendicular to the side and,
     * when [polarity] is given, points the same way as it (a real border keeps its polarity; texture such as wood grain
     * alternates, so it cannot fake a side).
     */
    fun support(a: Vec2, b: Vec2, tolDeg: Double, polarity: Vec2? = null): Double {
        val d = b - a
        val l = d.length
        if (l < 2) return 0.0
        val ux = d.x / l; val uy = d.y / l
        val nx = -uy; val ny = ux
        val n = max(8, (l / 2).toInt())
        val cosTol = cos(Math.toRadians(tolDeg))
        var hits = 0
        for (i in 0 until n) {
            val s = l * (0.05 + 0.9 * (i + 0.5) / n)
            val px = a.x + ux * s
            val py = a.y + uy * s
            var found = false
            for (t in -2..2) {
                val x = Math.round(px + nx * t).toInt()
                val y = Math.round(py + ny * t).toInt()
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val k = y * width + x
                if (mag[k] < low) continue
                val m = mag[k].toDouble()
                val c = (gx[k] * nx + gy[k] * ny) / m
                val aligned = abs(c) >= cosTol
                val samePolarity = polarity == null || (gx[k] * polarity.x + gy[k] * polarity.y) > 0
                if (aligned && samePolarity) { found = true; break }
            }
            if (found) hits++
        }
        return hits.toDouble() / n
    }

    companion object {
        fun compute(img: GrayImage, minGradient: Double): Edges {
            val w = img.width
            val h = img.height
            val p = img.pixels
            val gx = FloatArray(w * h)
            val gy = FloatArray(w * h)
            val mag = FloatArray(w * h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val k = y * w + x
                val a = p[k - w - 1]; val b = p[k - w]; val c = p[k - w + 1]
                val d = p[k - 1]; val f = p[k + 1]
                val g = p[k + w - 1]; val hh = p[k + w]; val i = p[k + w + 1]
                val sx = (c + 2 * f + i) - (a + 2 * d + g)
                val sy = (g + 2 * hh + i) - (a + 2 * b + c)
                gx[k] = sx; gy[k] = sy
                mag[k] = sqrt(sx * sx + sy * sy)
            }
            // Adaptive low threshold: the 80th percentile of non-zero magnitudes, at least minGradient.
            val hist = IntArray(1024)
            var count = 0
            for (m in mag) if (m > 0) { hist[min(1023, m.toInt())]++; count++ }
            var acc = 0
            var p80 = 0
            for (i in hist.indices) { acc += hist[i]; if (acc >= 0.8 * count) { p80 = i; break } }
            val low = max(minGradient, p80.toDouble())
            val edge = BooleanArray(w * h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val k = y * w + x
                val m = mag[k]
                if (m < low) continue
                // Non-maximum suppression along the gradient (4 directions).
                val ax = abs(gx[k]); val ay = abs(gy[k])
                val (o1, o2) = when {
                    ay <= 0.414f * ax -> k - 1 to k + 1
                    ax <= 0.414f * ay -> k - w to k + w
                    (gx[k] > 0) == (gy[k] > 0) -> k - w - 1 to k + w + 1
                    else -> k - w + 1 to k + w - 1
                }
                if (m >= mag[o1] && m >= mag[o2]) edge[k] = true
            }
            // The edge pixels strongest first, by a counting sort (no boxing, W9 budget).
            val counts = IntArray(1024)
            var nEdges = 0
            for (k in edge.indices) if (edge[k]) {
                counts[min(1023, mag[k].toInt())]++
                nEdges++
            }
            val start = IntArray(1024)
            var acc2 = 0
            for (b in 1023 downTo 0) { start[b] = acc2; acc2 += counts[b] }
            val order = IntArray(nEdges)
            for (k in edge.indices) if (edge[k]) {
                val b = min(1023, mag[k].toInt())
                order[start[b]++] = k
            }
            return Edges(w, h, gx, gy, mag, edge, low, order)
        }
    }
}

/** Line-support regions and their segments. */
internal object Segments {
    /**
     * A straight segment a→b with its mean gradient (polarity) direction.
     */
    class Segment(val a: Vec2, val b: Vec2, val gradient: Vec2, val count: Int) {
        val length: Double get() = a.distanceTo(b)
        val centre: Vec2 get() = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
        val direction: Vec2 get() = (b - a).let { Vec2(it.x / it.length, it.y / it.length) }
    }

    fun find(e: Edges, tolDeg: Double, minLength: Double): List<Segment> {
        val w = e.width
        val used = BooleanArray(w * e.height)
        val order = e.edgeOrder
        val cosTol = cos(Math.toRadians(tolDeg))
        val region = IntArray(w * e.height)
        val segs = ArrayList<Segment>()
        for (seed in order) {
            if (used[seed]) continue
            var n = 0
            region[n++] = seed
            used[seed] = true
            // Trig-free (W9 budget): the region's mean gradient direction as a unit vector; a pixel joins when the cosine
            // between its unit gradient and that direction is at least cos(tolerance).
            var sc = (e.gx[seed] / e.mag[seed]).toDouble()
            var ss = (e.gy[seed] / e.mag[seed]).toDouble()
            var rx = sc
            var ry = ss
            var head = 0
            while (head < n) {
                val k = region[head++]
                val x = k % w
                val y = k / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= e.height) continue
                    val kk = yy * w + xx
                    if (used[kk] || !e.isEdge[kk]) continue
                    val m = e.mag[kk]
                    val ux = e.gx[kk] / m
                    val uy = e.gy[kk] / m
                    if (ux * rx + uy * ry < cosTol) continue
                    used[kk] = true
                    region[n++] = kk
                    sc += ux; ss += uy
                    val l = sqrt(sc * sc + ss * ss)
                    rx = sc / l; ry = ss / l
                }
            }
            val regAngle = atan2(ry, rx)
            if (n < minLength * 0.7) continue
            fit(region, n, w, regAngle)?.takeIf { it.length >= minLength }?.let { segs += it }
        }
        return merge(segs, minLength)
    }

    private fun fit(region: IntArray, n: Int, w: Int, gradAngle: Double): Segment? {
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += region[i] % w; my += region[i] / w }
        mx /= n; my /= n
        var cxx = 0.0; var cxy = 0.0; var cyy = 0.0
        for (i in 0 until n) {
            val dx = region[i] % w - mx; val dy = region[i] / w - my
            cxx += dx * dx; cxy += dx * dy; cyy += dy * dy
        }
        val ang = 0.5 * atan2(2 * cxy, cxx - cyy)
        val ux = cos(ang); val uy = sin(ang)
        var t0 = Double.MAX_VALUE; var t1 = -Double.MAX_VALUE
        var perp = 0.0
        for (i in 0 until n) {
            val dx = region[i] % w - mx; val dy = region[i] / w - my
            val t = dx * ux + dy * uy
            t0 = min(t0, t); t1 = max(t1, t)
            val q = -dx * uy + dy * ux
            perp += q * q
        }
        // A line-support region must be thin (an edge, not a blob of texture).
        if (sqrt(perp / n) > 1.5) return null
        return Segment(Vec2(mx + ux * t0, my + uy * t0), Vec2(mx + ux * t1, my + uy * t1), Vec2(cos(gradAngle), sin(gradAngle)), n)
    }

    /** Merges collinear segments (direction within 2°, same polarity, lateral offset < 1.5 px, gap < minLength / 2). */
    private fun merge(input: List<Segment>, minLength: Double): List<Segment> {
        val segs = input.toMutableList()
        val cos2 = cos(Math.toRadians(2.0))
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in segs.indices) for (j in i + 1 until segs.size) {
                val a = segs[i]; val b = segs[j]
                val da = a.direction; val db = b.direction
                if (abs(da.x * db.x + da.y * db.y) < cos2) continue
                if (a.gradient.x * b.gradient.x + a.gradient.y * b.gradient.y < 0.8) continue
                val nx = -da.y; val ny = da.x
                fun off(p: Vec2) = abs((p.x - a.a.x) * nx + (p.y - a.a.y) * ny)
                if (off(b.a) > 1.5 || off(b.b) > 1.5) continue
                val ts = listOf(0.0, a.length, (b.a - a.a).let { it.x * da.x + it.y * da.y }, (b.b - a.a).let { it.x * da.x + it.y * da.y })
                val bLo = min(ts[2], ts[3]); val bHi = max(ts[2], ts[3])
                val gap = max(bLo - a.length, -bHi)
                if (gap > minLength / 2) continue
                val lo = ts.min(); val hi = ts.max()
                val g = Vec2(a.gradient.x * a.count + b.gradient.x * b.count, a.gradient.y * a.count + b.gradient.y * b.count)
                val gl = g.length
                segs[i] = Segment(a.a + da * lo, a.a + da * hi, Vec2(g.x / gl, g.y / gl), a.count + b.count)
                segs.removeAt(j)
                changed = true
                break@loop
            }
        }
        return segs
    }
}
