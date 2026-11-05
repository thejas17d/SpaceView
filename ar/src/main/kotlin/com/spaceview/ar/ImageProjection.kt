package com.spaceview.ar

import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import kotlin.math.max
import kotlin.math.min

/**
 * The CPU camera image (`Camera.getImageIntrinsics()`): the engine's pinhole [intrinsics] in that
 * image's pixels and native (sensor) orientation, plus its size. Copied out on the frame thread.
 */
class CpuImageCamera(val intrinsics: CameraIntrinsics, val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0)
    }

    /** The same camera at another resolution of the same image (e.g. intrinsics for 1280 wide, image 640 wide). */
    fun scaledTo(width: Int, height: Int): CpuImageCamera {
        if (width == widthPx && height == heightPx) return this
        val sx = width.toDouble() / widthPx
        val sy = height.toDouble() / heightPx
        val i = intrinsics
        return CpuImageCamera(CameraIntrinsics(i.fx * sx, i.fy * sy, i.cx * sx, i.cy * sy), width, height)
    }
}

/** Where the four projected face corners fall in the camera image. */
enum class CornerFit {
    /** All corners are inside the image (with the margin) and the face is big enough. */
    INSIDE,

    /** At least one corner is outside the image: step back (or re-aim). */
    OUTSIDE,

    /** The face covers too little of the image for a useful photo: step closer. */
    TOO_SMALL,

    /** A corner is behind (or level with) the camera. */
    BEHIND,
}

/**
 * World → CPU-image pixel projection, the inverse of the engine's `Ray.fromPixel` and with the same
 * conventions as [DepthPatch] (which back-projects raw depth with them): the camera **sensor** pose (`Camera.getPose()`, *not* the
 * display-oriented pose) has +X along the image rows (left → right), +Y up the image (against the
 * readout direction) and looks down −Z; pixel rows grow downwards. Display rotation therefore never
 * matters: the CPU image is always in sensor orientation, and so is this projection.
 *
 * Pure Kotlin (JVM-tested).
 */
object ImageProjection {

    /**
     * Pixel of world point [p] as (u, v) written into [out] at [offset]; false when the point is behind
     * the camera (closer than [MIN_DEPTH_M] along the view axis).
     */
    fun project(p: Vec3, sensorPose: Pose, intr: CameraIntrinsics, out: FloatArray, offset: Int = 0): Boolean =
        project(p, sensorPose.rotation.inverse(), sensorPose.translation, intr, out, offset)

    /** As [project] with the inverse camera rotation [inverseRotation] computed once by the caller. */
    private fun project(p: Vec3, inverseRotation: Quaternion, t: Vec3, intr: CameraIntrinsics, out: FloatArray, offset: Int): Boolean {
        // Camera-frame coordinates: R⁻¹ (p − t).
        val local = inverseRotation.rotate(p - t)
        val depth = -local.z
        if (depth < MIN_DEPTH_M) return false
        out[offset] = (intr.cx + intr.fx * local.x / depth).toFloat()
        out[offset + 1] = (intr.cy - intr.fy * local.y / depth).toFloat()
        return true
    }

    /**
     * Projects [corners] (in order) into [out] (x, y pairs) and classifies the result: every corner must
     * lie at least [marginFrac] × the short image side inside the image, and the projected face must span
     * at least [minSpanFrac] of the short side (else the photo would be too coarse).
     */
    fun fitCorners(
        corners: List<Vec3>,
        sensorPose: Pose,
        camera: CpuImageCamera,
        out: FloatArray,
        marginFrac: Double = EDGE_MARGIN_FRAC,
        minSpanFrac: Double = MIN_FACE_SPAN_FRAC,
    ): CornerFit {
        require(out.size >= corners.size * 2)
        val intr = camera.intrinsics
        val shortSide = min(camera.widthPx, camera.heightPx).toDouble()
        val margin = marginFrac * shortSide
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var outside = false
        val inverse = sensorPose.rotation.inverse()
        for (i in corners.indices) {
            if (!project(corners[i], inverse, sensorPose.translation, intr, out, i * 2)) return CornerFit.BEHIND
            val x = out[i * 2].toDouble()
            val y = out[i * 2 + 1].toDouble()
            if (x < margin || x > camera.widthPx - margin || y < margin || y > camera.heightPx - margin) outside = true
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (outside) return CornerFit.OUTSIDE
        return if (max(maxX - minX, maxY - minY) < minSpanFrac * shortSide) CornerFit.TOO_SMALL else CornerFit.INSIDE
    }

    /** Corners closer than this along the view axis count as behind the camera. */
    const val MIN_DEPTH_M = 0.05

    /** Each corner must be this far (× short image side) inside the image edge. Tunable. */
    const val EDGE_MARGIN_FRAC = 0.03

    /** The face must span at least this much of the short image side. Tunable. */
    const val MIN_FACE_SPAN_FRAC = 0.25
}
