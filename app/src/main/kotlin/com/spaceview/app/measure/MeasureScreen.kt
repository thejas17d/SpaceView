package com.spaceview.app.measure

import android.view.WindowManager
import com.spaceview.app.ads.AdsManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.common.ar.ArSessionHost
import com.spaceview.app.smart.HandsFreeAnnouncer
import com.spaceview.app.smart.HandsFreeUi
import com.spaceview.app.smart.rememberHandsFree
import com.spaceview.ar.ArFrameListener
import io.github.sceneview.ar.ARSceneScope
import com.spaceview.ar.MeasureEvent
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasureSession
import com.spaceview.engine.ReferenceObject
import kotlinx.coroutines.delay

/**
 * Measure route: permission / ARCore gate, then the AR measuring screen.
 *
 * @param startWithCalibration open straight into the calibration flow (a tape-measured known length).
 */
@Composable
fun MeasureRoute(
    initialMode: MeasureMode,
    startWithCalibration: Boolean,
    viewModel: MeasureViewModel,
    onClose: () -> Unit,
) {
    ArGate(onClose = onClose) {
        MeasureArScreen(initialMode, startWithCalibration, viewModel, onClose)
    }
}

/** UI-only calibration state of the screen. */
internal sealed interface CalibrationStep {
    data object Choosing : CalibrationStep
    /** [reference] null = a known (tape-measured) length, measured as a distance; else a card / sheet. */
    data class Measuring(val reference: ReferenceObject?) : CalibrationStep
}

