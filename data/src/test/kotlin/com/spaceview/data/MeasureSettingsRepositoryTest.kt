package com.spaceview.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
        factor, 0.003, ref, 12345L, Vec3(0.0, 1.0, 0.0), -0.75, Vec3(0.1, 0.75, -0.4), rangeM = 0.5, sessionId = 7L,
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
        assertEquals(7L, c.sessionId)
        assertEquals(-0.75, c.planeOffset, 1e-12)
        assertTrue(c.anchor.approxEquals(Vec3(0.1, 0.75, -0.4), 1e-12))
        val same = c.toScaleCalibration(sameSession = true)
        assertEquals(1.0, same.plane!!.normal.y, 1e-12)
        assertEquals(0.5, same.rangeM!!, 0.0)
        assertNull(c.toScaleCalibration(sameSession = false).plane)
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

    @Test
    fun clearsCalibration() = runTest {
        assumeFalse("DataStore cannot replace an existing file on Windows JVMs", windows)
        val r = repo(backgroundScope)
        r.setCalibration(record(1.012, ReferenceObject.ID1_CARD))
        r.clearCalibration()
        assertNull(r.settings.first().calibration)
    }
}
