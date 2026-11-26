package com.spaceview.app.scan

import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.CaptureCamera
import com.spaceview.engine.Measurement
import com.spaceview.engine.Pose
import com.spaceview.engine.Quality
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3

/**
 * The scan's Review as plain numbers, so it survives process death in `SavedStateHandle` (PLAN v3.1 W8: the adjusted
 * corners must be kept). Nothing here goes to Room (schema v3 is unchanged). Pure (JVM-tested).
 */
object ReviewSnapshot {
    private const val NONE = Double.NaN

    /** [ScanOutline] → numbers (version 1). */
    fun encodeOutline(o: ScanOutline): DoubleArray {
        val out = ArrayList<Double>(64)
        out += 1.0 // version
        out += o.shape.ordinal.toDouble()
        fun m(x: Measurement?) { out += x?.value ?: NONE; out += x?.sigma ?: NONE }
        fun v(p: Vec3) { out += p.x; out += p.y; out += p.z }
        m(o.width); m(o.height); m(o.depth); m(o.diagonal)
        out += o.quality.ordinal.toDouble()
        out += if (o.calibrated) 1.0 else 0.0
        out += if (o.geometryConsistent) 1.0 else 0.0
        o.face.corners.forEach(::v)
        v(o.face.normal)
        out += o.choice.edge.toDouble()
        v(o.choice.up); v(o.choice.towards)
        return out.toDoubleArray()
    }

    /** Numbers → [ScanOutline]; null for anything malformed (the Review then starts over, as before W8). */
    fun decodeOutline(a: DoubleArray?): ScanOutline? = runCatching {
        if (a == null || a.size != OUTLINE_SIZE || a[0] != 1.0) return null
        var i = 1
        fun next() = a[i++]
        fun m(): Measurement? { val value = next(); val sigma = next(); return if (value.isNaN()) null else Measurement(value, sigma) }
        fun v() = Vec3(next(), next(), next())
        val shape = ScanShape.entries[next().toInt()]
        val w = m()!!; val h = m()!!; val d = m(); val diag = m()
        val quality = Quality.entries[next().toInt()]
        val calibrated = next() == 1.0
        val consistent = next() == 1.0
        val corners = List(4) { v() }
        val normal = v()
        val edge = next().toInt()
        val up = v(); val towards = v()
        ScanOutline(shape, w, h, d, diag, quality, calibrated, consistent, FrontFace(corners, normal), FaceChoice(edge, up, towards))
    }.getOrNull()

    /** [CaptureCamera] → 13 numbers. */
    fun encodeCamera(c: CaptureCamera): DoubleArray {
        val t = c.pose.translation; val r = c.pose.rotation; val i = c.intrinsics
        return doubleArrayOf(t.x, t.y, t.z, r.x, r.y, r.z, r.w, i.fx, i.fy, i.cx, i.cy, c.widthPx.toDouble(), c.heightPx.toDouble())
    }

    fun decodeCamera(a: DoubleArray?): CaptureCamera? = runCatching {
        if (a == null || a.size != 13 || a.any { !it.isFinite() }) return null
        CaptureCamera(
            Pose(Vec3(a[0], a[1], a[2]), Quaternion(a[3], a[4], a[5], a[6])),
            CameraIntrinsics(a[7], a[8], a[9], a[10]),
            a[11].toInt(), a[12].toInt(),
        )
    }.getOrNull()

    /** Typed edits as (width, height, depth) metres, NaN = not edited. */
    fun encodeEdits(edits: Map<ScanDim, Double>): DoubleArray = DoubleArray(ScanDim.entries.size) { edits[ScanDim.entries[it]] ?: NONE }

    fun decodeEdits(a: DoubleArray?): Map<ScanDim, Double> {
        if (a == null || a.size != ScanDim.entries.size) return emptyMap()
        return ScanDim.entries.withIndex().filter { a[it.index].isFinite() && a[it.index] > 0 }.associate { it.value to a[it.index] }
    }

    /** Corners must be 8 finite numbers. */
    fun validCorners(c: FloatArray?): FloatArray? = c?.takeIf { it.size == 8 && it.all { v -> v.isFinite() } }

    private const val OUTLINE_SIZE = 1 + 1 + 8 + 3 + 12 + 3 + 1 + 6
}
