package com.spaceview.app.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import com.spaceview.app.ads.BannerAd
import com.spaceview.app.ads.AdsManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spaceview.app.R
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.measure.MeasureFormatter
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.history.MeasurementKind
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.data.history.SavedMeasurement
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One formatted history row (formatting is done in the ViewModel, off the composition path). */
data class HistoryRow(
    val id: Long,
    val kind: MeasurementKind,
    val label: String?,
    val valueText: String,
    val areaText: String?,
    val item: SavedMeasurement,
)

data class HistoryUiState(val rows: List<HistoryRow> = emptyList(), val loaded: Boolean = false)

class HistoryViewModel(
    private val history: MeasurementRepository,
    appSettings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<HistoryUiState> = combine(history.observeHistory(), appSettings.unitPreference) { items, units ->
        HistoryUiState(items.map { toRow(it, units) }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun delete(id: Long) {
        viewModelScope.launch { history.delete(id) }
    }

    companion object {
        /** Re-formats the stored SI values with the current unit setting (pure; unit-tested). */
        fun toRow(m: SavedMeasurement, units: UnitPreference): HistoryRow {
            val primary = MeasureFormatter.length(m.primary, m.context, units)
            val secondary = m.secondary
            val value = if (m.kind == MeasurementKind.RECTANGLE && secondary != null) {
                val h = MeasureFormatter.length(secondary, m.context, units)
                "${primary.primary.text} × ${h.primary.text}"
            } else {
                primary.text
            }
            val area = m.area?.let { MeasureFormatter.area(it, m.context, units) }
            return HistoryRow(m.id, m.kind, m.label, value, area, m)
        }
    }
}

/** History list (S13): newest first, each row with its ± and accuracy badge. */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            if (state.loaded && state.rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.id }) { row -> HistoryItem(row, onDelete = { viewModel.delete(row.id) }) }
                    item(key = "banner") {
                        BannerAd(adsManager = AdsManager.get(LocalContext.current), modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(row: HistoryRow, onDelete: () -> Unit) {
    val deleteLabel = stringResource(R.string.history_delete)
    val kind = stringResource(
        when (row.kind) {
            MeasurementKind.DISTANCE -> R.string.history_kind_distance
            MeasurementKind.HEIGHT -> R.string.history_kind_height
            MeasurementKind.RECTANGLE -> R.string.history_kind_rectangle
        },
    )
    InstrumentCard(
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
        },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (row.label ?: kind) + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.item.timestampMillis)),
                    style = SpaceViewTheme.typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(row.valueText, style = MaterialTheme.typography.titleMedium)
                row.areaText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccuracyBadge(row.item.quality)
                    if (row.item.calibrated) {
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.history_calibrated), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(deleteLabel) }
        }
    }
}
