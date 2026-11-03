package com.spaceview.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** History queries. All suspend / Flow: Room runs them off the main thread. */
@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(entity: MeasurementEntity): Long

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: Long): MeasurementEntity?

    @Query("UPDATE measurements SET label = :label WHERE id = :id")
    suspend fun setLabel(id: Long, label: String?)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    /** Keeps the newest [keep] rows (History is "the last N quick measurements"). */
    @Query("DELETE FROM measurements WHERE id NOT IN (SELECT id FROM measurements ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
