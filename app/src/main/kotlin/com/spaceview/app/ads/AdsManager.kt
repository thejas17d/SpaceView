package com.spaceview.app.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.view.doOnPreDraw
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.spaceview.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns consent (UMP), Mobile Ads start-up and the one interstitial.
 *
 * Start-up order (docs/research/android-performance.md §5.2):
 *  1. the first frame is drawn ([startAfterFirstFrame]);
 *  2. UMP `requestConsentInfoUpdate` (every launch, once per process), consent form only where required;
 *  3. when `canRequestAds()`, `MobileAds.initialize` on [Dispatchers.IO], once per process.
 *
 * Placement (docs/research/ui-ux.md §5): banners only on non-camera screens ([BannerAd]); one
 * preloaded interstitial, shown **only** through [maybeShowAfterSave] and only when [AdPolicy]
 * allows it. If no ad is ready, nothing is shown and nothing waits.
 *
 * Main-thread API: call everything from the main thread.
 */
class AdsManager(
    context: Context,
    val policy: AdPolicy,
    private val interstitialUnitId: String = BuildConfig.AD_INTERSTITIAL,
    /** Ad unit for [BannerAd]. */
    val bannerUnitId: String = BuildConfig.AD_BANNER,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consent: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(appContext) }
    private val sdkStarted = AtomicBoolean(false)
    private val consentRequested = AtomicBoolean(false)
    private val startScheduled = AtomicBoolean(false)
    @Volatile private var sdkInitialised = false

    private val _canShowAds = MutableStateFlow(false)

    /** True once consent allows ad requests and the SDK has finished initialising. */
    val canShowAds: StateFlow<Boolean> = _canShowAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)

    /** True when UMP says the user must be offered a privacy-options entry point (e.g. EEA/UK). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var interstitial: InterstitialAd? = null
    private var interstitialLoadedAt = 0L
    private var interstitialLoading = false
    private var showing = false

    /**
     * Call from `MainActivity.onCreate` (after `setContent`). Defers all consent and SDK work until
     * just after the first frame has been drawn, so ads never cost cold-start time.
     */
    fun startAfterFirstFrame(activity: ComponentActivity) {
        if (!startScheduled.compareAndSet(false, true)) {
            gatherConsent(activity)
            return
        }
        activity.window.decorView.doOnPreDraw { view ->
            // doOnPreDraw runs just before the first draw; post() lands just after it.
            view.post { if (!activity.isFinishing && !activity.isDestroyed) gatherConsent(activity) }
        }
    }

    /** UMP consent: once per process. Shows a consent form only where the law requires one. */
    fun gatherConsent(activity: Activity) {
        // A previous launch may already allow requests: start without waiting for the network.
        startSdkIfAllowed()
        if (!consentRequested.compareAndSet(false, true)) return
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    consentRequested.set(false)
                    startSdkIfAllowed()
                    return@requestConsentInfoUpdate
                }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "Consent form: ${error.message}")
                    updatePrivacyOptionsRequired()
                    startSdkIfAllowed()
                }
            },
            { error ->
                // Offline or misconfigured: fall back to the consent stored by an earlier launch.
                Log.w(TAG, "Consent update failed: ${error.message}")
                consentRequested.set(false)
                updatePrivacyOptionsRequired()
                startSdkIfAllowed()
            },
        )
    }

    /** Opens the UMP privacy-options form (Settings → Privacy options). [onDone] always runs. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "Privacy options: ${error.message}")
            updatePrivacyOptionsRequired()
            // The user may have withdrawn consent; requests stay governed by canRequestAds().
            if (!consent.canRequestAds()) {
                interstitial = null
                _canShowAds.value = false
            } else {
                startSdkIfAllowed()
            }
            onDone()
        }
    }

    private fun updatePrivacyOptionsRequired() {
        _privacyOptionsRequired.value =
            consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun startSdkIfAllowed() {
        if (!consent.canRequestAds()) return
        if (!sdkStarted.compareAndSet(false, true)) {
            if (sdkInitialised) _canShowAds.value = true
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                // No mediation in v1: skip adapter start-up work.
                MobileAds.disableMediationAdapterInitialization(appContext)
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                        .build(),
                )
                MobileAds.initialize(appContext) {
                    scope.launch {
                        MobileAds.setAppMuted(true)
                        sdkInitialised = true
                        _canShowAds.value = true
                        if (policy.nextSaveMayShow()) preloadInterstitial()
                    }
                }
            }
        }
    }

    /**
     * Loads one interstitial if none is loaded or loading. Called automatically when the next save
     * could be eligible; call it too when the user enters a scan/measure flow.
     */
    fun preloadInterstitial() {
        if (!_canShowAds.value || interstitialLoading) return
        if (interstitial != null && !isStale()) return
        interstitial = null
        interstitialLoading = true
        InterstitialAd.load(
            appContext,
            interstitialUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    interstitialLoadedAt = SystemClock.elapsedRealtime()
                    interstitialLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.i(TAG, "Interstitial not loaded: ${error.code}")
                    interstitial = null
                    interstitialLoading = false
                }
            },
        )
    }

    /** Loaded interstitials expire after an hour; refresh a little earlier. */
    private fun isStale(): Boolean = SystemClock.elapsedRealtime() - interstitialLoadedAt > MAX_AD_AGE_MS

    /**
     * Call exactly once when a scan or measurement has been saved successfully (before the Saved
     * screen is left). Counts toward the frequency cap and preloads an ad if the next exit may use it.
     */
    fun recordCompletedScan() {
        scope.launch {
            policy.recordCompletedScan()
            if (policy.nextSaveMayShow() || policy.decide(NextAction.DONE) == AdDecision.SHOW) preloadInterstitial()
        }
    }

    /**
     * The **only** way an interstitial is shown. Call when the user leaves the "Saved" result.
     * Shows the preloaded ad if [AdPolicy] allows it for [nextAction]; otherwise does nothing.
     * [onContinue] runs exactly once: after the ad closes, or straight away when no ad is shown.
     * Never call this from a camera/AR screen.
     */
    fun maybeShowAfterSave(
        activity: Activity,
        nextAction: NextAction = NextAction.DONE,
        onContinue: () -> Unit,
    ) {
        scope.launch {
            val decision = policy.decide(nextAction, AdScreen.NON_CAMERA)
            val ad = interstitial
            val ready = ad != null && !isStale() && !showing && _canShowAds.value &&
                !activity.isFinishing && !activity.isDestroyed
            if (decision != AdDecision.SHOW || !ready) {
                if (BuildConfig.DEBUG) Log.d(TAG, "No interstitial: $decision, ready=$ready")
                onContinue()
                return@launch
            }
            interstitial = null
            showing = true
            val finished = AtomicBoolean(false)
            fun finish() {
                if (finished.compareAndSet(false, true)) {
                    showing = false
                    onContinue()
                }
            }
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    scope.launch { policy.recordInterstitialShown() }
                }

                override fun onAdDismissedFullScreenContent() = finish()

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    Log.w(TAG, "Interstitial failed to show: ${error.message}")
                    finish()
                }
            }
            ad.show(activity)
        }
    }

    companion object {
        private const val TAG = "AdsManager"
        private const val MAX_AD_AGE_MS = 55L * 60 * 1000

        @Volatile private var instance: AdsManager? = null

        /** App-wide instance (manual DI). Cheap: touches no ads SDK until [startAfterFirstFrame]. */
        fun get(context: Context): AdsManager =
            instance ?: synchronized(this) {
                instance ?: AdsManager(
                    context,
                    AdPolicy(DataStoreAdCounterStore.get(context)),
                ).also { instance = it }
            }
    }
}
