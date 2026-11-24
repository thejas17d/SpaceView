package com.spaceview.app.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Settings the Distance screen needs; [loaded] is false until DataStore answered.
 *
 * @property imperial show decimal feet (only when the user chose Imperial; Auto and Metric use metres).
 */
data class RangeUiSettings(
    val imperial: Boolean = false,
    val soundsEnabled: Boolean = true,
    val depthEnabled: Boolean = true,
    val loaded: Boolean = false,
)

/**
 * Settings side of the Distance screen. It holds no ARCore objects (the screen-scoped `RangefinderSession` does) and
 * saves nothing: a rangefinder reading is read off the screen (no History row in v1).
 */
class RangefinderViewModel(measureSettings: MeasureSettingsRepository, appSettings: SettingsRepository) : ViewModel() {
    val settings: StateFlow<RangeUiSettings> = combine(
        measureSettings.settings,
        appSettings.unitPreference,
        appSettings.soundsEnabled,
    ) { m, units, sounds ->
        RangeUiSettings(imperial = units == UnitPreference.IMPERIAL, soundsEnabled = sounds, depthEnabled = m.depthEnabled, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RangeUiSettings())
}
