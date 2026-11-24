package com.spaceview.app.scan.imaging

import com.spaceview.engine.ArgbImage
import com.spaceview.engine.CutoutPixels

/** What the Review says about the background (PLAN v3 W2). */
enum class CutoutStatus {
    /** A flat object (frame, TV): the picture is cropped exactly to its measured corners; there is no background. */
    FLAT,

    /** Box / Other: the background was removed and the subject's bounds map to the measured W × H. */
    REMOVED,

    /** Segmentation could not run (Google Play services or its model not ready, an error, a timeout): rectangle kept. */
    NOT_AVAILABLE,

    /** Segmentation ran but its mask could not be trusted (object not found, a confused mask): rectangle kept. */
    NOT_SURE,
}

/**
 * The pictures the Review can save for one front photo.
 *
 * @property rectangle the rectified front photo, opaque, cropped to the measured outline (always available: what
 *   "Undo cutout" keeps).
 * @property cutout the background-free subject cropped to its mask's bounding box, or null when there is none.
 */
class CutoutChoice(val rectangle: ArgbImage, val cutout: ArgbImage?, val status: CutoutStatus)

/** Pure decision from a rectified photo and an optional segmentation mask (JVM-tested). */
object CutoutMaker {
    /**
     * @param flat true for FRAME / TV: no segmentation, the rectangle is the object.
     * @param mask ML Kit's foreground confidence per pixel of [photo] (row-major), or null when segmentation failed.
     */
    fun choose(photo: ArgbImage, flat: Boolean, mask: FloatArray?): CutoutChoice {
        if (flat) return CutoutChoice(photo, null, CutoutStatus.FLAT)
        if (mask == null || mask.size != photo.argb.size) return CutoutChoice(photo, null, CutoutStatus.NOT_AVAILABLE)
        if (!CutoutPixels.trusted(CutoutPixels.stats(mask))) return CutoutChoice(photo, null, CutoutStatus.NOT_SURE)
        val masked = CutoutPixels.applyMask(photo, mask)
        val bounds = CutoutPixels.alphaBounds(masked) ?: return CutoutChoice(photo, null, CutoutStatus.NOT_SURE)
        // A subject squeezed into a sliver of the outline is a wrong mask (another object, a shadow), not the object.
        val bw = bounds[2] - bounds[0]; val bh = bounds[3] - bounds[1]
        if (bw < photo.width * MIN_BOUNDS_SHARE || bh < photo.height * MIN_BOUNDS_SHARE) {
            return CutoutChoice(photo, null, CutoutStatus.NOT_SURE)
        }
        return CutoutChoice(photo, CutoutPixels.crop(masked, bounds), CutoutStatus.REMOVED)
    }

    /**
     * The subject's bounds must span at least this share of the measured outline on each axis: the outline was
     * measured around the object, so its mask should nearly fill it.
     */
    const val MIN_BOUNDS_SHARE = 0.5
}
