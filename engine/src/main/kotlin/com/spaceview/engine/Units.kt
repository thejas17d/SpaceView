package com.spaceview.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Display units for lengths. */
enum class LengthUnit(val symbol: String) {
    MM("mm"),
    CM("cm"),
    M("m"),

    /** Decimal inches ("42.9 in"), used for TV/monitor diagonals. */
    INCH_DECIMAL("in"),

    /** Fractional inches to 1/16" ("23 5/8 in"), used for frames and small objects. */
    INCH_FRACTION("in"),

    /** Feet and inches ("5′ 8″"), used for rooms and people. */
    FEET_INCHES("ft-in");

    /** True for the imperial units. */
    val isImperial: Boolean get() = this == INCH_DECIMAL || this == INCH_FRACTION || this == FEET_INCHES
}

/** Display units for areas. */
enum class AreaUnit(val symbol: String, val squareMetres: Double, val minStep: Double) {
    M2("m²", 1.0, 0.01),
    CM2("cm²", 1e-4, 1.0),
    SQ_FT("sq ft", Units.METRES_PER_FOOT * Units.METRES_PER_FOOT, 0.1),
    SQ_IN("sq in", Units.METRES_PER_INCH * Units.METRES_PER_INCH, 0.1),
}

/** What is being measured; drives context-aware default units (UI research §units). */
enum class MeasureContext {
    /** Room dimensions, ceiling and person heights: ft-in (secondary m). */
    ROOM,

    /** Furniture and general objects: cm (secondary in). */
    OBJECT,

    /** Small objects: cm with 1 decimal, mm when ± ≤ 2 mm (secondary in to 1/8"). */
    SMALL_OBJECT,

    /** Photo frames and posters: inches, as frames are sold (secondary cm). */
    FRAME,

    /** TVs and monitors: diagonal in inches (secondary cm). W × H should use [OBJECT]. */
    TV,
}

/** User's unit setting. AUTO follows the context table; METRIC/IMPERIAL force the primary system. */
enum class UnitPreference { AUTO, METRIC, IMPERIAL }

/**
 * A formatted measurement, e.g. value "45.5", unit "cm", plusMinus "0.5" → text "45.5 cm ± 0.5".
 * For feet-inches the unit is embedded: value "5′ 8″", plusMinus "1″".
 *
 * @property plusMinus null when the measurement has no uncertainty.
 * @property step display resolution used, in [unit] (inches for the imperial units).
 */
class FormattedLength(
    val value: String,
    val unitSymbol: String,
    val plusMinus: String?,
    val unit: LengthUnit,
    val step: Double,
) {
    /** Full text: "45.5 cm ± 0.5", "23 5/8 in ± 1/4", "5′ 8″ ± 1″". */
    val text: String
        get() {
            val v = if (unit == LengthUnit.FEET_INCHES) value else "$value $unitSymbol"
            return if (plusMinus == null) v else "$v ± $plusMinus"
        }

    override fun toString(): String = text
}

/** Primary (with ±) and optional secondary (value only) text, e.g. "61.0 cm ± 0.5 (24 in)". */
class ContextFormatted(val primary: FormattedLength, val secondary: String?) {
    /** Combined text. */
    val text: String get() = if (secondary == null) primary.text else "${primary.text} ($secondary)"
    override fun toString(): String = text
}

/**
 * Unit conversion and **precision-honest** formatting. All functions are pure.
 *
 * Rounding rule ("never show more precision than the ±"):
 * - Decimal units: the ±95 is first rounded to its displayed significant figures — one, two when it
 *   starts with 1 ("± 1.4") — and the value is then rounded to the decimal place of the leading digit
 *   of that **rounded** ± (±1.4 cm → whole cm; ±0.5 cm → 0.1 cm; ±12 cm → 10 cm; ±0.999 cm shows as
 *   "± 1.0" with a whole-cm value), never finer than the unit's minimum (1 mm).
 * - Fractional inches / feet-inches: the ± is rounded to the largest of 1/16, 1/8, 1/4, 1/2, 1 inch
 *   not exceeding it, then the value step is the largest of those not exceeding the rounded ±; the ±
 *   is shown on that step (never as 0).
 * Output always uses '.' as decimal separator and Western digits (Locale.ROOT).
 */
object Units {
    /** Exact by definition (international inch, 1959). */
    const val METRES_PER_INCH = 0.0254
    const val METRES_PER_FOOT = 0.3048

    private val FRACTION_STEPS = doubleArrayOf(1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0)

    /** Metres → [unit] (inches for the three imperial units). */
    fun fromMetres(m: Double, unit: LengthUnit): Double = when (unit) {
        LengthUnit.MM -> m * 1000.0
        LengthUnit.CM -> m * 100.0
        LengthUnit.M -> m
        LengthUnit.INCH_DECIMAL, LengthUnit.INCH_FRACTION, LengthUnit.FEET_INCHES -> m / METRES_PER_INCH
    }

