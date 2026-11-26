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
import org.junit.Assert.assertArrayEquals
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

    private fun vm(saved: SavedStateHandle = SavedStateHandle(), photos: ReviewPhotoStore? = null) =
        ScanViewModel(saved, flowOf(ScanSettings(loaded = true)), store, { "1.57.0" }, { reported++ }, photoStore = photos)

    /** In-memory [ReviewPhotoStore] recording prunes. */
    private class MemoryPhotos : ReviewPhotoStore {
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val pruned = java.util.concurrent.LinkedBlockingQueue<String>()
        override fun put(key: String, jpeg: ByteArray) { files[key] = jpeg }
        override fun get(key: String) = files[key]
        override fun remove(key: String) { files.remove(key) }
        override fun prune(keep: String?) {
            files.keys.filter { it != keep }.forEach { files.remove(it) }
            pruned.put(keep ?: "<none>")
        }
    }

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

    /** PLAN v3.1 W8: adjusted corners re-measure W from geometry, reach the draft, and survive process death. */
    @Test
    fun adjustedCornersAreMeasuredSavedAndSurviveProcessDeath() {
        val frame = listOf(Vec3(-0.3, 1.4, 0.0), Vec3(0.3, 1.4, 0.0), Vec3(0.3, 1.0, 0.0), Vec3(-0.3, 1.0, 0.0))
        val cam = com.spaceview.engine.CaptureCamera(
            com.spaceview.engine.Pose(Vec3(0.0, 1.2, 1.5)), com.spaceview.engine.CameraIntrinsics(500.0, 500.0, 320.0, 240.0), 640, 480,
        )
        fun m(v: Double, b: Double) = Measurement(v, b / Uncertainty.K95)
        val measured = ScanOutline(
            ScanShape.FLAT, m(0.6, 0.012), m(0.4, 0.011), null, m(0.72, 0.013), Quality.FAIR, false, true,
            FrontFace(frame, Vec3.UNIT_Z), FaceChoice(0, Vec3.UP, Vec3.UNIT_Z),
        )
        val auto = FloatArray(8).also { out ->
            frame.forEachIndexed { k, p -> com.spaceview.engine.FaceReprojection.project(p, cam)!!.let { out[2 * k] = it[0].toFloat(); out[2 * k + 1] = it[1].toFloat() } }
        }
        val handle = SavedStateHandle()
        val v = vm(handle)
        v.chooseKind(ObjectKind.FRAME)
        v.onOutline(measured, null, DeviceTier.NO_DEPTH)
        v.onCapture(auto, cam)
        assertEquals(ScanStep.REVIEW, v.state.value.step)
        assertFalse(v.state.value.cornersAdjusted)
        assertTrue(v.state.value.canAdjustCorners)
        // Gate v15: the TalkBack preview measures without applying.
        assertEquals(0.606, v.previewCorners(auto.copyOf().also { it[2] += 2f; it[4] += 2f })!!.first, 1e-5)
        assertFalse(v.state.value.cornersAdjusted)
        // Drag the right side 2 px right: +6 mm at 1.5 m.
        val dragged = auto.copyOf().also { it[2] += 2f; it[4] += 2f }
        v.applyCorners(dragged)
        assertTrue(v.state.value.cornersAdjusted)
        assertEquals(0.606, v.state.value.outline!!.width.value, 1e-5)
        assertEquals("the measured outline is kept for Reset", 0.6, v.state.value.autoOutline!!.width.value, 0.0)
        v.setLabel("Hall frame")

        // Process death: a new view model from the same SavedStateHandle comes back on the Review, adjusted.
        val again = vm(handle)
        val s = again.state.value
        assertEquals(ScanStep.REVIEW, s.step)
        assertTrue(s.restored)
        assertArrayEquals(dragged, s.adjustedCorners!!, 0f)
        assertArrayEquals(auto, s.autoCorners!!, 0f)
        assertEquals(0.606, s.outline!!.width.value, 1e-5)
        assertEquals("Hall frame", s.label)
        again.save("Frame")
        assertEquals(0.606, store.saved.single().width.meters, 1e-3)

        // Crossed corners are refused; Reset to auto brings the measured size back.
        val v2 = vm(SavedStateHandle())
        v2.chooseKind(ObjectKind.FRAME)
        v2.onOutline(measured, null, DeviceTier.NO_DEPTH)
        v2.onCapture(auto, cam)
        v2.applyCorners(auto.copyOf().also { it[0] = auto[2]; it[1] = auto[3]; it[2] = auto[0]; it[3] = auto[1] })
        assertTrue(v2.state.value.cornersRefused)
        assertFalse(v2.state.value.cornersAdjusted)
        v2.applyCorners(dragged)
        v2.resetCornersToAuto()
        assertFalse(v2.state.value.cornersAdjusted)
        assertEquals(0.6, v2.state.value.outline!!.width.value, 0.0)
        // Setting the automatic corners again is "Auto", not an adjustment.
        v2.applyCorners(auto.copyOf())
        assertFalse(v2.state.value.cornersAdjusted)
    }

    /** Gate v8: a new scan prunes stale kept photos; a restored Review keeps its own (onCleared no longer deletes it). */
    @Test
    fun keptPhotoSurvivesUntilTheNextScanAndARestoredReviewKeepsIt() {
        val photos = MemoryPhotos().apply { files["old"] = byteArrayOf(1); files["k1"] = byteArrayOf(2) }
        val h = SavedStateHandle()
        atReview(vm(h))
        h["scan_photo_key"] = "k1"
        vm(h, photos)
        assertEquals("restored Review keeps its photo", "k1", photos.pruned.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("k1"), photos.files.keys)
        vm(SavedStateHandle(), photos)
        assertEquals("a fresh scan prunes everything", "<none>", photos.pruned.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(photos.files.isEmpty())
    }

    /** Gate v9: a write that finishes after the Review moved on is removed again. */
    @Test
    fun aLateWriteNeverLeavesAPhoto() {
        val photos = MemoryPhotos()
        val v = vm(SavedStateHandle(), photos)
        v.keepIfCurrent(photos, "stale", byteArrayOf(1)) // photoKey is null: the Review has moved on
        assertTrue(photos.files.isEmpty())
        val file = java.io.File.createTempFile("review", "dir").apply { delete(); mkdirs() }
        val disk = FileReviewPhotoStore(file)
        disk.put("a-1", byteArrayOf(1, 2)); disk.put("b-2", byteArrayOf(3))
        assertNull("put keeps only the newest", disk.get("a-1"))
        disk.prune("b-2")
        assertEquals(1, disk.get("b-2")!!.size)
        disk.prune(null)
        assertNull(disk.get("b-2"))
        file.deleteRecursively()
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
        // While measuring (outline or photo step) the AR anchors cannot survive: measure again, same kind.
        val handle = SavedStateHandle()
        val v = vm(handle)
        v.chooseKind(ObjectKind.FRAME)
        v.onOutline(outline(), null, DeviceTier.NO_DEPTH)
        assertEquals(ScanStep.PHOTO, v.state.value.step)
        val restored = vm(handle)
        assertEquals(ScanStep.OUTLINE, restored.state.value.step)
        assertEquals(ObjectKind.FRAME, restored.state.value.kind)
        assertNull(restored.state.value.outline)
        assertEquals(ScanStep.TYPE, vm(SavedStateHandle()).state.value.step)
        // PLAN v3.1 W8: on the Review the measured numbers need no anchors, so the Review comes back.
        val h2 = SavedStateHandle()
        atReview(vm(h2))
        val review = vm(h2).state.value
        assertEquals(ScanStep.REVIEW, review.step)
        assertEquals(0.6, review.outline!!.width.value, 1e-9)
        assertTrue(review.restored)
    }
}
