package com.spaceview.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.spaceview.data.history.MeasurementDao
import com.spaceview.data.history.MeasurementEntity

/**
 * SpaceView's Room database (app-private). Gallery tables join in M3 with a migration; the schema
 * JSON is exported to `data/schemas/` so migrations can be tested.
 */
@Database(entities = [MeasurementEntity::class], version = 1, exportSchema = true)
abstract class SpaceViewDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val FILE_NAME = "spaceview.db"

        /** Builds the database. Opening is lazy: no I/O happens until the first query. */
        fun create(context: Context): SpaceViewDatabase =
            Room.databaseBuilder(context.applicationContext, SpaceViewDatabase::class.java, FILE_NAME).build()
    }
}
