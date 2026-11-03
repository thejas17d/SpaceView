package com.spaceview.app.measure

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.common.AddPointButton
import com.spaceview.app.common.CoachingCard
import com.spaceview.app.common.DimensionLabel
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.CalibrationCheck
import com.spaceview.ar.CommitFailure
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasureState
import com.spaceview.ar.Reading
import com.spaceview.ar.TrackingStatus
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.UnitPreference

/** All on-camera chrome of the measure screen. Recomposes only on throttled state (≤ 10 Hz). */
@Composable
internal fun MeasureHud(
    state: MeasureState,
    settings: MeasureUiSettings,
    calibration: CalibrationStep?,
    sessionFailed: Boolean,
    transient: TransientMessage?,
    onClose: () -> Unit,
    onModeChange: (MeasureMode) -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onFreeze: () -> Unit,
    onNew: () -> Unit,
    onSave: (String?) -> Unit,
    onStartOver: () -> Unit,
    onRetrySession: () -> Unit,
    onDismissSafety: () -> Unit,
    onChooseReference: (ReferenceObject) -> Unit,
    onCancelCalibration: () -> Unit,
    onApplyCalibration: (CalibrationOutcome.Accepted) -> Unit,
    onRetryCalibration: () -> Unit,
    finalReading: () -> Reading?,
    calibrationCheck: (ReferenceObject) -> CalibrationCheck?,
    calibratedToday: Boolean,
) {
    val coaching = state.tracking == TrackingStatus.INITIALIZING || !state.sessionRunning
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // ---- top: close, modes (hidden while coaching: Apple HIG "hide unrelated UI"), calibrated chip
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(onClick = if (calibration != null) onCancelCalibration else onClose, description = R.string.measure_close) { CloseGlyph(it) }
                Spacer(Modifier.weight(1f))
                if (calibration == null && !coaching) {
                    ModeSelector(state.mode, onModeChange)
                } else if (calibration is CalibrationStep.Measuring) {
                    ChromePill(stringResource(R.string.calib_title))
                }
                Spacer(Modifier.weight(1f))
                if (calibration == null && settings.calibration != null) {
                    ChromePill(stringResource(R.string.measure_calibrated_chip))
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            HintArea(state, settings, calibration, sessionFailed, transient, onStartOver, onRetrySession, onDismissSafety)
        }

        // ---- centre: reticle + live reading
        if (calibration != CalibrationStep.Choosing && !sessionFailed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Reticle(state)
                Spacer(Modifier.height(8.dp))
                LiveReadout(state, settings.units)
            }
        }

        // ---- bottom: results or controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                calibration == CalibrationStep.Choosing -> ReferencePicker(onChooseReference)
                calibration is CalibrationStep.Measuring && state.isComplete && state.reading != null -> CalibrationResultCard(
                    reference = calibration.reference,
                    reading = state.reading!!,
                    check = calibrationCheck,
                    onApply = onApplyCalibration,
                    onRetry = onRetryCalibration,
                )
                calibration == null && state.isComplete && state.reading != null -> ResultCard(
                    reading = state.reading!!,
                    tip = state.tip,
                    units = settings.units,
                    calibrationDateMillis = if (state.reading!!.calibrated) settings.calibration?.calibratedAtMillis else null,
                    calibratedThisVisit = calibratedToday,
                    onSave = onSave,
                    onNew = onNew,
                    onUndo = onUndo,
                    finalReading = finalReading,
                )
                !sessionFailed -> Controls(state, onAddPoint, onUndo, onFreeze)
            }
        }
    }
}

// ---- hints -----------------------------------------------------------------------------------

@Composable
private fun HintArea(
    state: MeasureState,
    settings: MeasureUiSettings,
    calibration: CalibrationStep?,
    sessionFailed: Boolean,
    transient: TransientMessage?,
    onStartOver: () -> Unit,
    onRetrySession: () -> Unit,
    onDismissSafety: () -> Unit,
) {
    when {
        sessionFailed -> CoachingCard(
            message = stringResource(R.string.arcore_session_failed),
            actionLabel = stringResource(R.string.common_try_again), onAction = onRetrySession,
        )
        !settings.safetyNoticeShown -> CoachingCard(
            title = stringResource(R.string.measure_safety_title),
            message = stringResource(R.string.measure_safety_body),
            actionLabel = stringResource(R.string.measure_ok), onAction = onDismissSafety,
        )
        transient != null -> CoachingCard(message = stringResource(transient.messageRes()))
        state.pointCount > 0 && !state.pointsTracking -> CoachingCard(
            message = stringResource(R.string.measure_lost),
            actionLabel = stringResource(R.string.measure_start_over), onAction = onStartOver,
        )
        !state.sessionRunning || state.tracking == TrackingStatus.INITIALIZING -> CoachingCard(
            title = stringResource(R.string.measure_coach_title),
            message = stringResource(R.string.measure_coach_move),
        )
        state.tracking != TrackingStatus.GOOD -> CoachingCard(message = stringResource(state.tracking.messageRes()))
        calibration == CalibrationStep.Choosing -> Unit
        state.isComplete -> Unit
        state.pendingCommit -> CoachingCard(message = stringResource(R.string.measure_hold_steady))
        !state.hasSurface -> CoachingCard(message = stringResource(R.string.measure_no_surface))
        else -> {
            val instruction = instructionRes(state.mode, state.pointCount)
            val extra = (calibration as? CalibrationStep.Measuring)?.let { stringResource(R.string.calib_same_surface) }
            CoachingCard(message = if (extra != null) "${stringResource(instruction)} $extra" else stringResource(instruction))
        }
    }
}

