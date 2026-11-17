package com.spaceview.app.scan3d

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.common.CoachingCard
import com.spaceview.app.measure.ArCameraView
import com.spaceview.app.measure.ArGate
import com.spaceview.app.measure.ChromeIconButton
import com.spaceview.app.measure.ChromePill
import com.spaceview.app.measure.CloseGlyph
import com.spaceview.app.measure.Controls
import com.spaceview.app.measure.FreezeOverlay
import com.spaceview.app.measure.KeepScreenOn
import com.spaceview.app.measure.LiveReadout
import com.spaceview.app.measure.Reticle
import com.spaceview.app.measure.instructionRes
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.ArFrameListener
import com.spaceview.ar.CaptureProgress
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasureSession
import com.spaceview.ar.MeasureState
import com.spaceview.ar.Reading
import com.spaceview.engine.UnitPreference
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import java.nio.ByteBuffer
import java.util.Locale

/** The lab's screens (debug builds only). */
@Composable
internal fun Scan3dLabApp(vm: LabViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (ui.step) {
        LabViewModel.Step.INTRO -> IntroScreen(ui, vm, onClose)
        LabViewModel.Step.AR -> {
            BackHandler { vm.backToIntro() }
            ArGate(onClose = vm::backToIntro) { LabArScreen(vm, ui) }
        }
        LabViewModel.Step.PROCESSING -> ProcessingScreen(ui.stage)
        LabViewModel.Step.RESULT -> {
            val r = ui.result
            if (ui.viewing && r?.glb != null) {
                BackHandler { vm.setViewing(false) }
                MeshViewer(r.glb, onClose = { vm.setViewing(false) })
            } else {
                BackHandler { vm.backToIntro() }
                ResultScreen(ui, vm)
            }
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

// ---- intro ---------------------------------------------------------------------------------------------

@Composable
private fun IntroScreen(ui: LabViewModel.Ui, vm: LabViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val chooser = stringResource(R.string.scan3d_share_chooser)
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan3d_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.scan3d_close)) }
        }
        Text(stringResource(R.string.scan3d_subtitle), style = MaterialTheme.typography.bodyMedium)
        Heading(stringResource(R.string.scan3d_protocol_title))
        Text(stringResource(R.string.scan3d_protocol_body), style = MaterialTheme.typography.bodyMedium)

        Heading(stringResource(R.string.scan3d_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in LabViewModel.LABELS) FilterChip(selected = ui.label == l, onClick = { vm.setLabel(l) }, label = { Text(l) })
        }
        OutlinedTextField(value = ui.label, onValueChange = vm::setLabel, singleLine = true, label = { Text(stringResource(R.string.scan3d_label)) }, modifier = Modifier.fillMaxWidth())

        Heading(stringResource(R.string.scan3d_tape_title))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapeField(ui.tapeW, R.string.scan3d_tape_w, Modifier.weight(1f)) { vm.setTape(w = it) }
            TapeField(ui.tapeH, R.string.scan3d_tape_h, Modifier.weight(1f)) { vm.setTape(h = it) }
            TapeField(ui.tapeD, R.string.scan3d_tape_d, Modifier.weight(1f)) { vm.setTape(d = it) }
        }
        Button(onClick = vm::beginAr, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.scan3d_start)) }
        Text(stringResource(R.string.scan3d_runs_saved, ui.runCount), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.share(context, chooser, withMesh = false) }, enabled = ui.runCount > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.scan3d_share_all))
        }
        if (ui.shareFailed) Text(stringResource(R.string.scan3d_share_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TapeField(value: String, label: Int, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

// ---- AR: mark the box, then capture ----------------------------------------------------------------------

@Composable
private fun LabArScreen(vm: LabViewModel, ui: LabViewModel.Ui) {
    val capture = vm.capture ?: return
    KeepScreenOn()
    var sessionKey by remember { mutableIntStateOf(0) }
    var sessionFailed by remember { mutableStateOf(false) }
    // The AR screen going away (activity finishing) stops the capture; the view model processes it.
    DisposableEffect(capture) { onDispose { capture.detach() } }
    key(sessionKey) {
        // Primary: MeasureSession (camera config, Depth on, BOX outline). Then the capture listener (S1, ADR-001).
        val session = remember { MeasureSession(MeasureMode.BOX, depthWanted = true) }
        DisposableEffect(session) { onDispose { session.close() } }
        LaunchedEffect(session) { session.setCalibration(null) }
        val extra = remember(capture) { listOf<ArFrameListener>(capture) }
        val state by session.state.collectAsStateWithLifecycle()
        val frozen by session.frozen.collectAsStateWithLifecycle()
        val progress by capture.progress.collectAsStateWithLifecycle()
        BackHandler(enabled = frozen != null) { session.endFreeze(apply = false) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { session.setViewSize(it.width, it.height) },
        ) {
            ArCameraView(session, planeRenderer = !progress.running && frozen == null, onSessionFailed = { sessionFailed = true }, extraListeners = extra)
            val f = frozen
            if (f != null) {
                FreezeOverlay(f, UnitPreference.METRIC, showReading = true, onAdjust = session::adjustFrozen, onDone = { session.endFreeze(apply = true) }, onCancel = { session.endFreeze(apply = false) })
            } else {
                LabHud(
                    state = state,
                    progress = progress,
                    ui = ui,
                    sessionFailed = sessionFailed,
                    onClose = vm::backToIntro,
                    onRetry = { sessionFailed = false; sessionKey++ },
                    onAddPoint = session::addPoint,
                    onUndo = session::undo,
                    onFreeze = session::freeze,
                    onStart = { reading -> vm.startCapture(session.committedPoints().map { it.point.position }, reading, state.capabilities?.tier) },
                    onFinish = vm::finishCapture,
                )
            }
        }
    }
}

@Composable
private fun LabHud(
    state: MeasureState,
    progress: CaptureProgress,
    ui: LabViewModel.Ui,
    sessionFailed: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onFreeze: () -> Unit,
    onStart: (Reading) -> Unit,
    onFinish: () -> Unit,
) {
    val finished = state.reading?.takeIf { state.isComplete && !it.isLive }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(onClick = onClose, description = R.string.common_back) { CloseGlyph(it) }
                Spacer(Modifier.weight(1f))
                ChromePill(sourceText(progress))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            when {
                sessionFailed -> CoachingCard(
                    message = stringResource(R.string.arcore_session_failed),
                    actionLabel = stringResource(R.string.common_try_again), onAction = onRetry,
                )
                progress.running -> Unit
                ui.startError != null -> CoachingCard(message = stringResource(errorRes(ui.startError)))
                finished == null -> CoachingCard(message = stringResource(instructionRes(MeasureMode.BOX, state.pointCount)))
                else -> Unit
            }
        }
        if (!sessionFailed && finished == null && !progress.running) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Reticle(state)
                Spacer(Modifier.height(8.dp))
                LiveReadout(state, UnitPreference.METRIC, showLength = true)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            when {
                sessionFailed -> Unit
                progress.running -> CaptureCard(progress, onFinish)
                finished != null -> BoxCard(finished, starting = ui.starting, canStart = progress.source != null && state.pointsTracking, onUndo = onUndo, onStart = { onStart(finished) })
                else -> Controls(state, onAddPoint, onUndo, onFreeze)
            }
        }
    }
}

