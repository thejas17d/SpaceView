package com.spaceview.ar

import com.spaceview.engine.Pose

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
        // Rotation matrix of the sensor pose, so each pixel is transformed without allocating (minor 8).
        val q = sensorPose.rotation
        val r00 = 1 - 2 * (q.y * q.y + q.z * q.z); val r01 = 2 * (q.x * q.y - q.z * q.w); val r02 = 2 * (q.x * q.z + q.y * q.w)
        val r10 = 2 * (q.x * q.y + q.z * q.w); val r11 = 1 - 2 * (q.x * q.x + q.z * q.z); val r12 = 2 * (q.y * q.z - q.x * q.w)
        val r20 = 2 * (q.x * q.z - q.y * q.w); val r21 = 2 * (q.y * q.z + q.x * q.w); val r22 = 1 - 2 * (q.x * q.x + q.y * q.y)
        val t = sensorPose.translation
        for (r in 0 until height) for (c in 0 until width) {
            if (n >= capacity) return n
            val i = r * width + c
            val conf = confidence[i].toInt() and 0xFF
            if (conf < minConfidence) continue
            val d = (depthMm[i].toInt() and 0xFFFF) / 1000.0
            if (d < minDepthM || d > maxDepthM) continue
            val u = (x0 + c) + 0.5
            val v = (y0 + r) + 0.5
            val lx = d * (u - cx) / fx
            val ly = d * (cy - v) / fy
            val lz = -d
            out[n * 3] = (r00 * lx + r01 * ly + r02 * lz + t.x).toFloat()
            out[n * 3 + 1] = (r10 * lx + r11 * ly + r12 * lz + t.y).toFloat()
            out[n * 3 + 2] = (r20 * lx + r21 * ly + r22 * lz + t.z).toFloat()
            n++
        }
        return n
    }

    companion object {
        /** Raw-depth confidence threshold (0..255); research suggests ≈ 200. Placeholder to tune. */
        const val DEFAULT_MIN_CONFIDENCE = 200
    }
}
