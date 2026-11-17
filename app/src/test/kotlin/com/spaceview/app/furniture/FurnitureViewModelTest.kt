package com.spaceview.app.furniture

import androidx.lifecycle.SavedStateHandle
import com.spaceview.app.pro.GatedSave
import com.spaceview.app.pro.ProFeature
import com.spaceview.app.scan.ScanSettings
import com.spaceview.data.gallery.CaptureMethod
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.TemplateDraft
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.furniture.FurniturePalettes
import com.spaceview.engine.furniture.FurnitureParams
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The furniture flow: gating before AR and at Save, the guided measure, the typed path and process death. */
@OptIn(ExperimentalCoroutinesApi::class)
class FurnitureViewModelTest {

    private class FakeStore : FurnitureStore {
        var blocker: ProFeature? = null
        var blockerThrows = false
        var saveResult: ((TemplateDraft) -> GatedSave)? = null
        var failNext = false
        var gate: CompletableDeferred<Unit>? = null
        val asked = ArrayList<String>()
        val saved = ArrayList<TemplateDraft>()

        override suspend fun templateBlocker(templateId: String): ProFeature? {
            asked += templateId
            if (blockerThrows) throw IOException("count failed")
            return blocker
        }

        override suspend fun save(draft: TemplateDraft): GatedSave {
            gate?.await()
            if (failNext) {
                failNext = false
                throw IOException("disk full")
            }
            saveResult?.let { return it(draft) }
            saved += draft
            return GatedSave.Saved(70L + saved.size)
        }

        override suspend fun get(id: Long): GalleryItem? = null
    }

