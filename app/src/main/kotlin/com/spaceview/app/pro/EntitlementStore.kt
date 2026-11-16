package com.spaceview.app.pro

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Persistence for [CachedEntitlement]. */
interface EntitlementStore {
    /** The cached record, or null if none (or unreadable: a corrupt file reads as "no Pro on record"). */
    suspend fun read(): CachedEntitlement?

    /** Replaces the record; null clears it. */
    suspend fun write(value: CachedEntitlement?)
}

/**
 * File name of the entitlement cache: `datastore/spaceview_entitlement.preferences_pb`. It is excluded
 * from cloud backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml), so a
 * restore can never grant Pro on another phone (ARCHITECTURE-v2 R4b).
 */
const val ENTITLEMENT_DATASTORE_NAME = "spaceview_entitlement"

private val Context.entitlementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ENTITLEMENT_DATASTORE_NAME,
    // A corrupt file must never crash start-up: it becomes empty (Free) and the next Play query restores Pro.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class DataStoreEntitlementStore(private val dataStore: DataStore<Preferences>) : EntitlementStore {

    override suspend fun read(): CachedEntitlement? =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
            .toCached()

    override suspend fun write(value: CachedEntitlement?) {
        dataStore.edit { prefs ->
            prefs.clear()
            if (value != null) {
                prefs[PRODUCT_ID] = value.productId
                prefs[TOKEN_HASH] = value.tokenHash
                prefs[CONFIRMED_AT] = value.confirmedAtMillis
                prefs[AUTO_RENEWING] = value.autoRenewing
                prefs[ACKNOWLEDGED] = value.acknowledged
                prefs[CATALOG_VERSION] = value.catalogVersion
                prefs[LAST_SEEN] = value.lastSeenMillis
            }
        }
    }

    private fun Preferences.toCached(): CachedEntitlement? {
        val productId = this[PRODUCT_ID] ?: return null
        val hash = this[TOKEN_HASH] ?: return null
        val confirmedAt = this[CONFIRMED_AT] ?: return null
        return CachedEntitlement(
            productId = productId,
            tokenHash = hash,
            confirmedAtMillis = confirmedAt,
            autoRenewing = this[AUTO_RENEWING] ?: false,
            acknowledged = this[ACKNOWLEDGED] ?: false,
            catalogVersion = this[CATALOG_VERSION] ?: 0,
            lastSeenMillis = this[LAST_SEEN] ?: confirmedAt,
        )
    }

    companion object {
        private val PRODUCT_ID = stringPreferencesKey("product_id")
        private val TOKEN_HASH = stringPreferencesKey("token_sha256")
        private val CONFIRMED_AT = longPreferencesKey("confirmed_at")
        private val AUTO_RENEWING = booleanPreferencesKey("auto_renewing")
        private val ACKNOWLEDGED = booleanPreferencesKey("acknowledged")
        private val CATALOG_VERSION = intPreferencesKey("catalog_version")
        private val LAST_SEEN = longPreferencesKey("last_seen")

        /** The app-wide store (one DataStore instance per file is required). */
        fun get(context: Context): DataStoreEntitlementStore =
            DataStoreEntitlementStore(context.applicationContext.entitlementDataStore)
    }
}
