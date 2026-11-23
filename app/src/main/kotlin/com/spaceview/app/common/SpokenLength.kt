package com.spaceview.app.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.spaceview.app.R
import com.spaceview.engine.FormattedLength
import com.spaceview.engine.LengthUnit

/**
 * TalkBack text for a formatted length: "45.5 cm ± 0.5" → "45.5 centimetres, plus or minus 0.5
 * centimetres"; "5′ 8″ ± 1″" → "5 feet 8 inches, plus or minus 1 inch"; "23 5/8 in" → "23 and 5 eighths inches".
 */
@Composable
@ReadOnlyComposable
fun spokenLength(length: FormattedLength): String {
    val value = spokenAmount(length.value, length.unit)
    val pm = length.plusMinus ?: return value
    return stringResource(R.string.a11y_length_with_tolerance, value, spokenAmount(pm, length.unit))
}

/**
 * A bare amount in [unit] ("0.5", "5/8" or "1″") with its unit name spoken out, through [spokenText] so fractions are
 * read in words and "1" takes a singular unit (SM1-M5 review MINOR 8).
 */
@Composable
@ReadOnlyComposable
fun spokenAmount(amount: String, unit: LengthUnit): String =
    spokenText(if (unit == LengthUnit.FEET_INCHES) amount else "$amount ${unit.symbol}")
