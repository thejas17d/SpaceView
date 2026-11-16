package com.spaceview.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Play Billing Library 9.1 adapter: the **only** file that imports `com.android.billingclient`
 * (ADR-006; enforced by `BillingBoundaryTest`).
 *
 * - The client is created and connected lazily, on first use: the repository starts after the first
 *   frame, so billing never costs cold-start time.
 * - `enableAutoServiceReconnection()` (PBL 8+) reconnects on its own; [ensureConnected] covers the
 *   first connection and a client that never connected.
 * - Pending purchases are enabled for one-time products and prepaid plans (the 30-day pass is prepaid).
 * - Suspended subscriptions (account hold) are not requested, so they read as "no Pro".
 * - Logs nothing about purchases; tokens never leave this object except inside [OwnedPurchase].
 */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val appContext = context.applicationContext

    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val code = map(result)
        _updates.tryEmit(PurchaseUpdate(code, if (code == BillingCode.OK) purchases.orEmpty().map(::toOwned) else emptyList()))
    }

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(appContext)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private val connectMutex = Mutex()

    /** Product details from the last [offers] call, needed to launch a purchase. */
    private val details = ConcurrentHashMap<String, ProductDetails>()

    override suspend fun queryActive(): QueryResult = guarded({ QueryResult.Failed(it) }) {
        val r = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        val code = map(r.billingResult)
        if (code == BillingCode.OK) QueryResult.Ok(r.purchasesList.orEmpty().map(::toOwned)) else QueryResult.Failed(code)
    }

    override suspend fun offers(productId: String): OffersResult = guarded({ OffersResult.Failed(it) }) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val r = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        val code = map(r.billingResult)
        if (code != BillingCode.OK) return@guarded OffersResult.Failed(code)
        val list = r.productDetailsList.orEmpty()
        list.forEach { details[it.productId] = it }
        // An empty list means the product isn't set up (or not visible to this account / app id).
        if (list.isEmpty()) OffersResult.Failed(BillingCode.ITEM_UNAVAILABLE) else OffersResult.Ok(list.flatMap(::toOffers))
    }

    override suspend fun launch(activity: Activity, offer: ProductOffer): LaunchResult {
        val connected = ensureConnected()
        if (connected != BillingCode.OK) return LaunchResult.Failed(connected)
        val productDetails = details[offer.productId] ?: return LaunchResult.Failed(BillingCode.ITEM_UNAVAILABLE)
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .build()
        val code = withContext(Dispatchers.Main.immediate) { map(client.launchBillingFlow(activity, params)) }
        return if (code == BillingCode.OK) LaunchResult.Launched else LaunchResult.Failed(code)
    }

    override suspend fun acknowledge(purchaseToken: String): BillingCode = guarded({ it }) {
        map(client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()))
    }

    /** Connects (once) and runs [block]; maps a failed connection or a thrown error to [onError]. */
    private suspend fun <T> guarded(onError: (BillingCode) -> T, block: suspend () -> T): T {
        val connected = ensureConnected()
        if (connected != BillingCode.OK) return onError(connected)
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // The library can throw on a dead service binding; treat it as a temporary outage.
            onError(BillingCode.SERVICE_UNAVAILABLE)
        }
    }

    private suspend fun ensureConnected(): BillingCode {
        if (client.isReady) return BillingCode.OK
        return connectMutex.withLock {
            if (client.isReady) return@withLock BillingCode.OK
            val done = CompletableDeferred<BillingCode>()
            withContext(Dispatchers.Main.immediate) {
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        done.complete(map(result))
                    }

                    override fun onBillingServiceDisconnected() {
                        done.complete(BillingCode.SERVICE_UNAVAILABLE)
                    }
                })
            }
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { done.await() } ?: BillingCode.SERVICE_UNAVAILABLE
        }
    }

    private fun toOwned(p: Purchase): OwnedPurchase = OwnedPurchase(
        productIds = p.products,
        purchaseToken = p.purchaseToken,
        state = when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNSPECIFIED
        },
        acknowledged = p.isAcknowledged,
        autoRenewing = p.isAutoRenewing,
        suspended = p.isSuspended,
        signedData = p.originalJson,
        signature = p.signature,
        // Both parsed by the library out of the signed originalJson, so they can't be changed without
        // breaking the signature.
        packageName = p.packageName,
        purchaseTimeMillis = p.purchaseTime,
    )

    private fun toOffers(d: ProductDetails): List<ProductOffer> =
        d.subscriptionOfferDetails.orEmpty().mapNotNull { o ->
            val phases = o.pricingPhases.pricingPhaseList
            val recurring = phases.lastOrNull() ?: return@mapNotNull null
            val leading = phases.dropLast(1)
            // Only free trials are shown as intro terms; an offer with a paid intro price is skipped rather
            // than shown with incomplete terms (Play subscriptions policy: state every price).
            if (leading.any { it.priceAmountMicros != 0L }) return@mapNotNull null
            val trial = leading.firstOrNull()?.let { phase ->
                BillingPeriod.parse(phase.billingPeriod)?.let { it.copy(count = it.count * phase.billingCycleCount.coerceAtLeast(1)) }
            }
            val period = BillingPeriod.parse(recurring.billingPeriod) ?: return@mapNotNull null
            ProductOffer(
                productId = d.productId,
                basePlanId = o.basePlanId,
                offerId = o.offerId,
                offerToken = o.offerToken,
                kind = if (recurring.recurrenceMode == ProductDetails.RecurrenceMode.NON_RECURRING) PlanKind.PREPAID else PlanKind.AUTO_RENEWING,
                formattedPrice = recurring.formattedPrice,
                priceMicros = recurring.priceAmountMicros,
                currencyCode = recurring.priceCurrencyCode,
                period = period,
                freeTrial = trial,
            )
        }

    private fun map(result: BillingResult): BillingCode = when (result.responseCode) {
        BillingResponseCode.OK -> BillingCode.OK
        BillingResponseCode.USER_CANCELED -> BillingCode.USER_CANCELED
        BillingResponseCode.ITEM_ALREADY_OWNED -> BillingCode.ITEM_ALREADY_OWNED
        BillingResponseCode.ITEM_UNAVAILABLE -> BillingCode.ITEM_UNAVAILABLE
        BillingResponseCode.BILLING_UNAVAILABLE -> BillingCode.BILLING_UNAVAILABLE
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_TIMEOUT,
        -> BillingCode.SERVICE_UNAVAILABLE
        BillingResponseCode.NETWORK_ERROR -> BillingCode.NETWORK_ERROR
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingCode.FEATURE_NOT_SUPPORTED
        BillingResponseCode.DEVELOPER_ERROR -> BillingCode.DEVELOPER_ERROR
        else -> BillingCode.ERROR
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
