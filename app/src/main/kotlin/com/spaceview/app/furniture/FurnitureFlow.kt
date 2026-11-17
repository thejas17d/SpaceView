package com.spaceview.app.furniture

import com.spaceview.app.scan.ScanDraftBuilder
import com.spaceview.data.gallery.CaptureMethod
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.TemplateDraft
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.furniture.FurniturePalettes
import com.spaceview.engine.furniture.FurnitureTemplate
import com.spaceview.engine.furniture.ParamError
import com.spaceview.engine.furniture.ParamKind
import com.spaceview.engine.furniture.ParamSpec
import com.spaceview.engine.furniture.Params
import com.spaceview.engine.furniture.Templates

/** Steps of the furniture flow. */
enum class FurnitureStep { PICK, OPTIONS, MEASURE, PREVIEW, REVIEW, SAVED }

/**
 * What the user set for one template, as plain numbers (kept in `SavedStateHandle`, so it survives process death).
 *
 * @property lengths lengths the user measured (MEASURED, with ±) or typed (TYPED, no ±). Anything absent uses the
 *   template's typical default, shown as an estimate.
 * @property options counts and choices the user picked (seats, doors, side…).
 * @property presetId a starting point (3-seat, queen…) whose values prefill what the user has not set.
 */
data class FurnitureInputs(
    val templateId: String,
    val lengths: Map<String, Dimension> = emptyMap(),
    val options: Map<String, Int> = emptyMap(),
    val presetId: String? = null,
    val paletteIndex: Int = 0,
)

/**
 * The furniture flow's pure rules (JVM-tested): which values the template is built from, which steps the guided
 * measure has, when Save is allowed, and the [TemplateDraft].
 *
 * Honesty rules (as the scan flow): a measured length keeps its ± (rounded **up** to 0.1 mm); a typed one is an
 * estimate without ±; W, D and H must each be measured or typed before Save (a typical default is never saved as the
 * item's size); the other parameters may stay at their typical values.
 */
object FurnitureFlow {
    val REQUIRED = listOf(FurnitureTemplate.W, FurnitureTemplate.D, FurnitureTemplate.H)

    fun template(id: String?): FurnitureTemplate? = id?.let(Templates::byId)

    /** The parameter map the model is built from: explicit values over the preset over the defaults. */
    fun params(t: FurnitureTemplate, inputs: FurnitureInputs): Params {
        val given = HashMap<String, Double>()
        t.presets.firstOrNull { it.id == inputs.presetId }?.let { given.putAll(it.values) }
        inputs.options.forEach { (k, v) -> given[k] = v.toDouble() }
        inputs.lengths.forEach { (k, d) -> given[k] = d.meters }
        return t.resolve(given)
    }

    /** The guided measure: W, D, H, then the template's key extras that apply to these values. */
    fun measureSteps(t: FurnitureTemplate, params: Params): List<ParamSpec> =
        t.params.take(3) + t.guidedExtras.filter { it.id in params }

    /** True when the template asks for options or offers presets before measuring. */
    fun hasOptions(t: FurnitureTemplate): Boolean = t.options.isNotEmpty() || t.presets.isNotEmpty()

    /** The value of length [id] as shown: the user's (measured or typed), else the typical default as an estimate. */
    fun dimension(inputs: FurnitureInputs, params: Params, id: String): Dimension? =
        inputs.lengths[id] ?: params[id]?.let { Dimension.preset(it) }

    /** W, D and H that the user has neither measured nor typed yet. */
    fun missingRequired(inputs: FurnitureInputs): List<String> = REQUIRED.filter { id ->
        inputs.lengths[id]?.source.let { it != DimensionSource.MEASURED && it != DimensionSource.TYPED }
    }

    fun errors(t: FurnitureTemplate, params: Params): List<ParamError> = t.validate(params)

    /** The error for one parameter (null when it is fine). */
    fun errorFor(t: FurnitureTemplate, params: Params, id: String): ParamError? = errors(t, params).firstOrNull { it.paramId == id }

