package com.spaceview.app.place

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.ArScreenAdGuard
import com.spaceview.app.common.AddPointButton
import com.spaceview.app.measure.ArCameraView
import com.spaceview.app.measure.ArGate
import com.spaceview.app.measure.ChromeIconButton
import com.spaceview.app.measure.CloseGlyph
import com.spaceview.app.measure.KeepScreenOn
import com.spaceview.app.measure.MeasureFormatter
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasureSession
import com.spaceview.ar.MeasureState
import com.spaceview.ar.PlacedItem
import com.spaceview.ar.PlacementController
import com.spaceview.ar.PlacementState
import com.spaceview.ar.PlacementStatus
import com.spaceview.ar.Reading
import com.spaceview.ar.SurfaceTarget
import com.spaceview.data.gallery.PlacementModel
import com.spaceview.engine.Fit
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Pose
import com.spaceview.engine.UnitPreference
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Place in room (M4, route `place/{id}`): permission / ARCore gate, then the AR placement screen. No ads are
 * shown or preloaded while it is composed, and Place never owes one ([ArScreenAdGuard], K2, D7).
 */
@Composable
fun PlaceRoute(viewModel: PlaceViewModel, onBack: () -> Unit) {
    ArScreenAdGuard(AdsManager.get(LocalContext.current))
    ArGate(onClose = onBack) {
        val load by viewModel.load.collectAsStateWithLifecycle()
        when (val l = load) {
            PlaceLoad.Loading -> MessageCard(stringResource(R.string.place_loading), onBack = null, onRetry = null)
            PlaceLoad.Deleted -> MessageCard(stringResource(R.string.place_deleted), onBack = onBack, onRetry = null)
            PlaceLoad.Failed -> MessageCard(stringResource(R.string.place_failed), onBack = onBack, onRetry = viewModel::reload)
            is PlaceLoad.Ready -> PlaceArScreen(l.loaded, viewModel, onBack)
        }
    }
}

/** Fit-check sub-mode of the screen (UI only). */
private enum class FitMode { OFF, GAP, AREA }

