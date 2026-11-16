package com.spaceview.app.pro

import com.spaceview.app.billing.BillingPeriod
import com.spaceview.app.billing.FakeBillingGateway
import com.spaceview.app.billing.PeriodUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferSelectorTest {
    private val all = FakeBillingGateway.defaultOffers()

    @Test
    fun `yearly first with trial, then monthly, then pass`() {
        val s = OfferSelector.select(all)
        assertEquals(listOf("yearly", "monthly", "pass30"), s.map { it.basePlanId })
        assertEquals(BillingPeriod(7, PeriodUnit.DAY), s[0].freeTrial)
    }

    @Test
    fun `no trial offer (already used) falls back to the yearly base plan`() {
        val s = OfferSelector.select(all.filter { it.offerId == null })
        assertNull(s.first().freeTrial)
        assertNull(s.first().offerId)
    }

    @Test
    fun `unknown base plans and other products are dropped`() {
        val extra = listOf(all.first().copy(basePlanId = "monthly-99"), all.first().copy(productId = "studio"))
        assertEquals(3, OfferSelector.select(all + extra).size)
    }

    @Test
    fun `missing plans are simply absent`() {
        assertEquals(listOf("monthly"), OfferSelector.select(all.filter { it.basePlanId == "monthly" }).map { it.basePlanId })
        assertEquals(emptyList<Any>(), OfferSelector.select(emptyList()))
    }

    @Test
    fun `default selection keeps a saved choice, else yearly`() {
        val s = OfferSelector.select(all)
        assertEquals("pass30", OfferSelector.defaultSelection(s, "pass30"))
        assertEquals("yearly", OfferSelector.defaultSelection(s, "gone"))
        assertEquals("monthly", OfferSelector.defaultSelection(s.filter { it.basePlanId == "monthly" }, null))
        assertNull(OfferSelector.defaultSelection(emptyList(), null))
    }

    @Test
    fun `ISO periods parse`() {
        assertEquals(BillingPeriod(1, PeriodUnit.MONTH), BillingPeriod.parse("P1M"))
        assertEquals(BillingPeriod(1, PeriodUnit.YEAR), BillingPeriod.parse("P1Y"))
        assertEquals(BillingPeriod(7, PeriodUnit.DAY), BillingPeriod.parse("P7D"))
        assertEquals(BillingPeriod(1, PeriodUnit.WEEK), BillingPeriod.parse(" P1W "))
        assertNull(BillingPeriod.parse("P0D"))
        assertNull(BillingPeriod.parse("P1Y2M"))
        assertNull(BillingPeriod.parse(""))
        assertNull(BillingPeriod.parse(null))
    }
}
