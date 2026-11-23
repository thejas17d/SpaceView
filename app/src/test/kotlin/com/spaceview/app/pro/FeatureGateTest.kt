package com.spaceview.app.pro

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogue table, Count limits, Unknown handling and "a lapse never hides data" (§4.5, §4.10). */
class FeatureGateTest {
    private val pro = Entitlement.Pro("pro", true, 0L, Entitlement.Pro.Source.PLAY_LIVE)
    private val grace = pro.copy(source = Entitlement.Pro.Source.CACHE_GRACE)

    @Test
    fun `catalogue matches the approved Free and Pro split`() {
        fun free(f: ProFeature) = ProCatalog.rule(f).free
        assertEquals(Allowance.None, free(ProFeature.NO_ADS))
        assertEquals(Allowance.Count(20), free(ProFeature.GALLERY_UNLIMITED))
        assertEquals(Allowance.Count(5), free(ProFeature.DEVICE_SCAN))
        assertEquals(Allowance.Count(2), free(ProFeature.COMPARE))
        // Gate T1: the 3 core templates are Free (by template id); the rest of the library is Pro.
        assertEquals(Allowance.Only(setOf("sofa_v1", "bed_v1", "wardrobe_v1")), free(ProFeature.FURNITURE_TEMPLATES))
        assertEquals(Allowance.None, free(ProFeature.EXPORT_GLB))
        assertEquals(Allowance.None, free(ProFeature.EXPORT_PDF))
        ProFeature.entries.forEach { assertEquals("Pro is unlimited for $it", Allowance.Unlimited, ProCatalog.rule(it).pro) }
        assertEquals("every feature has exactly one rule", ProFeature.entries.toSet(), ProCatalog.rules.map { it.feature }.toSet())
        assertEquals(ProFeature.entries.size, ProCatalog.rules.size)
        assertEquals(setOf("sofa_v1", "bed_v1", "wardrobe_v1"), ProCatalog.FREE_TEMPLATE_IDS)
        assertEquals(7L * 24 * 60 * 60 * 1000, ProCatalog.OFFLINE_GRACE_MS)
    }

    @Test
    fun `products and base plans match Play Console set-up`() {
        assertEquals("pro", ProCatalog.SUBSCRIPTION_ID)
        assertEquals(listOf("yearly", "monthly", "pass30"), ProCatalog.basePlans)
        assertEquals("trial7", ProCatalog.OFFER_YEARLY_TRIAL)
    }

    @Test
    fun `paywall advertises only shipped features`() {
        assertEquals(listOf(ProFeature.NO_ADS, ProFeature.GALLERY_UNLIMITED, ProFeature.FURNITURE_TEMPLATES), ProCatalog.advertised)
        assertTrue("templates shipped with T", ProCatalog.rule(ProFeature.FURNITURE_TEMPLATES).shipped)
    }

    @Test
    fun `core templates are free by id, the rest of the library needs Pro`() {
        val f = Entitlement.Free
        for (id in listOf("sofa_v1", "bed_v1", "wardrobe_v1")) {
            assertEquals(GateState.Allowed, FeatureRules.evaluate(ProFeature.FURNITURE_TEMPLATES, f, 0, id))
            assertTrue(ProCatalog.isCoreTemplate(id))
        }
        for (id in listOf("lsofa_v1", "dining_table_v1", "dining_chair_v1", "tv_unit_v1", "bookshelf_v1", "study_table_v1", "unknown_v1")) {
            assertEquals(id, GateState.NeedsPro(3), FeatureRules.evaluate(ProFeature.FURNITURE_TEMPLATES, f, 0, id))
            assertFalse(ProCatalog.isCoreTemplate(id))
        }
        // Usage never counts against core templates: the 100th sofa is still free (the gallery cap is separate).
        assertEquals(GateState.Allowed, FeatureRules.evaluate(ProFeature.FURNITURE_TEMPLATES, f, 100, "sofa_v1"))
        // Without an id (picker badges): "some are free".
        assertEquals(GateState.Limited(3, 3), FeatureRules.evaluate(ProFeature.FURNITURE_TEMPLATES, f, 0))
        assertEquals(GateState.Allowed, FeatureRules.evaluate(ProFeature.FURNITURE_TEMPLATES, pro, 0, "lsofa_v1"))
        assertEquals(GateState.NeedsPro(3), FeatureRules.evaluate(ProFeature.FURNITURE_TEMPLATES, Entitlement.Pending("pro"), 0, "lsofa_v1"))
    }