@Composable
private fun PlaceArScreen(loaded: LoadedModel, vm: PlaceViewModel, onBack: () -> Unit) {
    KeepScreenOn()
    val model = loaded.model
    val units by vm.units.collectAsStateWithLifecycle()
    val savedTarget by vm.target.collectAsStateWithLifecycle()
    val space by vm.space.collectAsStateWithLifecycle()
    var sessionKey by remember { mutableIntStateOf(0) }
    var sessionFailed by remember { mutableStateOf(false) }
    var restoredNote by remember { mutableStateOf(vm.restoredAfterDeath) }

    key(sessionKey) {
        // One MeasureSession (primary listener: camera config, reticle hit, the gap measurement) and the
        // placement listener after it, sharing one world frame (ADR-001).
        val session = remember { MeasureSession(MeasureMode.DISTANCE, depthWanted = false) }
        val controller = remember {
            PlacementController(
                PlacedItem(model.widthM, model.heightM, model.depthM, model.origin),
                savedTarget ?: PlaceViewModel.defaultTarget(model.origin),
                session,
            )
        }
        val extra = remember(controller) { listOf(controller) }
        DisposableEffect(session, controller) {
            onDispose {
                controller.close()
                session.close()
            }
        }
        // Read before any effect can overwrite it with the new controller's initial 0°.
        val initialSpinDeg = remember { vm.spinDeg }
        val holder = remember { NodeHolder() }
        DisposableEffect(controller, holder) {
            controller.onModelPose = holder::show
            onDispose { controller.onModelPose = null }
        }
        LaunchedEffect(controller) {
            // A space is never measured with a stored calibration (it only applies inside its own session).
            session.setCalibration(null)
            if (initialSpinDeg != 0) controller.twist(Math.toRadians(initialSpinDeg.toDouble()))
        }

        val status by controller.status.collectAsStateWithLifecycle()
        val measure by session.state.collectAsStateWithLifecycle()
        var fitMode by remember { mutableStateOf(FitMode.OFF) }
        var placeFailed by remember { mutableStateOf(false) }
        var notOnFloor by remember { mutableStateOf(false) }
        LaunchedEffect(status.spinDeg) { vm.setSpinDeg(status.spinDeg) }
        // After each drop on a wall, re-apply the saved slider height once the floor is known (process death
        // restores the value, not the anchor).
        val placedOnWall = status.state is PlacementState.Placed && status.target.isWall
        var heightApplied by remember { mutableStateOf(false) }
        LaunchedEffect(placedOnWall, status.centreHeightM != null) {
            if (!placedOnWall) {
                heightApplied = false
            } else if (!heightApplied && status.centreHeightM != null) {
                vm.centreHeightM?.let { controller.setCentreHeight(it) }
                heightApplied = true
            }
        }
        LaunchedEffect(status.target) { if (status.target != SurfaceTarget.WALL_FALLBACK) vm.setTarget(status.target) }
        LaunchedEffect(fitMode) {
            when (fitMode) {
                FitMode.OFF -> session.clear()
                FitMode.GAP -> session.setMode(MeasureMode.DISTANCE)
                FitMode.AREA -> session.setMode(MeasureMode.RECTANGLE)
            }
        }
        LaunchedEffect(placeFailed, notOnFloor) {
            if (placeFailed || notOnFloor) {
                kotlinx.coroutines.delay(TRANSIENT_MS)
                placeFailed = false
                notOnFloor = false
            }
        }
        BackHandler(enabled = fitMode != FitMode.OFF || status.state is PlacementState.FallbackLine) {
            if (fitMode != FitMode.OFF) fitMode = FitMode.OFF else controller.cancelFallback()
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { session.setViewSize(it.width, it.height) },
        ) {
            ArCameraView(
                session,
                planeRenderer = true,
                onSessionFailed = { sessionFailed = true },
                extraListeners = extra,
                content = { PlacedModel(loaded.bytes, holder) },
            )
            CentreMark(Modifier.align(Alignment.Center))
            PlaceHud(
                model = model,
                status = status,
                measure = measure,
                fitMode = fitMode,
                space = space,
                units = units,
                restoredNote = restoredNote,
                sessionFailed = sessionFailed,
                placeFailed = placeFailed,
                notOnFloor = notOnFloor,
                verdict = vm.verdict(model, status.target, space),
                spaceBeforePause = vm.spaceMeasuredBeforePause(status.pauses),
                onClose = onBack,
                onRetrySession = { sessionFailed = false; sessionKey++ },
                onTarget = { restoredNote = false; controller.target(it) },
                onPlace = { restoredNote = false; placeFailed = !controller.dropAtReticle() },
                onMove = controller::pickUp,
                onRotate = controller::rotate90,
                onNudge = controller::nudge,
                onEyeLevel = { if (controller.setCentreHeight(EYE_LEVEL_M)) vm.setCentreHeight(EYE_LEVEL_M) },
                onCentreHeight = { h -> if (controller.setCentreHeight(h)) vm.setCentreHeight(h) },
                onStartFallback = controller::startFallback,
                onAddFallbackPoint = { notOnFloor = !controller.addFallbackPoint() },
                onCancelFallback = controller::cancelFallback,
                onFitMode = { fitMode = it },
                onAddPoint = session::addPoint,
                onUndo = session::undo,
                onUseMeasurement = { reading ->
                    vm.setSpace(spaceOf(reading, session), status.pauses)
                    fitMode = FitMode.OFF
                },
                onClearSpace = { vm.setSpace(null) },
            )
        }
    }
}

/** Holds the SceneView node of the model so per-frame poses bypass Compose (§3.5). Main thread only. */
private class NodeHolder {
    private var node: Node? = null

    fun set(n: Node) {
        node = n
        n.isVisible = false
    }

    fun show(pose: Pose?) {
        val n = node ?: return
        if (pose == null) {
            n.isVisible = false
            return
        }
        val t = pose.translation
        val q = pose.rotation
        n.worldPosition = Float3(t.x.toFloat(), t.y.toFloat(), t.z.toFloat())
        n.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat())
        // Scale is never touched: the model is always 1:1 (no pinch-scale, ui-ux §2.6).
        n.isVisible = true
    }
}

