package com.spaceview.app.billing

import android.app.Activity
import com.spaceview.app.pro.ProCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.io.encoding.Base64

/**
 * A scriptable stand-in for Google Play, for JVM tests and for debug builds before the Play Console
 * products exist (debug builds use it unless `-PFAKE_BILLING=false`). It signs its purchases with its
 * own RSA key, so the real [PurchaseVerifier] path runs; [publicKeyBase64] is the matching key.
 *
 * Never used in release builds (`BuildConfig.FAKE_BILLING` is false there, so R8 removes it).
 */
class FakeBillingGateway(
    private val keyPair: KeyPair = sharedTestKeyPair,
    private val offerList: List<ProductOffer> = defaultOffers(),
    /** The package its purchases claim (Play's `packageName`). */
    val packageName: String = TEST_PACKAGE,
) : BillingGateway {

    /** What the next purchase flow does. */
    enum class NextOutcome { PURCHASE, PENDING, CANCEL, DECLINE, ALREADY_OWNED }

    val publicKeyBase64: String = Base64.Default.encode(keyPair.public.encoded)

    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    private val lock = Any()
    private val owned = mutableListOf<OwnedPurchase>()
    private var tokenSeq = 0

    // Scripting (tests and the debug panel).
    @Volatile var nextOutcome: NextOutcome = NextOutcome.PURCHASE

    /** Non-null: [queryActive] fails with it (e.g. [BillingCode.NETWORK_ERROR] = offline). */
    @Volatile var queryFailure: BillingCode? = null

    @Volatile var offersFailure: BillingCode? = null

    @Volatile var launchFailure: BillingCode? = null

    @Volatile var acknowledgeCode: BillingCode = BillingCode.OK

    /** False: [launch] returns Launched but the result arrives only via [completeLaunch] (e.g. process death). */
    @Volatile var completeAutomatically: Boolean = true

    /** Called after every change of the owned purchases (the debug build persists them). */
    @Volatile var onChanged: (List<OwnedPurchase>) -> Unit = {}

    @Volatile var queryCalls = 0
        private set

    @Volatile var launchCalls = 0
        private set

    val acknowledgedTokens: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    val purchases: List<OwnedPurchase> get() = synchronized(lock) { owned.toList() }

    override suspend fun queryActive(): QueryResult {
        queryCalls++
        queryFailure?.let { return QueryResult.Failed(it) }
        return QueryResult.Ok(purchases)
    }

    override suspend fun offers(productId: String): OffersResult {
        offersFailure?.let { return OffersResult.Failed(it) }
        return OffersResult.Ok(offerList.filter { it.productId == productId })
    }

    override suspend fun launch(activity: Activity, offer: ProductOffer): LaunchResult {
        launchCalls++
        launchFailure?.let { return LaunchResult.Failed(it) }
        if (completeAutomatically) completeLaunch(offer)
        return LaunchResult.Launched
    }

    /** Finishes a launched flow according to [nextOutcome], emitting the update Play would send. */
    fun completeLaunch(offer: ProductOffer) {
        val update = when (nextOutcome) {
            NextOutcome.PURCHASE -> PurchaseUpdate(BillingCode.OK, listOf(add(offer, PurchaseState.PURCHASED)))
            NextOutcome.PENDING -> PurchaseUpdate(BillingCode.OK, listOf(add(offer, PurchaseState.PENDING)))
            NextOutcome.CANCEL -> PurchaseUpdate(BillingCode.USER_CANCELED, emptyList())
            NextOutcome.DECLINE -> PurchaseUpdate(BillingCode.ERROR, emptyList())
            NextOutcome.ALREADY_OWNED -> PurchaseUpdate(BillingCode.ITEM_ALREADY_OWNED, emptyList())
        }
        _updates.tryEmit(update)
    }

    override suspend fun acknowledge(purchaseToken: String): BillingCode {
        val code = acknowledgeCode
        if (code == BillingCode.OK) {
            acknowledgedTokens += purchaseToken
            replaceWhere({ it.purchaseToken == purchaseToken }) { it.copy(acknowledged = true) }
        }
        return code
    }

    /** Adds a purchase as if it was made earlier or on another device (no update is emitted). */
    fun grant(
        state: PurchaseState = PurchaseState.PURCHASED,
        autoRenewing: Boolean = true,
        acknowledged: Boolean = true,
        suspended: Boolean = false,
        validSignature: Boolean = true,
        productId: String = ProCatalog.SUBSCRIPTION_ID,
        token: String? = null,
        packageName: String = this.packageName,
    ): OwnedPurchase {
        val p = signed(productId, token ?: nextToken(), state, acknowledged, autoRenewing, suspended, validSignature, packageName)
        synchronized(lock) { owned += p }
        onChanged(purchases)
        return p
    }

    /** The bank confirmed every pending payment; Play reports it like a finished purchase. */
    fun approvePending() {
        val approved = replaceWhere({ it.state == PurchaseState.PENDING }) {
            signed(it.productIds.first(), it.purchaseToken, PurchaseState.PURCHASED, false, it.autoRenewing, it.suspended, true)
        }
        if (approved.isNotEmpty()) _updates.tryEmit(PurchaseUpdate(BillingCode.OK, approved))
    }

    /** Refund, revocation, expiry or account hold: Play stops returning the purchases. */
    fun revokeAll() {
        synchronized(lock) { owned.clear() }
        onChanged(purchases)
    }

    /** Restores persisted purchases (debug builds), re-signed with this gateway's key. */
    fun restore(saved: List<OwnedPurchase>) {
        synchronized(lock) {
            owned.clear()
            saved.forEach { owned += signed(it.productIds.first(), it.purchaseToken, it.state, it.acknowledged, it.autoRenewing, it.suspended, true) }
        }
    }

    private fun add(offer: ProductOffer, state: PurchaseState): OwnedPurchase =
        grant(state = state, autoRenewing = offer.kind == PlanKind.AUTO_RENEWING, acknowledged = false, productId = offer.productId)

    private fun replaceWhere(match: (OwnedPurchase) -> Boolean, transform: (OwnedPurchase) -> OwnedPurchase): List<OwnedPurchase> {
        val changed = synchronized(lock) {
            owned.indices.filter { match(owned[it]) }.map { i -> transform(owned[i]).also { owned[i] = it } }
        }
        if (changed.isNotEmpty()) onChanged(purchases)
        return changed
    }

    private fun nextToken(): String = synchronized(lock) { "fake-token-${++tokenSeq}-${System.nanoTime()}" }

    private fun signed(
        productId: String,
        token: String,
        state: PurchaseState,
        acknowledged: Boolean,
        autoRenewing: Boolean,
        suspended: Boolean,
        validSignature: Boolean,
        packageName: String = this.packageName,
    ): OwnedPurchase {
        // Play's JSON shape: purchaseState is an int (0 purchased, 2 pending).
        val stateCode = when (state) {
            PurchaseState.PURCHASED -> 0
            PurchaseState.PENDING -> 2
            PurchaseState.UNSPECIFIED -> 1
        }
        val time = System.currentTimeMillis()
        val json = """{"packageName":"$packageName","productId":"$productId","purchaseTime":$time,"purchaseToken":"$token","purchaseState":$stateCode,"acknowledged":$acknowledged,"autoRenewing":$autoRenewing}"""
        val sig = Signature.getInstance("SHA1withRSA").run {
            initSign(keyPair.private)
            update(json.toByteArray(Charsets.UTF_8))
            sign()
        }
        if (!validSignature) sig[0] = (sig[0].toInt() xor 0x01).toByte()
        return OwnedPurchase(listOf(productId), token, state, acknowledged, autoRenewing, suspended, json, Base64.Default.encode(sig), packageName, time)
    }

    companion object {
        const val TEST_PACKAGE = "com.spaceview.app.test"

        /** One key per process: RSA key generation is slow. */
        val sharedTestKeyPair: KeyPair by lazy { generateKeyPair(2048) }

        fun generateKeyPair(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

        /** Offers shaped like the Play Console set-up in docs/research/pro-monetization.md §8 (INR). */
        fun defaultOffers(): List<ProductOffer> {
            val id = ProCatalog.SUBSCRIPTION_ID
            val month = BillingPeriod(1, PeriodUnit.MONTH)
            val year = BillingPeriod(1, PeriodUnit.YEAR)
            return listOf(
                ProductOffer(id, ProCatalog.BASE_PLAN_MONTHLY, null, "tok-monthly", PlanKind.AUTO_RENEWING, "₹129", 129_000_000, "INR", month, null),
                ProductOffer(id, ProCatalog.BASE_PLAN_YEARLY, null, "tok-yearly", PlanKind.AUTO_RENEWING, "₹799", 799_000_000, "INR", year, null),
                ProductOffer(
                    id, ProCatalog.BASE_PLAN_YEARLY, ProCatalog.OFFER_YEARLY_TRIAL, "tok-yearly-trial", PlanKind.AUTO_RENEWING,
                    "₹799", 799_000_000, "INR", year, BillingPeriod(7, PeriodUnit.DAY),
                ),
                ProductOffer(id, ProCatalog.BASE_PLAN_PASS, null, "tok-pass30", PlanKind.PREPAID, "₹149", 149_000_000, "INR", month, null),
            )
        }
    }
}
