package com.spaceview.app.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** P1 review MAJOR 3: the Free gallery cap (20), enforced for new items only. */
class GallerySaveGateTest {
    private val pro = Entitlement.Pro("pro", true, 0L, Entitlement.Pro.Source.PLAY_LIVE)
    private val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free)
    private var items = 0
    private var failCount = false

    private val gate = GallerySaveGate(
        gate = EntitlementFeatureGate(entitlement),
        galleryCount = { if (failCount) throw IOException("db") else items },
    )

    @Test
    fun `free saves up to 20, the 21st needs Pro`() = runTest {
        items = 19
        assertEquals(GateState.Limited(1, 20), gate.checkItem())
        items = 20
        assertEquals(GateState.NeedsPro(20), gate.checkItem())
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
    fun `an unreadable gallery never blocks the user`() = runTest {
        failCount = true
        assertEquals(GateState.Allowed, gate.checkItem())
    }
}
