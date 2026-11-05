package com.spaceview.app.scan.imaging

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Build
import com.spaceview.engine.Vec2
import kotlin.math.sqrt

/**
 * Straightens the front face of a scanned object into a texture with the measured W:H aspect
 * (UI research §3.3 A.4: "we crop and straighten the photo using the measured outline").
 *
 * Pure-Kotlin maths ([RectifyMath]: engine [com.spaceview.engine.Homography] + inverse-mapped bilinear warp
 * over `IntArray` buffers), no OpenCV. Only the quad's bounding box is read from the bitmap. Call off the main
 * thread (e.g. `Dispatchers.Default`): about 1–1.5 M pixel operations for a 1024 px output.
 *
 * Source size: meant for the ARCore CPU camera image or a still of at most about 2–3 MP. A larger window (a 12 MP
 * still where the object fills the frame) is first cropped and scaled down to [MAX_WINDOW_PIXELS] in one
 * `Bitmap.createBitmap` call, so the `IntArray` window never exceeds ~16 MB on 3–4 GB phones.
 */
object FrontPhotoRectifier {

    /**
     * @param source camera frame or still (any config; HARDWARE bitmaps are copied first).
     * @param cornersPx the 4 face corners in [source] pixel coordinates, starting at the top-left and running
     *   clockwise **or** counter-clockwise on screen (the engine reorders, so the texture is never mirrored).
     * @param aspectWOverH measured width / height of the face.
     * @param maxLongSide output long side cap; the output is never larger than the detail in the photo.
     * @return an ARGB_8888 bitmap of the face, W:H = [aspectWOverH].
     * @throws IllegalArgumentException for a degenerate or non-convex quad, or bad parameters.
     */
    fun rectify(source: Bitmap, cornersPx: List<PointF>, aspectWOverH: Double, maxLongSide: Int = 1024): Bitmap {
        require(cornersPx.size == 4) { "Need exactly 4 corners" }
        val corners = cornersPx.map { Vec2(it.x.toDouble(), it.y.toDouble()) }
        require(corners.all { it.x.isFinite() && it.y.isFinite() }) { "Corners must be finite" }
        val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException("Cannot read a hardware bitmap")
        } else {
            source
        }
        try {
            val plan = RectifyMath.plan(readable.width, readable.height, corners, aspectWOverH, maxLongSide)
            val windowPixels = plan.cropWidth.toLong() * plan.cropHeight
            if (windowPixels > MAX_WINDOW_PIXELS) {
                // 90%: the 1 px margins of the next plan must not push it over the limit again.
                val s = sqrt(0.9 * MAX_WINDOW_PIXELS / windowPixels).toFloat()
                val small = Bitmap.createBitmap(
                    readable, plan.cropLeft, plan.cropTop, plan.cropWidth, plan.cropHeight,
                    Matrix().apply { setScale(s, s) }, true,
                )
                try {
                    val sx = small.width.toFloat() / plan.cropWidth
                    val sy = small.height.toFloat() / plan.cropHeight
                    val local = cornersPx.map { PointF((it.x - plan.cropLeft) * sx, (it.y - plan.cropTop) * sy) }
                    return rectify(small, local, aspectWOverH, maxLongSide)
                } finally {
                    small.recycle()
                }
            }
            val window = IntArray(plan.cropWidth * plan.cropHeight)
            readable.getPixels(window, 0, plan.cropWidth, plan.cropLeft, plan.cropTop, plan.cropWidth, plan.cropHeight)
            val (pixels, w, h) = RectifyMath.warpPlanned(window, plan, corners)
                ?: throw IllegalArgumentException("The corners do not form a convex quadrilateral")
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            if (readable !== source) readable.recycle()
        }
    }

    /** Largest source window read into memory at once (4 MP = 16 MB of ARGB). */
    const val MAX_WINDOW_PIXELS = 4_000_000L
}
