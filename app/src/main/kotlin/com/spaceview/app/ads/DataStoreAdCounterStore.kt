package com.spaceview.app.ads

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Ad counters live in their own small file so clearing them never touches user settings. */
private val Context.adCountersDataStore: DataStore<Preferences> by preferencesDataStore(name = "spaceview_ads")

/** [AdCounterStore] backed by Preferences DataStore. A corrupt or unreadable file reads as zeros. */
class DataStoreAdCounterStore(private val dataStore: DataStore<Preferences>) : AdCounterStore {

    override suspend fun read(): AdCounters =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
            .toCounters()

    override suspend fun update(transform: (AdCounters) -> AdCounters): AdCounters {
        var result = AdCounters()
        dataStore.edit { prefs ->
            result = transform(prefs.toCounters())
            prefs.write(result)
        }
        return result
    }

    private fun Preferences.toCounters() = AdCounters(
        completedScans = this[COMPLETED_SCANS] ?: 0,
        scansSinceInterstitial = this[SCANS_SINCE] ?: 0,
        lastInterstitialAtMillis = this[LAST_AT] ?: 0L,
        interstitialDay = this[DAY] ?: Long.MIN_VALUE,
        interstitialsOnDay = this[ON_DAY] ?: 0,
    )

    private fun MutablePreferences.write(c: AdCounters) {
        this[COMPLETED_SCANS] = c.completedScans
        this[SCANS_SINCE] = c.scansSinceInterstitial
        this[LAST_AT] = c.lastInterstitialAtMillis
        this[DAY] = c.interstitialDay
        this[ON_DAY] = c.interstitialsOnDay
    }

    companion object {
        private val COMPLETED_SCANS = intPreferencesKey("completed_scans")
        private val SCANS_SINCE = intPreferencesKey("scans_since_interstitial")
        private val LAST_AT = longPreferencesKey("last_interstitial_at")
        private val DAY = longPreferencesKey("interstitial_day")
        private val ON_DAY = intPreferencesKey("interstitials_on_day")

        /** The app-wide store (one DataStore instance per file is required). */
        fun get(context: Context): DataStoreAdCounterStore =
            DataStoreAdCounterStore(context.applicationContext.adCountersDataStore)
    }
}
