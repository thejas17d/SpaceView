package com.spaceview.app.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Interstitial flow after measuring ([MeasureVisitTracker], M2 review M6):
 *  1. navigation calls [onMeasureEntered] / [onMeasureLeaving] around each Measure visit and
 *     [onArScreenEntered] / [onArScreenLeft] around every AR/camera destination;
 *  2. [recordCompletedScan] marks the current visit as "saved";
 *  3. leaving a visit with a save arms one pending chance; leaving without a save never does;
 *  4. the next non-AR screen calls [onNonArScreenResumed] once it is RESUMED (navigation keeps a
 *     destination at STARTED until its transition has finished). That waits until no AR screen is
 *     composed, then asks [AdPolicy]. The ad therefore never opens over the camera.
 * Interstitials are only preloaded from non-AR screens, never while AR is in the foreground.
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

    /** "Saved this visit", the pending after-measure chance and AR-foreground tracking. */
    private val visits = MeasureVisitTracker(clock = SystemClock::elapsedRealtime)
    private val arForeground = MutableStateFlow(false)
    private var pendingShowJob: Job? = null

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
     * Loads one interstitial if none is loaded or loading. Does nothing while an AR/camera screen is
     * in the foreground (the ads WebView and network work would compete with tracking); the next
     * non-AR screen to resume preloads instead ([onNonArScreenResumed]).
     */
    fun preloadInterstitial() {
        if (!_canShowAds.value || interstitialLoading || visits.arInForeground) return
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
     * Call exactly once when a scan or measurement has been saved successfully. Counts toward the
     * frequency cap and marks the current Measure visit as "saved". Never preloads: it runs on the
     * AR screen.
     */
    fun recordCompletedScan() {
        visits.onScanSaved()
        scope.launch {
            try {
                policy.recordCompletedScan()
            } catch (e: Exception) {
                // Disk full or a corrupt counter file must not crash measuring; the cap just under-counts.
                Log.w(TAG, "Could not record scan: ${e.message}")
            }
        }
    }

    /** A Measure destination ([key] = its back-stack entry id) is on screen. */
    fun onMeasureEntered(key: String) {
        visits.enterMeasure(key)
        pendingShowJob?.cancel()
    }

    /**
     * The user is leaving Measure visit [key] (call before popping it). Arms one after-measure
     * chance only if a result was saved during this visit.
     */
    fun onMeasureLeaving(key: String) {
        val eligible = visits.leaveMeasure(key)
        if (BuildConfig.DEBUG) Log.d(TAG, "Leaving measure, saved this visit=$eligible")
    }

    /**
     * The user leaves visit [key] for Place in my room (or another action that must never show an ad):
     * closes the visit without arming an after-save chance and drops any pending one.
     */
    fun onVisitLeftWithoutAd(key: String) {
        visits.leaveWithoutAd(key)
        pendingShowJob?.cancel()
        pendingShowJob = null
    }

    /** An AR/camera destination entered composition. Pair with [onArScreenLeft]. */
    fun onArScreenEntered() {
        visits.arScreenEntered()
        arForeground.value = visits.arInForeground
    }

    /** An AR/camera destination left composition (after its exit transition). */
    fun onArScreenLeft() {
        visits.arScreenLeft()
        arForeground.value = visits.arInForeground
    }

    /**
     * Call when a non-AR screen (Home, History, Settings) reaches RESUMED. Uses a pending
     * after-measure chance once the AR screen has left composition; otherwise preloads an
     * interstitial if an upcoming exit could be eligible. Pair with [onNonArScreenPaused].
     */
    fun onNonArScreenResumed(activity: Activity) {
        pendingShowJob?.cancel()
        if (!visits.consumePending()) {
            scope.launch { preloadIfUseful() }
            return
        }
        pendingShowJob = scope.launch {
            // The destination is RESUMED once its transition has finished; the AR content leaves
            // composition in the same or the next frame. Wait for that, but only briefly.
            val arGone = withTimeoutOrNull(AR_EXIT_WAIT_MS) { arForeground.first { !it } } != null
            if (!arGone || !activity.isResumedNow()) return@launch
            maybeShowAfterSave(activity, NextAction.BACK) { scope.launch { preloadIfUseful() } }
        }
    }

    /** The non-AR screen paused (app switch, next screen): a pending chance is dropped, never deferred. */
    fun onNonArScreenPaused() {
        pendingShowJob?.cancel()
        pendingShowJob = null
    }

    private suspend fun preloadIfUseful() {
        if (visits.arInForeground || !_canShowAds.value) return
        val useful = try {
            policy.nextSaveMayShow() || policy.decide(NextAction.DONE) == AdDecision.SHOW
        } catch (e: Exception) {
            Log.w(TAG, "Ad counters unreadable: ${e.message}")
            false
        }
        if (useful) preloadInterstitial()
    }

    private fun Activity.isResumedNow(): Boolean =
        !isFinishing && !isDestroyed &&
            (this as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false

    /**
     * The **only** way an interstitial is shown. Shows the preloaded ad if [AdPolicy] allows it for
     * [nextAction]; otherwise does nothing. [onContinue] runs exactly once: after the ad closes, or
     * straight away when no ad is shown.
     *
     * Call only from a RESUMED non-AR screen after the user saved something and reached a natural
     * break (normally through [onNonArScreenResumed]). Never from a camera/AR screen, and never on
     * a plain exit without a save.
     */
    fun maybeShowAfterSave(
        activity: Activity,
        nextAction: NextAction = NextAction.DONE,
        onContinue: () -> Unit,
    ) {
        scope.launch {
            val screen = if (visits.arInForeground) AdScreen.CAMERA_AR else AdScreen.NON_CAMERA
            val decision = try {
                policy.decide(nextAction, screen)
            } catch (e: Exception) {
                Log.w(TAG, "Ad counters unreadable: ${e.message}")
                AdDecision.TOO_SOON
            }
            val ad = interstitial
            val ready = ad != null && !isStale() && !showing && _canShowAds.value && activity.isResumedNow()
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
                    scope.launch {
                        try {
                            policy.recordInterstitialShown()
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not record interstitial: ${e.message}")
                        }
                    }
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

        /** Longest wait for the AR destination to leave composition once the next screen is RESUMED. */
        private const val AR_EXIT_WAIT_MS = 1_500L

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
