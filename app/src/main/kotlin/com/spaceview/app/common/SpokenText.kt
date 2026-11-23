package com.spaceview.app.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.spaceview.app.R

/**
 * TalkBack text for any visible measurement string (M5 accessibility pass, FRIDAY §4.J):
 * - "45.5 cm ± 0.5" → "45.5 centimetres plus or minus 0.5 centimetres";
 * - "18 × 24 × ~1 in" → "18 by 24 by about 1 inch";
 * - "23 5/8 in" → "23 and 5 eighths inches"; "± 1/4 in" → "plus or minus 1 quarter inch";
 * - "5′ 8″" → "5 feet 8 inches"; "1′" → "1 foot";
 * - "0.24 m² ± 0.01" → "0.24 square metres plus or minus 0.01 square metres".
 *
 * Use it where only the formatted text is at hand; with a [com.spaceview.engine.FormattedLength], [spokenLength] says
 * the same through this function. A unit is only spoken after a number ("in" in "43 in" becomes "inches", a stray
 * word "in" stays), and a bare tolerance after ± takes the unit of the value before it.
 *
 * Singular or plural (SM1-M5 review MINOR 8): a unit is singular after exactly "1", or after a fraction on its own
 * ("5 eighths inch", as in "a 5/8-inch board"); plural otherwise. Fractions are spoken in words, never as "5/8",
 * which TTS can read as a date.
 *
 * Allocation-light (review MINOR 9): the regexes are compiled once and units are matched with a `when`, so a call
 * builds only the token list and the output. Pure, so it is JVM-tested ([speak]).
 */
object SpokenText {
    /** A word in its singular and plural forms (the same in Hindi). */
    data class Form(val one: String, val other: String) {
        fun of(singular: Boolean) = if (singular) one else other
    }

    /** The spoken words, from string resources (EN + HI). */
    data class Words(
        val plusOrMinus: String,
        val about: String,
        val by: String,
        /** "%1$s and %2$s" for a mixed number ("23 and 5 eighths"). */
        val mixedNumber: String,
        /** "%1$s over %2$s" for a fraction with an unusual denominator. */
        val fractionOver: String,
        val mm: Form,
        val cm: Form,
        val m: Form,
        val inch: Form,
        val foot: Form,
        val squareMetre: Form,
        val squareFoot: Form,
        val half: Form,
        val quarter: Form,
        val eighth: Form,
        val sixteenth: Form,
    )

    private const val SQ_FT = "\u0001"
    private const val SQ_M = "\u0002"
    private val NUMBER = Regex("""^\d+(?:[.,]\d+)?$""")
    private val FRACTION = Regex("""^(\d+)/(\d+)$""")
    private val GLUED_UNIT = Regex("""(\d)(mm|cm|m|in|ft)\b""")
    private val SPACES = Regex("""\s+""")
    private val SYMBOLS = arrayOf("±", "×", "~", "′", "″", "(", ")")

    /** The unit a token names, or null. */
    private fun unitOf(tok: String, w: Words): Form? = when (tok) {
        "mm" -> w.mm
        "cm" -> w.cm
        "m" -> w.m
        "in", "″", "\"" -> w.inch
        "ft", "′", "'" -> w.foot
        SQ_M, "m²" -> w.squareMetre
        SQ_FT, "sq ft" -> w.squareFoot
        else -> null
    }

    /** The spoken name of a unit symbol ("cm" → "centimetres"); unknown symbols are returned unchanged. */
    fun unitWord(symbol: String, w: Words): String = unitOf(symbol.trim(), w)?.other ?: symbol

    /** "5/8" → "5 eighths", "1/2" → "1 half"; null if [tok] is not a fraction. */
    private fun fraction(tok: String, w: Words): String? {
        val m = FRACTION.matchEntire(tok) ?: return null
        val num = m.groupValues[1]
        val den = m.groupValues[2]
        val form = when (den) {
            "2" -> w.half
            "4" -> w.quarter
            "8" -> w.eighth
            "16" -> w.sixteenth
            else -> return w.fractionOver.format(num, den)
        }
        return "$num ${form.of(num == "1")}"
    }

