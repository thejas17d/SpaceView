package com.spaceview.app.measure

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withClip
import com.spaceview.app.R
import com.spaceview.app.common.CoachingCard
import com.spaceview.app.common.DimensionLabel
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.FrozenAdjustment
import com.spaceview.ar.FrozenFrame
import com.spaceview.ar.ScreenPoint
import com.spaceview.engine.UnitPreference

/**
 * Freeze-frame fine adjustment (UI research §2.5): the camera image is held still, the user can
 * lower the phone and drag anywhere to move the last point; the point moves at half the finger
 * speed for precision and a 2× loupe above it shows exactly where it is, so the finger never
 * covers the target. The new position is the ray through that pixel intersected with the point's
 * own surface plane, computed with the camera pose of the frozen frame.
 */
@Composable
internal fun FreezeOverlay(
    frame: FrozenFrame,
    units: UnitPreference,
    /** False while measuring a known-length calibration span: its AR value must not be shown (M2 gate R2). */
    showReading: Boolean,
    onAdjust: (Float, Float) -> FrozenAdjustment?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current.density
    val painter = remember(density) { DimensionPainter(density) }
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    var point by remember(frame) { mutableStateOf(frame.points.lastOrNull()?.let { frame.projection.project(it) }) }
    var adjustment by remember(frame) { mutableStateOf<FrozenAdjustment?>(null) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(frame) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val p = point ?: return@detectDragGestures
                        val target = ScreenPoint(p.x + drag.x * DRAG_GAIN, p.y + drag.y * DRAG_GAIN)
                        onAdjust(target.x, target.y)?.let {
                            adjustment = it
                            point = it.screen
                        }
                    }
                },
        ) {
            val pts = frame.points.toMutableList()
            adjustment?.let { if (pts.isNotEmpty()) pts[pts.lastIndex] = it.position }
            drawIntoCanvas { c ->
                val canvas = c.nativeCanvas
                canvas.drawBitmap(frame.bitmap, frame.imageToView, bitmapPaint)
                painter.draw(canvas, frame.projection, frame.mode, pts, null)
                val p = point ?: return@drawIntoCanvas
                // Loupe: 2× around the point, drawn above it (below it near the top edge).
                val r = LOUPE_RADIUS_DP * density
                val offset = (LOUPE_RADIUS_DP + LOUPE_GAP_DP) * density
                val cx = p.x.coerceIn(r, size.width - r)
                val cy = if (p.y - offset - r > 0) p.y - offset else p.y + offset
                val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                canvas.withClip(clip) {
                    drawColor(android.graphics.Color.BLACK)
                    val zoom = Matrix().apply {
                        postScale(LOUPE_ZOOM, LOUPE_ZOOM, p.x, p.y)
                        postTranslate(cx - p.x, cy - p.y)
                    }
                    concat(zoom)
                    drawBitmap(frame.bitmap, frame.imageToView, bitmapPaint)
                    painter.draw(this, frame.projection, frame.mode, pts, null)
                }
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2f * density; color = DimensionPainter.SIGNAL
                }
                canvas.drawCircle(cx, cy, r, ring)
                // Crosshair at the loupe centre = the point.
                val l = 8f * density
                canvas.drawLine(cx - l, cy, cx + l, cy, ring)
                canvas.drawLine(cx, cy - l, cx, cy + l, ring)
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoachingCard(message = stringResource(R.string.measure_freeze_hint))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            adjustment?.reading?.takeIf { showReading }?.let { reading ->
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    ChromePill(stringResource(R.string.measure_freeze_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDone,
                    modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
                ) { Text(stringResource(R.string.measure_freeze_done)) }
            }
        }
    }
}

/** The point moves at this fraction of the finger's movement (fine control). */
private const val DRAG_GAIN = 0.5f
private const val LOUPE_ZOOM = 2f
private const val LOUPE_RADIUS_DP = 64f
private const val LOUPE_GAP_DP = 24f