@Composable
private fun sourceText(p: CaptureProgress): String {
    val s = p.source ?: return stringResource(R.string.scan3d_source_none)
    return stringResource(R.string.scan3d_source, s.name)
}

private fun errorRes(e: LabViewModel.StartError): Int = when (e) {
    LabViewModel.StartError.BAD_BOX -> R.string.scan3d_err_bad_box
    LabViewModel.StartError.TOO_BIG -> R.string.scan3d_err_too_big
    LabViewModel.StartError.NO_DEPTH -> R.string.scan3d_err_no_depth
    LabViewModel.StartError.NO_MEMORY -> R.string.scan3d_err_no_memory
    LabViewModel.StartError.TOO_WARM -> R.string.scan3d_err_too_warm
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

@Composable
private fun BoxCard(reading: Reading, starting: Boolean, canStart: Boolean, onUndo: () -> Unit, onStart: () -> Unit) {
    ArCard {
        Text(stringResource(R.string.scan3d_box_ready), style = MaterialTheme.typography.titleMedium)
        Text(boxText(reading), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.measure_undo), color = SpaceViewTheme.colors.onArChrome)
            }
            Spacer(Modifier.weight(1f))
            if (starting) CircularProgressIndicator(Modifier.padding(8.dp))
            Button(
                onClick = onStart,
                enabled = canStart && !starting,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
            ) { Text(stringResource(R.string.scan3d_start_capture)) }
        }
    }
}

private fun boxText(r: Reading): String {
    fun cm(m: com.spaceview.engine.Measurement?) = m?.let { String.format(Locale.ROOT, "%.1f ± %.1f", it.value * 100, it.bound95 * 100) } ?: "–"
    return "W ${cm(r.primary)} · H ${cm(r.boxHeight)} · D ${cm(r.secondary)} cm"
}

@Composable
private fun CaptureCard(p: CaptureProgress, onFinish: () -> Unit) {
    ArCard {
        Text(stringResource(R.string.scan3d_capturing), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.scan3d_capture_status, (p.elapsedMs / 1000).toInt(), p.integrated, p.rateHz), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(
                R.string.scan3d_capture_detail,
                String.format(Locale.ROOT, "%.1f", p.lastFusionMs),
                (p.observedFraction * 100).toInt(),
                if (p.thermal >= 0) p.thermal.toString() else "?",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!p.tracking) Text(stringResource(R.string.measure_lost), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
        ) { Text(stringResource(R.string.scan3d_finish)) }
    }
}

