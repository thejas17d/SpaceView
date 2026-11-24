package com.spaceview.app.range

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.CoachingCard
import com.spaceview.app.common.ar.ArSessionHost
import com.spaceview.app.common.spokenText
import com.spaceview.app.measure.ArGate
import com.spaceview.app.measure.ChromeIconButton
import com.spaceview.app.measure.ChromePill
import com.spaceview.app.measure.ClickSounds
import com.spaceview.app.measure.CloseGlyph
import com.spaceview.app.measure.KeepScreenOn
import com.spaceview.app.measure.messageRes
import com.spaceview.app.measure.rememberThrottledAnnouncement
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.RangeEvent
import com.spaceview.ar.RangePhase
import com.spaceview.ar.RangeState
import com.spaceview.ar.RangefinderSession
import com.spaceview.ar.TrackingStatus
import com.spaceview.engine.range.RangeOutcome
import com.spaceview.engine.range.RangeReading

/**
 * Distance (rangefinder, docs/PLAN-v3-2D.md W4): the line-of-sight distance from the phone to the point under the
 * crosshair, up to 500 m, always with ±, a method badge and the Good / Fair / Rough badge. No measuring tools, no
 * saving, **no ads** (an AR screen). TalkBack announces held readings and 2-sight results.
 *
 * Wiring (orchestrator): `composable(Routes.DISTANCE) { RangefinderRoute(onBack = { navController.popBackStack() },
 * viewModel = viewModel { RangefinderViewModel(container.measureSettings, container.appSettings) }) }`.
 */
@Composable
fun RangefinderRoute(onBack: () -> Unit, viewModel: RangefinderViewModel) {
    ArGate(onClose = onBack) {
        RangefinderArScreen(viewModel, onBack)
    }
}

@Composable
private fun RangefinderArScreen(vm: RangefinderViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    KeepScreenOn()
    var sessionKey by remember { mutableIntStateOf(0) }
    var sessionFailed by remember { mutableStateOf(false) }
    key(sessionKey, settings.depthEnabled) {
        val session = remember { RangefinderSession(settings.depthEnabled) }
        DisposableEffect(session) { onDispose { session.close() } }
        val sounds = remember { ClickSounds() }
        DisposableEffect(sounds) { onDispose { sounds.release() } }
        val soundsOn by rememberUpdatedState(settings.soundsEnabled)
        val state by session.state.collectAsStateWithLifecycle()
        var transient by remember { mutableStateOf<Int?>(null) }
        val view = LocalView.current
        val imperial = settings.imperial

        LaunchedEffect(session) {
            session.events.collect { e ->
                when (e) {
                    is RangeEvent.SightTaken -> if (soundsOn) sounds.tick()
                    is RangeEvent.SightFailed -> transient = RangeUi.sightFailureRes(e.reason)
                    is RangeEvent.Result -> if (soundsOn && e.outcome is RangeOutcome.Ok) sounds.done()
                    is RangeEvent.Held -> Unit
                }
            }
        }
        // TalkBack: a held reading and every 2-sight outcome are announced once, in words.
        val heldSpoken = state.held?.let { spokenReading(it, imperial) }
        LaunchedEffect(heldSpoken) { heldSpoken?.let { view.announceForAccessibility(it) } }
        val resultSpoken = state.result?.takeIf { state.phase == RangePhase.RESULT }?.let { spokenOutcome(it, imperial) }
        LaunchedEffect(resultSpoken) { resultSpoken?.let { view.announceForAccessibility(it) } }
        LaunchedEffect(transient) {
            if (transient != null) {
                kotlinx.coroutines.delay(3000)
                transient = null
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { session.setViewSize(it.width, it.height) },
        ) {
            val listeners = remember(session) { listOf(session) }
            ArSessionHost(listeners = listeners, planeRenderer = true, onSessionFailed = { sessionFailed = true })
            RangeHud(
                state = state,
                imperial = imperial,
                sessionFailed = sessionFailed,
                transient = transient,
                onClose = onBack,
                onRetrySession = { sessionFailed = false; sessionKey++ },
                session = session,
            )
        }
    }
}

@Composable
private fun RangeHud(
    state: RangeState,
    imperial: Boolean,
    sessionFailed: Boolean,
    transient: Int?,
    onClose: () -> Unit,
    onRetrySession: () -> Unit,
    session: RangefinderSession,
) {
    val coaching = !state.sessionRunning || state.tracking == TrackingStatus.INITIALIZING
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // ---- top: close, title, hint
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(onClick = onClose, description = R.string.range_close) { CloseGlyph(it) }
                Spacer(Modifier.weight(1f))
                Surface(shape = MaterialTheme.shapes.small, color = SpaceViewTheme.colors.arChrome) {
                    Text(
                        stringResource(R.string.range_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = SpaceViewTheme.colors.onArChrome,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            val hint: String? = when {
                sessionFailed -> null
                transient != null -> stringResource(transient)
                coaching -> stringResource(R.string.range_coach)
                state.tracking != TrackingStatus.GOOD -> stringResource(state.tracking.messageRes())
                else -> phaseHint(state, imperial)
            }
            if (sessionFailed) {
                CoachingCard(
                    message = stringResource(R.string.arcore_session_failed),
                    actionLabel = stringResource(R.string.common_try_again),
                    onAction = onRetrySession,
                )
            } else if (hint != null) {
                CoachingCard(message = hint)
            }
        }

        // ---- centre: crosshair and the number
        if (!sessionFailed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(96.dp))
                Crosshair(state)
                Spacer(Modifier.height(12.dp))
                Readout(state, imperial)
            }
        }

        // ---- bottom: controls for the phase
        if (!sessionFailed && !coaching) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Controls(state, session)
            }
        }
    }
}

