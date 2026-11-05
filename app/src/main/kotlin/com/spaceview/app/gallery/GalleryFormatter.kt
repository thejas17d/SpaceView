package com.spaceview.app.gallery

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.ContextFormatted
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units
import java.util.Locale
import kotlin.math.sqrt

/**
 * Display text for gallery items, always through the engine's precision-honest [Units] (a value is never
 * shown more precisely than its ±; estimates have no ± and are marked "~"). Pure; JVM-unit-tested.
 */
object GalleryFormatter {

    /** Marker in front of an estimated (typed / preset) value in compact text. */
    const val ESTIMATE_MARK = "~"

    /**
     * Unit context of an item (UI research §6.4): frames and posters read in inches (as frames are sold),
     * everything else as objects in cm. A TV's W × H × D uses the object context; its diagonal is in inches.
     */
    fun contextFor(kind: ObjectKind): MeasureContext = if (kind == ObjectKind.FRAME) MeasureContext.FRAME else MeasureContext.OBJECT

    fun primaryUnit(kind: ObjectKind, pref: UnitPreference): LengthUnit = Units.defaultUnits(contextFor(kind), pref).first

    fun secondaryUnit(kind: ObjectKind, pref: UnitPreference): LengthUnit = Units.defaultUnits(contextFor(kind), pref).second

    /** The engine measurement behind a stored dimension (σ = bound95 / 1.96; estimates have σ = 0). */
    fun measurement(d: Dimension): Measurement = Measurement(d.meters, if (d.isEstimate) 0.0 else d.bound95 / Uncertainty.K95)

    /** One dimension with ± and secondary unit, e.g. "61.0 cm ± 0.5 (24 in)". */
    fun dimension(d: Dimension, kind: ObjectKind, pref: UnitPreference): ContextFormatted =
        Units.formatForContext(measurement(d), contextFor(kind), pref)

    /** "45.5 × 61 × ~2.5 cm" / "18 × 24 × ~1 in" / "1′ 6″ × 2′ × 1″" in [unit] (default: the item's primary unit). */
    fun compactDims(item: GalleryItem, pref: UnitPreference, unit: LengthUnit = primaryUnit(item.kind, pref)): String {
        val values = listOf(item.width, item.height, item.depth).joinToString(" × ") { d ->
            val v = Units.format(measurement(d), unit).value
            if (d.isEstimate) "$ESTIMATE_MARK$v" else v
        }
        return if (unit == LengthUnit.FEET_INCHES) values else "$values ${unit.symbol}"
    }

    /** Largest ± of the measured dimensions in the item's primary unit ("± 1 cm", "± 1/4 in"); null if none is measured. */
    fun maxPlusMinus(item: GalleryItem, pref: UnitPreference): String? {
        val bound = listOf(item.width, item.height, item.depth).filter { !it.isEstimate }.maxOfOrNull { it.bound95 } ?: return null
        if (bound <= 0.0) return null
        val unit = primaryUnit(item.kind, pref)
        val pm = Units.format(Measurement(0.0, bound / Uncertainty.K95), unit).plusMinus ?: return null
        return if (unit == LengthUnit.FEET_INCHES) "± $pm" else "± $pm ${unit.symbol}"
    }

    /**
     * Diagonal of the front face (TVs and monitors), and whether it rests on an estimate.
     *
     * The stored W and H ± are totals whose largest part, the tracking scale error, is shared by both sides, so
     * they must not be combined as independent (that understated the engine's diagonal ± by 20–27%). The
     * fully-correlated bound b_d = (w·b_w + h·b_h) / d is an upper bound for any correlation between the sides,
     * hence never below the engine's own diagonal ±, and it stays valid after an edit.
     *
     * When W or H is not measured, the diagonal is an estimate too: σ = 0, shown with "~" and no ±.
     */
    fun diagonal(item: GalleryItem): Pair<Measurement, Boolean> {
        val w = item.width
        val h = item.height
        val d = sqrt(w.meters * w.meters + h.meters * h.meters)
        val estimate = w.isEstimate || h.isEstimate
        if (estimate) return Measurement(d, 0.0) to true
        val bound95 = (w.meters * w.bound95 + h.meters * h.bound95) / d
        return Measurement(d, bound95 / Uncertainty.K95) to false
    }

    /**
     * Short TV diagonal for the card and share text, without ±; null for other kinds. Inches as TVs are sold
     * ("43.0 in"); with the metric preference cm first ("109 cm (43.0 in)"). "~" when estimated.
     */
    fun diagonalShort(item: GalleryItem, pref: UnitPreference): String? {
        if (item.kind != ObjectKind.TV) return null
        val (m, estimate) = diagonal(item)
        val f = Units.formatForContext(m, MeasureContext.TV, pref)
        val primary = if (f.primary.unit == LengthUnit.FEET_INCHES) f.primary.value else "${f.primary.value} ${f.primary.unitSymbol}"
        val mark = if (estimate) ESTIMATE_MARK else ""
        return if (pref == UnitPreference.METRIC && f.secondary != null) "$mark$primary ($mark${f.secondary})" else "$mark$primary"
    }

