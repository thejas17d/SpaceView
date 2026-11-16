package com.spaceview.app.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * The billing seam (ARCHITECTURE-v2 §4.3, ADR-006). Only [PlayBillingGateway] knows Play Billing
 * types; everything else (the entitlement reducer, the repository, the paywall) uses the plain
 * types below. [FakeBillingGateway] replaces Play in tests and in debug builds.
 *
 * Implementations are main-safe: every suspend function may be called from the main thread.
 */
interface BillingGateway {
    /** Results of purchase flows (Play's `PurchasesUpdatedListener`), as they arrive. Hot; never completes. */
    val updates: Flow<PurchaseUpdate>

    /** The user's current subscriptions (active, in Play's grace period, or pending). */
    suspend fun queryActive(): QueryResult

    /** Offers for [productId], with prices localised by Play. Never hard-code a price. */
    suspend fun offers(productId: String): OffersResult

    /** Opens Play's purchase sheet. The result arrives later on [updates]. Call on the main thread. */
    suspend fun launch(activity: Activity, offer: ProductOffer): LaunchResult

    /** Acknowledges a purchase. Play refunds purchases that are not acknowledged within 3 days. */
    suspend fun acknowledge(purchaseToken: String): BillingCode
}

/** Play's response codes, reduced to what the app treats differently. */
enum class BillingCode {
    OK,
    USER_CANCELED,
    ITEM_ALREADY_OWNED,

    /** The product or offer doesn't exist for this app / account (e.g. Play Console not set up). */
    ITEM_UNAVAILABLE,

    /** Play Store missing, blocked, or too old; or the account's country has no billing. */
    BILLING_UNAVAILABLE,

    /** Service disconnected, timed out or temporarily down. Retry later. */
    SERVICE_UNAVAILABLE,
    NETWORK_ERROR,
    FEATURE_NOT_SUPPORTED,

    /** A bug in how we call the API. Logged without PII; the user sees a generic error. */
    DEVELOPER_ERROR,
    ERROR,
}

enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/**
 * A purchase as Play reported it.
 *
 * Holds the purchase token and the signed JSON, so [toString] leaves them out: never log either.
 *
 * @property signedData Play's `originalJson`; [signature] is Play's RSA signature over it.
 * @property suspended account hold / paused: never grants Pro.
 */
data class OwnedPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: PurchaseState,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    val suspended: Boolean,
    val signedData: String,
    val signature: String,
    /** The app the purchase was made in (from the signed JSON); must be ours. */
    val packageName: String = "",
    /** When it was bought (from the signed JSON). Kept for a future server check; see ADR-004 residual risks. */
    val purchaseTimeMillis: Long = 0L,
) {
    override fun toString(): String =
        "OwnedPurchase(products=$productIds, state=$state, acknowledged=$acknowledged, autoRenewing=$autoRenewing, suspended=$suspended)"
}

sealed interface QueryResult {
    data class Ok(val purchases: List<OwnedPurchase>) : QueryResult

    data class Failed(val code: BillingCode) : QueryResult
}

enum class PlanKind {
    AUTO_RENEWING,

    /** Prepaid: paid once, ends after its period, no auto-renew (the 30-day pass). */
    PREPAID,
}

enum class PeriodUnit { DAY, WEEK, MONTH, YEAR }

/** An ISO-8601 period as Play reports it (`P1M`, `P1Y`, `P7D`, `P1W`). */
data class BillingPeriod(val count: Int, val unit: PeriodUnit) {

    companion object {
        private val ISO = Regex("^P(\\d+)([DWMY])$")

        /** Null for anything but a single-unit period (Play only uses those for base plans and trials). */
        fun parse(iso: String?): BillingPeriod? {
            val m = ISO.matchEntire(iso?.trim().orEmpty()) ?: return null
            val count = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val unit = when (m.groupValues[2]) {
                "D" -> PeriodUnit.DAY
                "W" -> PeriodUnit.WEEK
                "M" -> PeriodUnit.MONTH
                else -> PeriodUnit.YEAR
            }
            return BillingPeriod(count, unit)
        }
    }
}

/**
 * One purchasable offer of a subscription base plan.
 *
 * @property formattedPrice the recurring price (or the one prepaid price), localised by Play, tax included.
 * @property period how long one payment lasts.
 * @property freeTrial the free phase at the start of the offer, when the user is eligible (Play only
 *   returns offers the user is eligible for).
 */
data class ProductOffer(
    val productId: String,
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val kind: PlanKind,
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    val period: BillingPeriod,
    val freeTrial: BillingPeriod?,
)

sealed interface OffersResult {
    data class Ok(val offers: List<ProductOffer>) : OffersResult

    data class Failed(val code: BillingCode) : OffersResult
}

sealed interface LaunchResult {
    data object Launched : LaunchResult

    data class Failed(val code: BillingCode) : LaunchResult
}

/** The end of a purchase flow: [purchases] is empty unless [code] is [BillingCode.OK]. */
data class PurchaseUpdate(val code: BillingCode, val purchases: List<OwnedPurchase>)
