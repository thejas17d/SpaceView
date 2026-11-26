package com.spaceview.app.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Adjust corners" (PLAN v3.1 W8): the frozen capture photo, upright, with the face's four corners. Drag a corner, or a
 * side (both of its corners move); a magnifier loupe under the finger shows the exact spot; on release the corner
 * snaps to a clear image corner within a few pixels unless "Snap to corners" is off. Done hands the corners (sensor
 * pixels) to the view model, which re-measures width and height from geometry.
 *
 * @param onSnap snaps corner k of the sensor-pixel corners ([ScanViewModel.snapCorner]); null = no snap.
 * @param onDone the sensor-pixel corners and, per corner, the snap's σ (NaN = placed by hand).
 */
@Composable
internal fun CornerEditor(
    editor: EditorPhoto,
    refused: Boolean,
    onSnap: (FloatArray, Int) -> com.spaceview.app.scan.imaging.SnappedCorner?,
    onDone: (FloatArray, FloatArray) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    /** Width and height (m) the corners would measure (sensor pixels in), for TalkBack; null = not measurable. */
    onMeasure: (FloatArray) -> Pair<Double, Double>? = { null },
) {
    // Corners in display-image pixels (the upright photo); local until Done.
    var corners by remember(editor) { mutableStateOf(editor.corners.copyOf()) }
    // Per corner: the σ of a sub-pixel snap; NaN once the corner was moved by hand (or never snapped).
    var snapSigma by remember(editor) { mutableStateOf(FloatArray(4) { Float.NaN }) }
    var snapOn by rememberSaveable { mutableStateOf(true) }
    var active by remember { mutableIntStateOf(NONE) }
    // Gate v15: TalkBack edits one selected corner with custom actions (select next, move by a small step).
    var a11yCorner by remember(editor) { mutableIntStateOf(0) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val image = editor.image
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.scan_corners_editor_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.scan_corners_editor_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                val colors = SpaceViewTheme.colors
                val description = stringResource(R.string.scan_corners_editor_a11y)
                val cornerNames = listOf(
                    stringResource(R.string.scan_corner_tl), stringResource(R.string.scan_corner_tr),
                    stringResource(R.string.scan_corner_br), stringResource(R.string.scan_corner_bl),
                )
                val measured = onMeasure(editor.turns.cornersToSensor(corners))
                val status = if (measured != null) {
                    stringResource(R.string.scan_corners_a11y_status, cornerNames[a11yCorner], cmText(measured.first), cmText(measured.second))
                } else {
                    stringResource(R.string.scan_corners_a11y_status_unmeasurable, cornerNames[a11yCorner])
                }
                fun nudge(dx: Float, dy: Float): Boolean {
                    corners = moved(corners, a11yCorner, dx, dy, image.width.toFloat(), image.height.toFloat())
                    snapSigma = snapSigma.copyOf().also { it[a11yCorner] = Float.NaN }
                    return true
                }
                val actions = listOf(
                    CustomAccessibilityAction(stringResource(R.string.scan_corners_a11y_next)) { a11yCorner = (a11yCorner + 1) % 4; true },
                    CustomAccessibilityAction(stringResource(R.string.scan_corners_a11y_up)) { nudge(0f, -A11Y_STEP_PX) },
                    CustomAccessibilityAction(stringResource(R.string.scan_corners_a11y_down)) { nudge(0f, A11Y_STEP_PX) },
                    CustomAccessibilityAction(stringResource(R.string.scan_corners_a11y_left)) { nudge(-A11Y_STEP_PX, 0f) },
                    CustomAccessibilityAction(stringResource(R.string.scan_corners_a11y_right)) { nudge(A11Y_STEP_PX, 0f) },
                )
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics {
                            contentDescription = description
                            stateDescription = status
                            customActions = actions
                        }
                        .pointerInput(editor) {
                            fun fit(): Triple<Float, Float, Float> {
                                val s = min(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
                                return Triple(s, (size.width - image.width * s) / 2, (size.height - image.height * s) / 2)
                            }
                            detectDragGestures(
                                onDragStart = { p ->
                                    val (s, ox, oy) = fit()
                                    active = pick(corners, p, s, ox, oy, cornerSlop = 40.dp.toPx(), sideSlop = 28.dp.toPx())
                                    touch = p
                                },
                                onDragEnd = {
                                    if (snapOn && active in 0..3) {
                                        val k = active
                                        onSnap(editor.turns.cornersToSensor(corners), k)?.let { snapped ->
                                            val (x, y) = editor.turns.toDisplay(snapped.u, snapped.v)
                                            corners = corners.copyOf().also { it[2 * k] = x; it[2 * k + 1] = y }
                                            snapSigma = snapSigma.copyOf().also { it[k] = snapped.sigmaPx.toFloat() }
                                        }
                                    }
                                    active = NONE; touch = null
                                },
                                onDragCancel = { active = NONE; touch = null },
                            ) { change, drag ->
                                change.consume()
                                val (s, _, _) = fit()
                                touch = change.position
                                if (active != NONE) {
                                    corners = moved(corners, active, drag.x / s, drag.y / s, image.width.toFloat(), image.height.toFloat())
                                    // Moved by hand: the placement error of a finger applies again to the moved corners.
                                    val ks = if (active in 0..3) listOf(active) else listOf(active - 4, (active - 3) % 4)
                                    snapSigma = snapSigma.copyOf().also { for (k in ks) it[k] = Float.NaN }
                                }
                            }
                        },
                ) {
                    val s = min(size.width / image.width, size.height / image.height)
                    val ox = (size.width - image.width * s) / 2
                    val oy = (size.height - image.height * s) / 2
                    drawImage(
                        image,
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize((image.width * s).roundToInt(), (image.height * s).roundToInt()),
                    )
                    fun pt(k: Int) = Offset(ox + corners[2 * k] * s, oy + corners[2 * k + 1] * s)
                    val quad = Path().apply {
                        moveTo(pt(0).x, pt(0).y)
                        for (k in 1..3) lineTo(pt(k).x, pt(k).y)
                        close()
                    }
                    drawPath(quad, colors.signal, style = Stroke(width = 2.dp.toPx()))
                    for (k in 0..3) {
                        drawCircle(Color.White, radius = 9.dp.toPx(), center = pt(k))
                        drawCircle(colors.signal, radius = 6.dp.toPx(), center = pt(k))
                    }
                    // The loupe: the spot under the finger, magnified, in the top corner away from the finger.
                    val t = touch
                    if (t != null && active != NONE) {
                        val focus = if (active in 0..3) pt(active) else t
                        val r = 64.dp.toPx()
                        val centre = Offset(if (focus.x < size.width / 2) size.width - r - 8.dp.toPx() else r + 8.dp.toPx(), r + 8.dp.toPx())
                        val zoom = s * LOUPE_ZOOM
                        val ix = (focus.x - ox) / s
                        val iy = (focus.y - oy) / s
                        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, r)) }
                        clipPath(circle) {
                            drawRect(Color.Black, topLeft = centre - Offset(r, r), size = Size(2 * r, 2 * r))
                            drawImage(
                                image,
                                dstOffset = IntOffset((centre.x - ix * zoom).roundToInt(), (centre.y - iy * zoom).roundToInt()),
                                dstSize = IntSize((image.width * zoom).roundToInt(), (image.height * zoom).roundToInt()),
                            )
                            drawLine(colors.signal, centre - Offset(r, 0f), centre + Offset(r, 0f), strokeWidth = 1.dp.toPx())
                            drawLine(colors.signal, centre - Offset(0f, r), centre + Offset(0f, r), strokeWidth = 1.dp.toPx())
                        }
                        drawCircle(Color.White, radius = r, center = centre, style = Stroke(width = 2.dp.toPx()))
                    }
                }
                // Spoken after every TalkBack action: the selected corner and the size it now gives.
                Text(
                    status,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (refused) {
                    Text(
                        stringResource(R.string.scan_corners_refused),
                        color = SpaceViewTheme.colors.accuracyFair,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = snapOn, onCheckedChange = { snapOn = it })
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.scan_corners_snap), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gallery_cancel), color = Color.White) }
                    TextButton(
                        onClick = { corners = editor.autoCorners.copyOf(); snapSigma = FloatArray(4) { Float.NaN }; onReset() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.scan_corners_reset), color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onDone(editor.turns.cornersToSensor(corners), snapSigma.copyOf()) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.signal, contentColor = colors.onSignal),
                    ) { Text(stringResource(R.string.scan_done)) }
                }
            }
        }
    }
}

