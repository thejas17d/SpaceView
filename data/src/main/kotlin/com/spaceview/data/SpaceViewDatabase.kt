package com.spaceview.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.spaceview.data.history.MeasurementDao
import com.spaceview.data.history.MeasurementEntity

/**
 * SpaceView's Room database (app-private). Gallery tables join in M3 with a migration; the schema
 * JSON is exported to `data/schemas/` and every migration in [MIGRATIONS] is exercised by
 * `SpaceViewMigrationTest` (androidTest, MigrationTestHelper) before it ships.
 *
 * Adding a version: bump `version` and [LATEST_VERSION] together, add a `Migration(n, n + 1)` to
 * [MIGRATIONS], build once so Room exports `schemas/…/(n+1).json`, and extend the migration test.
 * M3 should also add the canonical-record columns of research §6 (device tier, ARCore version,
 * calibration factor and scope, raw lengths) in that migration.
 */
@Database(entities = [MeasurementEntity::class], version = 1, exportSchema = true)
abstract class SpaceViewDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val FILE_NAME = "spaceview.db"

        /** Must equal the `version` in @Database above. */
        const val LATEST_VERSION = 1

        /** Every schema migration, oldest first. Never destructive: History is user data. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Builds the database. Opening is lazy: no I/O happens until the first query. */
        fun create(context: Context): SpaceViewDatabase =
            Room.databaseBuilder(context.applicationContext, SpaceViewDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
