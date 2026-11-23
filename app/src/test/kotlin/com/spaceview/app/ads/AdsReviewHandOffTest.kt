package com.spaceview.app.ads

import android.app.Activity
import com.spaceview.app.pro.Entitlement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5: what the in-app review prompt reads from the ads manager (saves, AR foreground, an owed interstitial). */
class AdsReviewHandOffTest {
    private val activity = Activity()
    private var saves = 0

    private fun TestScope.manager(): AdsManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryAdCounterStore(AdCounters(completedScans = 10, scansSinceInterstitial = 5))
        return AdsManager(
            policy = AdPolicy(store, FakeClock()),
            sdk = FakeAdsSdk(),
            entitlement = MutableStateFlow<Entitlement>(Entitlement.Free),
            settled = MutableStateFlow(true),
            interstitialUnitId = "i",
            bannerUnitId = "b",
            clock = { testScheduler.currentTime },
            main = dispatcher,
            background = dispatcher,
            activityState = object : AdsManager.ActivityState {
                override fun isAlive(activity: Activity) = true

                override fun isResumed(activity: Activity) = true
            },
            log = {},
            onSaveCompleted = { saves++ },
        )
    }

    @Test
    fun `every recorded save is forwarded once`() = runTest {
        val ads = manager()
        ads.recordCompletedScan()
        ads.recordCompletedScan()
        assertEquals(2, saves)
    }

    @Test
    fun `AR foreground is visible to the prompt`() = runTest {
        val ads = manager()
        ads.onArScreenEntered()
        assertTrue(ads.arInForeground)
        ads.onArScreenLeft()
        assertFalse(ads.arInForeground)
    }

    @Test
    fun `an owed interstitial keeps the prompt away until it has closed`() = runTest {
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        ads.onMeasureEntered("v")
        ads.recordCompletedScan()
        ads.onMeasureLeaving("v")
        ads.onNonArScreenResumed(activity)
        // The prompt asks right after this call, on the same resume (NonArScreenAdEffect).
        assertTrue("claimed by the ad on this resume", ads.interstitialBusy)
        advanceUntilIdle()
        assertFalse("the fake ad has closed", ads.interstitialBusy)
    }

    @Test
    fun `no owed interstitial - not busy`() = runTest {
        val ads = manager()
        ads.start(activity)
        advanceUntilIdle()
        ads.onNonArScreenResumed(activity)
        runCurrent()
        assertFalse(ads.interstitialBusy)
    }
}
