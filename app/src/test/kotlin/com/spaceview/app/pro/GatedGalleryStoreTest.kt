package com.spaceview.app.pro

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.TemplateDraft
import com.spaceview.engine.furniture.Templates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate T2: every template save goes through the template gate, then the Free gallery cap. */
class GatedGalleryStoreTest {
    private val pro = Entitlement.Pro("pro", true, 0L, Entitlement.Pro.Source.PLAY_LIVE)
    private val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free)
    private var items = 0
    private val saved = ArrayList<TemplateDraft>()

    private val gate = EntitlementFeatureGate(entitlement)
    private val store = GatedGalleryStore(
        featureGate = gate,
        saveGate = GallerySaveGate(gate, galleryCount = { items }, deviceScanCount = { 0 }),
        saveTemplateTo = { saved += it; 9L },
    )

    private fun draft(id: String): TemplateDraft {
        val t = Templates.byId(id)!!
        val p = t.resolve(emptyMap())
        return TemplateDraft(
            id, p, Dimension.typed(p.getValue("W")), Dimension.typed(p.getValue("H")), Dimension.typed(p.getValue("D")), null, t.build(p),
        )
    }

    @Test
    fun `a core template saves on Free while the gallery has room`() = runTest {
        items = 19
        assertNull(store.templateBlocker("sofa_v1"))
        assertEquals(GatedSave.Saved(9L), store.saveTemplate(draft("sofa_v1")))
        assertEquals(1, saved.size)
    }

    @Test
    fun `a Pro template on Free is refused before the repository is touched`() = runTest {
        assertEquals(ProFeature.FURNITURE_TEMPLATES, store.templateBlocker("tv_unit_v1"))
        assertEquals(GatedSave.NeedsPro(ProFeature.FURNITURE_TEMPLATES), store.saveTemplate(draft("tv_unit_v1")))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the 21st item on Free needs Pro even for a core template`() = runTest {
        items = 20
        assertEquals(ProFeature.GALLERY_UNLIMITED, store.templateBlocker("bed_v1"))
        assertEquals(GatedSave.NeedsPro(ProFeature.GALLERY_UNLIMITED), store.saveTemplate(draft("bed_v1")))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `Pro saves any template beyond 20 items, and a lapse gates only the next save`() = runTest {
        entitlement.value = pro
        items = 40
        assertEquals(GatedSave.Saved(9L), store.saveTemplate(draft("lsofa_v1")))
        entitlement.value = Entitlement.Free
        assertEquals(GatedSave.NeedsPro(ProFeature.FURNITURE_TEMPLATES), store.saveTemplate(draft("lsofa_v1")))
        assertEquals(1, saved.size)
    }
}
