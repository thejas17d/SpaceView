package com.spaceview.app.furniture

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.testTag
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
import com.spaceview.app.common.spokenText
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
import com.spaceview.app.measure.messageRes
import com.spaceview.app.scan.ScanSettings
import com.spaceview.app.smart.HandsFreeUi
import com.spaceview.app.smart.rememberHandsFree
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.CommitFailure
import com.spaceview.ar.MeasureEvent
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasureSession
import com.spaceview.ar.MeasureState
import com.spaceview.ar.TrackingStatus
import com.spaceview.data.gallery.Dimension
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.furniture.FurnitureTemplate
import com.spaceview.engine.furniture.MeasureHint
import com.spaceview.engine.furniture.ParamError
import com.spaceview.engine.furniture.ParamSpec
import kotlinx.coroutines.delay

/** What the guided measure shows for the current parameter (built by the route from the view model's state). */
internal class GuidedStep(
    val template: FurnitureTemplate,
    val spec: ParamSpec,
    val stepText: String,
    /** The value already set for this parameter (measured or typed), if any. */
    val current: Dimension?,
    /** "Use typical" is offered (key extras only; W, D and H must be measured or typed). */
    val optional: Boolean,
    /** The error a measured [Measurement] would cause, so "Use this" is refused for a size the shape cannot have. */
    val errorFor: (Double) -> ParamError?,
)

/**
 * The guided measure (T): one [MeasureSession] in DISTANCE or HEIGHT mode for the parameter being measured, the same
 * honest ± and badge as Measure, and the step indicator. Reuses Measure's AR host, reticle, controls and freeze through
 * the public `MeasureSession` API (no fork). Every value can be typed instead (an estimate without ±).
 *
 * Call from **one** call site for every measure step, so the session (and tracking) survives the step changes. No ads
 * while composed ([ArScreenAdGuard]); a paywall never opens from here (gates run at the picker and at Save).
 */
@Composable
internal fun FurnitureMeasureScreen(
    step: GuidedStep,
    settings: ScanSettings,
    onBack: () -> Unit,
    onUse: (Measurement, DeviceTier) -> Unit,
    onType: () -> Unit,
    onUseTypical: () -> Unit,
    onNext: () -> Unit,
) {
    ArScreenAdGuard(AdsManager.get(LocalContext.current))
    if (!settings.loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    KeepScreenOn()
    var sessionKey by remember { mutableIntStateOf(0) }
    var sessionFailed by remember { mutableStateOf(false) }
    val mode = modeFor(step.spec)

    key(sessionKey, settings.depthEnabled) {
        val session = remember { MeasureSession(mode, settings.depthEnabled) }
        DisposableEffect(session) { onDispose { session.close() } }
        val sounds = remember { ClickSounds() }
        DisposableEffect(sounds) { onDispose { sounds.release() } }
        val soundsOn by rememberUpdatedState(settings.soundsEnabled)
        var rejected by remember { mutableStateOf<CommitFailure?>(null) }
        val state by session.state.collectAsStateWithLifecycle()
        val frozen by session.frozen.collectAsStateWithLifecycle()
        // Hands-free only while the reticle is on screen (not the result card, a freeze or a failed session).
        val handsFree = rememberHandsFree(session, settings.handsFreeEnabled, suspended = sessionFailed || frozen != null || state.isComplete)

        // A furniture measurement never uses a stored calibration (it only applies inside the session that made it).
        LaunchedEffect(session) { session.setCalibration(null) }
        // Each parameter starts with no points, in its own mode.
        LaunchedEffect(session, step.spec.id) {
            session.clear()
            session.setMode(mode)
        }
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
        BackHandler(enabled = frozen != null) { session.endFreeze(apply = false) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { session.setViewSize(it.width, it.height) },
        ) {
            ArCameraView(session, planeRenderer = frozen == null, onSessionFailed = { sessionFailed = true }, handsFree = handsFree)
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
                GuidedHud(
                    step = step,
                    state = state,
                    units = settings.units,
                    sessionFailed = sessionFailed,
                    rejected = rejected,
                    onBack = onBack,
                    onRetry = { sessionFailed = false; sessionKey++ },
                    onAddPoint = session::addPoint,
                    onUndo = session::undo,
                    onFreeze = session::freeze,
                    onRedo = session::clear,
                    onUse = { m -> onUse(m, state.capabilities?.tier ?: DeviceTier.NO_DEPTH) },
                    onType = onType,
                    onUseTypical = onUseTypical,
                    onNext = onNext,
                    handsFree = handsFree,
                )
            }
        }
    }
}

/** DISTANCE for widths and depths, HEIGHT for vertical sizes. */
internal fun modeFor(spec: ParamSpec): MeasureMode = if (spec.hint == MeasureHint.HEIGHT) MeasureMode.HEIGHT else MeasureMode.DISTANCE

