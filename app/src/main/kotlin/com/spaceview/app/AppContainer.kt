package com.spaceview.app

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.spaceview.app.ads.AdPolicy
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.DataStoreAdCounterStore
import com.spaceview.app.ads.GoogleAdsSdk
import com.spaceview.app.billing.BillingGateway
import com.spaceview.app.billing.FakeBillingGateway
import com.spaceview.app.billing.FakeBillingStore
import com.spaceview.app.billing.PlayBillingGateway
import com.spaceview.app.billing.PurchaseVerifier
import com.spaceview.app.furniture.FurnitureTemplateRegistry
import com.spaceview.app.pro.DataStoreEntitlementStore
import com.spaceview.app.pro.EntitlementFeatureGate
import com.spaceview.app.pro.EntitlementRepository
import com.spaceview.app.pro.FeatureGate
import com.spaceview.app.pro.GallerySaveGate
import com.spaceview.app.pro.GatedGalleryStore
import com.spaceview.app.pro.RefreshReason
import com.spaceview.app.review.DataStoreReviewRecordStore
import com.spaceview.app.review.PlayReviewLauncher
import com.spaceview.app.review.ReviewPrompter
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.app.settings.UserDataEraser
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.data.settings.MeasureSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual dependency injection: one instance per process, held by [SpaceViewApplication].
 * Everything is lazy, so nothing (no database, no DataStore file, no billing or ads SDK) is opened at
 * app start. Billing, the entitlement and the ARCore version lookup start after the first frame
 * ([onFirstFrame]).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Process-lifetime scope for app-wide work (entitlement refreshes). Main-thread by default. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Measurement history (Room). */
    val history: MeasurementRepository by lazy { MeasurementRepository.create(appContext) }

    /**
     * AR Gallery (Room + app-private files), sharing the History database. Stale or missing furniture models are
     * rebuilt from their template id and parameters by [FurnitureTemplateRegistry] (M4 MINOR 11, gate T3).
     */
    val galleryRepository: GalleryRepository by lazy { GalleryRepository.create(appContext, templates = FurnitureTemplateRegistry) }

    /** Calibration, depth and safety-notice settings (`spaceview_measure` DataStore). */
    val measureSettings: MeasureSettingsRepository by lazy { MeasureSettingsRepository.create(appContext) }

    /** Units, sounds and onboarding (`spaceview_settings` DataStore, owned by the settings feature). */
    val appSettings: SettingsRepository get() = SettingsRepository.get(appContext)

    @Volatile private var arcoreVersionCache: String? = null

    /**
     * Installed Google Play Services for AR version name, for the canonical record of saved results
     * (null when unknown). Returns a cached value and never makes the Binder call itself (ARCHITECTURE-v2
     * O1: it used to run on main at every save). The cache is filled off the main thread after the
     * first frame and refreshed on every return to the foreground (ARCore can update in the background).
     */
    val arcoreVersion: () -> String? = { arcoreVersionCache }

    private fun refreshArcoreVersion() {
        appScope.launch(Dispatchers.IO) {
            arcoreVersionCache = runCatching {
                appContext.packageManager.getPackageInfo("com.google.ar.core", 0).versionName
            }.getOrNull()
        }
    }

    /** Wall clock, injectable for tests. */
    val clock: () -> Long = System::currentTimeMillis

    // ---- Pro (ARCHITECTURE-v2 §4, ADR-004/005/006/011) ----

    /**
     * Debug builds use [FakeBillingGateway] unless built with `-PFAKE_BILLING=false`; release builds always
     * use Play (`BuildConfig.FAKE_BILLING` is a constant false there, so the fake is stripped by R8).
     */
    val billing: BillingGateway by lazy {
        if (BuildConfig.FAKE_BILLING) {
            // 1024-bit: generated on first use in debug builds only; key strength is irrelevant for a fake.
            FakeBillingGateway(FakeBillingGateway.generateKeyPair(1024), packageName = appContext.packageName)
                .also { FakeBillingStore(appContext).attach(it) }
        } else {
            PlayBillingGateway(appContext)
        }
    }

    /**
     * The Play licence key, parsed once on first use (the first purchase check, after the first frame), never
     * in `onCreate` (P1 review MINOR 6). Release builds validate it at build time (app/build.gradle.kts).
     */
    private val licenceKey: PublicKey? by lazy {
        PurchaseVerifier.publicKey((billing as? FakeBillingGateway)?.publicKeyBase64 ?: BuildConfig.PLAY_LICENSE_KEY)
    }

    /** The single source of truth for Pro. Started by [onFirstFrame]. */
    val entitlements: EntitlementRepository by lazy {
        EntitlementRepository(
            store = DataStoreEntitlementStore.get(appContext),
            billing = billing,
            // Play's signature with our key, for our package only.
            verifier = PurchaseVerifier.forKey({ licenceKey }, appContext.packageName),
            clock = clock,
            scope = appScope,
            io = Dispatchers.IO,
            // Without a valid licence key nothing could be verified, so nothing is sold (fails closed).
            purchasesAllowed = { licenceKey != null },
            log = { Log.w("Entitlements", it) },
        )
    }

    /** Contract K1: features ask this, never billing. */
    val featureGate: FeatureGate by lazy {
        // N2: a cold-start "needs Pro" from a stale cache waits (≤ 5 s) for Play to settle before it stands.
        EntitlementFeatureGate(entitlements.entitlement, settled = entitlements.settled)
    }

    /**
     * The Free gallery cap (20) and 3D-scan quota (5 in total), checked before a scan starts and again at Save (P1
     * review MAJOR 3). Every new gallery item goes through it: scans, templates (`saveTemplate`) and
     * on-device meshes (`saveMesh`). The 3D count is the lifetime counter, never below the saved MESH_DEVICE rows;
     * the D save path must call `SettingsRepository.recordDeviceScanSaved()` after each successful `saveMesh`.
     */
    val gallerySaveGate: GallerySaveGate by lazy {
        GallerySaveGate(
            gate = featureGate,
            galleryCount = { galleryRepository.count() },
            deviceScanCount = { maxOf(appSettings.deviceScansUsed(), galleryRepository.countDeviceScans()) },
        )
    }

    /**
     * The single gated insert path for templates (gate §8 T2): the template gate (core templates Free, the rest Pro),
     * then the Free gallery cap, before `GalleryRepository.saveTemplate`.
     */
    val gatedGalleryStore: GatedGalleryStore by lazy {
        GatedGalleryStore(featureGate = featureGate, saveGate = gallerySaveGate, saveTemplateTo = { galleryRepository.saveTemplate(it) })
    }

    /** Ads, gated by the entitlement (ADR-011). Touches no ads SDK until started. */
    val ads: AdsManager by lazy {
        AdsManager(
            policy = AdPolicy(DataStoreAdCounterStore.get(appContext)),
            sdk = GoogleAdsSdk(appContext) { Log.w("AdsSdk", it) },
            entitlement = entitlements.entitlement,
            settled = entitlements.settled,
            // Every successful save also counts toward the review prompt's "3rd save" moment.
            onSaveCompleted = { reviewPrompter.onSaveCompleted() },
        )
    }

    /**
     * The Play in-app review prompt (M5): after a positive moment, on a non-AR screen, never next to an
     * interstitial, at most once per 90 days. Touches no Play API until it actually asks.
     */
    val reviewPrompter: ReviewPrompter by lazy {
        ReviewPrompter(
            store = DataStoreReviewRecordStore.get(appContext),
            launcher = PlayReviewLauncher(appContext) { Log.w("Review", it) },
            arInForeground = { ads.arInForeground },
            adBusy = { ads.interstitialBusy },
            lastInterstitialAtMillis = { DataStoreAdCounterStore.get(appContext).read().lastInterstitialAtMillis },
            isResumed = AdsManager.ActivityState.Platform::isResumed,
            clock = clock,
        )
    }

    /**
     * Settings → Delete my data (M5): History, Gallery (with its share and Place caches), calibration and measuring settings, app settings.
     * Never the Play subscription or the cached entitlement.
     */
    val userDataEraser: UserDataEraser by lazy {
        UserDataEraser(
            history = history,
            gallery = galleryRepository,
            measureSettings = measureSettings,
            appSettings = appSettings,
        )
    }

    private val firstFrameDone = AtomicBoolean(false)

    /**
     * Call on the main thread just after the first frame (MainActivity). Starts the entitlement (cache,
     * then Play), a throttled refresh on every return to the foreground, and the ARCore version lookup.
     */
    fun onFirstFrame() {
        if (!firstFrameDone.compareAndSet(false, true)) return
        entitlements.start()
        refreshArcoreVersion()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    appScope.launch { entitlements.refresh(RefreshReason.FOREGROUND) }
                    refreshArcoreVersion()
                }
            },
        )
    }
}

/** The process-wide container. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SpaceViewApplication).container
