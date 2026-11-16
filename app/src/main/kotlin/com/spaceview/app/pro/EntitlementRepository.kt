package com.spaceview.app.pro

import android.app.Activity
import com.spaceview.app.billing.BillingCode
import com.spaceview.app.billing.BillingGateway
import com.spaceview.app.billing.LaunchResult
import com.spaceview.app.billing.OffersResult
import com.spaceview.app.billing.OwnedPurchase
import com.spaceview.app.billing.ProductOffer
import com.spaceview.app.billing.PurchaseUpdate
import com.spaceview.app.billing.QueryResult
import com.spaceview.app.billing.TokenHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class RefreshReason { APP_START, FOREGROUND, PAYWALL, RESTORE_BUTTON }

/** What a refresh found; drives the Restore button's message. */
sealed interface RefreshOutcome {
    data object ProActive : RefreshOutcome

    data object Pending : RefreshOutcome

    /** Play answered: no active Pro purchase on this Google account. */
    data object NothingFound : RefreshOutcome

    /** Play could not be asked; the cached entitlement (with grace) still applies. */
    data class Failed(val code: BillingCode) : RefreshOutcome

    /** A foreground refresh within 10 minutes of the last one. */
    data object Throttled : RefreshOutcome
}

sealed interface PurchaseOutcome {
    data object Purchased : PurchaseOutcome

    /** Play accepted a payment that is not complete yet (UPI, cash). Pro unlocks when it completes. */
    data object Pending : PurchaseOutcome

    /** The user closed Play's sheet. Silent. */
    data object Cancelled : PurchaseOutcome

    /** Play says the account already owns Pro; a restore ran. [restored] is true if Pro is now active. */
    data class AlreadyOwned(val restored: Boolean) : PurchaseOutcome

    /** This build has no licence key, so no purchase could ever be verified: nothing is sold. */
    data object NotConfigured : PurchaseOutcome

    data class Failed(val code: BillingCode) : PurchaseOutcome
}

/**
 * The single source of truth for Pro (ARCHITECTURE-v2 S3, §4.4, §4.7). Billing writes to it; ads, gates,
 * Settings and the paywall read [entitlement].
 *
 * - Cold start: [entitlement] is [Entitlement.Unknown] until the cache has been read (a cached Pro shows
 *   at once) or Play has answered, and never longer than [unknownTimeoutMs]: then the cache decides.
 * - One [Mutex] serialises refresh, purchase results and acknowledgement (check-then-grant is a race).
 * - Grants are idempotent by token hash; process death mid-purchase is recovered by the next query.
 * - Never logs a purchase token or order id.
 *
 * @param verifier the signature + package check ([com.spaceview.app.billing.PurchaseVerifier.forKey]).
 * @param purchasesAllowed false when the build has no valid licence key: purchases are refused (a purchase
 *   that can't be verified would be charged, never granted, and refunded by Play after 3 days). Evaluated
 *   lazily, so the RSA key is not parsed before the first frame (P1 review MINOR 6).
 */
