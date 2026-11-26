package com.spaceview.app.measure

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.MeasureSession
import com.spaceview.ar.RectFinderState
import com.spaceview.ar.RectHint
import com.spaceview.ar.RectangleFinder

/** W9: the screen-scoped [RectangleFinder] for [session], closed with the composable; [FinderEnabled] switches it. */
@Composable
internal fun rememberRectangleFinder(session: MeasureSession): RectangleFinder {
    val finder = remember(session) { RectangleFinder(session, logTiming = com.spaceview.app.BuildConfig.DEBUG) }
    DisposableEffect(finder) { onDispose { finder.close() } }
    return finder
}

/** Switches [finder]'s search on and off (after the hands-free switch exists: gate V2 needs the finder first). */
@Composable
internal fun FinderEnabled(finder: RectangleFinder, enabled: Boolean) {
    LaunchedEffect(finder, enabled) { finder.enabled = enabled }
}

/** The found rectangle's outline over the camera: dashed-free signal line, thicker as it steadies; full when locked. */
@Composable
internal fun RectangleOutline(finder: RectangleFinder) {
    val state by finder.state.collectAsStateWithLifecycle()
    val outline = state.outline ?: return
    val signal = SpaceViewTheme.colors.signal
    val label = stringResource(R.string.rect_outline)
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = label }) {
        val path = Path().apply {
            moveTo(outline[0], outline[1])
            for (i in 1 until 4) lineTo(outline[2 * i], outline[2 * i + 1])
            close()
        }
        drawPath(path, Color(0xFF16181B), style = Stroke(5.dp.toPx()))
        drawPath(path, signal.copy(alpha = 0.45f + 0.55f * state.progress), style = Stroke((1.5f + 2f * state.progress) * density))
        for (i in 0 until 4) drawCircle(signal, 4.dp.toPx(), Offset(outline[2 * i], outline[2 * i + 1]))
    }
}

/** The coaching line of the finder, or null when it has nothing to say. */
@StringRes
internal fun RectFinderState.hintRes(): Int? = when (hint) {
    RectHint.SEARCHING -> R.string.rect_searching
    RectHint.NO_PLANE -> R.string.rect_no_plane
    RectHint.HOLD_STEADY -> R.string.measure_hold_steady
    RectHint.TOO_STEEP -> R.string.rect_too_steep
    RectHint.HOLDING -> R.string.rect_holding
}

/** Shared by Measure and Scan: the finder's hint only while it is on and no point exists. */
internal object RectangleFinderHints {
    @StringRes
    fun RectFinderState.hint(active: Boolean): Int? = if (active) hintRes() else null
}
