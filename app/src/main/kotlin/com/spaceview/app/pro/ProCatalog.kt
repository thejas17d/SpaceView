package com.spaceview.app.pro

/**
 * Everything Pro can unlock. Gates are asked through [FeatureGate], never through billing types.
 *
 * Deliberately **not** here (always free, docs/research/pro-monetization.md §1, ARCHITECTURE-v2 §4.5):
 * measuring in every mode, ±, calibration, history, Smart Measure (auto rectangles, hands-free drop,
 * naming), object scan, Place in room, the fit verdict, sharing, and viewing / placing / sharing /
 * deleting anything already saved. A lapse never hides data: gates only guard *new* actions.
 */
enum class ProFeature {
    /** No banners and no interstitials; AdMob is never initialised for Pro ([com.spaceview.app.ads.AdsManager]). */
    NO_ADS,

    /** More than [ProCatalog.FREE_GALLERY_ITEMS] saved gallery items. Checked before a scan starts and at Save. */
    GALLERY_UNLIMITED,

    /** More than [ProCatalog.FREE_DEVICE_SCANS] saved on-device 3D scans (they also count toward the gallery). */
    DEVICE_SCAN,

    /** Furniture templates outside [ProCatalog.FREE_TEMPLATE_IDS] (the 3 core templates are always free). */
    FURNITURE_TEMPLATES,

    /** Comparing more than [ProCatalog.FREE_COMPARE_ITEMS] items against one space. */
    COMPARE,

    /** 3D model (GLB) export. */
    EXPORT_GLB,

    /** PDF export with dimensions. */
    EXPORT_PDF,
}

/** How much of a feature a tier gets. */
sealed interface Allowance {
    data object Unlimited : Allowance

    data object None : Allowance

    /** Up to [max] (e.g. saved items). The caller supplies the current usage. */
    data class Count(val max: Int) : Allowance {
        init {
            require(max > 0) { "Count must be positive; use None" }
        }
    }
}

/**
 * One row of the Free/Pro table.
 *
 * @property shipped true once the feature exists in the app. The paywall only advertises shipped
 *   features (PLAN §9 "Pro honesty": every paid feature does exactly what the paywall says). Flip it
 *   in the same change that ships the feature.
 */
data class FeatureRule(val feature: ProFeature, val free: Allowance, val pro: Allowance, val shipped: Boolean)

/**
 * The product catalogue and the Free/Pro split: **the one file** a pricing or limits decision edits
 * (ADR-005). Values from docs/research/pro-monetization.md §1 and §8, approved 2026-11-06.
 *
 * Limits are code constants for v1 (ARCHITECTURE-v2 §13 D16); there is no Remote Config.
 */
object ProCatalog {
    /** Bumped when a rule or a product changes meaning; stored with the cached entitlement. */
    const val CATALOG_VERSION = 1

    /** One subscription with three base plans (Play Console product id). */
    const val SUBSCRIPTION_ID = "pro"

    /** Auto-renewing, P1M, ₹129. */
    const val BASE_PLAN_MONTHLY = "monthly"

    /** Auto-renewing, P1Y, ₹799, with the [OFFER_YEARLY_TRIAL] offer. */
    const val BASE_PLAN_YEARLY = "yearly"

    /** Prepaid, P1M (30 days), ₹149, no auto-renew. */
    const val BASE_PLAN_PASS = "pass30"

    /** 7-day free trial on yearly; eligibility "never had this subscription" (set in Play Console). */
    const val OFFER_YEARLY_TRIAL = "trial7"

    /** Paywall order: yearly first, with its total price (research §8). */
    val basePlans: List<String> = listOf(BASE_PLAN_YEARLY, BASE_PLAN_MONTHLY, BASE_PLAN_PASS)

    /** Products whose purchase grants Pro. v1.x adds `pro_lifetime` / `studio` as Pro variants. */
    val proProductIds: Set<String> = setOf(SUBSCRIPTION_ID)

    const val FREE_GALLERY_ITEMS = 20
    const val FREE_DEVICE_SCANS = 3
    const val FREE_COMPARE_ITEMS = 2

    /** The 3 core templates that are free with full accuracy (research §1). Stored ids; never rename. */
    val FREE_TEMPLATE_IDS: Set<String> = setOf("sofa_v1", "bed_v1", "wardrobe_v1")

    /** Pro stays on for this long after the last confirmed Play check while offline (§13 D3). */
    const val OFFLINE_GRACE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * A wall clock this far *behind* the last confirmation is treated as moved back on purpose, and the
     * cached Pro is not trusted until Play confirms again. Small NTP corrections stay inside it.
     */
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 5L * 60 * 1000

    val rules: List<FeatureRule> = listOf(
        FeatureRule(ProFeature.NO_ADS, free = Allowance.None, pro = Allowance.Unlimited, shipped = true),
        FeatureRule(ProFeature.GALLERY_UNLIMITED, free = Allowance.Count(FREE_GALLERY_ITEMS), pro = Allowance.Unlimited, shipped = true),
        // The features below land later this sprint or in v1.x: flip `shipped` when they do.
        FeatureRule(ProFeature.DEVICE_SCAN, free = Allowance.Count(FREE_DEVICE_SCANS), pro = Allowance.Unlimited, shipped = false),
        FeatureRule(ProFeature.FURNITURE_TEMPLATES, free = Allowance.None, pro = Allowance.Unlimited, shipped = false),
        FeatureRule(ProFeature.COMPARE, free = Allowance.Count(FREE_COMPARE_ITEMS), pro = Allowance.Unlimited, shipped = false),
        FeatureRule(ProFeature.EXPORT_GLB, free = Allowance.None, pro = Allowance.Unlimited, shipped = false),
        FeatureRule(ProFeature.EXPORT_PDF, free = Allowance.None, pro = Allowance.Unlimited, shipped = false),
    )

    fun rule(feature: ProFeature): FeatureRule = rules.first { it.feature == feature }

    /** Pro benefits that exist in this build, in paywall order. */
    val advertised: List<ProFeature> get() = rules.filter { it.shipped && it.free != it.pro }.map { it.feature }
}
