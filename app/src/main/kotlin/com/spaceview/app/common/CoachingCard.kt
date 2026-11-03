package com.spaceview.app.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Coaching / hint card for AR screens ("Move your phone slowly side to side."): solid graphite
 * pill (never a blur) with a signal-yellow accent bar. TalkBack announces changes politely.
 *
 * Copy rules (ui-ux.md §2.4): friendly, short, no jargon ("plane", "tracking", "ARCore").
 *
 * @param message the instruction.
 * @param title optional short heading ("Low light").
 * @param actionLabel / onAction optional one-tap fix ("Calibrate", "Start over").
 * @param onDismiss shows a close button when non-null.
 */
@Composable
fun CoachingCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = SpaceViewTheme.colors
    Surface(
        modifier = modifier
            .widthIn(max = 480.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = colors.arChrome,
        contentColor = colors.onArChrome,
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(colors.signal),
            )
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .padding(start = 14.dp, end = if (onDismiss == null) 16.dp else 0.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.signal),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
                }
            }
            if (onDismiss != null) {
                val close = stringResource(R.string.common_dismiss)
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = close }) {
                    Canvas(Modifier.size(12.dp)) {
                        val s = 1.5.dp.toPx()
                        drawLine(colors.onArChrome, Offset.Zero, Offset(size.width, size.height), s)
                        drawLine(colors.onArChrome, Offset(size.width, 0f), Offset(0f, size.height), s)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF8A8F95)
@Composable
private fun CoachingCardPreview() {
    SpaceViewTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachingCard("Move your phone slowly side to side.")
            CoachingCard(
                title = "Low light",
                message = "Turn on a light or move closer to a window.",
                actionLabel = "Calibrate",
                onAction = {},
                onDismiss = {},
            )
        }
    }
}