/** The instruction for the current phase (live: why there is no number, when there is none). */
@Composable
private fun phaseHint(state: RangeState, imperial: Boolean): String? = when (state.phase) {
    RangePhase.LIVE -> when {
        state.held != null -> null
        state.live != null -> null
        else -> state.liveIssue?.let { stringResource(RangeUi.issueRes(it)) } ?: stringResource(R.string.range_aim)
    }
    RangePhase.AIM_SIGHT_1 -> stringResource(R.string.range_aim_sight_1)
    RangePhase.TAKING_SIGHT_1, RangePhase.TAKING_SIGHT_2 -> stringResource(R.string.range_taking)
    RangePhase.WALK -> stringResource(R.string.range_walk, RangeUi.walkText(state.requiredBaselineM ?: 1.0, imperial))
    RangePhase.RESULT -> (state.result as? RangeOutcome.Rejected)?.let { stringResource(RangeUi.rejectionRes(it.reason)) }
}

@Composable
private fun Crosshair(state: RangeState) {
    val signal = SpaceViewTheme.colors.signal
    val dark = Color(0xFF16181B)
    val description = stringResource(R.string.range_crosshair)
    Canvas(Modifier.size(72.dp).semantics { contentDescription = description }) {
        val c = center
        val r = 26.dp.toPx()
        val w = 2.dp.toPx()
        drawCircle(dark, r, c, style = Stroke(w * 2.2f))
        drawCircle(Color.White.copy(alpha = 0.85f), r, c, style = Stroke(w))
        if (state.stability > 0f) {
            drawArc(
                color = signal, startAngle = -90f, sweepAngle = 360f * state.stability.coerceIn(0f, 1f), useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                style = Stroke(w * 1.6f, cap = StrokeCap.Round),
            )
        }
        val gap = 4.dp.toPx()
        val len = r + 8.dp.toPx()
        for ((a, b) in listOf(
            Offset(c.x - len, c.y) to Offset(c.x - gap, c.y), Offset(c.x + gap, c.y) to Offset(c.x + len, c.y),
            Offset(c.x, c.y - len) to Offset(c.x, c.y - gap), Offset(c.x, c.y + gap) to Offset(c.x, c.y + len),
        )) {
            drawLine(dark, a, b, 3.5.dp.toPx(), StrokeCap.Round)
            drawLine(signal, a, b, 1.5.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(dark, 2.5.dp.toPx(), c)
        drawCircle(signal, 1.5.dp.toPx(), c)
    }
}

/** The number on screen: held, 2-sight result, 2-sight preview or the live reading. */
@Composable
private fun Readout(state: RangeState, imperial: Boolean) {
    val result = state.result.takeIf { state.phase == RangePhase.RESULT }
    val reading: RangeReading? = when {
        result is RangeOutcome.Ok -> result.reading
        state.phase == RangePhase.WALK -> null
        state.phase == RangePhase.LIVE -> state.held ?: state.live
        else -> null
    }
    val outOfRange = result == RangeOutcome.OutOfRange ||
        (state.phase == RangePhase.LIVE && state.live == null && state.liveIssue == com.spaceview.ar.RangeIssue.OUT_OF_RANGE)
    when {
        reading != null -> ReadingBlock(reading, imperial, live = state.phase == RangePhase.LIVE && state.held == null)
        outOfRange -> ChromePill(
            stringResource(R.string.range_out_of_range),
            style = MaterialTheme.typography.titleMedium,
            spoken = stringResource(R.string.range_out_of_range_a11y),
        )
        state.phase == RangePhase.WALK -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ChromePill(stringResource(R.string.range_walked, RangeUi.walkText(state.walkedBaselineM, imperial)), announce = false)
            state.preview?.let {
                Spacer(Modifier.height(6.dp))
                val t = RangeUi.text(it, imperial).text
                ChromePill(stringResource(R.string.range_preview, t), style = SpaceViewTheme.typography.caption, spoken = spokenText(stringResource(R.string.range_preview, t)))
            }
        }
    }
}

@Composable
private fun ReadingBlock(reading: RangeReading, imperial: Boolean, live: Boolean) {
    val text = RangeUi.text(reading, imperial)
    val method = stringResource(RangeUi.methodRes(reading.method))
    val spoken = spokenReading(reading, imperial)
    // A live value changes up to 10× a second: a polite node speaks it only once it settles.
    if (live) {
        val announced = rememberThrottledAnnouncement(spoken, settleOnly = true)
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announced })
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Surface(shape = MaterialTheme.shapes.medium, color = SpaceViewTheme.colors.arChrome) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${text.value} ${text.unitSymbol}",
                    color = SpaceViewTheme.colors.onArChrome,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "± ${text.plusMinus} ${text.unitSymbol}",
                    color = SpaceViewTheme.colors.onArChrome,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.range_line_of_sight),
                    color = SpaceViewTheme.colors.onArChrome.copy(alpha = 0.75f),
                    style = SpaceViewTheme.typography.caption,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChromePill(method, style = SpaceViewTheme.typography.caption)
            AccuracyBadge(reading.quality, onDarkChrome = true)
        }
        if (reading.assumesLevelGround) {
            Spacer(Modifier.height(6.dp))
            ChromePill(stringResource(R.string.range_assumes_level), style = SpaceViewTheme.typography.caption)
        }
    }
}

