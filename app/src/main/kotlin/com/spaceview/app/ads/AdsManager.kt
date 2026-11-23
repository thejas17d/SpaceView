package com.spaceview.app.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.spaceview.app.BuildConfig
import com.spaceview.app.appContainer
import com.spaceview.app.pro.Entitlement
import kotlinx.coroutines.CoroutineDispatcher
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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns consent (UMP), Mobile Ads start-up and the one interstitial. One instance per process, owned by
 * [com.spaceview.app.AppContainer] (ADR-011); [get] returns it.
 *
 * **Pro (ARCHITECTURE-v2 §4.6, fixes R4a).** Ads are switched off at the source:
 * - nothing starts until the entitlement has **settled**: the first start-up check with Play has
 *   finished (answered, or failed and fell back to the cache), capped at [SETTLE_WAIT_MS]. A 1 s
 *   "not Unknown" wait is not enough: a stale or missing cache reads as Free while Play is still
 *   answering, and AdMob can't be de-initialised once a Pro answer arrives (P1 review MAJOR 1);
 * - for Pro, UMP and `MobileAds.initialize` never run, and [canShowAds] stays false, so every
 *   [BannerAd] disappears and no interstitial is loaded or shown;
 * - becoming Pro mid-session drops the loaded interstitial and any pending chance (the SDK can't be
 *   de-initialised, but no further request goes out);
 * - a lapse mid-session runs the normal start path (consent before the first request).
 *
 * Start-up order (docs/research/android-performance.md §5.2): first frame ([startAfterFirstFrame]) →
 * entitlement known → UMP `requestConsentInfoUpdate` (once per process; the form only where required)
 * → when `canRequestAds()`, `MobileAds.initialize` on a background thread, once per process.
 *
 * Placement (docs/research/ui-ux.md §5): banners only on non-camera screens ([BannerAd]); one
 * preloaded interstitial, shown **only** through [maybeShowAfterSave] and only when [AdPolicy]
 * allows it. If no ad is ready, nothing is shown and nothing waits.
 *
 * Interstitial flow after measuring ([MeasureVisitTracker], M2 review M6; contract K2):
 *  1. navigation calls [onMeasureEntered] / [onMeasureLeaving] around each Measure or Scan visit and
 *     [onArScreenEntered] / [onArScreenLeft] around every AR/camera destination (Place included);
 *  2. [recordCompletedScan] marks the current visit as "saved" (never called from Place);
 *  3. leaving a visit with a save arms one pending chance; leaving without a save, or towards Place
 *     ([onVisitLeftWithoutAd]), never does;
 *  4. the next non-AR screen calls [onNonArScreenResumed] once it is RESUMED; that waits until no AR
 *     screen is composed, then asks [AdPolicy]. The ad therefore never opens over the camera.
 *
 * Main-thread API: call everything from the main thread.
 */
