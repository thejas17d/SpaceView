package com.spaceview.app.pro

import com.spaceview.app.billing.OwnedPurchase
import com.spaceview.app.billing.PurchaseState
import com.spaceview.app.billing.QueryResult
import com.spaceview.app.billing.TokenHash

/** What the user is entitled to right now. Read it from [EntitlementRepository.entitlement]. */
sealed interface Entitlement {
    /** Before the cache has been read (cold start, at most ~1 s). Gates as Free; suppresses ads. */
    data object Unknown : Entitlement

    data object Free : Entitlement

    /**
     * @property productId the Play product (v1: [ProCatalog.SUBSCRIPTION_ID]). Play's purchase does not
     *   say which base plan was bought, so no base plan is claimed here.
     * @property autoRenewing false for the prepaid pass and for a cancelled subscription that still runs.
     * @property confirmedAtMillis wall clock of the last successful Play check.
     */
    data class Pro(
        val productId: String,
        val autoRenewing: Boolean,
        val confirmedAtMillis: Long,
        val source: Source,
    ) : Entitlement {
        enum class Source {
            /** Confirmed by Play in this process. */
            PLAY_LIVE,

            /** From the cache, inside the 7-day offline grace (Play could not be reached). */
            CACHE_GRACE,
        }
    }

    /** A payment is pending (UPI, cash, slow card). No Pro features until Play confirms it. */
    data class Pending(val productId: String) : Entitlement
}

val Entitlement.isPro: Boolean get() = this is Entitlement.Pro

/**
 * The persisted record of the last confirmed Pro purchase (DataStore file `spaceview_entitlement`,
 * excluded from backup and device transfer). It holds no secret: only a SHA-256 of the token.
 *
 * shortcut: not encrypted. It cannot grant anything Play doesn't confirm within 7 days, and a
 * root/patch attacker can bypass the check anyway (ARCHITECTURE-v2 §4.4). Revisit with server checks.
 */
data class CachedEntitlement(
    val productId: String,
    val tokenHash: String,
    val confirmedAtMillis: Long,
    val autoRenewing: Boolean,
    val acknowledged: Boolean,
    val catalogVersion: Int,
    /**
     * The latest wall-clock time the app has seen while this record was in force (a high-water mark).
     * The offline grace is measured against it, so winding the clock back cannot extend the grace.
     */
    val lastSeenMillis: Long = confirmedAtMillis,
)

/**
 * The entitlement state machine (ARCHITECTURE-v2 §4.4). Pure: no Android, no I/O, no clock of its own.
 * Signature checking is injected as `isValid` so the reducer never grants on an unverified purchase.
 */
object EntitlementReducer {

    /**
     * @property cached what to persist (null = no Pro on record).
     * @property live the entitlement to publish.
     * @property toAcknowledge verified, purchased, unacknowledged purchases (acknowledge, retry on every start).
     * @property rejectedSignatures purchases of our products that failed the signature check (a non-PII counter).
     */
    data class Result(
        val cached: CachedEntitlement?,
        val live: Entitlement,
        val toAcknowledge: List<OwnedPurchase> = emptyList(),
        val rejectedSignatures: Int = 0,
    )

    /**
     * After a full query of the user's purchases ([QueryResult.Ok] is authoritative: an expired,
     * refunded, revoked or on-hold subscription is simply absent, so it means Free right away), or a
     * failed query (then the cache applies, with the offline grace).
     */
    fun onQuery(prev: CachedEntitlement?, result: QueryResult, now: Long, isValid: (OwnedPurchase) -> Boolean): Result =
        when (result) {
            // Offline: the cache decides, and the high-water mark moves forward (never back).
            is QueryResult.Failed -> Result(seen(prev, now), effective(prev, now))
            is QueryResult.Ok -> fromPurchases(prev, result.purchases, now, isValid)
        }

    /**
     * After a purchase flow finished with purchases. Unlike a query, an update only lists the purchases of
     * that flow, so it can grant but never takes Pro away: a pending or invalid update keeps [current].
     */
    fun onPurchaseUpdate(
        prev: CachedEntitlement?,
        current: Entitlement,
        purchases: List<OwnedPurchase>,
        now: Long,
        isValid: (OwnedPurchase) -> Boolean,
    ): Result {
        val r = fromPurchases(prev, purchases, now, isValid)
        return when {
            r.live is Entitlement.Pro -> r
            current is Entitlement.Pro -> Result(prev, current, rejectedSignatures = r.rejectedSignatures)
            r.live is Entitlement.Pending -> Result(prev, r.live, rejectedSignatures = r.rejectedSignatures)
            else -> Result(prev, if (current is Entitlement.Unknown) effective(prev, now) else current, rejectedSignatures = r.rejectedSignatures)
        }
    }