@Composable
private fun Controls(state: RangeState, session: RangefinderSession) {
    val tracking = state.sessionRunning && state.tracking == TrackingStatus.GOOD
    when (state.phase) {
        RangePhase.LIVE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.held != null) {
                PrimaryAction(R.string.range_live) { session.release() }
            } else {
                PrimaryAction(R.string.range_hold, enabled = state.live != null) { session.hold() }
            }
            SecondaryAction(R.string.range_two_sight) { session.startTwoSight() }
        }
        RangePhase.AIM_SIGHT_1, RangePhase.TAKING_SIGHT_1 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryAction(R.string.range_cancel) { session.backToLive() }
            PrimaryAction(R.string.range_take_sight_1, enabled = tracking && state.phase == RangePhase.AIM_SIGHT_1) { session.takeSight() }
        }
        RangePhase.WALK, RangePhase.TAKING_SIGHT_2 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryAction(R.string.range_cancel) { session.backToLive() }
            PrimaryAction(R.string.range_take_sight_2, enabled = tracking && state.phase == RangePhase.WALK) { session.takeSight() }
        }
        RangePhase.RESULT -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Offered only when the core can really retake (no tracking loss or pause since sight 1; review MINOR 5).
            if (state.canRetake) SecondaryAction(R.string.range_retake) { session.retakeSight2() }
            SecondaryAction(R.string.range_start_over) { session.startTwoSight() }
            PrimaryAction(R.string.range_done) { session.backToLive() }
        }
    }
}

@Composable
private fun PrimaryAction(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
    ) { Text(stringResource(label)) }
}

@Composable
private fun SecondaryAction(label: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SpaceViewTheme.colors.arChrome, contentColor = SpaceViewTheme.colors.onArChrome),
    ) { Text(stringResource(label)) }
}

/** "Distance 41 metres plus or minus 1 metre. Method: Ground." for TalkBack. */
@Composable
private fun spokenReading(r: RangeReading, imperial: Boolean): String =
    stringResource(R.string.range_result_a11y, spokenText(RangeUi.text(r, imperial).text), stringResource(RangeUi.methodRes(r.method)))

@Composable
private fun spokenOutcome(o: RangeOutcome, imperial: Boolean): String = when (o) {
    is RangeOutcome.Ok -> spokenReading(o.reading, imperial)
    RangeOutcome.OutOfRange -> stringResource(R.string.range_out_of_range_a11y)
    is RangeOutcome.Rejected -> stringResource(RangeUi.rejectionRes(o.reason))
}
