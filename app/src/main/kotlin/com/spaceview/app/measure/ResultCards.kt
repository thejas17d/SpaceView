package com.spaceview.app.measure

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.hintRes
import com.spaceview.app.common.spokenAmount
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.CalibrationCheck
import com.spaceview.ar.ImprovementTip
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.Reading
import com.spaceview.engine.FormattedLength
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.Shapes
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Uncertainty
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Solid graphite card for AR screens. */
@Composable
private fun ChromeCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = SpaceViewTheme.colors.arChrome,
        contentColor = SpaceViewTheme.colors.onArChrome,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/**
 * Finished measurement: value ± (rounded by the engine formatter), Good/Fair/Rough badge with a
 * shape, one improvement tip, calibration note, and Save / New / Undo.
 */
@Composable
internal fun ResultCard(
    reading: Reading,
    tip: ImprovementTip?,
    units: UnitPreference,
    calibrationDateMillis: Long?,
    calibratedThisVisit: Boolean,
    onSave: (String?) -> Unit,
    onNew: () -> Unit,
    onUndo: () -> Unit,
    finalReading: () -> Reading?,
) {
    var label by rememberSaveable { mutableStateOf("") }
    val ctx = MeasureFormatter.contextFor(reading)
    ChromeCard {
        Text(
            stringResource(
                when (reading.mode) {
                    MeasureMode.DISTANCE -> R.string.result_distance
                    MeasureMode.HEIGHT -> R.string.result_height
                    MeasureMode.RECTANGLE -> R.string.home_rectangle
                },
            ).uppercase(),
            style = SpaceViewTheme.typography.caption,
            modifier = Modifier.semantics { heading() },
        )
        if (reading.mode == MeasureMode.RECTANGLE && reading.secondary != null) {
            ValueRow(R.string.result_width, reading.primary, ctx, units)
            ValueRow(R.string.result_rect_height, reading.secondary!!, ctx, units)
            reading.area?.let { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.result_area), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                    Text(MeasureFormatter.area(a, ctx, units), style = MaterialTheme.typography.titleMedium)
                }
            }
            val skew = reading.maxRightAngleDeviationDeg ?: 0.0
            if (!reading.geometryConsistent) {
                Text(stringResource(R.string.calib_reject_skewed), style = MaterialTheme.typography.bodyMedium, color = SpaceViewTheme.colors.signal)
            } else if (skew > Shapes.SQUARENESS_TOLERANCE_DEG) {
                Text(
                    stringResource(R.string.result_not_square, String.format(Locale.ROOT, "%.0f", skew)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AccuracyBadge(reading.quality, onDarkChrome = true)
        } else {
            val f = MeasureFormatter.length(reading.primary, ctx, units)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(f.primary.valueWithUnit(), style = SpaceViewTheme.typography.readout)
                Spacer(Modifier.width(8.dp))
                f.secondary?.let { Text("($it)", style = SpaceViewTheme.typography.tolerance, modifier = Modifier.padding(bottom = 8.dp)) }
            }
            AccuracyBadge(
                reading.quality,
                tolerance = f.primary.toleranceText(),
                toleranceSpoken = f.primary.plusMinus?.let { spokenAmount(it, f.primary.unit) },
                onDarkChrome = true,
            )
        }
        Text(stringResource(reading.quality.hintRes()), style = MaterialTheme.typography.bodySmall)
        if (calibrationDateMillis != null) {
            Text(
                stringResource(R.string.result_calibrated, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(calibrationDateMillis))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (tip != null && !(tip == ImprovementTip.CALIBRATE && calibratedThisVisit)) {
            Text(
                "${stringResource(R.string.result_tip_title)}: ${stringResource(tip.messageRes())}",
                style = MaterialTheme.typography.bodyMedium,
                color = SpaceViewTheme.colors.signal,
            )
        }
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.length <= 80) label = it },
            label = { Text(stringResource(R.string.result_label_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SpaceViewTheme.colors.onArChrome,
                unfocusedTextColor = SpaceViewTheme.colors.onArChrome,
                focusedLabelColor = SpaceViewTheme.colors.signal,
                unfocusedLabelColor = SpaceViewTheme.colors.onArChrome,
                focusedBorderColor = SpaceViewTheme.colors.signal,
                cursorColor = SpaceViewTheme.colors.signal,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.measure_undo), color = SpaceViewTheme.colors.onArChrome)
            }
            TextButton(onClick = onNew, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.measure_new), color = SpaceViewTheme.colors.onArChrome)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (finalReading() != null) onSave(label.ifBlank { null }) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
            ) { Text(stringResource(R.string.measure_save)) }
        }
    }
}