/**
 * The model at 1:1 under a parent node whose transform [holder] sets every frame. `scaleToUnits` and
 * `centerOrigin` are null so SceneView never rescales or recentres it; editing gestures are off.
 */
@Composable
private fun ARSceneScope.PlacedModel(bytes: ByteArray, holder: NodeHolder) {
    val instance = remember(bytes) {
        runCatching {
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }
            modelLoader.createModelInstance(buffer)
        }.getOrNull()
    } ?: return
    Node(isEditable = false, apply = holder::set) {
        ModelNode(modelInstance = instance, scaleToUnits = null, centerOrigin = null, isEditable = false)
    }
}

/** The reticle: a small crosshair at the view centre (the placement and fit hits are taken there). */
@Composable
private fun CentreMark(modifier: Modifier) {
    val signal = SpaceViewTheme.colors.signal
    Canvas(modifier.size(36.dp)) {
        val c = center
        val r = 10.dp.toPx()
        val w = 2.dp.toPx()
        drawLine(signal, c.copy(x = c.x - r), c.copy(x = c.x + r), w)
        drawLine(signal, c.copy(y = c.y - r), c.copy(y = c.y + r), w)
    }
}

@Composable
private fun PlaceHud(
    model: PlacementModel,
    status: PlacementStatus,
    measure: MeasureState,
    fitMode: FitMode,
    space: SpaceReading?,
    units: UnitPreference,
    restoredNote: Boolean,
    sessionFailed: Boolean,
    placeFailed: Boolean,
    notOnFloor: Boolean,
    verdict: PlaceFit?,
    spaceBeforePause: Boolean,
    onClose: () -> Unit,
    onRetrySession: () -> Unit,
    onTarget: (SurfaceTarget) -> Unit,
    onPlace: () -> Unit,
    onMove: () -> Unit,
    onRotate: () -> Unit,
    onNudge: (Int, Int) -> Unit,
    onEyeLevel: () -> Unit,
    onCentreHeight: (Double) -> Unit,
    onStartFallback: () -> Unit,
    onAddFallbackPoint: () -> Unit,
    onCancelFallback: () -> Unit,
    onFitMode: (FitMode) -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onUseMeasurement: (Reading) -> Unit,
    onClearSpace: () -> Unit,
) {
    val chrome = SpaceViewTheme.colors.arChrome
    val onChrome = SpaceViewTheme.colors.onArChrome
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        // ---- top: close, true-size chip, surface chips ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChromeIconButton(onClick = onClose, description = R.string.place_close) { CloseGlyph(it) }
            val trueSize = stringResource(R.string.place_true_size_a11y)
            Surface(shape = MaterialTheme.shapes.small, color = chrome, modifier = Modifier.semantics { contentDescription = trueSize }) {
                Text(stringResource(R.string.place_true_size), color = onChrome, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        if (model.modelKind == ModelKind.MESH_DEVICE || model.modelKind == ModelKind.MESH_CLOUD) {
            ChromeNote(stringResource(if (model.meshFallback) R.string.place_mesh_fallback else R.string.place_mesh_label))
        }
        if (fitMode == FitMode.OFF && status.state !is PlacementState.FallbackLine) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                for ((t, label) in listOf(
                    SurfaceTarget.FLOOR to R.string.place_target_floor,
                    SurfaceTarget.TABLE to R.string.place_target_table,
                    SurfaceTarget.WALL to R.string.place_target_wall,
                )) {
                    val selected = status.target == t || (t == SurfaceTarget.WALL && status.target == SurfaceTarget.WALL_FALLBACK)
                    FilterChip(selected = selected, onClick = { onTarget(t) }, label = { Text(stringResource(label)) })
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ---- hint / errors ----
        val hint = when {
            sessionFailed -> R.string.place_session_failed
            placeFailed -> R.string.place_place_failed
            notOnFloor && status.state is PlacementState.FallbackLine -> R.string.place_fallback_not_floor
            fitMode == FitMode.GAP -> R.string.place_measure_hint_gap
            fitMode == FitMode.AREA -> R.string.place_measure_hint_area
            else -> hintFor(status, restoredNote)
        }
        ChromeNote(stringResource(hint), live = true)
        if (sessionFailed) {
            Button(onClick = onRetrySession) { Text(stringResource(R.string.place_retry)) }
        }
        if (status.offerWallFallback && fitMode == FitMode.OFF) {
            ChromeNote(stringResource(R.string.place_fallback_offer))
            Button(onClick = onStartFallback) { Text(stringResource(R.string.place_fallback_use)) }
        }
        val height = status.centreHeightM
        if (height != null && status.state is PlacementState.Placed && fitMode == FitMode.OFF) {
            ChromeNote(stringResource(R.string.place_height, MeasureFormatter.targetDistance((height * 100).toInt(), units)))
            if (status.target.isWall) HeightSlider(height, onCentreHeight)
        }

        // ---- verdict ----
        if (verdict != null && space != null && fitMode == FitMode.OFF) {
            FitCard(verdict, space, units, spaceBeforePause, onAgain = { onFitMode(if (space.kind == SpaceKind.GAP) FitMode.GAP else FitMode.AREA) }, onClear = onClearSpace)
        }

        // ---- controls ----
        Spacer(Modifier.height(8.dp))
        when {
            fitMode != FitMode.OFF -> FitControls(fitMode, measure, units, onFitMode, onAddPoint, onUndo, onUseMeasurement)
            status.state is PlacementState.FallbackLine -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onCancelFallback) { Text(stringResource(R.string.place_cancel)) }
                AddPointButton(onClick = onAddFallbackPoint, contentDescription = stringResource(R.string.place_fallback_add))
            }
            status.state is PlacementState.Placed -> PlacedControls(status.target.isWall, onMove, onRotate, onNudge, onEyeLevel.takeIf { status.centreHeightM != null }, onFitMode)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlace, enabled = status.state is PlacementState.Preview) { Text(stringResource(R.string.place_action_place)) }
                OutlinedButton(onClick = onRotate) { Text(stringResource(R.string.place_action_rotate)) }
            }
        }
    }
}

