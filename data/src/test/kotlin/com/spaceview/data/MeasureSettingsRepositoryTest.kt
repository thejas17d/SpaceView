package com.spaceview.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import com.spaceview.data.settings.CalibrationRecord
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real Preferences DataStore on the JVM. DataStore replaces its file with `File.renameTo`, which
 * fails on Windows once the file exists, so tests write once per file; the multi-write test runs
 * on CI (Linux) only.
 */
class MeasureSettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun record(factor: Double, ref: ReferenceObject? = ReferenceObject.A4) = CalibrationRecord(
        factor, 0.003, ref, 12345L, Vec3(0.0, 1.0, 0.0), -0.75, Vec3(0.1, 0.75, -0.4), rangeM = 0.5, sessionId = "arcore-7",
    )

    private fun repo(scope: CoroutineScope) = MeasureSettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { java.io.File(tmp.root, "m.preferences_pb") },
    )

    @Test
    fun defaults() = runTest {
        val s = repo(backgroundScope).settings.first()
        assertNull(s.calibration)
        assertFalse(s.depthEnabled)
        assertFalse(s.safetyNoticeShown)
    }

    @Test
    fun storesCalibration() = runTest {
        val r = repo(backgroundScope)
        r.setCalibration(record(1.012))
        val c = r.settings.first().calibration!!
        assertEquals(1.012, c.factor, 0.0)
        assertEquals(0.003, c.sigma, 0.0)
        assertEquals(ReferenceObject.A4, c.reference)
        assertEquals(12345L, c.calibratedAtMillis)
        assertEquals(0.5, c.rangeM, 0.0)
        assertEquals("arcore-7", c.sessionId)
        assertEquals(-0.75, c.planeOffset, 1e-12)
        assertTrue(c.anchor.approxEquals(Vec3(0.1, 0.75, -0.4), 1e-12))
        val same = c.toScaleCalibration()
        assertEquals(1.0, same.plane!!.normal.y, 1e-12)
        assertEquals(0.5, same.rangeM!!, 0.0)
        // Only the ARCore session that made it may use it (M2 review M3).
        assertTrue(c.applicableIn("arcore-7", 12_345L) != null)
        assertNull(c.applicableIn("arcore-8", 12_345L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImplausibleCalibration() = runTest {
        repo(backgroundScope).setCalibration(record(1.12, null))
    }

    @Test
    fun incompleteScopeIsInvalid() {
        assertNull(MeasureSettingsRepository.decode(null))
        assertNull(MeasureSettingsRepository.decode("1,2,3"))
        assertNull(MeasureSettingsRepository.decode("0,0,0,1,0,0,0"))
        assertTrue(MeasureSettingsRepository.decode("0,1,0,-0.75,0.1,0.75,-0.4") != null)
    }

    @Test
    fun storesDepthFlag() = runTest {
        val r = repo(backgroundScope)
        r.setDepthEnabled(true)
        assertTrue(r.settings.first().depthEnabled)
    }

    @Test
    fun storesSafetyNotice() = runTest {
        val r = repo(backgroundScope)
        r.markSafetyNoticeShown()
        assertTrue(r.settings.first().safetyNoticeShown)
    }

    /** In-memory DataStore: the multi-write paths run on every OS (the file-based one cannot on Windows). */
    private class MemoryStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    @Test
    fun clearCalibrationRemovesEverything() = runTest {
        val store = MemoryStore()
        val r = MeasureSettingsRepository(store)
        r.setCalibration(record(1.012, ReferenceObject.ID1_CARD))
        r.setDepthEnabled(true)
        assertTrue(r.settings.first().calibration != null)
        r.clearCalibration()
        val s = r.settings.first()
        assertNull(s.calibration)
        assertTrue(s.depthEnabled) // other settings are kept
        assertTrue(store.state.value.asMap().keys.none { it.name.startsWith("calibration") })
        // Clearing twice (Settings button tapped again) is harmless.
        r.clearCalibration()
        assertNull(r.settings.first().calibration)
    }

    @Test
    fun legacyVisitTimeRecordIsNeverLoaded() = runTest {
        // An M2 build stored the visit time (a Long) as the session; such a record has no ARCore
        // session identity and must not come back as a calibration.
        val store = MemoryStore()
        val r = MeasureSettingsRepository(store)
        r.setCalibration(record(1.012))
        store.state.value = store.state.value.toMutablePreferences().apply {
            remove(stringPreferencesKey("calibration_session_id"))
            this[longPreferencesKey("calibration_session")] = 1_700_000_000_000L
        }
        assertNull(r.settings.first().calibration)
        r.clearCalibration()
        assertTrue(store.state.value.asMap().isEmpty())
    }

    @Test
    fun writeFailuresSurfaceAsIoExceptions() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("full")
        }
        val r = MeasureSettingsRepository(broken)
        // Reads fall back to defaults; writes throw IOException for the caller to report.
        assertNull(r.settings.first().calibration)
        try {
            r.clearCalibration()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {}
    }

    @Test
    fun clearsCalibration() = runTest {
        assumeFalse("DataStore cannot replace an existing file on Windows JVMs", windows)
        val r = repo(backgroundScope)
        r.setCalibration(record(1.012, ReferenceObject.ID1_CARD))
        r.clearCalibration()
        assertNull(r.settings.first().calibration)
    }
}