@Composable
private fun GuidedHud(
    step: GuidedStep,
    state: MeasureState,
    units: UnitPreference,
    sessionFailed: Boolean,
    rejected: CommitFailure?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onFreeze: () -> Unit,
    onRedo: () -> Unit,
    onUse: (Measurement) -> Unit,
    onType: () -> Unit,
    onUseTypical: () -> Unit,
    onNext: () -> Unit,
    handsFree: HandsFreeUi,
) {
    val label = stringResource(FurnitureLabels.param(step.template, step.spec.id))
    val coaching = state.tracking == TrackingStatus.INITIALIZING || !state.sessionRunning
    val finished = state.reading?.takeIf { state.isComplete && !it.isLive }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(onClick = onBack, description = R.string.common_back) { CloseGlyph(it) }
                Spacer(Modifier.weight(1f))
                ChromePill(step.stepText)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            val hint = FurnitureLabels.hint(step.template, step.spec.id)?.let { stringResource(it) }.orEmpty()
            when {
                sessionFailed -> CoachingCard(
                    message = stringResource(R.string.arcore_session_failed),
                    actionLabel = stringResource(R.string.common_try_again), onAction = onRetry,
                )
                rejected != null -> CoachingCard(message = stringResource(rejected.rejectRes()))
                state.pointCount > 0 && !state.pointsTracking -> CoachingCard(message = stringResource(R.string.measure_lost))
                coaching -> CoachingCard(title = stringResource(R.string.measure_coach_title), message = stringResource(R.string.measure_coach_move))
                state.tracking != TrackingStatus.GOOD -> CoachingCard(message = stringResource(state.tracking.messageRes()))
                else -> CoachingCard(title = stringResource(R.string.furniture_measure_title, label.lowercase()), message = hint)
            }
        }

        if (!sessionFailed && finished == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Reticle(state, handsFree::progress)
                Spacer(Modifier.height(8.dp))
                LiveReadout(state, units, showLength = true)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (finished != null) {
                ResultCard(step, label, finished.primary, finished.quality, units, enabled = state.pointsTracking, onRedo = onRedo, onUse = onUse, onType = onType)
            } else {
                step.current?.let {
                    val now = stringResource(R.string.furniture_now, FurnitureFormat.dimension(it, units))
                    ChromePill(now, spoken = spokenText(now))
                }
                if (!sessionFailed) Controls(state, onAddPoint, onUndo, onFreeze, handsFree)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArTextButton(stringResource(R.string.furniture_type), onType, Modifier.testTag("furniture_type"))
                    if (step.optional) ArTextButton(stringResource(R.string.furniture_use_typical), onUseTypical)
                    if (step.current != null || step.optional) ArTextButton(stringResource(R.string.furniture_next), onNext)
                }
            }
        }
    }
}

/** The finished reading: value with its ±, the badge, a refusal when the shape cannot have it, Measure again / Type / Use this. */
@Composable
private fun ResultCard(
    step: GuidedStep,
    label: String,
    m: Measurement,
    quality: com.spaceview.engine.Quality,
    units: UnitPreference,
    enabled: Boolean,
    onRedo: () -> Unit,
    onUse: (Measurement) -> Unit,
    onType: () -> Unit,
) {
    val stored = FurnitureFlow.measured(m)
    val error = step.errorFor(stored.meters)
    val shownValue = FurnitureFormat.dimension(stored, units)
    val spokenValue = spokenText(shownValue)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = SpaceViewTheme.colors.arChrome,
        contentColor = SpaceViewTheme.colors.onArChrome,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), style = SpaceViewTheme.typography.caption, modifier = Modifier.semantics { heading() })
            Text(
                shownValue,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spokenValue
                },
            )
            AccuracyBadge(quality, onDarkChrome = true)
            if (error != null) {
                Text(FurnitureFormat.error(error, label, units), style = MaterialTheme.typography.bodyMedium, color = SpaceViewTheme.colors.signal)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRedo, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.furniture_measure_again), color = SpaceViewTheme.colors.onArChrome)
                }
                TextButton(onClick = onType, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.furniture_type), color = SpaceViewTheme.colors.onArChrome)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onUse(m) },
                    enabled = enabled && error == null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("furniture_use"),
                    colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
                ) { Text(stringResource(R.string.furniture_use_this)) }
            }
        }
    }
}

@Composable
private fun ArTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = SpaceViewTheme.colors.arChrome, modifier = modifier) {
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text, color = SpaceViewTheme.colors.onArChrome)
        }
    }
}

private fun CommitFailure.rejectRes(): Int = when (this) {
    CommitFailure.NOT_TRACKING -> R.string.measure_reject_not_tracking
    CommitFailure.NO_SURFACE -> R.string.measure_reject_no_surface
    CommitFailure.NOT_STEADY -> R.string.measure_reject_not_steady
    CommitFailure.SAME_SPOT -> R.string.measure_reject_same_spot
    CommitFailure.BOX_TOP_TOO_LOW -> R.string.scan_reject_box_top
}

private const val TRANSIENT_MS = 3000L
