package com.spaceview.app.ads

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real Preferences DataStore on the JVM.
 *
 * DataStore's JVM file storage replaces the file with `File.renameTo`, which fails on Windows once
 * the target exists, so a second write to the same file only works on Linux/macOS (and Android).
 * Most tests therefore do a single write per file; the multi-write test runs on CI (ubuntu) only.
 */
class DataStoreAdCounterStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun dataStore(name: String) = CoroutineScope(Dispatchers.IO + SupervisorJob()).let { scope ->
        scopes += scope
        PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("$name.preferences_pb") }
    }

    /** Closes every open DataStore so the same file can be reopened. */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    @After fun tearDown() = runBlocking { closeAll() }

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun countersRoundTripThroughFile() = runBlocking {
        val written = AdCounters(
            completedScans = 7,
            scansSinceInterstitial = 2,
            lastInterstitialAtMillis = 1_762_160_000_000L,
            interstitialDay = 20_395L,
            interstitialsOnDay = 3,
        )
        assertEquals(written, DataStoreAdCounterStore(dataStore("ads")).update { written })
        closeAll()
        assertEquals(written, DataStoreAdCounterStore(dataStore("ads")).read())
    }

    @Test
    fun emptyStoreReadsAsZero() = runBlocking {
        assertEquals(AdCounters(), DataStoreAdCounterStore(dataStore("empty")).read())
    }

    @Test
    fun policyCountersPersistAcrossInstances() = runBlocking {
        assumeFalse("DataStore JVM rewrite is unsupported on Windows; runs on CI", windows)
        val clock = FakeClock()
        val first = AdPolicy(DataStoreAdCounterStore(dataStore("policy")), clock)
        repeat(4) { first.recordCompletedScan() }
        assertEquals(AdDecision.SHOW, first.decide(NextAction.DONE))
        first.recordInterstitialShown()
        closeAll()

        val reopened = DataStoreAdCounterStore(dataStore("policy"))
        val c = reopened.read()
        assertEquals(4, c.completedScans)
        assertEquals(0, c.scansSinceInterstitial)
        assertEquals(clock.now, c.lastInterstitialAtMillis)
        assertEquals(1, c.interstitialsOnDay)
        assertEquals(AdDecision.TOO_FEW_SCANS, AdPolicy(reopened, clock).decide(NextAction.DONE))
    }

    @Test
    fun settingsDefaults() = runBlocking {
        val repo = SettingsRepository(dataStore("defaults"))
        assertEquals(UnitPreference.AUTO, repo.unitPreference.first())
        assertTrue(repo.soundsEnabled.first())
        assertFalse(repo.onboardingCompleted.first())
    }

    @Test
    fun settingsUnitPreferencePersists() = runBlocking {
        SettingsRepository(dataStore("units")).setUnitPreference(UnitPreference.IMPERIAL)
        closeAll()
        assertEquals(UnitPreference.IMPERIAL, SettingsRepository(dataStore("units")).unitPreference.first())
    }

    @Test
    fun settingsSoundsPersist() = runBlocking {
        SettingsRepository(dataStore("sounds")).setSoundsEnabled(false)
        closeAll()
        assertFalse(SettingsRepository(dataStore("sounds")).soundsEnabled.first())
    }

    @Test
    fun onboardingCompletionPersists() = runBlocking {
        SettingsRepository(dataStore("onboarding")).setOnboardingCompleted()
        closeAll()
        assertTrue(SettingsRepository(dataStore("onboarding")).onboardingCompleted.first())
    }
}
