package com.spaceview.app.pro

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import com.spaceview.app.billing.BillingCode
import com.spaceview.app.billing.FakeBillingGateway
import com.spaceview.app.billing.PurchaseVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Paywall states: loading, offers, pending, error, offline, not on sale, already Pro; restore; process death. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    private val main = StandardTestDispatcher()
    private val fake = FakeBillingGateway()
    private val activity = Activity()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(
        saved: SavedStateHandle = SavedStateHandle(),
        enabled: Boolean = true,
        feature: ProFeature = ProFeature.GALLERY_UNLIMITED,
    ): PaywallViewModel {
        val repo = EntitlementRepository(
            store = InMemoryEntitlementStore(),
            billing = fake,
            verifier = PurchaseVerifier.forKey({ PurchaseVerifier.publicKey(fake.publicKeyBase64) }, fake.packageName),
            clock = { 1_800_000_000_000L },
            // Not backgroundScope: advanceUntilIdle() skips background work.
            scope = CoroutineScope(coroutineContext + Job()),
            io = main,
            purchasesAllowed = { enabled },
        )
        repo.start()
        return PaywallViewModel(feature, saved, repo)
    }

    @Test
    fun `starts loading, then shows the offers with yearly selected`() = runTest(main) {
        val vm = vm()
        assertEquals(OffersUi.Loading, vm.state.value.offers)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("yearly", "monthly", "pass30"), (s.offers as OffersUi.Ready).offers.map { it.basePlanId })
        assertEquals("yearly", s.selectedBasePlan)
        assertEquals(Entitlement.Free, s.entitlement)
    }

    @Test
    fun `offline shows a retryable offline problem`() = runTest(main) {
        fake.offersFailure = BillingCode.NETWORK_ERROR
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OffersUi.Failed(PaywallProblem.OFFLINE), vm.state.value.offers)
        fake.offersFailure = null
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.offers is OffersUi.Ready)
    }

    @Test
    fun `Play missing or products not set up`() = runTest(main) {
        fake.offersFailure = BillingCode.BILLING_UNAVAILABLE
        val a = vm()
        advanceUntilIdle()
        assertEquals(OffersUi.Failed(PaywallProblem.UNAVAILABLE), a.state.value.offers)
        fake.offersFailure = BillingCode.ITEM_UNAVAILABLE
        val b = vm()
        advanceUntilIdle()
        assertEquals(OffersUi.Failed(PaywallProblem.NOT_ON_SALE), b.state.value.offers)
    }

    @Test
    fun `a build without a licence key is not on sale`() = runTest(main) {
        val vm = vm(enabled = false)
        advanceUntilIdle()
        assertEquals(OffersUi.Failed(PaywallProblem.NOT_ON_SALE), vm.state.value.offers)
    }

    @Test
    fun `buying turns the screen into You have Pro`() = runTest(main) {
        val vm = vm()
        advanceUntilIdle()
        vm.buy(activity)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.entitlement is Entitlement.Pro)
        assertNull(s.message)
        assertEquals(false, s.busy)
    }

    @Test
    fun `pending purchase shows the pending message and state`() = runTest(main) {
        fake.nextOutcome = FakeBillingGateway.NextOutcome.PENDING
        val vm = vm()
        advanceUntilIdle()
        vm.buy(activity)
        advanceUntilIdle()
        assertEquals(PaywallMessage.PURCHASE_PENDING, vm.state.value.message)
        assertEquals(Entitlement.Pending("pro"), vm.state.value.entitlement)
    }

    @Test
    fun `no second purchase while a payment is pending`() = runTest(main) {
        fake.grant(state = com.spaceview.app.billing.PurchaseState.PENDING, acknowledged = false)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Entitlement.Pending("pro"), vm.state.value.entitlement)
        vm.buy(activity)
        advanceUntilIdle()
        assertEquals(0, fake.launchCalls)
    }

    @Test
    fun `cancel is silent`() = runTest(main) {
        fake.nextOutcome = FakeBillingGateway.NextOutcome.CANCEL
        val vm = vm()
        advanceUntilIdle()
        vm.buy(activity)
        advanceUntilIdle()
        assertNull(vm.state.value.message)
        assertEquals(Entitlement.Free, vm.state.value.entitlement)
    }

    @Test
    fun `failed purchase shows a message`() = runTest(main) {
        fake.launchFailure = BillingCode.NETWORK_ERROR
        val vm = vm()
        advanceUntilIdle()
        vm.buy(activity)
        advanceUntilIdle()
        assertEquals(PaywallMessage.PURCHASE_OFFLINE, vm.state.value.message)
    }

    @Test
    fun `restore states`() = runTest(main) {
        val vm = vm()
        advanceUntilIdle()
        vm.restore()
        advanceUntilIdle()
        assertEquals(PaywallMessage.NOTHING_TO_RESTORE, vm.state.value.message)
        fake.grant()
        vm.restore()
        advanceUntilIdle()
        assertEquals(PaywallMessage.RESTORED, vm.state.value.message)
        assertTrue(vm.state.value.entitlement is Entitlement.Pro)
        fake.queryFailure = BillingCode.SERVICE_UNAVAILABLE
        vm.restore()
        advanceUntilIdle()
        assertEquals(PaywallMessage.RESTORE_FAILED, vm.state.value.message)
    }

    @Test
    fun `selected plan survives process death`() = runTest(main) {
        val saved = SavedStateHandle()
        val first = vm(saved)
        advanceUntilIdle()
        first.select("pass30")
        val second = vm(saved)
        advanceUntilIdle()
        assertEquals("pass30", second.state.value.selectedBasePlan)
        assertEquals("pass30", second.state.value.selectedOffer!!.basePlanId)
    }

    @Test
    fun `route argument parsing`() {
        assertEquals(ProFeature.DEVICE_SCAN, PaywallDestination.parse("DEVICE_SCAN"))
        assertEquals(ProFeature.NO_ADS, PaywallDestination.parse("bogus"))
        assertEquals(ProFeature.NO_ADS, PaywallDestination.parse(null))
        assertEquals("paywall/GALLERY_UNLIMITED", PaywallDestination.route(ProFeature.GALLERY_UNLIMITED))
    }

    @Test
    fun `manage link targets the Pro subscription in Play`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=pro&package=com.spaceview.app",
            ProLinks.manageSubscriptions("com.spaceview.app"),
        )
    }
}
