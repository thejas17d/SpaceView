package com.spaceview.app.scan

import androidx.lifecycle.SavedStateHandle
import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ScanDraft
import com.spaceview.app.scan.imaging.CutoutChoice
import com.spaceview.app.scan.imaging.CutoutStatus
import com.spaceview.engine.ArgbImage
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
import org.junit.Assert.assertSame
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

    private val rect = ArgbImage(4, 3, IntArray(12) { 0xFF336699.toInt() })
    private val cut = ArgbImage(2, 3, IntArray(6) { if (it % 2 == 0) 0xFF336699.toInt() else 0x00336699 })

    /** PLAN v3 W2: the Review saves the cutout; "Undo cutout" saves the rectangle; Redo brings the cutout back. */
    @Test
    fun cutoutIsSavedUnlessUndone() {
        val v = atReview()
        v.onPictures(CutoutChoice(rect, cut, CutoutStatus.REMOVED), null, null)
        assertTrue(v.state.value.hasCutout)
        assertFalse(v.state.value.photoBusy)
        v.setCutoutUndone(true)
        assertTrue(v.state.value.cutoutUndone)
        v.setCutoutUndone(false)
        v.save("Chair")
        assertSame(cut, store.saved.single().cutout!!.image)

        val w = atReview(vm())
        w.onPictures(CutoutChoice(rect, cut, CutoutStatus.REMOVED), null, null)
        w.setCutoutUndone(true)
        w.save("Chair")
        assertSame("undone: the rectangle", rect, store.saved[1].cutout!!.image)
        // After saving, the choice can no longer change.
        w.setCutoutUndone(false)
        assertTrue(w.state.value.cutoutUndone)
    }

    /** A segmentation that could not run or be trusted keeps the rectangle, says why, and offers no undo. */
    @Test
    fun untrustedCutoutKeepsTheRectangle() {
        for (status in listOf(CutoutStatus.NOT_AVAILABLE, CutoutStatus.NOT_SURE, CutoutStatus.FLAT)) {
            val v = atReview(vm())
            v.onPictures(CutoutChoice(rect, null, status), null, null)
            assertEquals(status, v.state.value.cutoutStatus)
            assertFalse(v.state.value.hasCutout)
            v.setCutoutUndone(true)
            assertFalse("nothing to undo", v.state.value.cutoutUndone)
            v.save("Box")
            assertSame(rect, store.saved.last().cutout!!.image)
        }
        // A REMOVED status without a picture is not a cutout.
        val w = atReview(vm())
        w.onPictures(CutoutChoice(rect, null, CutoutStatus.REMOVED), null, null)
        assertFalse(w.state.value.hasCutout)
        assertEquals(CutoutStatus.NOT_SURE, w.state.value.cutoutStatus)
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
        // PLAN v3 W2: still a cutout item, a plain true-size panel without a picture.
        assertNull(d.cutout!!.image)
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

    /** P1 review MAJOR 3 (Builder B): the Free gallery cap at Save keeps the scan; Save works again after upgrading. */
    @Test
    fun galleryFullKeepsTheDraftAndSavesAfterUpgrade() {
        var allowed = false
        val v = atReview(ScanViewModel(SavedStateHandle(), flowOf(ScanSettings(loaded = true)), store, { "1.57.0" }, { reported++ }, canSave = { allowed }))
        v.setLabel("Sofa table")
        v.save("Frame")
        val s = v.state.value
        assertTrue(s.galleryFull)
        assertFalse(s.saving)
        assertEquals(ScanStep.REVIEW, s.step)
        assertEquals("Sofa table", s.label)
        assertTrue("values kept", s.values != null)
        assertEquals("nothing stored", 0, store.saved.size)
        assertEquals("not counted as a saved scan", 0, reported)
        allowed = true // the user bought Pro and came back
        v.save("Frame")
        assertFalse(v.state.value.galleryFull)
        assertEquals("Sofa table", store.saved.single().label)
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