    private val store = FakeStore()
    private var savedCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) =
        FurnitureViewModel(saved, flowOf(ScanSettings(loaded = true)), store, { "1.57.0" }, { savedCount++ }, cpu = Dispatchers.Unconfined)

    private fun m(meters: Double, bound95: Double = 0.012) = Measurement(meters, bound95 / Uncertainty.K95)

    /** Collects the route events (paywall opens). */
    private fun FurnitureViewModel.paywalls(scope: TestScope): List<ProFeature> {
        val out = ArrayList<ProFeature>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            events.collect { if (it is FurnitureEvent.OpenPaywall) out += it.feature }
        }
        return out
    }

    @Test
    fun `a core template starts without a paywall, after the gates were asked`() = runTest {
        val vm = vm()
        val paywalls = vm.paywalls(this)
        vm.pick("sofa_v1")
        assertEquals(listOf("sofa_v1"), store.asked)
        assertEquals(FurnitureStep.OPTIONS, vm.state.value.step)
        assertEquals("sofa_v1", vm.state.value.inputs!!.templateId)
        assertTrue(paywalls.isEmpty())
    }

    @Test
    fun `a Pro template on Free opens the paywall from the picker, before any AR`() = runTest {
        store.blocker = ProFeature.FURNITURE_TEMPLATES
        val vm = vm()
        val paywalls = vm.paywalls(this)
        vm.pick("lsofa_v1")
        assertEquals(listOf(ProFeature.FURNITURE_TEMPLATES), paywalls)
        assertEquals("never reaches the guided measure", FurnitureStep.PICK, vm.state.value.step)
        assertNull(vm.state.value.inputs)
        assertNull(vm.state.value.checking)
    }

    @Test
    fun `a full Free gallery opens the gallery paywall at the picker`() = runTest {
        store.blocker = ProFeature.GALLERY_UNLIMITED
        val vm = vm()
        val paywalls = vm.paywalls(this)
        vm.pick("bed_v1")
        assertEquals(listOf(ProFeature.GALLERY_UNLIMITED), paywalls)
        assertEquals(FurnitureStep.PICK, vm.state.value.step)
    }

    @Test
    fun `a gate read error never blocks the user (Save checks again)`() = runTest {
        store.blockerThrows = true
        val vm = vm()
        vm.pick("wardrobe_v1")
        assertEquals(FurnitureStep.OPTIONS, vm.state.value.step)
    }

    @Test
    fun `a template without options goes straight to the guided measure`() = runTest {
        val vm = vm()
        vm.pick("dining_chair_v1")
        assertEquals(FurnitureStep.MEASURE, vm.state.value.step)
        assertEquals(W, vm.state.value.currentSpec!!.id)
    }

    @Test
    fun `guided measure - W D H then the key extras, each with its own step, then preview, review and save`() = runTest {
        val vm = vm()
        vm.pick("sofa_v1")
        vm.choosePreset("three_seat")
        vm.startMeasuring()
        val s0 = vm.state.value
        assertEquals(listOf(W, D, H, FurnitureParams.SEAT, FurnitureParams.ARM_H, FurnitureParams.ARM_W), s0.measureSteps.map { it.id })
        assertEquals(2, s0.stepNumber) // options = 1, width = 2
        assertEquals(1 + 6 + 2, s0.stepTotal)
        vm.useMeasured(W, m(2.1234), DeviceTier.DEPTH)
        vm.useMeasured(D, m(0.9), DeviceTier.DEPTH)
        vm.useMeasured(H, m(0.86), DeviceTier.DEPTH)
        assertEquals(FurnitureParams.SEAT, vm.state.value.currentSpec!!.id)
        vm.useMeasured(FurnitureParams.SEAT, m(0.45), DeviceTier.DEPTH)
        // An extra may stay typical.
        vm.resetLength(FurnitureParams.ARM_H)
        vm.nextMeasure()
        vm.typeLength(FurnitureParams.ARM_W, 0.2)
        assertEquals(FurnitureStep.PREVIEW, vm.state.value.step)
        vm.toReview()
        val s = vm.state.value
        assertEquals(FurnitureStep.REVIEW, s.step)
        assertTrue(s.canSave)
        vm.setPalette(2)
        vm.setLabel("Living-room sofa")
        vm.save("Sofa")
        assertEquals(FurnitureStep.SAVED, vm.state.value.step)
        assertEquals(71L, vm.state.value.savedId)
        assertEquals("one completed scan for the ad cap", 1, savedCount)
        val draft = store.saved.single()
        assertEquals("sofa_v1", draft.templateId)
        assertEquals(DimensionSource.MEASURED, draft.width.source)
        assertEquals(2.1234, draft.width.meters, 1e-12)
        assertTrue("± rounded up, never shrunk", draft.width.bound95 >= 0.012 - 1e-12 && draft.width.bound95 <= 0.0121 + 1e-12)
        assertEquals(3.0, draft.params.getValue(FurnitureParams.SEATS), 0.0)
        assertEquals(0.2, draft.params.getValue(FurnitureParams.ARM_W), 0.0)
        assertEquals(2.0, draft.params.getValue(FurniturePalettes.KEY), 0.0)
        assertEquals("Living-room sofa", draft.label)
        assertEquals(CaptureMethod.MANUAL, draft.captureMethod)
        assertEquals(DeviceTier.DEPTH, draft.deviceTier)
        assertEquals("1.57.0", draft.arcoreVersion)
        assertEquals(1.0, draft.calibrationFactor!!, 0.0)
        // Saving twice is impossible.
        vm.save("Sofa")
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `a measured size the shape cannot have is refused before it is used`() = runTest {
        val vm = vm()
        vm.pick("sofa_v1")
        vm.startMeasuring()
        val s = vm.state.value
        val t = s.template!!
        assertNotNull("a 50 cm sofa", FurnitureFlow.errorIfSet(t, s.inputs!!, W, 0.5))
        assertNull(FurnitureFlow.errorIfSet(t, s.inputs!!, W, 2.0))
    }

    @Test
    fun `typed path - no camera, W D H must be typed before Save, typed values are estimates`() = runTest {
        val vm = vm()
        vm.pick("wardrobe_v1")
        vm.typeInstead()
        assertEquals(FurnitureStep.REVIEW, vm.state.value.step)
        assertFalse("typical W/D/H are never saved as the item's size", vm.state.value.canSave)
        assertEquals(listOf(W, D, H), FurnitureFlow.missingRequired(vm.state.value.inputs!!))
        vm.typeLength(W, 1.5)
        vm.typeLength(D, 0.6)
        assertFalse(vm.state.value.canSave)
        vm.typeLength(H, 2.1)
        assertTrue(vm.state.value.canSave)
        vm.save("Wardrobe")
        val draft = store.saved.single()
        assertEquals(DimensionSource.TYPED, draft.height.source)
        assertEquals(0.0, draft.height.bound95, 0.0)
        assertNull("nothing measured", draft.captureMethod)
        assertNull(draft.arcoreVersion)
        // Back from Review on the typed path returns to the options.
        val vm2 = vm()
        vm2.pick("wardrobe_v1")
        vm2.typeInstead()
        vm2.back()
        assertEquals(FurnitureStep.OPTIONS, vm2.state.value.step)
    }

    @Test
    fun `needs Pro at Save keeps the draft and offers the paywall`() = runTest {
        store.saveResult = { GatedSave.NeedsPro(ProFeature.GALLERY_UNLIMITED) }
        val vm = vm()
        val paywalls = vm.paywalls(this)
        readyBed(vm)
        vm.save("Bed")
        val s = vm.state.value
        assertEquals(ProFeature.GALLERY_UNLIMITED, s.needsPro)
        assertEquals(FurnitureStep.REVIEW, s.step)
        assertFalse(s.saving)
        assertEquals("nothing saved, nothing counted", 0, savedCount)
        assertEquals(3, s.inputs!!.lengths.size)
        vm.upgrade()
        assertEquals(listOf(ProFeature.GALLERY_UNLIMITED), paywalls)
        // After upgrading, Save goes through with the same values.
        store.saveResult = null
        vm.save("Bed")
        assertEquals(FurnitureStep.SAVED, vm.state.value.step)
        assertEquals(1, savedCount)
    }

    @Test
    fun `a storage failure is reported and Save can be retried`() = runTest {
        store.failNext = true
        val vm = vm()
        readyBed(vm)
        vm.save("Bed")
        assertTrue(vm.state.value.saveFailed)
        assertNull(vm.state.value.savedId)
        vm.save("Bed")
        assertEquals(FurnitureStep.SAVED, vm.state.value.step)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun `Back while saving is ignored and a second tap does not save twice`() = runTest {
        store.gate = CompletableDeferred()
        val vm = vm()
        readyBed(vm)
        vm.save("Bed")
        assertTrue(vm.state.value.saving)
        vm.save("Bed")
        vm.backToPicker()
        assertEquals(FurnitureStep.REVIEW, vm.state.value.step)
        store.gate!!.complete(Unit)
        assertEquals(1, store.saved.size)
        assertEquals(FurnitureStep.SAVED, vm.state.value.step)
    }

    @Test
    fun `process death mid-measure restores the step, the values with their ± and the options`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.pick("lsofa_v1")
        first.setOption(FurnitureParams.SIDE, FurnitureParams.SIDE_LEFT)
        first.startMeasuring()
        first.useMeasured(W, m(2.8, 0.021), DeviceTier.NO_DEPTH)
        first.typeLength(D, 1.7)
        first.setPalette(3)
        assertEquals(2, first.state.value.measureIndex)

        val restored = vm(handle).state.value
        assertEquals(FurnitureStep.MEASURE, restored.step)
        assertEquals(2, restored.measureIndex)
        assertEquals(H, restored.currentSpec!!.id)
        val w = restored.inputs!!.lengths.getValue(W)
        assertEquals(DimensionSource.MEASURED, w.source)
        assertEquals(2.8, w.meters, 1e-12)
        assertTrue(w.bound95 >= 0.021 - 1e-12 && w.bound95 <= 0.0211 + 1e-12)
        assertEquals(DimensionSource.TYPED, restored.inputs!!.lengths.getValue(D).source)
        assertEquals(FurnitureParams.SIDE_LEFT, restored.inputs!!.options.getValue(FurnitureParams.SIDE))
        assertEquals(3, restored.inputs!!.paletteIndex)
    }

    @Test
    fun `process death after Save comes back on Saved and asks for the item`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(handle)
        readyBed(first)
        first.setLabel("Guest bed")
        first.save("Bed")
        val restored = vm(handle).state.value
        assertEquals(FurnitureStep.SAVED, restored.step)
        assertEquals(71L, restored.savedId)
        assertEquals(1, savedCount)
    }

    @Test
    fun `process death on Review keeps the name and where Back goes`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.pick("bed_v1")
        first.typeInstead()
        first.setLabel("Kids' bed")
        val restored = vm(handle)
        assertEquals(FurnitureStep.REVIEW, restored.state.value.step)
        assertEquals("Kids' bed", restored.state.value.label)
        restored.back()
        assertEquals(FurnitureStep.OPTIONS, restored.state.value.step)
    }

    @Test
    fun `a corrupt or unknown saved template restarts at the picker`() = runTest {
        val handle = SavedStateHandle(mapOf(FurnitureViewModel.KEY_TEMPLATE to "sofa_v99", FurnitureViewModel.KEY_STEP to "MEASURE"))
        assertEquals(FurnitureStep.PICK, vm(handle).state.value.step)
    }

    @Test
    fun `preview opened from Review returns to Review, from the measure to the last measure step`() = runTest {
        val vm = vm()
        readyBed(vm)
        vm.toPreview()
        assertEquals(FurnitureStep.PREVIEW, vm.state.value.step)
        vm.back()
        assertEquals(FurnitureStep.REVIEW, vm.state.value.step)
        val vm2 = vm()
        vm2.pick("bed_v1")
        vm2.startMeasuring()
        repeat(vm2.state.value.measureSteps.size) { vm2.typeLength(vm2.state.value.currentSpec!!.id, listOf(1.6, 2.0, 1.0, 0.5, 0.2)[it]) }
        assertEquals(FurnitureStep.PREVIEW, vm2.state.value.step)
        vm2.back()
        assertEquals(FurnitureStep.MEASURE, vm2.state.value.step)
        assertEquals(vm2.state.value.measureSteps.size - 1, vm2.state.value.measureIndex)
    }

    @Test
    fun `leaving the AR step without a camera keeps what was measured`() = runTest {
        val vm = vm()
        vm.pick("sofa_v1")
        vm.startMeasuring()
        vm.leaveMeasure()
        assertEquals("nothing measured: back to options", FurnitureStep.OPTIONS, vm.state.value.step)
        vm.startMeasuring()
        vm.useMeasured(W, m(2.0), DeviceTier.NO_DEPTH)
        vm.leaveMeasure()
        assertEquals(FurnitureStep.REVIEW, vm.state.value.step)
        assertEquals(1, vm.state.value.inputs!!.lengths.size)
    }

    @Test
    fun `the preview model is built for valid values only`() = runTest {
        val vm = vm()
        readyBed(vm)
        val glb = vm.previewGlb.first { it != null }
        assertTrue(glb!!.size in 1..20_000)
        vm.typeLength(H, 3.0) // taller than any bed: invalid
        assertTrue(vm.state.value.errors.isNotEmpty())
        assertNull(vm.previewGlb.first { it == null })
    }

    /** A bed with W, D, H typed (headboard by default), on Review. */
    private fun readyBed(vm: FurnitureViewModel) {
        vm.pick("bed_v1")
        vm.typeInstead()
        vm.typeLength(W, 1.6)
        vm.typeLength(D, 2.0)
        vm.typeLength(H, 1.0)
        assertTrue(vm.state.value.canSave)
    }
}