@Composable
private fun ValueRow(@StringRes label: Int, m: Measurement, ctx: MeasureContext, units: UnitPreference) {
    val f = MeasureFormatter.length(m, ctx, units)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
        Text(f.primary.text, style = MaterialTheme.typography.titleLarge)
        f.secondary?.let {
            Spacer(Modifier.width(8.dp))
            Text("($it)", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun FormattedLength.valueWithUnit(): String =
    if (unit == com.spaceview.engine.LengthUnit.FEET_INCHES) value else "$value $unitSymbol"

private fun FormattedLength.toleranceText(): String? = plusMinus?.let {
    if (unit == com.spaceview.engine.LengthUnit.FEET_INCHES) "± $it" else "± $it $unitSymbol"
}

@StringRes
internal fun ImprovementTip.messageRes(): Int = when (this) {
    ImprovementTip.MOVE_CLOSER -> R.string.tip_move_closer
    ImprovementTip.ADD_LIGHT -> R.string.tip_add_light
    ImprovementTip.HOLD_STILL -> R.string.tip_hold_still
    ImprovementTip.FACE_SURFACE -> R.string.tip_face_surface
    ImprovementTip.AIM_AT_TEXTURE -> R.string.tip_aim_at_texture
    ImprovementTip.CALIBRATE -> R.string.tip_calibrate
    ImprovementTip.MEASURE_AGAIN -> R.string.tip_measure_again
}

/** Card / A4 choice before calibrating. */
@Composable
internal fun ReferencePicker(onChoose: (ReferenceObject) -> Unit) {
    ChromeCard {
        Text(stringResource(R.string.calib_choose), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        ReferenceOption(R.string.calib_card, R.string.calib_card_detail) { onChoose(ReferenceObject.ID1_CARD) }
        ReferenceOption(R.string.calib_a4, R.string.calib_a4_detail) { onChoose(ReferenceObject.A4) }
        Text(stringResource(R.string.calib_same_surface), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReferenceOption(@StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = SpaceViewTheme.colors.arChrome,
        border = androidx.compose.foundation.BorderStroke(1.dp, SpaceViewTheme.colors.onArChrome.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = SpaceViewTheme.colors.onArChrome)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = SpaceViewTheme.colors.onArChrome)
        }
    }
}

/** Result of measuring the reference: the factor with its ±, or why it was refused. */
@Composable
internal fun CalibrationResultCard(
    reference: ReferenceObject,
    reading: Reading,
    check: (ReferenceObject) -> CalibrationCheck?,
    onApply: (CalibrationOutcome.Accepted) -> Unit,
    onRetry: () -> Unit,
) {
    // Recomputed whenever the (anchor-refined) reading changes.
    val outcome = remember(reference, reading) { MeasureViewModel.evaluateCalibration(reference, check(reference)) }
    ChromeCard {
        Text(stringResource(R.string.calib_result_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        when (outcome) {
            is CalibrationOutcome.Accepted -> {
                val c = outcome.calibration
                val pm95 = Uncertainty.K95 * c.sigma / c.factor
                Text(
                    stringResource(
                        R.string.calib_result_body,
                        MeasureFormatter.percent(c.factor - 1.0),
                        String.format(Locale.ROOT, "%.1f%%", pm95 * 100),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            is CalibrationOutcome.Rejected -> Text(
                stringResource(
                    when (outcome.reason) {
                        CalibrationRejection.OUT_OF_RANGE -> R.string.calib_reject_out_of_range
                        CalibrationRejection.INCONSISTENT -> R.string.calib_reject_inconsistent
                        CalibrationRejection.TOO_NOISY -> R.string.calib_reject_noisy
                        CalibrationRejection.SKEWED -> R.string.calib_reject_skewed
                        CalibrationRejection.INVALID -> R.string.calib_reject_invalid
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.calib_retry), color = SpaceViewTheme.colors.onArChrome)
            }
            Spacer(Modifier.weight(1f))
            if (outcome is CalibrationOutcome.Accepted) {
                Button(
                    onClick = { onApply(outcome) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
                ) { Text(stringResource(R.string.calib_apply)) }
            }
        }
    }
}