// ---- processing ----------------------------------------------------------------------------------------

@Composable
private fun ProcessingScreen(stage: String?) {
    Page {
        Spacer(Modifier.height(48.dp))
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.scan3d_processing), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (stage != null) Text(stringResource(R.string.scan3d_processing_stage, stage), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

// ---- result --------------------------------------------------------------------------------------------

@Composable
private fun ResultScreen(ui: LabViewModel.Ui, vm: LabViewModel) {
    val r = ui.result ?: return
    val m = r.metrics
    val context = LocalContext.current
    val chooser = stringResource(R.string.scan3d_share_chooser)
    Page {
        Heading(stringResource(R.string.scan3d_result_title) + " · " + m["label"])
        if (r.error != null) Text(stringResource(R.string.scan3d_error, r.error), color = MaterialTheme.colorScheme.error)
        val badge = m["crosscheck_badge"]
        if (badge.isNotEmpty()) {
            Text(
                if (m["crosscheck_agrees"] == "true") stringResource(R.string.scan3d_badge_ok) else stringResource(R.string.scan3d_badge_rough, m["crosscheck_worst_axis"]),
                style = MaterialTheme.typography.titleSmall,
                color = if (m["crosscheck_agrees"] == "true") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Heading(stringResource(R.string.scan3d_sizes))
        for (ax in listOf("w", "h", "d")) {
            Text(
                "${ax.uppercase()}: ${m["box_${ax}_cm"]} ± ${m["box_${ax}_pm_cm"]}  /  ${m["mesh_${ax}_cm"].ifEmpty { "–" }} ± ${m["mesh_${ax}_pm_cm"].ifEmpty { "–" }}  /  ${m["tape_${ax}_cm"].ifEmpty { "–" }}",
                style = SpaceViewTheme.typography.spec,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setViewing(true) }, enabled = r.glb != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.scan3d_view_mesh)) }
            OutlinedButton(onClick = vm::save, enabled = r.glb != null && ui.save == LabViewModel.SaveState.IDLE, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.scan3d_save))
            }
        }
        when (ui.save) {
            LabViewModel.SaveState.SAVING -> CircularProgressIndicator()
            LabViewModel.SaveState.SAVED -> Text(stringResource(R.string.scan3d_saved))
            LabViewModel.SaveState.NEEDS_PRO -> Text(stringResource(R.string.scan3d_save_needs_pro), color = MaterialTheme.colorScheme.error)
            LabViewModel.SaveState.FAILED -> Text(stringResource(R.string.scan3d_save_failed), color = MaterialTheme.colorScheme.error)
            LabViewModel.SaveState.IDLE -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.share(context, chooser, withMesh = false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.scan3d_share_run)) }
            OutlinedButton(onClick = { vm.share(context, chooser, withMesh = true) }, enabled = r.glb != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.scan3d_share_mesh))
            }
        }
        if (ui.shareFailed) Text(stringResource(R.string.scan3d_share_failed), color = MaterialTheme.colorScheme.error)
        Button(onClick = vm::beginAr, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.scan3d_new_scan)) }
        TextButton(onClick = vm::backToIntro, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.scan3d_done)) }

        Heading(stringResource(R.string.scan3d_all_metrics))
        // Raw metric keys = the CSV / JSON column names, so the screen and the file read the same.
        for (k in LabMetrics.COLUMNS) {
            Text("$k = ${m[k].ifEmpty { "–" }}", style = SpaceViewTheme.typography.spec)
        }
    }
}

// ---- mesh viewer ---------------------------------------------------------------------------------------

/** The scan GLB in a plain (non-AR) SceneView: drag to orbit, pinch to zoom. Normalised to 1 unit at the origin. */
@Composable
private fun MeshViewer(glb: ByteArray, onClose: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = remember(glb) {
        runCatching {
            val buffer = ByteBuffer.allocateDirect(glb.size).put(glb).also { it.flip() }
            modelLoader.createModelInstance(buffer)
        }.getOrNull()
    }
    val cameraNode = rememberCameraNode(engine) {
        position = Float3(0f, 0.6f, 1.8f)
        lookAt(Float3(0f, 0f, 0f))
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(orbitHomePosition = cameraNode.worldPosition, targetPosition = Float3(0f, 0f, 0f)),
        ) {
            if (instance != null) ModelNode(modelInstance = instance, scaleToUnits = 1f, centerOrigin = Float3(0f, 0f, 0f))
        }
        Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            ChromeIconButton(onClick = onClose, description = R.string.common_back) { CloseGlyph(it) }
            Spacer(Modifier.height(8.dp))
            ChromePill(stringResource(R.string.scan3d_viewer_hint))
        }
    }
}
