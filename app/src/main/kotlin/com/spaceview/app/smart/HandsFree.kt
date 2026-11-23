package com.spaceview.app.smart

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.ar.HandsFreeDropper
import com.spaceview.ar.MeasureEvent
import com.spaceview.ar.MeasureSession

/**
 * Smart Measure SM1a on one AR measuring screen (Measure, Scan outline, furniture guided measure): the
 * [HandsFreeDropper] listener for [com.spaceview.app.measure.ArCameraView], the ring progress for the reticle, and the
 * HUD switch. Free for everyone (decision D5: an accessibility feature).
 *
 * [enabled] (the HUD switch) starts from the Settings value and overrides it for this screen only, until the Settings
 * value changes again. The dropper runs only while [enabled] **and** the screen shows its reticle and controls
 * (see [rememberHandsFree]'s `suspended`) **and** the window has focus.
 */
@Stable
class HandsFreeUi internal constructor(
    internal val dropper: HandsFreeDropper,
    private val enabledState: MutableState<Boolean>,
    private val progressState: State<Float>,
    private val announcement: State<Int>,
    private val announcementShown: State<Boolean>,
) {
    val enabled: Boolean get() = enabledState.value

    /**
     * 0..1 progress of the hold, animated, for the reticle's outer ring. 0 when off. Changes every animation frame
     * during a hold: read it only in a draw lambda (see `Reticle`), never in composition.
     */
    val progress: Float get() = if (enabledState.value) progressState.value else 0f

    /** The 1-based number of the last point dropped hands-free, or 0 while nothing is to be announced. */
    internal val lastAutoPoint: Int get() = if (announcementShown.value) announcement.value else 0

    fun toggle() {
        enabledState.value = !enabledState.value
    }
}

/**
 * Creates the hands-free drop for [session] (one per session).
 *
 * @param settingEnabled the persisted Settings value.
 * @param suspended true whenever the screen does not show its reticle and controls (a picker, a result or review card,
 *   a freeze, a failed session): the dwell must never drop a point the user cannot see (SM1 review MAJOR 1). A
 *   window without focus (any dialog, including the consent form, over the AR screen) suspends it as well.
 * @param startOff start with the HUD switch off whatever the setting (calibration, where the aim must be exact);
 *   the user can still switch it on.
 */
@Composable
fun rememberHandsFree(
    session: MeasureSession,
    settingEnabled: Boolean,
    suspended: Boolean = false,
    startOff: Boolean = false,
): HandsFreeUi {
    val dropper = remember(session) { HandsFreeDropper(session) }
    val enabled = rememberSaveable(settingEnabled, startOff) { mutableStateOf(settingEnabled && !startOff) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val active = HandsFreeGate.active(enabled.value, suspended, focused)
    SideEffect { dropper.enabled = active }
    val raw by dropper.progress.collectAsStateWithLifecycle()
    // Steps of 1/5 from the AR thread. The ring sweeps linearly one step ahead, so it closes as the point drops,
    // and snaps back to 0 when the hold ends.
    val progress = animateFloatAsState(
        targetValue = if (raw == 0f) 0f else (raw + 1f / HandsFreeDropper.PROGRESS_STEPS).coerceAtMost(1f),
        animationSpec = if (raw == 0f) snap() else tween(RING_STEP_MS, easing = LinearEasing),
        label = "handsFreeRing",
    )
    val announced = remember(session) { mutableIntStateOf(0) }
    val shown = remember(session) { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(session) {
        session.events.collect { e ->
            if (e is MeasureEvent.PointAdded && e.auto) {
                if (HAPTIC_TICK_ENABLED) {
                    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.KEYBOARD_TAP
                    }
                    view.performHapticFeedback(effect)
                }
                // Blank the live region for one frame, then set the text: every drop is a change, so TalkBack also
                // speaks a point re-dropped after Undo with the same number (SM1 review MINOR 6).
                shown.value = false
                withFrameNanos { }
                announced.intValue = e.index + 1
                shown.value = true
            }
        }
    }
    return remember(dropper, enabled) { HandsFreeUi(dropper, enabled, progress, announced, shown) }
}

/** When the dropper may run: switched on, the reticle and controls on screen, and the window focused. */
internal object HandsFreeGate {
    fun active(enabled: Boolean, suspended: Boolean, windowFocused: Boolean): Boolean = enabled && !suspended && windowFocused
}

/**
 * TalkBack: "Placed point 2" after each hands-free drop. Always composed (empty until the first drop), so the first
 * drop is a *change* of an existing polite live region, not a node appearing.
 */
@Composable
internal fun HandsFreeAnnouncer(ui: HandsFreeUi) {
    val n = ui.lastAutoPoint
    val text = if (n > 0) stringResource(R.string.smart_point_placed, n) else ""
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text })
}

/**
 * The haptic tick on a hands-free drop is **off** (SM1 review MAJOR 3): ARCore's guidance and the UI research advise
 * against vibration during AR, and it is unmeasured. Flip it only for the TESTING §21 device experiment (tracking or
 * the reading must not jump with the tick on); the click sound, the ring and the announcement remain.
 */
internal const val HAPTIC_TICK_ENABLED = false

/** One ring step: 700 ms hold / 5 steps. */
private const val RING_STEP_MS = 140