@Composable
private fun MeasureArScreen(
    initialMode: MeasureMode,
    startWithCalibration: Boolean,
    vm: MeasureViewModel,
    onClose: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    KeepScreenOn()
    var sessionKey by remember { mutableIntStateOf(0) }
    var sessionFailed by remember { mutableStateOf(false) }

    // A new MeasureSession (and ARCore session) per key: retry after a failure, or depth toggled.
    key(sessionKey, settings.depthEnabled) {
        val session = remember { MeasureSession(initialMode, settings.depthEnabled) }
        DisposableEffect(session) { onDispose { session.close() } }

        var calibration by remember { mutableStateOf<CalibrationStep?>(if (startWithCalibration) CalibrationStep.Choosing else null) }
        var measureMode by rememberSaveable { mutableStateOf(initialMode) }
        val sounds = remember { ClickSounds() }
        DisposableEffect(sounds) { onDispose { sounds.release() } }
        val soundsOn by rememberUpdatedState(settings.soundsEnabled)
        var transient by remember { mutableStateOf<TransientMessage?>(null) }

        // Calibration measures a distance (known length) or a rectangle (card / A4) with the *uncalibrated* model.
        val calibrating = calibration is CalibrationStep.Measuring
        val calibrationMode = (calibration as? CalibrationStep.Measuring)?.let {
            if (it.reference == null) MeasureMode.DISTANCE else MeasureMode.RECTANGLE
        }
        // While aiming at a known-length span its AR value stays hidden, so it cannot be typed back in (gate R2).
        val measuringKnownLength = calibration.let { it is CalibrationStep.Measuring && it.reference == null }
        val arSessionId by session.sessionId.collectAsStateWithLifecycle()
        LaunchedEffect(calibrationMode, measureMode) {
            session.setMode(calibrationMode ?: measureMode)
        }
        // A stored calibration applies only inside the ARCore session that made it (M2 review M3).
        LaunchedEffect(calibrating, settings.calibration, arSessionId) {
            session.setCalibration(if (calibrating) null else vm.calibrationFor(settings.calibration, arSessionId))
        }
        LaunchedEffect(session) {
            session.events.collect { e ->
                when (e) {
                    is MeasureEvent.PointAdded -> if (soundsOn) { if (e.complete) sounds.done() else sounds.tick() }
                    is MeasureEvent.CommitRejected -> transient = TransientMessage.Rejected(e.reason)
                    MeasureEvent.FreezeUnavailable -> transient = TransientMessage.FreezeUnavailable
                }
            }
        }
        val ads = AdsManager.get(LocalContext.current)
        LaunchedEffect(vm) {
            vm.events.collect { e ->
                transient = when (e) {
                    MeasureVmEvent.Saved -> {
                        ads.recordCompletedScan()
                        TransientMessage.Saved
                    }
                    MeasureVmEvent.CalibrationApplied -> TransientMessage.CalibrationApplied
                    MeasureVmEvent.StorageFailed -> TransientMessage.StorageFailed
                }
            }
        }
        LaunchedEffect(transient) {
            if (transient != null) {
                delay(TRANSIENT_MS)
                transient = null
            }
        }

        val state by session.state.collectAsStateWithLifecycle()
        val frozen by session.frozen.collectAsStateWithLifecycle()
        // Hands-free only while the reticle and controls are on screen (never behind the reference picker), and off
        // at the start of a calibration, where the aim must be exact (SM1 review MAJOR 1).
        val handsFree = rememberHandsFree(
            session,
            settings.handsFreeEnabled,
            suspended = handsFreeSuspended(calibration, sessionFailed, frozen != null),
            startOff = calibration != null,
        )

        BackHandler(enabled = frozen != null || calibration != null) {
            when {
                frozen != null -> session.endFreeze(apply = false)
                calibration != null -> {
                    calibration = null
                    session.clear()
                    if (startWithCalibration) onClose()
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { session.setViewSize(it.width, it.height) },
        ) {
            // W9: hands-free rectangles are found in the camera image (outline → hold → locked → measured).
            val state0 by session.state.collectAsStateWithLifecycle()
            val finder = rememberRectangleFinder(
                session,
                enabled = handsFree.enabled && calibration == null && frozen == null && !sessionFailed &&
                    measureMode == MeasureMode.RECTANGLE && state0.mode == MeasureMode.RECTANGLE,
            )
            val finderListeners = remember(finder) { listOf<com.spaceview.ar.ArFrameListener>(finder) }
            ArCameraView(
                session, planeRenderer = frozen == null, onSessionFailed = { sessionFailed = true },
                extraListeners = finderListeners, handsFree = handsFree,
            )
            if (frozen == null && finder.enabled && state0.pointCount == 0) RectangleOutline(finder)
            val finderState by finder.state.collectAsStateWithLifecycle()
            val rectHint = finderState.hintRes()?.takeIf { finder.enabled && state0.pointCount == 0 }

            val f = frozen
            if (f != null) {
                FreezeOverlay(
                    frame = f,
                    units = settings.units,
                    showReading = !measuringKnownLength,
                    onAdjust = session::adjustFrozen,
                    onDone = { session.endFreeze(apply = true) },
                    onCancel = { session.endFreeze(apply = false) },
                )
            } else {
                MeasureHud(
                    state = state,
                    settings = settings,
                    calibration = calibration,
                    sessionFailed = sessionFailed,
                    transient = transient,
                    onClose = onClose,
                    onModeChange = { measureMode = it },
                    onAddPoint = session::addPoint,
                    onUndo = session::undo,
                    onFreeze = session::freeze,
                    onNew = session::clear,
                    // Saves exactly the reading on the result card (M2 review minor 1).
                    onSave = { reading, label ->
                        vm.save(reading, label, state.capabilities?.tier, handsFree = session.anyHandsFreePoint())
                        session.clear()
                    },
                    onStartOver = session::clear,
                    onRetrySession = { sessionFailed = false; sessionKey++ },
                    onDismissSafety = vm::dismissSafetyNotice,
                    onChooseReference = { ref -> calibration = startCalibration(ref, session::clear) },
                    onCancelCalibration = {
                        calibration = null
                        session.clear()
                        if (startWithCalibration) onClose()
                    },
                    onApplyCalibration = { outcome ->
                        vm.applyCalibration(outcome, arSessionId)
                        calibration = null
                        session.clear()
                    },
                    onRetryCalibration = session::clear,
                    calibrationCheck = session::calibrationCheck,
                    knownLengthCheck = session::knownLengthCheck,
                    handsFree = handsFree,
                    rectHint = rectHint,
                )
            }
        }
    }
}

/**
 * Hands-free must not drop points the user cannot see: while the reference picker covers the reticle, while a frozen
 * photo is being adjusted, or after the AR session failed (SM1 review MAJOR 1).
 */
internal fun handsFreeSuspended(calibration: CalibrationStep?, sessionFailed: Boolean, frozen: Boolean): Boolean =
    calibration == CalibrationStep.Choosing || sessionFailed || frozen

/**
 * A calibration reference was chosen: it always starts from zero points (stray points, from a dwell or a tap before
 * the choice, would otherwise become the calibrated span when the mode does not change; SM1 review MAJOR 1).
 */
internal fun startCalibration(reference: ReferenceObject?, clearPoints: () -> Unit): CalibrationStep {
    clearPoints()
    return CalibrationStep.Measuring(reference)
}

/** Short-lived messages shown in the hint area. */
internal sealed interface TransientMessage {
    data class Rejected(val reason: com.spaceview.ar.CommitFailure) : TransientMessage
    data object FreezeUnavailable : TransientMessage
    data object Saved : TransientMessage
    data object CalibrationApplied : TransientMessage
    data object StorageFailed : TransientMessage
}

private const val TRANSIENT_MS = 3000L

/**
 * The camera with SceneView's ARCore session driven by [session] (through the shared [ArSessionHost]), plus the
 * dimension-line overlay (drawn from [MeasureSession.overlay], never through Compose). Emits two full-size
 * children into the caller's Box. Shared by Measure and Scan; Place adds its own listener after [session].
 *
 * @param extraListeners listeners called after [session] on every frame (must not change for this composable).
 * @param handsFree the hands-free point drop (SM1a): its listener runs right after [session], and its TalkBack
 *   announcement lives here, so every measuring screen gets the same behaviour from one place.
 */
@Composable
internal fun ArCameraView(
    session: MeasureSession,
    planeRenderer: Boolean,
    onSessionFailed: () -> Unit,
    extraListeners: List<ArFrameListener> = emptyList(),
    handsFree: HandsFreeUi? = null,
    content: (@Composable ARSceneScope.() -> Unit)? = null,
) {
    // Keyed on the dropper (one per session), never on the switch state: the host's listener list must not change.
    val dropper = handsFree?.dropper
    val listeners = remember(session, extraListeners, dropper) {
        listOf<ArFrameListener>(session) + listOfNotNull(dropper) + extraListeners
    }
    ArSessionHost(listeners = listeners, planeRenderer = planeRenderer, onSessionFailed = onSessionFailed, content = content)
    if (handsFree != null) HandsFreeAnnouncer(handsFree)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MeasureOverlayView(ctx).also { v ->
                v.frame = session.overlay
                session.onOverlayChanged = { v.invalidate() }
            }
        },
    )
}

/** FLAG_KEEP_SCREEN_ON on the AR window only (never a wake lock). */
@Composable
internal fun KeepScreenOn() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
