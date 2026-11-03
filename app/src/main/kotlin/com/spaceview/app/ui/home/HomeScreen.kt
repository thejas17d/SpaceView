package com.spaceview.app.ui.home

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * M0 placeholder home screen: shows the "precision instrument" theme (readout with tolerance and
 * accuracy badge, hairline cards, signal-yellow primary action). Replaced by the real home and
 * navigation in M2.
 */
@Composable
fun HomeScreen(
    onStartMeasuring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            RulerTicks(
                color = SpaceViewTheme.colors.hairline,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
            )
            SampleReadout()
            ModeRow(R.string.home_mode_measure, R.string.home_mode_measure_detail)
            ModeRow(R.string.home_mode_scan, R.string.home_mode_scan_detail)
            ModeRow(R.string.home_mode_gallery, R.string.home_mode_gallery_detail)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStartMeasuring,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpaceViewTheme.colors.signal,
                    contentColor = SpaceViewTheme.colors.onSignal,
                ),
            ) {
                Text(stringResource(R.string.home_start_measuring), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Steel-rule tick marks: every 5th tick is longer, every 10th is full height. */
@Composable
private fun RulerTicks(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val step = 4.dp.toPx()
        val stroke = 1.dp.toPx()
        var index = 0
        var x = stroke / 2
        while (x <= size.width) {
            val tickHeight = when {
                index % 10 == 0 -> size.height
                index % 5 == 0 -> size.height * 0.6f
                else -> size.height * 0.35f
            }
            drawLine(color, Offset(x, 0f), Offset(x, tickHeight), strokeWidth = stroke)
            x += step
            index++
        }
    }
}

@Composable
private fun SampleReadout() {
    InstrumentCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.home_sample_label).uppercase(),
                style = SpaceViewTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(R.string.home_sample_value),
                    style = SpaceViewTheme.typography.readout,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_sample_tolerance),
                    style = SpaceViewTheme.typography.tolerance,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            AccuracyBadge(label = stringResource(R.string.home_accuracy_good), color = SpaceViewTheme.colors.accuracyGood)
        }
    }
}

@Composable
private fun AccuracyBadge(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun ModeRow(@StringRes title: Int, @StringRes detail: Int) {
    InstrumentCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_coming_soon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Flat card with a 1 dp hairline border: the base surface of every non-AR screen. */
@Composable
private fun InstrumentCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, SpaceViewTheme.colors.hairline),
        content = content,
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenPreview() {
    SpaceViewTheme {
        HomeScreen(onStartMeasuring = {})
    }
}