class AdsManager(
    val policy: AdPolicy,
    private val sdk: AdsSdk,
    private val entitlement: StateFlow<Entitlement>,
    /** True once the entitlement has settled ([com.spaceview.app.pro.EntitlementRepository.settled]). */
    private val settled: StateFlow<Boolean>,
    private val interstitialUnitId: String = BuildConfig.AD_INTERSTITIAL,
    /** Ad unit for [BannerAd]. */
    val bannerUnitId: String = BuildConfig.AD_BANNER,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val background: CoroutineDispatcher = Dispatchers.IO,
    private val activityState: ActivityState = ActivityState.Platform,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    private val settleWaitMs: Long = SETTLE_WAIT_MS,
    /** Called once per successful save, after [recordCompletedScan] (the in-app review prompt counts saves). */
    private val onSaveCompleted: () -> Unit = {},
) {
    /** Lifecycle questions about an [Activity], injectable for JVM tests. */
    interface ActivityState {
        fun isAlive(activity: Activity): Boolean

        fun isResumed(activity: Activity): Boolean

        object Platform : ActivityState {
            override fun isAlive(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed

            override fun isResumed(activity: Activity): Boolean =
                isAlive(activity) &&
                    (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val sdkStarted = AtomicBoolean(false)
    private val consentRequested = AtomicBoolean(false)
    private val startScheduled = AtomicBoolean(false)
    @Volatile private var sdkInitialised = false

    /** The entitlement is known and is not Pro. False while Unknown and for Pro. */
    private var adsAllowed = false
    private var entitlementJob: Job? = null
    private var host: WeakReference<Activity>? = null

    private val _canShowAds = MutableStateFlow(false)

    /** True once the user isn't Pro, consent allows ad requests and the SDK has finished initialising. */
    val canShowAds: StateFlow<Boolean> = _canShowAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)

    /** True when UMP says the user must be offered a privacy-options entry point (e.g. EEA/UK). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var interstitial: AdsInterstitial? = null
    private var interstitialLoadedAt = 0L
    private var interstitialLoading = false
    private var showing = false

    /** [maybeShowAfterSave] calls still deciding (the counters are read asynchronously) or showing. */
    private var afterSaveInFlight = 0

    /** "Saved this visit", the pending after-measure chance and AR-foreground tracking. */
    private val visits = MeasureVisitTracker(clock = clock)
    private val arForeground = MutableStateFlow(false)
    private var pendingShowJob: Job? = null

    /** True while an AR/camera destination is composed (the review prompt never asks then). */
    val arInForeground: Boolean get() = visits.arInForeground

    /**
     * True while an after-save interstitial is owed for the current resume, being decided, or on screen. The
     * in-app review prompt waits for it ("never right after an interstitial").
     */
    val interstitialBusy: Boolean get() = pendingShowJob?.isActive == true || afterSaveInFlight > 0 || showing

    /**
     * Call from `MainActivity.onCreate` (after `setContent`). Defers all ads work until just after the
     * first frame has been drawn, so ads never cost cold-start time.
     */
    fun startAfterFirstFrame(activity: ComponentActivity) {
        if (!startScheduled.compareAndSet(false, true)) {
            start(activity)
            return
        }
        activity.window.decorView.doOnPreDraw { view ->
            // doOnPreDraw runs just before the first draw; post() lands just after it.
            view.post { if (activityState.isAlive(activity)) start(activity) }
        }
    }

    /**
     * After the first frame: waits (≤ [settleWaitMs]) for the entitlement to settle, then follows it. Ads
     * start only for non-Pro users; a later change of the entitlement switches ads off or on. A Free user's
     * first ad comes a moment later; ads have no cold-start urgency.
     */
    fun start(activity: Activity) {
        host = WeakReference(activity)
        if (entitlementJob != null) {
            if (adsAllowed) gatherConsent(activity)
            return
        }
        entitlementJob = scope.launch {
            withTimeoutOrNull(settleWaitMs) { settled.first { it } }
            // Settled, or Play never answered within the cap: follow what is known; still Unknown = Free
            // (§4.5 rule 5). A later Pro value still switches ads off.
            onEntitlement(entitlement.value.takeUnless { it is Entitlement.Unknown } ?: Entitlement.Free)
            entitlement.collect { if (it !is Entitlement.Unknown) onEntitlement(it) }
        }
    }

    private fun onEntitlement(e: Entitlement) {
        val allowed = e !is Entitlement.Pro
        if (allowed == adsAllowed) return
        adsAllowed = allowed
        if (allowed) {
            host?.get()?.takeIf { activityState.isAlive(it) }?.let { gatherConsent(it) } ?: startSdkIfAllowed()
        } else {
            // Pro: no further ad requests. A loaded ad and a pending after-save chance are dropped.
            interstitial = null
            pendingShowJob?.cancel()
            pendingShowJob = null
        }
        updateCanShowAds()
    }

    /**
     * UMP consent: once per process, and only when ads are allowed. Shows a form only where required.
     *
     * Never while an AR/camera screen is composed (SM1-M5 review MINOR 10): the form is a dialog over the activity,
     * so the AR session (and hands-free point drops) would keep running behind it. A request skipped for that reason,
     * or one that failed (offline), is retried by the next non-AR screen to resume ([onNonArScreenResumed]).
     */
    fun gatherConsent(activity: Activity) {
        if (!adsAllowed) return
        // A previous launch may already allow requests: start without waiting for the network.
        startSdkIfAllowed()
        if (visits.arInForeground) return
        if (!consentRequested.compareAndSet(false, true)) return
        // The AR check is repeated when the update arrives, right before the form (gate G1).
        sdk.requestConsent(activity, canShowForm = { !visits.arInForeground }) { result ->
            // Offline, misconfigured, the activity finished, or AR came to the front (DEFERRED): ask again on the
            // next non-AR resume.
            if (result != ConsentResult.UPDATED) consentRequested.set(false)
            updatePrivacyOptionsRequired()
            startSdkIfAllowed()
        }
    }

    /** Opens the UMP privacy-options form (Settings → Privacy options). [onDone] always runs. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        sdk.showPrivacyOptions(activity) {
            updatePrivacyOptionsRequired()
            // The user may have withdrawn consent; requests stay governed by canRequestAds().
            if (!sdk.canRequestAds()) interstitial = null else startSdkIfAllowed()
            updateCanShowAds()
            onDone()
        }
    }

    private fun updatePrivacyOptionsRequired() {
        _privacyOptionsRequired.value = sdk.privacyOptionsRequired()
    }

    private fun updateCanShowAds() {
        _canShowAds.value = adsAllowed && sdkInitialised && sdk.canRequestAds()
    }

    private fun startSdkIfAllowed() {
        if (!adsAllowed || !sdk.canRequestAds()) return
        if (!sdkStarted.compareAndSet(false, true)) {
            updateCanShowAds()
            return
        }
        scope.launch {
            withContext(background) {
                sdk.initialize {
                    scope.launch {
                        sdkInitialised = true
                        updateCanShowAds()
                        if (_canShowAds.value && policy.nextSaveMayShow()) preloadInterstitial()
                    }
                }
            }
        }
    }

    /**
     * Loads one interstitial if none is loaded or loading. Does nothing for Pro, or while an AR/camera
     * screen is in the foreground (the ads WebView and network work would compete with tracking); the
     * next non-AR screen to resume preloads instead ([onNonArScreenResumed]).
     */
    fun preloadInterstitial() {
        if (!_canShowAds.value || interstitialLoading || visits.arInForeground) return
        if (interstitial != null && !isStale()) return
        interstitial = null
        interstitialLoading = true
        sdk.loadInterstitial(interstitialUnitId) { ad ->
            scope.launch {
                interstitialLoading = false
                // Pro may have started while it loaded: never keep an ad for a Pro user.
                interstitial = ad?.takeIf { adsAllowed }
                interstitialLoadedAt = clock()
            }
        }
    }

    /** Loaded interstitials expire after an hour; refresh a little earlier. */
    private fun isStale(): Boolean = clock() - interstitialLoadedAt > MAX_AD_AGE_MS

    /**
     * Call exactly once when a scan or measurement has been saved successfully. Counts toward the
     * frequency cap and marks the current Measure visit as "saved". Never preloads: it runs on the
     * AR screen. Never call it from Place (contract K2).
     */
    fun recordCompletedScan() {
        visits.onScanSaved()
        onSaveCompleted()
        scope.launch {
            try {
                policy.recordCompletedScan()
            } catch (e: Exception) {
                // Disk full or a corrupt counter file must not crash measuring; the cap just under-counts.
                log("Could not record scan: ${e.message}")
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
        if (BuildConfig.DEBUG) log("Leaving measure, saved this visit=$eligible")
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
        // A natural break: retry a consent request that was deferred (AR in front) or failed (offline). No-op once
        // resolved, and for Pro.
        if (adsAllowed && entitlementJob != null) gatherConsent(activity)
        pendingShowJob?.cancel()
        if (!visits.consumePending()) {
            scope.launch { preloadIfUseful() }
            return
        }
        if (!adsAllowed) return
        pendingShowJob = scope.launch {
            // The destination is RESUMED once its transition has finished; the AR content leaves
            // composition in the same or the next frame. Wait for that, but only briefly.
            val arGone = withTimeoutOrNull(AR_EXIT_WAIT_MS) { arForeground.first { !it } } != null
            if (!arGone || !activityState.isResumed(activity)) return@launch
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
            log("Ad counters unreadable: ${e.message}")
            false
        }
        if (useful) preloadInterstitial()
    }

    /**
     * The **only** way an interstitial is shown. Shows the preloaded ad if the user isn't Pro and
     * [AdPolicy] allows it for [nextAction]; otherwise does nothing. [onContinue] runs exactly once:
     * after the ad closes, or straight away when no ad is shown.
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
        afterSaveInFlight++
        val release = AtomicBoolean(false)
        val done: () -> Unit = { if (release.compareAndSet(false, true)) afterSaveInFlight-- }
        scope.launch {
            val screen = if (visits.arInForeground) AdScreen.CAMERA_AR else AdScreen.NON_CAMERA
            val decision = try {
                policy.decide(nextAction, screen)
            } catch (e: Exception) {
                log("Ad counters unreadable: ${e.message}")
                AdDecision.TOO_SOON
            }
            val ad = interstitial
            val ready = ad != null && adsAllowed && !isStale() && !showing && _canShowAds.value && activityState.isResumed(activity)
            if (decision != AdDecision.SHOW || !ready) {
                if (BuildConfig.DEBUG) log("No interstitial: $decision, ready=$ready")
                done()
                onContinue()
                return@launch
            }
            interstitial = null
            showing = true
            val finished = AtomicBoolean(false)
            ad.show(
                activity,
                onShown = {
                    scope.launch {
                        try {
                            policy.recordInterstitialShown()
                        } catch (e: Exception) {
                            log("Could not record interstitial: ${e.message}")
                        }
                    }
                },
                onClosed = {
                    if (finished.compareAndSet(false, true)) {
                        showing = false
                        done()
                        onContinue()
                    }
                },
            )
        }.invokeOnCompletion { cause -> if (cause != null) done() } // never leave the review prompt blocked
    }

    companion object {
        private const val TAG = "AdsManager"
        private const val MAX_AD_AGE_MS = 55L * 60 * 1000

        /** Longest wait for the AR destination to leave composition once the next screen is RESUMED. */
        private const val AR_EXIT_WAIT_MS = 1_500L

        /**
         * Longest wait for the entitlement to settle before ads follow whatever is known. Above the billing
         * connect timeout (10 s) plus a query, so a slow but working Play always answers first.
         */
        const val SETTLE_WAIT_MS = 12_000L

        /** The app-wide instance, owned by the container. Cheap: touches no ads SDK until [start]. */
        fun get(context: Context): AdsManager = context.appContainer.ads
    }
}
