package com.spaceview.data.gallery

/**
 * What a gallery item is. Stored by name: never rename an entry (unknown names read back as [OTHER]).
 * [FURNITURE] is a parametric template saved by earlier test builds (v1 makes none, PLAN v3).
 */
enum class ObjectKind { FRAME, TV, APPLIANCE, BOX, OTHER, FURNITURE }

/**
 * How the points of a measurement or scan were captured (v3 `capture_method`, NULL = before v3). Stored by name.
 */
enum class CaptureMethod { MANUAL, HANDS_FREE, AUTO_RECT, DEPTH_SCAN }

/**
 * Where a dimension's value comes from. Stored by name: never rename an entry.
 * - [MEASURED]: from the AR measurement, with its 95% ±.
 * - [PRESET]: a default the scan flow filled in (e.g. a frame's depth); not measured, no ±.
 * - [TYPED]: typed by the user (scan Review or gallery edit); not measured, no ±.
 */
enum class DimensionSource { MEASURED, PRESET, TYPED }

/**
 * One dimension in metres with its 95% ± (metres). Only [DimensionSource.MEASURED] values carry a ±;
 * the gallery refuses a measured value whose ± is 0 (every measured number shows its tolerance).
 */
data class Dimension(val meters: Double, val bound95: Double, val source: DimensionSource) {
    /** Compatibility: isEstimate = true means [DimensionSource.PRESET]; pass [DimensionSource.TYPED] for typed values. */
    constructor(meters: Double, bound95: Double, isEstimate: Boolean) :
        this(meters, bound95, if (isEstimate) DimensionSource.PRESET else DimensionSource.MEASURED)

    /** True when the value was not measured (preset or typed): shown with "~" and without a ±. */
    val isEstimate: Boolean get() = source != DimensionSource.MEASURED

    companion object {
        fun measured(meters: Double, bound95: Double) = Dimension(meters, bound95, DimensionSource.MEASURED)
        fun preset(meters: Double) = Dimension(meters, 0.0, DimensionSource.PRESET)
        fun typed(meters: Double) = Dimension(meters, 0.0, DimensionSource.TYPED)
    }
}

/**
 * The v1 scan model (PLAN v3 W2): the object's picture, already cropped so that its edges are the measured W × H
 * (a flat object's rectified front, or a segmented subject's mask bounding box). Straight-alpha pixels: the
 * background has alpha 0 when it was removed; a fully opaque picture is a plain rectangle (flat objects, "Undo
 * cutout", or a segmentation that could not be trusted).
 *
 * @property image null = no photo: the model is a plain, true-size panel.
 */
class CutoutDraft(val image: com.spaceview.engine.ArgbImage?)

/**
 * Everything the scan flow hands to the gallery.
 *
 * @property cutout v1 (PLAN v3): the flat, background-free, true-size picture; the item becomes a
 *   [com.spaceview.engine.ModelKind.CUTOUT]. Null = the M3 proxy box path with [frontJpeg] (kept for legacy callers).
 * @property frontJpeg the already-rectified front photo (W:H aspect) for the proxy box path; ignored with a [cutout].
 * @property calibrationFactor scale factor that was applied to the measured sizes (1.0 = none; null = unknown).
 *   With the stored measured values it gives the raw (uncalibrated) lengths for a later re-tune.
 */
class ScanDraft(
    val kind: ObjectKind,
    val width: Dimension, val height: Dimension, val depth: Dimension,
    val quality: com.spaceview.engine.Quality,
    val calibrated: Boolean,
    val deviceTier: com.spaceview.engine.DeviceTier,
    val frontJpeg: ByteArray?,
    val label: String?,
    val arcoreVersion: String?,
    val calibrationFactor: Double? = null,
    val captureMethod: CaptureMethod? = CaptureMethod.MANUAL,
    val cutout: CutoutDraft? = null,
)
