package com.spaceview.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests on a device / emulator (`:data:connectedDebugAndroidTest`). The schemas come from
 * `data/schemas` (exported by Room, added to the androidTest assets by the Room Gradle plugin).
 *
 * When M3 adds version 2: keep [v1RowSurvivesAllMigrations] and add a test that creates version 1,
 * calls `helper.runMigrationsAndValidate(DB, 2, true, *SpaceViewDatabase.MIGRATIONS)` and checks the
 * new columns' defaults.
 */
@RunWith(AndroidJUnit4::class)
class SpaceViewMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpaceViewDatabase::class.java)

    @Test
    fun v1RowSurvivesAllMigrations() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO measurements (kind, value_m, sigma_m, quality, unit_context, calibrated, timestamp) " +
                    "VALUES ('DISTANCE', 0.6, 0.005, 'FAIR', 'OBJECT', 0, 1000)",
            )
        }
        // Migrate to the latest version and let Room validate the schema against the entities.
        helper.runMigrationsAndValidate(DB, SpaceViewDatabase.LATEST_VERSION, true, *SpaceViewDatabase.MIGRATIONS).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, SpaceViewDatabase::class.java, DB)
            .addMigrations(*SpaceViewDatabase.MIGRATIONS)
            .build()
        try {
            val row = runBlocking { room.measurementDao().get(1) }!!
            assertEquals(0.6, row.valueMetres, 0.0)
            assertEquals("DISTANCE", row.kind)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
