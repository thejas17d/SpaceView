package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3

/**
 * A small window of ARCore raw depth (16-bit millimetres + 8-bit confidence) copied out of the
 * acquired images on the frame thread, so the images can be closed at once and the back-projection
 * runs on the measurement worker.
 *
 * @property x0 / [y0] top-left of the window in depth-image pixels.
 * @property fx / [fy] / [cx] / [cy] pinhole intrinsics already scaled to the depth image resolution.
 * @property sensorPose the camera **sensor** pose (`Camera.getPose()`), which is the frame the
 *   depth image lives in (not the display-oriented pose).
 */
class DepthPatch(
    val width: Int,
    val height: Int,
    val x0: Int,
    val y0: Int,
    val depthMm: ShortArray,
    val confidence: ByteArray,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val sensorPose: Pose,
) {
    init {
        require(depthMm.size >= width * height && confidence.size >= width * height)
    }

    /**
     * Back-projects confident pixels to world points (ARCore camera convention: +X right, +Y up,
     * looking down −Z; image rows grow downwards). Writes packed x, y, z into [out] and returns the
     * number of points written.
     */
    fun toWorldPoints(
        out: FloatArray,
        minConfidence: Int = DEFAULT_MIN_CONFIDENCE,
        minDepthM: Double = 0.2,
        maxDepthM: Double = 5.0,
    ): Int {
        var n = 0
        val capacity = out.size / 3
        for (r in 0 until height) for (c in 0 until width) {
            if (n >= capacity) return n
            val i = r * width + c
            val conf = confidence[i].toInt() and 0xFF
            if (conf < minConfidence) continue
            val d = (depthMm[i].toInt() and 0xFFFF) / 1000.0
            if (d < minDepthM || d > maxDepthM) continue
            val u = (x0 + c) + 0.5
            val v = (y0 + r) + 0.5
            val local = Vec3(d * (u - cx) / fx, d * (cy - v) / fy, -d)
            val w = sensorPose.transformPoint(local)
            out[n * 3] = w.x.toFloat()
            out[n * 3 + 1] = w.y.toFloat()
            out[n * 3 + 2] = w.z.toFloat()
            n++
        }
        return n
    }

    companion object {
        /** Raw-depth confidence threshold (0..255); research suggests ≈ 200. Placeholder to tune. */
        const val DEFAULT_MIN_CONFIDENCE = 200
    }
}