@StringRes
private fun hintFor(status: PlacementStatus, restored: Boolean): Int = when (val s = status.state) {
    is PlacementState.FallbackLine -> when {
        status.fallbackRejected -> R.string.place_fallback_rejected
        s.pointsTapped == 0 -> R.string.place_fallback_step0
        else -> R.string.place_fallback_step1
    }
    is PlacementState.Placed -> if (s.tracking) R.string.place_hint_placed else R.string.place_hint_tracking_lost
    is PlacementState.Preview -> R.string.place_hint_preview
    PlacementState.Searching -> when {
        restored -> R.string.place_hint_restored
        !status.cameraTracking -> R.string.place_hint_init
        status.target.isWall -> R.string.place_hint_searching_wall
        status.target == SurfaceTarget.TABLE -> R.string.place_hint_searching_table
        else -> R.string.place_hint_searching_floor
    }
}

@Composable
private fun PlacedControls(
    onWall: Boolean,
    onMove: () -> Unit,
    onRotate: () -> Unit,
    onNudge: (Int, Int) -> Unit,
    onEyeLevel: (() -> Unit)?,
    onFitMode: (FitMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeButton("←", R.string.place_nudge_left) { onNudge(-1, 0) }
            NudgeButton("→", R.string.place_nudge_right) { onNudge(1, 0) }
            NudgeButton("↑", if (onWall) R.string.place_nudge_up else R.string.place_nudge_away) { onNudge(0, 1) }
            NudgeButton("↓", if (onWall) R.string.place_nudge_down else R.string.place_nudge_closer) { onNudge(0, -1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onMove) { Text(stringResource(R.string.place_action_move)) }
            OutlinedButton(onClick = onRotate) { Text(stringResource(R.string.place_action_rotate)) }
            if (onEyeLevel != null) OutlinedButton(onClick = onEyeLevel) { Text(stringResource(R.string.place_eye_level)) }
        }
        Button(onClick = { onFitMode(if (onWall) FitMode.AREA else FitMode.GAP) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.place_check_fit))
        }
    }
}

