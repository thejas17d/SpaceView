package com.spaceview.app.scan

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.ArScreenAdGuard
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.CoachingCard
import com.spaceview.app.gallery.GalleryFormatter
import com.spaceview.app.measure.ArCameraView
import com.spaceview.app.measure.ChromeIconButton
import com.spaceview.app.measure.ChromePill
import com.spaceview.app.measure.ClickSounds
import com.spaceview.app.measure.CloseGlyph
import com.spaceview.app.measure.Controls
import com.spaceview.app.measure.FreezeOverlay
import com.spaceview.app.measure.KeepScreenOn
import com.spaceview.app.measure.LiveReadout
import com.spaceview.app.measure.Reticle
import com.spaceview.app.measure.instructionRes
import com.spaceview.app.measure.messageRes
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.CaptureHint
import com.spaceview.ar.CommitFailure
import com.spaceview.ar.FrontCaptureStatus
import com.spaceview.ar.FrontPhoto
import com.spaceview.ar.ImprovementTip
import com.spaceview.ar.MeasureEvent
import com.spaceview.ar.MeasureSession
import com.spaceview.ar.MeasureState
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.ar.TrackingStatus
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.delay

/**
 * The AR steps of a scan: outline (step 2) and front photo (step 3), on one [MeasureSession] in the
 * kind's capture mode (rectangle for flat objects, box otherwise). Every value shown carries the same
 * honest ± as Measure; no ads while this is composed ([ArScreenAdGuard]).
 */
