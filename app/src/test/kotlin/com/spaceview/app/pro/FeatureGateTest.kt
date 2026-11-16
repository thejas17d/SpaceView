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
        assertEquals(Allowance.Count(3), free(ProFeature.DEVICE_SCAN))
        assertEquals(Allowance.Count(2), free(ProFeature.COMPARE))
        assertEquals(Allowance.None, free(ProFeature.FURNITURE_TEMPLATES))
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
        assertEquals(listOf(ProFeature.NO_ADS, ProFeature.GALLERY_UNLIMITED), ProCatalog.advertised)
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
        assertEquals(GateState.NeedsPro(3), FeatureRules.evaluate(ProFeature.DEVICE_SCAN, f, 3))
        assertEquals(GateState.Limited(3, 3), FeatureRules.evaluate(ProFeature.DEVICE_SCAN, f, -5))
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
}
