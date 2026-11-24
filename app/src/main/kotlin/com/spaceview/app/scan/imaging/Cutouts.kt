package com.spaceview.app.scan.imaging

import com.spaceview.engine.ArgbImage
import com.spaceview.engine.CutoutPixels

/** What the Review says about the background (PLAN v3 W2). */
enum class CutoutStatus {
    /** A flat object (frame, TV): the picture is cropped exactly to its measured corners; there is no background. */
    FLAT,

    /**
     * Box / Other: the background was removed. The picture keeps the whole measured W × H canvas (the rectified photo
     * already is W × H): only the alpha changes, so nothing is cropped or stretched.
     */
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
 * @property cutout the same W × H canvas as [rectangle] with the background made transparent (alpha from the mask), or
 *   null when there is none. Never cropped: the rectified photo's edges are the measured outline, so cropping it to the
 *   subject and mapping the crop back onto W × H would stretch the object (PLAN v3 review MAJOR 5).
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
        // Trust check only (the bounds never crop): the outline was measured around the object, so whatever the mask
        // keeps visible (any alpha, so faint thin parts such as chair legs count) must nearly fill it on both axes. A
        // mask that leaves a large part of the outline empty found something else, or lost part of the object: keep
        // the rectangle rather than save an object with missing parts.
        val bounds = CutoutPixels.alphaBounds(masked, minAlpha = 1) ?: return CutoutChoice(photo, null, CutoutStatus.NOT_SURE)
        val bw = bounds[2] - bounds[0]; val bh = bounds[3] - bounds[1]
        if (bw < photo.width * MIN_BOUNDS_SHARE || bh < photo.height * MIN_BOUNDS_SHARE) {
            return CutoutChoice(photo, null, CutoutStatus.NOT_SURE)
        }
        return CutoutChoice(photo, masked, CutoutStatus.REMOVED)
    }

    /**
     * The visible part of the mask must span at least this share of the measured outline on each axis (trust check;
     * PLAN v3 review MAJOR 5 raised it from 0.5).
     */
    const val MIN_BOUNDS_SHARE = 0.85
}
