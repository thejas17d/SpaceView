package com.spaceview.app.ui.home

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.MeasureMode

/**
 * Measure home (S2): the measuring modes, calibration status and History. AR starts only when a
 * mode is chosen (saves battery, and the camera permission is asked in that context).
 */
@Composable
fun HomeScreen(
    onMeasure: (MeasureMode) -> Unit,
    onCalibrate: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onScan: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Header(Modifier.weight(1f))
                TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.home_settings))
                }
            }
            RulerTicks(color = SpaceViewTheme.colors.hairline, modifier = Modifier.fillMaxWidth().height(12.dp))
            Text(
                stringResource(R.string.home_section_measure).uppercase(),
                style = SpaceViewTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            ModeRow(R.string.home_distance, R.string.home_distance_detail) { onMeasure(MeasureMode.DISTANCE) }
            ModeRow(R.string.home_height, R.string.home_height_detail) { onMeasure(MeasureMode.HEIGHT) }
            ModeRow(R.string.home_rectangle, R.string.home_rectangle_detail) { onMeasure(MeasureMode.RECTANGLE) }
            ModeRow(R.string.home_scan_object, R.string.home_scan_object_detail, onClick = onScan)
            ModeRow(R.string.home_gallery, R.string.home_gallery_detail, onClick = onGallery)
            Spacer(Modifier.height(4.dp))
            CalibrationRow(onCalibrate)
            ModeRow(R.string.home_history, R.string.home_history_detail, onClick = onHistory)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onMeasure(MeasureMode.DISTANCE) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
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
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun ModeRow(@StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    RowCard(stringResource(title), stringResource(detail), onClick)
}

/**
 * Calibrate entry. Never shows a saved calibration as active: a calibration applies only inside the
 * AR session that made it, so from Home none is ever in effect (M2 gate R3). Settings shows the
 * last calibration date as history.
 */
@Composable
private fun CalibrationRow(onClick: () -> Unit) {
    RowCard(stringResource(R.string.home_calibrate), stringResource(R.string.home_calibrate_detail), onClick)
}

@Composable
private fun RowCard(title: String, detail: String, onClick: () -> Unit) {
    InstrumentCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenPreview() {
    SpaceViewTheme {
        HomeScreen(onMeasure = {}, onCalibrate = {}, onHistory = {}, onSettings = {}, onScan = {}, onGallery = {})
    }
}