class EntitlementRepository(
    private val store: EntitlementStore,
    private val billing: BillingGateway,
    private val verifier: (OwnedPurchase) -> Boolean,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val purchasesAllowed: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val unknownTimeoutMs: Long = UNKNOWN_TIMEOUT_MS,
    private val foregroundThrottleMs: Long = FOREGROUND_THROTTLE_MS,
) {
    private val _entitlement = MutableStateFlow<Entitlement>(Entitlement.Unknown)

    /** The current entitlement. Starts [Entitlement.Unknown]; see [start]. */
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private var cacheLoaded = false
    @Volatile private var cached: CachedEntitlement? = null
    private var lastQueryOkAt: Long? = null
    private val acknowledgedHashes = mutableSetOf<String>()
    private val purchaseEvents = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 8)

    /** Hashes of every purchase token seen so far, to tell a new flow's result from an older one's (MINOR 8). */
    private val seenTokenHashes = mutableSetOf<String>()

    /** A finished purchase flow and the (hashed) tokens it reported. */
    private class PurchaseEvent(val outcome: PurchaseOutcome, val tokenHashes: Set<String>)

    private val _settled = MutableStateFlow(false)

    /**
     * True once the first start-up check with Play has finished (answered, or failed and fallen back to the
     * cache). Ads wait for this, not just for "not Unknown", so a Pro user whose cache is stale or missing
     * (8 days away, reinstall, new phone) never gets UMP or `MobileAds.initialize` while Play is still
     * answering (P1 review MAJOR 1). Gates don't wait for it: Unknown already gates as Free.
     */
    val settled: StateFlow<Boolean> = _settled.asStateFlow()

    /** False when this build has no valid licence key: nothing is sold. */
    val purchasesEnabled: Boolean get() = purchasesAllowed()

    /** Signature failures seen in this process (non-PII; for a future analytics event). */
    @Volatile var rejectedSignatures: Int = 0
        private set

    /**
     * Call once, after the first frame (never in `Application.onCreate`). Reads the cache, listens for
     * purchase results, and asks Play.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Listen before anything can launch a purchase, so no result is missed.
        scope.launch { billing.updates.collect { onUpdate(it) } }
        val timeout = scope.launch {
            delay(unknownTimeoutMs)
            // Not under the mutex: a slow first Play connection holds it, and this must fire on time.
            _entitlement.compareAndSet(Entitlement.Unknown, EntitlementReducer.effective(cached, clock()))
        }
        scope.launch {
            mutex.withLock {
                ensureCacheLoaded()
                // A cached Pro (within grace) shows at once, so a Pro user never sees an ad flash.
                val fromCache = EntitlementReducer.effective(cached, clock())
                if (fromCache is Entitlement.Pro && _entitlement.value is Entitlement.Unknown) publish(fromCache)
            }
            try {
                refresh(RefreshReason.APP_START)
            } finally {
                timeout.cancel()
                _settled.value = true
            }
        }
    }

    /** Asks Play for the current purchases. Foreground refreshes are throttled to one per 10 minutes. */
    suspend fun refresh(reason: RefreshReason): RefreshOutcome = mutex.withLock {
        ensureCacheLoaded()
        val now = clock()
        val last = lastQueryOkAt
        if (reason == RefreshReason.FOREGROUND && last != null && now - last in 0 until foregroundThrottleMs) {
            return@withLock RefreshOutcome.Throttled
        }
        val result = billing.queryActive()
        if (result is QueryResult.Ok) {
            lastQueryOkAt = now
            result.purchases.forEach { seenTokenHashes += TokenHash.of(it.purchaseToken) }
        }
        val reduced = EntitlementReducer.onQuery(cached, result, now, verifier)
        apply(reduced)
        when {
            result is QueryResult.Failed -> {
                log("Play query failed (${reason.name}): ${result.code}")
                RefreshOutcome.Failed(result.code)
            }
            reduced.live is Entitlement.Pro -> RefreshOutcome.ProActive
            reduced.live is Entitlement.Pending -> RefreshOutcome.Pending
            else -> RefreshOutcome.NothingFound
        }
    }

    /** The offers to show on the paywall (prices from Play), yearly first. */
    suspend fun offers(): OffersResult =
        when (val r = billing.offers(ProCatalog.SUBSCRIPTION_ID)) {
            is OffersResult.Failed -> r
            is OffersResult.Ok -> OffersResult.Ok(OfferSelector.select(r.offers))
        }

    /**
     * Runs Play's purchase flow for [offer] and waits for its result. If the process dies meanwhile, the
     * purchase is picked up by the next start's query (and acknowledged then).
     */
    suspend fun purchase(activity: Activity, offer: ProductOffer): PurchaseOutcome {
        if (!purchasesEnabled) return PurchaseOutcome.NotConfigured
        val before = mutex.withLock { seenTokenHashes.toSet() }
        return coroutineScope {
            // Subscribe before launching so the result can't arrive first. Only this flow's result counts:
            // one with no purchases (cancel, error) or with a token not seen before. A late update for an
            // older purchase (e.g. a pending one approved now) is applied but doesn't answer this flow.
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                purchaseEvents.first { ev -> ev.tokenHashes.isEmpty() || ev.tokenHashes.any { it !in before } }.outcome
            }
            when (val launch = billing.launch(activity, offer)) {
                LaunchResult.Launched -> result.await()
                is LaunchResult.Failed -> {
                    result.cancel()
                    if (launch.code == BillingCode.ITEM_ALREADY_OWNED) {
                        restoreAfterAlreadyOwned()
                    } else {
                        if (launch.code != BillingCode.USER_CANCELED) log("Launch failed: ${launch.code}")
                        if (launch.code == BillingCode.USER_CANCELED) PurchaseOutcome.Cancelled else PurchaseOutcome.Failed(launch.code)
                    }
                }
            }
        }
    }

    private suspend fun onUpdate(update: PurchaseUpdate) {
        val hashes = update.purchases.map { TokenHash.of(it.purchaseToken) }.toSet()
        val outcome = when (update.code) {
            BillingCode.OK -> mutex.withLock {
                seenTokenHashes += hashes
                val r = EntitlementReducer.onPurchaseUpdate(cached, _entitlement.value, update.purchases, clock(), verifier)
                apply(r)
                when {
                    r.live is Entitlement.Pro -> PurchaseOutcome.Purchased
                    r.live is Entitlement.Pending -> PurchaseOutcome.Pending
                    // Nothing verifiable in the update (e.g. a bad signature): never grant.
                    else -> PurchaseOutcome.Failed(BillingCode.ERROR)
                }
            }
            BillingCode.USER_CANCELED -> PurchaseOutcome.Cancelled
            BillingCode.ITEM_ALREADY_OWNED -> restoreAfterAlreadyOwned()
            else -> {
                log("Purchase failed: ${update.code}")
                PurchaseOutcome.Failed(update.code)
            }
        }
        purchaseEvents.emit(PurchaseEvent(outcome, hashes))
    }

    private suspend fun restoreAfterAlreadyOwned(): PurchaseOutcome.AlreadyOwned {
        val r = refresh(RefreshReason.RESTORE_BUTTON)
        return PurchaseOutcome.AlreadyOwned(restored = r == RefreshOutcome.ProActive)
    }

    /** Call with [mutex] held. */
    private suspend fun ensureCacheLoaded() {
        if (cacheLoaded) return
        cached = try {
            withContext(io) { store.read() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Entitlement cache unreadable: ${e.javaClass.simpleName}")
            null
        }
        cacheLoaded = true
    }

    /** Call with [mutex] held. */
    private suspend fun apply(r: EntitlementReducer.Result) {
        if (r.rejectedSignatures > 0) {
            rejectedSignatures += r.rejectedSignatures
            log("Ignored ${r.rejectedSignatures} purchase(s) with an invalid signature")
        }
        persist(r.cached)
        publish(r.live)
        acknowledge(r.toAcknowledge)
    }

    private fun publish(e: Entitlement) {
        _entitlement.value = e
    }

    private suspend fun persist(value: CachedEntitlement?) {
        if (value == cached) return
        cached = value
        try {
            withContext(io) { store.write(value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Disk full or similar: the in-memory state is still right; the next confirmation rewrites it.
            log("Entitlement cache not written: ${e.javaClass.simpleName}")
        }
    }

    /** Acknowledges once per token per process; a failure is retried at the next refresh (every start). */
    private suspend fun acknowledge(purchases: List<OwnedPurchase>) {
        for (p in purchases) {
            val hash = TokenHash.of(p.purchaseToken)
            if (hash in acknowledgedHashes) continue
            val code = billing.acknowledge(p.purchaseToken)
            if (code == BillingCode.OK) {
                acknowledgedHashes += hash
                cached?.takeIf { it.tokenHash == hash && !it.acknowledged }?.let { persist(it.copy(acknowledged = true)) }
            } else {
                log("Acknowledge failed: $code (retried at next start)")
            }
        }
    }

    companion object {
        /** Longest time [entitlement] stays Unknown after [start] (ARCHITECTURE-v2 §4.5 rule 5). */
        const val UNKNOWN_TIMEOUT_MS = 1_000L

        /** At most one foreground refresh per 10 minutes (§4.7). */
        const val FOREGROUND_THROTTLE_MS = 10L * 60 * 1000
    }
}