    /** The error [value] would cause for parameter [id] (before it is used). */
    fun errorIfSet(t: FurnitureTemplate, inputs: FurnitureInputs, id: String, value: Double): ParamError? {
        val spec = t.spec(id) ?: return null
        val trial = if (spec.kind == ParamKind.LENGTH) {
            inputs.copy(lengths = inputs.lengths + (id to Dimension.typed(value)))
        } else {
            inputs.copy(options = inputs.options + (id to value.toInt()))
        }
        return errorFor(t, params(t, trial), id)
    }

    fun canSave(t: FurnitureTemplate, inputs: FurnitureInputs): Boolean =
        missingRequired(inputs).isEmpty() && errors(t, params(t, inputs)).isEmpty()

    /** A measured length as stored (value to 0.1 mm, ± rounded up), exactly as the scan flow stores it. */
    fun measured(m: Measurement): Dimension = ScanDraftBuilder.measured(m)

    /** The saved name: what was typed, else the template's name. */
    fun label(typed: String?, suggested: String): String = ScanDraftBuilder.label(typed, suggested)

    /**
     * The draft to save. Requires [canSave]. The stored parameters include the palette index
     * ([FurniturePalettes.KEY]) so a regenerated model keeps its colours.
     */
    fun draft(t: FurnitureTemplate, inputs: FurnitureInputs, label: String, tier: DeviceTier, arcoreVersion: String?): TemplateDraft {
        require(canSave(t, inputs)) { "Not ready to save: ${missingRequired(inputs)} ${errors(t, params(t, inputs))}" }
        val params = params(t, inputs)
        val anyMeasured = inputs.lengths.values.any { it.source == DimensionSource.MEASURED }
        fun dim(id: String) = inputs.lengths.getValue(id)
        val palette = inputs.paletteIndex.takeIf { it in 0 until FurniturePalettes.count } ?: 0
        return TemplateDraft(
            templateId = t.id,
            params = params + (FurniturePalettes.KEY to palette.toDouble()),
            width = dim(FurnitureTemplate.W),
            height = dim(FurnitureTemplate.H),
            depth = dim(FurnitureTemplate.D),
            label = label.take(GalleryRepository.MAX_LABEL),
            parts = t.build(params),
            palette = FurniturePalettes.of(palette),
            deviceTier = tier,
            calibrated = false,
            // A measurement here never applies a calibration (it only lives inside the AR session that made it).
            calibrationFactor = if (anyMeasured) 1.0 else null,
            arcoreVersion = if (anyMeasured) arcoreVersion else null,
            captureMethod = if (anyMeasured) CaptureMethod.MANUAL else null,
        )
    }

    // ---- SavedStateHandle encoding (plain strings of numbers) -------------------------------------------------

    /** `id=meters,bound95,SOURCE;…` */
    fun encodeLengths(lengths: Map<String, Dimension>): String =
        lengths.entries.joinToString(";") { (k, d) -> "$k=${d.meters},${d.bound95},${d.source.name}" }

    /** Inverse of [encodeLengths]; malformed entries are dropped. */
    fun decodeLengths(s: String?): Map<String, Dimension> {
        if (s.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Dimension>()
        for (entry in s.split(';')) {
            val (k, v) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            val f = v.split(',')
            if (f.size != 3) continue
            val m = f[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            val b = f[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: continue
            val src = DimensionSource.entries.firstOrNull { it.name == f[2] } ?: continue
            out[k] = Dimension(m, b, src)
        }
        return out
    }

    /** `id=value;…` */
    fun encodeOptions(options: Map<String, Int>): String = options.entries.joinToString(";") { (k, v) -> "$k=$v" }

    fun decodeOptions(s: String?): Map<String, Int> {
        if (s.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in s.split(';')) {
            val (k, v) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            out[k] = v.toIntOrNull() ?: continue
        }
        return out
    }
}