@Composable
internal fun ScanArScreen(
    kind: ObjectKind,
    step: ScanStep,
    outline: ScanOutline?,
    settings: ScanSettings,
    onBack: () -> Unit,
    onOutline: (ScanOutline, ImprovementTip?, DeviceTier) -> Unit,
    onPhoto: (FrontPhoto) -> Unit,
    onSkipPhoto: () -> Unit,
    onRemeasure: () -> Unit,
) {
    ArScreenAdGuard(AdsManager.get(LocalContext.current))
    if (!settings.loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    KeepScreenOn()
    val shape = ScanDraftBuilder.shapeFor(kind)
    var sessionKey by remember { mutableIntStateOf(0) }
    var sessionFailed by remember { mutableStateOf(false) }

    key(sessionKey, settings.depthEnabled) {
        val session = remember { MeasureSession(shape.mode, settings.depthEnabled) }
        DisposableEffect(session) { onDispose { session.close() } }
        val sounds = remember { ClickSounds() }
        DisposableEffect(sounds) { onDispose { sounds.release() } }
        val soundsOn by rememberUpdatedState(settings.soundsEnabled)
        var rejected by remember { mutableStateOf<CommitFailure?>(null) }

        val state by session.state.collectAsStateWithLifecycle()
        val frozen by session.frozen.collectAsStateWithLifecycle()
        val capture by session.frontCapture.collectAsStateWithLifecycle()
        val photoReady by session.frontPhoto.collectAsStateWithLifecycle()

        // A scan never uses a stored calibration: it only applies inside the session that made it.
        LaunchedEffect(session) { session.setCalibration(null) }
        LaunchedEffect(session) {
            session.events.collect { e ->
                when (e) {
                    is MeasureEvent.PointAdded -> if (soundsOn) { if (e.complete) sounds.done() else sounds.tick() }
                    is MeasureEvent.CommitRejected -> rejected = e.reason
                    MeasureEvent.FreezeUnavailable -> Unit
                }
            }
        }
        LaunchedEffect(rejected) {
            if (rejected != null) {
                delay(TRANSIENT_MS)
                rejected = null
            }
        }
        // Photo step: arm the auto-capture whenever the session runs and nothing is armed or taken yet
        // (again after a pause). Outline step: never armed.
        // The confirmed outline (with its front face) is what gets photographed; the session never re-decides.
        LaunchedEffect(step, state.sessionRunning, state.tracking, capture == null, photoReady == null) {
            if (step == ScanStep.PHOTO && outline != null && state.sessionRunning && capture == null && photoReady == null) {
                session.startFrontCapture(outline)
            } else if (step == ScanStep.OUTLINE) {
                session.cancelFrontCapture()
            }
        }
        // Photo step on a session without the outline points (Try again, or SceneView re-created the
        // session): nothing to photograph, so go back to outlining (M3 review m3).
        LaunchedEffect(step, state.sessionRunning, state.pointCount) {
            if (step == ScanStep.PHOTO && state.sessionRunning && state.pointCount < shape.mode.requiredPoints) onBack()
        }
        // Back while adjusting on a frozen frame only leaves the freeze (registered after the route's handler, so it wins).
        BackHandler(enabled = frozen != null) { session.endFreeze(apply = false) }
        val photoCallback by rememberUpdatedState(onPhoto)
        LaunchedEffect(photoReady) {
            if (photoReady != null) session.takeFrontPhoto()?.let { photoCallback(it) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { session.setViewSize(it.width, it.height) },
        ) {
            ArCameraView(session, planeRenderer = frozen == null && step == ScanStep.OUTLINE, onSessionFailed = { sessionFailed = true })
            val f = frozen
            if (f != null) {
                FreezeOverlay(
                    frame = f,
                    units = settings.units,
                    showReading = true,
                    onAdjust = session::adjustFrozen,
                    onDone = { session.endFreeze(apply = true) },
                    onCancel = { session.endFreeze(apply = false) },
                )
            } else {
                ScanHud(
                    kind = kind,
                    shape = shape,
                    step = step,
                    state = state,
                    capture = capture,
                    units = settings.units,
                    sessionFailed = sessionFailed,
                    rejected = rejected,
                    onBack = onBack,
                    onRetry = { sessionFailed = false; sessionKey++ },
                    onAddPoint = session::addPoint,
                    onUndo = session::undo,
                    onFreeze = session::freeze,
                    outlineOf = { reading -> session.scanOutline(shape, reading) },
                    onNext = { outline -> onOutline(outline, state.tip, state.capabilities?.tier ?: DeviceTier.NO_DEPTH) },
                    onSkipPhoto = onSkipPhoto,
                    onRemeasure = {
                        session.clear()
                        onRemeasure()
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanHud(
    kind: ObjectKind,
    shape: ScanShape,
    step: ScanStep,
    state: MeasureState,
    capture: FrontCaptureStatus?,
    units: UnitPreference,
    sessionFailed: Boolean,
    rejected: CommitFailure?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onFreeze: () -> Unit,
    outlineOf: (com.spaceview.ar.Reading) -> ScanOutline?,
    onNext: (ScanOutline) -> Unit,
    onSkipPhoto: () -> Unit,
    onRemeasure: () -> Unit,
) {
    val coaching = state.tracking == TrackingStatus.INITIALIZING || !state.sessionRunning
    // Flat objects: no surface under the reticle for ~5 s while tracking → wall hint (the wall–floor-line
    // fallback is M4).
    var wallHint by remember { mutableStateOf(false) }
    LaunchedEffect(shape, step, state.hasSurface, state.pointCount, state.tracking) {
        wallHint = false
        if (shape == ScanShape.FLAT && step == ScanStep.OUTLINE && !state.hasSurface && state.pointCount == 0 &&
            state.tracking == TrackingStatus.GOOD
        ) {
            delay(WALL_HINT_DELAY_MS)
            wallHint = true
        }
    }
    val finished = state.reading?.takeIf { state.isComplete && !it.isLive }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(onClick = onBack, description = R.string.common_back) { CloseGlyph(it) }
                Spacer(Modifier.weight(1f))
                ChromePill(stepText(step))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            when {
                sessionFailed -> CoachingCard(
                    message = stringResource(R.string.arcore_session_failed),
                    actionLabel = stringResource(R.string.common_try_again), onAction = onRetry,
                )
                step == ScanStep.PHOTO -> Unit
                rejected != null -> CoachingCard(message = stringResource(rejected.messageRes()))
                state.pointCount > 0 && !state.pointsTracking -> CoachingCard(message = stringResource(R.string.measure_lost))
                coaching -> CoachingCard(title = stringResource(R.string.measure_coach_title), message = stringResource(R.string.measure_coach_move))
                state.tracking != TrackingStatus.GOOD -> CoachingCard(message = stringResource(state.tracking.messageRes()))
                state.isComplete -> Unit
                state.pendingCommit -> CoachingCard(message = stringResource(R.string.measure_hold_steady))
                wallHint -> CoachingCard(message = stringResource(R.string.scan_wall_hint))
                !state.hasSurface -> CoachingCard(message = stringResource(R.string.measure_no_surface))
                else -> CoachingCard(message = stringResource(instructionRes(shape.mode, state.pointCount)))
            }
        }

        if (step == ScanStep.OUTLINE && !sessionFailed && finished == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Reticle(state)
                Spacer(Modifier.height(8.dp))
                LiveReadout(state, units, showLength = true)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                sessionFailed -> Unit
                step == ScanStep.PHOTO -> PhotoCard(capture, onSkipPhoto, onRemeasure)
                finished != null -> {
                    val outline = remember(finished) { outlineOf(finished) }
                    OutlineCard(kind, outline, units, enabled = state.pointsTracking, onUndo = onUndo, onNext = { outline?.let(onNext) })
                }
                else -> Controls(state, onAddPoint, onUndo, onFreeze)
            }
        }
    }
}

@Composable
private fun ArCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = SpaceViewTheme.colors.arChrome,
        contentColor = SpaceViewTheme.colors.onArChrome,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** The finished outline: W / H (/ D) with their ±, the badge, Undo and Next. */
@Composable
private fun OutlineCard(kind: ObjectKind, outline: ScanOutline?, units: UnitPreference, enabled: Boolean, onUndo: () -> Unit, onNext: () -> Unit) {
    ArCard {
        Text(stringResource(R.string.scan_outline_title).uppercase(), style = SpaceViewTheme.typography.caption, modifier = Modifier.semantics { heading() })
        if (outline != null) {
            val values = ScanDraftBuilder.values(kind, outline)
            ArValueRow(R.string.gallery_width, values.width, kind, units)
            ArValueRow(R.string.gallery_height, values.height, kind, units)
            if (outline.depth != null) ArValueRow(R.string.gallery_depth, values.depth, kind, units)
            if (!outline.geometryConsistent) {
                Text(stringResource(R.string.calib_reject_skewed), style = MaterialTheme.typography.bodyMedium, color = SpaceViewTheme.colors.signal)
            }
            AccuracyBadge(values.quality, onDarkChrome = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.measure_undo), color = SpaceViewTheme.colors.onArChrome)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onNext,
                enabled = enabled && outline != null,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
            ) { Text(stringResource(R.string.scan_next)) }
        }
    }
}

@Composable
private fun ArValueRow(@StringRes label: Int, d: Dimension, kind: ObjectKind, units: UnitPreference) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
        Text(GalleryFormatter.dimension(d, kind, units).primary.text, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Front-photo guidance: one instruction, a hold progress bar, and Skip. Only the instruction is a live
 * region, and it never contains the changing angle, so TalkBack speaks once per new hint (m14).
 */
@Composable
private fun PhotoCard(capture: FrontCaptureStatus?, onSkip: () -> Unit, onRemeasure: () -> Unit) {
    ArCard {
        Text(stringResource(R.string.scan_photo_title).uppercase(), style = SpaceViewTheme.typography.caption, modifier = Modifier.semantics { heading() })
        val hint = capture?.hint ?: CaptureHint.NOT_TRACKING
        Text(
            stringResource(
                when (hint) {
                    CaptureHint.NOT_TRACKING -> R.string.scan_photo_tracking
                    CaptureHint.FACE_OBJECT -> R.string.scan_photo_face
                    CaptureHint.TILT -> R.string.scan_photo_tilt
                    CaptureHint.STEP_BACK -> R.string.scan_photo_step_back
                    CaptureHint.STEP_CLOSER -> R.string.scan_photo_step_closer
                    CaptureHint.HOLD_STILL -> R.string.scan_photo_hold_still
                    CaptureHint.HOLDING -> R.string.scan_photo_holding
                    CaptureHint.CAPTURED -> R.string.scan_photo_captured
                    CaptureHint.REMEASURE -> R.string.scan_photo_remeasure
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (hint == CaptureHint.TILT) {
            Text(stringResource(R.string.scan_photo_tilt_angle, capture?.angleDeg ?: 0), style = MaterialTheme.typography.bodyMedium)
        }
        if (hint != CaptureHint.REMEASURE) {
            val progress = when (hint) {
                CaptureHint.HOLDING -> capture?.progress ?: 0f
                CaptureHint.CAPTURED -> 1f
                else -> 0f
            }
            val progressLabel = stringResource(R.string.scan_photo_progress)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
                color = SpaceViewTheme.colors.signal,
            )
        }
        Row {
            if (hint == CaptureHint.REMEASURE) {
                TextButton(onClick = onRemeasure, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_measure_again), color = SpaceViewTheme.colors.onArChrome)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSkip, enabled = hint != CaptureHint.CAPTURED, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.scan_photo_skip), color = SpaceViewTheme.colors.onArChrome)
            }
        }
    }
}

@StringRes
private fun CommitFailure.messageRes(): Int = when (this) {
    CommitFailure.NOT_TRACKING -> R.string.measure_reject_not_tracking
    CommitFailure.NO_SURFACE -> R.string.measure_reject_no_surface
    CommitFailure.NOT_STEADY -> R.string.measure_reject_not_steady
    CommitFailure.SAME_SPOT -> R.string.measure_reject_same_spot
    CommitFailure.BOX_TOP_TOO_LOW -> R.string.scan_reject_box_top
}

private const val TRANSIENT_MS = 3000L

/** "Can't find the wall" after this long without a surface (ui-ux §3.3 A.2). Tunable. */
internal const val WALL_HINT_DELAY_MS = 5000L