    /**
     * The entitlement the cache allows on its own (no live answer from Play): Pro while the grace has not
     * run out, else Free.
     *
     * Clock safety (P1 review MAJOR 2). The grace is measured in *app-observed* time:
     * `age = max(now, lastSeen) − confirmedAt`, where `lastSeen` is the persisted high-water mark of every
     * clock reading taken while offline ([seen]). So:
     * - a forward jump only shortens the grace;
     * - a clock more than [ProCatalog.CLOCK_SKEW_TOLERANCE_MS] behind `lastSeen` (or behind the
     *   confirmation) means it was wound back: the cache is not trusted (Free until Play confirms again);
     * - winding back to a point *inside* the window doesn't help either: the age never goes below
     *   `lastSeen − confirmedAt`, and repeated rollbacks keep failing the `lastSeen` check.
     * The grace is therefore bounded by 7 days of time the app has actually observed. Not covered: a user
     * who never lets the app see the later time (e.g. rolls back before every launch, offline the whole time,
     * with the Play Store disabled) still sees at most `lastSeen` advance by real use.
     */
    fun effective(cached: CachedEntitlement?, now: Long): Entitlement {
        if (cached == null || cached.productId !in ProCatalog.proProductIds) return Entitlement.Free
        val highWater = maxOf(cached.lastSeenMillis, cached.confirmedAtMillis)
        if (now < highWater - ProCatalog.CLOCK_SKEW_TOLERANCE_MS) return Entitlement.Free
        val age = maxOf(now, highWater) - cached.confirmedAtMillis
        return if (age <= ProCatalog.OFFLINE_GRACE_MS) {
            Entitlement.Pro(cached.productId, cached.autoRenewing, cached.confirmedAtMillis, Entitlement.Pro.Source.CACHE_GRACE)
        } else {
            Entitlement.Free
        }
    }

    /** [cached] with its high-water mark moved forward to [now] (never back). */
    fun seen(cached: CachedEntitlement?, now: Long): CachedEntitlement? =
        if (cached == null || now <= cached.lastSeenMillis) cached else cached.copy(lastSeenMillis = now)

    private fun fromPurchases(
        prev: CachedEntitlement?,
        purchases: List<OwnedPurchase>,
        now: Long,
        isValid: (OwnedPurchase) -> Boolean,
    ): Result {
        val ours = purchases.filter { p -> p.productIds.any { it in ProCatalog.proProductIds } }
        val (valid, invalid) = ours.partition(isValid)
        val active = valid.filter { it.state == PurchaseState.PURCHASED && !it.suspended }
        // Prefer a renewing subscription over a prepaid pass or a cancelled one when several exist.
        val best = active.maxByOrNull { if (it.autoRenewing) 1 else 0 }
        if (best != null) {
            val productId = best.productIds.first { it in ProCatalog.proProductIds }
            val hash = TokenHash.of(best.purchaseToken)
            val cached = CachedEntitlement(
                productId = productId,
                tokenHash = hash,
                confirmedAtMillis = now,
                autoRenewing = best.autoRenewing,
                // Keep a known acknowledgement of the same token even if this answer is older.
                acknowledged = best.acknowledged || (prev?.tokenHash == hash && prev.acknowledged),
                catalogVersion = ProCatalog.CATALOG_VERSION,
                // Play just confirmed: its answer is authoritative, so the grace restarts from this clock.
                lastSeenMillis = now,
            )
            val live = Entitlement.Pro(productId, best.autoRenewing, now, Entitlement.Pro.Source.PLAY_LIVE)
            return Result(cached, live, active.filter { !it.acknowledged }, invalid.size)
        }
        val pending = valid.firstOrNull { it.state == PurchaseState.PENDING }
        if (pending != null) {
            return Result(null, Entitlement.Pending(pending.productIds.first { it in ProCatalog.proProductIds }), rejectedSignatures = invalid.size)
        }
        return Result(null, Entitlement.Free, rejectedSignatures = invalid.size)
    }
}