/**
 * The wall height slider (§3.4 step 3): the item's centre height above the floor, 20–240 cm. Moves the placed item
 * vertically on its anchor; the value is saved for process death and re-applied after the next drop.
 */
@Composable
private fun HeightSlider(heightM: Double, onChange: (Double) -> Unit) {
    val label = stringResource(R.string.place_height_slider)
    Slider(
        value = heightM.toFloat().coerceIn(MIN_CENTRE_HEIGHT_M, MAX_CENTRE_HEIGHT_M),
        onValueChange = { onChange(it.toDouble()) },
        valueRange = MIN_CENTRE_HEIGHT_M..MAX_CENTRE_HEIGHT_M,
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).semantics { contentDescription = label },
    )
}

@Composable
private fun NudgeButton(glyph: String, @StringRes description: Int, onClick: () -> Unit) {
    val label = stringResource(description)
    Surface(shape = MaterialTheme.shapes.large, color = SpaceViewTheme.colors.arChrome, modifier = Modifier.size(48.dp)) {
        TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
            Text(glyph, color = SpaceViewTheme.colors.onArChrome)
        }
    }
}

@Composable
private fun FitControls(
    mode: FitMode,
    measure: MeasureState,
    units: UnitPreference,
    onFitMode: (FitMode) -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onUse: (Reading) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = mode == FitMode.GAP, onClick = { onFitMode(FitMode.GAP) }, label = { Text(stringResource(R.string.place_measure_gap)) })
            FilterChip(selected = mode == FitMode.AREA, onClick = { onFitMode(FitMode.AREA) }, label = { Text(stringResource(R.string.place_measure_area)) })
        }
        val reading = measure.reading
        if (reading != null) {
            val ctx = MeasureFormatter.contextFor(reading)
            val text = buildString {
                append(MeasureFormatter.length(reading.primary, ctx, units).primary.text)
                reading.secondary?.let { append(" × ").append(MeasureFormatter.length(it, ctx, units).primary.text) }
            }
            ChromeNote(text, live = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onFitMode(FitMode.OFF) }) { Text(stringResource(R.string.place_cancel)) }
            if (measure.pointCount > 0) OutlinedButton(onClick = onUndo) { Text(stringResource(R.string.place_undo)) }
            val done = reading != null && measure.isComplete && !reading.isLive
            if (done) {
                Button(onClick = { onUse(reading!!) }) { Text(stringResource(R.string.place_use_measurement)) }
            } else {
                AddPointButton(onClick = onAddPoint, enabled = measure.sessionRunning)
            }
        }
    }
}

@Composable
private fun FitCard(fit: PlaceFit, space: SpaceReading, units: UnitPreference, beforePause: Boolean, onAgain: () -> Unit, onClear: () -> Unit) {
    val colors = SpaceViewTheme.colors
    val accent = when (fit.fit) {
        Fit.FITS -> colors.accuracyGood
        Fit.TIGHT -> colors.accuracyFair
        Fit.TOO_BIG -> colors.accuracyRough
    }
    val headline = when (fit.fit) {
        Fit.FITS -> stringResource(R.string.place_fit_fits, amountText(PlaceVerdict.amount(fit.clearanceM, units, roundUp = false)))
        Fit.TIGHT -> stringResource(R.string.place_fit_tight)
        Fit.TOO_BIG -> stringResource(R.string.place_fit_too_big, amountText(PlaceVerdict.amount(fit.clearanceM, units, roundUp = true)))
    }
    Surface(shape = MaterialTheme.shapes.medium, color = colors.arChrome, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
        Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, color = accent, style = MaterialTheme.typography.titleMedium)
            val notes = buildList {
                add(stringResource(R.string.place_fit_band, amountText(PlaceVerdict.amount(fit.bandM, units, roundUp = true))))
                add(stringResource(when (fit.limitingAxis) {
                    FitAxis.WIDTH -> R.string.place_fit_axis_width
                    FitAxis.HEIGHT -> R.string.place_fit_axis_height
                    FitAxis.DEPTH -> R.string.place_fit_axis_depth
                }))
                if (fit.usedTypedSize) add(stringResource(R.string.place_fit_typed))
                if (fit.widthOnly) {
                    add(stringResource(R.string.place_fit_width_only))
                } else if (fit.heightNotChecked) {
                    add(stringResource(R.string.place_fit_height_unchecked))
                }
                if (beforePause) add(stringResource(R.string.place_fit_before_pause))
                if (fit.rotated) add(stringResource(R.string.place_fit_rotated))
                if (fit.boundingBox) add(stringResource(R.string.place_fit_bounding))
            }
            for (n in notes) Text(n, color = colors.onArChrome, style = MaterialTheme.typography.bodySmall)
            val spaceText = buildString {
                append(amountText(PlaceVerdict.amount(space.primaryM, units, roundUp = false)))
                space.secondaryM?.let { append(" × ").append(amountText(PlaceVerdict.amount(it, units, roundUp = false))) }
            }
            Text(stringResource(R.string.place_fit_space, spaceText), color = colors.onArChrome, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onAgain) { Text(stringResource(R.string.place_fit_again)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.place_fit_clear)) }
            }
        }
    }
}