@StringRes
private fun TransientMessage.messageRes(): Int = when (this) {
    is TransientMessage.Rejected -> when (reason) {
        CommitFailure.NOT_TRACKING -> R.string.measure_reject_not_tracking
        CommitFailure.NO_SURFACE -> R.string.measure_reject_no_surface
        CommitFailure.NOT_STEADY -> R.string.measure_reject_not_steady
        CommitFailure.SAME_SPOT -> R.string.measure_reject_same_spot
    }
    TransientMessage.FreezeUnavailable -> R.string.measure_freeze_unavailable
    TransientMessage.Saved -> R.string.measure_saved
    TransientMessage.CalibrationApplied -> R.string.calib_applied
}

@StringRes
internal fun TrackingStatus.messageRes(): Int = when (this) {
    TrackingStatus.INITIALIZING -> R.string.measure_coach_move
    TrackingStatus.GOOD -> R.string.measure_aim_first
    TrackingStatus.TOO_FAST -> R.string.measure_too_fast
    TrackingStatus.TOO_DARK -> R.string.measure_too_dark
    TrackingStatus.LOW_TEXTURE -> R.string.measure_low_texture
    TrackingStatus.LOST -> R.string.measure_lost
    TrackingStatus.CAMERA_PROBLEM -> R.string.measure_camera_problem
}

@StringRes
internal fun instructionRes(mode: MeasureMode, pointCount: Int): Int = when (mode) {
    MeasureMode.DISTANCE -> if (pointCount == 0) R.string.measure_aim_first else R.string.measure_aim_end
    MeasureMode.HEIGHT -> if (pointCount == 0) R.string.measure_aim_base else R.string.measure_aim_top
    MeasureMode.RECTANGLE -> when (pointCount) {
        0 -> R.string.measure_aim_corner_1
        1 -> R.string.measure_aim_corner_2
        else -> R.string.measure_aim_corner_3
    }
}

// ---- reticle and live readout ---------------------------------------------------------------

/**
 * Fixed-size centre crosshair (two-tone so it reads on white walls and dark floors). The ring
 * fills as the point under it steadies; a filled centre dot means "steady, tap +". Without a
 * surface it is a dashed grey ring.
 */
@Composable
private fun Reticle(state: MeasureState) {
    val signal = SpaceViewTheme.colors.signal
    val dark = Color(0xFF16181B)
    val description = stringResource(if (state.locked) R.string.measure_reticle_locked else R.string.measure_reticle)
    Canvas(Modifier.size(64.dp).semantics { contentDescription = description }) {
        val c = center
        val r = 22.dp.toPx()
        val w = 2.dp.toPx()
        if (!state.hasSurface) {
            drawCircle(dark, r, c, style = Stroke(w * 2.2f))
            drawCircle(Color(0xFFA9AEB4), r, c, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
            crosshair(c, r, dark, Color(0xFFA9AEB4))
            return@Canvas
        }
        drawCircle(dark, r, c, style = Stroke(w * 2.2f))
        drawCircle(Color.White.copy(alpha = 0.85f), r, c, style = Stroke(w))
        if (state.stability > 0f) {
            drawArc(
                color = signal, startAngle = -90f, sweepAngle = 360f * state.stability.coerceIn(0f, 1f), useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                style = Stroke(w * 1.6f, cap = StrokeCap.Round),
            )
        }
        crosshair(c, r, dark, signal)
        if (state.locked) {
            drawCircle(dark, 4.5.dp.toPx(), c)
            drawCircle(signal, 3.dp.toPx(), c)
        }
    }
}

private fun DrawScope.crosshair(c: Offset, r: Float, outline: Color, fill: Color) {
    val gap = 5.dp.toPx()
    val len = r - 4.dp.toPx()
    val ow = 3.5.dp.toPx()
    val fw = 1.5.dp.toPx()
    val segs = listOf(
        Offset(c.x - len, c.y) to Offset(c.x - gap, c.y), Offset(c.x + gap, c.y) to Offset(c.x + len, c.y),
        Offset(c.x, c.y - len) to Offset(c.x, c.y - gap), Offset(c.x, c.y + gap) to Offset(c.x, c.y + len),
    )
    for ((a, b) in segs) drawLine(outline, a, b, ow, StrokeCap.Round)
    for ((a, b) in segs) drawLine(fill, a, b, fw, StrokeCap.Round)
}

@Composable
private fun LiveReadout(state: MeasureState, units: UnitPreference) {
    val reading = state.reading
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (reading != null && !state.isComplete) {
            val ctx = MeasureFormatter.contextFor(reading)
            val primary = MeasureFormatter.length(reading.primary, ctx, units).primary
            val secondary = reading.secondary
            if (secondary != null) {
                val h = MeasureFormatter.length(secondary, ctx, units).primary
                DimensionLabel(value = stringResource(R.string.history_rect_value, primary.text, h.text), onDarkChrome = true)
            } else {
                DimensionLabel(length = primary, onDarkChrome = true)
            }
        }
        val cm = state.targetDistanceCm
        if (cm != null && !state.isComplete) {
            Spacer(Modifier.height(4.dp))
            ChromePill(
                stringResource(R.string.measure_distance_to_target, MeasureFormatter.targetDistance(cm, units)),
                style = SpaceViewTheme.typography.caption,
            )
        }
    }
}

