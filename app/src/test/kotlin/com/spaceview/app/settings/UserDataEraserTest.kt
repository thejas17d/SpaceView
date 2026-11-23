package com.spaceview.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.spaceview.data.gallery.GalleryDao
import com.spaceview.data.gallery.GalleryFileRefs
import com.spaceview.data.gallery.GalleryImageCodec
import com.spaceview.data.gallery.GalleryItemEntity
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.history.MeasurementDao
import com.spaceview.data.history.MeasurementEntity
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.data.settings.MeasureSettings
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** In-memory Preferences DataStore (the file-based one cannot write twice on Windows). */
class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)
    var failWrites = false
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failWrites) throw java.io.IOException("disk full")
        return transform(state.value).also { state.value = it }
    }
}

/** M5 Delete my data, through the real repositories over in-memory DAOs and DataStores and real files. */
class UserDataEraserTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files get() = File(tmp.root, "files")
    private val cache get() = File(tmp.root, "cache")

    private class Measurements : MeasurementDao {
        var rows = 3
        var fail = false
        override suspend fun deleteAll() {
            if (fail) throw java.io.IOException("db")
            rows = 0
        }
        override suspend fun count() = rows
        override suspend fun insert(entity: MeasurementEntity): Long = error("unused")
        override fun observeRecent(limit: Int): Flow<List<MeasurementEntity>> = flowOf(emptyList())
        override suspend fun get(id: Long): MeasurementEntity? = null
        override suspend fun setLabel(id: Long, label: String?) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun trimTo(keep: Int) = Unit
    }

    private class Gallery : GalleryDao {
        var rows = 2
        override suspend fun deleteAll(): Int = rows.also { rows = 0 }
        override suspend fun count() = rows
        override suspend fun insert(entity: GalleryItemEntity): Long = error("unused")
        override fun observeAll(): Flow<List<GalleryItemEntity>> = flowOf(emptyList())
        override fun observeCount(): Flow<Int> = flowOf(rows)
        override suspend fun countModelKind(modelKind: String) = 0
        override fun observe(id: Long): Flow<GalleryItemEntity?> = flowOf(null)
        override suspend fun get(id: Long): GalleryItemEntity? = null
        override suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long) = Unit
        override suspend fun setLabel(id: Long, label: String?, updatedAt: Long) = 0
        override suspend fun setDimensions(
            id: Long, widthM: Double, widthB: Double, widthS: String, heightM: Double, heightB: Double, heightS: String,
            depthM: Double, depthB: Double, depthS: String, quality: String, modelPath: String, totalBytes: Long, updatedAt: Long,
        ) = 0
        override suspend fun allFileRefs(): List<GalleryFileRefs> = emptyList()
        override suspend fun delete(id: Long) = 0
    }

    private val noCodec = object : GalleryImageCodec {
        override fun decode(encoded: ByteArray, maxLongSide: Int) = null
    }

    private val measurementDao = Measurements()
    private val galleryDao = Gallery()
    private val measurePrefs = MemoryPreferences()
    private val appPrefs = MemoryPreferences()

    private fun eraser() = UserDataEraser(
        history = MeasurementRepository(measurementDao),
        gallery = GalleryRepository(galleryDao, files, noCodec, io = Dispatchers.IO, cacheDir = cache),
        measureSettings = MeasureSettingsRepository(measurePrefs),
        appSettings = SettingsRepository(appPrefs),
    )

    private fun file(path: String) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `deletes history, gallery files, calibration, settings and app caches but keeps onboarding and SDK caches`() = runTest {
        val model = file("files/gallery/1/model.glb")
        val photo = file("files/gallery/2/front.jpg")
        val share = file("cache/share/1-1000/SpaceView.jpg")
        val placeBox = file("cache/place/box-1.glb")
        val sdkCache = file("cache/WebView/Default/HTTP Cache/data_0")
        val keepOutside = file("files/datastore/spaceview_entitlement.preferences_pb")
        val measure = MeasureSettingsRepository(measurePrefs)
        measure.setDepthEnabled(true)
        measure.markSafetyNoticeShown()
        val settings = SettingsRepository(appPrefs)
        settings.setUnitPreference(UnitPreference.IMPERIAL)
        settings.setSoundsEnabled(false)
        settings.setOnboardingCompleted()

        val result = eraser().eraseAll()

        assertTrue(result.complete)
        assertEquals(0, measurementDao.rows)
        assertEquals(0, galleryDao.rows)
        assertFalse(model.exists())
        assertFalse(photo.exists())
        assertFalse(File(files, "gallery").exists())
        assertFalse(share.exists())
        assertFalse(placeBox.exists())
        assertTrue("SDK caches (WebView, Play services, ML Kit) are never touched (review MINOR 11)", sdkCache.isFile)
        assertEquals(MeasureSettings(), measure.settings.first())
        assertEquals(UnitPreference.AUTO, settings.unitPreference.first())
        assertTrue(settings.soundsEnabled.first())
        assertTrue("onboarding is not personal data", settings.onboardingCompleted.first())
        assertTrue("the Pro entitlement cache (Play subscription) is never touched", keepOutside.isFile)
    }

    @Test
    fun `a failing step is reported and the other steps still run`() = runTest {
        measurementDao.fail = true
        measurePrefs.failWrites = true
        val model = file("files/gallery/1/model.glb")
        appPrefs.state.value = emptyPreferences().toMutablePreferences().apply {
            this[booleanPreferencesKey("sounds_enabled")] = false
        }

        val result = eraser().eraseAll()

        assertFalse(result.complete)
        assertFalse(result.history)
        assertFalse(result.measureSettings)
        assertTrue(result.gallery)
        assertTrue(result.appSettings)
        assertFalse(model.exists())
        assertTrue(SettingsRepository(appPrefs).soundsEnabled.first())
    }

    @Test
    fun `nothing saved yet - erasing is harmless and complete`() = runTest {
        measurePrefs.state.value = emptyPreferences().toMutablePreferences().apply { this[longPreferencesKey("calibration_at")] = 5L }
        val result = eraser().eraseAll()
        assertTrue(result.complete)
        assertTrue(measurePrefs.state.value.asMap().isEmpty())
    }
}