@Composable
private fun amountText(a: PlaceVerdict.Amount): String =
    stringResource(if (a.imperial) R.string.place_amount_in else R.string.place_amount_cm, a.value.toInt())

@Composable
private fun ChromeNote(text: String, live: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small, color = SpaceViewTheme.colors.arChrome, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text,
            color = SpaceViewTheme.colors.onArChrome,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        )
    }
}

@Composable
private fun MessageCard(text: String, onBack: (() -> Unit)?, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.medium, color = SpaceViewTheme.colors.arChrome, modifier = Modifier.padding(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, color = SpaceViewTheme.colors.onArChrome)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onBack != null) OutlinedButton(onClick = onBack) { Text(stringResource(R.string.place_back)) }
                    if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.place_retry)) }
                }
            }
        }
    }
}

/**
 * A completed reading as a [SpaceReading]. For a rectangle, which side is horizontal is decided from the committed
 * corners (the reading's sides may be reordered by the engine): the side whose length matches the most horizontal
 * edge is the width.
 */
internal fun spaceOf(reading: Reading, session: MeasureSession): SpaceReading {
    val primary = reading.primary
    val secondary = reading.secondary
    if (reading.mode != MeasureMode.RECTANGLE || secondary == null) {
        return SpaceReading(SpaceKind.GAP, primary.value, primary.sigma)
    }
    val p = session.committedPoints().map { it.point.position }
    val horizontalFirst = if (p.size >= 3) {
        PlaceGeometry.primaryIsHorizontal(primary.value, secondary.value, p[1] - p[0], p[2] - p[1])
    } else {
        true
    }
    return SpaceReading(SpaceKind.AREA, primary.value, primary.sigma, secondary.value, secondary.sigma, horizontalFirst)
}

/** Pure helpers for the space reading (JVM-tested). */
internal object PlaceGeometry {
    /** True when [primaryM] is the length of the more horizontal of the two corner edges [e1], [e2]. */
    fun primaryIsHorizontal(primaryM: Double, secondaryM: Double, e1: com.spaceview.engine.Vec3, e2: com.spaceview.engine.Vec3): Boolean {
        fun tilt(e: com.spaceview.engine.Vec3) = if (e.length < 1e-9) 1.0 else abs(e.y) / e.length
        val horizontal = if (tilt(e1) <= tilt(e2)) e1.length else e2.length
        return abs(primaryM - horizontal) <= abs(secondaryM - horizontal)
    }
}

/** Eye-level guide for wall items (ui-ux §3.5): centre 145 cm above the floor. */
private const val EYE_LEVEL_M = 1.45
private const val MIN_CENTRE_HEIGHT_M = 0.2f
private const val MAX_CENTRE_HEIGHT_M = 2.4f
private const val TRANSIENT_MS = 3000L