    /** [unit] (inches for imperial) → metres. */
    fun toMetres(v: Double, unit: LengthUnit): Double = when (unit) {
        LengthUnit.MM -> v / 1000.0
        LengthUnit.CM -> v / 100.0
        LengthUnit.M -> v
        LengthUnit.INCH_DECIMAL, LengthUnit.INCH_FRACTION, LengthUnit.FEET_INCHES -> v * METRES_PER_INCH
    }

    /** Smallest step a unit is ever shown with (in that unit; inches for imperial). */
    fun minStep(unit: LengthUnit): Double = when (unit) {
        LengthUnit.MM -> 1.0
        LengthUnit.CM -> 0.1
        LengthUnit.M -> 0.001
        LengthUnit.INCH_DECIMAL -> 0.1
        LengthUnit.INCH_FRACTION, LengthUnit.FEET_INCHES -> 1.0 / 16
    }

    /**
     * Display step for an uncertainty [plusMinus] already expressed in [unit] (≤ 0 → finest step).
     */
    fun stepFor(plusMinus: Double, unit: LengthUnit): Double {
        val min = minStep(unit)
        if (!(plusMinus > 0)) return min
        return when (unit) {
            LengthUnit.INCH_FRACTION, LengthUnit.FEET_INCHES -> fractionStep(roundTo(plusMinus, fractionStep(plusMinus)))
            else -> decimalSteps(plusMinus, min).valueStep
        }
    }

    /** Largest of 1/16 … 1 inch not exceeding [x] (1/16 when x is smaller). */
    private fun fractionStep(x: Double): Double {
        var s = FRACTION_STEPS[0]
        for (c in FRACTION_STEPS) if (c <= x + 1e-9) s = c
        return s
    }

    /** Rounded ± (as displayed), the step it is shown with, and the value step derived from it. */
    private class DecimalSteps(val plusMinus: Double, val plusMinusStep: Double, val valueStep: Double)

    /** One significant figure, two when the leading digit is 1. */
    private fun sigFigStep(x: Double): Double {
        val lead = decimalLeadingPlace(x)
        return if (x / lead < 2.0 - 1e-9) lead / 10 else lead
    }

    /** MINOR-9: round the ± to its displayed figures first, then derive both steps from the rounded ±. */
    private fun decimalSteps(pm: Double, min: Double): DecimalSteps {
        if (!(pm > 0)) return DecimalSteps(0.0, min, min)
        val r1 = maxOf(roundTo(pm, maxOf(min, sigFigStep(pm))), min)
        val pmStep = maxOf(min, sigFigStep(r1))
        val r2 = maxOf(roundTo(r1, pmStep), pmStep)
        return DecimalSteps(r2, pmStep, maxOf(min, decimalLeadingPlace(r2)))
    }

    /** Formats a length measurement in [unit] with its ±95 (see the object docs for the rounding rule). */
    fun format(m: Measurement, unit: LengthUnit): FormattedLength {
        val pm = if (m.sigma > 0) fromMetres(m.bound95, unit) else 0.0
        val step = stepFor(pm, unit)
        val v = fromMetres(m.value, unit)
        val valueText = formatValue(v, unit, step)
        val pmText = if (m.sigma > 0) formatPlusMinus(pm, unit, step) else null
        return FormattedLength(valueText, unit.symbol, pmText, unit, step)
    }

    /**
     * Value-only text in [unit] at the precision allowed by [plusMinusMetres] (used for the secondary unit),
     * e.g. "24 in", "5′ 8″", "61.0 cm".
     */
    fun formatValueOnly(valueMetres: Double, plusMinusMetres: Double, unit: LengthUnit): String {
        val step = stepFor(fromMetres(plusMinusMetres, unit), unit)
        val text = formatValue(fromMetres(valueMetres, unit), unit, step)
        return if (unit == LengthUnit.FEET_INCHES) text else "$text ${unit.symbol}"
    }

    /** Default (primary, secondary) units for a context and preference. */
    fun defaultUnits(context: MeasureContext, pref: UnitPreference = UnitPreference.AUTO): Pair<LengthUnit, LengthUnit> =
        when (context) {
            MeasureContext.ROOM -> if (pref == UnitPreference.METRIC) LengthUnit.M to LengthUnit.FEET_INCHES
            else LengthUnit.FEET_INCHES to LengthUnit.M
            MeasureContext.OBJECT, MeasureContext.SMALL_OBJECT ->
                if (pref == UnitPreference.IMPERIAL) LengthUnit.INCH_FRACTION to LengthUnit.CM
                else LengthUnit.CM to LengthUnit.INCH_FRACTION
            MeasureContext.FRAME -> if (pref == UnitPreference.METRIC) LengthUnit.CM to LengthUnit.INCH_FRACTION
            else LengthUnit.INCH_FRACTION to LengthUnit.CM
            MeasureContext.TV -> if (pref == UnitPreference.METRIC) LengthUnit.CM to LengthUnit.INCH_DECIMAL
            else LengthUnit.INCH_DECIMAL to LengthUnit.CM
        }

    /** Threshold for switching small objects to millimetres (UI: "mm only when ± ≤ 2 mm"). */
    const val MM_SWITCH_BOUND = 0.002

