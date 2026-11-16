package com.spaceview.app.ads

import android.app.Activity
import com.spaceview.app.pro.Entitlement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAdsSdk : AdsSdk {
    var consentRequests = 0
    var inits = 0
    var loads = 0
    var shown = 0
    var canRequest = true

    override fun canRequestAds(): Boolean = canRequest

    override fun privacyOptionsRequired(): Boolean = false

    override fun requestConsent(activity: Activity, onResult: (ConsentResult) -> Unit) {
        consentRequests++
        onResult(ConsentResult.UPDATED)
    }

    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) = onDone()

    override fun initialize(onReady: () -> Unit) {
        inits++
        onReady()
    }

    override fun loadInterstitial(unitId: String, onLoaded: (AdsInterstitial?) -> Unit) {
        loads++
        onLoaded(
            object : AdsInterstitial {
                override fun show(activity: Activity, onShown: () -> Unit, onClosed: () -> Unit) {
                    shown++
                    onShown()
                    onClosed()
                }
            },
        )
    }
}

/** ADR-011 / §4.6 / §4.10: Pro never initialises AdMob, ads never start before the entitlement is known. */
class AdsManagerProTest {
    private val activity = Activity()
    private val pro = Entitlement.Pro("pro", true, 0L, Entitlement.Pro.Source.PLAY_LIVE)
    private val sdk = FakeAdsSdk()
    private val entitlement = MutableStateFlow<Entitlement>(Entitlement.Unknown)

    /** Most tests start from a settled entitlement; the settle tests reset it. */
    private val settled = MutableStateFlow(true)

