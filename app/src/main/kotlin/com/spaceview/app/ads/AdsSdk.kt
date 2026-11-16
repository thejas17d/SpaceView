package com.spaceview.app.ads

import android.app.Activity
import android.content.Context
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

/**
 * The seam over the Mobile Ads SDK and UMP statics (ADR-011), so [AdsManager]'s gating can be tested
 * on the JVM. [GoogleAdsSdk] is the only implementation in the app. Main-thread API, except
 * [initialize], which [AdsManager] calls on a background thread.
 */
interface AdsSdk {
    /** UMP: consent (or no consent needed) allows ad requests. */
    fun canRequestAds(): Boolean

    /** UMP: the user must be offered a privacy-options entry point (e.g. EEA/UK). */
    fun privacyOptionsRequired(): Boolean

    /** UMP consent-info update, then the consent form if required. [onResult] runs once, on the main thread. */
    fun requestConsent(activity: Activity, onResult: (ConsentResult) -> Unit)

    /** UMP privacy-options form. [onDone] always runs. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit)

    /** `MobileAds.initialize` (no mediation, PG content, muted). [onReady] may run on any thread. */
    fun initialize(onReady: () -> Unit)

    /** Loads one interstitial; [onLoaded] gets null on failure. */
    fun loadInterstitial(unitId: String, onLoaded: (AdsInterstitial?) -> Unit)
}

enum class ConsentResult {
    /** The info was updated and any required form was handled. */
    UPDATED,

    /** Offline or misconfigured: the consent stored by an earlier launch still applies; retry next time. */
    FAILED,

    /** The activity finished while waiting; retry with the next activity. */
    ACTIVITY_GONE,
}

/** A loaded interstitial. */
interface AdsInterstitial {
    /** Shows it. [onShown] when it is on screen; [onClosed] exactly once, when dismissed or if it fails to show. */
    fun show(activity: Activity, onShown: () -> Unit, onClosed: () -> Unit)
}

/** [AdsSdk] backed by the Google Mobile Ads SDK and UMP. Touches nothing until a method is called. */
class GoogleAdsSdk(context: Context, private val log: (String) -> Unit = {}) : AdsSdk {
    private val appContext = context.applicationContext
    private val consent: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(appContext) }

    override fun canRequestAds(): Boolean = consent.canRequestAds()

    override fun privacyOptionsRequired(): Boolean =
        consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun requestConsent(activity: Activity, onResult: (ConsentResult) -> Unit) {
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    onResult(ConsentResult.ACTIVITY_GONE)
                    return@requestConsentInfoUpdate
                }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) log("Consent form: ${error.message}")
                    onResult(ConsentResult.UPDATED)
                }
            },
            { error ->
                log("Consent update failed: ${error.message}")
                onResult(ConsentResult.FAILED)
            },
        )
    }

    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) log("Privacy options: ${error.message}")
            onDone()
        }
    }

    override fun initialize(onReady: () -> Unit) {
        // No mediation in v1: skip adapter start-up work.
        MobileAds.disableMediationAdapterInitialization(appContext)
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                .build(),
        )
        MobileAds.initialize(appContext) {
            MobileAds.setAppMuted(true)
            onReady()
        }
    }

    override fun loadInterstitial(unitId: String, onLoaded: (AdsInterstitial?) -> Unit) {
        InterstitialAd.load(
            appContext,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) = onLoaded(GoogleInterstitial(ad, log))

                override fun onAdFailedToLoad(error: LoadAdError) {
                    log("Interstitial not loaded: ${error.code}")
                    onLoaded(null)
                }
            },
        )
    }

    private class GoogleInterstitial(private val ad: InterstitialAd, private val log: (String) -> Unit) : AdsInterstitial {
        override fun show(activity: Activity, onShown: () -> Unit, onClosed: () -> Unit) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() = onShown()

                override fun onAdDismissedFullScreenContent() = onClosed()

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    log("Interstitial failed to show: ${error.message}")
                    onClosed()
                }
            }
            ad.show(activity)
        }
    }
}
