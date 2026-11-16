package com.spaceview.app.pro

import com.spaceview.app.billing.ProductOffer

/** Picks what the paywall shows from Play's offers. Pure. */
object OfferSelector {
    /**
     * One offer per known base plan, in [ProCatalog.basePlans] order (yearly first):
     * - the free-trial offer when Play returns one (Play only returns offers the user is eligible for),
     * - otherwise the plain base-plan offer.
     * Offers of unknown base plans, and offers whose terms we can't state honestly (no recurring period),
     * are dropped.
     */
    fun select(offers: List<ProductOffer>): List<ProductOffer> =
        ProCatalog.basePlans.mapNotNull { basePlan ->
            val forPlan = offers.filter { it.productId == ProCatalog.SUBSCRIPTION_ID && it.basePlanId == basePlan }
            forPlan.firstOrNull { it.offerId != null && it.freeTrial != null }
                ?: forPlan.firstOrNull { it.offerId == null }
        }

    /** The plan pre-selected on the paywall: the saved choice if still offered, else yearly, else the first. */
    fun defaultSelection(offers: List<ProductOffer>, saved: String?): String? =
        offers.firstOrNull { it.basePlanId == saved }?.basePlanId
            ?: offers.firstOrNull { it.basePlanId == ProCatalog.BASE_PLAN_YEARLY }?.basePlanId
            ?: offers.firstOrNull()?.basePlanId
}
