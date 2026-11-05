package com.spaceview.app.scan

import com.spaceview.app.gallery.GalleryFormatter
import com.spaceview.ar.ImprovementTip
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ScanDraft
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Uncertainty
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The three sizes of a scanned object. */
enum class ScanDim { WIDTH, HEIGHT, DEPTH }

/**
 * What the Review screen shows and Save stores, before the photo and name are added.
 *
 * @property diagonal front-face diagonal for frames and TVs ([ScanDraftBuilder.diagonalOf], the Gallery's
 *   rule): measured with its ± only while W and H are both measured, otherwise an estimate; null otherwise.
 */
data class ScanValues(
    val width: Dimension,
    val height: Dimension,
    val depth: Dimension,
    val diagonal: Dimension?,
    val quality: Quality,
    val calibrated: Boolean,
) {
    operator fun get(d: ScanDim): Dimension = when (d) {
        ScanDim.WIDTH -> width
        ScanDim.HEIGHT -> height
        ScanDim.DEPTH -> depth
    }

    /** True when every side is within what the gallery accepts (1 mm – 50 m). */
    val isSavable: Boolean
        get() = listOf(width, height, depth).all { GalleryRepository.isValidSide(it.meters) }
}

/**
 * Pure scan logic (JVM-tested): kind → capture shape and preset depth, measured values → stored
 * [Dimension]s that never shrink the ±, user edits → estimates, and the final [ScanDraft].
 *
 * Honesty rules: a measured value always carries a ± above zero (the tier floor guarantees it); a
 * preset ([DimensionSource.PRESET]) or typed ([DimensionSource.TYPED]) value is an estimate with no ±
 * (`bound95 = 0`); the badge only rates what is still measured.
 */
object ScanDraftBuilder {

    /** Preset depth of a wall-hung frame / mirror / poster (research §3.3: thin frame 2 cm, canvas 4 cm). Tunable. */
    const val FRAME_DEPTH_M = 0.03

    /** Preset depth of a wall-mounted flat TV / monitor. Tunable. */
    const val TV_DEPTH_M = 0.08

    /** Stored values are rounded to this (0.1 mm); a ± is rounded **up** to it. */
    private const val STEP_M = 1e-4

    fun shapeFor(kind: ObjectKind): ScanShape = when (kind) {
        ObjectKind.FRAME, ObjectKind.TV -> ScanShape.FLAT
        ObjectKind.APPLIANCE, ObjectKind.BOX, ObjectKind.OTHER -> ScanShape.BOX
    }

    /** The typed default depth of a flat, wall-hung object; null when depth is measured. */
    fun presetDepth(kind: ObjectKind): Double? = when (kind) {
        ObjectKind.FRAME -> FRAME_DEPTH_M
        ObjectKind.TV -> TV_DEPTH_M
        else -> null
    }

    /** Units context of the review screen (frames read in inches in AUTO, TV diagonals too). */
    fun contextFor(kind: ObjectKind): MeasureContext = GalleryFormatter.contextFor(kind)

    /** A measured value as stored: value to 0.1 mm, ±95 rounded **up** to 0.1 mm (never below the engine's). */
    fun measured(m: Measurement): Dimension {
        val bound = m.bound95
        val up = if (bound > 0) ceil(bound / STEP_M - 1e-9).coerceAtLeast(1.0) * STEP_M else STEP_M
        return Dimension.measured((m.value / STEP_M).roundToLong() * STEP_M, up)
    }

    /** Parses a typed size in [unit] (cm or in; "23 5/8" allowed) to metres, or null if invalid / out of range. */
    fun parseEdit(text: String, unit: GalleryFormatter.EditUnit): Double? = GalleryFormatter.parseLength(text, unit)

