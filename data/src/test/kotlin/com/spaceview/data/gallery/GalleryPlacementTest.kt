package com.spaceview.data.gallery

import com.spaceview.engine.ArgbImage
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GLB_VERSION
import com.spaceview.engine.GlbReader
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** M4: `modelForPlacement` (K4, K8) and kind-aware edit/repair; PLAN v3: cutouts and legacy template / mesh rows. */
class GalleryPlacementTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val filesRoot get() = File(tmp.root, "files")
    private val cacheRoot get() = File(tmp.root, "cache")
    private val galleryDir get() = File(filesRoot, "gallery")

    private fun repo(dao: FakeGalleryDao = FakeGalleryDao()) =
        GalleryRepository(
            dao, filesRoot, JvmImageCodec, clock = { now }, io = Dispatchers.IO, transactions = FakeTransactions(dao),
            cacheDir = cacheRoot,
        )

    private val jpeg: ByteArray by lazy { JvmImageIo.encodeJpeg(640, 480, TestPhotos.typicalFrame(640, 480), 0.9f) }

    private fun frame(photo: ByteArray? = jpeg) = ScanDraft(
        ObjectKind.FRAME, Dimension.measured(0.457, 0.009), Dimension.measured(0.61, 0.011), Dimension.preset(0.025),
        Quality.FAIR, false, DeviceTier.NO_DEPTH, photo, "Frame", "1.57.0",
    )

    // ---- proxy boxes -----------------------------------------------------------------------------------

    @Test fun currentModelIsUsedAsIs() = runTest {
        val repo = repo()
        val id = repo.save(frame())
        val item = repo.get(id)!!
        val p = repo.modelForPlacement(id)!!
        assertEquals(item.modelFile, p.file)
        assertEquals(BoxOrigin.BACK_CENTER, p.origin)
        assertEquals(0.457, p.widthM, 1e-12)
        assertEquals(0.61, p.heightM, 1e-12)
        assertEquals(0.025, p.depthM, 1e-12)
        assertEquals(ModelKind.PROXY_BOX, p.modelKind)
        assertFalse(p.meshFallback)
        assertEquals(DimensionSource.PRESET, p.depth.source)
        assertEquals(GLB_VERSION, GlbReader.readInfo(p.file.readBytes())!!.glbVersion)
        // Nothing was rewritten.
        assertEquals(item.modelFile.name, repo.get(id)!!.modelFile.name)
    }

    @Test fun m3ModelIsRegeneratedOnceAndKeepsItsSizeAndPhoto() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(frame())
        val old = repo.get(id)!!.modelFile
        old.writeBytes(legacyGlb(0.457, 0.61, 0.025, "BACK_CENTER"))
        now = 5_000L
        val p = repo.modelForPlacement(id)!!
        assertNotEquals(old, p.file)
        assertEquals("model-5000.glb", p.file.name)
        assertFalse("old model deleted after the UPDATE", old.exists())
        val row = dao.rows.value.single()
        assertEquals("gallery/$id/model-5000.glb", row.modelPath)
        assertEquals("gallery/$id/front.jpg", row.photoPath)
        val info = GlbReader.readInfo(p.file.readBytes())!!
        assertEquals(GLB_VERSION, info.glbVersion)
        assertEquals(0.457, info.widthM, 0.0)
        assertEquals(row.totalBytes, File(galleryDir, "$id").listFiles()!!.sumOf { it.length() })
        // The photo is still embedded (unlit front, ADR-003).
        assertTrue(Glb(p.file.readBytes()).json.contains("KHR_materials_unlit"))
        // Second call: no further regeneration.
        now = 6_000L
        assertEquals(p.file, repo.modelForPlacement(id)!!.file)
    }

    @Test fun mismatchedExtrasOrMissingFileRegenerates() = runTest {
        val repo = repo()
        val id = repo.save(frame(photo = null))
        val f = repo.get(id)!!.modelFile
        f.writeBytes(GlbWriter.writeBox(0.5, 0.61, 0.025, origin = BoxOrigin.BACK_CENTER))
        now = 2_000L
        val p = repo.modelForPlacement(id)!!
        assertEquals(0.457, p.widthM, 0.0)
        p.file.delete()
        now = 3_000L
        val q = repo.modelForPlacement(id)!!
        assertTrue(q.file.isFile)
        assertEquals(0.457, q.widthM, 0.0)
        // Within 0.1 mm counts as a match.
        q.file.writeBytes(GlbWriter.writeBox(0.45705, 0.61, 0.025, origin = BoxOrigin.BACK_CENTER))
        assertEquals(q.file, repo.modelForPlacement(id)!!.file)
    }

    @Test fun failedRowUpdateLeavesTheOldPair() = runTest {
        val dao = object : FakeGalleryDao() {
            var fail = false
            override suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long) {
                if (fail) throw IOException("SQLiteFull")
                super.setFiles(id, modelPath, photoPath, thumbnailPath, totalBytes)
            }
        }
        val repo = repo(dao)
        val id = repo.save(frame())
        val old = repo.get(id)!!.modelFile
        val legacy = legacyGlb(0.457, 0.61, 0.025, "BACK_CENTER")
        old.writeBytes(legacy)
        dao.fail = true
        now = 7_000L
        assertNull(repo.modelForPlacement(id))
        assertEquals(old, repo.get(id)!!.modelFile)
        assertArrayEquals(legacy, old.readBytes())
        assertFalse("the new model was removed", File(galleryDir, "$id/model-7000.glb").exists())
    }

    @Test fun missingOrUnreadableItem() = runTest {
        val repo = repo()
        assertNull(repo.modelForPlacement(42))
        assertEquals(PlacementResult.Missing, repo.placementModel(42))
    }

    /** M4 review MINOR 5: a storage failure is Failed, never Missing ("This item was deleted"). */
    @Test fun storageFailureIsFailedNotMissing() = runTest {
        val dao = object : FakeGalleryDao() {
            var fail = false
            override suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long) {
                if (fail) throw IOException("SQLiteFull")
                super.setFiles(id, modelPath, photoPath, thumbnailPath, totalBytes)
            }
        }
        val repo = repo(dao)
        val id = repo.save(frame())
        repo.get(id)!!.modelFile.writeBytes(legacyGlb(0.457, 0.61, 0.025, "BACK_CENTER"))
        dao.fail = true
        assertEquals(PlacementResult.Failed, repo.placementModel(id))
        // An unreadable row (a non-finite size) is Failed too.
        dao.rows.value = dao.rows.value.map { it.copy(widthMeters = Double.NaN) }
        assertEquals(PlacementResult.Failed, repo.placementModel(id))
        dao.fail = false
        dao.rows.value = emptyList()
        assertEquals(PlacementResult.Missing, repo.placementModel(id))
    }

    // ---- legacy template / mesh rows (PLAN v3 W1: saved by earlier test builds, no writer in v1) ----------------

    /** Inserts a row as an earlier build saved it, with [glb] as its stored model (and an optional thumbnail). */
    private suspend fun legacyRow(
        dao: FakeGalleryDao,
        kind: ObjectKind,
        modelKind: ModelKind,
        glb: ByteArray?,
        w: Double, h: Double, d: Double,
        glbBox: Triple<Double, Double, Double>? = null,
        thumbnail: Boolean = false,
    ): Long {
        val id = dao.insert(
            GalleryItemEntity(
                kind = kind.name,
                widthMeters = w, widthBound95 = 0.01, widthSource = "MEASURED",
                heightMeters = h, heightBound95 = 0.01, heightSource = "MEASURED",
                depthMeters = d, depthBound95 = 0.01, depthSource = "MEASURED",
                quality = "GOOD", calibrated = false, deviceTier = "DEPTH", createdAt = now, updatedAt = now, modelPath = "",
                modelKind = modelKind.name,
                templateId = if (modelKind == ModelKind.TEMPLATE) "sofa_v1" else null,
                templateParams = if (modelKind == ModelKind.TEMPLATE) "{\"W\":$w}" else null,
                meshGlbW = glbBox?.first, meshGlbH = glbBox?.second, meshGlbD = glbBox?.third,
            ),
        )
        val dir = File(galleryDir, "$id").apply { mkdirs() }
        glb?.let { File(dir, "model.glb").writeBytes(it) }
        if (thumbnail) File(dir, "thumb.webp").writeBytes(jpeg)
        dao.setFiles(id, "gallery/$id/model.glb", null, if (thumbnail) "gallery/$id/thumb.webp" else null, 1L)
        return id
    }

    /** A legacy 3D-scan GLB: extras with the mesh writer's kind, origin and declared bounding-box centre. */
    private fun legacyMeshGlb(w: Double, h: Double, d: Double, kind: String = "MESH_DEVICE"): ByteArray = rawGlb(
        "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"extras\":" +
            "{\"widthM\":$w,\"heightM\":$h,\"depthM\":$d,\"origin\":\"BOTTOM_CENTER\",\"glbVersion\":2,\"modelKind\":\"$kind\"," +
            "\"aabbCx\":0.01,\"aabbCy\":${h / 2 + 0.01},\"aabbCz\":0.0}}]}",
    )

    @Test fun legacyTemplateIsPlacedFromItsStoredGlb() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val glb = GlbWriter.writeBox(2.0, 0.85, 0.9, modelKind = ModelKind.TEMPLATE)
        val id = legacyRow(dao, ObjectKind.FURNITURE, ModelKind.TEMPLATE, glb, 2.0, 0.85, 0.9)
        val item = repo.get(id)!!
        assertEquals(ObjectKind.FURNITURE, item.kind)
        assertEquals("sofa_v1", item.templateId)
        val p = repo.modelForPlacement(id)!!
        assertEquals(item.modelFile, p.file)
        assertFalse(p.meshFallback)
        assertEquals(BoxOrigin.BOTTOM_CENTER, p.origin)
        assertEquals(ModelKind.TEMPLATE, p.modelKind)
        // v1 can't rebuild a template: a size edit is refused and the model is untouched.
        assertFalse(repo.updateDimensions(id, widthM = 1.8))
        assertArrayEquals(glb, repo.get(id)!!.modelFile.readBytes())
    }

    @Test fun legacyTemplateThatCannotBeUsedFallsBackToItsBoxWithoutCrashing() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        // Stored model of another size (a template whose parts changed later).
        val id = legacyRow(dao, ObjectKind.FURNITURE, ModelKind.TEMPLATE, GlbWriter.writeBox(1.9, 0.85, 0.9, modelKind = ModelKind.TEMPLATE), 2.0, 0.85, 0.9)
        val p = repo.modelForPlacement(id)!!
        assertTrue(p.meshFallback)
        assertTrue(p.file.path.startsWith(cacheRoot.path))
        assertEquals(2.0, p.widthM, 1e-12)
        assertEquals("gallery/$id/model.glb", dao.rows.value.single().modelPath)
        // A missing model: cleanUp has nothing to rebuild (and does not count it), placement still works.
        File(galleryDir, "$id/model.glb").delete()
        assertEquals(0, repo.cleanUp())
        assertTrue(repo.modelForPlacement(id)!!.meshFallback)
        // Garbage instead of a GLB.
        File(galleryDir, "$id/model.glb").writeBytes(ByteArray(100) { 9 })
        assertTrue(repo.modelForPlacement(id)!!.meshFallback)
    }

    @Test fun legacyMeshIsPlacedFromItsStoredGlbOrFallsBackToTheMeasuredBox() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val glb = legacyMeshGlb(0.6, 0.4, 0.4)
        val id = legacyRow(dao, ObjectKind.BOX, ModelKind.MESH_DEVICE, glb, 0.61, 0.41, 0.405, glbBox = Triple(0.6, 0.4, 0.4), thumbnail = true)
        val item = repo.get(id)!!
        assertEquals(0.6, item.glbExtents!!.widthM, 0.0)
        val p = repo.modelForPlacement(id)!!
        assertEquals(item.modelFile, p.file)
        assertFalse(p.meshFallback)
        assertEquals(0.6, p.widthM, 1e-12) // rendered geometry: the mesh, never rescaled
        assertEquals(0.61, p.width.meters, 0.0) // verdict: the measured box
        assertTrue(p.centre!!.approxEquals(com.spaceview.engine.Vec3(0.01, 0.21, 0.0)))
        // An edit changes only the row.
        now = 4_000L
        assertTrue(repo.updateDimensions(id, widthM = 0.65))
        assertArrayEquals(glb, repo.get(id)!!.modelFile.readBytes())
        // Extras that don't match the stored GLB box: the measured box instead, the file untouched.
        File(galleryDir, "$id/model.glb").writeBytes(legacyMeshGlb(0.5, 0.4, 0.4))
        val q = repo.modelForPlacement(id)!!
        assertTrue(q.meshFallback)
        assertEquals(ModelKind.MESH_DEVICE, q.modelKind)
        // A missing mesh is never rebuilt; its thumbnail stays.
        File(galleryDir, "$id/model.glb").delete()
        assertEquals(0, repo.cleanUp())
        assertTrue(repo.get(id)!!.thumbnailFile!!.isFile)
        assertTrue(repo.modelForPlacement(id)!!.meshFallback)
    }

    // ---- cutouts (PLAN v3 W2/W3) ------------------------------------------------------------------------

    private fun chair(image: ArgbImage?, label: String? = "Chair") = ScanDraft(
        ObjectKind.OTHER, Dimension.measured(0.48, 0.01), Dimension.measured(0.86, 0.012), Dimension.measured(0.52, 0.012),
        Quality.GOOD, false, DeviceTier.DEPTH, null, label, "1.57.0", cutout = CutoutDraft(image),
    )

    /** A photo-like subject (noisy) on a transparent background: a "chair" filling ~55% of its bounding box. */
    private fun transparentSubject(w: Int = 700, h: Int = 1000): ArgbImage {
        val rnd = java.util.Random(4)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inSeat = y in (h * 45 / 100) until (h * 60 / 100)
            val inBack = y < h * 45 / 100 && x in (w / 10) until (w * 9 / 10)
            val inLegs = y >= h * 60 / 100 && (x < w / 8 || x >= w * 7 / 8)
            val r = (120 + 40 * kotlin.math.sin(x * 0.05) + rnd.nextGaussian() * 12).toInt().coerceIn(0, 255)
            val g = (80 + 30 * kotlin.math.cos(y * 0.03) + rnd.nextGaussian() * 12).toInt().coerceIn(0, 255)
            val b = (60 + rnd.nextGaussian() * 12).toInt().coerceIn(0, 255)
            val a = if (inSeat || inBack || inLegs) 255 else 0
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return ArgbImage(w, h, px)
    }

    @Test fun opaqueCutoutIsAFlatTrueSizeModelWithItsPictureAndThumbnail() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val photo = ArgbImage(640, 480, TestPhotos.typicalFrame(640, 480))
        val draft = ScanDraft(
            ObjectKind.FRAME, Dimension.measured(0.457, 0.009), Dimension.measured(0.61, 0.011), Dimension.preset(0.025),
            Quality.FAIR, false, DeviceTier.NO_DEPTH, null, "Frame", "1.57.0", cutout = CutoutDraft(photo),
        )
        val id = repo.save(draft)
        val item = repo.get(id)!!
        assertEquals(ModelKind.CUTOUT, item.modelKind)
        assertEquals(setOf("model.glb", "cutout.webp", "thumb.webp"), File(galleryDir, "$id").list()!!.toSet())
        assertEquals("gallery/$id/cutout.webp", dao.rows.value.single().photoPath)
        assertEquals(0.025, item.depth.meters, 0.0) // the depth stays a number on the row
        assertTrue(item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
        val info = GlbReader.readInfo(item.modelFile.readBytes())!!
        assertEquals(ModelKind.CUTOUT, info.modelKind)
        assertEquals(BoxOrigin.CENTER, info.origin)
        assertEquals(0.0, info.depthM, 0.0)
        assertTrue("opaque: a JPEG texture, no blending", String(item.modelFile.readBytes(), Charsets.ISO_8859_1).let { "image/jpeg" in it && "BLEND" !in it })

        val p = repo.modelForPlacement(id)!!
        assertEquals(item.modelFile, p.file)
        assertFalse(p.meshFallback)
        assertEquals(BoxOrigin.CENTER, p.origin)
        assertEquals(0.457, p.widthM, 1e-12); assertEquals(0.61, p.heightM, 1e-12); assertEquals(0.0, p.depthM, 0.0)
        assertEquals("the fit check uses the row's depth", 0.025, p.depth.meters, 0.0)
        assertEquals(ModelKind.CUTOUT, p.modelKind)
    }

    @Test fun transparentCutoutBlendsAPngTextureWithinTheBudget() = runTest {
        val repo = repo()
        val id = repo.save(chair(transparentSubject()))
        val item = repo.get(id)!!
        assertEquals(ModelKind.CUTOUT, item.modelKind)
        assertTrue("${item.bytes} B", item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
        val glb = item.modelFile.readBytes()
        val text = String(glb, Charsets.ISO_8859_1)
        assertTrue("alpha cutout: BLEND + PNG", "\"alphaMode\":\"BLEND\"" in text && "image/png" in text)
        assertEquals(0.48, GlbReader.readInfo(glb)!!.widthM, 0.0)
        println("CUTOUT SIZE: model.glb=${item.modelFile.length()} B, cutout.webp=${item.photoFile?.length()} B, thumb=${item.thumbnailFile?.length()} B")
    }

    @Test fun cutoutWithoutAPhotoIsAPlainPanel() = runTest {
        val repo = repo()
        val id = repo.save(chair(null))
        val item = repo.get(id)!!
        assertEquals(ModelKind.CUTOUT, item.modelKind)
        assertNull(item.photoFile)
        assertNull(item.thumbnailFile)
        assertEquals(setOf("model.glb"), File(galleryDir, "$id").list()!!.toSet())
        assertEquals(BoxOrigin.CENTER, repo.modelForPlacement(id)!!.origin)
    }

    @Test fun cutoutEditAndRepairRegenerateTheFlatModel() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.save(chair(ArgbImage(400, 700, TestPhotos.typicalFrame(400, 700))))
        now = 5_000L
        assertTrue(repo.updateDimensions(id, widthM = 0.5, depthM = 0.6))
        val item = repo.get(id)!!
        assertEquals("model-5000.glb", item.modelFile.name)
        val info = GlbReader.readInfo(item.modelFile.readBytes())!!
        assertEquals(ModelKind.CUTOUT, info.modelKind)
        assertEquals(0.5, info.widthM, 0.0)
        assertEquals(0.86, info.heightM, 0.0)
        assertEquals("depth is a number, never rendered", 0.0, info.depthM, 0.0)
        assertEquals(0.6, item.depth.meters, 0.0)
        assertTrue(item.bytes <= GalleryRepository.MAX_SCAN_BYTES)
        // A depth-only edit leaves the quad's size: the model still matches.
        assertEquals(item.modelFile, repo.modelForPlacement(id)!!.file)
        // A missing model is rebuilt from the stored picture.
        item.modelFile.delete()
        now = 6_000L
        assertEquals(1, repo.cleanUp())
        val repaired = repo.get(id)!!
        assertTrue(repaired.modelFile.isFile)
        assertEquals(ModelKind.CUTOUT, GlbReader.readInfo(repaired.modelFile.readBytes())!!.modelKind)
        // A stale model (garbage) is regenerated for placement, not replaced by a box.
        repaired.modelFile.writeBytes(ByteArray(64) { 1 })
        now = 7_000L
        val p = repo.modelForPlacement(id)!!
        assertFalse(p.meshFallback)
        assertEquals("model-7000.glb", p.file.name)
        assertEquals(BoxOrigin.CENTER, p.origin)
    }

    /** A GLB with only a JSON chunk (padded with spaces) and an empty BIN chunk. */
    private fun rawGlb(json: String): ByteArray {
        val j = json.toByteArray(Charsets.UTF_8)
        val jl = (j.size + 3) and 3.inv()
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        u32(0x46546C67); u32(2); u32(12 + 8 + jl + 8); u32(jl); u32(0x4E4F534A)
        out.write(j); repeat(jl - j.size) { out.write(0x20) }
        u32(0); u32(0x004E4942)
        return out.toByteArray()
    }

    /** An M3-era GLB: extras without glbVersion / modelKind (only the JSON chunk matters to the reader). */
    private fun legacyGlb(w: Double, h: Double, d: Double, origin: String): ByteArray {
        val json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"extras\":" +
            "{\"widthM\":$w,\"heightM\":$h,\"depthM\":$d,\"origin\":\"$origin\"}}]}"
        val j = json.toByteArray(Charsets.UTF_8)
        val jl = (j.size + 3) and 3.inv()
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        u32(0x46546C67); u32(2); u32(12 + 8 + jl + 8); u32(jl); u32(0x4E4F534A)
        out.write(j); repeat(jl - j.size) { out.write(0x20) }
        u32(0); u32(0x004E4942)
        return out.toByteArray()
    }
}
