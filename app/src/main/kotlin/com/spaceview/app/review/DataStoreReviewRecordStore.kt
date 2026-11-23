package com.spaceview.app.review

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.reviewDataStore: DataStore<Preferences> by preferencesDataStore(name = "spaceview_review")

/** [ReviewRecordStore] on its own Preferences DataStore file (`spaceview_review`). */
class DataStoreReviewRecordStore(private val dataStore: DataStore<Preferences>) : ReviewRecordStore {
    override suspend fun read(): ReviewRecord = dataStore.data.first().toRecord()

    override suspend fun update(transform: (ReviewRecord) -> ReviewRecord): ReviewRecord {
        var result = ReviewRecord()
        dataStore.edit { p ->
            result = transform(p.toRecord())
            p[SAVES] = result.saves
            p[LAST_ASKED] = result.lastAskedAtMillis
        }
        return result
    }

    private fun Preferences.toRecord() = ReviewRecord(
        saves = (this[SAVES] ?: 0).coerceAtLeast(0),
        lastAskedAtMillis = this[LAST_ASKED] ?: 0L,
    )

    companion object {
        private val SAVES = intPreferencesKey("saves")
        private val LAST_ASKED = longPreferencesKey("last_asked_at")

        @Volatile private var instance: DataStoreReviewRecordStore? = null

        fun get(context: Context): DataStoreReviewRecordStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreReviewRecordStore(context.applicationContext.reviewDataStore).also { instance = it }
            }
    }
}