private const val NONE = -1

/** TalkBack nudge step, in pixels of the upright photo. */
private const val A11Y_STEP_PX = 2f

/** "60.6" (one decimal, centimetres) for the TalkBack status. */
internal fun cmText(m: Double): String = String.format(java.util.Locale.ROOT, "%.1f", m * 100)
private const val LOUPE_ZOOM = CornerAdjust.LOUPE_ZOOM.toFloat()

/**
 * What a touch at [p] grabs: corner k (0..3) within [cornerSlop], else side k (4 + k: corners k and k + 1) within
 * [sideSlop] of its segment, else [NONE]. Display-image corners mapped to the canvas by scale [s] and offset.
 */
internal fun pick(c: FloatArray, p: Offset, s: Float, ox: Float, oy: Float, cornerSlop: Float, sideSlop: Float): Int {
    fun x(k: Int) = ox + c[2 * (k % 4)] * s
    fun y(k: Int) = oy + c[2 * (k % 4) + 1] * s
    val nearest = (0..3).minBy { hypot(x(it) - p.x, y(it) - p.y) }
    if (hypot(x(nearest) - p.x, y(nearest) - p.y) <= cornerSlop) return nearest
    var best = NONE; var bestD = sideSlop
    for (k in 0..3) {
        val ax = x(k); val ay = y(k); val bx = x(k + 1); val by = y(k + 1)
        val len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        if (len2 <= 0f) continue
        val t = (((p.x - ax) * (bx - ax) + (p.y - ay) * (by - ay)) / len2).coerceIn(0f, 1f)
        val d = hypot(ax + t * (bx - ax) - p.x, ay + t * (by - ay) - p.y)
        if (d < bestD) { bestD = d; best = 4 + k }
    }
    return best
}

/** [c] with grab [target] (a corner, or a side's two corners) moved by (dx, dy) image pixels, kept inside the image. */
internal fun moved(c: FloatArray, target: Int, dx: Float, dy: Float, w: Float, h: Float): FloatArray {
    val out = c.copyOf()
    val ks = if (target in 0..3) listOf(target) else listOf(target - 4, (target - 3) % 4)
    for (k in ks) {
        out[2 * k] = (out[2 * k] + dx).coerceIn(0f, w)
        out[2 * k + 1] = (out[2 * k + 1] + dy).coerceIn(0f, h)
    }
    return out
}
