package com.spaceview.app.scan

import androidx.lifecycle.SavedStateHandle
import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ScanDraft
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.Vec3
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private class FakeStore : ScanStore {
        val saved = ArrayList<ScanDraft>()
        var gate: CompletableDeferred<Unit>? = null
        var failNext = false
        val gets = ArrayList<Long>()

        override suspend fun save(draft: ScanDraft): Long {
            gate?.await()
            if (failNext) {
                failNext = false
                throw IOException("disk full")
            }
            saved += draft
            return 40L + saved.size
        }

        override suspend fun get(id: Long): GalleryItem? {
            gets += id
            return null
        }

        override suspend fun shareCopy(id: Long, fileName: String): File? = null
    }

    private val store = FakeStore()
    private var reported = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) =
        ScanViewModel(saved, flowOf(ScanSettings(loaded = true)), store, { "1.57.0" }, { reported++ })

    private fun outline(): ScanOutline {
        val face = FrontFace(listOf(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3.ZERO), Vec3.UNIT_Z)
        fun m(v: Double, b: Double) = Measurement(v, b / Uncertainty.K95)
        return ScanOutline(
            ScanShape.FLAT, m(0.6, 0.012), m(0.4, 0.011), null, m(0.72, 0.013), Quality.FAIR, false, true, face,
            FaceChoice(0, Vec3.UP, Vec3.UNIT_Z),
        )
    }

    /** Type → outline → skip photo → Review. */
    private fun atReview(v: ScanViewModel = vm()): ScanViewModel {
        v.chooseKind(ObjectKind.FRAME)
        v.onOutline(outline(), null, DeviceTier.NO_DEPTH)
        assertEquals(ScanStep.PHOTO, v.state.value.step)
        v.skipPhoto()
        assertEquals(ScanStep.REVIEW, v.state.value.step)
        return v
    }

    @Test
    fun doubleSaveStoresOnceAndCountsOnce() {
        val v = atReview()
        store.gate = CompletableDeferred()
        v.save("Frame")
        v.save("Frame")
        assertTrue(v.state.value.saving)
        store.gate!!.complete(Unit)
        assertEquals(1, store.saved.size)
        assertEquals(1, reported)
        assertEquals(ScanStep.SAVED, v.state.value.step)
        assertEquals(41L, v.state.value.savedId)
        v.save("Frame")
        assertEquals(1, store.saved.size)
        assertEquals(listOf(41L), store.gets)
    }

    @Test
    fun skippedPhotoSavesWithoutJpegAndWithTheReviewedValues() {
        val v = atReview()
        v.setLabel("  Café frame ")
        v.save("Frame")
        val d = store.saved.single()
        assertNull(d.frontJpeg)
        assertEquals("Café frame", d.label)
        assertEquals(ObjectKind.FRAME, d.kind)
        assertEquals(v.state.value.values!!.width, d.width)
        assertTrue(d.depth.isEstimate)
        assertEquals("1.57.0", d.arcoreVersion)
    }

    @Test
    fun aFailedSaveIsReportedAndCanBeRetried() {
        val v = atReview()
        store.failNext = true
        v.save("Frame")
        assertTrue(v.state.value.saveFailed)
        assertFalse(v.state.value.saving)
        assertEquals(0, reported)
        assertEquals(ScanStep.REVIEW, v.state.value.step)
        v.save("Frame")
        assertEquals(1, store.saved.size)
        assertEquals(1, reported)
    }

    /** M3 review m4: Measure again / Back cannot start the camera while the save runs. */
    @Test
    fun remeasureAndBackAreIgnoredWhileSaving() {
        val v = atReview()
        store.gate = CompletableDeferred()
        v.save("Frame")
        v.remeasure()
        v.backToType()
        assertEquals(ScanStep.REVIEW, v.state.value.step)
        store.gate!!.complete(Unit)
        assertEquals(ScanStep.SAVED, v.state.value.step)
        // After saving, the scan cannot be thrown back into measuring either.
        v.remeasure()
        assertEquals(ScanStep.SAVED, v.state.value.step)
    }

    @Test
    fun reviewWorkNeedsConfirmation() {
        val v = atReview()
        assertFalse(v.state.value.hasReviewWork)
        v.edit(ScanDim.DEPTH, 0.05)
        assertTrue(v.state.value.hasReviewWork)
        v.remeasure()
        assertEquals(ScanStep.OUTLINE, v.state.value.step)
        assertNull(v.state.value.outline)
        assertFalse(v.state.value.hasReviewWork)
    }

    /** M3 review m5: process death on the Saved screen comes back to Saved, not to step 1. */
    @Test
    fun processDeathOnSavedRestoresSaved() {
        val handle = SavedStateHandle()
        val first = atReview(vm(handle))
        first.save("Frame")
        val restored = vm(handle)
        assertEquals(ScanStep.SAVED, restored.state.value.step)
        assertEquals(41L, restored.state.value.savedId)
        assertEquals(ObjectKind.FRAME, restored.state.value.kind)
        assertEquals(listOf(41L, 41L), store.gets) // the restored screen reloads its item
        assertEquals(1, reported) // restoring never counts the scan again
    }

    @Test
    fun processDeathWhileMeasuringRestartsTheOutline() {
        val handle = SavedStateHandle()
        atReview(vm(handle))
        val restored = vm(handle)
        // The AR outline cannot survive (anchors die with ARCore): measure again, same kind.
        assertEquals(ScanStep.OUTLINE, restored.state.value.step)
        assertEquals(ObjectKind.FRAME, restored.state.value.kind)
        assertNull(restored.state.value.outline)
        assertEquals(ScanStep.TYPE, vm(SavedStateHandle()).state.value.step)
    }
}
