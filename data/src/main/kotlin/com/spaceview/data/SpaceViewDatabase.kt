package com.spaceview.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spaceview.data.gallery.GalleryDao
import com.spaceview.data.gallery.GalleryItemEntity
import com.spaceview.data.history.MeasurementDao
import com.spaceview.data.history.MeasurementEntity

/**
 * SpaceView's Room database (app-private). The schema JSON is exported to `data/schemas/` and every
 * migration in [MIGRATIONS] is exercised by `SpaceViewMigrationTest` (androidTest, MigrationTestHelper)
 * before it ships.
 *
 * Adding a version: bump `version` and [LATEST_VERSION] together, add a `Migration(n, n + 1)` to
 * [MIGRATIONS], build once so Room exports `schemas/…/(n+1).json`, copy its `createSql` into the migration,
 * and extend the migration test.
 *
 * - v1 (M2): `measurements` (History).
 * - v2 (M3): `gallery_items` (AR Gallery) and the canonical-record columns on `measurements`
 *   (M2 gate §6.3): device tier, ARCore version, calibration factor, raw (uncalibrated) length.
 * - v3 (M4, ARCHITECTURE-v2 §9 / ADR-008): the sprint's one consolidated migration. `gallery_items` gains
 *   `model_kind` (NOT NULL, default `PROXY_BOX`, which backfills every existing row), the template columns, the mesh
 *   extent columns (§7.1.5), the mesh GLB box columns, `mesh_info` and `capture_method`; `measurements` gains `capture_method`. All additive.
 *   **Amendment window:** v3 may be amended in place only until an APK containing v3 is handed to the user or to
 *   testers; after that any change is v4.
 */
@Database(entities = [MeasurementEntity::class, GalleryItemEntity::class], version = 3, exportSchema = true)
abstract class SpaceViewDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    abstract fun galleryDao(): GalleryDao

    companion object {
        const val FILE_NAME = "spaceview.db"

        /** Must equal the `version` in @Database above. */
        const val LATEST_VERSION = 3

        /** v1 → v2: adds the gallery table and the canonical-record columns of History (all with defaults). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Must match the createSql of gallery_items in schemas/…/2.json exactly.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gallery_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `width_m` REAL NOT NULL, `width_bound95_m` REAL NOT NULL, " +
                        "`width_source` TEXT NOT NULL, `height_m` REAL NOT NULL, `height_bound95_m` REAL NOT NULL, " +
                        "`height_source` TEXT NOT NULL, `depth_m` REAL NOT NULL, `depth_bound95_m` REAL NOT NULL, " +
                        "`depth_source` TEXT NOT NULL, `measured_width_m` REAL, `measured_width_bound95_m` REAL, " +
                        "`measured_height_m` REAL, `measured_height_bound95_m` REAL, `measured_depth_m` REAL, " +
                        "`measured_depth_bound95_m` REAL, `quality` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, " +
                        "`calibration_factor` REAL, `device_tier` TEXT NOT NULL, `arcore_version` TEXT, `label` TEXT, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `model_path` TEXT NOT NULL, " +
                        "`photo_path` TEXT, `thumbnail_path` TEXT, `total_bytes` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gallery_items_created_at` ON `gallery_items` (`created_at`)")
                // Canonical record of a History measurement. Legacy rows: tier / version / factor / raw length
                // unknown (NULL); M2 never stored the factor (`calibrated` still says whether one applied).
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `device_tier` TEXT")
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `arcore_version` TEXT")
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `calibration_factor` REAL")
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `raw_value_m` REAL")
            }
        }

        /** The `ALTER TABLE` statements of [MIGRATION_2_3], in order (also used by the JVM schema test). */
        internal val V3_STATEMENTS: List<String> = listOf(
            "ALTER TABLE `gallery_items` ADD COLUMN `model_kind` TEXT NOT NULL DEFAULT 'PROXY_BOX'",
            "ALTER TABLE `gallery_items` ADD COLUMN `template_id` TEXT",
            "ALTER TABLE `gallery_items` ADD COLUMN `template_params` TEXT",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_extent_w_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_extent_h_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_extent_d_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_extent_w_bound95_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_extent_h_bound95_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_extent_d_bound95_m` REAL",
            // Amended in place (M4 review MAJOR 2), inside the v3 amendment window: the GLB's own box.
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_glb_w_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_glb_h_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_glb_d_m` REAL",
            "ALTER TABLE `gallery_items` ADD COLUMN `mesh_info` TEXT",
            "ALTER TABLE `gallery_items` ADD COLUMN `capture_method` TEXT",
            "ALTER TABLE `measurements` ADD COLUMN `capture_method` TEXT",
        )

        /**
         * v2 → v3: additive columns only (see the class comment). Existing gallery rows become `PROXY_BOX`; every
         * other new column is NULL, which every reader treats as "unknown / not a mesh / not a template".
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in V3_STATEMENTS) db.execSQL(sql)
            }
        }

        /** Every schema migration, oldest first. Never destructive: History and Gallery are user data. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile private var instance: SpaceViewDatabase? = null

        /**
         * The process-wide database (one Room instance per file, shared by History and Gallery so they see
         * each other's invalidations). Opening is lazy: no I/O happens until the first query.
         */
        fun get(context: Context): SpaceViewDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        /** Builds a new database instance. Prefer [get]; this is for tests. */
        fun create(context: Context): SpaceViewDatabase =
            Room.databaseBuilder(context.applicationContext, SpaceViewDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
