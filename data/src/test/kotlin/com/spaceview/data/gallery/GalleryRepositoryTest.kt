package com.spaceview.data.gallery

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Quality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** In-memory DAO mirroring the SQL semantics the repository relies on. */
private open class FakeGalleryDao : GalleryDao {
    val rows = MutableStateFlow<List<GalleryItemEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(entity: GalleryItemEntity): Long {
        val id = nextId++
        rows.value = rows.value + entity.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<GalleryItemEntity>> =
        rows.map { l -> l.sortedWith(compareByDescending<GalleryItemEntity> { it.createdAt }.thenByDescending { it.id }) }

    override fun observe(id: Long): Flow<GalleryItemEntity?> = rows.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun allFileRefs() = rows.value.map { GalleryFileRefs(it.id, it.modelPath, it.photoPath, it.thumbnailPath) }

    override suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(modelPath = modelPath, photoPath = photoPath, thumbnailPath = thumbnailPath, totalBytes = totalBytes) else it
        }
    }

    override suspend fun setLabel(id: Long, label: String?, updatedAt: Long): Int {
        var n = 0
        rows.value = rows.value.map { if (it.id == id) { n++; it.copy(label = label, updatedAt = updatedAt) } else it }
        return n
    }

    override suspend fun setDimensions(
        id: Long, widthM: Double, widthB: Double, widthS: String, heightM: Double, heightB: Double, heightS: String,
        depthM: Double, depthB: Double, depthS: String, quality: String, modelPath: String, totalBytes: Long, updatedAt: Long,
    ): Int {
        var n = 0
        rows.value = rows.value.map {
            if (it.id == id) {
                n++
                it.copy(
                    widthMeters = widthM, widthBound95 = widthB, widthSource = widthS,
                    heightMeters = heightM, heightBound95 = heightB, heightSource = heightS,
                    depthMeters = depthM, depthBound95 = depthB, depthSource = depthS,
                    quality = quality, modelPath = modelPath, totalBytes = totalBytes, updatedAt = updatedAt,
                )
            } else {
                it
            }
        }
        return n
    }

    override suspend fun delete(id: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.id == id }
        return before - rows.value.size
    }
}

/** Rolls back the fake DAO when the block throws (like a Room transaction). */
private class FakeTransactions(private val dao: FakeGalleryDao) : GalleryTransactions {
    override suspend fun <R> run(block: suspend () -> R): R {
        val snapshot = dao.rows.value
        try {
            return block()
        } catch (e: Throwable) {
            dao.rows.value = snapshot
            throw e
        }
    }
}

class GalleryRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L

    private val filesRoot get() = File(tmp.root, "files")
    private val cacheRoot get() = File(tmp.root, "cache")

    private fun repo(
        dao: FakeGalleryDao = FakeGalleryDao(),
        codec: GalleryImageCodec = JvmImageCodec,
        transactions: GalleryTransactions = FakeTransactions(dao),
    ) = GalleryRepository(
        dao, filesRoot, codec, clock = { now }, io = Dispatchers.IO, transactions = transactions, cacheDir = cacheRoot,
    )

    private fun draft(
        jpeg: ByteArray? = typicalJpeg,
        kind: ObjectKind = ObjectKind.FRAME,
        label: String? = "  Café frame  ",
    ) = ScanDraft(
        kind = kind,
        width = Dimension(0.457, 0.009, false),
        height = Dimension(0.610, 0.011, false),
        depth = Dimension(0.025, 0.0, true),
        quality = Quality.FAIR,
        calibrated = false,
        deviceTier = DeviceTier.NO_DEPTH,
        frontJpeg = jpeg,
        label = label,
        arcoreVersion = "1.57.0",
    )

    private val galleryDir get() = File(filesRoot, "gallery")

    @Test
    fun saveWritesModelPhotoThumbnailAndRow() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(draft())
        val item = repo.get(id)!!
        assertEquals(ObjectKind.FRAME, item.kind)
        assertEquals("Café frame", item.label)
        assertEquals(0.457, item.width.meters, 0.0)
        assertTrue(item.depth.isEstimate)
        assertEquals(Quality.FAIR, item.quality)
        assertEquals(DeviceTier.NO_DEPTH, item.deviceTier)
        assertEquals("1.57.0", item.arcoreVersion)
        assertEquals(File(filesRoot, "gallery/$id/model.glb"), item.modelFile)
        assertTrue(item.modelFile.isFile)
        assertTrue(item.photoFile!!.isFile)
        assertTrue(item.thumbnailFile!!.isFile)
        assertEquals("gallery/$id/front.jpg", dao.rows.value.single().photoPath)
        assertEquals(item.modelFile.length() + item.photoFile.length() + item.thumbnailFile.length(), item.bytes)
        // No temp files or staging directories left behind.
        assertEquals(listOf(id.toString()), galleryDir.list()!!.toList())
        assertEquals(setOf("model.glb", "front.jpg", "thumb.webp"), File(galleryDir, "$id").list()!!.toSet())

        val glb = Glb(item.modelFile.readBytes())
        assertTrue(glb.json.contains("\"widthM\":0.457"))
        assertTrue(glb.json.contains("\"depthM\":0.025"))
        assertTrue(glb.json.contains("\"origin\":\"BACK_CENTER\""))
        assertTrue(glb.json.contains("image/jpeg"))
        // The GLB embeds exactly the stored front photo.
        assertTrue(glb.containsBytes(item.photoFile.readBytes()))
    }

    @Test
    fun frontPhotoIsCappedAt1024AndThumbnailAt256() = runTest {
        val big = JvmImageIo.encodeJpeg(2048, 1536, TestPhotos.typicalFrame(2048, 1536), 0.95f)
        val repo = repo()
        val item = repo.get(repo.save(draft(jpeg = big)))!!
        val (pw, ph, _) = JvmImageIo.decode(item.photoFile!!.readBytes())!!
        assertEquals(1024, pw)
        assertEquals(768, ph)
        val (tw, th, _) = JvmImageIo.decode(item.thumbnailFile!!.readBytes())!!
        assertEquals(256, tw)
        assertEquals(192, th)
    }

    @Test
    fun typicalScanIsWithinHalfAMegabyte() = runTest {
        val repo = repo()
        val item = repo.get(repo.save(draft()))!!
        report("typical 1024x768", item)
        assertTrue("typical scan is ${item.bytes} B", item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
        assertEquals(item.bytes, repo.totalBytes())
    }

    @Test
    fun detailedScanIsWithinHalfAMegabyte() = runTest {
        val src = JvmImageIo.encodeJpeg(1024, 768, TestPhotos.detailedPoster(), 0.92f)
        val repo = repo()
        val item = repo.get(repo.save(draft(jpeg = src)))!!
        report("detailed 1024x768", item)
        assertTrue("detailed scan is ${item.bytes} B", item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
    }

    @Test
    fun worstCaseNoiseStepsDownButStaysWithinBudget() = runTest {
        val src = JvmImageIo.encodeJpeg(1024, 1024, TestPhotos.noisy(), 0.92f)
        val repo = repo()
        val images = repo.encode(ObjectKind.FRAME, draft().width, draft().height, draft().depth, null, src)
        assertTrue("noise must force a step-down", images.frontQuality < 82 || images.frontWidth < 1024)
        assertTrue(images.front != null)
        val item = repo.get(repo.save(draft(jpeg = src)))!!
        report("noise 1024x1024 (q${images.frontQuality}, ${images.frontWidth}px)", item)
        assertTrue("noisy scan is ${item.bytes} B", item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
    }

    @Test
    fun noPhotoGivesFlatColourModelOnly() = runTest {
        val repo = repo()
        val item = repo.get(repo.save(draft(jpeg = null, kind = ObjectKind.APPLIANCE)))!!
        assertNull(item.photoFile)
        assertNull(item.thumbnailFile)
        val glb = Glb(item.modelFile.readBytes())
        assertFalse(glb.json.contains("image/jpeg"))
        assertTrue(glb.json.contains("\"origin\":\"BOTTOM_CENTER\""))
        assertTrue(item.bytes < 5_000)
    }

    @Test
    fun undecodablePhotoIsDroppedNotFatal() = runTest {
        val repo = repo()
        val item = repo.get(repo.save(draft(jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))))!!
        assertNull(item.photoFile)
        assertTrue(item.modelFile.isFile)
    }

    @Test
    fun invalidDraftIsRejectedWithoutFiles() = runTest {
        val repo = repo()
        for (bad in listOf(0.0, -1.0, Double.NaN, 51.0)) {
            val d = ScanDraft(
                ObjectKind.BOX, Dimension(bad, 0.01, false), Dimension(0.5, 0.01, false), Dimension(0.5, 0.01, false),
                Quality.GOOD, false, DeviceTier.DEPTH, null, null, null,
            )
            try {
                repo.save(d)
                fail("accepted width $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertTrue(galleryDir.list().isNullOrEmpty())
    }

    @Test
    fun databaseFailureLeavesNoFiles() = runTest {
        val dao = object : FakeGalleryDao() {
            override suspend fun insert(entity: GalleryItemEntity): Long = throw IOException("disk full")
        }
        val repo = repo(dao)
        try {
            repo.save(draft())
            fail("save should throw")
        } catch (expected: IOException) {
        }
        assertTrue(galleryDir.list()!!.isEmpty())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun failureAfterTheMoveRollsBackRowAndFiles() = runTest {
        val dao = object : FakeGalleryDao() {
            override suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long) =
                throw IllegalStateException("constraint")
        }
        val repo = repo(dao)
        try {
            repo.save(draft())
            fail("save should throw")
        } catch (expected: IllegalStateException) {
        }
        assertTrue(galleryDir.list()!!.isEmpty())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun codecFailureLeavesNoFiles() = runTest {
        val broken = object : GalleryImageCodec {
            override fun decode(encoded: ByteArray, maxLongSide: Int): GalleryImage = object : GalleryImage {
                override val width = 10
                override val height = 10
                override fun scaled(maxLongSide: Int) = this
                override fun jpeg(quality: Int): ByteArray = throw IOException("encoder")
                override fun webp(quality: Int): ByteArray = byteArrayOf(1)
                override fun release() = Unit
            }
        }
        val repo = repo(codec = broken)
        try {
            repo.save(draft())
            fail("save should throw")
        } catch (expected: IOException) {
        }
        assertTrue(galleryDir.list().isNullOrEmpty())
    }

    @Test
    fun updateDimensionsMarksEstimatesAndRegeneratesModel() = runTest {
        val repo = repo()
        val id = repo.save(draft())
        val photoBefore = repo.get(id)!!.photoFile!!.readBytes()
        now = 2_000L
        assertTrue(repo.updateDimensions(id, widthM = 0.5))
        val item = repo.get(id)!!
        assertEquals(Dimension.typed(0.5), item.width)
        assertEquals(DimensionSource.TYPED, item.width.source)
        assertEquals(Dimension(0.610, 0.011, false), item.height)
        // The measured value is kept for "Reset to measured".
        assertEquals(Dimension.measured(0.457, 0.009), item.measuredWidth)
        assertEquals(2_000L, item.updatedAt)
        assertEquals(1_000L, item.createdAt)
        val glb = Glb(item.modelFile.readBytes())
        assertTrue(glb.json.contains("\"widthM\":0.5,"))
        assertTrue(glb.containsBytes(photoBefore))
        assertEquals(item.modelFile.length() + item.photoFile!!.length() + item.thumbnailFile!!.length(), item.bytes)
        // A new model file; the old one is gone and no temp file is left.
        assertEquals("model-2000.glb", item.modelFile.name)
        assertEquals(setOf("model-2000.glb", "front.jpg", "thumb.webp"), File(galleryDir, "$id").list()!!.toSet())
    }

    @Test
    fun editingNeverUpgradesTheBadge() = runTest {
        val repo = repo()
        val rough = ScanDraft(
            ObjectKind.BOX, Dimension(0.5, 0.004, false), Dimension(0.5, 0.004, false), Dimension(0.5, 0.004, false),
            Quality.ROUGH, false, DeviceTier.DEPTH, null, null, null,
        )
        val id = repo.save(rough)
        assertTrue(repo.updateDimensions(id, depthM = 0.4))
        assertEquals(Quality.ROUGH, repo.get(id)!!.quality)

        val good = ScanDraft(
            ObjectKind.BOX, Dimension(0.5, 0.004, false), Dimension(0.5, 0.004, false), Dimension(0.5, 0.004, false),
            Quality.GOOD, false, DeviceTier.DEPTH, null, null, null,
        )
        val id2 = repo.save(good)
        assertTrue(repo.updateDimensions(id2, widthM = 0.6, heightM = 0.6, depthM = 0.6))
        val all = repo.get(id2)!!
        assertTrue(all.allEstimated)
        assertEquals(Quality.GOOD, all.quality)
    }

    @Test
    fun invalidEditIsRefusedAndChangesNothing() = runTest {
        val repo = repo()
        val id = repo.save(draft())
        assertFalse(repo.updateDimensions(id, widthM = 0.0))
        assertFalse(repo.updateDimensions(id, heightM = Double.POSITIVE_INFINITY))
        assertFalse(repo.updateDimensions(999, widthM = 0.3))
        assertEquals(0.457, repo.get(id)!!.width.meters, 0.0)
    }

    @Test
    fun renameTrimsAndClears() = runTest {
        val repo = repo()
        val id = repo.save(draft())
        now = 5_000L
        assertTrue(repo.rename(id, "  Living room TV  "))
        assertEquals("Living room TV", repo.get(id)!!.label)
        assertEquals(5_000L, repo.get(id)!!.updatedAt)
        assertTrue(repo.rename(id, "   "))
        assertNull(repo.get(id)!!.label)
        assertTrue(repo.rename(id, "x".repeat(200)))
        assertEquals(GalleryRepository.MAX_LABEL, repo.get(id)!!.label!!.length)
        assertFalse(repo.rename(12345, "nope"))
    }

    @Test
    fun deleteRemovesRowAndFiles() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val a = repo.save(draft())
        val b = repo.save(draft(jpeg = null))
        assertTrue(repo.delete(a))
        assertNull(repo.get(a))
        assertFalse(File(galleryDir, "$a").exists())
        assertTrue(File(galleryDir, "$b").exists())
        assertEquals(repo.get(b)!!.bytes, repo.totalBytes())
    }

    @Test
    fun observeAllIsNewestFirst() = runTest {
        val repo = repo()
        now = 1L; val first = repo.save(draft(label = "a"))
        now = 2L; val second = repo.save(draft(jpeg = null, label = "b"))
        assertEquals(listOf(second, first), repo.observeAll().first()!!.map { it.id })
        assertEquals("b", repo.observe(second).first()!!.label)
    }

    @Test
    fun observeNeverThrows() = runTest {
        val dao = object : FakeGalleryDao() {
            override fun observeAll(): Flow<List<GalleryItemEntity>> = flow { throw IllegalStateException("corrupt db") }
            override fun observe(id: Long): Flow<GalleryItemEntity?> = flow { throw IllegalStateException("corrupt db") }
        }
        val repo = repo(dao)
        assertNull(repo.observeAll().first()) // an error, not an empty gallery
        assertNull(repo.observe(1).first())
    }

    @Test
    fun unknownNamesFallBackInsteadOfHidingTheRow() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        repo.save(draft(jpeg = null))
        val base = dao.rows.value.single()
        val odd = dao.insert(base.copy(kind = "SPACESHIP", quality = "SUPERB", deviceTier = "LIDAR9", widthSource = "GUESSED"))
        dao.insert(base.copy(widthMeters = Double.NaN))
        assertEquals(2, repo.observeAll().first()!!.size)
        val item = repo.get(odd)!!
        assertEquals(ObjectKind.OTHER, item.kind)
        assertEquals(Quality.ROUGH, item.quality)
        assertEquals(DeviceTier.NO_DEPTH, item.deviceTier)
        assertTrue(item.width.isEstimate) // unknown provenance is never shown as measured
        assertTrue(repo.delete(odd))
    }

    @Test
    fun cleanUpRemovesStagingAndOrphans() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(draft(jpeg = null))
        File(galleryDir, ".tmp-dead").mkdirs()
        File(galleryDir, ".tmp-dead/model.glb").writeBytes(byteArrayOf(1))
        File(galleryDir, "999").mkdirs()
        assertEquals(2, repo.cleanUp())
        assertEquals(listOf(id.toString()), galleryDir.list()!!.toList())
    }

    @Test
    fun idReusedAfterRollbackOverwritesOrphanDirectory() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        // An orphan directory where the next id will go (e.g. a process death after the move).
        File(galleryDir, "1").mkdirs()
        File(galleryDir, "1/stale.bin").writeBytes(ByteArray(10))
        val id = repo.save(draft(jpeg = null))
        assertEquals(1L, id)
        assertFalse(File(galleryDir, "1/stale.bin").exists())
        assertNotNull(repo.get(id))
    }

    // ---- M3 review fixes ----------------------------------------------------------------------------

    @Test
    fun measuredSideWithoutPlusMinusIsRejected() = runTest {
        val repo = repo()
        val d = ScanDraft(
            ObjectKind.BOX, Dimension(0.5, 0.0, false), Dimension(0.5, 0.01, false), Dimension(0.5, 0.01, false),
            Quality.GOOD, false, DeviceTier.DEPTH, null, null, null,
        )
        try {
            repo.save(d)
            fail("accepted a measured side with ± 0")
        } catch (expected: IllegalArgumentException) {
        }
        assertTrue(galleryDir.list().isNullOrEmpty())
        // Not measured: no ± needed, and any ± given is dropped.
        val id = repo.save(
            ScanDraft(
                ObjectKind.BOX, Dimension.preset(0.5), Dimension(0.5, 0.02, DimensionSource.TYPED), Dimension(0.5, 0.01, false),
                Quality.GOOD, false, DeviceTier.DEPTH, null, null, null,
            ),
        )
        assertEquals(0.0, repo.get(id)!!.height.bound95, 0.0)
    }

    @Test
    fun provenanceAndMeasuredOriginalsAreStored() = runTest {
        val repo = repo()
        val id = repo.save(
            ScanDraft(
                ObjectKind.FRAME, Dimension.measured(0.457, 0.009), Dimension.typed(0.61), Dimension.preset(0.025),
                Quality.FAIR, true, DeviceTier.NO_DEPTH, null, null, null, calibrationFactor = 1.012,
            ),
        )
        val item = repo.get(id)!!
        assertEquals(DimensionSource.MEASURED, item.width.source)
        assertEquals(DimensionSource.TYPED, item.height.source)
        assertEquals(DimensionSource.PRESET, item.depth.source)
        assertEquals(Dimension.measured(0.457, 0.009), item.measuredWidth)
        assertNull(item.measuredHeight)
        assertNull(item.measuredDepth)
        assertEquals(1.012, item.calibrationFactor!!, 0.0)
        assertTrue(item.hasTyped)
        // Compatibility constructor: isEstimate = true is a preset, not "typed".
        assertEquals(DimensionSource.PRESET, Dimension(0.1, 0.0, true).source)
    }

    /** M2: the row and the model it points to must agree even when the database write fails. */
    @Test
    fun failedDimensionWriteKeepsModelAndRowInAgreement() = runTest {
        val dao = object : FakeGalleryDao() {
            override suspend fun setDimensions(
                id: Long, widthM: Double, widthB: Double, widthS: String, heightM: Double, heightB: Double, heightS: String,
                depthM: Double, depthB: Double, depthS: String, quality: String, modelPath: String, totalBytes: Long, updatedAt: Long,
            ): Int = throw IOException("SQLiteFull")
        }
        val repo = repo(dao)
        val id = repo.save(draft())
        val before = File(galleryDir, "$id").list()!!.toSet()
        now = 2_000L
        assertFalse(repo.updateDimensions(id, widthM = 0.5))
        val item = repo.get(id)!!
        assertEquals(0.457, item.width.meters, 0.0)
        assertTrue(Glb(item.modelFile.readBytes()).json.contains("\"widthM\":0.457"))
        assertEquals(before, File(galleryDir, "$id").list()!!.toSet()) // the new model was removed
    }

    /** M2: a process death between writing the new model and the UPDATE leaves the old pair; cleanUp sweeps the new file. */
    @Test
    fun deathBeforeTheRowUpdateLeavesOldPairAndSweepsTheNewModel() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(draft())
        // What a death right after writing the new model leaves: an unreferenced model and a temp file.
        File(galleryDir, "$id/model-2000.glb").writeBytes(ByteArray(100))
        File(galleryDir, "$id/model-2001.glb.tmp").writeBytes(ByteArray(10))
        assertEquals(2, repo.cleanUp())
        val item = repo.get(id)!!
        assertEquals(setOf("model.glb", "front.jpg", "thumb.webp"), File(galleryDir, "$id").list()!!.toSet())
        assertTrue(Glb(item.modelFile.readBytes()).json.contains("\"widthM\":0.457"))
    }

    /** M2: a death after the UPDATE but before the old model is deleted: the row already points at the new model. */
    @Test
    fun deathAfterTheRowUpdateSweepsTheOldModel() = runTest {
        val repo = repo()
        val id = repo.save(draft())
        val old = File(galleryDir, "$id/model.glb").readBytes()
        now = 2_000L
        assertTrue(repo.updateDimensions(id, heightM = 0.7))
        File(galleryDir, "$id/model.glb").writeBytes(old) // the old model survived the death
        assertEquals(1, repo.cleanUp())
        val item = repo.get(id)!!
        assertEquals("model-2000.glb", item.modelFile.name)
        assertTrue(Glb(item.modelFile.readBytes()).json.contains("\"heightM\":0.7"))
        assertFalse(File(galleryDir, "$id/model.glb").exists())
    }

    @Test
    fun repeatedEditsInTheSameMillisecondGetDistinctModels() = runTest {
        val repo = repo()
        val id = repo.save(draft(jpeg = null))
        assertTrue(repo.updateDimensions(id, widthM = 0.3))
        val first = repo.get(id)!!.modelFile
        assertTrue(repo.updateDimensions(id, widthM = 0.4))
        val second = repo.get(id)!!
        assertFalse(first == second.modelFile)
        assertFalse(first.exists())
        assertTrue(Glb(second.modelFile.readBytes()).json.contains("\"widthM\":0.4"))
        assertEquals(listOf(second.modelFile.name), File(galleryDir, "$id").list()!!.toList())
    }

    /** MINOR 1: Back during Save while the transaction runs: the save completes with its files. */
    @Test
    fun cancellingDuringTheTransactionNeverLeavesARowWithoutFiles() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val dao = object : FakeGalleryDao() {
            override suspend fun insert(entity: GalleryItemEntity): Long {
                entered.complete(Unit)
                gate.await()
                return super.insert(entity)
            }
        }
        val repo = repo(dao)
        val job = launch(Dispatchers.IO) { repo.save(draft()) }
        entered.await()
        job.cancel()
        gate.complete(Unit)
        job.join()
        val row = dao.rows.value.single()
        assertTrue(row.modelPath.isNotEmpty())
        assertTrue(File(filesRoot, row.modelPath).isFile)
        assertTrue(File(filesRoot, row.photoPath!!).isFile)
        assertEquals(0, repo.cleanUp())
        assertEquals(listOf(row.id.toString()), galleryDir.list()!!.toList())
    }

    /** MINOR 1: cancelled while encoding: nothing is written. */
    @Test
    fun cancellingBeforeTheCommitLeavesNothing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : GalleryImageCodec {
            override fun decode(encoded: ByteArray, maxLongSide: Int): GalleryImage? {
                entered.complete(Unit)
                release.await()
                return JvmImageCodec.decode(encoded, maxLongSide)
            }
        }
        val dao = FakeGalleryDao()
        val repo = repo(dao, codec = slow)
        val job = launch(Dispatchers.IO) { repo.save(draft()) }
        entered.await()
        job.cancel()
        release.countDown()
        job.join()
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(galleryDir.list().isNullOrEmpty())
    }

    /** MINOR 2: rows whose files are missing are repaired, not left pointing at nothing. */
    @Test
    fun cleanUpRegeneratesAMissingModelAndThumbnail() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(draft())
        File(galleryDir, "$id/model.glb").delete()
        File(galleryDir, "$id/thumb.webp").delete()
        now = 3_000L
        assertEquals(1, repo.cleanUp())
        val item = repo.get(id)!!
        assertTrue(item.modelFile.isFile)
        val glb = Glb(item.modelFile.readBytes())
        assertTrue(glb.json.contains("\"widthM\":0.457"))
        assertTrue(glb.containsBytes(item.photoFile!!.readBytes()))
        assertTrue(item.thumbnailFile!!.isFile)
        assertEquals(item.modelFile.length() + item.photoFile.length() + item.thumbnailFile.length(), item.bytes)
        assertEquals(0, repo.cleanUp()) // idempotent
    }

    @Test
    fun cleanUpRebuildsARowWhoseDirectoryVanished() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(draft())
        File(galleryDir, "$id").deleteRecursively()
        assertEquals(1, repo.cleanUp())
        val item = repo.get(id)!!
        assertTrue(item.modelFile.isFile)
        assertNull(item.photoFile)
        assertNull(item.thumbnailFile)
        assertTrue(Glb(item.modelFile.readBytes()).json.contains("\"heightM\":0.61"))
    }

    /** MINOR 6: the budget holds against exact bytes, even with a long Devanagari label on a noisy photo. */
    @Test
    fun noisyPhotoWithLongDevanagariLabelStaysWithinBudget() = runTest {
        val src = JvmImageIo.encodeJpeg(1024, 1024, TestPhotos.noisy(), 0.92f)
        val repo = repo()
        val label = "बैठक का बड़ा फ़्रेम ".repeat(6).take(GalleryRepository.MAX_LABEL)
        assertEquals(GalleryRepository.MAX_LABEL, label.length)
        val item = repo.get(repo.save(draft(jpeg = src, label = label)))!!
        report("noise + 80-char Devanagari label", item)
        assertTrue("${item.bytes} B", item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
        assertEquals(item.modelFile.length() + item.photoFile!!.length() + item.thumbnailFile!!.length(), item.bytes)
    }

    @Test
    fun veryWidePhotoStaysWithinBudget() = runTest {
        val src = JvmImageIo.encodeJpeg(2048, 128, TestPhotos.typicalFrame(2048, 128), 0.95f)
        val repo = repo()
        val item = repo.get(repo.save(draft(jpeg = src)))!!
        assertTrue(item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
        val (w, h, _) = JvmImageIo.decode(item.photoFile!!.readBytes())!!
        assertEquals(1024, w)
        assertEquals(64, h)
    }

    /** MINOR 10: share copies are per item version, survive later shares, and go with the item. */
    @Test
    fun shareCopiesAreVersionedPrunedAndDeletedWithTheItem() = runTest {
        val repo = repo()
        val a = repo.save(draft())
        val b = repo.save(draft())
        val first = repo.shareCopy(a, "SpaceView-frame.jpg")!!
        assertEquals(File(cacheRoot, "share/$a-1000/SpaceView-frame.jpg"), first)
        assertTrue(first.readBytes().contentEquals(repo.get(a)!!.photoFile!!.readBytes()))
        now = 2_000L
        assertTrue(repo.rename(a, "x"))
        val second = repo.shareCopy(a, "SpaceView-x.jpg")!!
        assertTrue("the earlier copy must survive a later share", first.isFile)
        assertFalse(first == second)
        val other = repo.shareCopy(b, "SpaceView-frame.jpg")!!
        assertTrue(repo.delete(a))
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue(other.isFile)
        // Copies older than a day are pruned on the next share.
        now = other.parentFile!!.lastModified() + GalleryRepository.SHARE_TTL_MS + 60_000
        repo.shareCopy(b, "SpaceView-again.jpg")
        assertFalse(other.exists())
        assertNull(repo.shareCopy(b, "../escape.jpg"))
        assertNull(repo.shareCopy(repo.save(draft(jpeg = null)), "SpaceView.jpg"))
    }

    private fun report(name: String, item: GalleryItem) {
        println(
            "SCAN SIZE [$name]: model.glb=${item.modelFile.length()} B, front.jpg=${item.photoFile?.length()} B, " +
                "thumb=${item.thumbnailFile?.length()} B (JPEG stand-in for WebP), total=${item.bytes} B",
        )
    }

    private companion object {
        val typicalJpeg: ByteArray by lazy { JvmImageIo.encodeJpeg(1024, 768, TestPhotos.typicalFrame(), 0.92f) }
    }
}

/** Minimal GLB reader: the JSON chunk as text and the whole file for byte searches. */
private class Glb(private val bytes: ByteArray) {
    val json: String

    init {
        fun u32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        check(u32(0) == 0x46546C67) { "not a GLB" }
        check(u32(8) == bytes.size) { "length mismatch" }
        val jsonLen = u32(12)
        json = String(bytes, 20, jsonLen, Charsets.UTF_8)
    }

    fun containsBytes(needle: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