    /** Formats with context-aware primary + secondary units, e.g. "61.0 cm ± 0.5 (24 in)". */
    fun formatForContext(
        m: Measurement,
        context: MeasureContext,
        pref: UnitPreference = UnitPreference.AUTO,
    ): ContextFormatted {
        var (primary, secondary) = defaultUnits(context, pref)
        if (context == MeasureContext.SMALL_OBJECT && m.sigma > 0 && m.bound95 <= MM_SWITCH_BOUND + 1e-12) {
            if (primary == LengthUnit.CM) primary = LengthUnit.MM
            if (secondary == LengthUnit.CM) secondary = LengthUnit.MM
        }
        return ContextFormatted(format(m, primary), formatValueOnly(m.value, m.bound95, secondary))
    }

    /** Formats an area (m² in, any [AreaUnit] out) with the decimal rounding rule. */
    fun formatArea(m: Measurement, unit: AreaUnit): String {
        val v = m.value / unit.squareMetres
        val pm = m.bound95 / unit.squareMetres
        val steps = decimalSteps(pm, unit.minStep)
        val vt = decimalText(v, steps.valueStep)
        return if (m.sigma > 0) "$vt ${unit.symbol} ± ${decimalPlusMinus(pm, unit.minStep)}" else "$vt ${unit.symbol}"
    }

    /** Default area unit: sq ft for rooms unless metric is forced; m² otherwise (cm² for small things). */
    fun defaultAreaUnit(context: MeasureContext, pref: UnitPreference = UnitPreference.AUTO): AreaUnit = when {
        pref == UnitPreference.IMPERIAL -> if (context == MeasureContext.ROOM) AreaUnit.SQ_FT else AreaUnit.SQ_IN
        context == MeasureContext.ROOM -> if (pref == UnitPreference.METRIC) AreaUnit.M2 else AreaUnit.SQ_FT
        context == MeasureContext.SMALL_OBJECT || context == MeasureContext.FRAME -> AreaUnit.CM2
        else -> AreaUnit.M2
    }

    // ---- internals ------------------------------------------------------------------------------

    /** 10^floor(log10(x)) for x > 0, tolerant of binary round-off just below a power of ten. */
    internal fun decimalLeadingPlace(x: Double): Double = 10.0.pow(floor(log10(x) + 1e-9))

    private fun decimalsFor(step: Double): Int = if (step >= 1.0) 0 else (-floor(log10(step) + 1e-9)).toInt()

    private fun roundTo(v: Double, step: Double): Double {
        val r = (abs(v) / step).roundToLong() * step
        return if (v < 0) -r else r
    }

    private fun decimalText(v: Double, step: Double): String {
        val r = roundTo(v, step) + 0.0 // + 0.0 turns -0.0 into 0.0
        return String.format(Locale.ROOT, "%.${decimalsFor(step)}f", r)
    }

    /** ± with 1 significant figure (2 when the leading digit is 1), never finer than [min], never 0. */
    private fun decimalPlusMinus(pm: Double, min: Double): String {
        val st = decimalSteps(pm, min)
        return String.format(Locale.ROOT, "%.${decimalsFor(st.plusMinusStep)}f", st.plusMinus)
    }

    private fun formatValue(v: Double, unit: LengthUnit, step: Double): String = when (unit) {
        LengthUnit.INCH_FRACTION -> fractionText(roundTo(v, step))
        LengthUnit.FEET_INCHES -> feetInchesText(roundTo(v, step))
        else -> decimalText(v, step)
    }

    private fun formatPlusMinus(pm: Double, unit: LengthUnit, step: Double): String = when (unit) {
        LengthUnit.INCH_FRACTION -> fractionText(maxOf(roundTo(pm, step), step))
        LengthUnit.FEET_INCHES -> fractionText(maxOf(roundTo(pm, step), step)) + "″"
        else -> decimalPlusMinus(pm, minStep(unit))
    }

    /** Inches (already a multiple of 1/16) → "23 5/8", "5/8", "-1 1/2", "24". */
    internal fun fractionText(inches: Double): String {
        val sixteenths = (abs(inches) * 16).roundToLong()
        val whole = sixteenths / 16
        var num = sixteenths % 16
        var den = 16L
        while (num != 0L && num % 2 == 0L) { num /= 2; den /= 2 }
        val sign = if (inches < 0 && sixteenths != 0L) "-" else ""
        return when {
            num == 0L -> "$sign$whole"
            whole == 0L -> "$sign$num/$den"
            else -> "$sign$whole $num/$den"
        }
    }

    /** Inches (multiple of the step) → "5′ 8″", "5′ 8 1/2″", "7″", "-1′ 2″". */
    internal fun feetInchesText(inches: Double): String {
        val sixteenths = (abs(inches) * 16).roundToLong()
        val feet = sixteenths / (12 * 16)
        val rem = (sixteenths - feet * 12 * 16) / 16.0
        val sign = if (inches < 0 && sixteenths != 0L) "-" else ""
        val inchText = fractionText(rem) + "″"
        return if (feet == 0L) "$sign$inchText" else "$sign$feet′ $inchText"
    }
}
