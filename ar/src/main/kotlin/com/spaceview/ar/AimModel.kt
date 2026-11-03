package com.spaceview.ar

import com.spaceview.engine.LocalisationInput
import kotlin.math.max
import kotlin.math.min

/**
 * Camera-image intrinsics (ARCore `Camera.getImageIntrinsics()`: the CPU image, which is also the
 * freeze-frame still), copied out on the frame thread.
 *
 * @property focalPx focal length in pixels of that image.
 * @property widthPx / [heightPx] image size in pixels (sensor orientation).
 */
class ImageIntrinsics(val focalPx: Double, val widthPx: Int, val heightPx: Int) {
    init {
        require(focalPx.isFinite() && focalPx > 0 && widthPx > 0 && heightPx > 0)
    }

    val longSidePx: Int get() = max(widthPx, heightPx)
    val shortSidePx: Int get() = min(widthPx, heightPx)
}

/**
 * Pixel-error model of endpoint placement (M2 review B1). Every pixel error is expressed in
 * **camera-image pixels**, together with the focal length of that same image, so the localisation σ
 * (pixels × range / focal) depends on the camera and never on the display resolution.
 *
 * - Live aim: the user aligns the centre reticle with detail in the camera image. Detail finer than the
 *   camera image does not exist, so the error is [AIM_PIXELS_VGA] pixels of a VGA-class image (long side
 *   [REFERENCE_LONG_SIDE_PX]); a higher-resolution CPU image is normalised to that reference, so the
 *   angular error is the same whatever camera config ARCore picked. If the view were coarser than the
 *   image, [AIM_VIEW_PIXELS] view pixels (scaled view → image) would dominate instead.
 * - Freeze-frame adjustment: the still *is* the CPU image, so the placement error is
 *   [ADJUST_IMAGE_PIXELS] pixels of that image with its own focal length, or [ADJUST_VIEW_PIXELS] view
 *   pixels scaled to image pixels, whichever is larger (a still stretched 3–4× onto the view cannot be
 *   placed better than one of its own pixels).
 *
 * All values are placeholders to tune from the bench test (M0–M1 review measured ≈1.5 VGA pixels).
 */
object AimModel {
    /** 1σ reticle aim error in pixels of a VGA-class camera image (≈4 mm at 1 m with f ≈ 500 px). */
    const val AIM_PIXELS_VGA = 2.0

    /** 1σ reticle aim error in view pixels (only matters on a view coarser than the camera image). */
    const val AIM_VIEW_PIXELS = 2.0

    /** 1σ freeze-frame placement error in pixels of the still (CPU image). */
    const val ADJUST_IMAGE_PIXELS = 1.0

    /** 1σ freeze-frame placement error in view pixels (only matters when the still is shown shrunk). */
    const val ADJUST_VIEW_PIXELS = 1.0

    /** Long side of the reference (VGA) image the aim error is defined in. */
    const val REFERENCE_LONG_SIDE_PX = 640.0

    /**
     * View pixels per camera-image pixel when the image fills a [viewW] × [viewH] view (centre crop,
     * image rotated to the view's orientation).
     */
    fun viewPxPerImagePx(intr: ImageIntrinsics, viewW: Int, viewH: Int): Double {
        require(viewW > 0 && viewH > 0)
        val viewLong = max(viewW, viewH).toDouble()
        val viewShort = min(viewW, viewH).toDouble()
        return max(viewLong / intr.longSidePx, viewShort / intr.shortSidePx)
    }

    /** Aim localisation of a live reticle point at [rangeM] (null when the range is unusable). */
    fun aim(rangeM: Double, intr: ImageIntrinsics, viewPxPerImagePx: Double = Double.POSITIVE_INFINITY): LocalisationInput? {
        if (!(rangeM > 0) || !rangeM.isFinite()) return null
        val toReference = REFERENCE_LONG_SIDE_PX / intr.longSidePx
        val focalRef = intr.focalPx * toReference
        // View-pixel aim error converted to image pixels, then to reference pixels.
        val fromView = if (viewPxPerImagePx > 0) AIM_VIEW_PIXELS / viewPxPerImagePx * toReference else 0.0
        return LocalisationInput(rangeM, focalRef, max(AIM_PIXELS_VGA, fromView))
    }

    /** Placement localisation of a freeze-frame adjustment at [rangeM] on a still with [still] intrinsics. */
    fun adjust(rangeM: Double, still: ImageIntrinsics, viewPxPerImagePx: Double): LocalisationInput? {
        if (!(rangeM > 0) || !rangeM.isFinite()) return null
        val fromView = if (viewPxPerImagePx > 0) ADJUST_VIEW_PIXELS / viewPxPerImagePx else 0.0
        return LocalisationInput(rangeM, still.focalPx, max(ADJUST_IMAGE_PIXELS, fromView))
    }
}
