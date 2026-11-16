package com.spaceview.app.pro

import android.app.Activity
import com.spaceview.app.billing.BillingCode
import com.spaceview.app.billing.BillingGateway
import com.spaceview.app.billing.FakeBillingGateway
import com.spaceview.app.billing.OffersResult
import com.spaceview.app.billing.ProductOffer
import com.spaceview.app.billing.PurchaseState
import com.spaceview.app.billing.PurchaseVerifier
import com.spaceview.app.billing.QueryResult
import com.spaceview.app.billing.TokenHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class InMemoryEntitlementStore(var value: CachedEntitlement? = null, var failReads: Boolean = false) : EntitlementStore {
    var writes = 0

    override suspend fun read(): CachedEntitlement? {
        if (failReads) throw IOException("corrupt")
        return value
    }

    override suspend fun write(value: CachedEntitlement?) {
        writes++
        this.value = value
    }
}

/** Coroutine tests with [FakeBillingGateway] (§4.10): grace, restore, pending, refund, races, process death. */
class EntitlementRepositoryTest {
    private val day = 24L * 60 * 60 * 1000
    private var now = 1_800_000_000_000L
    private val activity = Activity()

    private fun TestScope.repo(
        fake: BillingGateway,
        store: EntitlementStore,
        key: String = (fake as? FakeBillingGateway)?.publicKeyBase64 ?: FakeBillingGateway().publicKeyBase64,
        enabled: Boolean = true,
        // Not backgroundScope: advanceUntilIdle() skips background work. A root Job on the test scheduler.
        scope: CoroutineScope = CoroutineScope(coroutineContext + Job()),
    ) = EntitlementRepository(
        store = store,
        billing = fake,
        verifier = PurchaseVerifier.forKey({ PurchaseVerifier.publicKey(key) }, FakeBillingGateway.TEST_PACKAGE),
        clock = { now },
        scope = scope,
        io = StandardTestDispatcher(testScheduler),
        purchasesAllowed = { enabled },
    )

    private suspend fun yearly(r: EntitlementRepository): ProductOffer =
        (r.offers() as OffersResult.Ok).offers.first { it.basePlanId == ProCatalog.BASE_PLAN_YEARLY }

