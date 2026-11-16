package com.spaceview.data.gallery

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GLB_VERSION
import com.spaceview.engine.GlbReader
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.MeshExtras
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Part
import com.spaceview.engine.PartMaterial
import com.spaceview.engine.Quality
import com.spaceview.engine.Vec3
import com.spaceview.engine.recon.TriMesh
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

/** M4: `modelForPlacement` (K4, K8), the v3 saves `saveTemplate` / `saveMesh` (K10) and kind-aware edit/repair. */
class GalleryPlacementTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val filesRoot get() = File(tmp.root, "files")
    private val cacheRoot get() = File(tmp.root, "cache")
    private val galleryDir get() = File(filesRoot, "gallery")

    private fun repo(dao: FakeGalleryDao = FakeGalleryDao(), templates: TemplateModelSource = TemplateModelSource.NONE) =
        GalleryRepository(
            dao, filesRoot, JvmImageCodec, clock = { now }, io = Dispatchers.IO, transactions = FakeTransactions(dao),
            cacheDir = cacheRoot, templates = templates,
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

    // ---- templates (K10) ---------------------------------------------------------------------------------

    private fun sofaParts(w: Double = 2.0, h: Double = 0.85, d: Double = 0.9) = listOf(
        Part("base", Vec3(0.0, h * 0.25, 0.0), Vec3(w, h * 0.5, d), material = PartMaterial.BODY),
        Part("back", Vec3(0.0, h * 0.75, -d * 0.4), Vec3(w, h * 0.5, d * 0.2), material = PartMaterial.SOFT),
    )

    private fun sofa(parts: List<Part> = sofaParts()) = TemplateDraft(
        templateId = "sofa_v1",
        params = mapOf("W" to 2.0, "H" to 0.85, "D" to 0.9, "seatHeight" to 0.45),
        width = Dimension.measured(2.0, 0.02), height = Dimension.typed(0.85), depth = Dimension.measured(0.9, 0.012),
        label = "Sofa", parts = parts, captureMethod = CaptureMethod.MANUAL,
    )

    @Test fun saveTemplateWritesPartsAndRow() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.saveTemplate(sofa())
        val item = repo.get(id)!!
        assertEquals(ObjectKind.FURNITURE, item.kind)
        assertEquals(ModelKind.TEMPLATE, item.modelKind)
        assertEquals("sofa_v1", item.templateId)
        assertEquals(0.45, item.templateParams!!["seatHeight"]!!, 0.0)
        assertEquals(CaptureMethod.MANUAL, item.captureMethod)
        assertNull(item.photoFile)
        assertNull(item.thumbnailFile)
        assertEquals(setOf("model.glb"), File(galleryDir, "$id").list()!!.toSet())
        assertTrue(item.bytes < 20_000)
        val p = repo.modelForPlacement(id)!!
        assertEquals(item.modelFile, p.file)
        assertEquals(BoxOrigin.BOTTOM_CENTER, p.origin)
        assertEquals(ModelKind.TEMPLATE, p.modelKind)
        // A gallery size edit cannot change a template (its size is a parameter).
        assertFalse(repo.updateDimensions(id, widthM = 1.8))
        assertEquals(2.0, repo.get(id)!!.width.meters, 0.0)
    }

    @Test fun templateWhosePartsAreNotTheItemSizeIsRefused() = runTest {
        val repo = repo()
        try {
            repo.saveTemplate(sofa(parts = sofaParts(w = 1.9)))
            fail("accepted parts that are not W x H x D")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            repo.saveTemplate(TemplateDraft("sofa_v1", mapOf("bad key" to 1.0), Dimension.typed(2.0), Dimension.typed(0.85), Dimension.typed(0.9), null, sofaParts()))
            fail("accepted a bad parameter name")
        } catch (expected: IllegalArgumentException) {
        }
        assertTrue(galleryDir.list().isNullOrEmpty())
    }

    @Test fun staleTemplateRegeneratesFromItsSourceOrFallsBackToTheBox() = runTest {
        var asked: Map<String, Double>? = null
        val source = TemplateModelSource { id, params -> asked = params; if (id == "sofa_v1") sofaParts() else null }
        val dao = FakeGalleryDao()
        val withSource = repo(dao, source)
        val id = withSource.saveTemplate(sofa())
        val file = withSource.get(id)!!.modelFile
        file.delete()
        now = 9_000L
        val p = withSource.modelForPlacement(id)!!
        assertEquals("model-9000.glb", p.file.name)
        assertEquals(ModelKind.TEMPLATE, GlbReader.readInfo(p.file.readBytes())!!.modelKind)
        assertEquals(0.9, asked!!["D"]!!, 0.0)
        // Without a source: the measured box from the cache, the row untouched.
        p.file.delete()
        val noSource = repo(dao)
        val q = noSource.modelForPlacement(id)!!
        assertTrue(q.meshFallback)
        assertTrue(q.file.path.startsWith(cacheRoot.path))
        assertEquals(2.0, q.widthM, 1e-12)
        assertEquals("gallery/$id/model-9000.glb", dao.rows.value.single().modelPath)
    }

    // ---- meshes (K8, K10, ADR-014) -----------------------------------------------------------------------

    /** A closed 0.6 × 0.4 × 0.4 m box mesh (8 vertices, 12 triangles) with colours. */
    private fun cartonMesh(w: Float = 0.6f, h: Float = 0.4f, d: Float = 0.4f): TriMesh {
        val x = w / 2; val z = d / 2
        val p = floatArrayOf(-x, 0f, -z, x, 0f, -z, x, 0f, z, -x, 0f, z, -x, h, -z, x, h, -z, x, h, z, -x, h, z)
        val i = intArrayOf(0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1, 1, 5, 6, 1, 6, 2, 2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0)
        return TriMesh(p, ByteArray(24) { (it * 10).toByte() }, i)
    }

    private fun meshDraft(
        glb: ByteArray = GlbWriter.writeMesh(cartonMesh()),
        glbBox: GlbExtents = GlbExtents(0.6, 0.4, 0.4),
        extents: MeshExtents = MeshExtents(0.6, 0.41, 0.395, 0.03, 0.03, 0.03),
    ) = MeshDraft(
        kind = ObjectKind.BOX,
        width = Dimension.measured(0.61, 0.01), height = Dimension.measured(0.41, 0.01), depth = Dimension.measured(0.405, 0.01),
        quality = Quality.GOOD, calibrated = false, deviceTier = DeviceTier.DEPTH,
        glb = glb, glbExtents = glbBox, meshExtents = extents, meshInfo = "{\"voxelM\":0.01}", thumbnailSource = jpeg, label = "Carton", arcoreVersion = "1.57.0",
    )

    @Test fun saveMeshKeepsTheMeasuredBoxAsTheSize() = runTest {
        val repo = repo()
        val id = repo.saveMesh(meshDraft())
        val item = repo.get(id)!!
        assertEquals(ModelKind.MESH_DEVICE, item.modelKind)
        assertEquals(0.61, item.width.meters, 0.0) // the measured box
        assertEquals(0.6, item.meshExtents!!.widthM, 1e-6) // the §7.1.5 extents, separately
        assertEquals(0.41, item.meshExtents.heightM, 1e-6) // ≠ the GLB box: both are kept (M4 review MAJOR 2)
        assertEquals(0.4, item.glbExtents!!.heightM, 1e-6)
        assertEquals(0.03, item.meshExtents.widthBound95, 0.0)
        assertEquals(CaptureMethod.DEPTH_SCAN, item.captureMethod)
        assertNull(item.photoFile)
        assertTrue(item.thumbnailFile!!.isFile)
        assertEquals(setOf("model.glb", "thumb.webp"), File(galleryDir, "$id").list()!!.toSet())

        val p = repo.modelForPlacement(id)!!
        assertEquals(item.modelFile, p.file)
        assertFalse(p.meshFallback)
        assertEquals(0.6, p.widthM, 1e-6) // rendered geometry: the mesh, never rescaled
        assertEquals(0.61, p.width.meters, 0.0) // verdict: the measured box
        assertEquals(BoxOrigin.BOTTOM_CENTER, p.origin)
    }

    @Test fun unusableMeshFallsBackToTheMeasuredBoxWithoutTouchingIt() = runTest {
        val dao = FakeGalleryDao()
        val repo = repo(dao)
        val id = repo.saveMesh(meshDraft())
        val item = repo.get(id)!!
        val garbage = ByteArray(300) { 3 }
        item.modelFile.writeBytes(garbage)
        val p = repo.modelForPlacement(id)!!
        assertTrue(p.meshFallback)
        assertEquals(ModelKind.MESH_DEVICE, p.modelKind)
        assertEquals(0.61, p.widthM, 1e-12)
        assertEquals(0.41, p.heightM, 1e-12)
        assertTrue(p.file.path.startsWith(cacheRoot.path))
        assertArrayEquals("the mesh file is never regenerated", garbage, item.modelFile.readBytes())
        assertEquals(item.modelFile.name, repo.get(id)!!.modelFile.name)
        // Mismatched extras (another mesh's size) also fall back.
        item.modelFile.writeBytes(GlbWriter.writeMesh(cartonMesh(w = 0.5f)))
        assertTrue(repo.modelForPlacement(id)!!.meshFallback)
        // A missing mesh: cleanUp does not regenerate it, and does not count it as repaired.
        item.modelFile.delete()
        assertEquals(0, repo.cleanUp())
        assertFalse(item.modelFile.exists())
        assertTrue(item.thumbnailFile!!.isFile)
        assertTrue(repo.modelForPlacement(id)!!.meshFallback)
    }

    @Test fun meshEditChangesOnlyTheRow() = runTest {
        val repo = repo()
        val id = repo.saveMesh(meshDraft())
        val before = repo.get(id)!!.modelFile.readBytes()
        now = 4_000L
        assertTrue(repo.updateDimensions(id, widthM = 0.65))
        val item = repo.get(id)!!
        assertEquals(0.65, item.width.meters, 0.0)
        assertEquals(DimensionSource.TYPED, item.width.source)
        assertArrayEquals(before, item.modelFile.readBytes())
        assertEquals("model.glb", item.modelFile.name)
    }

    @Test fun saveMeshValidatesTheGlb() = runTest {
        val repo = repo()
        fun rejects(d: MeshDraft): Boolean = try {
            kotlinx.coroutines.runBlocking { repo.saveMesh(d) }
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("a box is not a mesh", rejects(meshDraft(glb = GlbWriter.writeBox(0.6, 0.4, 0.4))))
        assertTrue("the GLB box must match the GLB", rejects(meshDraft(glbBox = GlbExtents(0.7, 0.4, 0.4))))
        assertTrue("measured extents must be valid", rejects(meshDraft(extents = MeshExtents(0.0, 0.4, 0.4, 0.03, 0.03, 0.03))))
        assertTrue("origin must be bottom centre", rejects(meshDraft(glb = GlbWriter.writeMesh(cartonMesh(), BoxOrigin.CENTER))))
        assertTrue("kind must match", rejects(meshDraft(glb = GlbWriter.writeMesh(cartonMesh(), extras = MeshExtras(ModelKind.MESH_CLOUD)))))
        assertTrue(galleryDir.list().isNullOrEmpty())
        assertNotNull(repo.saveMesh(meshDraft().let { d ->
            MeshDraft(d.kind, d.width, d.height, d.depth, d.quality, d.calibrated, d.deviceTier, d.glb, d.glbExtents, d.meshExtents, null, null, null, null)
        }))
    }

    // ---- codec -------------------------------------------------------------------------------------------

    @Test fun templateParamsRoundTrip() {
        val p = mapOf("W" to 2.0, "seat_h" to 0.45, "n" to 3.0)
        assertEquals(p, TemplateParamsCodec.decode(TemplateParamsCodec.encode(p)))
        assertEquals(emptyMap<String, Double>(), TemplateParamsCodec.decode("{}"))
        assertNull(TemplateParamsCodec.decode(null))
        assertNull(TemplateParamsCodec.decode("[1]"))
        assertNull(TemplateParamsCodec.decode("{\"a\":\"x\"}"))
        assertNull(TemplateParamsCodec.decode("{\"a\":1,}"))
        assertEquals(1e-3, TemplateParamsCodec.decode("{\"a\":1.0E-3}")!!["a"]!!, 0.0)
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
