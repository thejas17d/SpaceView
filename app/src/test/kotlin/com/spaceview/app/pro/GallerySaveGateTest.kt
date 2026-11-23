package com.spaceview.app.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** P1 review MAJOR 3: the Free gallery cap (20) and 3D-scan quota (5 in total), enforced for new items only. */
class GallerySaveGateTest {
    private val pro = Entitlement.Pro("pro", true, 0L, Entitlement.Pro.Source.PLAY_LIVE)
    private val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free)
    private var items = 0
    private var meshes = 0
    private var failCount = false

    private val gate = GallerySaveGate(
        gate = EntitlementFeatureGate(entitlement),
        galleryCount = { if (failCount) throw IOException("db") else items },
        deviceScanCount = { meshes },
    )

    @Test
    fun `free saves up to 20, the 21st needs Pro`() = runTest {
        items = 19
        assertEquals(GateState.Limited(1, 20), gate.checkItem())
        items = 20
        assertEquals(GateState.NeedsPro(20), gate.checkItem())
        assertEquals(ProFeature.GALLERY_UNLIMITED, GallerySaveGate.featureFor(gate.checkItem()))
    }

    @Test
    fun `Pro saves without limit, and a lapse only blocks the next save`() = runTest {
        entitlement.value = pro
        items = 40
        assertTrue(gate.checkItem().allowed)
        entitlement.value = Entitlement.Free
        assertFalse("only the 41st save is gated; the 40 stay", gate.checkItem().allowed)
    }

    @Test
    fun `3D scans have their own quota of 5 inside the gallery cap`() = runTest {
        items = 5
        meshes = 4
        assertEquals(GateState.Limited(1, 5), gate.checkDeviceScan())
        meshes = 5
        val s = gate.checkDeviceScan()
        assertEquals(GateState.NeedsPro(5), s)
        assertEquals(ProFeature.DEVICE_SCAN, GallerySaveGate.featureFor(s, deviceScan = true))
        items = 20
        meshes = 0
        assertEquals("a full gallery blocks a 3D scan too", GateState.NeedsPro(20), gate.checkDeviceScan())
        assertEquals(ProFeature.GALLERY_UNLIMITED, GallerySaveGate.featureFor(gate.checkDeviceScan(), deviceScan = true))
        entitlement.value = pro
        meshes = 50
        assertTrue(gate.checkDeviceScan().allowed)
    }

    @Test
    fun `an unreadable gallery never blocks the user`() = runTest {
        failCount = true
        assertEquals(GateState.Allowed, gate.checkItem())
    }
}