// ---- controls -------------------------------------------------------------------------------

@Composable
private fun Controls(state: MeasureState, onAddPoint: () -> Unit, onUndo: () -> Unit, onFreeze: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.pointCount > 0) ChromeIconButton(onClick = onUndo, description = R.string.measure_undo) { UndoGlyph(it) }
        }
        AddPointButton(
            onClick = onAddPoint,
            enabled = state.sessionRunning && state.tracking != TrackingStatus.INITIALIZING && !state.pendingCommit,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.pointCount > 0) ChromeIconButton(onClick = onFreeze, description = R.string.measure_freeze) { FreezeGlyph(it) }
        }
    }
}

@Composable
private fun ModeSelector(mode: MeasureMode, onModeChange: (MeasureMode) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = SpaceViewTheme.colors.arChrome) {
        Row(Modifier.selectableGroup().padding(4.dp)) {
            for ((m, label) in listOf(
                MeasureMode.DISTANCE to R.string.measure_mode_distance,
                MeasureMode.HEIGHT to R.string.measure_mode_height,
                MeasureMode.RECTANGLE to R.string.measure_mode_rectangle,
            )) {
                val selected = m == mode
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) SpaceViewTheme.colors.signal else Color.Transparent,
                    modifier = Modifier.selectable(selected = selected, role = Role.Tab, onClick = { onModeChange(m) }),
                ) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) SpaceViewTheme.colors.onSignal else SpaceViewTheme.colors.onArChrome,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// ---- small chrome helpers ----------------------------------------------------------------------

/** Text on a solid graphite pill (never a blur), legible over any camera image. */
@Composable
internal fun ChromePill(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge) {
    Surface(shape = MaterialTheme.shapes.small, color = SpaceViewTheme.colors.arChrome) {
        Text(
            text,
            style = style,
            color = SpaceViewTheme.colors.onArChrome,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
@Composable
internal fun ChromeIconButton(onClick: () -> Unit, @StringRes description: Int, glyph: @Composable BoxScope.(Color) -> Unit) {
    val label = stringResource(description)
    Surface(shape = MaterialTheme.shapes.large, color = SpaceViewTheme.colors.arChrome, modifier = Modifier.size(48.dp)) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
            Box(Modifier.size(24.dp)) { glyph(SpaceViewTheme.colors.onArChrome) }
        }
    }
}

@Composable
internal fun BoxScope.CloseGlyph(color: Color) {
    Canvas(Modifier.matchParentSize()) {
        val s = 2.dp.toPx()
        val p = size.minDimension * 0.22f
        drawLine(color, Offset(p, p), Offset(size.width - p, size.height - p), s, StrokeCap.Square)
        drawLine(color, Offset(size.width - p, p), Offset(p, size.height - p), s, StrokeCap.Square)
    }
}

@Composable
internal fun BoxScope.UndoGlyph(color: Color) {
    Canvas(Modifier.matchParentSize()) {
        val s = 2.dp.toPx()
        val r = size.minDimension * 0.32f
        drawArc(color, 200f, 250f, false, Offset(center.x - r, center.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(s))
        val tip = Offset(center.x - r * 0.94f, center.y - r * 0.34f)
        drawLine(color, tip, Offset(tip.x - 1.dp.toPx(), tip.y - 6.dp.toPx()), s, StrokeCap.Square)
        drawLine(color, tip, Offset(tip.x + 6.dp.toPx(), tip.y - 1.dp.toPx()), s, StrokeCap.Square)
    }
}

/** Snowflake-like "freeze" glyph drawn as three crossing strokes with end ticks. */
@Composable
internal fun BoxScope.FreezeGlyph(color: Color) {
    Canvas(Modifier.matchParentSize()) {
        val s = 1.75.dp.toPx()
        val r = size.minDimension * 0.4f
        for (k in 0 until 3) {
            val a = Math.toRadians(90.0 + k * 60.0)
            val dx = (kotlin.math.cos(a) * r).toFloat()
            val dy = (kotlin.math.sin(a) * r).toFloat()
            drawLine(color, Offset(center.x - dx, center.y - dy), Offset(center.x + dx, center.y + dy), s, StrokeCap.Square)
        }
        drawCircle(color, 2.dp.toPx(), center)
    }
}