    /** TV diagonal with its ± in inches plus cm ("43.0 in ± 0.9 (109 cm)"); no ± when estimated; null for other kinds. */
    fun diagonalDetail(item: GalleryItem, pref: UnitPreference): ContextFormatted? =
        if (item.kind != ObjectKind.TV) null else Units.formatForContext(diagonal(item).first, MeasureContext.TV, pref)

    /**
     * Compact dimensions as TalkBack should read them: "18 by 24 by about 1 in" ("~" has no spoken meaning).
     * [about] and [by] are the localised words.
     */
    fun spokenDims(dimsText: String, about: String, by: String): String =
        dimsText.replace(" × ", " $by ").replace(ESTIMATE_MARK, "$about ")

    /**
     * The measurement part of the share text:
     * "18 × 24 × ~1 in (± 1/4 in) · 45.7 × 61.0 × ~2.5 cm" (+ " · 43.0 in diagonal" handled by the caller).
     */
    fun shareDims(item: GalleryItem, pref: UnitPreference): String {
        val primary = compactDims(item, pref)
        val pm = maxPlusMinus(item, pref)
        val secondaryUnit = secondaryUnit(item.kind, pref)
        val secondary = compactDims(item, pref, secondaryUnit)
        return buildString {
            append(primary)
            if (pm != null) append(" (").append(pm).append(')')
            if (secondary != primary) append(" · ").append(secondary)
        }
    }

    /** Units the edit dialog offers. */
    enum class EditUnit(val lengthUnit: LengthUnit, val symbol: String) { CM(LengthUnit.CM, "cm"), IN(LengthUnit.INCH_DECIMAL, "in") }

    /** Default edit unit: inches when the item's primary unit is imperial. */
    fun editUnit(kind: ObjectKind, pref: UnitPreference): EditUnit = if (primaryUnit(kind, pref).isImperial) EditUnit.IN else EditUnit.CM

    /** Text a dimension is pre-filled with in the edit dialog ("45.5", "18.25"). */
    fun editText(meters: Double, unit: EditUnit): String {
        val v = Units.fromMetres(meters, unit.lengthUnit)
        val s = String.format(Locale.ROOT, if (unit == EditUnit.CM) "%.1f" else "%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    /**
     * Parses a typed length in [unit] to metres: "45.5", "45,5", "23 5/8", "5/8", "24in"/"61 cm" (a matching
     * suffix is ignored). Null when unparsable or outside the gallery's accepted range (1 mm – 50 m).
     */
    fun parseLength(text: String, unit: EditUnit): Double? {
        var t = asciiDigits(text).trim().lowercase(Locale.ROOT).replace(',', '.')
        for (suffix in listOf(unit.symbol, "\"", "″")) if (t.endsWith(suffix)) t = t.removeSuffix(suffix).trim()
        if (t.isEmpty()) return null
        val value = parseNumber(t) ?: return null
        val metres = Units.toMetres(value, unit.lengthUnit)
        return if (GalleryRepository.isValidSide(metres)) metres else null
    }

    /** Any Unicode decimal digit (Devanagari "४५", Arabic-Indic, full-width …) as its ASCII digit. */
    private fun asciiDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            val digit = if (c in '0'..'9') -1 else Character.digit(c, 10)
            append(if (digit >= 0) '0' + digit else c)
        }
    }

    /**
     * The value to save for one edit-dialog field: null when untouched ([typed] null) or when its text is still
     * the pre-filled value in [unit] (so re-typing the measured number never turns it into an estimate);
     * otherwise the parsed metres (null if unparsable; the dialog blocks Save then).
     */
    fun editedValue(typed: String?, originalMeters: Double, unit: EditUnit): Double? {
        val t = typed ?: return null
        if (t.trim() == editText(originalMeters, unit)) return null
        return parseLength(t, unit)
    }

    /** Typed texts carried over a unit switch: each parsable text is converted, anything else is kept as typed. */
    fun convertTyped(typed: List<String?>, from: EditUnit, to: EditUnit): List<String?> =
        typed.map { t -> t?.let { parseLength(it, from)?.let { m -> editText(m, to) } ?: it } }

    /** "12", "12.5", "5/8", "23 5/8" → value; null otherwise. */
    private fun parseNumber(t: String): Double? {
        val parts = t.split(Regex("\\s+"))
        return when (parts.size) {
            1 -> if ('/' in parts[0]) fraction(parts[0]) else parts[0].toDoubleOrNull()
            2 -> {
                val whole = parts[0].toDoubleOrNull() ?: return null
                val frac = fraction(parts[1]) ?: return null
                if (whole < 0 || '.' in parts[0]) null else whole + frac
            }
            else -> null
        }?.takeIf { it.isFinite() && it > 0 }
    }

    private fun fraction(s: String): Double? {
        val nd = s.split('/')
        if (nd.size != 2) return null
        val n = nd[0].toIntOrNull() ?: return null
        val d = nd[1].toIntOrNull() ?: return null
        return if (n < 0 || d <= 0) null else n.toDouble() / d
    }
}
