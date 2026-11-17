package com.spaceview.app.furniture

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.GalleryDao
import com.spaceview.data.gallery.GalleryFileRefs
import com.spaceview.data.gallery.GalleryImage
import com.spaceview.data.gallery.GalleryImageCodec
import com.spaceview.data.gallery.GalleryItemEntity
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.PlacementResult
import com.spaceview.data.gallery.TemplateModelSource
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GLB_VERSION
import com.spaceview.engine.GlbReader
import com.spaceview.engine.ModelKind
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Gate T3 / M4 MINOR 11: with the registry wired, a template item whose model is stale (an older `glbVersion`) is
 * rebuilt from its parameters (the same parts and colours), not replaced by a box.
 */
class FurnitureRegenerationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L

    private fun repo(dao: MemoryDao, templates: TemplateModelSource) = GalleryRepository(
        dao, File(tmp.root, "files"), NoImages, clock = { now }, io = Dispatchers.IO, cacheDir = File(tmp.root, "cache"), templates = templates,
    )

    @Test
    fun `a stale template model regenerates its parts and palette, not a box`() = runTest {
        val dao = MemoryDao()
        val gallery = repo(dao, FurnitureTemplateRegistry)
        val inputs = FurnitureInputs(
            "lsofa_v1",
            lengths = mapOf(W to Dimension.measured(2.8, 0.02), D to Dimension.measured(1.7, 0.015), H to Dimension.typed(0.86)),
            paletteIndex = 2,
        )
        val t = FurnitureFlow.template(inputs.templateId)!!
        val draft = FurnitureFlow.draft(t, inputs, "Corner sofa", DeviceTier.NO_DEPTH, "1.57.0")
        val id = gallery.saveTemplate(draft)
        val original = gallery.get(id)!!.modelFile
        val originalBytes = original.readBytes()

        // A glbVersion bump: the stored model is now one version behind.
        val stale = String(originalBytes, Charsets.ISO_8859_1).replace("\"glbVersion\":$GLB_VERSION", "\"glbVersion\":${GLB_VERSION - 1}")
        assertNotEquals(String(originalBytes, Charsets.ISO_8859_1), stale)
        original.writeBytes(stale.toByteArray(Charsets.ISO_8859_1))
        assertEquals(GLB_VERSION - 1, GlbReader.readInfo(original.readBytes())!!.glbVersion)

        now = 5_000L
        val ready = gallery.placementModel(id) as PlacementResult.Ready
        val model = ready.model
        assertFalse("rebuilt, not the measured-box fallback", model.meshFallback)
        assertEquals(ModelKind.TEMPLATE, model.modelKind)
        assertEquals("model-5000.glb", model.file.name)
        assertFalse("never the cache box", model.file.path.startsWith(File(tmp.root, "cache").path))
        val info = GlbReader.readInfo(model.file.readBytes())!!
        assertEquals(GLB_VERSION, info.glbVersion)
        assertEquals(ModelKind.TEMPLATE, info.modelKind)
        assertEquals(2.8, info.widthM, 1e-4)
        assertEquals(1.7, info.depthM, 1e-4)
        assertEquals(0.86, info.heightM, 1e-4)
        // The same parts and the same colours: byte-for-byte the model that was first saved.
        assertArrayEquals(originalBytes, model.file.readBytes())
        assertTrue("the stale file is gone", !original.exists())
        assertEquals("gallery/$id/model-5000.glb", dao.rows.value.single().modelPath)
    }

    @Test
    fun `without the registry the same stale model would fall back to a box`() = runTest {
        val dao = MemoryDao()
        val withRegistry = repo(dao, FurnitureTemplateRegistry)
        val inputs = FurnitureInputs("sofa_v1", lengths = mapOf(W to Dimension.typed(2.0), D to Dimension.typed(0.9), H to Dimension.typed(0.85)))
        val id = withRegistry.saveTemplate(FurnitureFlow.draft(FurnitureFlow.template("sofa_v1")!!, inputs, "Sofa", DeviceTier.NO_DEPTH, null))
        val file = withRegistry.get(id)!!.modelFile
        file.writeBytes(String(file.readBytes(), Charsets.ISO_8859_1).replace("\"glbVersion\":$GLB_VERSION", "\"glbVersion\":1").toByteArray(Charsets.ISO_8859_1))
        val none = (repo(dao, TemplateModelSource.NONE).placementModel(id) as PlacementResult.Ready).model
        assertTrue(none.meshFallback)
        assertEquals(ModelKind.PROXY_BOX, GlbReader.readInfo(none.file.readBytes())!!.modelKind)
        // The registry rebuilds it.
        val rebuilt = (withRegistry.placementModel(id) as PlacementResult.Ready).model
        assertFalse(rebuilt.meshFallback)
        assertEquals(ModelKind.TEMPLATE, GlbReader.readInfo(rebuilt.file.readBytes())!!.modelKind)
    }

    /** Templates have no photo: the codec is never asked to decode. */
    private object NoImages : GalleryImageCodec {
        override fun decode(encoded: ByteArray, maxLongSide: Int): GalleryImage? = null
    }

    /** In-memory DAO with the SQL semantics the repository relies on. */
    private class MemoryDao : GalleryDao {
        val rows = MutableStateFlow<List<GalleryItemEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(entity: GalleryItemEntity): Long {
            val id = nextId++
            rows.value = rows.value + entity.copy(id = id)
            return id
        }

        override fun observeAll(): Flow<List<GalleryItemEntity>> = rows
        override suspend fun count() = rows.value.size
        override fun observeCount(): Flow<Int> = rows.map { it.size }
        override suspend fun countModelKind(modelKind: String) = rows.value.count { it.modelKind == modelKind }
        override fun observe(id: Long): Flow<GalleryItemEntity?> = rows.map { l -> l.firstOrNull { it.id == id } }
        override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }

        override suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(modelPath = modelPath, photoPath = photoPath, thumbnailPath = thumbnailPath, totalBytes = totalBytes) else it
            }
        }

        override suspend fun setLabel(id: Long, label: String?, updatedAt: Long): Int = 0

        override suspend fun setDimensions(
            id: Long, widthM: Double, widthB: Double, widthS: String, heightM: Double, heightB: Double, heightS: String,
            depthM: Double, depthB: Double, depthS: String, quality: String, modelPath: String, totalBytes: Long, updatedAt: Long,
        ): Int = 0

        override suspend fun allFileRefs() = rows.value.map { GalleryFileRefs(it.id, it.modelPath, it.photoPath, it.thumbnailPath) }

        override suspend fun delete(id: Long): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.id == id }
            return before - rows.value.size
        }
    }
}