    /**
     * Values for the review: the outline's measurements, the kind's preset depth for a flat object, and
     * the user's [edits] (metres per dimension; an edited value becomes an estimate).
     */
    fun values(kind: ObjectKind, outline: ScanOutline, edits: Map<ScanDim, Double> = emptyMap()): ScanValues {
        val preset = presetDepth(kind) ?: FRAME_DEPTH_M
        val baseDepth = outline.depth?.let { measured(it) } ?: Dimension.preset(preset)
        fun pick(d: ScanDim, base: Dimension) = edits[d]?.let { Dimension.typed(it) } ?: base
        val w = pick(ScanDim.WIDTH, measured(outline.width))
        val h = pick(ScanDim.HEIGHT, measured(outline.height))
        val d = pick(ScanDim.DEPTH, baseDepth)

        val measuredDims = listOf(w, h, d).filter { !it.isEstimate }
        val untouched = measuredDims.size == listOf(outline.width, outline.height, outline.depth).count { it != null }
        val quality = when {
            measuredDims.isEmpty() -> Quality.ROUGH
            // Nothing edited: exactly the badge the user saw in AR (it includes the corner-geometry check).
            untouched -> outline.quality
            !outline.geometryConsistent -> Quality.ROUGH
            else -> Quality.worst(*measuredDims.map { qualityOf(it) }.toTypedArray())
        }
        return ScanValues(w, h, d, diagonal(kind, outline, w, h), quality, outline.calibrated && measuredDims.isNotEmpty())
    }

    @Suppress("UNUSED_PARAMETER")
    private fun diagonal(kind: ObjectKind, outline: ScanOutline, w: Dimension, h: Dimension): Dimension? {
        if (kind != ObjectKind.FRAME && kind != ObjectKind.TV) return null
        return diagonalOf(w, h)
    }

    /**
     * Front-face diagonal from the stored W and H, with **the same rule as the Gallery**
     * (`GalleryFormatter.diagonal`, M3 review m11), so one item never shows two different ±: when both
     * are measured, ±95 = (w·b_w + h·b_h) / d (fully correlated errors: an upper bound); otherwise an
     * estimate without ± (typed if it rests on a typed side, else preset).
     */
    fun diagonalOf(w: Dimension, h: Dimension): Dimension {
        val d = sqrt(w.meters * w.meters + h.meters * h.meters)
        if (!w.isEstimate && !h.isEstimate && d > 0) return Dimension.measured(d, (w.meters * w.bound95 + h.meters * h.bound95) / d)
        return if (w.source == DimensionSource.TYPED || h.source == DimensionSource.TYPED) Dimension.typed(d) else Dimension.preset(d)
    }

    private fun qualityOf(d: Dimension): Quality = Quality.of(Measurement(d.meters, d.bound95 / Uncertainty.K95))

    /** The tip worth showing on the review (only for Fair / Rough). Calibration does not carry into a scan, so it is never suggested. */
    fun reviewTip(quality: Quality, tip: ImprovementTip?): ImprovementTip? {
        if (quality == Quality.GOOD) return null
        return when (tip) {
            null, ImprovementTip.CALIBRATE -> ImprovementTip.MEASURE_AGAIN
            else -> tip
        }
    }

    /** The saved name: what was typed, else the suggested default; trimmed to the gallery's limit. */
    fun label(typed: String?, suggested: String): String =
        (typed?.trim()?.takeIf { it.isNotEmpty() } ?: suggested).take(GalleryRepository.MAX_LABEL)

    fun draft(
        kind: ObjectKind,
        values: ScanValues,
        frontJpeg: ByteArray?,
        label: String,
        tier: DeviceTier,
        arcoreVersion: String?,
    ): ScanDraft = ScanDraft(
        kind = kind,
        width = values.width,
        height = values.height,
        depth = values.depth,
        quality = values.quality,
        calibrated = values.calibrated,
        deviceTier = tier,
        frontJpeg = frontJpeg,
        label = label,
        arcoreVersion = arcoreVersion,
        // A scan never applies a calibration (it only lives inside the AR session that made it).
        calibrationFactor = 1.0,
    )
}
