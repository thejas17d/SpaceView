package com.spaceview.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
     * Settings → Delete my data: units and sounds go back to their defaults. "Onboarding finished" is kept: it is
     * not personal data, and the user should not be sent through the intro again. Throws [IOException] on a write
     * failure.
     */
    suspend fun resetPreferences() {
        dataStore.edit { p ->
            val onboarded = p[ONBOARDING_DONE]
            p.clear()
            if (onboarded != null) p[ONBOARDING_DONE] = onboarded
        }
    }

    companion object {
        private val UNITS = stringPreferencesKey("unit_preference")
        private val SOUNDS = booleanPreferencesKey("sounds_enabled")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")

        @Volatile private var instance: SettingsRepository? = null

        /** App-wide instance (one DataStore per file is required). */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.settingsDataStore).also { instance = it }
            }
    }
}