    @Test
    fun `check passes the template id through`() = runTest {
        val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free)
        val gate = EntitlementFeatureGate(entitlement)
        assertTrue(gate.check(ProFeature.FURNITURE_TEMPLATES, itemId = "bed_v1").allowed)
        assertFalse(gate.check(ProFeature.FURNITURE_TEMPLATES, itemId = "tv_unit_v1").allowed)
        entitlement.value = pro
        assertTrue(gate.check(ProFeature.FURNITURE_TEMPLATES, itemId = "tv_unit_v1").allowed)
    }

    @Test
    fun `Smart Measure, Place and accessibility are never gated`() {
        val names = ProFeature.entries.map { it.name }
        listOf("SMART", "RECTANGLE", "HANDS_FREE", "NAMING", "PLACE", "VERDICT", "MEASURE", "SHARE", "VIEW", "DELETE").forEach { word ->
            assertTrue("$word must stay free", names.none { it.contains(word) })
        }
    }

    @Test
    fun `count limits on Free`() {
        val f = Entitlement.Free
        assertEquals(GateState.Limited(20, 20), FeatureRules.evaluate(ProFeature.GALLERY_UNLIMITED, f, 0))
        assertEquals(GateState.Limited(2, 20), FeatureRules.evaluate(ProFeature.GALLERY_UNLIMITED, f, 18))
        assertEquals(GateState.Limited(1, 20), FeatureRules.evaluate(ProFeature.GALLERY_UNLIMITED, f, 19))
        assertEquals(GateState.NeedsPro(20), FeatureRules.evaluate(ProFeature.GALLERY_UNLIMITED, f, 20))
        assertEquals(GateState.Limited(1, 5), FeatureRules.evaluate(ProFeature.DEVICE_SCAN, f, 4))
        assertEquals(GateState.NeedsPro(5), FeatureRules.evaluate(ProFeature.DEVICE_SCAN, f, 5))
        assertEquals(GateState.Limited(5, 5), FeatureRules.evaluate(ProFeature.DEVICE_SCAN, f, -5))
        assertEquals(GateState.NeedsPro(0), FeatureRules.evaluate(ProFeature.EXPORT_GLB, f, 0))
    }

    @Test
    fun `Pro and Pro in offline grace are unlimited`() {
        listOf(pro, grace).forEach { e ->
            ProFeature.entries.forEach { assertEquals(GateState.Allowed, FeatureRules.evaluate(it, e, 10_000)) }
        }
    }

    @Test
    fun `Pending and Unknown gate as Free`() {
        listOf(Entitlement.Pending("pro"), Entitlement.Unknown).forEach { e ->
            assertEquals(GateState.NeedsPro(20), FeatureRules.evaluate(ProFeature.GALLERY_UNLIMITED, e, 20))
        }
    }

    @Test
    fun `lapse never hides data - 40 items stay, only the next save is gated`() = runTest {
        val entitlement = MutableStateFlow<Entitlement>(pro)
        val gate = EntitlementFeatureGate(entitlement)
        assertTrue(gate.check(ProFeature.GALLERY_UNLIMITED, 40).allowed)
        entitlement.value = Entitlement.Free // the subscription lapsed
        val next = gate.check(ProFeature.GALLERY_UNLIMITED, 40)
        assertFalse("the 41st save needs Pro", next.allowed)
        // Nothing in the catalogue gates reading what exists: only creation/output features are listed,
        // so all 40 items remain viewable, placeable, shareable and deletable.
        assertTrue(ProCatalog.rules.all { it.feature.name !in setOf("GALLERY_VIEW", "GALLERY_PLACE") })
    }

    @Test
    fun `observe follows entitlement and usage`() = runTest {
        val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free)
        val gate = EntitlementFeatureGate(entitlement)
        assertEquals(GateState.Limited(2, 20), gate.observe(ProFeature.GALLERY_UNLIMITED, flowOf(18)).first())
        entitlement.value = pro
        assertEquals(GateState.Allowed, gate.observe(ProFeature.GALLERY_UNLIMITED, flowOf(18)).first())
    }

    @Test
    fun `check waits up to 1 s for a cold-start entitlement`() = runTest {
        val entitlement = MutableStateFlow<Entitlement>(Entitlement.Unknown)
        val gate = EntitlementFeatureGate(entitlement)
        val result = async { gate.check(ProFeature.EXPORT_PDF) }
        advanceTimeBy(500)
        entitlement.value = pro
        runCurrent()
        assertEquals("a Pro user is not shown the paywall right after launch", GateState.Allowed, result.await())
    }

    @Test
    fun `check falls back to Free after 1 s of Unknown`() = runTest {
        val gate = EntitlementFeatureGate(MutableStateFlow(Entitlement.Unknown))
        val result = async { gate.check(ProFeature.EXPORT_PDF) }
        advanceTimeBy(1_001)
        assertEquals(GateState.NeedsPro(0), result.await())
    }

    // ---- N2: a stale cold-start Free must not show a Pro user the paywall ----

    @Test
    fun `N2 stale Free then Pro arrives before the settle - Allowed`() = runTest {
        val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free) // stale cache
        val settled = MutableStateFlow(false)
        val gate = EntitlementFeatureGate(entitlement, settled = settled)
        val result = async { gate.check(ProFeature.GALLERY_UNLIMITED, 20) }
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse("still waiting for Play", result.isCompleted)
        entitlement.value = pro
        settled.value = true
        runCurrent()
        assertEquals(GateState.Allowed, result.await())
    }

    @Test
    fun `N2 Play settles on Free - NeedsPro stands`() = runTest {
        val settled = MutableStateFlow(false)
        val gate = EntitlementFeatureGate(MutableStateFlow(Entitlement.Free), settled = settled)
        val result = async { gate.check(ProFeature.GALLERY_UNLIMITED, 20) }
        advanceTimeBy(1_500)
        settled.value = true
        runCurrent()
        assertEquals(GateState.NeedsPro(20), result.await())
    }

    @Test
    fun `N2 never settles - NeedsPro after 5 s`() = runTest {
        val gate = EntitlementFeatureGate(MutableStateFlow(Entitlement.Free), settled = MutableStateFlow(false))
        val result = async { gate.check(ProFeature.GALLERY_UNLIMITED, 20) }
        advanceTimeBy(EntitlementFeatureGate.SETTLE_WAIT_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(GateState.NeedsPro(20), result.await())
    }

    @Test
    fun `N2 already settled Free - NeedsPro with no wait`() = runTest {
        val gate = EntitlementFeatureGate(MutableStateFlow(Entitlement.Free), settled = MutableStateFlow(true))
        val start = testScheduler.currentTime
        assertEquals(GateState.NeedsPro(20), gate.check(ProFeature.GALLERY_UNLIMITED, 20))
        assertEquals(0L, testScheduler.currentTime - start)
    }

    @Test
    fun `N2 an allowed answer never waits for the settle`() = runTest {
        val gate = EntitlementFeatureGate(MutableStateFlow(Entitlement.Free), settled = MutableStateFlow(false))
        val start = testScheduler.currentTime
        assertEquals(GateState.Limited(2, 20), gate.check(ProFeature.GALLERY_UNLIMITED, 18))
        assertEquals(0L, testScheduler.currentTime - start)
    }
}