    @Test
    fun `cold start with an active purchase becomes live Pro and is cached`() = runTest {
        val fake = FakeBillingGateway().apply { grant() }
        val store = InMemoryEntitlementStore()
        val r = repo(fake, store)
        assertEquals(Entitlement.Unknown, r.entitlement.value)
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Pro.Source.PLAY_LIVE, (r.entitlement.value as Entitlement.Pro).source)
        assertEquals(now, store.value!!.confirmedAtMillis)
    }

    @Test
    fun `offline inside grace keeps Pro from the cache`() = runTest {
        val fake = FakeBillingGateway().apply { queryFailure = BillingCode.NETWORK_ERROR }
        val store = InMemoryEntitlementStore(CachedEntitlement("pro", "h", now - 6 * day, true, true, 1))
        val r = repo(fake, store)
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Pro.Source.CACHE_GRACE, (r.entitlement.value as Entitlement.Pro).source)
        assertTrue(r.refresh(RefreshReason.RESTORE_BUTTON) is RefreshOutcome.Failed)
    }

    @Test
    fun `offline after grace is Free, and coming back online restores Pro`() = runTest {
        val fake = FakeBillingGateway().apply {
            grant()
            queryFailure = BillingCode.SERVICE_UNAVAILABLE
        }
        val store = InMemoryEntitlementStore(CachedEntitlement("pro", "h", now - 7 * day - 1, true, true, 1))
        val r = repo(fake, store)
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Free, r.entitlement.value)
        fake.queryFailure = null
        assertEquals(RefreshOutcome.ProActive, r.refresh(RefreshReason.RESTORE_BUTTON))
        assertTrue(r.entitlement.value is Entitlement.Pro)
    }

    @Test
    fun `Unknown lasts at most 1 s when Play never answers`() = runTest {
        val hanging = object : BillingGateway by FakeBillingGateway() {
            override suspend fun queryActive(): QueryResult = awaitCancellation()
        }
        val r = repo(hanging, InMemoryEntitlementStore(), key = FakeBillingGateway().publicKeyBase64)
        r.start()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(Entitlement.Unknown, r.entitlement.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Entitlement.Free, r.entitlement.value)
    }

    @Test
    fun `a cached Pro shows before Play answers`() = runTest {
        val hanging = object : BillingGateway by FakeBillingGateway() {
            override suspend fun queryActive(): QueryResult = awaitCancellation()
        }
        val store = InMemoryEntitlementStore(CachedEntitlement("pro", "h", now - day, true, true, 1))
        val r = repo(hanging, store, key = FakeBillingGateway().publicKeyBase64)
        r.start()
        runCurrent()
        assertTrue(r.entitlement.value is Entitlement.Pro)
    }

    @Test
    fun `refund or revocation turns Pro into Free on the next query and clears the cache`() = runTest {
        val fake = FakeBillingGateway().apply { grant() }
        val store = InMemoryEntitlementStore()
        val r = repo(fake, store)
        r.start()
        advanceUntilIdle()
        assertTrue(r.entitlement.value is Entitlement.Pro)
        fake.revokeAll()
        assertEquals(RefreshOutcome.NothingFound, r.refresh(RefreshReason.RESTORE_BUTTON))
        assertEquals(Entitlement.Free, r.entitlement.value)
        assertNull(store.value)
    }

    @Test
    fun `foreground refresh is throttled to one per 10 minutes`() = runTest {
        val fake = FakeBillingGateway()
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        val calls = fake.queryCalls
        assertEquals(RefreshOutcome.Throttled, r.refresh(RefreshReason.FOREGROUND))
        assertEquals(calls, fake.queryCalls)
        now += 10 * 60 * 1000
        assertEquals(RefreshOutcome.NothingFound, r.refresh(RefreshReason.FOREGROUND))
        assertEquals(calls + 1, fake.queryCalls)
        // Paywall and Restore are never throttled.
        r.refresh(RefreshReason.PAYWALL)
        r.refresh(RefreshReason.RESTORE_BUTTON)
        assertEquals(calls + 3, fake.queryCalls)
    }

    @Test
    fun `purchase grants Pro and acknowledges once`() = runTest {
        val fake = FakeBillingGateway()
        val store = InMemoryEntitlementStore()
        val r = repo(fake, store)
        r.start()
        advanceUntilIdle()
        assertEquals(PurchaseOutcome.Purchased, r.purchase(activity, yearly(r)))
        advanceUntilIdle()
        assertTrue(r.entitlement.value is Entitlement.Pro)
        assertEquals(1, fake.acknowledgedTokens.size)
        assertTrue(store.value!!.acknowledged)
    }

    @Test
    fun `cancelled purchase is silent and changes nothing`() = runTest {
        val fake = FakeBillingGateway().apply { nextOutcome = FakeBillingGateway.NextOutcome.CANCEL }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(PurchaseOutcome.Cancelled, r.purchase(activity, yearly(r)))
        assertEquals(Entitlement.Free, r.entitlement.value)
    }

    @Test
    fun `declined payment is a failure and grants nothing`() = runTest {
        val fake = FakeBillingGateway().apply { nextOutcome = FakeBillingGateway.NextOutcome.DECLINE }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(PurchaseOutcome.Failed(BillingCode.ERROR), r.purchase(activity, yearly(r)))
        assertEquals(Entitlement.Free, r.entitlement.value)
    }

    @Test
    fun `pending payment then bank approval unlocks Pro`() = runTest {
        val fake = FakeBillingGateway().apply { nextOutcome = FakeBillingGateway.NextOutcome.PENDING }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(PurchaseOutcome.Pending, r.purchase(activity, yearly(r)))
        assertEquals(Entitlement.Pending("pro"), r.entitlement.value)
        assertTrue("a pending purchase is never acknowledged", fake.acknowledgedTokens.isEmpty())
        fake.approvePending()
        advanceUntilIdle()
        assertTrue(r.entitlement.value is Entitlement.Pro)
        assertEquals(1, fake.acknowledgedTokens.size)
    }

    @Test
    fun `pending found at start, approved while the app was closed`() = runTest {
        val fake = FakeBillingGateway().apply { grant(state = PurchaseState.PENDING, acknowledged = false) }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Pending("pro"), r.entitlement.value)
        assertEquals(RefreshOutcome.Pending, r.refresh(RefreshReason.RESTORE_BUTTON))
        fake.revokeAll()
        fake.grant(acknowledged = false)
        assertEquals(RefreshOutcome.ProActive, r.refresh(RefreshReason.RESTORE_BUTTON))
    }

    @Test
    fun `ITEM_ALREADY_OWNED restores`() = runTest {
        val fake = FakeBillingGateway()
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        // Bought on another device after this app's last query.
        fake.grant()
        fake.launchFailure = BillingCode.ITEM_ALREADY_OWNED
        assertEquals(PurchaseOutcome.AlreadyOwned(restored = true), r.purchase(activity, yearly(r)))
        assertTrue(r.entitlement.value is Entitlement.Pro)
    }

    @Test
    fun `restore after reinstall finds the subscription`() = runTest {
        val fake = FakeBillingGateway().apply { grant() }
        val r = repo(fake, InMemoryEntitlementStore())
        // Not started: a fresh install whose cache is empty.
        assertEquals(RefreshOutcome.ProActive, r.refresh(RefreshReason.RESTORE_BUTTON))
    }

    @Test
    fun `refresh racing a purchase grants once and acknowledges once`() = runTest {
        val fake = FakeBillingGateway()
        val store = InMemoryEntitlementStore()
        val r = repo(fake, store)
        r.start()
        advanceUntilIdle()
        val offer = yearly(r)
        val buy = async { r.purchase(activity, offer) }
        val refresh = async { r.refresh(RefreshReason.PAYWALL) }
        val refresh2 = async { r.refresh(RefreshReason.RESTORE_BUTTON) }
        assertEquals(PurchaseOutcome.Purchased, buy.await())
        refresh.await()
        refresh2.await()
        advanceUntilIdle()
        assertEquals(1, fake.acknowledgedTokens.size)
        assertEquals(TokenHash.of(fake.purchases.single().purchaseToken), store.value!!.tokenHash)
    }

    @Test
    fun `acknowledgement failure is retried after a process restart`() = runTest {
        val fake = FakeBillingGateway().apply { acknowledgeCode = BillingCode.SERVICE_UNAVAILABLE }
        val store = InMemoryEntitlementStore()
        val dying = CoroutineScope(coroutineContext + Job())
        val first = repo(fake, store, scope = dying)
        first.start()
        advanceUntilIdle()
        assertEquals(PurchaseOutcome.Purchased, first.purchase(activity, yearly(first)))
        assertTrue(fake.acknowledgedTokens.isEmpty())
        assertTrue("granted even before acknowledgement", first.entitlement.value is Entitlement.Pro)
        // Process death, then Play is reachable again.
        dying.cancel()
        fake.acknowledgeCode = BillingCode.OK
        val second = repo(fake, store)
        second.start()
        advanceUntilIdle()
        assertTrue(second.entitlement.value is Entitlement.Pro)
        assertEquals(1, fake.acknowledgedTokens.size)
        assertTrue(store.value!!.acknowledged)
    }

    @Test
    fun `process death mid-purchase is recovered by the next start`() = runTest {
        val fake = FakeBillingGateway().apply { completeAutomatically = false }
        val store = InMemoryEntitlementStore()
        val dying = CoroutineScope(coroutineContext + Job())
        val first = repo(fake, store, scope = dying)
        first.start()
        advanceUntilIdle()
        val offer = yearly(first)
        val flow = dying.launch { first.purchase(activity, offer) }
        runCurrent()
        assertEquals(1, fake.launchCalls)
        // The process dies with Play's sheet open; the payment completes anyway.
        flow.cancel()
        dying.cancel()
        fake.grant(acknowledged = false)
        val second = repo(fake, store)
        second.start()
        advanceUntilIdle()
        assertTrue(second.entitlement.value is Entitlement.Pro)
        assertEquals(1, fake.acknowledgedTokens.size)
    }

    @Test
    fun `forged purchase from a fake billing service never grants`() = runTest {
        val fake = FakeBillingGateway().apply { grant(validSignature = false, acknowledged = false) }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Free, r.entitlement.value)
        assertEquals(1, r.rejectedSignatures)
        assertTrue("never acknowledged, so Play refunds it", fake.acknowledgedTokens.isEmpty())
    }

    @Test
    fun `build without a licence key sells nothing`() = runTest {
        val fake = FakeBillingGateway()
        val r = repo(fake, InMemoryEntitlementStore(), key = "", enabled = false)
        assertEquals(PurchaseOutcome.NotConfigured, r.purchase(activity, FakeBillingGateway.defaultOffers().first()))
        assertEquals(0, fake.launchCalls)
    }

    @Test
    fun `unreadable cache reads as no Pro, then Play restores it`() = runTest {
        val fake = FakeBillingGateway().apply { grant() }
        val r = repo(fake, InMemoryEntitlementStore(failReads = true))
        r.start()
        advanceUntilIdle()
        assertTrue(r.entitlement.value is Entitlement.Pro)
    }

    @Test
    fun `offers are one per base plan, yearly first, with the trial when eligible`() = runTest {
        val r = repo(FakeBillingGateway(), InMemoryEntitlementStore())
        val offers = (r.offers() as OffersResult.Ok).offers
        assertEquals(listOf("yearly", "monthly", "pass30"), offers.map { it.basePlanId })
        assertEquals("trial7", offers.first().offerId)
    }
    // ---- P1 review MAJOR 1: settled ----

    @Test
    fun `settled only after the first start-up check, not at the 1 s Unknown fallback`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fake = FakeBillingGateway().apply { grant() }
        val slow = object : BillingGateway by fake {
            override suspend fun queryActive(): QueryResult {
                gate.await()
                return fake.queryActive()
            }
        }
        // A stale cache: 8 days since the last confirmation.
        val store = InMemoryEntitlementStore(CachedEntitlement("pro", "h", now - 8 * day, true, true, 1))
        val r = repo(slow, store, key = fake.publicKeyBase64)
        r.start()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("the 1 s fallback reads the stale cache as Free", Entitlement.Free, r.entitlement.value)
        assertEquals("but it has not settled", false, r.settled.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.entitlement.value is Entitlement.Pro)
        assertTrue(r.settled.value)
    }

    @Test
    fun `a failed start-up check also settles`() = runTest {
        val fake = FakeBillingGateway().apply { queryFailure = BillingCode.BILLING_UNAVAILABLE }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertTrue(r.settled.value)
        assertEquals(Entitlement.Free, r.entitlement.value)
    }

    @Test
    fun `offline evaluation persists the clock high-water mark`() = runTest {
        val fake = FakeBillingGateway().apply { queryFailure = BillingCode.NETWORK_ERROR }
        val store = InMemoryEntitlementStore(CachedEntitlement("pro", "h", now - 2 * day, true, true, 1))
        val r = repo(fake, store)
        r.start()
        advanceUntilIdle()
        assertEquals(now, store.value!!.lastSeenMillis)
        // Rolled back below the mark on the next start: not trusted.
        now -= 3 * day
        val second = repo(fake, store)
        second.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Free, second.entitlement.value)
    }

    // ---- P1 review MINOR 8: purchase results are matched to their own flow ----

    @Test
    fun `an older pending purchase approved mid-flow does not answer the new flow`() = runTest {
        val fake = FakeBillingGateway().apply {
            grant(state = PurchaseState.PENDING, acknowledged = false, productId = "pro")
            completeAutomatically = false
        }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Pending("pro"), r.entitlement.value)
        // The UI blocks Buy while Pending, so model the narrow case: the repository call itself.
        val offer = yearly(r)
        val buy = async { r.purchase(activity, offer) }
        runCurrent()
        fake.approvePending() // the old payment clears now
        advanceUntilIdle()
        assertTrue("entitlement still updated", r.entitlement.value is Entitlement.Pro)
        assertTrue("the new flow is still waiting for its own result", buy.isActive)
        fake.nextOutcome = FakeBillingGateway.NextOutcome.CANCEL
        fake.completeLaunch(offer)
        assertEquals(PurchaseOutcome.Cancelled, buy.await())
    }

    @Test
    fun `purchases for another package are ignored`() = runTest {
        val fake = FakeBillingGateway().apply { grant(packageName = "com.someone.else") }
        val r = repo(fake, InMemoryEntitlementStore())
        r.start()
        advanceUntilIdle()
        assertEquals(Entitlement.Free, r.entitlement.value)
    }
}