    fun speak(text: String, w: Words): String {
        var t = text.replace("sq ft", " $SQ_FT ").replace("m²", " $SQ_M ")
        for (sym in SYMBOLS) t = t.replace(sym, " $sym ")
        // "45.5cm" → "45.5 cm" (a unit glued to its number).
        t = GLUED_UNIT.replace(t, "$1 $2")
        val tokens = t.split(SPACES).filter { it.isNotEmpty() }
        val out = ArrayList<String>(tokens.size + 4)
        var lastUnit: Form? = null
        var afterPm = false
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val prevWasQuantity = i > 0 && (NUMBER.matches(tokens[i - 1]) || FRACTION.matches(tokens[i - 1]))
            val unit = if (prevWasQuantity) unitOf(tok, w) else null
            when {
                tok == "±" -> { out += w.plusOrMinus; afterPm = true }
                tok == "×" -> out += w.by
                tok == "~" -> out += w.about
                tok == "(" || tok == ")" -> if (out.isNotEmpty() && out.last() != ",") out += ","
                unit != null -> {
                    out += unit.of(singularBefore(tokens, i))
                    lastUnit = unit
                }
                NUMBER.matches(tok) || FRACTION.matches(tok) -> {
                    // A whole number followed by a fraction is one quantity: "23 5/8".
                    val next = tokens.getOrNull(i + 1)
                    val spoken = if (NUMBER.matches(tok) && next != null && FRACTION.matches(next)) {
                        i++
                        w.mixedNumber.format(tok, fraction(next, w))
                    } else {
                        fraction(tok, w) ?: tok
                    }
                    out += spoken
                    val after = tokens.getOrNull(i + 1)
                    val bareTolerance = afterPm && (after == null || unitOf(after, w) == null)
                    val u = lastUnit
                    // A bare tolerance ("± 0.5") speaks the value's unit.
                    if (bareTolerance && u != null) out += u.of(singularQuantity(tok, next))
                    afterPm = false
                }
                else -> out += tok
            }
            i++
        }
        return out.joinToString(" ").replace(" ,", ",").trim().trimEnd(',')
    }

    /** Whether the quantity that ends just before index [unitIndex] takes a singular unit. */
    private fun singularBefore(tokens: List<String>, unitIndex: Int): Boolean {
        val last = tokens[unitIndex - 1]
        if (FRACTION.matches(last)) {
            // "23 5/8 in" is plural; "5/8 in" alone is singular.
            val before = tokens.getOrNull(unitIndex - 2)
            return before == null || !NUMBER.matches(before)
        }
        return last == "1"
    }

    private fun singularQuantity(tok: String, next: String?): Boolean = when {
        NUMBER.matches(tok) && next != null && FRACTION.matches(next) -> false
        FRACTION.matches(tok) -> true
        else -> tok == "1"
    }
}

/** [SpokenText.speak] with the current locale's words. */
@Composable
@ReadOnlyComposable
fun spokenText(text: String): String = SpokenText.speak(text, spokenWords())

/** The spoken name of a unit symbol, e.g. for a "cm" / "in" chip. */
@Composable
@ReadOnlyComposable
fun spokenUnit(symbol: String): String = SpokenText.unitWord(symbol, spokenWords())

@Composable
@ReadOnlyComposable
private fun form(one: Int, other: Int) = SpokenText.Form(stringResource(one), stringResource(other))

@Composable
@ReadOnlyComposable
private fun spokenWords(): SpokenText.Words = SpokenText.Words(
    plusOrMinus = stringResource(R.string.a11y_plus_or_minus),
    about = stringResource(R.string.a11y_about),
    by = stringResource(R.string.a11y_by),
    mixedNumber = stringResource(R.string.a11y_mixed_number),
    fractionOver = stringResource(R.string.a11y_fraction_over),
    mm = form(R.string.a11y_unit_mm_one, R.string.a11y_unit_mm),
    cm = form(R.string.a11y_unit_cm_one, R.string.a11y_unit_cm),
    m = form(R.string.a11y_unit_m_one, R.string.a11y_unit_m),
    inch = form(R.string.a11y_unit_inch_one, R.string.a11y_unit_inches),
    foot = form(R.string.a11y_unit_foot_one, R.string.a11y_unit_feet),
    squareMetre = form(R.string.a11y_unit_square_metre_one, R.string.a11y_unit_square_metres),
    squareFoot = form(R.string.a11y_unit_square_foot_one, R.string.a11y_unit_square_feet),
    half = form(R.string.a11y_fraction_half_one, R.string.a11y_fraction_half),
    quarter = form(R.string.a11y_fraction_quarter_one, R.string.a11y_fraction_quarter),
    eighth = form(R.string.a11y_fraction_eighth_one, R.string.a11y_fraction_eighth),
    sixteenth = form(R.string.a11y_fraction_sixteenth_one, R.string.a11y_fraction_sixteenth),
)
