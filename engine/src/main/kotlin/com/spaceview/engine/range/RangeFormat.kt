package com.spaceview.engine.range

import com.spaceview.engine.LengthUnit
import com.spaceview.engine.Measurement
import com.spaceview.engine.Units

/**
 * A formatted rangefinder distance, e.g. value "500", unit "m", plusMinus "20" → "500 m ± 20 m".
 *
 * Never more digits than the ± supports: the decimal rule of [Units] (± rounded **up** to one significant figure, two
 * when it starts with 1; the value rounded to the ±'s leading decimal place), so 503.27 m ± 18 m shows as
 * "500 m ± 18 m" (two figures when the ± starts with 1) and 4.53 m ± 0.06 m as "4.53 m ± 0.06 m".
 */
class RangeText(val value: String, val unitSymbol: String, val plusMinus: String) {
    val text: String get() = "$value $unitSymbol ± $plusMinus $unitSymbol"
    override fun toString(): String = text
}

/** Formats rangefinder distances in metres, or in decimal feet for imperial users. Pure. */
object RangeFormat {
    /** Symbol used for decimal feet. */
    const val FEET_SYMBOL = "ft"

    fun format(m: Measurement, imperial: Boolean): RangeText {
        // Decimal feet reuse the decimal rule of metres on the value expressed in feet (Units has no decimal-feet unit;
        // feet-inches would cap the step at 1″ and show far more digits than a ±10 m reading supports).
        val scale = if (imperial) 1.0 / Units.METRES_PER_FOOT else 1.0
        val f = Units.format(Measurement(m.value * scale, m.sigma * scale), LengthUnit.M)
        return RangeText(f.value, if (imperial) FEET_SYMBOL else LengthUnit.M.symbol, f.plusMinus ?: "0")
    }
}
