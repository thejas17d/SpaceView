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

/**
 * A UMP + Mobile Ads fake whose consent step is **asynchronous**, like the real form: [requestConsent] parks its
 * callback until the test resolves it, and [canRequestAds] only turns true when the user's answer allows it. Every
 * SDK call is logged in order, so a test can prove nothing ad-related happens before consent is resolved.
 */
class ScriptedConsentSdk(
    /** Consent stored by an earlier launch (UMP keeps it across launches). */
    var canRequest: Boolean = false,
    var privacyRequired: Boolean = false,
) : AdsSdk {
    val log = mutableListOf<String>()
    private var pending: ((ConsentResult) -> Unit)? = null
    private var canShow: () -> Boolean = { true }
    private var privacyDone: (() -> Unit)? = null

    val formShowing: Boolean get() = pending != null

    override fun canRequestAds(): Boolean = canRequest

    override fun privacyOptionsRequired(): Boolean = privacyRequired

    override fun requestConsent(activity: Activity, canShowForm: () -> Boolean, onResult: (ConsentResult) -> Unit) {
        log += "consent"
        canShow = canShowForm
        pending = onResult
    }

    /** The user answers the form (or the update fails). */
    fun resolve(result: ConsentResult, allowsAds: Boolean, privacyOptions: Boolean = privacyRequired) {
        val cb = checkNotNull(pending) { "no consent request is waiting" }
        pending = null
        if (result == ConsentResult.UPDATED && !canShow()) {
            // Like GoogleAdsSdk: the update arrived with AR in front, so the form is not shown and nothing is answered.
            log += "consent-deferred"
            cb(ConsentResult.DEFERRED)
            return
        }
        canRequest = allowsAds
        privacyRequired = privacyOptions
        log += "consent-resolved"
        cb(result)
    }

    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) {
        log += "privacy-options"
        privacyDone = onDone
    }

    /** The user closes the privacy-options form, having changed their choice to [allowsAds]. */
    fun closePrivacyOptions(allowsAds: Boolean) {
        canRequest = allowsAds
        checkNotNull(privacyDone).invoke()
        privacyDone = null
    }

    override fun initialize(onReady: () -> Unit) {
        log += "init"
        onReady()
    }

    override fun loadInterstitial(unitId: String, onLoaded: (AdsInterstitial?) -> Unit) {
        log += "load"
        onLoaded(
            object : AdsInterstitial {
                override fun show(activity: Activity, onShown: () -> Unit, onClosed: () -> Unit) {
                    log += "show"
                    onShown()
                    onClosed()
                }
            },
        )
    }

    fun count(event: String) = log.count { it == event }
}

