package com.spaceview.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "spaceview_settings")

/**
 * User settings and first-run state (Preferences DataStore, file `spaceview_settings`).
 * Get the app-wide instance with [SettingsRepository.get]; all flows are cold and main-safe.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private val prefs: Flow<Preferences> =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** Unit system: AUTO (context-aware, default), METRIC or IMPERIAL. Feed into `Units.defaultUnits`. */
    val unitPreference: Flow<UnitPreference> = prefs.map { p ->
        p[UNITS]?.let { name -> UnitPreference.entries.firstOrNull { it.name == name } } ?: UnitPreference.AUTO
    }.distinctUntilChanged()

    /** Soft click sounds in AR (Android uses sound, not haptics, during AR). Default on. */
    val soundsEnabled: Flow<Boolean> = prefs.map { it[SOUNDS] ?: true }.distinctUntilChanged()

    /** True once the user has finished (or skipped) onboarding. */
    val onboardingCompleted: Flow<Boolean> = prefs.map { it[ONBOARDING_DONE] ?: false }.distinctUntilChanged()

    suspend fun setUnitPreference(value: UnitPreference) {
        dataStore.edit { it[UNITS] = value.name }
    }

    suspend fun setSoundsEnabled(value: Boolean) {
        dataStore.edit { it[SOUNDS] = value }
    }

    suspend fun setOnboardingCompleted(value: Boolean = true) {
        dataStore.edit { it[ONBOARDING_DONE] = value }
    }

    /**
     * On-device 3D scans ever saved on this install (the Free rule is "5 in total", user decision 2026-11-23):
     * deleting a scan does not give one back. Read failures surface as 0 via [prefs]; the gate also takes the
     * saved-row count, so the quota is never below what the gallery holds.
     */
    suspend fun deviceScansUsed(): Int = prefs.first()[DEVICE_SCANS_USED] ?: 0

    /** Call once after every successful `GalleryRepository.saveMesh`. Throws [IOException] on a write failure. */
    suspend fun recordDeviceScanSaved() {
        dataStore.edit { it[DEVICE_SCANS_USED] = (it[DEVICE_SCANS_USED] ?: 0) + 1 }
    }

    /**
     * Settings → Delete my data: units and sounds go back to their defaults. "Onboarding finished" and the 3D-scan
     * quota count are kept: neither is personal data, the user should not be sent through the intro again, and
     * deleting data must not hand back free scans. Throws [IOException] on a write failure.
     */
    suspend fun resetPreferences() {
        dataStore.edit { p ->
            val onboarded = p[ONBOARDING_DONE]
            val scansUsed = p[DEVICE_SCANS_USED]
            p.clear()
            if (onboarded != null) p[ONBOARDING_DONE] = onboarded
            if (scansUsed != null) p[DEVICE_SCANS_USED] = scansUsed
        }
    }

    companion object {
        private val UNITS = stringPreferencesKey("unit_preference")
        private val SOUNDS = booleanPreferencesKey("sounds_enabled")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        private val DEVICE_SCANS_USED = intPreferencesKey("device_scans_used")

        @Volatile private var instance: SettingsRepository? = null

        /** App-wide instance (one DataStore per file is required). */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.settingsDataStore).also { instance = it }
            }
    }
}
