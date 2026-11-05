package com.spaceview.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spaceview.data.gallery.GalleryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests on a device / emulator (`:data:connectedDebugAndroidTest`). The schemas come from
 * `data/schemas` (exported by Room, added to the androidTest assets by the Room Gradle plugin).
 */
@RunWith(AndroidJUnit4::class)
class SpaceViewMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpaceViewDatabase::class.java)

    private fun createV1WithOneRow() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO measurements (kind, value_m, sigma_m, quality, unit_context, calibrated, timestamp) " +
                    "VALUES ('DISTANCE', 0.6, 0.005, 'FAIR', 'OBJECT', 0, 1000)",
            )
        }
    }

    private fun openRoom(): SpaceViewDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.databaseBuilder(context, SpaceViewDatabase::class.java, DB)
            .addMigrations(*SpaceViewDatabase.MIGRATIONS)
            .build()
    }

    @Test
    fun v1RowSurvivesAllMigrations() {
        createV1WithOneRow()
        // Migrate to the latest version and let Room validate the schema against the entities.
        helper.runMigrationsAndValidate(DB, SpaceViewDatabase.LATEST_VERSION, true, *SpaceViewDatabase.MIGRATIONS).close()

        val room = openRoom()
        try {
            val row = runBlocking { room.measurementDao().get(1) }!!
            assertEquals(0.6, row.valueMetres, 0.0)
            assertEquals("DISTANCE", row.kind)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate1To2AddsGalleryTableAndCanonicalColumnsWithDefaults() {
        createV1WithOneRow()
        helper.runMigrationsAndValidate(DB, 2, true, SpaceViewDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT device_tier, arcore_version, calibration_factor, raw_value_m FROM measurements WHERE id = 1").use { c ->
                c.moveToFirst()
                assertNull(c.getString(0))
                assertNull(c.getString(1))
                assertEquals(true, c.isNull(2))
                assertEquals(true, c.isNull(3))
            }
            db.query("SELECT COUNT(*) FROM gallery_items").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }

        val room = openRoom()
        try {
            val dao = room.galleryDao()
            val id = runBlocking {
                dao.insert(
                    GalleryItemEntity(
                        kind = "FRAME",
                        widthMeters = 0.457, widthBound95 = 0.01, widthSource = "MEASURED",
                        heightMeters = 0.61, heightBound95 = 0.01, heightSource = "MEASURED",
                        depthMeters = 0.02, depthBound95 = 0.0, depthSource = "PRESET",
                        quality = "FAIR", calibrated = false, deviceTier = "NO_DEPTH",
                        createdAt = 1L, updatedAt = 1L, modelPath = "gallery/1/model.glb",
                    ),
                )
            }
            val row = runBlocking { dao.get(id) }
            assertNotNull(row)
            assertEquals(0.457, row!!.widthMeters, 0.0)
            val legacy = runBlocking { room.measurementDao().get(1) }!!
            assertNull(legacy.calibrationFactor)
            assertNull(legacy.deviceTier)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