/** M5: UMP consent verified end to end against the [AdsSdk] seam (PLAN §9 privacy, FRIDAY §4.D PRIVACY). */
class AdsConsentTest {
    private val activity = Activity()
    private val pro = Entitlement.Pro("pro", true, 0L, Entitlement.Pro.Source.PLAY_LIVE)
    private val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free)
    private val settled = MutableStateFlow(true)

    private fun TestScope.manager(sdk: AdsSdk): AdsManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Counters that make an interstitial due, so only consent can stop it.
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

    @Test
    fun `first launch - nothing is initialised or requested while the consent form is open`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        assertTrue(sdk.formShowing)
        assertEquals(listOf("consent"), sdk.log)
        assertFalse("no banner may load", ads.canShowAds.value)
        ads.preloadInterstitial()
        ads.maybeShowAfterSave(activity, NextAction.DONE) {}
        advanceUntilIdle()
        assertEquals("still nothing but the consent request", listOf("consent"), sdk.log)
    }

    @Test
    fun `consent given - init, then the first ad request, strictly after the form resolved`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true)
        advanceUntilIdle()
        assertEquals(listOf("consent", "consent-resolved", "init", "load"), sdk.log)
        assertTrue(ads.canShowAds.value)
    }

    @Test
    fun `consent refused or form failed - the SDK never initialises and nothing is requested`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = false)
        advanceUntilIdle()
        ads.preloadInterstitial()
        advanceUntilIdle()
        assertEquals(0, sdk.count("init"))
        assertEquals(0, sdk.count("load"))
        assertFalse(ads.canShowAds.value)
    }

    @Test
    fun `privacy options required - Settings gets the entry once consent info is known`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        assertFalse("unknown until UMP answers", ads.privacyOptionsRequired.value)
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true, privacyOptions = true)
        advanceUntilIdle()
        assertTrue(ads.privacyOptionsRequired.value)
    }

    @Test
    fun `privacy options not required - the Settings entry stays disabled`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true, privacyOptions = false)
        advanceUntilIdle()
        assertFalse(ads.privacyOptionsRequired.value)
    }

    @Test
    fun `withdrawing consent in privacy options drops the loaded ad and hides banners`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true, privacyOptions = true)
        advanceUntilIdle()
        assertEquals(1, sdk.count("load"))
        var done = false
        ads.showPrivacyOptions(activity) { done = true }
        sdk.closePrivacyOptions(allowsAds = false)
        advanceUntilIdle()
        assertTrue(done)
        assertFalse(ads.canShowAds.value)
        ads.maybeShowAfterSave(activity, NextAction.DONE) {}
        ads.preloadInterstitial()
        advanceUntilIdle()
        assertEquals(0, sdk.count("show"))
        assertEquals("no new request after withdrawal", 1, sdk.count("load"))
    }

    @Test
    fun `offline at launch - consent is asked again when a non-AR screen resumes`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.FAILED, allowsAds = false)
        advanceUntilIdle()
        assertEquals(0, sdk.count("init"))
        ads.onNonArScreenResumed(activity) // the next non-AR screen to resume retries
        advanceUntilIdle()
        assertEquals(2, sdk.count("consent"))
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true)
        advanceUntilIdle()
        assertEquals(1, sdk.count("init"))
    }

    @Test
    fun `consent already resolved - a later start does not show the form again`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true)
        advanceUntilIdle()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.count("consent"))
        assertEquals(1, sdk.count("init"))
    }

    @Test
    fun `returning user - consent stored by an earlier launch lets ads start, and the update still runs`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = true)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        // Google's recommended start-up: requests may use the consent UMP already stored (canRequestAds()).
        assertEquals(1, sdk.count("consent"))
        assertEquals(1, sdk.count("init"))
        assertTrue(ads.canShowAds.value)
    }

    @Test
    fun `Pro - the consent form is never requested and Settings never offers privacy options`() = runTest {
        entitlement.value = pro
        val sdk = ScriptedConsentSdk(canRequest = false, privacyRequired = true)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), sdk.log)
        assertFalse(ads.privacyOptionsRequired.value)
        assertFalse(ads.canShowAds.value)
    }

    @Test
    fun `entitlement not settled - no consent form until it settles or the cap passes (a Pro answer may still come)`() = runTest {
        settled.value = false
        entitlement.value = Entitlement.Unknown
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceTimeBy(AdsManager.SETTLE_WAIT_MS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), sdk.log)
        advanceUntilIdle() // the cap passes with no answer: Free
        assertEquals(listOf("consent"), sdk.log)
    }

    @Test
    fun `the consent form never opens over an AR screen - it waits for the next non-AR screen`() = runTest {
        settled.value = false
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        ads.onArScreenEntered() // the user opened Measure before Play answered
        settled.value = true
        advanceUntilIdle()
        assertEquals("no form over the camera", 0, sdk.count("consent"))
        ads.onNonArScreenResumed(activity) // still in AR (e.g. a dialog-less resume): nothing
        advanceUntilIdle()
        assertEquals(0, sdk.count("consent"))
        ads.onArScreenLeft()
        ads.onNonArScreenResumed(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.count("consent"))
    }

    /** Gate G1: the request starts on Home, the user opens Measure during the network round-trip. */
    @Test
    fun `AR opened while the request is in flight - no form over the camera, it shows on the next non-AR resume`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.count("consent"))
        ads.onArScreenEntered()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true) // the update arrives, the form would show now
        advanceUntilIdle()
        assertEquals("form deferred, not shown over the camera", 1, sdk.count("consent-deferred"))
        assertEquals(0, sdk.count("consent-resolved"))
        assertEquals(0, sdk.count("init"))
        ads.onArScreenLeft()
        ads.onNonArScreenResumed(activity)
        advanceUntilIdle()
        assertEquals("asked again on the non-AR screen", 2, sdk.count("consent"))
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true)
        advanceUntilIdle()
        assertEquals(1, sdk.count("consent-resolved"))
        assertEquals(1, sdk.count("init"))
    }

    @Test
    fun `a failed request is not retried over an AR screen either`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.FAILED, allowsAds = false)
        ads.onArScreenEntered()
        ads.start(activity)
        advanceUntilIdle()
        assertEquals(1, sdk.count("consent"))
    }

    @Test
    fun `resolved consent - later non-AR resumes never ask again`() = runTest {
        val sdk = ScriptedConsentSdk(canRequest = false)
        val ads = manager(sdk)
        ads.start(activity)
        advanceUntilIdle()
        sdk.resolve(ConsentResult.UPDATED, allowsAds = true)
        repeat(3) { ads.onNonArScreenResumed(activity) }
        advanceUntilIdle()
        assertEquals(1, sdk.count("consent"))
    }
}