    private fun TestScope.manager(): AdsManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Counters that make an interstitial due, so only the entitlement can stop it.
        val store = InMemoryAdCounterStore(AdCounters(completedScans = 10, scansSinceInterstitial = 5))
        return AdsManager(
            policy = AdPolicy(store, FakeClock()),
            sdk = sdk,
            entitlement = entitlement,
            settled = settled,
            interstitialUnitId = "test-interstitial",
            bannerUnitId = "test-banner",
            clock = { testScheduler.currentTime },
            main = dispatcher,
            background = dispatcher,
            activityState = object : AdsManager.ActivityState {
                override fun isAlive(activity: Activity) = true

                override fun isResumed(activity: Activity) = true
            },
            log = {},
        )
    }

    private fun AdsManager.showAfterSave() = maybeShowAfterSave(activity, NextAction.DONE) {}

    @Test
    fun `Pro at start - no UMP, no SDK init, no ads`() = runTest {
        entitlement.value = pro
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(0, sdk.consentRequests)
        assertEquals(0, sdk.inits)
        assertEquals(0, sdk.loads)
        assertFalse(ads.canShowAds.value)
    }

    @Test
    fun `Free at start - consent, init and the interstitial work as before`() = runTest {
        entitlement.value = Entitlement.Free
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.consentRequests)
        assertEquals(1, sdk.inits)
        assertTrue(ads.canShowAds.value)
        assertEquals("preloaded because the next save may show", 1, sdk.loads)
        var continued = false
        ads.maybeShowAfterSave(activity, NextAction.DONE) { continued = true }
        advanceUntilIdle()
        assertEquals(1, sdk.shown)
        assertTrue("continues after the ad closes", continued)
    }

    @Test
    fun `slow Play - Unknown for 5 s, then Pro - zero consent and zero init`() = runTest {
        settled.value = false
        val ads = manager()
        ads.start(activity)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, sdk.consentRequests)
        entitlement.value = pro
        settled.value = true
        advanceUntilIdle()
        assertEquals(0, sdk.consentRequests)
        assertEquals(0, sdk.inits)
        assertFalse(ads.canShowAds.value)
    }

    /** P1 review MAJOR 1: stale cache (8 days away) or reinstall reads Free at 1 s while Play is still answering. */
    @Test
    fun `stale cache - Free before Play answers is not trusted, then Pro - zero consent and zero init`() = runTest {
        settled.value = false
        val ads = manager()
        ads.start(activity)
        advanceTimeBy(1_000)
        entitlement.value = Entitlement.Free // the repository's 1 s fallback from a stale cache
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, sdk.consentRequests)
        assertEquals(0, sdk.inits)
        entitlement.value = pro // Play answers at 3 s
        settled.value = true
        advanceUntilIdle()
        assertEquals(0, sdk.consentRequests)
        assertEquals(0, sdk.inits)
        assertFalse(ads.canShowAds.value)
    }

    @Test
    fun `settled Free - ads start right away`() = runTest {
        settled.value = false
        val ads = manager()
        ads.start(activity)
        advanceTimeBy(800)
        entitlement.value = Entitlement.Free
        settled.value = true
        advanceUntilIdle()
        assertEquals(1, sdk.consentRequests)
        assertEquals(1, sdk.inits)
        assertTrue(ads.canShowAds.value)
    }

    @Test
    fun `Play never answers - after the 12 s cap ads follow the cache (Free)`() = runTest {
        settled.value = false
        val ads = manager()
        ads.start(activity)
        entitlement.value = Entitlement.Free
        advanceTimeBy(AdsManager.SETTLE_WAIT_MS - 1)
        runCurrent()
        assertEquals(0, sdk.inits)
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, sdk.inits)
        assertTrue(ads.canShowAds.value)
    }

    @Test
    fun `becoming Pro mid-session drops the interstitial and hides banners`() = runTest {
        entitlement.value = Entitlement.Free
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.loads)
        entitlement.value = pro
        advanceUntilIdle()
        assertFalse("BannerAd renders nothing", ads.canShowAds.value)
        var continued = false
        ads.maybeShowAfterSave(activity, NextAction.DONE) { continued = true }
        advanceUntilIdle()
        assertTrue(continued)
        assertEquals(0, sdk.shown)
        ads.preloadInterstitial()
        advanceUntilIdle()
        assertEquals("no new request for Pro", 1, sdk.loads)
    }

    @Test
    fun `becoming Pro drops a pending after-save chance`() = runTest {
        entitlement.value = Entitlement.Free
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        ads.onMeasureEntered("visit")
        ads.recordCompletedScan()
        ads.onMeasureLeaving("visit")
        entitlement.value = pro
        advanceUntilIdle()
        ads.onNonArScreenResumed(activity)
        advanceUntilIdle()
        assertEquals(0, sdk.shown)
    }

    @Test
    fun `lapse runs consent and init once, later changes never re-init`() = runTest {
        entitlement.value = pro
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(0, sdk.inits)
        entitlement.value = Entitlement.Free
        advanceUntilIdle()
        assertEquals(1, sdk.consentRequests)
        assertEquals(1, sdk.inits)
        assertTrue(ads.canShowAds.value)
        entitlement.value = pro
        advanceUntilIdle()
        assertFalse(ads.canShowAds.value)
        entitlement.value = Entitlement.Free
        advanceUntilIdle()
        assertEquals(1, sdk.consentRequests)
        assertEquals(1, sdk.inits)
        assertTrue(ads.canShowAds.value)
    }

    @Test
    fun `Pending payment still shows ads (no Pro features yet)`() = runTest {
        entitlement.value = Entitlement.Pending("pro")
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.inits)
        assertTrue(ads.canShowAds.value)
    }

    @Test
    fun `no consent - SDK never initialises`() = runTest {
        entitlement.value = Entitlement.Free
        sdk.canRequest = false
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.consentRequests)
        assertEquals(0, sdk.inits)
        assertFalse(ads.canShowAds.value)
    }

    @Test
    fun `interstitial is never shown while an AR screen is composed`() = runTest {
        entitlement.value = Entitlement.Free
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        ads.onArScreenEntered()
        ads.showAfterSave()
        advanceUntilIdle()
        assertEquals(0, sdk.shown)
    }
}
